#!/usr/bin/env bash
# Упаковка нативных бинарей в jniLibs Android-приложения:
#   APP/app/src/main/jniLibs/<abi>/lib<tool>.so
#
# Каталог jniLibs, а не assets, по одной причине: SELinux запрещает
# исполнять файл из app data (avc: denied { execute_no_trans } для
# app_data_file), поэтому запуск бинаря из filesDir невозможен в принципе.
# Нативные библиотеки раскладываются системой при установке в
# nativeLibraryDir, и оттуда они исполняемы: это тот же механизм, которым
# работают бинари-приложения вроде Termux и v2rayNG.
#
# Файлы кладутся сырыми: сжатие в APK делает AGP (useLegacyPackaging),
# а в каталог распакованных библиотек они приходят обычными файлами.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
DEST="$HERE/../APP/app/src/main/jniLibs"
TOOLS=(nfqws goodbyedpi wg box openvpn tor lyrebird)
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

rm -rf "$DEST"
for abi in "${ABIS[@]}"; do
  mkdir -p "$DEST/$abi"
  for t in "${TOOLS[@]}"; do
    src="$OUT/$abi/$t"
    [ -x "$src" ] || { echo "НЕТ: $src" >&2; exit 1; }
    # Имя обязано начинаться с lib и заканчиваться .so -- иначе AGP
    # считает файл не нативной библиотекой и не положит в APK.
    cp -f "$src" "$DEST/$abi/lib$t.so"
    chmod 755 "$DEST/$abi/lib$t.so"
  done
  printf "%-12s " "$abi"
  du -ch "$DEST/$abi"/*.so | tail -1 | cut -f1
done
echo "---"
du -ch "$DEST"/*/*.so | tail -1 | cut -f1 | xargs echo "итого jniLibs:"
