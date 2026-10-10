param(
    [string]$Target = "",
    [int]$SoakSeconds = 1800,
    [int]$PayloadBytes = 4096
)

$ErrorActionPreference = "Stop"
$targetArgs = @()
if ($Target) { $targetArgs = @("--target", $Target) }

cargo fmt --all -- --check
cargo check --workspace @targetArgs
cargo test --workspace @targetArgs
cargo run -p bridge-lab @targetArgs -- sim --seconds $SoakSeconds --fps 60 --payload $PayloadBytes --report reports/phase1-soak.json
cargo run -p bridge-lab @targetArgs -- reconnect --cycles 100
Write-Host "Phase 1 simulation checks passed. This is not physical USB validation."
