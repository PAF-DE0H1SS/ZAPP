<div align="center">

# 🛰️ ZAPP

**A cross-platform network toolkit** -- one shared UI, Android and desktop.

[![Platform: Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Windows 10/11](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat&logo=windows&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Linux](https://img.shields.io/badge/Linux-deb%20%7C%20AppImage-FCC624?style=flat&logo=linux&logoColor=black)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![CI](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml/badge.svg)](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml)
[![Made with Kotlin](https://img.shields.io/badge/Made%20with-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/UI-Compose%20Multiplatform-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![License: Free for everyone](https://img.shields.io/badge/License-free%20for%20everyone-2EA44F?style=flat)](LICENSE)

</div>

> [!IMPORTANT]
> **Status: released v2.7.0, signed.** The project builds for Android and desktop, the
> release carries five APKs per variant (four CPU architectures plus a universal one),
> an MSI and an AppImage. 1303 tests are green. See the Roadmap for what comes next.

ZAPP is a network toolkit written in Kotlin with **Compose Multiplatform**: one shared UI
for the native Android build and for desktop. The shared code lives in `APP/composeApp`, the
Android application in `APP/app`, the desktop wrapper in `APP/desktopApp`. ZAPP talks to no
server -- it is a set of local tools working on the machine it runs on.

---

## Language / Язык / 语言

<details open>
<summary><b>🇬🇧 English</b></summary>

### ✨ Overview

> [!NOTE]
> **Supported platforms** -- one codebase, two delivery formats:

| 🖥️ Platform | 📦 Delivery | 🗂️ Format |
|---|---|---|
| 📱 **Android 13+** | APK, split by ABI | `.apk` |
| 🖥️ **Windows 10/11** | MSI installer (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Build coordinates are spread over three files:

| File | Holds |
|---|---|
| `APP/gradle.properties` | `zappVersion=2.7.0` -- the one place the version is written |
| `APP/app/build.gradle.kts` | `compileSdk=37.2`, `minSdk=33`, `targetSdk=36`, `versionCode=9` |
| `APP/gradle/libs.versions.toml` | Kotlin 2.4.20, AGP 9.2.1, kotlinx-serialization 1.9.0 |
| `APP/gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 |

### 📲 Which APK to pick

Each variant ships as five APKs -- one per CPU architecture plus a universal one.
The native daemons (`tor`, `nfqws`, `goodbyedpi`, `sing-box`, ...) account for
almost the whole size, and a universal APK carries four copies of them, so a
split is about **four times smaller**:

| Your device | Download | Size |
|---|---|---|
| 📱 Most modern phones | `ZAPP-<ver>-arm64-v8a.apk` | 31 MB |
| 📱 Older / budget phones | `ZAPP-<ver>-armeabi-v7a.apk` | 30 MB |
| 📱 x86 emulator, 64-bit | `ZAPP-<ver>-x86_64.apk` | 32 MB |
| 📱 x86 emulator, 32-bit | `ZAPP-<ver>-x86.apk` | 32 MB |
| ❓ Not sure | `ZAPP-<ver>.apk` (universal) | 115 MB |

The same set exists for the **root** build, with `ZAPP-ROOT-` in front of the
name. Everything else is identical -- the root build just runs `nfqws` and
`goodbyedpi` with elevated privileges.

All five carry the same `versionCode`, so installing any one of them on top of
another is an ordinary update, never a downgrade. Android picks the APK matching
your CPU, and the split you install really is the smaller one.

### 🎯 Features

| Area | What works |
|---|---|
| 🛡️ **Tor** | bridge sources built in (top-100 lists and custom URLs), fastest-bridge auto-pick by latency, failover to a backup pool, bootstrap to 100%, local SOCKS 9050, pluggable transports via lyrebird |
| 🌐 **VPN** | WireGuard / AmneziaWG, OpenVPN and sing-box engines (vless, vmess, trojan, ss, hysteria2, tuic), grouped configs with urltest, kill-switch, VpnService permission flow |
| 🛹 **Zapret / GoodbyeDPI** | root daemons `nfqws` and `goodbyedpi` share one NFQUEUE chain (`ZAPP_DPI`), 16 strategy codes, auto-strategy picker, both run at the same time, auto-restart on crash |
| 📊 **Speed plaque** | live counters plus the active modes in brackets -- `(T\|V\|Z\|GDPI)`; the same line goes to the notification shade |
| 📈 **Speed sparkline** | rx/tx mini-graph on the status card (download red, upload blue) plus a session traffic counter right under it |
| 🧭 **DNS autoselect** | with an empty user DNS list the tunnel probes public resolvers (8.8.8.8, 1.1.1.1, 77.88.8.8, ...) with a real A-query before start and rides the first one that answers |
| 🧱 **Shared UI** | Compose Multiplatform module `:composeApp` compiled for Android and JVM desktop |
| 🧭 **Navigation** | bottom bar with six tabs on Android -- Tor, VPN, Zapret, GoodbyeDPI, GPS, Settings; GPS mock location exists only on Android, so desktop shows five |
| 🎨 **Design system** | one dark colour scheme, typography, shapes, spacing scale, ripples, starfield/glass background |
| 🌗 **Theme** | dark only -- there is no light scheme and nothing to switch to |
| ⚙️ **Settings** | grouped cards: language, welcome tour replay, performance, permissions, device, updates, traffic, presets, VPN profiles, routing, URL test, Zapret strategy and filters, GoodbyeDPI mode, logs, docs, about |
| 📖 **In-app docs** | 7 sections, 11 items, EN / RU / ZH |
| 📶 **Traffic monitor** | system counters, speed graph, session summary; native C++ reader on Android with a Kotlin fallback, opens as a separate window |
| 📦 **Packaging** | local APK / MSI / `.deb` / AppImage; APK split by ABI (four architectures plus a universal); GitHub Actions builds the same on push to `main` and on `v*` tags and publishes a signed release |
| 🧪 **Tests** | 1303 cases in `desktopTest`: WCAG contrast, locale resolution, config codecs, link parsing, sing-box config, bridge selection, sparkline math, DNS autoselect |

### 🧭 Architecture

```
ZAPP/
├── APP/                     Gradle root project
│   ├── composeApp/          shared module: Android library + JVM("desktop")
│   │   ├── commonMain/      UI, design system, models, ViewModels, core logic
│   │   ├── androidMain/     actual implementations (SharedPreferences)
│   │   ├── desktopMain/     actual implementations (filesystem)
│   │   ├── jvmShared/       JVM code shared by Android and desktop (21 files)
│   │   └── desktopTest/     test suite (1303 cases)
│   ├── app/                 Android application: manifest, MainActivity, resources
│   ├── desktopApp/          desktop launcher, jpackage packaging
│   └── build-all.sh         release | dev | linux
├── native/                  scripts and patches that build the daemons
├── ABOUT.md                 what the app does, in plain words
├── DOC.md                   technical documentation
├── DEVELOPMENT.md           how it works, forking guide, preset format
├── DOCX.docx                the same documentation as a Word file
└── LICENSE                  free and open for everyone
```

`jvmShared` holds the JVM half of the core: process spawning, sockets, file
access and daemon control are identical on Android and desktop, but cannot sit
in `commonMain`, which is not tied to JVM. Both `androidMain` and `desktopMain`
declare `dependsOn(jvmShared)`.

Two namespaces coexist on purpose: `:app` uses `xyz.azraellab.zapp`, while `:composeApp`
declares the Android library namespace `xyz.azraellab.zapp.shared`. The namespace is the
library's Android identity (generated `R`/`BuildConfig`), not the Kotlin package, and the
manifest merger rejects duplicates -- the Kotlin packages may overlap, the namespaces may not.

### 🔨 Build

Requires JDK 21 (with `jpackage` for desktop installers) and the Android SDK
with platform 37.2.

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/ZAPP[-ROOT]-<ver>-debug[-<abi>].apk
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/ZAPP[-ROOT]-<ver>[-<abi>].apk
```

Each command builds both flavours, and each flavour is split into four
architecture APKs plus a universal one.

Release signing is local-only: create a keystore and point `signing/keystore.properties`
at it, and the `release` build type is signed automatically. Without that file the release
APK is built unsigned, so forks and CI still build without secrets.
</details>

<details>
<summary><b>Desktop</b></summary>

```bash
./gradlew :desktopApp:createDistributable        # installer for the current OS
./gradlew :desktopApp:packageUberJarForCurrentOS # single jar, runs on Windows/macOS/Linux
./gradlew :desktopApp:packageMsi                 # Windows (run on a Windows host)
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

Or use the build script, which finds a full JDK and `objcopy` on its own:

```bash
./APP/build-all.sh dev     # debug APK + desktop uber-JAR
./APP/build-all.sh linux   # AppImage + .deb
./APP/build-all.sh release # debug+release APK + uber-JAR (default)
```
</details>

<details>
<summary><b>Two settings you must not remove</b></summary>

`APP/gradle.properties` pins:

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

With ABI splits, AGP starts one packager per architecture *inside a single
task* and signs them through the shared `ForkJoinPool.commonPool()`. The
packagers starve the pool, the signature step waits for workers that never
free up, and the build stops on `packageRootRelease` with no output and no
exception. `workers.max=1` serialises the splits so the pool is empty by
signing time.

Removing either flag does not slow the build down -- it makes it hang, and
reproducibly. See [DOC.md](DOC.md) for the full analysis.
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`jlink` and `jpackage` need `objcopy`, which is not in `PATH` by default -- point
`PATH` at a binutils package before building:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

**NixOS artifacts are not publishable.** A NixOS build bakes `/nix/store` paths into the
launcher interpreter and the jlink runtime, so an AppImage or `.deb` produced here will
not start on Debian, Ubuntu or Windows. `build-all.sh` therefore unpacks each artifact
and writes `build/dist/PUBLISHABLE` and `build/dist/UNPUBLISHABLE` -- the APK and the
uber-JAR pass, AppImage and `.deb` do not. Publish only what is in `PUBLISHABLE`, or
take the desktop packages from CI, which builds them on `ubuntu` and `windows`.
</details>

### 🧪 Tests

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1303 test cases across 18 files, all green: WCAG contrast for component and palette
colour pairs, system locale mapping (including the country part of a locale tag),
config codecs (`AppConfig`, `VpnConfig`), proxy link parsing, sing-box config
generation, WireGuard ini parsing, bridge selection, traffic sampling, sparkline
math, route progress, version comparison and DNS autoselect probing. `--rerun-tasks`
matters: without it an unchanged task reports `UP-TO-DATE` and the test count is
not recomputed.

The palette tests are not decoration. Replace `outline` with `#D4D4D8` and `ThemeTest`
fails, which is the point: a colour that reads as a visible border on a dark surface
must not be able to pass unnoticed.

### ✅ Roadmap

Staged as 0-13, one stage at a time, build and tests green in between. See [ABOUT.md](ABOUT.md)
for a short, abstract description.

- [x] **0** KMP skeleton, `ui/theme/`, background, ripple
- [x] **1** `ui/components/` -- design-system components with contrast tests
- [x] **2** `ui/nav/` -- bottom bar, tabs, traffic monitor as a separate window
- [x] **3** state and storage -- `AppState`, `ConfigStore`, `AppLog` instead of a separate `data/` layer
- [x] **4** onboarding: welcome tour, permission requests, icon that follows the connection state
- [x] **5** `core/` parsers: vless / vmess / trojan / ss / hysteria2 / wireguard
- [x] **6** config collection: bridge sources (built-in repos, custom URLs), background refresh, deduplication
- [x] **7** liveness: ping and archive of bridges, fastest-bridge auto-pick
- [x] **8** connect: WireGuard/AmneziaWG, OpenVPN, sing-box, Tor via bridges, kill-switch
- [x] **9** Zapret: strategies, presets, root path, auto-strategy picker; WinDivert (Windows) is out of scope for now
- [x] **10** GoodbyeDPI: root daemon running next to zapret on one NFQUEUE chain
- [ ] **11** GPS: the settings page exists, scenarios and map are not built
- [x] **12** packaging: APK / MSI / AppImage / `.deb` build locally and in CI, APK split by ABI; Nix and PKGBUILD are not
- [x] **13** contrast, polish, i18n EN/RU/ZH -- done; signed release APK -- done, CI signs the published build with a secret keystore

### 🌿 Branches

| Branch | Purpose |
|---|---|
| `main` | **stable** -- released |
| `develop` | integration -- everything merges here first |
| `feature/data-layer` | stage 3 leftovers and storage work |
| `feature/gps` | stage 11: scenarios and map |
| `feature/packaging` | stage 12: Nix, PKGBUILD |
| `release/v0.2.0` | next release stabilization |

### 📚 Docs

- [ABOUT.md](ABOUT.md) - what the app does, in plain words
- [DOC.md](DOC.md) - full technical documentation: stack, modules, build pitfalls, tests
- [DEVELOPMENT.md](DEVELOPMENT.md) - how it works, the forking guide, and the full preset format with examples
- [DOCX.docx](DOCX.docx) - the same document as a Word file (GitHub does not preview `.docx`, download it)

### 📄 License

**Free and open for everyone** -- see [LICENSE](LICENSE).

- Use it, study it, change it, fork it, sell it, ship it in a paid product -- no
  permission needed, no agreement, no revenue share, no royalties to anyone.
- No paid tier, no subscription, no paywalled features, no donations, no account,
  no telemetry. It runs entirely on your machine.
- Attribution is appreciated but not required.

ZAPP is provided "AS IS", without warranty of any kind. You are responsible for
compliance with the laws that apply to you.

</details>

<details>
<summary><b>🇷🇺 Русский</b></summary>

### ✨ Обзор

> [!NOTE]
> **Поддерживаемые платформы** -- одна кодовая база, два формата поставки:

| 🖥️ Платформа | 📦 Поставка | 🗂️ Формат |
|---|---|---|
| 📱 **Android 13+** | APK, сплит по ABI | `.apk` |
| 🖥️ **Windows 10/11** | MSI-установщик (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Координаты сборки лежат в трёх файлах:

| Файл | Что в нём |
|---|---|
| `APP/gradle.properties` | `zappVersion=2.7.0` -- единственное место, где записана версия |
| `APP/app/build.gradle.kts` | `compileSdk=37.2`, `minSdk=33`, `targetSdk=36`, `versionCode=9` |
| `APP/gradle/libs.versions.toml` | Kotlin 2.4.20, AGP 9.2.1, kotlinx-serialization 1.9.0 |
| `APP/gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 |

### 📲 Какой APK скачивать

Каждый вариант собирается в пять APK -- по одному на архитектуру плюс
универсальный. Нативные демоны (`tor`, `nfqws`, `goodbyedpi`, `sing-box`, ...)
составляют почти весь вес, а универсальный APK несёт четыре их копии, поэтому
сплит примерно **в четыре раза меньше**:

| Ваше устройство | Скачивать | Размер |
|---|---|---|
| 📱 Большинство современных телефонов | `ZAPP-<вер>-arm64-v8a.apk` | 31 МБ |
| 📱 Старые и бюджетные телефоны | `ZAPP-<вер>-armeabi-v7a.apk` | 30 МБ |
| 📱 x86-эмулятор, 64 бит | `ZAPP-<вер>-x86_64.apk` | 32 МБ |
| 📱 x86-эмулятор, 32 бита | `ZAPP-<вер>-x86.apk` | 32 МБ |
| ❓ Не уверены | `ZAPP-<вер>.apk` (универсал) | 115 МБ |

Тот же набор есть и для **root**-сборки, с префиксом `ZAPP-ROOT-` в имени.
Остальное полностью совпадает -- root-сборка лишь запускает `nfqws` и
`goodbyedpi` с повышенными правами.

У всех пяти один и тот же `versionCode`, поэтому установка любого из них поверх
любого -- обычная обновление, а не понижение. Android сам подбирает APK под
вашу архитектуру, и сплит действительно окажется меньше.

### 🎯 Возможности

| Область | Что работает |
|---|---|
| 🛡️ **Tor** | встроенные источники мостов (топ-100 списки и свои URL), автовыбор самого быстрого моста по задержке, переход на резервный пул, загрузка до 100%, локальный SOCKS 9050, pluggable-транспорты через lyrebird |
| 🌐 **VPN** | движки WireGuard / AmneziaWG, OpenVPN и sing-box (vless, vmess, trojan, ss, hysteria2, tuic), групповые конфиги с urltest, kill-switch, запрос разрешения VpnService |
| 🛹 **Zapret / GoodbyeDPI** | root-демоны `nfqws` и `goodbyedpi` на одной цепочке NFQUEUE (`ZAPP_DPI`), 16 кодов стратегий, автоподбор стратегии, работают одновременно, автоперезапуск после падения |
| 📊 **Плашка скорости** | живые счётчики и активные режимы в скобках -- `(T\|V\|Z\|GDPI)`; та же строка уходит в шторку уведомлений |
| 📈 **Спарклайн скорости** | мини-график rx/tx на карточке статуса (загрузка красная, выгрузка синяя) плюс счётчик трафика сессии прямо под ним |
| 🧭 **Автоподбор DNS** | при пустом пользовательском списке DNS туннель пробует публичные резолверы (8.8.8.8, 1.1.1.1, 77.88.8.8, ...) настоящим A-запросом до старта и едет на первом отвечающем |
| 🧱 **Общий UI** | модуль Compose Multiplatform `:composeApp`, собранный под Android и JVM-десктоп |
| 🧭 **Навигация** | нижняя панель из шести вкладок на Android -- Tor, VPN, Zapret, GoodbyeDPI, GPS, Настройки; подмена координат есть только на Android, поэтому на десктопе пять |
| 🎨 **Дизайн-система** | одна тёмная палитра, типографика, формы, шкала отступов, риплы, фон со звёздами и стеклом |
| 🌗 **Тема** | только тёмная -- светлой схемы нет и переключаться не на что |
| ⚙️ **Настройки** | сгруппированные карточки: язык, повтор приветствия, производительность, разрешения, устройство, обновления, трафик, пресеты, VPN-профили, маршрутизация, urltest, стратегия и фильтры Zapret, режим GoodbyeDPI, логи, документация, о программе |
| 📖 **Справка в приложении** | 7 разделов, 11 пунктов, EN / RU / ZH |
| 📶 **Монитор трафика** | системные счётчики, график скорости, итог сессии; нативный C++-ридер на Android с Kotlin-запаской, открывается отдельным окном |
| 📦 **Упаковка** | локальные APK / MSI / `.deb` / AppImage; сплит APK по ABI (четыре архитектуры плюс универсал); GitHub Actions собирает то же самое на пуш в `main` и на теги `v*` и публикует подписанный релиз |
| 🧪 **Тесты** | 1303 кейса в `desktopTest`: контраст WCAG, разбор локали, кодеки конфигов, разбор ссылок, конфиг sing-box, выбор мостов, математика спарклайна, автоподбор DNS |

### 🧭 Архитектура

```
ZAPP/
├── APP/                     корневой проект Gradle
│   ├── composeApp/          общий модуль: Android-библиотека + JVM("desktop")
│   │   ├── commonMain/      UI, дизайн-система, модели, ViewModel, ядро
│   │   ├── androidMain/     actual-реализации (SharedPreferences)
│   │   ├── desktopMain/     actual-реализации (файловая система)
│   │   ├── jvmShared/       общая для Android и десктопа JVM-часть (21 файл)
│   │   └── desktopTest/     тестовый набор (1303 кейса)
│   ├── app/                 Android-приложение: манифест, MainActivity, ресурсы
│   ├── desktopApp/          десктопная точка входа, упаковка jpackage
│   └── build-all.sh         release | dev | linux
├── native/                  скрипты и патчи для сборки демонов
├── ABOUT.md                 что делает приложение, простыми словами
├── DOC.md                   техническая документация
├── DEVELOPMENT.md           как устроено, гайд по форку, формат пресетов
├── DOCX.docx                та же документация в Word
└── LICENSE                  свободно для всех
```

`jvmShared` содержит JVM-половину ядра: запуск процессов, сокеты, работа с
файлами и управление демонами на Android и десктопе одинаковы, но не могут
лежать в `commonMain`, который не привязан к JVM. И `androidMain`, и
`desktopMain` объявляют `dependsOn(jvmShared)`.

Два namespace сосуществуют намеренно: `:app` использует `xyz.azraellab.zapp`,
а `:composeApp` объявляет namespace Android-библиотеки
`xyz.azraellab.zapp.shared`. Namespace -- это идентичность библиотеки в
Android (генерируемые `R`/`BuildConfig`), а не Kotlin-пакет, и merger
манифестов запрещает совпадение -- Kotlin-пакеты совпадать могут, namespace -- нет.

### 🔨 Сборка

Нужен JDK 21 (с `jpackage` для десктопных установщиков) и Android SDK с
платформой 37.2.

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/ZAPP[-ROOT]-<вер>-debug[-<abi>].apk
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/ZAPP[-ROOT]-<вер>[-<abi>].apk
```

Каждая команда собирает оба флейвора, и каждый флейвор режется на четыре
APK по архитектурам плюс универсальный.

Подпись release -- только локально: создайте keystore и укажите его в
`signing/keystore.properties`, и build type `release` подпишется сам. Без
этого файла release собирается неподписанным, поэтому форки и CI работают
без секретов.
</details>

<details>
<summary><b>Десктоп</b></summary>

```bash
./gradlew :desktopApp:createDistributable        # установщик для текущей ОС
./gradlew :desktopApp:packageUberJarForCurrentOS # один jar, запускается на Windows/macOS/Linux
./gradlew :desktopApp:packageMsi                 # Windows (запускать на Windows)
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

Либо через скрипт, который сам находит полный JDK и `objcopy`:

```bash
./APP/build-all.sh dev     # debug-APK + desktop uber-JAR
./APP/build-all.sh linux   # AppImage + .deb
./APP/build-all.sh release # debug+release APK + uber-JAR (по умолчанию)
```
</details>

<details>
<summary><b>Две настройки, которые нельзя убирать</b></summary>

В `APP/gradle.properties` зафиксировано:

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

При сплитах по ABI AGP запускает по упаковщику на каждую архитектуру
*внутри одной задачи* и подписывает их через общий
`ForkJoinPool.commonPool()`. Упаковщики высасывают пул, шаг подписи ждёт
воркеры, которые не освободятся никогда, и сборка останавливается на
`packageRootRelease` без вывода и без исключения. `workers.max=1`
сериализует сплиты, и к моменту подписи пул пуст.

Убрать эти флаги -- не ускорить сборку, а повесить её, причём
воспроизводимо. Полный разбор -- в [DOC.md](DOC.md).
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`jlink` и `jpackage` требуют `objcopy`, которого по умолчанию нет в `PATH` --
перед сборкой добавьте binutils:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

**Артефакты с NixOS публиковать нельзя.** Сборка на NixOS вписывает пути
`/nix/store` в лаунчер и рантайм jlink, поэтому AppImage и `.deb`, собранные
здесь, не запустятся на Debian, Ubuntu и Windows. `build-all.sh` поэтому
разбирает каждый артефакт и раскладывает по `build/dist/PUBLISHABLE` и
`build/dist/UNPUBLISHABLE` -- APK и uber-JAR проходят, AppImage и `.deb` --
нет. Публикуйте только то, что в `PUBLISHABLE`, либо берите десктопные
пакеты из CI, где они собираются на `ubuntu` и `windows`.
</details>

### 🧪 Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1303 тестовых кейса в 18 файлах, все зелёные: контраст пар цветов компонентов и
палитры по WCAG, сопоставление системной локали (включая страну в теге локали),
кодеки конфигов (`AppConfig`, `VpnConfig`), разбор ссылок протоколов, генерация
конфига sing-box, разбор WireGuard ini, выбор мостов, счёт трафика, математика
спарклайна, прогресс по маршруту, сравнение версий и проба автоподбора DNS.
`--rerun-tasks` обязателен: без него неизменившаяся задача отдаёт
`UP-TO-DATE`, и счётчик тестов не пересчитывается.

Тесты палитры -- не украшение. Замените `outline` на `#D4D4D8`, и `ThemeTest`
упадёт: именно это и нужно -- цвет, который читается как видимая рамка на
тёмном фоне, не должен проходить незамеченным.

### ✅ План развития

Стадии 0-13, по одной за раз, сборка и тесты зелёные между ними. Короткое
описание -- в [ABOUT.md](ABOUT.md).

- [x] **0** скелет KMP, `ui/theme/`, фон, рипл
- [x] **1** `ui/components/` -- компоненты дизайн-системы с тестами контраста
- [x] **2** `ui/nav/` -- нижняя панель, вкладки, монитор трафика отдельным окном
- [x] **3** состояние и хранение -- `AppState`, `ConfigStore`, `AppLog` вместо отдельного слоя `data/`
- [x] **4** онбординг: приветствие, запрос разрешений, значок по состоянию соединения
- [x] **5** парсеры `core/`: vless / vmess / trojan / ss / hysteria2 / wireguard
- [x] **6** сбор конфигов: источники мостов (встроенные репозитории, свои URL), фоновое обновление, дедупликация
- [x] **7** живость: пинг и архив мостов, автовыбор самого быстрого
- [x] **8** подключение: WireGuard/AmneziaWG, OpenVPN, sing-box, Tor через мосты, kill-switch
- [x] **9** Zapret: стратегии, пресеты, root-путь, автоподбор; WinDivert (Windows) пока вне рамок
- [x] **10** GoodbyeDPI: root-демон рядом с zapret на одной цепочке NFQUEUE
- [ ] **11** GPS: страница настроек есть, сценарии и карта не сделаны
- [x] **12** упаковка: APK / MSI / AppImage / `.deb` собираются локально и в CI, сплит APK по ABI; Nix и PKGBUILD нет
- [x] **13** контраст, полировка, i18n EN/RU/ZH -- сделано; подписанный release APK -- сделано, CI подписывает публикуемую сборку секретным keystore

### 🌿 Ветки

| Ветка | Назначение |
|---|---|
| `main` | **стабильная** -- релиз |
| `develop` | интеграция -- сюда сливается всё |
| `feature/data-layer` | остатки стадии 3 и работа со стораджем |
| `feature/gps` | стадия 11: сценарии и карта |
| `feature/packaging` | стадия 12: Nix, PKGBUILD |
| `release/v0.2.0` | стабилизация следующего релиза |

### 📚 Документы

- [ABOUT.md](ABOUT.md) -- что делает приложение, простыми словами
- [DOC.md](DOC.md) -- полная техническая документация: стек, модули, грабли сборки, тесты
- [DEVELOPMENT.md](DEVELOPMENT.md) -- как устроено, гайд по форку и полный формат пресетов с примерами
- [DOCX.docx](DOCX.docx) -- та же документация в Word (GitHub не показывает `.docx`, скачайте файл)

### 📄 Лицензия

**Свободно для всех** -- см. [LICENSE](LICENSE).

- Используйте, изучайте, меняйте, форкайте, продавайте, включайте в платный
  продукт -- разрешения не нужно, соглашений нет, долей с выручки и роялти
  никому нет.
- Нет платного уровня, нет подписки, нет функций за платной стеной, нет
  пожертвований, нет аккаунта, нет телеметрии. Всё работает на вашей машине.
- Упоминание авторства приятно, но не обязательно.

ZAPP предоставляется «КАК ЕСТЬ», без каких-либо гарантий. Ответственность за
соответствие законам, действующим в вашей юрисдикции, несёте вы.

</details>

<details>
<summary><b>🇨🇳 中文</b></summary>

### ✨ 概述

> [!NOTE]
> **支持平台** -- 同一套代码，两种交付格式：

| 🖥️ 平台 | 📦 交付 | 🗂️ 格式 |
|---|---|---|
| 📱 **Android 13+** | APK，按 ABI 拆分 | `.apk` |
| 🖥️ **Windows 10/11** | MSI 安装包（jpackage） | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

构建参数分布在三个文件里：

| 文件 | 内容 |
|---|---|
| `APP/gradle.properties` | `zappVersion=2.7.0` -- 版本号唯一写入处 |
| `APP/app/build.gradle.kts` | `compileSdk=37.2`、`minSdk=33`、`targetSdk=36`、`versionCode=9` |
| `APP/gradle/libs.versions.toml` | Kotlin 2.4.20、AGP 9.2.1、kotlinx-serialization 1.9.0 |
| `APP/gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 |

### 📲 该下载哪个 APK

每个变体出五个 APK -- 每种 CPU 架构一个，外加一个通用包。原生守护进程
（`tor`、`nfqws`、`goodbyedpi`、`sing-box`……）几乎占了全部体积，而通用包
要带四份它们，所以拆分包大约**小四倍**：

| 你的设备 | 下载 | 体积 |
|---|---|---|
| 📱 大多数现代手机 | `ZAPP-<版本>-arm64-v8a.apk` | 31 MB |
| 📱 较旧或低端手机 | `ZAPP-<版本>-armeabi-v7a.apk` | 30 MB |
| 📱 x86 模拟器，64 位 | `ZAPP-<版本>-x86_64.apk` | 32 MB |
| 📱 x86 模拟器，32 位 | `ZAPP-<版本>-x86.apk` | 32 MB |
| ❓ 不确定 | `ZAPP-<版本>.apk`（通用包） | 115 MB |

**root** 版本有同样的五个文件，文件名前缀为 `ZAPP-ROOT-`。其余完全相同 --
root 版只是用更高权限运行 `nfqws` 和 `goodbyedpi`。

五个包的 `versionCode` 相同，所以互相覆盖安装是普通更新，绝不会降级。
Android 会自动选中与你 CPU 匹配的包，装上的拆分包确实更小。

### 🎯 功能

| 领域 | 已实现 |
|---|---|
| 🛡️ **Tor** | 内置桥接源（top-100 列表与自定义 URL）、按延迟自动选最快桥、故障切换到备用池、加载到 100%、本地 SOCKS 9050、经 lyrebird 的 pluggable 传输 |
| 🌐 **VPN** | WireGuard / AmneziaWG、OpenVPN 与 sing-box 引擎（vless、vmess、trojan、ss、hysteria2、tuic）、带 urltest 的分组配置、kill-switch、VpnService 授权流程 |
| 🛹 **Zapret / GoodbyeDPI** | root 守护进程 `nfqws` 与 `goodbyedpi` 共用一条 NFQUEUE 链（`ZAPP_DPI`）、16 种策略代码、策略自动选择、两者可同时运行、崩溃后自动重启 |
| 📊 **速度标牌** | 实时计数与括号内的活动模式 -- `(T\|V\|Z\|GDPI)`；同一行也会进入通知栏 |
| 📈 **速度迷你图** | 状态卡上的 rx/tx 迷你曲线（下载红、上传蓝），下方是会话流量计数 |
| 🧭 **DNS 自动选择** | 用户 DNS 列表为空时，隧道在启动前用真实 A 查询探测公共解析器（8.8.8.8、1.1.1.1、77.88.8.8……），并使用第一个响应者 |
| 🧱 **共享 UI** | Compose Multiplatform 模块 `:composeApp`，编译到 Android 与 JVM 桌面 |
| 🧭 **导航** | Android 上底部六个标签 -- Tor、VPN、Zapret、GoodbyeDPI、GPS、设置；GPS 模拟定位仅 Android 有，桌面显示五个 |
| 🎨 **设计系统** | 一套暗色配色、字体、形状、间距刻度、涟漪、星空与玻璃背景 |
| 🌗 **主题** | 仅暗色 -- 没有浅色方案，也没有可切换的目标 |
| ⚙️ **设置** | 分组卡片：语言、欢迎页重播、性能、权限、设备、更新、流量、预设、VPN 配置、路由、URL 测试、Zapret 策略与过滤、GoodbyeDPI 模式、日志、文档、关于 |
| 📖 **应用内文档** | 7 个章节、11 个条目，EN / RU / ZH |
| 📶 **流量监视器** | 系统计数器、速度曲线、会话汇总；Android 上用原生 C++ 读取器，Kotlin 为后备，独立窗口打开 |
| 📦 **打包** | 本地 APK / MSI / `.deb` / AppImage；APK 按 ABI 拆分（四种架构加通用包）；GitHub Actions 在推送 `main` 和 `v*` 标签时构建同样的产物并发布签名版本 |
| 🧪 **测试** | `desktopTest` 共 1303 个用例：WCAG 对比度、语言环境解析、配置编解码、链接解析、sing-box 配置生成、桥接选择、迷你图数学、DNS 自动选择 |

### 🧭 架构

```
ZAPP/
├── APP/                     Gradle 根项目
│   ├── composeApp/          共享模块：Android 库 + JVM("desktop")
│   │   ├── commonMain/      UI、设计系统、模型、ViewModel、核心逻辑
│   │   ├── androidMain/     actual 实现（SharedPreferences）
│   │   ├── desktopMain/     actual 实现（文件系统）
│   │   ├── jvmShared/       Android 与桌面共用的 JVM 代码（21 个文件）
│   │   └── desktopTest/     测试集（1303 个用例）
│   ├── app/                 Android 应用：manifest、MainActivity、资源
│   ├── desktopApp/          桌面启动器、jpackage 打包
│   └── build-all.sh         release | dev | linux
├── native/                  构建守护进程的脚本与补丁
├── ABOUT.md                 应用做什么，用平实的话
├── DOC.md                   技术文档
├── DEVELOPMENT.md           工作原理、Fork 指南、预设格式
├── DOCX.docx                同一份文档的 Word 版本
└── LICENSE                  对所有人自由开放
```

`jvmShared` 存放核心的 JVM 半边：进程启动、套接字、文件访问和守护进程
控制在 Android 与桌面上完全相同，但不能放进不依赖 JVM 的 `commonMain`。
`androidMain` 与 `desktopMain` 都声明了 `dependsOn(jvmShared)`。

两个 namespace 是刻意并存的：`:app` 用 `xyz.azraellab.zapp`，而 `:composeApp`
声明 Android 库 namespace `xyz.azraellab.zapp.shared`。namespace 是库在
Android 中的身份（生成的 `R`/`BuildConfig`），不是 Kotlin 包，manifest
merger 拒绝重复 -- Kotlin 包可以重合，namespace 不行。

### 🔨 构建

需要 JDK 21（安装桌面安装包需带 `jpackage`）和包含 37.2 平台的 Android SDK。

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/ZAPP[-ROOT]-<版本>-debug[-<abi>].apk
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/ZAPP[-ROOT]-<版本>[-<abi>].apk
```

每条命令构建两个变体，每个变体拆成四种架构各一个 APK，外加通用包。

release 签名只在本地：创建 keystore 并在 `signing/keystore.properties`
中指向它，`release` 构建类型会自动签名。没有该文件时 release 会构建为
未签名，因此 fork 和 CI 无需密钥也能构建。
</details>

<details>
<summary><b>桌面</b></summary>

```bash
./gradlew :desktopApp:createDistributable        # 当前系统的安装包
./gradlew :desktopApp:packageUberJarForCurrentOS # 单个 jar，可在 Windows/macOS/Linux 运行
./gradlew :desktopApp:packageMsi                 # Windows（在 Windows 上运行）
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

也可以用构建脚本，它会自行找到完整 JDK 和 `objcopy`：

```bash
./APP/build-all.sh dev     # debug APK + 桌面 uber-JAR
./APP/build-all.sh linux   # AppImage + .deb
./APP/build-all.sh release # debug+release APK + uber-JAR（默认）
```
</details>

<details>
<summary><b>两项不能移除的设置</b></summary>

`APP/gradle.properties` 固定了：

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

按 ABI 拆分时，AGP 会在**单个任务内部**为每种架构启动一个打包器，并通过
共享的 `ForkJoinPool.commonPool()` 签名。打包器会抽干线程池，签名步骤
等待永远不会释放的 worker，构建就停在 `packageRootRelease`，既无输出
也无异常。`workers.max=1` 串行化拆分，使签名时线程池为空。

移除这两个开关不会让构建变慢 -- 而是让它挂起，而且可稳定复现。
完整分析见 [DOC.md](DOC.md)。
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`jlink` 和 `jpackage` 需要 `objcopy`，`PATH` 里默认没有 -- 构建前先指向
一个 binutils 包：

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

**NixOS 产物不可发布。** NixOS 构建会把 `/nix/store` 路径写入启动器解释器
和 jlink 运行时，因此这里生成的 AppImage 和 `.deb` 无法在 Debian、Ubuntu
或 Windows 上启动。`build-all.sh` 会解包每个产物并写入
`build/dist/PUBLISHABLE` 和 `build/dist/UNPUBLISHABLE` -- APK 和 uber-JAR
通过，AppImage 和 `.deb` 不通过。只发布 `PUBLISHABLE` 里的内容，或者从 CI
取桌面包，CI 在 `ubuntu` 和 `windows` 上构建它们。
</details>

### 🧪 测试

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

18 个文件中的 1303 个测试用例，全部通过：组件与调色板配色的 WCAG 对比度、
系统语言环境映射（含区域标签中的国家部分）、配置编解码（`AppConfig`、
`VpnConfig`）、代理链接解析、sing-box 配置生成、WireGuard ini 解析、桥接
选择、流量采样、迷你图数学、路线进度、版本比较与 DNS 自动选择探测。
`--rerun-tasks` 不可少：没有它，未改动的任务会报告 `UP-TO-DATE`，测试
数量不会重新计算。

调色板测试不是装饰。把 `outline` 换成 `#D4D4D8`，`ThemeTest` 就会失败 --
这正是重点：在暗色表面上读作可见边框的颜色，不应当能悄悄通过。

### ✅ 路线图

按 0-13 分阶段，一次一个阶段，期间保持构建与测试全绿。简短说明见
[ABOUT.md](ABOUT.md)。

- [x] **0** KMP 骨架、`ui/theme/`、背景、涟漪
- [x] **1** `ui/components/` -- 带对比度测试的设计系统组件
- [x] **2** `ui/nav/` -- 底部栏、标签页、流量监视器独立窗口
- [x] **3** 状态与存储 -- `AppState`、`ConfigStore`、`AppLog` 取代独立 `data/` 层
- [x] **4** 引导流程：欢迎页、权限请求、随连接状态变化的图标
- [x] **5** `core/` 解析器：vless / vmess / trojan / ss / hysteria2 / wireguard
- [x] **6** 配置采集：桥接源（内置仓库、自定义 URL）、后台刷新、去重
- [x] **7** 存活检测：桥接 ping 与归档、最快桥自动选择
- [x] **8** 连接：WireGuard/AmneziaWG、OpenVPN、sing-box、经桥的 Tor、kill-switch
- [x] **9** Zapret：策略、预设、root 路径、策略自动选择；WinDivert（Windows）暂不在范围内
- [x] **10** GoodbyeDPI：与 zapret 共用一条 NFQUEUE 链的 root 守护进程
- [ ] **11** GPS：设置页已有，场景与地图未做
- [x] **12** 打包：APK / MSI / AppImage / `.deb` 本地与 CI 均可构建，APK 按 ABI 拆分；Nix 与 PKGBUILD 未做
- [x] **13** 对比度、打磨、i18n EN/RU/ZH -- 完成；签名 release APK -- 完成，CI 用机密 keystore 签名发布构建

### 🌿 分支

| 分支 | 用途 |
|---|---|
| `main` | **稳定版** -- 已发布 |
| `develop` | 集成 -- 所有内容先合到这里 |
| `feature/data-layer` | 第 3 阶段遗留与存储工作 |
| `feature/gps` | 第 11 阶段：场景与地图 |
| `feature/packaging` | 第 12 阶段：Nix、PKGBUILD |
| `release/v0.2.0` | 下一次发布的稳定化 |

### 📚 文档

- [ABOUT.md](ABOUT.md) -- 应用做什么，用平实的话
- [DOC.md](DOC.md) -- 完整技术文档：技术栈、模块、构建陷阱、测试
- [DEVELOPMENT.md](DEVELOPMENT.md) -- 工作原理、Fork 指南，以及带示例的完整预设格式
- [DOCX.docx](DOCX.docx) -- 同一份文档的 Word 版本（GitHub 不预览 `.docx`，请下载）

### 📄 许可证

**对所有人自由开放** -- 见 [LICENSE](LICENSE)。

- 可以使用、研究、修改、fork、出售、放进付费产品 -- 无需许可、无需协议、
  无需分成、无需向任何人支付版税。
- 没有付费档、没有订阅、没有付费墙功能、没有捐赠、没有账号、没有遥测。
  它完全在你的机器上运行。
- 感谢署名，但不是必须。

ZAPP 按「原样」提供，不附带任何明示或默示保证。遵守适用于你的法律由你自己负责。

</details>
