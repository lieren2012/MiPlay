//! Linux USB Gadget adapter boundary.
//!
//! This crate deliberately does not fake USB Device support on Windows. It exposes
//! a preflight API so phase 2 can fail early when no UDC is available.

use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdcController {
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinuxPreflight {
    pub kernel: String,
    pub udc_controllers: Vec<UdcController>,
    pub configfs_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinuxAdapterError {
    message: String,
}

impl LinuxAdapterError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for LinuxAdapterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for LinuxAdapterError {}

#[cfg(target_os = "linux")]
pub fn preflight() -> Result<LinuxPreflight, LinuxAdapterError> {
    use std::fs;

    let mut controllers = Vec::new();
    for entry in fs::read_dir("/sys/class/udc")
        .map_err(|error| LinuxAdapterError::new(format!("cannot read /sys/class/udc: {error}")))?
    {
        let entry = entry.map_err(|error| {
            LinuxAdapterError::new(format!("cannot inspect /sys/class/udc entry: {error}"))
        })?;
        controllers.push(UdcController {
            name: entry.file_name().to_string_lossy().into_owned(),
        });
    }
    controllers.sort_by(|left, right| left.name.cmp(&right.name));

    if controllers.is_empty() {
        return Err(LinuxAdapterError::new(
            "no USB Device Controller was found under /sys/class/udc",
        ));
    }

    let configfs_path = "/sys/kernel/config/usb_gadget".to_owned();
    if !std::path::Path::new(&configfs_path).is_dir() {
        return Err(LinuxAdapterError::new(format!(
            "USB gadget configfs is not mounted at {configfs_path}"
        )));
    }

    let kernel = fs::read_to_string("/proc/sys/kernel/osrelease")
        .unwrap_or_else(|_| "unknown".into())
        .trim()
        .to_owned();

    Ok(LinuxPreflight {
        kernel,
        udc_controllers: controllers,
        configfs_path,
    })
}

#[cfg(not(target_os = "linux"))]
pub fn preflight() -> Result<LinuxPreflight, LinuxAdapterError> {
    Err(LinuxAdapterError::new(
        "USB Device/OTG preflight requires Linux and a board with a UDC; this host is not a target board",
    ))
}

#[cfg(test)]
mod tests {

    #[test]
    fn non_linux_host_reports_clear_error() {
        #[cfg(not(target_os = "linux"))]
        {
            let error =
                super::preflight().expect_err("Windows laptop must not claim USB Device support");
            assert!(error.message().contains("requires Linux"));
        }
    }
}
