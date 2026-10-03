#!/usr/bin/env bash
# Кросс-сборка lyrebird (Go, CGO выключен) под Android: 4 ABI.
# lyrebird -- официальная замена obfs4proxy: obfs4, obfs2/3, scramblesuit,
# snowflake, meeklite, webtunnel одним бинарём.
# Результат: native/out/<abi>/lyrebird
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
GO="${GO:-/opt/go/bin/go}"
MODULE="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird"
NDK_CLANG="${NDK_CLANG:-/home/azrael/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin}"

export PATH="$(dirname "$GO"):$PATH"
export GOPROXY="https://proxy.golang.org,direct"
export GOFLAGS="-mod=mod"
export CGO_ENABLED=0
# lyrebird опирается на go:linkname в net.zoneCache (пакет anet) --
# Go 1.23+ такую линковку отвергает. go.mod требует go 1.22 -- собираем им же.
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.22.12}"

# abi -> GOARCH [GOARM]
ABIS=(
  "arm64-v8a arm64 0"
  "armeabi-v7a arm 7"
  "x86 386 0"
  "x86_64 amd64 0"
)

echo "module: $MODULE"

for entry in "${ABIS[@]}"; do
  read -r abi arch goarm <<<"$entry"
  echo "=== $abi (GOARCH=$arch GOARM=$goarm) ==="
  out="$OUT/$abi/lyrebird"
  # Некоторым ABI android pie требует внешнюю линковку (android/386, android/arm,
  # android/amd64), а внешняя линковка требует cgo -- для них поднимаем clang
  # из NDK. Остальные собираются с CGO_ENABLED=0 и линкуются во внутренний.
  if [[ "$arch" == "386" ]]; then
    CC="$NDK_CLANG/i686-linux-android33-clang" \
    GOOS=android GOARCH=386 CGO_ENABLED=1 \
      "$GO" build -trimpath -ldflags "-s -w" -o "$out" "$MODULE/cmd/lyrebird"
  elif [[ "$goarm" != "0" ]]; then
    CC="$NDK_CLANG/armv7a-linux-androideabi33-clang" \
    GOOS=android GOARCH=arm GOARM="$goarm" CGO_ENABLED=1 \
      "$GO" build -trimpath -ldflags "-s -w" -o "$out" "$MODULE/cmd/lyrebird"
  elif [[ "$arch" == "amd64" ]]; then
    CC="$NDK_CLANG/x86_64-linux-android33-clang" \
    GOOS=android GOARCH=amd64 CGO_ENABLED=1 \
      "$GO" build -trimpath -ldflags "-s -w" -o "$out" "$MODULE/cmd/lyrebird"
  else
    GOOS=android GOARCH="$arch" \
      "$GO" build -trimpath -ldflags "-s -w" -o "$out" "$MODULE/cmd/lyrebird"
  fi
  file "$out" | head -1
done
echo "=== lyrebird готово ==="
