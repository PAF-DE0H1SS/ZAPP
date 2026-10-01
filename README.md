<div align="center">

# 🛰️ ZAPP

**A cross-platform network toolkit** — one shared UI, Android and desktop.

[![Platform: Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Windows 10/11](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat&logo=windows&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Linux](https://img.shields.io/badge/Linux-deb%20%7C%20AppImage-FCC624?style=flat&logo=linux&logoColor=black)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Made with Kotlin](https://img.shields.io/badge/Made%20with-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/UI-Compose%20Multiplatform-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![License: Dual - PolyForm NC 1.0.0](https://img.shields.io/badge/License-Dual%20-%20PolyForm%20NC%201.0.0-red?style=flat)](LICENSE)

</div>

> [!IMPORTANT]
> **Status: skeleton (v0.1.0).** The project builds for Android and desktop, the design
> system is in place, but there are no feature screens yet — the app currently draws an
> empty themed window. See the Roadmap for what comes next.

ZAPP is a network toolkit written in Kotlin with **Compose Multiplatform**: one shared UI
for the native Android build and for desktop. The shared code lives in `APP/composeApp`, the
Android application in `APP/app`, the desktop wrapper in `APP/desktopApp`. ZAPP talks to no
server — it is a set of local tools working on the machine it runs on.

---

## Language / Язык / 语言

<details open>
<summary><b>🇬🇧 English</b></summary>

### ✨ Overview

> [!NOTE]
> **Supported platforms** — one codebase, two delivery formats:

| 🖥️ Platform | 📦 Delivery | 🗂️ Format |
|---|---|---|
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI installer (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Build coordinates are kept in one place, `APP/gradle.properties`: `zappVersion=0.1.0`,
`minSdk=33` (Android 13), `targetSdk=36`, `versionCode=1`.

### 🎯 Features

Nothing functional yet — this is the foundation stage. What is in place:

| Area | What works |
|---|---|
| 🧱 **Shared UI** | Compose Multiplatform module `:composeApp` compiled for Android and JVM desktop |
| 🎨 **Design system** | colour schemes (dark/light), typography, shapes, spacing scale, ripples, starfield/glass background |
| 🌗 **Theme persistence** | system / light / dark choice survives restart — `SharedPreferences` on Android, `~/.config/zapp/theme` on desktop |
| 📦 **Packaging** | APK, MSI, `.deb`, AppImage and a cross-platform uber-JAR |
| 🧪 **Tests** | `desktopTest` source set wired up with `kotlin("test")` |

### 🧭 Architecture

```
ZAPP/
├── APP/                     Gradle root project
│   ├── composeApp/          shared module: Android library + JVM("desktop")
│   │   ├── commonMain/      UI, design system, models, ViewModels
│   │   ├── androidMain/     actual implementations (SharedPreferences)
│   │   └── desktopMain/     actual implementations (filesystem)
│   ├── app/                 Android application: manifest, MainActivity, resources
│   └── desktopApp/          desktop launcher, jpackage packaging
├── docs/                    working notes
└── LICENSE                  hybrid dual licensing
```

Two namespaces coexist on purpose: `:app` uses `xyz.azraellab.zapp`, while `:composeApp`
declares the Android library namespace `xyz.azraellab.zapp.shared`. The namespace is the
library's Android identity (generated `R`/`BuildConfig`), not the Kotlin package, and the
manifest merger rejects duplicates — the Kotlin packages may overlap, the namespaces may not.

### 🔨 Build

Requires JDK 21 (with `jpackage` for desktop installers) and Android SDK with platform 37.

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8 minify + resource shrink -> app/build/outputs/apk/release/
```

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

On NixOS, `jlink` and `jpackage` need `objcopy`, which is not in `PATH` by default — point
`PATH` at a binutils package, e.g.
`export PATH=/nix/store/*binutils-*/bin:$PATH`, before building.
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

```bash
nix build . --option sandbox false   # local build
nix run .                            # run from the checkout
```
</details>

### 🧪 Tests

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

The `desktopTest` source set is configured, but no test cases exist yet — the counter is
currently zero. `--rerun-tasks` matters: without it an unchanged task reports `UP-TO-DATE`
and the test count is not recomputed.

### ✅ Roadmap

Staged as P0-P13, one stage per PR, build and tests green in between. Full detail in
[docs/PLAN.md](docs/PLAN.md).

- [x] **P0** KMP skeleton, `ui/theme/`, background, ripple
- [ ] **P1** `ui/components/` — design-system components with contrast tests
- [ ] **P2** `ui/nav/` + `NavScaffold` — back-stack, rail on wide screens, two panes
- [ ] **P3** `data/` — DTO, repositories, `UiState`, ViewModel
- [ ] **P4** onboarding: `DeviceProfile`, capability probe, root/DIVERT/loopback requests
- [ ] **P5** `core/` parsers: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **P6** config collection: sources, cache, background polling, deduplication
- [ ] **P7** liveness check by handshake, 10-minute cache
- [ ] **P8** connect: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **P9** Zapret: strategies, presets, root path, WinDivert, fallback
- [ ] **P10** GoodbyeDPI: TLS splitter, root daemon, Windows without rights, VpnService
- [ ] **P11** GPS: test provider, scenarios, map (Android)
- [ ] **P12** packaging: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **P13** contrast, polish, i18n RU/EN, signed APK

### 🌿 Branches

| Branch | Purpose |
|---|---|
| `main` | **stable** - released |

### 📚 Docs

- [docs/PLAN.md](docs/PLAN.md) - product plan, design system, stages P0-P13
- [AGENTS.md](AGENTS.md) - build environment, emulator setup, known pitfalls

### 📄 License

Distributed under **hybrid dual licensing** — see [LICENSE](LICENSE):

- **Noncommercial use** → [PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) (full text in Part 4)
- **Forks / monetization** → author terms (Parts 1-3): revenue share **≥ 50%**, back-feed clause, CLA

Commercial use requires a written agreement with the author: <typ.onepatop@gmail.com>.

</details>

<details>
<summary><b>🇷🇺 Русский</b></summary>

### ✨ Обзор

> [!NOTE]
> **Поддерживаемые платформы** — одна кодовая база, два формата поставки:

| 🖥️ Платформа | 📦 Поставка | 🗂️ Формат |
|---|---|---|
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI-установщик (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Координаты сборки лежат в одном месте, `APP/gradle.properties`: `zappVersion=0.1.0`,
`minSdk=33` (Android 13), `targetSdk=36`, `versionCode=1`.

### 🎯 Возможности

Функционала пока нет — это стадия фундамента. Что уже работает:

| Область | Что есть |
|---|---|
| 🧱 **Общий UI** | модуль Compose Multiplatform `:composeApp`, собирается под Android и JVM-десктоп |
| 🎨 **Дизайн-система** | цветовые схемы (тёмная/светлая), типографика, формы, шкала отступов, ripple, фон «звёздное небо» со стеклом |
| 🌗 **Хранение темы** | выбор system / light / dark переживает перезапуск — `SharedPreferences` на Android, `~/.config/zapp/theme` на десктопе |
| 📦 **Упаковка** | APK, MSI, `.deb`, AppImage и кроссплатформенный uber-JAR |
| 🧪 **Тесты** | source set `desktopTest` подключён с `kotlin("test")` |

### 🧭 Архитектура

```
ZAPP/
├── APP/                     корневой проект Gradle
│   ├── composeApp/          общий модуль: Android-библиотека + JVM("desktop")
│   │   ├── commonMain/      UI, дизайн-система, модели, ViewModel
│   │   ├── androidMain/     actual-реализации (SharedPreferences)
│   │   └── desktopMain/     actual-реализации (файловая система)
│   ├── app/                 Android-приложение: манифест, MainActivity, ресурсы
│   └── desktopApp/          десктопная точка входа, упаковка jpackage
├── docs/                    рабочие заметки
└── LICENSE                  гибридная двойная лицензия
```

Два namespace соседствуют намеренно: у `:app` — `xyz.azraellab.zapp`, у `:composeApp` —
namespace Android-библиотеки `xyz.azraellab.zapp.shared`. Namespace — это идентичность
библиотеки в Android (в него попадают сгенерированные `R`/`BuildConfig`), а не Kotlin-пакет,
и merger манифестов запрещает дубли: Kotlin-пакеты могут совпадать, namespace — нет.

### 🔨 Сборка

Нужен JDK 21 (с `jpackage` для десктопных установщиков) и Android SDK с платформой 37.

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8 + shrink ресурсов -> app/build/outputs/apk/release/
```

Подпись release локальная: создайте keystore и укажите на него `signing/keystore.properties` —
и build type `release` подпишется сам. Без этого файла release-APK собирается unsigned,
так что форки и CI собираются без секретов.
</details>

<details>
<summary><b>Десктоп</b></summary>

```bash
./gradlew :desktopApp:createDistributable        # установщик для текущей ОС
./gradlew :desktopApp:packageUberJarForCurrentOS # один jar для Windows/macOS/Linux
./gradlew :desktopApp:packageMsi                 # Windows (запускать на Windows)
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

На NixOS `jlink` и `jpackage` требуют `objcopy`, которого нет в `PATH` по умолчанию —
перед сборкой добавьте в `PATH` пакет binutils, например
`export PATH=/nix/store/*binutils-*/bin:$PATH`.
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

```bash
nix build . --option sandbox false   # локальная сборка
nix run .                            # запуск из каталога
```
</details>

### 🧪 Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

Source set `desktopTest` настроен, но тестовых кейсов пока нет — счётчик равен нулю.
`--rerun-tasks` обязателен: без него неизменившаяся задача отдаёт `UP-TO-DATE`, и счётчик
тестов не пересчитывается.

### ✅ План развития

Разбит на этапы P0-P13, по одному PR на этап, между этапами сборка и тесты зелёные.
Подробности — в [docs/PLAN.md](docs/PLAN.md).

- [x] **P0** каркас KMP, `ui/theme/`, фон, ripple
- [ ] **P1** `ui/components/` — компоненты дизайн-системы с тестами контраста
- [ ] **P2** `ui/nav/` + `NavScaffold` — back-stack, рельс на широком экране, две панели
- [ ] **P3** `data/` — DTO, репозитории, `UiState`, ViewModel
- [ ] **P4** онбординг: `DeviceProfile`, разведка, запрос прав (root / DIVERT / loopback)
- [ ] **P5** `core/` парсеры: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **P6** сбор конфигов: источники, кэш, фоновый опрос, дедупликация
- [ ] **P7** проверка живости рукопожатием, кэш 10 минут
- [ ] **P8** подключение: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **P9** Zapret: стратегии, пресеты, root-путь, WinDivert, fallback
- [ ] **P10** GoodbyeDPI: TLS-сплиттер, root-демон, Windows-без-прав, VpnService
- [ ] **P11** GPS: test-provider, сценарии, карта (Android)
- [ ] **P12** упаковка: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **P13** контрасты, полировка, i18n RU/EN, подписанный APK

### 🌿 Ветки

| Ветка | Назначение |
|---|---|
| `main` | **stable** — релизная |

### 📚 Документы

- [docs/PLAN.md](docs/PLAN.md) - план продукта, дизайн-система, этапы P0-P13
- [AGENTS.md](AGENTS.md) - окружение сборки, эмулятор, известные грабли

### 📄 Лицензия

Распространяется на условиях **гибридной двойной лицензии** — см. [LICENSE](LICENSE):

- **Некоммерческое использование** → [PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) (полный текст в части 4)
- **Форки / монетизация** → условия автора (части 1-3): отчисление **≥ 50%**, back-feed, CLA

Для коммерческого использования нужен письменный договор с автором: <typ.onepatop@gmail.com>.

</details>

---

<div align="center">

Made with Kotlin and Compose Multiplatform · © 2026 azrael (PAF-DE0H1SS)

</div>
