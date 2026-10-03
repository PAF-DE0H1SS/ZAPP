#!/usr/bin/env bash
# Кросс-сборка tor (статичные libevent + openssl из prefix-ssl) под Android.
# Результат: native/out/<abi>/tor
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
OUT="$HERE/out"
NDK="/home/azrael/Android/Sdk/ndk/28.2.13676358"
HOSTTRIPLE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=33
MAKE="/nix/store/z4y5pmpbpqh7y66pp33k51s85ask4nim-gnumake-4.4.1/bin/make"
JOBS="$(nproc 2>/dev/null || echo 4)"
TOR_VER=0.4.9.13
LIBEVENT_VER=2.1.12-stable

export PATH="$HOSTTRIPLE/bin:$(dirname "$MAKE"):$PATH"
export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip

ABIS=(
  "arm64-v8a aarch64-linux-android aarch64-linux-android"
  "armeabi-v7a arm-linux-androideabi armv7a-linux-androideabi"
  "x86 i686-linux-android i686-linux-android"
  "x86_64 x86_64-linux-android x86_64-linux-android"
)

fetch() { [ -f "$SRC/$2" ] || curl -sfL -o "$SRC/$2" "$1"; }
fetch "https://dist.torproject.org/tor-${TOR_VER}.tar.gz" "tor-${TOR_VER}.tar.gz"
fetch "https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VER}/libevent-${LIBEVENT_VER}.tar.gz" \
  "libevent-${LIBEVENT_VER}.tar.gz"
for f in "$SRC"/tor-*.tar.gz "$SRC"/libevent-*.tar.gz; do
  base="$(basename "$f" .tar.gz)"
  [ -d "$SRC/$base" ] || tar xzf "$f" -C "$SRC"
done

for entry in "${ABIS[@]}"; do
  read -r abi host cprefix <<<"$entry"
  echo "=== $abi ==="

  # --- libevent ---
  evdir="$OUT/$abi/prefix-event"
  if [ ! -f "$evdir/lib/libevent_core.a" ]; then
    bdir="$OUT/$abi/build-libevent"
    rm -rf "$bdir"; mkdir -p "$bdir"
    cp -r "$SRC/libevent-${LIBEVENT_VER}/." "$bdir/"
    find "$bdir" -type f -exec touch -d "2020-01-01 00:00:00" {} +
    cd "$bdir"
    ./configure --host="$host" --prefix="$evdir" \
      --disable-shared --enable-static \
      --disable-openssl --disable-samples --disable-regress \
      --disable-libmount --disable-debug-mode \
      CC="$HOSTTRIPLE/bin/${cprefix}${API}-clang" \
      CFLAGS="-Os -D_FORTIFY_SOURCE=2"
    "$MAKE" -j"$JOBS"
    "$MAKE" install
    cd "$HERE"
  fi
  ls "$evdir/lib"/libevent_*.a | head -4

  # --- tor ---
  if [ ! -x "$OUT/$abi/tor" ]; then
    bdir="$OUT/$abi/build-tor"
    rm -rf "$bdir"; mkdir -p "$bdir"
    cp -r "$SRC/tor-${TOR_VER}/." "$bdir/"
    find "$bdir" -type f -exec touch -d "2020-01-01 00:00:00" {} +
    cd "$bdir"
    vssl="$OUT/$abi/prefix-ssl"
    vsdir="$vssl"; [ -d "$vssl/lib64" ] && vsdir="$vssl/lib64"
    PKG_CONFIG_LIBDIR="$evdir/lib/pkgconfig:$vsdir/pkgconfig" \
    ./configure --host="$host" \
      --disable-tool-name-check \
      --disable-asciidoc --disable-unittests --disable-gpl \
      --disable-systemd --disable-lz4 --disable-zstd \
      --with-libevent-dir="$evdir" \
      --with-openssl-dir="$vsdir" \
      CC="$HOSTTRIPLE/bin/${cprefix}${API}-clang" \
      CFLAGS="-Os -D_FORTIFY_SOURCE=2" \
      LDFLAGS="-L$evdir/lib -L$vsdir/lib -L$vsdir/lib64"
    "$MAKE" -j"$JOBS"
    install -m 755 src/app/tor "$OUT/$abi/tor"
    cd "$HERE"
  fi
  file "$OUT/$abi/tor" | head -1
done
echo "=== tor готово ==="
