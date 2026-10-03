#!/usr/bin/env bash
# Кросс-сборка OpenVPN (статичный openssl) под Android: 3 ABI.
# Результат: native/out/<abi>/openvpn
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
OUT="$HERE/out"
NDK="/home/azrael/Android/Sdk/ndk/28.2.13676358"
HOSTTRIPLE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=33
MAKE="/nix/store/z4y5pmpbpqh7y66pp33k51s85ask4nim-gnumake-4.4.1/bin/make"
JOBS="$(nproc 2>/dev/null || echo 4)"
OPENSSL_VER=3.6.5
OPENVPN_VER=2.7.7

export PATH="$HOSTTRIPLE/bin:$(dirname "$MAKE"):/opt/go/bin:$PATH"
export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip
export ANDROID_NDK_ROOT="$NDK"

# abi | openssl-цель | openvpn-хост (autoconf) | NDK-префикс компилятора
ABIS=(
  "arm64-v8a android-arm64 aarch64-linux-android aarch64-linux-android"
  "armeabi-v7a android-arm arm-linux-androideabi armv7a-linux-androideabi"
  "x86 android-x86 i686-linux-android i686-linux-android"
  "x86_64 android-x86_64 x86_64-linux-android x86_64-linux-android"
)

fetch() { [ -f "$SRC/$2" ] || curl -sfL -o "$SRC/$2" "$1"; }
fetch "https://www.openssl.org/source/openssl-${OPENSSL_VER}.tar.gz" "openssl-${OPENSSL_VER}.tar.gz"
fetch "https://github.com/OpenVPN/openvpn/releases/download/v${OPENVPN_VER}/openvpn-${OPENVPN_VER}.tar.gz" "openvpn-${OPENVPN_VER}.tar.gz"
for f in "$SRC"/openssl-*.tar.gz "$SRC"/openvpn-*.tar.gz; do
  base="$(basename "$f" .tar.gz)"
  [ -d "$SRC/$base" ] || tar xzf "$f" -C "$SRC"
done
# Патч (идемпотентный): configure 2.7.x требует libcap-ng на всём *-*-linux*,
# включая linux-android. На Android'е capability-дроп не нужен -- пропускаем ветку.
python3 - "$SRC/openvpn-${OPENVPN_VER}/configure" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
if '*-*-linux-gnu*|*-*-linux-musl*)' not in s:
    i = s.find('checking for libcap-ng')
    j = s.rfind('*-*-linux*)', 0, i)
    assert i > 0 and j > 0, "openvpn configure: якорь libcap-ng не найден"
    s = s[:j] + '*-*-linux-gnu*|*-*-linux-musl*)' + s[j + len('*-*-linux*)'):]
    open(p, "w").write(s)
PYEOF

for entry in "${ABIS[@]}"; do
  read -r abi ossl_target host cprefix <<<"$entry"
  echo "=== $abi ($ossl_target) ==="
  sdir="$OUT/$abi/prefix-ssl"
  if [ ! -f "$sdir/lib64/libssl.a" ] && [ ! -f "$sdir/lib/libssl.a" ]; then
    bdir="$OUT/$abi/build-openssl"
    rm -rf "$bdir"; mkdir -p "$bdir"
    cd "$SRC/openssl-${OPENSSL_VER}"
    # Пересборка в чистый каталог: openssl не любит расшаренные build-дериктории.
    make clean >/dev/null 2>&1 || true
    ./Configure "$ossl_target" -D__ANDROID_API__=$API \
      no-shared no-tests no-apps no-docs \
      --prefix="$sdir" --openssldir="$sdir/ssl"
    make -j"$JOBS"
    make install_sw
    cd "$HERE"
  fi
  # Нормализовать путь к библиотекам (lib vs lib64)
  sslroot="$sdir"
  vdir="$sdir"; [ -d "$sdir/lib64" ] && vdir="$sdir/lib64"
  bdir="$OUT/$abi/build-openvpn"
  rm -rf "$bdir"; mkdir -p "$bdir"
  cp -r "$SRC/openvpn-${OPENVPN_VER}/." "$bdir/"
  # После cp -r метки времени сбиваются: automake решает, что надо регенерировать
  # aclocal/configure, а autotools в системе нет. Выравниваем все метки.
  find "$bdir" -type f -exec touch -d "2020-01-01 00:00:00" {} +
  cd "$bdir"
  PKG_CONFIG_LIBDIR="$vdir/pkgconfig:$sdir/lib/pkgconfig" \
    ./configure --host="$host" --prefix="$OUT/$abi/prefix-vpn" \
      --disable-shared --enable-static \
      --with-crypto-library=openssl \
      --disable-lz4 --disable-lzo --disable-plugins --disable-unit-tests \
      --disable-manpages --disable-dco --disable-libcapng \
      CC="$HOSTTRIPLE/bin/${cprefix}${API}-clang" \
      CFLAGS="-Os -D_FORTIFY_SOURCE=2" \
      LDFLAGS="-L$vdir"
  "$MAKE" -j"$JOBS"
  install -m 755 src/openvpn/openvpn "$OUT/$abi/openvpn"
  cd "$HERE"
  ls -la "$OUT/$abi/openvpn"
done
echo "=== openvpn готово ==="
