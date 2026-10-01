#!/usr/bin/env bash
# ============================================================
# ZAPP: автосборка всех платформ (Android + Desktop).
#
#  Запуск:  ./APP/build-all.sh [release|dev|linux]
#    release  - релиз: debug+release APK + desktop uber-JAR (по умолчанию)
#    dev      - быстрая: только debug APK + desktop uber-JAR
#    linux    - Linux-форматы: AppImage + .deb
#
#  Код, дизайн и функционал общие (composeApp). Один набор исходников --
#  Android и десктоп.
#
#  Java: ищем полный JDK 21 (нужен jpackage для createDistributable).
#        JBR из Android Studio содержит jpackage, но Gradle-демон держит JBR,
#        и на нём сборка desktop-дистрибутива падает -- поэтому JAVA_HOME
#        должен указывать на полный JDK.
#
#  Публикуемость: сборка на NixOS вписывает /nix/store в launcher, .desktop
#  и конфигурацию jlink/jpackage. На debian/ubuntu/windows такие файлы не
#  работают. Публиковать можно только то, что прошло проверку: имена
#  файлов в PUBLISHABLE. Полные публикуемые дистрибутивы собирает CI
#  (.github/workflows/build.yml) на ubuntu и windows.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-release}"

find_jdk_with_jpackage() {
  for d in /nix/store/*openjdk-21*/ /nix/store/*openjdk-17*/; do
    [ -x "$d/bin/jpackage" ] && { echo "${d%/}"; return; }
  done
  [ -x "/usr/lib/jvm/java/bin/jpackage" ] && { echo "/usr/lib/jvm/java"; return; }
  [ -x "$HOME/.jdks/java/bin/jpackage" ] && { echo "$HOME/.jdks/java"; return; }
  return 1
}

if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/jpackage" ]; then
  # Указанный JAVA_HOME не даёт jpackage, а он нужен для дистрибутива.
  unset JAVA_HOME
fi

if [ -z "${JAVA_HOME:-}" ]; then
  if JDK=$(find_jdk_with_jpackage 2>/dev/null); then
    export JAVA_HOME="$JDK"
  else
    echo "[ZAPP] JAVA_HOME не задан, полный JDK с jpackage не найден" >&2
    exit 1
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "[ZAPP] JAVA_HOME=$JAVA_HOME"
fi

# jlink и jpackage на NixOS требуют objcopy, а его нет в PATH по умолчанию.
if ! command -v objcopy > /dev/null 2>&1; then
  for d in /nix/store/*binutils-*/; do
    if [ -x "$d/bin/objcopy" ]; then
      export PATH="$d/bin:$PATH"
      echo "[ZAPP] binutils=$d"
      break
    fi
  done
fi

# .deb (jpackage DEB-бандлер) требует fakeroot и dpkg-deb -- на NixOS их тоже
# нет в PATH, и без них задача падает с "Invalid or unsupported type: [deb]".
if [ "$MODE" = "linux" ] || [ "$MODE" = "release" ]; then
  if ! command -v fakeroot > /dev/null 2>&1; then
    for d in /nix/store/*fakeroot-*/; do
      if [ -x "$d/bin/fakeroot" ]; then
        export PATH="$d/bin:$PATH"
        echo "[ZAPP] fakeroot=$d"
        break
      fi
    done
  fi
  if ! command -v dpkg-deb > /dev/null 2>&1; then
    for d in /nix/store/*dpkg-*/; do
      if [ -x "$d/bin/dpkg-deb" ]; then
        export PATH="$d/bin:$PATH"
        echo "[ZAPP] dpkg=$d"
        break
      fi
    done
  fi
fi

echo "[ZAPP] === Сборка $(date -u +%FT%TZ) / mode=$MODE ==="
case "$MODE" in
  dev)
    ./gradlew :app:assembleDebug :desktopApp:packageUberJarForCurrentOS
    ;;
  release)
    ./gradlew :app:assembleDebug :app:assembleRelease \
              :desktopApp:createDistributable :desktopApp:packageUberJarForCurrentOS
    ;;
  linux)
    ./gradlew :desktopApp:packageAppImage :desktopApp:packageDeb
    ;;
  *)
    echo "Неизвестный режим: $MODE (release|dev|linux)" >&2
    exit 1
    ;;
esac

DIST="build/dist"
mkdir -p "$DIST"

cp app/build/outputs/apk/debug/app-debug.apk "$DIST/ZAPP-android-debug.apk"
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  cp app/build/outputs/apk/release/app-release.apk "$DIST/ZAPP-android-release.apk"
  echo "[ZAPP] release APK подписан (signing/keystore.properties)"
elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
  cp app/build/outputs/apk/release/app-release-unsigned.apk "$DIST/ZAPP-android-release-unsigned.apk"
  echo "[ZAPP] ВНИМАНИЕ: release APK без подписи (нет signing/keystore.properties)"
fi

DESKTOP_JAR=$(ls desktopApp/build/compose/jars/*.jar 2>/dev/null | head -1 || true)
[ -n "$DESKTOP_JAR" ] && cp "$DESKTOP_JAR" "$DIST/ZAPP-desktop-$(uname -m).jar"

# Сырой jpackage .deb непригоден: в нём нет Depends на реальные библиотеки,
# нет .desktop и иконки. Дополняем scripts/finish-deb.sh, в dist кладём
# только результат. Именно *_zapp.deb: общий *.deb подхватывает уже
# дополненный, и повторный прогон дал бы *_zapp_zapp.deb.
DEB_DIR=desktopApp/build/compose/binaries/main/deb
if [ -d "$DEB_DIR" ]; then
  mapfile -t RAWS < <(find "$DEB_DIR" -maxdepth 1 -type f -name '*_amd64.deb' ! -name '*_zapp.deb')
  if [ "${#RAWS[@]}" -eq 1 ]; then
    if ../scripts/finish-deb.sh "${RAWS[0]}"; then
      cp "${RAWS[0]%.deb}_zapp.deb" "$DIST/"
      rm -f "${RAWS[0]%.deb}_zapp_zapp.deb"
    else
      echo "[ZAPP] ВНИМАНИЕ: finish-deb.sh не отработал, .deb в dist не будет" >&2
    fi
  elif [ "${#RAWS[@]}" -gt 1 ]; then
    echo "[ZAPP] ВНИМАНИЕ: сырых .deb больше одного, беру первый: ${RAWS[0]}" >&2
  fi
fi

APPIMAGE=$(ls desktopApp/build/compose/binaries/main/app/*.AppImage 2>/dev/null | head -1 || true)
[ -n "$APPIMAGE" ] && cp "$APPIMAGE" "$DIST/"

# jpackage и dpkg-deb от root оставляют root-owned файлы и ломают сборку azrael.
# Владельца не хардкодим: берём того, от кого реально запущен скрипт
# (SUDO_USER), иначе владельца репозитория.
if [ "$(id -u)" = "0" ]; then
  OWNER="${SUDO_USER:-${SUDO_USER_NAME:-}}"
  if [ -z "$OWNER" ] && command -v stat > /dev/null 2>&1; then
    OWNER=$(stat -c '%U' . 2>/dev/null || true)
  fi
  if [ -n "$OWNER" ] && [ "$OWNER" != "root" ]; then
    chown -R "$OWNER" "$DIST" 2>/dev/null || \
      echo "[ZAPP] ВНИМАНИЕ: не удалось вернуть владельца $OWNER на $DIST" >&2
  else
    echo "[ZAPP] ВНИМАНИЕ: владелец артефактов остался root ($DIST)" >&2
  fi
fi

# Публикуемость: NixOS-сборка вписывает /nix/store в launcher и конфигурацию.
# Публиковать можно только то, что прошло проверку: имена в PUBLISHABLE.
CHECKSUMS="$DIST/SHA256SUMS"
PUBLISHABLE="$DIST/PUBLISHABLE"
REJECTED="$DIST/UNPUBLISHABLE"
rm -f "$CHECKSUMS" "$PUBLISHABLE" "$REJECTED"
: > "$PUBLISHABLE"
: > "$REJECTED"

# Проверка идёт по РАСПАКОВАННОМУ содержимому. Искать байты в самом файле
# нельзя: AppImage -- это squashfs, .deb -- сжатый tar в ar, .apk и .jar --
# zip. Всё это лежит в контейнере, grep -a по файлу молча ничего не находит
# и объявляет NixOS-сборку публикуемой. Поэтому: .deb распаковывается через
# dpkg-deb -x, zip-контейнеры через unzip -p, AppImage проверяется по
# appdir, из которого он собран.
has_nix_store() {
  local f="$1" name
  name=$(basename "$f")
  case "$name" in
    *.deb)
      local tmp
      tmp=$(mktemp -d)
      if command -v dpkg-deb > /dev/null 2>&1 && dpkg-deb -x "$f" "$tmp" 2>/dev/null; then
        LC_ALL=C grep -rl -m1 '/nix/store' "$tmp" 2>/dev/null | head -1
        local found=$?
        rm -rf "$tmp"
        return $found
      fi
      rm -rf "$tmp"
      echo "[ZAPP] не смог распаковать $name, считаю непроверяемым" >&2
      return 0
      ;;
    *.AppImage)
      # AppImage: проверяем исходный appdir, из которого он собран.
      local ad="desktopApp/build/compose/binaries/main/app"
      if [ -d "$ad" ]; then
        LC_ALL=C grep -rl -m1 '/nix/store' "$ad" 2>/dev/null | head -1
        return $?
      fi
      echo "[ZAPP] нет appdir для проверки $name" >&2
      return 0
      ;;
    *.apk|*.jar|*.zip)
      if command -v unzip > /dev/null 2>&1; then
        unzip -p "$f" 2>/dev/null | LC_ALL=C grep -a -q -m1 '/nix/store' && return 0
        return 1
      fi
      echo "[ZAPP] нет unzip, $name непроверяем" >&2
      return 0
      ;;
    *)
      LC_ALL=C grep -a -q -m1 '/nix/store' "$f" 2>/dev/null
      return $?
      ;;
  esac
}

for f in "$DIST"/*; do
  [ -f "$f" ] || continue
  name=$(basename "$f")
  case "$name" in
    SHA256SUMS|PUBLISHABLE|UNPUBLISHABLE) continue ;;
  esac
  if has_nix_store "$f"; then
    echo "$name" >> "$REJECTED"
  else
    echo "$name" >> "$PUBLISHABLE"
  fi
done

( cd "$DIST" && cat PUBLISHABLE UNPUBLISHABLE | while IFS= read -r n; do
    if [ -n "$n" ]; then
      sha256sum "$n"
    fi
  done > SHA256SUMS )

if [ -s "$REJECTED" ]; then
  echo "[ZAPP] === НЕ ПУБЛИКУЕМО (внутри /nix/store): ===" >&2
  sed 's/^/  - /' "$REJECTED" >&2
  echo "[ZAPP] Публиковать только файлы из $PUBLISHABLE" >&2
  echo "[ZAPP] Полные публикуемые дистрибутивы собирает CI на ubuntu/windows" >&2
else
  echo "[ZAPP] === Все артефакты публикуемы ==="
fi

echo "[ZAPP] === Готово. Артефакты: ==="
ls -la "$DIST"
