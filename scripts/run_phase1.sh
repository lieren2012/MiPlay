#!/usr/bin/env sh
set -eu

SOAK_SECONDS="${1:-1800}"
PAYLOAD_BYTES="${2:-4096}"

cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
cargo run -p bridge-lab -- sim \
  --seconds "$SOAK_SECONDS" \
  --fps 60 \
  --payload "$PAYLOAD_BYTES" \
  --report reports/phase1-soak.json
cargo run -p bridge-lab -- reconnect --cycles 100
echo "Phase 1 simulation checks passed. This is not physical USB validation."
