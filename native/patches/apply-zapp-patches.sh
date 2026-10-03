#!/usr/bin/env bash
# Применение zapp-патчей к amnezia-box. Идемпотентно: повторный запуск --
# no-op, поэтому звать можно на каждой сборке.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$HERE/../src/amnezia-box}"
[ -d "$SRC/protocol/tun" ] || { echo "нет исходников amnezia-box: $SRC" >&2; exit 1; }
if ! grep -q "tunFdFromEnv" "$SRC/protocol/tun/inbound.go" 2>/dev/null; then
  patch -p1 -d "$SRC" < "$HERE/zapp-tun-fd.patch"
fi
cp "$HERE/zapp-tun-fd.go" "$SRC/protocol/tun/zapp_fd.go"
if ! grep -q "newZappNetworkUpdateMonitor" "$SRC/route/network.go" 2>/dev/null; then
  patch -p1 -d "$SRC" < "$HERE/zapp-netlink-monitor.patch"
fi
cp "$HERE/zapp-netlink-monitor.go" "$SRC/route/zapp_netlink_monitor.go"
# Impending() форка паникует на любой deprecated-отчёт: версия 1.260910.0
# даёт minor 260910, что больше любого ScheduledVersion (см. патч).
if ! grep -q "ZAPP-deprecated-impending" "$SRC/experimental/deprecated/constants.go" 2>/dev/null; then
  patch -p1 -d "$SRC" < "$HERE/zapp-deprecated-impending.patch"
fi
