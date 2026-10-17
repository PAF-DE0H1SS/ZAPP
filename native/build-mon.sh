#!/usr/bin/env bash
# Сборка zappmon -- Rust-монитора соединений для десктопа.
#
# Демон собирается под хост (не кросс-компиляция): он нужен десктопному
# приложению и ищется им в native/out/<хост>/zappmon или в PATH.
# Выход: native/out/<хост>/zappmon.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# Линкер: на NixOS `cc` вне PATH, пока не поднят stdenv.
if command -v cc >/dev/null 2>&1; then
    cargo build --release --manifest-path "$HERE/mon/Cargo.toml"
else
    nix-shell -p gcc --run "cargo build --release --manifest-path '$HERE/mon/Cargo.toml'"
fi

ARCH="$(uname -m)"
mkdir -p "$HERE/out/$ARCH"
cp "$HERE/mon/target/release/zappmon" "$HERE/out/$ARCH/zappmon"
strip "$HERE/out/$ARCH/zappmon" 2>/dev/null || true
echo "native/out/$ARCH/zappmon: $(wc -c < "$HERE/out/$ARCH/zappmon") bytes"
