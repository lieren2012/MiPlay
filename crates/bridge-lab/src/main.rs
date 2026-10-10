use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use bridge_core::{
    run_reconnect_cycles, run_simulation, BridgeState, SimulationConfig, SimulationReport,
};
use bridge_iccoa::{
    run_iccoa_reconnect_cycles, run_iccoa_soak, IccoaSimConfig, IccoaSoakReport, IccoaState,
};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Command {
    Sim(SimArgs),
    Reconnect { cycles: u64 },
    Iccoa(IccoaArgs),
    Preflight,
    Help,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SimArgs {
    seconds: u64,
    fps: u32,
    payload_bytes: usize,
    report: Option<PathBuf>,
}

impl Default for SimArgs {
    fn default() -> Self {
        Self {
            seconds: 30,
            fps: 60,
            payload_bytes: 4096,
            report: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct IccoaArgs {
    seconds: u64,
    fps: u32,
    cycles: u64,
    report: Option<PathBuf>,
}

impl Default for IccoaArgs {
    fn default() -> Self {
        Self {
            seconds: 30,
            fps: 60,
            cycles: 100,
            report: None,
        }
    }
}

fn main() -> ExitCode {
    match parse_args(env::args().skip(1)) {
        Ok(Command::Help) => {
            print_help();
            ExitCode::SUCCESS
        }
        Ok(Command::Preflight) => run_preflight(),
        Ok(Command::Reconnect { cycles }) => run_reconnect(cycles),
        Ok(Command::Sim(args)) => run_sim(args),
        Ok(Command::Iccoa(args)) => run_iccoa(args),
        Err(message) => {
            eprintln!("error: {message}");
            eprintln!("run `bridge-lab --help` for usage");
            ExitCode::from(2)
        }
    }
}

fn parse_args(args: impl Iterator<Item = String>) -> Result<Command, String> {
    let args = args.collect::<Vec<_>>();
    if args.is_empty() || args[0] == "--help" || args[0] == "-h" {
        return Ok(Command::Help);
    }

    match args[0].as_str() {
        "sim" => parse_sim(&args[1..]).map(Command::Sim),
        "iccoa" => parse_iccoa(&args[1..]).map(Command::Iccoa),
        "reconnect" => parse_reconnect(&args[1..]).map(|cycles| Command::Reconnect { cycles }),
        "preflight" => {
            if args.len() == 1 {
                Ok(Command::Preflight)
            } else {
                Err("preflight does not accept arguments".into())
            }
        }
        other => Err(format!("unknown command `{other}`")),
    }
}

fn parse_sim(args: &[String]) -> Result<SimArgs, String> {
    let mut parsed = SimArgs::default();
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--seconds" => {
                parsed.seconds = parse_number(args, &mut index, "--seconds")?;
            }
            "--fps" => {
                parsed.fps = parse_number(args, &mut index, "--fps")?;
            }
            "--payload" => {
                parsed.payload_bytes = parse_number(args, &mut index, "--payload")?;
            }
            "--report" => {
                index += 1;
                let value = args
                    .get(index)
                    .ok_or_else(|| "--report requires a path".to_owned())?;
                parsed.report = Some(PathBuf::from(value));
            }
            unknown => return Err(format!("unknown sim argument `{unknown}`")),
        }
        index += 1;
    }
    Ok(parsed)
}

fn parse_reconnect(args: &[String]) -> Result<u64, String> {
    if args.is_empty() {
        return Ok(100);
    }
    if args.len() != 2 || args[0] != "--cycles" {
        return Err("reconnect expects `--cycles <count>`".into());
    }
    args[1]
        .parse()
        .map_err(|_| "reconnect cycle count must be an integer".into())
}

fn parse_iccoa(args: &[String]) -> Result<IccoaArgs, String> {
    let mut parsed = IccoaArgs::default();
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--seconds" => {
                parsed.seconds = parse_number(args, &mut index, "--seconds")?;
            }
            "--fps" => {
                parsed.fps = parse_number(args, &mut index, "--fps")?;
            }
            "--cycles" => {
                parsed.cycles = parse_number(args, &mut index, "--cycles")?;
            }
            "--report" => {
                index += 1;
                let value = args
                    .get(index)
                    .ok_or_else(|| "--report requires a path".to_owned())?;
                parsed.report = Some(PathBuf::from(value));
            }
            unknown => return Err(format!("unknown iccoa argument `{unknown}`")),
        }
        index += 1;
    }
    Ok(parsed)
}

fn parse_number<T>(args: &[String], index: &mut usize, name: &str) -> Result<T, String>
where
    T: std::str::FromStr,
{
    *index += 1;
    let value = args
        .get(*index)
        .ok_or_else(|| format!("{name} requires a value"))?;
    value
        .parse()
        .map_err(|_| format!("{name} has an invalid value `{value}`"))
}

fn run_sim(args: SimArgs) -> ExitCode {
    let mut config = SimulationConfig::lab_default();
    config.duration_secs = args.seconds;
    config.fps = args.fps;
    config.media_payload_bytes = args.payload_bytes;

    match run_simulation(&config) {
        Ok(report) => {
            print_report(&report, config.duration_secs, config.fps);
            if let Some(path) = args.report {
                if let Err(error) = write_report(&path, &report) {
                    eprintln!("failed to write report {}: {error}", path.display());
                    return ExitCode::FAILURE;
                }
                println!("report: {}", path.display());
            }
            if report.final_state == BridgeState::Active {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Err(error) => {
            eprintln!("simulation failed: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run_reconnect(cycles: u64) -> ExitCode {
    match run_reconnect_cycles(cycles) {
        Ok(report) => {
            println!("reconnect cycles: {}", report.requested_cycles);
            println!("CarPlay connections: {}", report.carplay_connections);
            println!("CarLife connections: {}", report.carlife_connections);
            println!("disconnects: {}", report.disconnects);
            println!("final state: {:?}", report.final_state);
            if report.final_state == BridgeState::Active {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Err(error) => {
            eprintln!("reconnect simulation failed: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run_preflight() -> ExitCode {
    match bridge_linux::preflight() {
        Ok(result) => {
            println!("kernel: {}", result.kernel);
            println!("configfs: {}", result.configfs_path);
            for controller in result.udc_controllers {
                println!("UDC: {}", controller.name);
            }
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("USB Device preflight unavailable: {error}");
            eprintln!("use an Ubuntu VM/board with a real USB Device Controller");
            ExitCode::FAILURE
        }
    }
}

fn print_report(report: &SimulationReport, seconds: u64, fps: u32) {
    println!("mode: simulation-only (no physical USB validation)");
    println!("requested frames: {}", report.requested_frames);
    println!("forwarded frames: {}", report.forwarded_frames);
    println!("media: {} bytes", report.media_bytes);
    println!("audio packets: {}", report.audio_packets);
    println!("input events: {}", report.input_events);
    println!("wire round trips: {}", report.wire_round_trips);
    println!("wall time: {:.3} s", report.wall_time.as_secs_f64());
    println!(
        "simulation throughput: {:.0} frames/s",
        report.frames_per_second()
    );
    println!("final state: {:?}", report.final_state);
    println!(
        "phase 1 soak: {}",
        if report.meets_phase1_soak(seconds, fps) {
            "PASS"
        } else {
            "FAIL"
        }
    );
}

fn write_report(path: &Path, report: &SimulationReport) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)?;
        }
    }
    let json = format!(
        concat!(
            "{{\n",
            "  \"mode\": \"simulation-only\",\n",
            "  \"requested_frames\": {},\n",
            "  \"forwarded_frames\": {},\n",
            "  \"media_bytes\": {},\n",
            "  \"audio_packets\": {},\n",
            "  \"input_events\": {},\n",
            "  \"wire_round_trips\": {},\n",
            "  \"wall_time_ms\": {},\n",
            "  \"final_state\": \"{:?}\"\n",
            "}}\n"
        ),
        report.requested_frames,
        report.forwarded_frames,
        report.media_bytes,
        report.audio_packets,
        report.input_events,
        report.wire_round_trips,
        report.wall_time.as_millis(),
        report.final_state,
    );
    fs::write(path, json)
}

fn run_iccoa(args: IccoaArgs) -> ExitCode {
    let mut config = IccoaSimConfig::lab_default();
    config.duration_secs = args.seconds;
    config.fps = args.fps;

    let report = match run_iccoa_soak(&config) {
        Ok(report) => report,
        Err(error) => {
            eprintln!("ICCOA soak failed: {error}");
            return ExitCode::FAILURE;
        }
    };
    print_iccoa_report(&report, config.duration_secs, config.fps);

    let reconnect = match run_iccoa_reconnect_cycles(args.cycles) {
        Ok(reconnect) => reconnect,
        Err(error) => {
            eprintln!("ICCOA reconnect soak failed: {error}");
            return ExitCode::FAILURE;
        }
    };
    println!("reconnect cycles: {}", reconnect.requested_cycles);
    println!("peers found: {}", reconnect.peers_found);
    println!("handshakes: {}", reconnect.handshakes);
    println!("streaming sessions: {}", reconnect.streaming_sessions);
    println!("peers lost: {}", reconnect.peers_lost);
    println!("final state: {:?}", reconnect.final_state);

    if let Some(path) = args.report {
        if let Err(error) = write_iccoa_report(&path, &report, &reconnect) {
            eprintln!("failed to write report {}: {error}", path.display());
            return ExitCode::FAILURE;
        }
        println!("report: {}", path.display());
    }

    if report.final_state == IccoaState::Streaming && reconnect.final_state == IccoaState::Idle {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

fn print_iccoa_report(report: &IccoaSoakReport, seconds: u64, fps: u32) {
    println!("mode: ICCOA Carlink head-unit simulation (lab codec only)");
    println!("requested frames: {}", report.requested_frames);
    println!("forwarded frames: {}", report.forwarded_frames);
    println!("video: {} bytes", report.video_bytes);
    println!("audio packets: {}", report.audio_packets);
    println!("audio: {} bytes", report.audio_bytes);
    println!("microphone packets: {}", report.microphone_packets);
    println!("upstream commands: {}", report.upstream_commands);
    println!("wall time: {:.3} s", report.wall_time.as_secs_f64());
    println!("throughput: {:.0} frames/s", report.frames_per_second());
    println!("final state: {:?}", report.final_state);
    println!(
        "ICCOA soak: {}",
        if report.meets_soak(seconds, fps) {
            "PASS"
        } else {
            "FAIL"
        }
    );
}

fn write_iccoa_report(
    path: &Path,
    report: &IccoaSoakReport,
    reconnect: &bridge_iccoa::IccoaReconnectReport,
) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent)?;
        }
    }
    let json = format!(
        concat!(
            "{{\n",
            "  \"mode\": \"iccoa-lab-codec-only\",\n",
            "  \"requested_frames\": {},\n",
            "  \"forwarded_frames\": {},\n",
            "  \"video_bytes\": {},\n",
            "  \"audio_packets\": {},\n",
            "  \"microphone_packets\": {},\n",
            "  \"upstream_commands\": {},\n",
            "  \"reconnect_cycles\": {},\n",
            "  \"wall_time_ms\": {},\n",
            "  \"final_state\": \"{:?}\"\n",
            "}}\n"
        ),
        report.requested_frames,
        report.forwarded_frames,
        report.video_bytes,
        report.audio_packets,
        report.microphone_packets,
        report.upstream_commands,
        reconnect.requested_cycles,
        report.wall_time.as_millis(),
        report.final_state,
    );
    fs::write(path, json)
}

fn print_help() {
    println!(
        "bridge-lab - clean-room CarPlay/CarLife bridge simulation\n\n\
         USAGE:\n\
           bridge-lab sim [--seconds N] [--fps N] [--payload BYTES] [--report PATH]\n\
           bridge-lab reconnect [--cycles N]\n\
           bridge-lab iccoa [--seconds N] [--fps N] [--cycles N] [--report PATH]\n\
           bridge-lab preflight\n\n\
         NOTES:\n\
           `sim` and `reconnect` do not use a car or USB device.\n\
           `iccoa` runs the ICCOA Carlink head-unit path with a lab-only codec.\n\
           `preflight` requires Linux and a real USB Device Controller."
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_sim_arguments() {
        let args = vec![
            "sim".into(),
            "--seconds".into(),
            "1800".into(),
            "--fps".into(),
            "60".into(),
            "--payload".into(),
            "4096".into(),
        ];
        let command = parse_args(args.into_iter()).unwrap();
        assert_eq!(
            command,
            Command::Sim(SimArgs {
                seconds: 1800,
                fps: 60,
                payload_bytes: 4096,
                report: None,
            })
        );
    }

    #[test]
    fn defaults_to_help() {
        assert_eq!(parse_args(std::iter::empty()).unwrap(), Command::Help);
    }

    #[test]
    fn rejects_unknown_arguments() {
        let args = vec!["sim".into(), "--gpu".into()];
        assert!(parse_args(args.into_iter()).is_err());
    }
}
