#!/usr/bin/env bash
# Кросс-сборка нативных бинарей ZAPP под Android (arm64-v8a, armeabi-v7a, x86_64).
#
# Собираются статически в себя (libmnl/libnfnetlink/libnetfilter_queue) и
# динамически в bionic (-lz, -llog из NDK):
#   nfqws      -- ядро zapret (bol-van/zapret), NFQUEUE-обход DPI
#   goodbyedpi -- Linux-порт (wickstudio/GoodbyeDPI-Linux), тот же CLI
#   wg         -- wireguard-tools, управление интерфейсом wireguard
#
# Результат: native/out/<abi>/{nfqws,goodbyedpi,wg} → кладётся в assets.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"
OUT="$HERE/out"
NDK="/home/azrael/Android/Sdk/ndk/28.2.13676358"
HOSTTRIPLE="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=33
MAKE="/nix/store/z4y5pmpbpqh7y66pp33k51s85ask4nim-gnumake-4.4.1/bin/make"
JOBS="$(nproc 2>/dev/null || echo 4)"

export PATH="$HOSTTRIPLE/bin:$(dirname "$MAKE"):/opt/go/bin:$PATH"
# NDK не даёт триплетных binutils: ar/ranlib/nm живут в llvm-*.
export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip
# Принудительный первый include -- bionic-совместимость (см. bionic-compat.h).
COMPAT="-include $HERE/bionic-compat.h"

# abi | autoconf-хост | префикс компилятора NDK
ABIS=(
  "arm64-v8a aarch64-linux-android aarch64-linux-android"
  "armeabi-v7a arm-linux-androideabi armv7a-linux-androideabi"
  "x86 i686-linux-android i686-linux-android"
  "x86_64 x86_64-linux-android x86_64-linux-android"
)

cc_for()  { echo "$HOSTTRIPLE/bin/$3$API-clang"; }
cxx_for() { echo "$HOSTTRIPLE/bin/$3$API-clang++"; }

build_libs() {
  local abi="$1" host="$2" cprefix="$3"
  local prefix="$OUT/$abi/prefix"
  local cc; cc="$(cc_for x "$host" "$cprefix")"
  mkdir -p "$prefix"

  build_auto() {
    local dir="$1"; shift
    local bdir="$OUT/$abi/build-$(basename "$dir")"
    rm -rf "$bdir"; mkdir -p "$bdir"; cd "$bdir"
    # shellcheck disable=SC2086
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
      "$SRC/$dir/configure" \
      --host="$host" --prefix="$prefix" \
      --disable-shared --enable-static \
      CC="$cc" CFLAGS="-Os -fPIC $COMPAT" "$@"
    "$MAKE" -j"$JOBS" install
    cd "$HERE"
  }

  build_auto libmnl-1.0.5
  build_auto libnfnetlink-1.0.2
  build_auto libnetfilter_queue-1.0.5
}

build_nfqws() {
  local abi="$1" host="$2" cprefix="$3"
  local prefix="$OUT/$abi/prefix"
  local cc; cc="$(cc_for x "$host" "$cprefix")"
  local dir="$OUT/$abi/build-nfqws"
  rm -rf "$dir"; mkdir -p "$dir"
  cp -r "$SRC/zapret/nfq/." "$dir/"
  cd "$dir"
  "$MAKE" android -j"$JOBS" \
    CC="$cc" \
    CFLAGS="-Os -I$prefix/include $COMPAT" \
    LIBS_LINUX="-L$prefix/lib -lnetfilter_queue -lnfnetlink -lmnl -lz" \
    LDFLAGS="-L$prefix/lib"
  install -m 755 nfqws "$OUT/$abi/nfqws"
  cd "$HERE"
}

build_goodbyedpi() {
  local abi="$1" host="$2" cprefix="$3"
  local prefix="$OUT/$abi/prefix"
  local cc; cc="$(cc_for x "$host" "$cprefix")"
  local dir="$OUT/$abi/build-goodbyedpi"
  rm -rf "$dir"; mkdir -p "$dir"
  cp -r "$SRC/goodbyedpi-linux/src/." "$dir/"
  cd "$dir"
  # shellcheck disable=SC2046
  "$cc" -std=c99 -O2 -D_FORTIFY_SOURCE=2 -D_GNU_SOURCE -fPIE \
    $COMPAT -I"$prefix/include" \
    *.c utils/repl_str.c \
    -L"$prefix/lib" -lnetfilter_queue -lnfnetlink -lmnl -lm \
    -pie -Wl,--gc-sections -s -o goodbyedpi
  install -m 755 goodbyedpi "$OUT/$abi/goodbyedpi"
  cd "$HERE"
}

build_wg() {
  # amneziawg-tools: форк wireguard-tools с атрибутами AmneziaWG (Jc..P6),
  # обратно совместим с обычным WireGuard (kernel-режим через netlink).
  local abi="$1" host="$2" cprefix="$3"
  local cc; cc="$(cc_for x "$host" "$cprefix")"
  local dir="$OUT/$abi/build-amneziawg-tools"
  rm -rf "$dir"; mkdir -p "$dir"
  cp -r "$SRC/amneziawg-tools/." "$dir/"
  rm -rf "$dir/.git"
  cd "$dir/src"
  "$MAKE" -j"$JOBS" wg \
    CC="$cc" \
    RUNSTATEDIR=/data/local/tmp/zapp/run \
    WIREGUARD_TOOLS_VERSION=awg-20260812 \
    WITH_WGQUICK= WITH_BASHCOMPLETION= WITH_SYSTEMDUNITS=
  install -m 755 wg "$OUT/$abi/wg"
  cd "$HERE"
}

build_box() {
  # amnezia-box: sing-box с with_awg -- статический linux-бинарь (CGO_ENABLED=0),
  # работает на Android как есть. GOARCH под ABI.
  local abi="$1" goarch="$2" goarm="$3"
  local out="$OUT/$abi/box"
  if [ -x "$out" ]; then return 0; fi
  "$HERE/patches/apply-zapp-patches.sh" "$SRC/amnezia-box"
  cd "$SRC/amnezia-box"
  env GOOS=linux GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=0 \
    GOTOOLCHAIN=local GOWORK=off \
    GOPROXY="https://proxy.golang.org,direct" \
    "$MAKE" -f Makefile.amnezia build OUTPUT="$out"
  cd "$HERE"
}

mkdir -p "$OUT" "$SRC"
# Сырьё качается при его отсутствии (в git оно не входит).
fetch() { [ -f "$SRC/$2" ] || curl -sfL -o "$SRC/$2" "$1"; }
fetch https://www.netfilter.org/projects/libmnl/files/libmnl-1.0.5.tar.bz2 libmnl-1.0.5.tar.bz2
fetch https://www.netfilter.org/projects/libnfnetlink/files/libnfnetlink-1.0.2.tar.bz2 libnfnetlink-1.0.2.tar.bz2
fetch https://www.netfilter.org/projects/libnetfilter_queue/files/libnetfilter_queue-1.0.5.tar.bz2 libnetfilter_queue-1.0.5.tar.bz2
[ -d "$SRC/zapret" ]          || git clone -q --depth 1 https://github.com/bol-van/zapret.git "$SRC/zapret"
[ -d "$SRC/goodbyedpi-linux" ] || git clone -q --depth 1 https://github.com/wickstudio/GoodbyeDPI-Linux.git "$SRC/goodbyedpi-linux"
[ -d "$SRC/amneziawg-tools" ] || git clone -q --depth 1 https://github.com/amnezia-vpn/amneziawg-tools.git "$SRC/amneziawg-tools"
[ -d "$SRC/amnezia-box" ]     || git clone -q --depth 1 -b master https://github.com/amnezia-vpn/amnezia-box.git "$SRC/amnezia-box"
# Тарболлы netfilter распаковываются один раз: configure лежит в них готовым.
for f in "$SRC"/*.tar.bz2; do
  base="$(basename "$f" .tar.bz2)"
  [ -d "$SRC/$base" ] || tar xjf "$f" -C "$SRC"
done
# Одноразовые правки исходников (идемпотентные):
# 1) старые config.sub/config.guess не знают *-linux-android -- берём свежие из libmnl;
# 2) legacy libipq-совместимость требует заголовка, которого нет в bionic, -- убираем
#    из сборки (nfqws нужен только libnetfilter_queue).
if [ -d "$SRC/libnfnetlink-1.0.2" ] && ! grep -q "linux-android" "$SRC/libnfnetlink-1.0.2/config.sub" 2>/dev/null; then
  cp "$SRC/libmnl-1.0.5/build-aux/config.sub" "$SRC/libmnl-1.0.5/build-aux/config.guess" "$SRC/libnfnetlink-1.0.2/"
fi
if grep -q "libnetfilter_queue_libipq.la" "$SRC/libnetfilter_queue-1.0.5/src/Makefile.in" 2>/dev/null; then
  sed -i 's/^lib_LTLIBRARIES = libnetfilter_queue.la libnetfilter_queue_libipq.la$/lib_LTLIBRARIES = libnetfilter_queue.la/' \
    "$SRC/libnetfilter_queue-1.0.5/src/Makefile.in"
fi
# 3) дубли определений bionic: libnfnetlink не должен повторять то, что уже
#    дал <linux/netfilter/nfnetlink.h> (включается первым через bionic-compat.h);
if grep -q "^enum nfnetlink_groups {" "$SRC/libnfnetlink-1.0.2/include/libnfnetlink/linux_nfnetlink.h" 2>/dev/null; then
  python3 - "$SRC/libnfnetlink-1.0.2/include/libnfnetlink/linux_nfnetlink.h" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old1 = "enum nfnetlink_groups {"
new1 = "#ifndef NFNLGRP_NONE\n/* bionic-заголовок уже дал эти определения -- не дублируем. */\nenum nfnetlink_groups {"
old2 = "#define NFNETLINK_V0\t0"
new2 = "#endif /* NFNLGRP_NONE */\n#define NFNETLINK_V0\t0"
assert old1 in s and old2 in s, "linux_nfnetlink.h: патч не найден"
s = s.replace(old1, new1, 1).replace(old2, new2, 1)
open(p, "w").write(s)
PYEOF
fi
# 4) union tcp_word_hdr уже определён в linux/tcp.h (bionic)
if grep -q "^union tcp_word_hdr {" "$SRC/libnetfilter_queue-1.0.5/src/extra/tcp.c" 2>/dev/null; then
  python3 - "$SRC/libnetfilter_queue-1.0.5/src/extra/tcp.c" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = """union tcp_word_hdr {
	struct tcphdr hdr;
	uint32_t  words[5];
};

#define tcp_flag_word(tp) ( ((union tcp_word_hdr *)(tp))->words[3])"""
new = """#ifndef tcp_flag_word
union tcp_word_hdr {
	struct tcphdr hdr;
	uint32_t  words[5];
};

#define tcp_flag_word(tp) ( ((union tcp_word_hdr *)(tp))->words[3])
#endif /* tcp_flag_word */"""
assert old in s, "tcp.c: патч не найден"
open(p, "w").write(s.replace(old, new, 1))
PYEOF
fi
# abi | autoconf-хост | NDK-префикс | GOARCH | GOARM
GOARCS=("arm64-v8a arm64 7" "armeabi-v7a arm 7" "x86 386 7" "x86_64 amd64 7")
for entry in "${ABIS[@]}"; do
  read -r abi host cprefix <<<"$entry"
  goarch=arm64; goarm=""
  for g in "${GOARCS[@]}"; do
    read -r gabi ga gm <<<"$g"
    [ "$gabi" = "$abi" ] && { goarch="$ga"; goarm="$gm"; }
  done
  echo "=== $abi (GOARCH=$goarch) ==="
  mkdir -p "$OUT/$abi"
  build_libs "$abi" "$host" "$cprefix"
  build_nfqws "$abi" "$host" "$cprefix"
  build_goodbyedpi "$abi" "$host" "$cprefix"
  build_wg "$abi" "$host" "$cprefix"
  build_box "$abi" "$goarch" "$goarm"
  ls -la "$OUT/$abi/nfqws" "$OUT/$abi/goodbyedpi" "$OUT/$abi/wg" "$OUT/$abi/box"
done
echo "=== готово ==="
