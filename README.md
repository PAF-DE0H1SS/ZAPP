<div align="center">

# 🛰️ ZAPP

**A cross-platform network toolkit** -- one shared UI, Android and desktop.

[![Platform: Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Windows 10/11](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat&logo=windows&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Linux](https://img.shields.io/badge/Linux-deb%20%7C%20AppImage-FCC624?style=flat&logo=linux&logoColor=black)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Made with Kotlin](https://img.shields.io/badge/Made%20with-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/UI-Compose%20Multiplatform-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![License: Free for everyone](https://img.shields.io/badge/License-free%20for%20everyone-2EA44F?style=flat)](LICENSE)

</div>

> [!IMPORTANT]
> **Status: skeleton (v0.1.0).** The project builds for Android and desktop, the design
> system is in place, but there are no feature screens yet -- the app currently draws an
> empty themed window. See the Roadmap for what comes next.

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
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI installer (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Build coordinates are kept in one place, `APP/gradle.properties`: `zappVersion=0.1.0`,
`minSdk=33` (Android 13), `targetSdk=36`, `versionCode=1`.

### 🎯 Features

Nothing functional yet -- this is the foundation stage. What is in place:

| Area | What works |
|---|---|
| 🧱 **Shared UI** | Compose Multiplatform module `:composeApp` compiled for Android and JVM desktop |
| 🎨 **Design system** | colour schemes (dark/light), typography, shapes, spacing scale, ripples, starfield/glass background |
| 🌗 **Theme persistence** | system / light / dark choice survives restart -- `SharedPreferences` on Android, `~/.config/zapp/theme` on desktop |
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
└── LICENSE                  free and open for everyone
```

Two namespaces coexist on purpose: `:app` uses `xyz.azraellab.zapp`, while `:composeApp`
declares the Android library namespace `xyz.azraellab.zapp.shared`. The namespace is the
library's Android identity (generated `R`/`BuildConfig`), not the Kotlin package, and the
manifest merger rejects duplicates -- the Kotlin packages may overlap, the namespaces may not.

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

On NixOS, `jlink` and `jpackage` need `objcopy`, which is not in `PATH` by default -- point
`PATH` at a binutils package, e.g.
`export PATH=/nix/store/*binutils-*/bin:$PATH`, before building.
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`flake.nix` arrives at stage P12. Until then, build by hand with `objcopy` in `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```
</details>

### 🧪 Tests

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

The `desktopTest` source set is configured, but no test cases exist yet -- the counter is
currently zero. `--rerun-tasks` matters: without it an unchanged task reports `UP-TO-DATE`
and the test count is not recomputed.

### ✅ Roadmap

Staged as P0-P13, one stage per PR, build and tests green in between. Full detail in
[docs/PLAN.md](docs/PLAN.md).

- [x] **P0** KMP skeleton, `ui/theme/`, background, ripple
- [ ] **P1** `ui/components/` -- design-system components with contrast tests
- [ ] **P2** `ui/nav/` + `NavScaffold` -- back-stack, rail on wide screens, two panes
- [ ] **P3** `data/` -- DTO, repositories, `UiState`, ViewModel
- [ ] **P4** onboarding: `DeviceProfile`, capability probe, root/DIVERT/loopback requests
- [ ] **P5** `core/` parsers: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **P6** config collection: sources, cache, background polling, deduplication
- [ ] **P7** liveness check by handshake, 10-minute cache
- [ ] **P8** connect: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **P9** Zapret: strategies, presets, root path, WinDivert, fallback
- [ ] **P10** GoodbyeDPI: TLS splitter, root daemon, Windows without rights, VpnService
- [ ] **P11** GPS: test provider, scenarios, map (Android)
- [ ] **P12** packaging: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **P13** contrast, polish, i18n EN/RU/ZH, signed APK

### 🌿 Branches

| Branch | Purpose |
|---|---|
| `main` | **stable** - released |

### 📚 Docs

- [docs/PLAN.md](docs/PLAN.md) - product plan, design system, stages P0-P13
- `AGENTS.md` in a local checkout - build environment, emulator setup, known pitfalls

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
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI-установщик (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Координаты сборки лежат в одном месте, `APP/gradle.properties`: `zappVersion=0.1.0`,
`minSdk=33` (Android 13), `targetSdk=36`, `versionCode=1`.

### 🎯 Возможности

Функционала пока нет -- это стадия фундамента. Что уже работает:

| Область | Что есть |
|---|---|
| 🧱 **Общий UI** | модуль Compose Multiplatform `:composeApp`, собирается под Android и JVM-десктоп |
| 🎨 **Дизайн-система** | цветовые схемы (тёмная/светлая), типографика, формы, шкала отступов, ripple, фон «звёздное небо» со стеклом |
| 🌗 **Хранение темы** | выбор system / light / dark переживает перезапуск -- `SharedPreferences` на Android, `~/.config/zapp/theme` на десктопе |
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
└── LICENSE                  бесплатно и открыто для всех
```

Два namespace соседствуют намеренно: у `:app` -- `xyz.azraellab.zapp`, у `:composeApp` --
namespace Android-библиотеки `xyz.azraellab.zapp.shared`. Namespace -- это идентичность
библиотеки в Android (в него попадают сгенерированные `R`/`BuildConfig`), а не Kotlin-пакет,
и merger манифестов запрещает дубли: Kotlin-пакеты могут совпадать, namespace -- нет.

### 🔨 Сборка

Нужен JDK 21 (с `jpackage` для десктопных установщиков) и Android SDK с платформой 37.

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8 + shrink ресурсов -> app/build/outputs/apk/release/
```

Подпись release локальная: создайте keystore и укажите на него `signing/keystore.properties` --
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

На NixOS `jlink` и `jpackage` требуют `objcopy`, которого нет в `PATH` по умолчанию --
перед сборкой добавьте в `PATH` пакет binutils, например
`export PATH=/nix/store/*binutils-*/bin:$PATH`.
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`flake.nix` появится на этапе P12. До этого сборка вручную, с `objcopy` в `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```
</details>

### 🧪 Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

Source set `desktopTest` настроен, но тестовых кейсов пока нет -- счётчик равен нулю.
`--rerun-tasks` обязателен: без него неизменившаяся задача отдаёт `UP-TO-DATE`, и счётчик
тестов не пересчитывается.

### ✅ План развития

Разбит на этапы P0-P13, по одному PR на этап, между этапами сборка и тесты зелёные.
Подробности -- в [docs/PLAN.md](docs/PLAN.md).

- [x] **P0** каркас KMP, `ui/theme/`, фон, ripple
- [ ] **P1** `ui/components/` -- компоненты дизайн-системы с тестами контраста
- [ ] **P2** `ui/nav/` + `NavScaffold` -- back-stack, рельс на широком экране, две панели
- [ ] **P3** `data/` -- DTO, репозитории, `UiState`, ViewModel
- [ ] **P4** онбординг: `DeviceProfile`, разведка, запрос прав (root / DIVERT / loopback)
- [ ] **P5** `core/` парсеры: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **P6** сбор конфигов: источники, кэш, фоновый опрос, дедупликация
- [ ] **P7** проверка живости рукопожатием, кэш 10 минут
- [ ] **P8** подключение: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **P9** Zapret: стратегии, пресеты, root-путь, WinDivert, fallback
- [ ] **P10** GoodbyeDPI: TLS-сплиттер, root-демон, Windows-без-прав, VpnService
- [ ] **P11** GPS: test-provider, сценарии, карта (Android)
- [ ] **P12** упаковка: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **P13** контрасты, полировка, i18n EN/RU/ZH, подписанный APK

### 🌿 Ветки

| Ветка | Назначение |
|---|---|
| `main` | **stable** -- релизная |

### 📚 Документы

- [docs/PLAN.md](docs/PLAN.md) - план продукта, дизайн-система, этапы P0-P13
- `AGENTS.md` в локальном клоне - окружение сборки, эмулятор, известные грабли

### 📄 Лицензия

**Бесплатно и открыто для всех** -- см. [LICENSE](LICENSE).

- Используйте, изучайте, меняйте, форкайте, продавайте, встраивайте в платные
  продукты -- разрешение не нужно, договор не нужен, отчисления и роялти никому
  не платятся.
- Нет платной версии, подписки, платных функций, донатов, аккаунта и телеметрии.
  Всё работает локально на вашей машине.
- Указание авторства приветствуется, но не обязательно.

ZAPP предоставляется «КАК ЕСТЬ», без гарантий любого рода. Соблюдение применимого
законодательства -- ваша ответственность.

</details>

<details>
<summary><b>🇨🇳 中文</b></summary>

### ✨ 概述

> [!NOTE]
> **支持的平台** -- 一套代码，两种交付格式：

| 🖥️ 平台 | 📦 交付 | 🗂️ 格式 |
|---|---|---|
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI 安装包 (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

构建坐标集中放在一处，`APP/gradle.properties`：`zappVersion=0.1.0`、
`minSdk=33`（Android 13）、`targetSdk=36`、`versionCode=1`。

### 🎯 功能

目前还没有实际功能，仍处于基础阶段。已经就位的部分：

| 领域 | 内容 |
|---|---|
| 🧱 **共享 UI** | Compose Multiplatform 模块 `:composeApp`，同时编译到 Android 和 JVM 桌面端 |
| 🎨 **设计系统** | 配色方案（深色/浅色）、字体、形状、间距刻度、ripple、星点玻璃背景 |
| 🌗 **主题持久化** | system / light / dark 的选择可跨重启保留 -- Android 用 `SharedPreferences`，桌面端用 `~/.config/zapp/theme` |
| 📦 **打包** | APK、MSI、`.deb`、AppImage 以及跨平台 uber-JAR |
| 🧪 **测试** | `desktopTest` source set 已配置 `kotlin("test")` |
| 🌐 **语言** | 英文、俄文、中文 |

### 🧭 架构

```
ZAPP/
├── APP/                     Gradle 根项目
│   ├── composeApp/          共享模块：Android 库 + JVM("desktop")
│   │   ├── commonMain/      UI、设计系统、模型、ViewModel
│   │   ├── androidMain/     actual 实现（SharedPreferences）
│   │   └── desktopMain/     actual 实现（文件系统）
│   ├── app/                 Android 应用：清单、MainActivity、资源
│   └── desktopApp/          桌面端入口，jpackage 打包
├── docs/                    工作笔记
└── LICENSE                  免费且对所有人开放
```

两个 namespace 有意不同：`:app` 用 `xyz.azraellab.zapp`，`:composeApp` 声明的
Android 库 namespace 是 `xyz.azraellab.zapp.shared`。namespace 是库在 Android
中的标识（生成的 `R`/`BuildConfig` 归它），不是 Kotlin 包名，manifest merger
不允许重复 -- Kotlin 包名可以重叠，namespace 不行。

### 🔨 构建

需要 JDK 21（桌面安装包要用 `jpackage`）和带 platform 37 的 Android SDK。

<details open>
<summary><b>Android</b></summary>

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8 混淆 + 资源压缩 -> app/build/outputs/apk/release/
```

release 签名仅限本地：创建 keystore 并让 `signing/keystore.properties` 指向它，
`release` build type 会自动签名。没有这个文件时 release APK 会以未签名方式构建，
所以 fork 和 CI 不需要任何密钥也能构建。
</details>

<details>
<summary><b>桌面端</b></summary>

```bash
./gradlew :desktopApp:createDistributable        # 当前系统的安装包
./gradlew :desktopApp:packageUberJarForCurrentOS # 单个 jar，可在 Windows/macOS/Linux 运行
./gradlew :desktopApp:packageMsi                 # Windows（需在 Windows 上运行）
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

在 NixOS 上，`jlink` 和 `jpackage` 需要 `objcopy`，而它默认不在 `PATH` 里 --
构建前把 `PATH` 指向某个 binutils 包，例如
`export PATH=/nix/store/*binutils-*/bin:$PATH`。
</details>

<details>
<summary><b>🐧 NixOS</b></summary>

`flake.nix` 会在 P12 阶段加入。在那之前手动构建，并确保 `objcopy` 在 `PATH` 中：

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```
</details>

### 🧪 测试

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

`desktopTest` source set 已配置好，但目前还没有测试用例，计数为零。`--rerun-tasks`
很关键：没有它，未变更的任务会报 `UP-TO-DATE`，测试计数不会重新统计。

### ✅ 路线图

分为 P0-P13，每个阶段一个 PR，阶段之间保持构建和测试全绿。详见
[docs/PLAN.md](docs/PLAN.md)。

- [x] **P0** KMP 骨架、`ui/theme/`、背景、ripple
- [ ] **P1** `ui/components/` -- 设计系统组件及其对比度测试
- [ ] **P2** `ui/nav/` + `NavScaffold` -- 返回栈、宽屏侧栏、双面板
- [ ] **P3** `data/` -- DTO、仓储、`UiState`、ViewModel
- [ ] **P4** 引导流程：`DeviceProfile`、能力探测、root/DIVERT/loopback 权限申请
- [ ] **P5** `core/` 解析器：vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **P6** 配置收集：来源、缓存、后台轮询、去重
- [ ] **P7** 握手存活检查，10 分钟缓存
- [ ] **P8** 连接：WireGuard/AmneziaWG + Xray/sing-box
- [ ] **P9** Zapret：策略、预设、root 路径、WinDivert、降级
- [ ] **P10** GoodbyeDPI：TLS 分流器、root 守护进程、Windows 免提权、VpnService
- [ ] **P11** GPS：测试提供者、场景、地图（Android）
- [ ] **P12** 打包：APK/AAB、MSI/exe、`.deb`、Nix、PKGBUILD
- [ ] **P13** 对比度、打磨、多语言 EN/RU/ZH、已签名 APK

### 🌿 分支

| 分支 | 用途 |
|---|---|
| `main` | **stable** -- 稳定分支 |

### 📚 文档

- [docs/PLAN.md](docs/PLAN.md) -- 产品计划、设计系统、阶段 P0-P13
- 本地检出中的 `AGENTS.md` -- 构建环境、模拟器、已知坑

### 📄 许可证

**免费且对所有人开放** -- 见 [LICENSE](LICENSE)。

- 可以使用、研究、修改、fork、销售、嵌入付费产品 -- 无需许可、无需协议、
  不向任何人支付任何分成或版税。
- 没有付费版、订阅、付费功能、捐赠、账号，也没有遥测。全部在你自己机器上运行。
- 署名受到欢迎，但不是必需的。

ZAPP 按「现状」提供，不带任何担保。遵守适用于你的法律是你的责任。

</details>

---

<div align="center">

Made with Kotlin and Compose Multiplatform · © 2026 azrael (PAF-DE0H1SS)

</div>
