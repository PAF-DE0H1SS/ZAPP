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
> **Status: working interface (v0.1.0).** The project builds for Android and desktop.
> The app opens on a dark themed window with a bottom bar: VPN, Zapret, GoodbyeDPI, GPS
> and Settings, plus a separate traffic monitor window. See the Roadmap for what comes next.

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

Settings are stored, presets import and export, and the traffic counters are live. What is in place:

| Area | What works |
|---|---|
| 🧱 **Shared UI** | Compose Multiplatform module `:composeApp` compiled for Android and JVM desktop |
| 🧭 **Navigation** | bottom bar with five tabs; the traffic monitor opens as a separate window |
| 🎨 **Design system** | one dark colour scheme, typography, shapes, spacing scale, ripples, starfield/glass background |
| 🌗 **Theme** | dark only -- there is no light scheme and nothing to switch to |
| ⚙️ **Settings** | config model per section, presets with import/export, debounced save to disk |
| 📶 **Traffic monitor** | system counters, speed graph, session summary; native C++ reader on Android with a Kotlin fallback |
| 📦 **Packaging** | APK, MSI, `.deb`, AppImage and a cross-platform uber-JAR |
| 🧪 **Tests** | 22 cases in `desktopTest`: 6 component contrast + 4 palette contrast + 12 locale resolution |

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
├── ABOUT.md                 what the app does, in plain words
├── DOC.md                   technical documentation
├── DOCX.docx                the same documentation as a Word file
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

`flake.nix` arrives at stage 12. Until then, build by hand with `objcopy` in `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```

Or use the build script, which finds a full JDK and `objcopy` on its own:

```bash
./build-all.sh dev     # debug APK + uber-JAR
./build-all.sh linux   # AppImage + .deb
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

22 test cases, all green: `ComponentsTest` (6) checks each component colour pair against
the WCAG contrast ratios, `ThemeTest` (4) does the same for the palette, and `I18nTest`
(12) checks that the system locale maps to the right language, including the country part
of a locale tag. `--rerun-tasks` matters: without it
an unchanged task reports `UP-TO-DATE` and the test count is not recomputed.

The palette tests are not decoration. Replace `outline` with `#D4D4D8` and `ThemeTest`
fails, which is the point: a colour that reads as a visible border on a dark surface
must not be able to pass unnoticed.

### ✅ Roadmap

Staged as 0-13, one stage at a time, build and tests green in between. See [ABOUT.md](ABOUT.md)
for a short, abstract description.

- [x] **0** KMP skeleton, `ui/theme/`, background, ripple
- [x] **1** `ui/components/` -- design-system components with contrast tests
- [x] **2** `ui/nav/` -- bottom bar, five tabs, traffic monitor as a separate window
- [ ] **3** `data/` -- DTO, repositories, `UiState`, ViewModel
- [ ] **4** onboarding: `DeviceProfile`, capability probe, root/DIVERT/loopback requests
- [ ] **5** `core/` parsers: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **6** config collection: sources, cache, background polling, deduplication
- [ ] **7** liveness check by handshake, 10-minute cache
- [ ] **8** connect: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **9** Zapret: strategies, presets, root path, WinDivert, fallback
- [ ] **10** GoodbyeDPI: TLS splitter, root daemon, Windows without rights, VpnService
- [ ] **11** GPS: test provider, scenarios, map (Android)
- [ ] **12** packaging: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **13** contrast, polish, i18n EN/RU/ZH, signed APK

### 🌿 Branches

| Branch | Purpose |
|---|---|
| `main` | **stable** - released |

### 📚 Docs

- [ABOUT.md](ABOUT.md) - what the app does, in plain words
- [DOC.md](DOC.md) - full technical documentation: stack, modules, build pitfalls, tests
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
| 📱 **Android 13+** | APK | `.apk` |
| 🖥️ **Windows 10/11** | MSI-установщик (jpackage) | `.msi` |
| 🐧 **Linux** | Ubuntu / Debian · AppImage | `.deb` · `.AppImage` |

Координаты сборки лежат в одном месте, `APP/gradle.properties`: `zappVersion=0.1.0`,
`minSdk=33` (Android 13), `targetSdk=36`, `versionCode=1`.

### 🎯 Возможности

Настройки сохраняются, пресеты импортируются и выгружаются, счётчики трафика живые.
Что уже работает:

| Область | Что есть |
|---|---|
| 🧱 **Общий UI** | модуль Compose Multiplatform `:composeApp`, собирается под Android и JVM-десктоп |
| 🧭 **Навигация** | нижняя панель из пяти разделов; мониторинг трафика открывается отдельным окном |
| 🎨 **Дизайн-система** | одна тёмная цветовая схема, типографика, формы, шкала отступов, ripple, фон «звёздное небо» со стеклом |
| 🌗 **Тема** | только тёмная: светлой схемы нет и переключаться не на что |
| ⚙️ **Настройки** | модель настроек по разделам, пресеты с импортом и выгрузкой, отложенная запись на диск |
| 📶 **Мониторинг трафика** | системные счётчики, график скорости, сводка за сессию; на Android -- нативный C++ с откатом на Kotlin |
| 📦 **Упаковка** | APK, MSI, `.deb`, AppImage и кроссплатформенный uber-JAR |
| 🧪 **Тесты** | 22 кейса в `desktopTest`: 6 на контраст компонентов + 4 на контраст палитры + 12 на разбор локали |

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
├── ABOUT.md                 что делает приложение, простыми словами
├── DOC.md                   техническая документация
├── DOCX.docx                та же документация в формате Word
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

`flake.nix` появится на этапе 12. До этого сборка вручную, с `objcopy` в `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```

Либо скриптом сборки, который сам найдёт полный JDK и `objcopy`:

```bash
./build-all.sh dev     # debug APK + uber-JAR
./build-all.sh linux   # AppImage + .deb
```

**Артефакты с NixOS публиковать нельзя.** Сборка на NixOS вписывает пути `/nix/store` в
интерпретатор launcher'а и в runtime jlink, поэтому собранный здесь AppImage или `.deb`
не запустится на Debian, Ubuntu или Windows. Поэтому `build-all.sh` распаковывает каждый
артефакт и раскладывает их по спискам `build/dist/PUBLISHABLE` и
`build/dist/UNPUBLISHABLE`: APK и uber-JAR проходят, AppImage и `.deb` -- нет.
Публикуйте только то, что в `PUBLISHABLE`, либо берите десктопные пакеты из CI, который
собирает их на `ubuntu` и `windows`.
</details>

### 🧪 Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

22 тестовых кейса, все зелёные: `ComponentsTest` (6) проверяет каждую пару цветов
компонента на контраст по WCAG, `ThemeTest` (4) -- то же самое для палитры,
`I18nTest` (12) -- что локаль системы, включая страну в теге, отображается в правильный
язык приложения. `--rerun-tasks` обязателен: без него
неизменившаяся задача отдаёт `UP-TO-DATE`, и счётчик тестов не пересчитывается.

Тесты палитры -- не для галочки. Подставьте вместо `outline` цвет `#D4D4D8`, и `ThemeTest`
упадёт, и это правильное поведение: цвет, который на тёмном фоне читается как видимая
рамка, не должен проходить незамеченным.

### ✅ План развития

Разбит на этапы 0-13, по одному этапу за раз, между этапами сборка и тесты зелёные.
Короткое и абстрактное описание -- в [ABOUT.md](ABOUT.md).

- [x] **0** каркас KMP, `ui/theme/`, фон, ripple
- [x] **1** `ui/components/` -- компоненты дизайн-системы с тестами контраста
- [x] **2** `ui/nav/` -- нижняя панель, пять разделов, мониторинг трафика отдельным окном
- [ ] **3** `data/` -- DTO, репозитории, `UiState`, ViewModel
- [ ] **4** онбординг: `DeviceProfile`, разведка, запрос прав (root / DIVERT / loopback)
- [ ] **5** `core/` парсеры: vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **6** сбор конфигов: источники, кэш, фоновый опрос, дедупликация
- [ ] **7** проверка живости рукопожатием, кэш 10 минут
- [ ] **8** подключение: WireGuard/AmneziaWG + Xray/sing-box
- [ ] **9** Zapret: стратегии, пресеты, root-путь, WinDivert, fallback
- [ ] **10** GoodbyeDPI: TLS-сплиттер, root-демон, Windows-без-прав, VpnService
- [ ] **11** GPS: test-provider, сценарии, карта (Android)
- [ ] **12** упаковка: APK/AAB, MSI/exe, `.deb`, Nix, PKGBUILD
- [ ] **13** контрасты, полировка, i18n EN/RU/ZH, подписанный APK

### 🌿 Ветки

| Ветка | Назначение |
|---|---|
| `main` | **stable** -- релизная |

### 📚 Документы

- [ABOUT.md](ABOUT.md) - что делает приложение, простыми словами
- [DOC.md](DOC.md) - подробная техническая документация: стек, модули, грабли сборки, тесты
- [DOCX.docx](DOCX.docx) - тот же документ в формате Word (GitHub не показывает `.docx` в браузере, файл нужно скачать)

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

设置可以保存，预设支持导入导出，流量计数已经可用。已就位的部分：

| 领域 | 内容 |
|---|---|
| 🧱 **共享 UI** | Compose Multiplatform 模块 `:composeApp`，同时编译到 Android 和 JVM 桌面端 |
| 🧭 **导航** | 底部五个标签页；流量监控以独立窗口打开 |
| 🎨 **设计系统** | 单一深色配色、字体、形状、间距刻度、ripple、星点玻璃背景 |
| 🌗 **主题** | 只有深色：没有浅色方案，也没有可切换的对象 |
| ⚙️ **设置** | 分区的配置模型、预设导入导出、写盘防抖 |
| 📶 **流量监控** | 系统计数、速度曲线、会话汇总；Android 上优先使用原生 C++，失败时退回 Kotlin |
| 📦 **打包** | APK、MSI、`.deb`、AppImage 以及跨平台 uber-JAR |
| 🧪 **测试** | `desktopTest` 共 22 个用例：6 个组件对比度 + 4 个调色板对比度 + 12 个语言环境解析 |
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
├── ABOUT.md                 应用做什么，通俗说明
├── DOC.md                   技术文档
├── DOCX.docx                同一份文档的 Word 版本
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

`flake.nix` 会在第 12 阶段加入。在那之前手动构建，并确保 `objcopy` 在 `PATH` 中：

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
./gradlew autoBuild
```

或者用构建脚本，它会自己找到完整 JDK 和 `objcopy`：

```bash
./build-all.sh dev     # debug APK + uber-JAR
./build-all.sh linux   # AppImage + .deb
```

**NixOS 上构建的产物不能发布。** NixOS 构建会把 `/nix/store` 路径写进 launcher 的
解释器和 jlink runtime，所以在 NixOS 上打出来的 AppImage 或 `.deb` 在 Debian、Ubuntu
或 Windows 上起不来。因此 `build-all.sh` 会解包每个产物，分成 `build/dist/PUBLISHABLE`
和 `build/dist/UNPUBLISHABLE` 两份清单：APK 和 uber-JAR 通过，AppImage 和 `.deb` 不通过。
只发布 `PUBLISHABLE` 里的文件，或者直接取 CI 的桌面包 -- CI 在 `ubuntu` 和 `windows` 上构建。
</details>

### 🧪 测试

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

22 个测试用例，全部通过：`ComponentsTest`（6 个）按 WCAG 对比度检查组件的每一对颜色，
`ThemeTest`（4 个）对调色板做同样的检查，`I18nTest`（12 个）检查系统语言环境（含地区部分）
是否映射到正确的界面语言。
`--rerun-tasks` 很关键：没有它，未变更的任务会报 `UP-TO-DATE`，测试计数不会重新统计。

调色板测试不是摆设。把 `outline` 换成 `#D4D4D8`，`ThemeTest` 会失败，这正是它该做的：
在深色背景上看起来像可见边框的颜色，不该悄悄通过。

### ✅ 路线图

分为 0-13，每次推进一个阶段，阶段之间保持构建和测试全绿。简短而抽象的说明见
[ABOUT.md](ABOUT.md)。

- [x] **0** KMP 骨架、`ui/theme/`、背景、ripple
- [x] **1** `ui/components/` -- 设计系统组件及其对比度测试
- [x] **2** `ui/nav/` -- 底部导航五个标签页，流量监控独立窗口
- [ ] **3** `data/` -- DTO、仓储、`UiState`、ViewModel
- [ ] **4** 引导流程：`DeviceProfile`、能力探测、root/DIVERT/loopback 权限申请
- [ ] **5** `core/` 解析器：vless / vmess / trojan / ss / hysteria2 / wireguard
- [ ] **6** 配置收集：来源、缓存、后台轮询、去重
- [ ] **7** 握手存活检查，10 分钟缓存
- [ ] **8** 连接：WireGuard/AmneziaWG + Xray/sing-box
- [ ] **9** Zapret：策略、预设、root 路径、WinDivert、降级
- [ ] **10** GoodbyeDPI：TLS 分流器、root 守护进程、Windows 免提权、VpnService
- [ ] **11** GPS：测试提供者、场景、地图（Android）
- [ ] **12** 打包：APK/AAB、MSI/exe、`.deb`、Nix、PKGBUILD
- [ ] **13** 对比度、打磨、多语言 EN/RU/ZH、已签名 APK

### 🌿 分支

| 分支 | 用途 |
|---|---|
| `main` | **stable** -- 稳定分支 |

### 📚 文档

- [ABOUT.md](ABOUT.md) - 应用做什么，通俗说明
- [DOC.md](DOC.md) - 完整技术文档：技术栈、模块、构建坑、测试
- [DOCX.docx](DOCX.docx) - 同一份文档的 Word 版本（GitHub 不在浏览器里预览 `.docx`，需要下载）

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
