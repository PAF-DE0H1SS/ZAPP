<div align="center">

# 🛰️ ZAPP

**Technical documentation**

[![Platform: Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Windows 10/11](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat&logo=windows&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Linux](https://img.shields.io/badge/Linux-deb%20%7C%20AppImage-FCC624?style=flat&logo=linux&logoColor=black)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![CI](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml/badge.svg)](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml)
[![License: Free for everyone](https://img.shields.io/badge/License-free%20for%20everyone-2EA44F?style=flat)](LICENSE)

</div>

The full reference: stack, modules, namespaces, source sets, build variants,
tests, build pitfalls and the release process. For the project overview see
[README.md](README.md); for a plain-language page about the app see
[ABOUT.md](ABOUT.md); for the forking guide and the preset format see
[DEVELOPMENT.md](DEVELOPMENT.md).

---

## Language / Язык / 语言

<details open>
<summary><b>🇬🇧 English</b></summary>

This document describes what is in the project, what it is built from, and where
everything lives. All versions and numbers come from configs and built artifacts,
not from prose. Where a value was verified by running it, that is marked.

Other documents: [README.md](README.md) — the repository home page,
[ABOUT.md](ABOUT.md) — what the app does in plain words,
[DEVELOPMENT.md](DEVELOPMENT.md) — fork guide and preset format.

## Contents

**Part I. Structure**

1. [Stack](#1-stack)
2. [Modules](#2-modules)
3. [Namespaces](#3-namespaces)
4. [Source sets](#4-source-sets)

**Part II. Interface**

5. [Design system](#5-design-system)
6. [Screens](#6-screens)
7. [Theme and language](#7-theme-and-language)

**Part III. Build**

8. [Build](#8-build)
9. [Build gotchas](#9-build-gotchas)
10. [APK signing](#10-apk-signing)

**Part IV. Checks and release**

11. [Tests](#11-tests)
12. [CI build and release](#12-ci-build-and-release)
13. [File map](#13-file-map)
14. [Checks after changes](#14-checks-after-changes)
15. [What is left](#15-what-is-left)

---

# Part I. Structure

## 1. Stack

| Layer | What | Version | Where defined |
|---|---|---|---|
| Language | Kotlin | 2.4.20 | `APP/gradle/libs.versions.toml` |
| Build | Gradle | 9.4.1 | `APP/gradle/wrapper/gradle-wrapper.properties` |
| Android plugin | AGP | 9.2.1 | `APP/gradle/libs.versions.toml` |
| UI | Compose Multiplatform | 1.12.1 | same |
| Serialization | kotlinx-serialization-json | 1.9.0 | same |
| Android activity | androidx.activity:activity-compose | 1.13.0 | same |
| Android core | androidx.core:core-ktx | 1.10.1 | same |
| Tests | kotlin("test") | 2.4.20 | `APP/composeApp/build.gradle.kts` |
| Desktop packaging | jpackage, jlink | JDK 21 | `APP/desktopApp/build.gradle.kts` |
| AppImage | appimagetool | continuous | `.github/workflows/build.yml` |
| Android target OS | API 33 and higher | — | `APP/app/build.gradle.kts` |
| Android target OS | targetSdk 36 | — | same |

The single source of truth for versions is `APP/gradle/libs.versions.toml`. In
`build.gradle.kts` versions are not repeated, only aliases are used.

The app version itself lives separately, in `APP/gradle.properties`:

```properties
zappVersion=2.7.0
```

`APP/app/build.gradle.kts` reads it as `versionName` and
`APP/desktopApp/build.gradle.kts` reads it as `packageVersion`, so the APK and
the desktop distribution do not drift apart. Verified on a built APK:
`versionName=2.7.0`, `versionCode=9`, `minSdk=33`, `targetSdk=36`,
`compileSdk=37.2`.

---

## 2. Modules

Three modules inside `APP/`. One shared codebase; the platform parts are split
across source sets inside the module.

### 2.1. `:composeApp`

The main module. Kotlin Multiplatform with two targets:

- `androidLibrary` — Android library, `namespace = xyz.azraellab.zapp.shared`,
  `compileSdk = 37`, `minSdk = 33`, `jvmTarget = JVM_11`;
- `jvm("desktop")` — a plain JVM library.

`commonMain` dependencies:

```kotlin
implementation(compose.runtime)
implementation(compose.foundation)
implementation(compose.material3)
implementation(compose.ui)
implementation(compose.materialIconsExtended)
implementation(libs.kotlinx.serialization.json)
```

`desktopMain` adds `compose.desktop.currentOs`, `androidMain` adds
`androidx.activity.compose`, `desktopTest` adds `kotlin("test")`.

`androidResources` is enabled because the design system pulls in Compose
resources.

### 2.2. `:app`

Android only. An application, not a library.

| Parameter | Value |
|---|---|
| `namespace` | `xyz.azraellab.zapp` |
| `applicationId` | `xyz.azraellab.zapp` |
| `minSdk` | 33 |
| `targetSdk` | 36 |
| `compileSdk` | 37, `minorApiLevel` 2 |
| `sourceCompatibility` | Java 11 |

The release build enables minification and resource shrinking:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

One working activity — `MainActivity` on `ComponentActivity`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AndroidCtx.attach(this)
    installPresetFileBridge(context = this, onPickRead = { ... }, onPickWrite = { ... })
    setContent { App() }
}
```

Two SAF launchers are registered as class fields, before `onCreate`:
`registerForActivityResult` throws if it is called later.
`installPresetFileBridge` only moves the callbacks into `PresetFileGateway` —
the shared code does not know how a file is picked, and the app must answer the
request with a suspend call (`suspend`).

Besides `MainActivity`, the manifest declares two launchers — `LauncherLock` and
`LauncherUnlock`. The first opens by default and carries a flag that blocks
screenshots; the second is a plain entry without that restriction.

`AndroidCtx` gives access to `Context` from code that has no `Context` to pass.
It lives in its own file `AndroidCtx.android.kt` in `androidMain`: next to the
other `actual` implementations, but tied to nothing extra.

The manifest has nine permissions:

```
INTERNET                    ACCESS_NETWORK_STATE
FOREGROUND_SERVICE          FOREGROUND_SERVICE_SPECIAL_USE
ACCESS_FINE_LOCATION        ACCESS_COARSE_LOCATION
ACCESS_MOCK_LOCATION        POST_NOTIFICATIONS
QUERY_ALL_PACKAGES
```

Permission requests are not all in the manifest: `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` are requested on the welcome screen, and
`ACCESS_MOCK_LOCATION` is granted by hand in developer settings.

### 2.3. `:desktopApp`

Desktop only. Kotlin JVM, depends on `:composeApp` and
`compose.desktop.currentOs`. Entry point — `xyz.azraellab.zapp.desktop.MainKt`.

```kotlin
mainClass = "xyz.azraellab.zapp.desktop.MainKt"
targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
packageName = "zapp"
packageVersion = zappVersion
description = "ZAPP - network toolkit"
vendor = "ZAPP"
linux {
    appCategory = "Network"
    menuGroup = "Network"
}
```

---

## 3. Namespaces

`:app` and `:composeApp` cannot have the same `namespace` — the manifest merger
rejects duplicates. Hence:

| Module | namespace | Kotlin package |
|---|---|---|
| `:app` | `xyz.azraellab.zapp` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` | `xyz.azraellab.zapp.*` |

The `namespace` of an Android library and the Kotlin package are different
things. The namespace goes into generated `R` and `BuildConfig`, while the
Kotlin package defines code visibility across the classpath. Matching packages
across modules are normal; matching namespaces are not.

---

## 4. Source sets

Five source sets in `composeApp/src`:

| Source set | What it holds |
|---|---|
| `commonMain` | UI, pages, navigation, strings, models, `expect` declarations |
| `androidMain` | `expect` implementation via `Context`, `SharedPreferences` and SAF |
| `desktopMain` | implementation via files in `~/.config/zapp/` |
| `jvmShared` | JVM code shared by Android and desktop: processes, sockets, daemons |
| `desktopTest` | tests: components, palette, language, codecs, parsers |

`jvmShared` exists because part of the code is identical on both platforms but
cannot live in `commonMain`: work with processes, sockets and files is
JVM-only. Declared in `composeApp/build.gradle.kts`:

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

`commonMain` has 39 `expect` declarations (32 public and 7 `internal`).
`androidMain` and `jvmShared` have no
test source sets of their own: tests for the shared JVM part run from
`desktopTest`, where the shared JVM is already on the classpath.

---

# Part II. Interface

## 5. Design system

Everything lives in `APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/ui/theme/`.

### 5.1. Palette (`Color.kt`)

One scheme, dark: `AzraelDarkScheme`. There is no light scheme in the code —
no theme switch either, and there is nothing to persist between launches.

| Token | Value | Role |
|---|---|---|
| `AzraelPrimary` | `#4ADE80` | accent |
| `AzraelOnPrimary` | `#07170C` | text on accent |
| `AzraelBgDeep` | `#0A0A0A` | dark scheme background |
| `AzraelBgCard` | `#0DFFFFFF` | translucent card |
| `AzraelBorder` | `#2EFFFFFF` | decorative border |
| `AzraelTextDim` | `#D9EDEDED` | muted text |
| `AzraelDanger` | `#F43F5E` | destructive action |
| `AzraelWarning` | `#F59E0B` | warning |
| `AzraelInfo` | `#38BDF8` | info |
| `AzraelSuccess` | `#22C55E` | success |
| `AzraelStrong` | `#16A34A` | strong success |
| `AzraelErrorSoft` | `#F87171` | soft error |

Ratings in a row: `#F4585A` — poor, `#F59E0B` — medium, `#22C55E` — good,
`#16A34A` — excellent. `#FFB74D` and `#E6C86A` have roles in code but are not
shown on screen yet.

Light scheme: background `#FAFAFA`, surface `#FFFFFF`, `primary` — `#166534`,
`outline` — `#78787F`.

`#166534` was chosen instead of `#15803D` on purpose: `#15803D` gives 4.14:1
contrast, which is not enough for text on a button.

### 5.2. Two borders

`outline` and `outlineVariant` are split by meaning, and a test checks it:

- `outline` — the semantic border of a control. WCAG 1.4.11 requires 3:1, and
  both schemes deliver (3.77 and 4.20 against the page background);
- `outlineVariant` — decorative: glass card, divider, passive backdrop. The 3:1
  rule does not apply: you do not need to spot it by eye to start interacting.

Earlier both borders came from one place and silently drifted apart: `outline`
here, `alpha = 0.10` of `onSurface` there. Now the tokens are split by meaning,
not by looks, and the formulas live in `Contrast.kt` as a separate file — a
test must not repeat the numbers it checks.

### 5.3. Spacing (`Spacing.kt`)

Scale: `xxs` 2, `xs` 4, `sm` 8, `md` 12, `lg` 16, `xl` 24, `xxl` 32 (in dp).

Screens: `screenPadding` 16, `cardPadding` 14, `cardGap` 12, `listGap` 8,
`fieldGap` 10, `controlHeight` 44, `controlHeightSm` 36, `touchTarget` 48,
`divider` 1, `stroke` 1.

`touchTarget` 48 is the WCAG 2.5.8 minimum for a finger, while `controlHeight`
44 is what the eye sees. Different numbers for different jobs, hence two
tokens.

### 5.4. Corner radii (`Shape.kt`)

`xs` 6, `sm` 10, `md` 14, `lg` 20, `xl` 26, `glass` 22 (in dp).

### 5.5. Typography (`Type.kt`)

`FontFamily.Default`, `LineHeightStyle.Alignment.Center`. `display*` headings
are Bold and SemiBold with negative tracking from −1.0 to −0.25; `body` and
`label` text is Normal and Medium with tracking from 0.1 to 0.4. Negative
tracking for large text and positive for small — opposite rules, otherwise
either headings fall apart or small text is unreadable up close.

### 5.6. Ripple (`Ripple.kt`)

`ripple(bounded, onSurface)`, wrapped in a separate indication theme so that
components do not set up ripple every time from scratch.

### 5.7. Background (`StarfieldBackground.kt`)

The starfield is drawn on a canvas, not as an image: an image cannot be
inverted for the theme, and it does not react to theme changes at runtime.

```kotlin
const val STAR_COUNT = 110
const val HIT_FRAMES = 90
const val MIN_COMETS = 1
const val MAX_COMETS = 5
const val COMET_SPAWN_MIN_MS = 2600
const val COMET_SPAWN_MAX_MS = 7200
private const val TRAIL_LEN_PX = 120f
```

Star coordinates are stored as screen fractions (0…1), otherwise stars scatter
on rotation or at another size. Comet radii are in pixels instead: a comet tail
must be the same length regardless of window size.

There is no light theme, so stars are always light on a dark background and no
inversion is needed. Repainting already created stars is done by
`LaunchedEffect(palette)`: a keyless `remember` would survive a scheme change
and leave the stars in the old color.

A star and a comet intersect — the comet gives a flash, and that is what
`HIT_FRAMES` is for.

---

## 6. Screens

Entry point is `App.kt`: it creates `AppState`, loads the config and hands the
state to `AppRoot(state)` inside `AppThemeRoot`. There is nothing else in
`App.kt`; all the work is in `core/` and `ui/`.

### 6.1. Navigation

`ui/nav/AppRoot.kt` holds the bottom bar and the selected section. Six tabs
from `ui/nav/AppTab.kt`:

| Tab | Content |
|---|---|
| `TOR` | bridges, sources, Tor bootstrap |
| `VPN` | profiles, connects, tunnel launch |
| `ZAPRET` | DPI bypass strategies |
| `GOODBYE_DPI` | the second bypass engine |
| `GPS` | coordinate spoofing |
| `SETTINGS` | settings, help, log |

Tab availability depends on the platform: `AppTab.available(gpsSupported)`
hides GPS on desktop, where there is no coordinate spoofing. Six tabs on
Android, five on desktop.

Traffic monitoring is not a tab. `state.trafficWindowOpen` switches the screen
to `ui/traffic/TrafficWindow.kt`, and the navigation bar disappears there: this
window is opened from settings and closed with the X.

### 6.2. Pages

Every page lives in `ui/pages/` and is built on `PageScaffold` /
`PageGroup` from `ui/pages/PageScaffold.kt`: title, settings groups, uniform
spacing.

| File | What it does |
|---|---|
| `TorPage.kt` | bridges, sources, download progress |
| `VpnPage.kt` | profile, route, auto-switching, connection status |
| `VpnProfileEditor.kt` | VPN profile editor |
| `ZapretPage.kt` | strategies, families, advanced block, presets |
| `GoodbyeDpiPage.kt` | modes, TLS splitter, domain list |
| `GpsPage.kt` | mode, coordinates, route, app allowlist; empty state where the feature is missing |
| `SettingsPage.kt` | language, traffic, presets (export, import, reset), help |
| `DaemonControl.kt` | daemon control |
| `PerAppSection.kt` | app selection |
| `RoutingCards.kt` | routing cards |
| `ErrorReason.kt` | connection error reason |
| `DevTestsPage.kt` | dev tests showcase |

### 6.3. State

`core/AppState.kt` — the single source of truth for the UI. Inside: the config
model by section, traffic history and summary, flags (`trafficWindowOpen`),
counters, and `PresetMessage` for the result of a preset operation.

Two rules make messages and labels reach the UI as type `Str` instead of
`String`:

- `tr(Str)` — `@Composable`, and it cannot be called inside `onClick`;
  translation happens at composition time, and the lambda gets the `Str` itself;
- state stores a key, not a ready-made string, so a language change redraws
  labels without a restart.

Config writes are deferred: a flag change goes through `debounce` into
`ConfigStore`, otherwise a slider would write to `SharedPreferences` on every
move.

### 6.4. Presets

`PresetFileGateway` — `suspend read()` / `write(content, name)`, implemented
per platform: an AWT dialog on desktop (unavailable in headless,
`available = false`), SAF on Android via `installPresetFileBridge` called from
`MainActivity`. `AppState.importPreset()` and `exportPreset()` return
`PresetMessage` instead of writing to the UI directly.

The preset file format is described in [DEVELOPMENT.md](DEVELOPMENT.md).

---

## 7. Theme and language

There is one theme, dark: `AzraelTheme` without a mode parameter always returns
`AzraelDarkScheme`. There is no light scheme in the code, no switch, and
nothing to persist between launches. `AppThemeStore`, `AppThemeMode` and all
their `actual` implementations are deleted.

### 7.1. Language storage

The language lives on `expect`/`actual` and survives a restart.

| Platform | Storage |
|---|---|
| Android | `SharedPreferences` |
| Desktop | `~/.config/zapp/lang` |

On desktop the write is atomic: first a temp file, then a rename. A crash in
the middle would otherwise leave a file the next launch could not read.

### 7.2. Language selection

Three languages: EN, RU, ZH. Rule order:

1. the user's explicit choice, if made;
2. otherwise the system language by country and language codes;
3. otherwise English.

`AppLangStore.systemCode()` returns the full tag (`ro-MD`, `zh-Hans-CN`), not
just the language. In Moldova the system language is `ro`, there is no separate
"Moldovan" code (`mo` is obsolete in ISO 639-1), and Russian is set there by
the `MD` region. Earlier only `language` was returned, and a real `ro-MD` fell
through to English.

The region is checked separately from the language because `ro` without a
region is just Romanian, and Romania is not in the CIS. Moldova becomes Russian
by `ro-MD`.

The second component of the code must not be taken: in `zh-Hans-CN` it is the
script `Hans`, not a country. So the last component exactly two letters long is
taken.

The check order matters: Chinese is checked before the region, otherwise `zh-TW`
and `zh-HK` would end up in Russian.

CIS languages that yield a Russian interface: `ru`, `be`, `uk`, `kk`, `ky`,
`tg`, `tk`, `uz`, `az`, `hy`, `ka`. Countries: `RU`, `BY`, `UA`, `KZ`, `KG`,
`TJ`, `TM`, `UZ`, `AZ`, `AM`, `GE`, `MD`.

German or Turkish default to English: a Russian interface will be appreciated
in Kazakhstan, not in Germany or Turkey.

### 7.3. Strings (`core/Str.kt`)

Keys are `enum` elements, not strings. A forgotten translation then fails to
compile instead of showing up on screen as `CORE_START`.

The file has 1349 lines and **463 `enum` keys**, each translated into three
languages. Proper names (`ZAPP`, `VPN`, `Zapret`, `GoodbyeDPI`, `GPS`) and
abbreviations are identical in all three branches — that is how the eye finds
them, and keeping three identical copies is pointless.

`Str.of` is designed to support `null` as "the same on all languages", but no
key uses it right now: the degenerate case was handled by hand, and it is
visible by eye. If there are more keys, this case is worth bringing back.

### 7.4. In-app help

`core/Docs.kt` — texts of the "Documentation" tab: **7 sections and 11 items**,
localized with the same trio (EN, RU, ZH). Sections: VPN, Zapret, GoodbyeDPI,
GPS, Presets, Log, Permissions.

---

# Part III. Build

## 8. Build

### 8.1. Requirements

- JDK 21, full, with `jpackage` (JBR from Android Studio does not qualify);
- Android SDK with platform 37.2;
- on NixOS — `objcopy` from binutils, see section 9.

### 8.2. Android

```bash
cd APP
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/
```

Each command builds both flavors (`root` and `noroot`), and each flavor splits
into five APKs.

### 8.3. Desktop

```bash
cd APP
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageMsi
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

### 8.4. The `APP/build-all.sh` script

| Mode | What it does |
|---|---|
| `dev` | debug APK + uber-JAR |
| `release` | debug and release APK + current OS distribution + uber-JAR |
| `linux` | AppImage + `.deb` |

```bash
./APP/build-all.sh dev
./APP/build-all.sh linux
./APP/build-all.sh release
```

The script finds a full JDK with `jpackage` and `objcopy` in the nix store on
its own, because they are not in `PATH`. Results go to `APP/build/dist/`.

The script calls `scripts/finish-deb.sh`, which is not in the repository. On a
clean machine it is missing, and then the `.deb` stays raw and gets the name
`zapp_UNFINISHED_amd64.deb` — so a finished package cannot be confused with a
raw one.

### 8.5. APK split by ABI

Each variant builds into five APKs: four by architecture and one universal.

| File | What it is |
|---|---|
| `ZAPP[-ROOT]-<ver>[-debug].apk` | universal, all four ABIs |
| `ZAPP[-ROOT]-<ver>[-debug]-<abi>.apk` | one ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

The reason for the split is native daemons. Without it an APK carries **four
copies** of the same `lib/*.so` (about 29 MB each), and release comes out at
121 MB, of which dex with resources is only 3.4 MB. The split gives the device
only its own architecture: a phone on `arm64-v8a` gets about 32 MB instead of
121.

Enabled by the `splits.abi` block in `APP/app/build.gradle.kts`; each output
gets its name from `androidComponents.onVariants` via `output.filters` (the
universal APK has no filter, hence no suffix). `x86` and `x86_64` are not
needed by real devices — they exist only for the emulator — but they stay in
the universal APK.

All five APKs of one variant share the same `versionCode`, so installing any
over any is a normal replacement, not a downgrade.

### 8.6. Artifact portability

A build on NixOS bakes `/nix/store` into the launcher interpreter and the jlink
runtime, so the AppImage and `.deb` from it will not run on Debian, Ubuntu and
Windows. Verified: the ELF file `bin/zapp` inside the package has `interpreter`
pointing to `/nix/store/...-glibc-2.42/lib/ld-linux-x86-64.so.2`, and the same
in `libapplauncher.so` and in `lib/runtime/lib/*.so`.

The script unpacks each artifact and sorts it into lists:

| List | What goes in |
|---|---|
| `PUBLISHABLE` | APK and uber-JAR |
| `UNPUBLISHABLE` | AppImage and `.deb` |

Unpacking is mandatory. AppImage is squashfs, `.deb` is a compressed tar in ar,
`.apk` and `.jar` are zip. Searching for bytes directly in a file inside a
compressed container finds nothing and declares the NixOS build fit for
publishing. So `.deb` is unpacked via `dpkg-deb -x`, zip via `unzip -p`, and
the AppImage is checked by the appdir it was built from.

Full publishable distributions are built by CI on `ubuntu` and `windows`.

Sizes built on this machine: APK 58.6 MB, uber-JAR 74.6 MB, finished `.deb`
85.7 MB, AppImage 100.9 MB. The uber-JAR was verified by running: the process
lives and does not crash.

### 8.7. `.deb`

The raw jpackage package is unusable for five reasons, and `finish-deb.sh`
covers them:

1. `/usr/bin/zapp` — a symlink launcher. jpackage puts the executable only in
   `/opt/<пакет>/bin/` and does nothing in `/usr/bin`, so after install the
   command cannot be typed in a terminal, only clicked in the menu;
2. `/usr/share/applications/zapp.desktop` — jpackage registers the `.desktop`
   only by calling `xdg-desktop-menu install` from `postinst`, and without
   `xdg-utils` the menu entry simply does not appear;
3. `/usr/share/icons/hicolor/512x512/apps/zapp.png` — so the icon is found by
   the standard instead of an absolute path in `/opt`;
4. `Depends` in `control` is replaced wholesale. jpackage computes dependencies
   with `jdeps`, and on NixOS it finds no libraries outside `/usr/lib`, so
   `control` ends up with a lone `xdg-utils` and no `libstdc++6` and
   `libx11-6`. On a real Debian machine the package would install and then
   crash at launch;
5. jpackage's `prerm` removes the menu with a bare `xdg-desktop-menu
   uninstall`, no `command -v` and no `|| true` under `set -e`, so `apt remove`
   would fail with code 1.

`libasound2` is written as `libasound2 | libasound2t64`: on Debian 13
(trixie) the package is called `libasound2t64`, on bookworm — `libasound2`.

`finish-deb.sh` must be run on a `*_amd64.deb` file without the `_zapp`
suffix, otherwise you get `*_zapp_zapp.deb`.

---

## 9. Build gotchas

Collected here is what breaks regularly and is not obvious from the symptom.
Order — by how often it comes up.

### 9.1. Hung APK packaging: common pool starvation

`APP/gradle.properties` contains `org.gradle.parallel=false` and
`org.gradle.workers.max=1`. The real protection is the second flag, and it is
worth understanding why: the symptom is misleading.

AGP writes and signs APKs through `ForkJoinPool.commonPool()` (zipflinger and
apksig), one pool for the whole daemon. ABI splits are launched not by Gradle
but by AGP itself: for each ABI it puts a separate
`IncrementalSplitterRunnable` into `workerExecutor.noIsolation()`, and five
packagers of one `package<Variant>` task run at once. Then:

1. each packager writes entries through `ApkFlinger.writeFile` into the shared
   pool;
2. `ApkFlinger.close()` first waits for its own entries, then closes the
   archive;
3. closing the archive is the V2 signature: `computeOneMbChunkContentDigests`
   submits exactly `parallelism` tasks to the same pool and waits for **all of
   them at once** (`IncrementalPackagerBuilder.lambda$withSigning$0`);
4. if the pool holds other packagers' entries, there are never enough free
   workers. This is starvation, not a race: the behavior is deterministic.

The build stops at `packageRootRelease` without a single line of output and
never finishes. The CI log has no exception and no reason — only a hint to run
with `--stacktrace`, because there is no exception here: nobody crashes,
everyone just waits.

`org.gradle.parallel=false` **does not affect** this situation: it serializes
tasks, while the competition lives inside one. Turning off parallelism did not
save the build. `org.gradle.workers.max=1` fixes it — a single packaging
worker, so there are no foreign entries in the pool at signing time. Verified
with three clean packaging cycles: all 20 APKs each time with the full set of
libraries.

To find where it hangs, look at the `> Task :app:package...` lines in the log:
the last such line is the hang point.

### 9.2. Plugins in the root `build.gradle.kts`

All plugins are listed with `apply false`. This is not a style choice: AGP
pulls `kotlin-gradle-plugin` 2.2.x transitively, and if our
`kotlin.multiplatform` is not on the classpath, Gradle stays on the version
from AGP. With the list the conflict resolves in favor of 2.4.20, otherwise an
`--offline` build fails with `No cached version of ... 2.2.10`.

### 9.3. Gradle 9 and accessors

The `projects.composeApp` accessor does not resolve. Use
`project(":composeApp")`.

### 9.4. Tests without `--rerun-tasks`

An unchanged task reports `UP-TO-DATE`, and the test counter is not
recalculated. Always run with `--rerun-tasks`.

### 9.5. `objcopy` on NixOS

`jlink` and `jpackage` require `objcopy`, and it is not in `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

Without it `createRuntimeImage` fails with `External tool execution failed`.

### 9.6. `fakeroot` and `dpkg-deb`

`.deb` is built through fakeroot and dpkg-deb. On NixOS they are not in `PATH`
either, and without them the task fails with `Invalid or unsupported type: [deb]`.

### 9.7. The `packageAppImage` task

The task completes with `BUILD SUCCESSFUL` and **creates no AppImage**:
jpackage only produces the `app/zapp` directory with the binary and libraries.
The real AppImage is built by the external `appimagetool`, so in CI it is
installed explicitly, and `AppRun`, `.desktop` and the icon are assembled by
hand. Verified: after the task the directory contains only the `zapp`
subdirectory and no `.AppImage` file.

### 9.8. JDK requirements

JBR from Android Studio does not contain `jpackage`. A full JDK 21 is needed
for the desktop build. The Gradle daemon holds JBR, so before a release build:

```bash
./gradlew --stop
```

### 9.9. File ownership

A build run as root creates root-owned `build/` directories and breaks Android
Studio for the regular user. After building:

```bash
chown -R azrael:users .
chmod -R u+w .
```

---

## 10. APK signing

### 10.1. Local signing

There is no keystore in the repository and there should not be: `signing/` is
in `.gitignore`. Signing is done locally through
`APP/signing/keystore.properties`:

```properties
# signing/keystore.properties, this directory is in .gitignore
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without this file the release build comes out unsigned — that is how forks and
CI build without secrets.

### 10.2. Signature check

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<ver>.apk
```

`keytool -printcert -jarfile` on an APK answers `Not a signed jar file`. That
is not a signing error — the tool simply cannot do it.

---

# Part IV. Checks and release

## 11. Tests

The suite lives in `composeApp/src/desktopTest` — 18 files, **1303 cases**.

| File | What it checks |
|---|---|
| `ui/theme/ThemeTest` | WCAG contrast of palette pairs |
| `ui/components/ComponentsTest` | component contrast |
| `ui/nav/AppTabTest` | tab set and order |
| `core/AppConfigCodecTest` | codecs of `AppConfig` and `VpnConfig` |
| `core/dev/DevTestsParameterized` | run of all `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | protocol link parsing |
| `core/net/SingboxConfigTest` | sing-box config generation |
| `core/net/WireGuardIniTest` | WireGuard config parsing |
| `core/TorBridgesTest` | bridge selection |
| `core/dpi/SplitRelayTest` | split relay math |
| `core/gps/RouteProgressTest` | route progress |
| `core/update/VersionCompareTest` | version comparison |
| `core/I18nTest`, `LinkSpeedTest`, `UsageTunerTest` | locales, speed, profile |
| `core/engine/TorBootstrapPercentTest` | Tor bootstrap percent |
| `core/VpnConnectsTest` | connect list |

Out of 1303 cases, 1165 come from the parameterized run
`DevTestsParameterized` — that is all `core/dev/*Suite.kt` (24 suites); the
other 138 are in the direct tests above.

The most valuable thing here is not the count but that the tests do not let
things pass silently:

- `SparklineSuite` and `DnsFailoverSuite` cover pure math that the UI runs:
  the sparkline on the tunnel status card and automatic DNS server selection
  before the daemon starts;
- substituting `#D4D4D8` for `outline` fails `ThemeSuite` — checked by hand.
  A color that reads as a visible frame on a dark background must not go
  unnoticed.

Run:

```bash
cd APP
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

`--rerun-tasks` is mandatory, otherwise the task reports `UP-TO-DATE` and the
counter is not recalculated.

---

## 12. CI build and release

The file is `.github/workflows/build.yml`, four jobs:

| Job | Runner | Artifacts |
|---|---|---|
| `android` | `ubuntu-latest` | debug and release APKs by ABI splits, release signed with a secret keystore |
| `windows` | `windows-latest` | MSI |
| `linux` | `ubuntu-latest` | AppImage |
| `release` | `ubuntu-latest` | GitHub Release on tag `v*` |

Triggers: push to `main`, tag push `v*`, pull request to `main`, manual run.
Runs are grouped by `build-${{ github.ref }}` with `cancel-in-progress`: a push
to a branch cancels its unfinished run, while a tag lives in its own group and
builds in parallel with the branch.

All Gradle tasks run from the `APP/` directory, because the repository root
also holds `README.md`, `ABOUT.md` and the other documents.

### 12.1. Native binaries

Before building, the `android` job downloads the native daemons from the
release tagged `native-bins` (`native-jniLibs.tar.gz`) into
`APP/app/src/main/jniLibs/`. Without them the APK builds but has no VPN, Zapret
or Tor and fails the `libbox.so` check.

The repository itself holds only scripts and patches (`native/*.sh`,
`native/patches/`), not binaries: `native/out/` and `native/src/` are in
`.gitignore`.

### 12.2. Signing in CI

There is no keystore in the repository and there should not be. The `android`
job at the `Prepare release keystore` step lays out the repository secrets
(`KS_B64`, `KS_PASS`, `KS_ALIAS`, `KEY_PASS`) into `APP/signing/` and writes
`keystore.properties`, after which the `release` build type signs itself. If
the secrets are not set, the step prints a warning and release builds
unsigned, but such an APK does not make it into the release — the filter drops
`*unsigned*`.

### 12.3. Release contents

The `release` job collects two selections from the artifacts:

```bash
find artifacts -type f \( -name '*.msi' -o -name '*.AppImage' \)
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

Each APK is checked for `libbox.so` inside before being added: without the
native daemon it does not go into the release. The wildcards in the filter are
required on both sides: a split name ends with the architecture
(`ZAPP-2.7.0-debug-x86.apk`), and the `*-debug.apk` variant let eight debug
builds through while the universal ones were cut at the same time.

If there are no production artifacts at all, no release is created — not an
empty one. The contents of release v2.7.0: ten release APKs (five each for
`root` and `noroot`), MSI and AppImage.

### 12.4. `.deb` in CI

`.deb` is not built in CI at all: the raw jpackage package has no `Depends`,
no symlink in `/usr/bin`, no `.desktop` and no icon, and publishing it in that
state is not acceptable. The finishing step is `scripts/finish-deb.sh`, but
`scripts/` is in `.gitignore` and does not go into the repository, so `.deb`
remains for local builds via `build-all.sh`.

---

## 13. File map

```
ZAPP/
├── README.md                      home page, three languages
├── ABOUT.md                       what the app does, three languages
├── DOCX.md                        this document
├── DEVELOPMENT.md                 fork guide and preset format
├── LICENSE
├── lock.svg, unlock.svg           launcher icons
├── native/                        scripts and patches that build the daemons
└── APP/
    ├── gradle/libs.versions.toml     versions of every library
    ├── gradle.properties             app version
    ├── build.gradle.kts              plugins with apply false, autoBuild
    ├── build-all.sh                  builds every platform
    ├── app/                          Android application
    │   ├── proguard-rules.pro
    │   └── src/main/
    │       ├── AndroidManifest.xml        9 permissions, 2 launchers
    │       ├── java/xyz/azraellab/zapp/MainActivity.kt
    │       └── res/                       icons, themes, strings, backup rules
    ├── composeApp/                   shared code
    │   └── src/
    │       ├── commonMain/kotlin/xyz/azraellab/zapp/
    │       │   ├── App.kt              remember { AppState() }, AppRoot(state)
    │       │   ├── core/               AppConfig, AppState, models, presets,
    │       │   │                       ConfigStore, TrafficSource, Str (463 keys),
    │       │   │                       AppLang, Docs (7 sections),
    │       │   │                       engine/ (tunnel), daemon/ (daemons, NFQUEUE),
    │       │   │                       net/ (SingboxConfig, DnsFailover, parsers),
    │       │   │                       traffic/ (TrafficMeter, Sparkline), dev/ (suites)
    │       │   └── ui/
    │       │       ├── AppLang.kt        language state, tr()
    │       │       ├── StarfieldBackground.kt
    │       │       ├── Theme.kt          AppThemeRoot
    │       │       ├── components/       buttons, banner, input, card, palette
    │       │       ├── nav/              AppRoot, AppTab (6 tabs)
    │       │       ├── pages/            Tor, VPN, Zapret, GoodbyeDPI, GPS, Settings
    │       │       ├── traffic/          TrafficWindow
    │       │       └── theme/            Color, Spacing, Shape, Type, Ripple
    │       ├── androidMain/.../core/      AndroidCtx, ConfigStore (SAF),
    │       │                              TrafficSource (JNI), PlatformCaps
    │       ├── desktopMain/.../core/      ConfigStore (files), TrafficSource (/proc)
    │       ├── jvmShared/.../core/        shared JVM half: net, daemon, engine,
    │       │                              probe, dpi, root, geo, update (21 files)
    │       └── desktopTest/.../           1303 cases, 18 files
    └── desktopApp/                   desktop application
        └── build.gradle.kts         mainClass, packaging formats
```

---

## 14. Checks after changes

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd APP
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopTest --offline --rerun-tasks
./gradlew :app:assembleDebug
./gradlew :desktopApp:createDistributable
```

Before committing as root:

```bash
chown -R azrael:users .
chmod -R u+w .
```

Check that the app does not crash on a device:

```bash
adb install -r app/build/outputs/apk/root/debug/ZAPP-ROOT-<ver>-debug.apk
adb shell am start -n xyz.azraellab.zapp/.MainActivity
adb logcat -d | grep -iE "FATAL|AndroidRuntime"
```

A clean install instead of an update is required: what is checked is the first
launch.

---

## 15. What is left

Done: the shared code builds for both platforms, the design system is
complete, tests are green (1303 cases). Working: connections —
WireGuard/AmneziaWG, OpenVPN, sing-box (vless, vmess, trojan, ss, hysteria2,
tuic), Tor over bridges, kill-switch; DPI bypass — zapret and goodbyedpi on one
NFQUEUE chain with automatic strategy selection; traffic monitoring with a
sparkline and a session counter; automatic DNS swap before the tunnel starts.

Not done: GPS scenarios and map (the settings page exists), Nix and PKGBUILD.
release-APK signing works both locally (`APP/signing/keystore.properties`) and
in CI (repository secrets).

The stage queue, same as in README:

| Stage | Contents | Criterion |
|---|---|---|
| 11 | GPS: scenarios and map | coordinate spoofing visible in a third-party app |
| 12 | packaging: Nix, PKGBUILD | all formats build from a clean checkout |
| 13 | polish, i18n, signing | done: EN/RU/ZH, CI publishes a signed release |

</details>

<details>
<summary><b>🇷🇺 Русский</b></summary>

Документ описывает, что лежит в проекте, чем он собран и где что находится.
Все версии и числа взяты из конфигов и из собранных артефактов, а не из
описания. Там, где значение проверялось запуском, это отмечено.

Другие документы: [README.md](README.md) — главная страница репозитория,
[ABOUT.md](ABOUT.md) — что делает приложение простыми словами,
[DEVELOPMENT.md](DEVELOPMENT.md) — гайд по форку и формат пресетов.

## Содержание

**Часть I. Устройство**

1. [Стек](#1-стек)
2. [Модули](#2-модули)
3. [Пространства имён](#3-пространства-имён)
4. [Наборы исходников](#4-наборы-исходников)

**Часть II. Интерфейс**

5. [Дизайн-система](#5-дизайн-система)
6. [Экраны](#6-экраны)
7. [Тема и язык](#7-тема-и-язык)

**Часть III. Сборка**

8. [Сборка](#8-сборка)
9. [Грабли сборки](#9-грабли-сборки)
10. [Подпись APK](#10-подпись-apk)

**Часть IV. Проверки и выпуск**

11. [Тесты](#11-тесты)
12. [Сборка в CI и выпуск](#12-сборка-в-ci-и-выпуск)
13. [Карта файлов](#13-карта-файлов)
14. [Проверки после изменений](#14-проверки-после-изменений)
15. [Что ещё предстоит](#15-что-ещё-предстоит)

---

# Часть I. Устройство

## 1. Стек

| Слой | Что | Версия | Где прописано |
|---|---|---|---|
| Язык | Kotlin | 2.4.20 | `APP/gradle/libs.versions.toml` |
| Сборка | Gradle | 9.4.1 | `APP/gradle/wrapper/gradle-wrapper.properties` |
| Android-плагин | AGP | 9.2.1 | `APP/gradle/libs.versions.toml` |
| Интерфейс | Compose Multiplatform | 1.12.1 | там же |
| Сериализация | kotlinx-serialization-json | 1.9.0 | там же |
| Android-активити | androidx.activity:activity-compose | 1.13.0 | там же |
| Android-ядро | androidx.core:core-ktx | 1.10.1 | там же |
| Тесты | kotlin("test") | 2.4.20 | `APP/composeApp/build.gradle.kts` |
| Упаковка десктопа | jpackage, jlink | JDK 21 | `APP/desktopApp/build.gradle.kts` |
| AppImage | appimagetool | continuous | `.github/workflows/build.yml` |
| Целевая ОС Android | API 33 и выше | — | `APP/app/build.gradle.kts` |
| Целевая ОС Android | targetSdk 36 | — | там же |

Единая точка правды по версиям — `APP/gradle/libs.versions.toml`. В
`build.gradle.kts` версии не дублируются, используются только псевдонимы.

Версия самого приложения живёт отдельно, в `APP/gradle.properties`:

```properties
zappVersion=2.7.0
```

Её читают `APP/app/build.gradle.kts` как `versionName` и
`APP/desktopApp/build.gradle.kts` как `packageVersion`, чтобы APK и десктопный
дистрибутив не разошлись по версиям. Проверено на собранном APK:
`versionName=2.7.0`, `versionCode=9`, `minSdk=33`, `targetSdk=36`,
`compileSdk=37.2`.

---

## 2. Модули

Три модуля внутри `APP/`. Общий код один, платформенные части разъезжаются по
наборам исходников внутри модуля.

### 2.1. `:composeApp`

Главный модуль. Kotlin Multiplatform с двумя целями:

- `androidLibrary` — Android-библиотека, `namespace = xyz.azraellab.zapp.shared`,
  `compileSdk = 37`, `minSdk = 33`, `jvmTarget = JVM_11`;
- `jvm("desktop")` — обычная JVM-библиотека.

Зависимости `commonMain`:

```kotlin
implementation(compose.runtime)
implementation(compose.foundation)
implementation(compose.material3)
implementation(compose.ui)
implementation(compose.materialIconsExtended)
implementation(libs.kotlinx.serialization.json)
```

`desktopMain` добавляет `compose.desktop.currentOs`, `androidMain` —
`androidx.activity.compose`, `desktopTest` — `kotlin("test")`.

`androidResources` включены, потому что дизайн-система тянет ресурсы Compose.

### 2.2. `:app`

Только Android. Приложение, а не библиотека.

| Параметр | Значение |
|---|---|
| `namespace` | `xyz.azraellab.zapp` |
| `applicationId` | `xyz.azraellab.zapp` |
| `minSdk` | 33 |
| `targetSdk` | 36 |
| `compileSdk` | 37, `minorApiLevel` 2 |
| `sourceCompatibility` | Java 11 |

Release-сборка включает минификацию и усадку ресурсов:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Одна рабочая активити — `MainActivity` на `ComponentActivity`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AndroidCtx.attach(this)
    installPresetFileBridge(context = this, onPickRead = { ... }, onPickWrite = { ... })
    setContent { App() }
}
```

Два лаунчера SAF регистрируются полем класса, до `onCreate`: `registerForActivityResult`
бросает исключение, если его вызвать позже. `installPresetFileBridge` занимается
только переносом колбэков в `PresetFileGateway` — как именно выбрать файл,
общий код не знает, а приложение обязано отвечать на запрос сопротивляемым
вызовом (`suspend`).

Помимо `MainActivity` в манифесте объявлены два лаунчера — `LauncherLock` и
`LauncherUnlock`. Первый открывается по умолчанию и несёт флаг, который
запрещает снимок экрана, второй — обычный вход без этого ограничения.

`AndroidCtx` даёт доступ к `Context` из кода, которому `Context` передавать
нечем. Он объявлен отдельным файлом `AndroidCtx.android.kt` в `androidMain`:
там же, где остальные `actual`-реализации, но не привязан ни к чему лишнему.

В манифесте девять разрешений:

```
INTERNET                    ACCESS_NETWORK_STATE
FOREGROUND_SERVICE          FOREGROUND_SERVICE_SPECIAL_USE
ACCESS_FINE_LOCATION        ACCESS_COARSE_LOCATION
ACCESS_MOCK_LOCATION        POST_NOTIFICATIONS
QUERY_ALL_PACKAGES
```

Запрос прав не вынесен в манифест целиком: `ACCESS_FINE_LOCATION` и
`ACCESS_COARSE_LOCATION` запрашиваются на экране приветствия, а
`ACCESS_MOCK_LOCATION` пользователь выдаёт руками в настройках разработчика.

### 2.3. `:desktopApp`

Только десктоп. Kotlin JVM, зависит от `:composeApp` и
`compose.desktop.currentOs`. Точка входа — `xyz.azraellab.zapp.desktop.MainKt`.

```kotlin
mainClass = "xyz.azraellab.zapp.desktop.MainKt"
targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
packageName = "zapp"
packageVersion = zappVersion
description = "ZAPP - network toolkit"
vendor = "ZAPP"
linux {
    appCategory = "Network"
    menuGroup = "Network"
}
```

---

## 3. Пространства имён

`:app` и `:composeApp` не могут иметь одинаковый `namespace` — merger манифестов
ругается на дубли. Поэтому:

| Модуль | namespace | Пакет Kotlin |
|---|---|---|
| `:app` | `xyz.azraellab.zapp` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` | `xyz.azraellab.zapp.*` |

`namespace` Android-библиотеки и пакет Kotlin — разные вещи. Namespace попадает
в сгенерированные `R` и `BuildConfig`, а пакет Kotlin определяет видимость кода
по classpath. Совпадение пакетов у модулей нормально, совпадение namespace — нет.

---

## 4. Наборы исходников

Пять наборов в `composeApp/src`:

| Набор исходников | Что в нём |
|---|---|
| `commonMain` | интерфейс, страницы, навигация, строки, модели, объявления `expect` |
| `androidMain` | реализация `expect` через `Context`, `SharedPreferences` и SAF |
| `desktopMain` | реализация через файлы в `~/.config/zapp/` |
| `jvmShared` | JVM-код, общий для Android и десктопа: процессы, сокеты, демоны |
| `desktopTest` | тесты: компоненты, палитра, язык, кодеки, парсеры |

`jvmShared` появился потому, что часть кода одинакова для обеих платформ, но
не может лежать в `commonMain`: работа с процессами, сокетами и файлами
доступна только на JVM. Объявлено в `composeApp/build.gradle.kts`:

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

В `commonMain` — 39 объявлений `expect` (32 публичных и 7 `internal`).
Собственных тестовых наборов у
`androidMain` и `jvmShared` нет: тесты общей JVM-части идут из `desktopTest`,
где общая JVM уже в classpath.

---

# Часть II. Интерфейс

## 5. Дизайн-система

Всё лежит в `APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/ui/theme/`.

### 5.1. Палитра (`Color.kt`)

Схема одна, тёмная: `AzraelDarkScheme`. Светлой схемы в коде нет —
переключателя темы тоже нет, и хранить «тёмная/светлая» между запусками незачем.

| Токен | Значение | Роль |
|---|---|---|
| `AzraelPrimary` | `#4ADE80` | акцент |
| `AzraelOnPrimary` | `#07170C` | текст на акценте |
| `AzraelBgDeep` | `#0A0A0A` | фон тёмной схемы |
| `AzraelBgCard` | `#0DFFFFFF` | полупрозрачная карточка |
| `AzraelBorder` | `#2EFFFFFF` | декоративная граница |
| `AzraelTextDim` | `#D9EDEDED` | приглушённый текст |
| `AzraelDanger` | `#F43F5E` | опасное действие |
| `AzraelWarning` | `#F59E0B` | предупреждение |
| `AzraelInfo` | `#38BDF8` | информация |
| `AzraelSuccess` | `#22C55E` | успех |
| `AzraelStrong` | `#16A34A` | усиленный успех |
| `AzraelErrorSoft` | `#F87171` | мягкая ошибка |

Оценки подряд: `#F4585A` — бедно, `#F59E0B` — средне, `#22C55E` — хорошо,
`#16A34A` — отлично. Роли `#FFB74D` и `#E6C86A` в коде есть, но на экране
пока не показываются.

Светлая схема: фон `#FAFAFA`, поверхность `#FFFFFF`, `primary` — `#166534`,
`outline` — `#78787F`.

Значение `#166534` выбрано вместо `#15803D` сознательно: `#15803D` даёт
контраст 4.14:1, и этого не хватает для текста на кнопке.

### 5.2. Две границы

`outline` и `outlineVariant` разведены по смыслу, и это проверяется тестом:

- `outline` — смысловая граница элемента управления. WCAG 1.4.11 требует 3:1,
  обе схемы дают (3.77 и 4.20 к фону страницы);
- `outlineVariant` — декоративная: стеклянная карточка, разделитель, пассивная
  подложка. Правило 3:1 к ней не применяется: её не нужно искать глазом,
  чтобы начать взаимодействие.

Раньше обе границы брались из одного места и молча разъехались: где-то `outline`,
где-то `alpha = 0.10` от `onSurface`. Теперь токены разделены по смыслу, а не
по красоте, и формулы лежат в `Contrast.kt` отдельным файлом — тест не должен
повторять числа, которые он же и проверяет.

### 5.3. Отступы (`Spacing.kt`)

Шкала: `xxs` 2, `xs` 4, `sm` 8, `md` 12, `lg` 16, `xl` 24, `xxl` 32 (в dp).

Экраны: `screenPadding` 16, `cardPadding` 14, `cardGap` 12, `listGap` 8,
`fieldGap` 10, `controlHeight` 44, `controlHeightSm` 36, `touchTarget` 48,
`divider` 1, `stroke` 1.

`touchTarget` 48 — минимум WCAG 2.5.8 для пальца, а `controlHeight` 44 виден
глазом. Разные числа для разных задач, поэтому и токена два.

### 5.4. Скругления (`Shape.kt`)

`xs` 6, `sm` 10, `md` 14, `lg` 20, `xl` 26, `glass` 22 (в dp).

### 5.5. Типографика (`Type.kt`)

`FontFamily.Default`, `LineHeightStyle.Alignment.Center`. Заголовки `display*`
набраны Bold и SemiBold с отрицательным трекингом от −1.0 до −0.25, текст
`body` и `label` — Normal и Medium с трекингом от 0.1 до 0.4. Отрицательный
трекинг у крупного текста и положительный у мелкого — противоположные
правила, иначе либо заголовки разъезжаются, либо мелкий текст в упор не
читается.

### 5.6. Ripple (`Ripple.kt`)

`ripple(bounded, onSurface)`, обёрнут в отдельную тему индикации, чтобы
компоненты не задавали ripple каждый раз заново.

### 5.7. Фон (`StarfieldBackground.kt`)

Звёздное небо рисуется на канвасе, а не картинкой: картинку нельзя
инвертировать под тему, и она не реагирует на смену темы на лету.

```kotlin
const val STAR_COUNT = 110
const val HIT_FRAMES = 90
const val MIN_COMETS = 1
const val MAX_COMETS = 5
const val COMET_SPAWN_MIN_MS = 2600
const val COMET_SPAWN_MAX_MS = 7200
private const val TRAIL_LEN_PX = 120f
```

Координаты звёзд хранятся долями экрана (0…1), иначе на повороте экрана или
при другом размере звёзды разъезжаются. Радиусы комет, наоборот, в пикселях:
хвост кометы должен быть одинаковой длины независимо от размера окна.

Светлой темы нет, поэтому звёзды всегда светлые на тёмном фоне и инверсия не
нужна. Перекрашивание уже созданных звёзд делает `LaunchedEffect(palette)`:
`remember` без ключа пережил бы смену схемы и оставил звёзды старого цвета.

Звезда и кома пересекаются — кома даёт вспышку, для этого и нужен
`HIT_FRAMES`.

---

## 6. Экраны

Точка входа — `App.kt`: создаёт `AppState`, загружает конфиг и отдаёт
состояние `AppRoot(state)` в `AppThemeRoot`. Больше в `App.kt` ничего нет,
вся работа — в `core/` и `ui/`.

### 6.1. Навигация

`ui/nav/AppRoot.kt` держит нижнюю панель и выбранный раздел. Шесть вкладок из
`ui/nav/AppTab.kt`:

| Вкладка | Содержимое |
|---|---|
| `TOR` | мосты, источники, загрузка Tor |
| `VPN` | профили, коннекты, запуск туннеля |
| `ZAPRET` | стратегии обхода DPI |
| `GOODBYE_DPI` | второй движок обхода |
| `GPS` | подмена координат |
| `SETTINGS` | настройки, справка, журнал |

Доступность вкладки зависит от платформы: `AppTab.available(gpsSupported)`
прячет GPS на десктопе, где спуфинга координат не будет. На Android — шесть
вкладок, на десктопе — пять.

Мониторинг трафика — не вкладка. `state.trafficWindowOpen` переключает экран
на `ui/traffic/TrafficWindow.kt`, и панель навигации на нём исчезает: это окно
открывают из настроек и закрывают крестиком.

### 6.2. Страницы

Каждая страница лежит в `ui/pages/` и строится на `PageScaffold` /
`PageGroup` из `ui/pages/PageScaffold.kt`: заголовок, группы настроек, единые
отступы.

| Файл | Что делает |
|---|---|
| `TorPage.kt` | мосты, источники, прогресс загрузки |
| `VpnPage.kt` | профиль, маршрут, автопереключение, статус подключения |
| `VpnProfileEditor.kt` | редактор VPN-профиля |
| `ZapretPage.kt` | стратегии, семейства, продвинутый блок, пресеты |
| `GoodbyeDpiPage.kt` | режимы, TLS-сплиттер, список доменов |
| `GpsPage.kt` | режим, координаты, маршрут, белый список приложений; пустое состояние там, где функции нет |
| `SettingsPage.kt` | язык, трафик, пресеты (экспорт, импорт, сброс), справка |
| `DaemonControl.kt` | управление демонами |
| `PerAppSection.kt` | выбор приложений |
| `RoutingCards.kt` | карточки маршрутизации |
| `ErrorReason.kt` | причина ошибки подключения |
| `DevTestsPage.kt` | витрина dev-тестов |

### 6.3. Состояние

`core/AppState.kt` — единственный источник правды для интерфейса. Внутри:
модель конфига по разделам, история и сводка трафика, флаги
(`trafficWindowOpen`), счётчики и `PresetMessage` для результата операции
с пресетом.

Два правила, из-за которых сообщения и подписи приходят в интерфейс типом
`Str`, а не `String`:

- `tr(Str)` — `@Composable`, и его нельзя вызывать внутри `onClick`;
  перевод делают в момент композиции, а в лямбду кладут сам `Str`;
- состояние хранит ключ, а не готовую строку, поэтому смена языка
  перерисовывает подписи без перезапуска.

Запись конфига отложенная: изменение флага через `debounce` уходит в
`ConfigStore`, иначе ползунок писал бы в `SharedPreferences` на каждое
движение.

### 6.4. Пресеты

`PresetFileGateway` — `suspend read()` / `write(content, name)`, реализован
на каждой платформе по-своему: на десктопе — диалог AWT (в headless недоступен,
`available = false`), на Android — SAF через `installPresetFileBridge`,
вызванный из `MainActivity`. `AppState.importPreset()` и `exportPreset()`
возвращают `PresetMessage`, а не пишут в интерфейс напрямую.

Формат файла пресета описан в [DEVELOPMENT.md](DEVELOPMENT.md).

---

## 7. Тема и язык

Тема одна, тёмная: `AzraelTheme` без параметра режима всегда отдаёт
`AzraelDarkScheme`. Светлой схемы в коде нет, переключателя нет, хранить
между запусками нечего. `AppThemeStore`, `AppThemeMode` и все их `actual`-
реализации удалены.

### 7.1. Хранение языка

Язык живёт на `expect`/`actual` и переживает перезапуск.

| Платформа | Хранилище |
|---|---|
| Android | `SharedPreferences` |
| Десктоп | `~/.config/zapp/lang` |

На десктопе запись атомарная: сначала временный файл, затем переименование.
Падение на середине иначе оставило бы файл, который следующий запуск не смог
бы прочитать.

### 7.2. Выбор языка

Три языка: EN, RU, ZH. Порядок правил:

1. явный выбор пользователя, если он сделан;
2. иначе язык системы по кодам стран и языков;
3. иначе английский.

`AppLangStore.systemCode()` отдаёт полный тег (`ro-MD`, `zh-Hans-CN`), а не
только язык. В Молдове язык системы `ro`, отдельного кода «молдавский» нет
(в ISO 639-1 код `mo` устарел), и русский там ставится по региону `MD`.
Раньше отдавался только `language`, и настоящий `ro-MD` уезжал в английский.

Регион проверяется отдельно от языка, потому что `ro` без региона — просто
румынский, а Румыния не в СНГ. Русским Молдова становится по `ro-MD`.

Второй компонент кода брать нельзя: в `zh-Hans-CN` это script `Hans`, а не
страна. Поэтому берётся последний компонент длиной ровно в две буквы.

Порядок проверок важен: китайский смотрится раньше региона, иначе `zh-TW` и
`zh-HK` уехали бы в русский.

Языки СНГ, которые дают русский интерфейс: `ru`, `be`, `uk`, `kk`, `ky`, `tg`,
`tk`, `uz`, `az`, `hy`, `ka`. Страны: `RU`, `BY`, `UA`, `KZ`, `KG`, `TJ`,
`TM`, `UZ`, `AZ`, `AM`, `GE`, `MD`.

Немецкий или турецкий по умолчанию дают английский: русский интерфейс
в Казахстане оценят, а в Германии или Турции — нет.

### 7.3. Строки (`core/Str.kt`)

Ключи — элементы `enum`, а не строки. Забытый перевод тогда не собирается, а
не показывается на экране в виде `CORE_START`.

В файле 1349 строк и **463 ключа** `enum`, и каждый переведён на три языка.
Имена собственные (`ZAPP`, `VPN`, `Zapret`, `GoodbyeDPI`, `GPS`) и
аббревиатуры заданы одинаково во всех трёх ветках — так их и находят взглядом,
а держать три одинаковые копии смысла нет.

В `Str.of` задумана поддержка `null` как «одинаково на всех языках», но
сейчас ей не пользуется ни один ключ: вырожденный случай разобрали вручную,
и он виден глазом. Если ключей станет больше, этот случай стоит вернуть.

### 7.4. Справка внутри приложения

`core/Docs.kt` — тексты вкладки «Документация»: **7 разделов и 11 пунктов**,
локализация той же тройкой (EN, RU, ZH). Секции: VPN, Zapret, GoodbyeDPI,
GPS, Presets, Log, Permissions.

---

# Часть III. Сборка

## 8. Сборка

### 8.1. Что требуется

- JDK 21, полный, с `jpackage` (JBR из Android Studio не подходит);
- Android SDK с платформой 37.2;
- для NixOS — `objcopy` из binutils, см. раздел 9.

### 8.2. Android

```bash
cd APP
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/
```

Каждая команда собирает оба флейвора (`root` и `noroot`), и каждый флейвор
режется на пять APK.

### 8.3. Десктоп

```bash
cd APP
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageMsi
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

### 8.4. Скрипт `APP/build-all.sh`

| Режим | Что делает |
|---|---|
| `dev` | debug-APK + uber-JAR |
| `release` | debug- и release-APK + дистрибутив текущей ОС + uber-JAR |
| `linux` | AppImage + `.deb` |

```bash
./APP/build-all.sh dev
./APP/build-all.sh linux
./APP/build-all.sh release
```

Скрипт сам находит полный JDK с `jpackage` и `objcopy` в nix store, потому
что в `PATH` их нет. Результаты складываются в `APP/build/dist/`.

Скрипт вызывает `scripts/finish-deb.sh`, а тот в репозиторий не входит. На
чистой машине его нет, и тогда `.deb` остаётся сырым и получает имя
`zapp_UNFINISHED_amd64.deb` — чтобы спутать дополненный пакет с сырым было
нельзя.

### 8.5. Сплит APK по ABI

Каждый вариант собирается в пять APK: четыре по архитектурам и один
универсальный.

| Файл | Что это |
|---|---|
| `ZAPP[-ROOT]-<вер>[-debug].apk` | универсальный, все четыре ABI |
| `ZAPP[-ROOT]-<вер>[-debug]-<abi>.apk` | один ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

Причина сплита — нативные демоны. Без него APK несёт **четыре копии** одних
и тех же `lib/*.so` (около 29 МБ каждая), и release выходит 121 МБ, из которых
dex с ресурсами — всего 3,4 МБ. Сплит отдаёт устройству только свою
архитектуру: телефон на `arm64-v8a` получает около 32 МБ вместо 121.

Включается блоком `splits.abi` в `APP/app/build.gradle.kts`; имя каждому
выходу задаёт `androidComponents.onVariants` по `output.filters` (у
универсального APK фильтра нет, поэтому он и остаётся без хвоста). `x86` и
`x86_64` реальным устройствам не нужны — они существуют только для
эмулятора, — но в универсальном APK остаются.

У всех пяти APK одного варианта одинаковый `versionCode`, поэтому установка
любого поверх любого — обычная замена, а не понижение версии.

### 8.6. Публикуемость артефактов

Сборка на NixOS вписывает `/nix/store` в интерпретатор лаунчера и в рантайм
jlink, поэтому AppImage и `.deb` оттуда на Debian, Ubuntu и Windows не
запустятся. Проверено: у ELF-файла `bin/zapp` внутри пакета `interpreter`
указывает на `/nix/store/...-glibc-2.42/lib/ld-linux-x86-64.so.2`, и то же в
`libapplauncher.so` и в `lib/runtime/lib/*.so`.

Скрипт распаковывает каждый артефакт и раскладывает по спискам:

| Список | Что попадает |
|---|---|
| `PUBLISHABLE` | APK и uber-JAR |
| `UNPUBLISHABLE` | AppImage и `.deb` |

Распаковка обязательна. AppImage — это squashfs, `.deb` — сжатый tar в ar,
`.apk` и `.jar` — zip. Поиск байтов прямо в файле внутри сжатого контейнера
ничего не находит и объявляет NixOS-сборку годной к публикации. Поэтому
`.deb` распаковывается через `dpkg-deb -x`, zip — через `unzip -p`, а AppImage
проверяется по appdir, из которого он собран.

Полные публикуемые дистрибутивы собирает CI на `ubuntu` и `windows`.

Размеры, собранные на этой машине: APK 58,6 МБ, uber-JAR 74,6 МБ, дополненный
`.deb` 85,7 МБ, AppImage 100,9 МБ. Uber-JAR проверен запуском: процесс живёт
и не падает.

### 8.7. `.deb`

Сырой пакет из jpackage непригоден по пяти причинам, и `finish-deb.sh` их
закрывает:

1. `/usr/bin/zapp` — симлинк-лаунчер. jpackage кладёт исполняемый файл только
   в `/opt/<пакет>/bin/` и в `/usr/bin` ничего не делает, поэтому после
   установки команду нельзя набрать в терминале, только мышкой через меню;
2. `/usr/share/applications/zapp.desktop` — jpackage регистрирует `.desktop`
   только вызовом `xdg-desktop-menu install` из `postinst`, и без `xdg-utils`
   запись в меню просто не появляется;
3. `/usr/share/icons/hicolor/512x512/apps/zapp.png` — чтобы иконка нашлась по
   стандарту, а не абсолютным путём в `/opt`;
4. `Depends` в `control` заменяется целиком. jpackage считает зависимости
   через `jdeps`, а на NixOS тот не находит библиотеки вне `/usr/lib`, и в
   `control` попадает одинокая `xdg-utils` без `libstdc++6` и `libx11-6`. На
   реальной машине Debian пакет встал бы и упал при запуске;
5. `prerm` от jpackage снимает меню голым `xdg-desktop-menu uninstall` без
   `command -v` и без `|| true` при `set -e`, из-за чего `apt remove` падал бы
   с кодом 1.

`libasound2` записывается как `libasound2 | libasound2t64`: на Debian 13
(trixie) пакет называется `libasound2t64`, на bookworm — `libasound2`.

Собирать `finish-deb.sh` нужно на файле `*_amd64.deb` без суффикса `_zapp`,
иначе получится `*_zapp_zapp.deb`.

---

## 9. Грабли сборки

Здесь собрано то, что ломается регулярно и не очевидно по симптому.
Порядок — по частоте обращений.

### 9.1. Зависшая упаковка APK: голодание общего пула

`APP/gradle.properties` содержит `org.gradle.parallel=false` и
`org.gradle.workers.max=1`. Настоящая защита — второй флаг, и стоит
разобраться, почему: симптом обманчив.

AGP пишет и подписывает APK через `ForkJoinPool.commonPool()` (zipflinger и
apksig), пул один на весь демон. Сплиты по ABI запускает не Gradle, а сама
AGP: на каждый ABI она кладёт в `workerExecutor.noIsolation()` отдельный
`IncrementalSplitterRunnable`, и пять упаковщиков одной задачи
`package<Variant>` крутятся одновременно. Дальше:

1. каждый упаковщик пишет записи через `ApkFlinger.writeFile` в общий пул;
2. `ApkFlinger.close()` сначала дожидается своих записей, потом закрывает
   архив;
3. закрытие архива — это подпись V2: `computeOneMbChunkContentDigests`
   отправляет в тот же пул ровно `parallelism` задач и ждёт **все сразу**
   (`IncrementalPackagerBuilder.lambda$withSigning$0`);
4. если в пуле лежат записи чужих упаковщиков, свободных воркеров не хватает
   никогда. Это голодание, а не гонка: поведение детерминированно.

Сборка останавливается на `packageRootRelease` без единой строки вывода и не
завершается. В логе CI нет ни исключения, ни причины — только подсказку
запустить с `--stacktrace`, потому что исключения тут и нет: никто не падает,
все просто ждут.

`org.gradle.parallel=false` на эту ситуацию **не действует**: он
сериализует задачи, а конкуренция живёт внутри одной. Отключение
параллелизма сборку не спасало. Лечит `org.gradle.workers.max=1` — воркер
упаковки один, чужих записей в пуле в момент подписи нет. Проверено тремя
чистыми циклами упаковки: все 20 APK каждый раз с полным набором
библиотек.

Где тормозит — смотрите по строкам `> Task :app:package...` в логе: последняя
такая строка и есть точка зависания.

### 9.2. Плагины в корневом `build.gradle.kts`

Все плагины перечислены с `apply false`. Это не стилистика: AGP тянет
транзитивно `kotlin-gradle-plugin` 2.2.x, и если нашего `kotlin.multiplatform`
в classpath нет, Gradle остаётся на версии из AGP. Со списком конфликт
разрешается в пользу 2.4.20, иначе сборка `--offline` падает с
`No cached version of ... 2.2.10`.

### 9.3. Gradle 9 и аксессоры

Аксессор `projects.composeApp` не резолвится. Использовать
`project(":composeApp")`.

### 9.4. Тесты без `--rerun-tasks`

Неизменившаяся задача отдаёт `UP-TO-DATE`, счётчик тестов не пересчитывается.
Всегда запускать с `--rerun-tasks`.

### 9.5. `objcopy` на NixOS

`jlink` и `jpackage` требуют `objcopy`, а в `PATH` его нет:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

Без этого `createRuntimeImage` падает с `External tool execution failed`.

### 9.6. `fakeroot` и `dpkg-deb`

`.deb` собирается через fakeroot и dpkg-deb. На NixOS их тоже нет в `PATH`,
и без них задача падает с `Invalid or unsupported type: [deb]`.

### 9.7. Задача `packageAppImage`

Задача отрабатывает с `BUILD SUCCESSFUL` и **не создаёт AppImage**: jpackage
отдаёт только каталог `app/zapp` с бинарником и библиотеками. Сборку
настоящего AppImage делает внешний `appimagetool`, поэтому в CI он ставится
явно, а `AppRun`, `.desktop` и иконка собираются вручную. Проверено: после
задачи в каталоге есть только подкаталог `zapp`, файла `.AppImage` нет.

### 9.8. Требования к JDK

JBR из Android Studio не содержит `jpackage`. Для десктопной сборки нужен
полный JDK 21. Gradle-демон держит JBR, поэтому перед релизной сборкой:

```bash
./gradlew --stop
```

### 9.9. Владение файлами

Сборка от root создаёт каталоги `build/`, принадлежащие root, и ломает
Android Studio у обычного пользователя. После сборки:

```bash
chown -R azrael:users .
chmod -R u+w .
```

---

## 10. Подпись APK

### 10.1. Локальная подпись

Keystore в репозитории нет и не должен быть: `signing/` в `.gitignore`.
Подпись делается локально через `APP/signing/keystore.properties`:

```properties
# signing/keystore.properties, каталог в .gitignore
storeFile=/путь/к/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Без этого файла release-сборка выходит неподписанной — так форки и CI
собираются без секретов.

### 10.2. Проверка подписи

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<вер>.apk
```

`keytool -printcert -jarfile` на APK отвечает `Not a signed jar file`. Это не
ошибка подписи — просто инструмент так не умеет.

---

# Часть IV. Проверки и выпуск

## 11. Тесты

Набор лежит в `composeApp/src/desktopTest` — 18 файлов, **1303 кейса**.

| Файл | Что проверяет |
|---|---|
| `ui/theme/ThemeTest` | контраст WCAG пар палитры |
| `ui/components/ComponentsTest` | контраст компонентов |
| `ui/nav/AppTabTest` | состав и порядок вкладок |
| `core/AppConfigCodecTest` | кодеки `AppConfig` и `VpnConfig` |
| `core/dev/DevTestsParameterized` | прогон всех `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | разбор ссылок протоколов |
| `core/net/SingboxConfigTest` | генерация конфига sing-box |
| `core/net/WireGuardIniTest` | разбор WireGuard-конфига |
| `core/TorBridgesTest` | выбор мостов |
| `core/dpi/SplitRelayTest` | математика сплит-релея |
| `core/gps/RouteProgressTest` | прогресс по маршруту |
| `core/update/VersionCompareTest` | сравнение версий |
| `core/I18nTest`, `LinkSpeedTest`, `UsageTunerTest` | локали, скорость, профиль |
| `core/engine/TorBootstrapPercentTest` | процент загрузки Tor |
| `core/VpnConnectsTest` | список коннектов |

Из 1303 кейсов 1165 даёт параметризованный прогон `DevTestsParameterized` —
это все `core/dev/*Suite.kt` (24 суиты), остальные 138 — в прямых тестах
выше.

Самое ценное здесь — не количество, а то, что тесты не дают пройти молча:

- `SparklineSuite` и `DnsFailoverSuite` покрывают чистую математику,
  которую исполняет интерфейс: спарклайн на статусной карточке туннеля и
  автоподбор DNS-сервера перед стартом демона;
- подстановка `#D4D4D8` вместо `outline` роняет `ThemeSuite` — проверено
  вручную. Цвет, который читается как видимая рамка на тёмном фоне, не должен
  проходить незамеченным.

Запуск:

```bash
cd APP
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

`--rerun-tasks` обязателен, иначе задача отдаст `UP-TO-DATE` и счётчик не
пересчитается.

---

## 12. Сборка в CI и выпуск

Файл — `.github/workflows/build.yml`, четыре джобы:

| Джоба | Runner | Артефакты |
|---|---|---|
| `android` | `ubuntu-latest` | debug- и release-APK по ABI-сплитам, release подписан секретным keystore |
| `windows` | `windows-latest` | MSI |
| `linux` | `ubuntu-latest` | AppImage |
| `release` | `ubuntu-latest` | GitHub Release по тегу `v*` |

Триггеры: push в `main`, push тега `v*`, pull request в `main`, ручной
запуск. Прогоны группируются по `build-${{ github.ref }}` с
`cancel-in-progress`: пуш в ветку отменяет незавершённый прогон по ней, а
тег живёт в своей группе и строится параллельно с веткой.

Все задачи Gradle запускаются из каталога `APP/`, потому что в корне
репозитория лежат ещё `README.md`, `ABOUT.md` и остальные документы.

### 12.1. Нативные бинари

Перед сборкой джоба `android` качает нативные демоны из релиза с тегом
`native-bins` (`native-jniLibs.tar.gz`) в `APP/app/src/main/jniLibs/`. Без них
APK соберётся, но останется без VPN, Zapret и Tor и не пройдёт проверку на
`libbox.so`.

В самом репозитории лежат только скрипты и патчи (`native/*.sh`,
`native/patches/`), а не бинари: `native/out/` и `native/src/` в
`.gitignore`.

### 12.2. Подпись в CI

Keystore в репозитории нет и не должен быть. Джоба `android` на шаге
`Prepare release keystore` раскладывает секреты репозитория (`KS_B64`,
`KS_PASS`, `KS_ALIAS`, `KEY_PASS`) в `APP/signing/` и пишет
`keystore.properties`, после чего build type `release` подписывается сам.
Если секреты не заданы, шаг печатает предупреждение и release собирается
неподписанным, но такой APK в релиз не попадёт — фильтр отсекает
`*unsigned*`.

### 12.3. Состав релиза

Джоба `release` собирает из артефактов две выборки:

```bash
find artifacts -type f \( -name '*.msi' -o -name '*.AppImage' \)
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

Каждый APK перед добавлением проверяется на наличие `libbox.so` внутри: без
нативного демона в релиз он не идёт. Звёздочки в фильтре обязательны с обеих
сторон: имя сплита заканчивается на архитектуру
(`ZAPP-2.7.0-debug-x86.apk`), и вариант `*-debug.apk` пропускал восемь
debug-сборок, а универсальные при этом отсекались.

Если production-артефактов нет вообще, релиз не создаётся, а не создаётся
пустой. Состав релиза v2.7.0 — десять release-APK (по пять на `root` и
`noroot`), MSI и AppImage.

### 12.4. `.deb` в CI

`.deb` в CI не собирается вообще: сырой пакет из jpackage не содержит
`Depends`, симлинка в `/usr/bin`, `.desktop` и иконки, а публиковать его в
таком виде нельзя. Доработка — `scripts/finish-deb.sh`, но `scripts/` в
`.gitignore` и в репозиторий не идёт, поэтому `.deb` остаётся для локальной
сборки через `build-all.sh`.

---

## 13. Карта файлов

```
ZAPP/
├── README.md                      главная страница, три языка
├── ABOUT.md                       что делает приложение, три языка
├── DOCX.md                        этот документ
├── DEVELOPMENT.md                 гайд по форку и формат пресетов
├── LICENSE
├── lock.svg, unlock.svg           иконки лаунчеров
├── native/                        скрипты и патчи для сборки демонов
└── APP/
    ├── gradle/libs.versions.toml     версии всех библиотек
    ├── gradle.properties             версия приложения
    ├── build.gradle.kts              плагины с apply false, autoBuild
    ├── build-all.sh                  сборка всех платформ
    ├── app/                          Android-приложение
    │   ├── proguard-rules.pro
    │   └── src/main/
    │       ├── AndroidManifest.xml        9 разрешений, 2 лаунчера
    │       ├── java/xyz/azraellab/zapp/MainActivity.kt
    │       └── res/                       иконки, темы, строки, backup-правила
    ├── composeApp/                   общий код
    │   └── src/
    │       ├── commonMain/kotlin/xyz/azraellab/zapp/
    │       │   ├── App.kt              remember { AppState() }, AppRoot(state)
    │       │   ├── core/               AppConfig, AppState, модели, пресеты,
    │       │   │                       ConfigStore, TrafficSource, Str (463 ключа),
    │       │   │                       AppLang, Docs (7 разделов),
    │       │   │                       engine/ (туннель), daemon/ (демоны, NFQUEUE),
    │       │   │                       net/ (SingboxConfig, DnsFailover, парсеры),
    │       │   │                       traffic/ (TrafficMeter, Sparkline), dev/ (суиты)
    │       │   └── ui/
    │       │       ├── AppLang.kt        состояние языка, tr()
    │       │       ├── StarfieldBackground.kt
    │       │       ├── Theme.kt          AppThemeRoot
    │       │       ├── components/       кнопки, баннер, ввод, карточка, палитра
    │       │       ├── nav/              AppRoot, AppTab (6 вкладок)
    │       │       ├── pages/            Tor, VPN, Zapret, GoodbyeDPI, GPS, Settings
    │       │       ├── traffic/          TrafficWindow
    │       │       └── theme/            Color, Spacing, Shape, Type, Ripple
    │       ├── androidMain/.../core/      AndroidCtx, ConfigStore (SAF),
    │       │                              TrafficSource (JNI), PlatformCaps
    │       ├── desktopMain/.../core/      ConfigStore (файлы), TrafficSource (/proc)
    │       ├── jvmShared/.../core/        общая JVM-часть: net, daemon, engine,
    │       │                              probe, dpi, root, geo, update (21 файл)
    │       └── desktopTest/.../           1303 кейса, 18 файлов
    └── desktopApp/                   десктоп-приложение
        └── build.gradle.kts         mainClass, форматы упаковки
```

---

## 14. Проверки после изменений

```bash
export JAVA_HOME=/путь/к/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd APP
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopTest --offline --rerun-tasks
./gradlew :app:assembleDebug
./gradlew :desktopApp:createDistributable
```

Перед коммитом от root:

```bash
chown -R azrael:users .
chmod -R u+w .
```

Проверка, что приложение не падает на устройстве:

```bash
adb install -r app/build/outputs/apk/root/debug/ZAPP-ROOT-<вер>-debug.apk
adb shell am start -n xyz.azraellab.zapp/.MainActivity
adb logcat -d | grep -iE "FATAL|AndroidRuntime"
```

Чистая установка вместо обновления обязательна: проверяется именно первый
запуск.

---

## 15. Что ещё предстоит

Сделано: общий код собирается под обе платформы, дизайн-система закончена,
тесты зелёные (1303 кейса). Работает подключение — WireGuard/AmneziaWG,
OpenVPN, sing-box (vless, vmess, trojan, ss, hysteria2, tuic), Tor через
мосты, kill-switch; обход DPI — zapret и goodbyedpi на одной NFQUEUE-цепочке
с автоподбором стратегии; мониторинг трафика со спарклайном и счётчиком
сеанса; автозамена DNS перед стартом туннеля.

Не сделано: сценарии и карта GPS (страница настроек есть), Nix и PKGBUILD.
Подпись release-APK работает и локально (`APP/signing/keystore.properties`),
и в CI (секреты репозитория).

Очередь по этапам, как в README:

| Этап | Содержание | Критерий |
|---|---|---|
| 11 | GPS: сценарии и карта | подмена координат видна в стороннем приложении |
| 12 | упаковка: Nix, PKGBUILD | все форматы собираются из чистого чекаута |
| 13 | полировка, i18n, подпись | сделано: EN/RU/ZH, подписанный релиз публикует CI |

</details>

<details>
<summary><b>🇨🇳 中文</b></summary>

文档说明项目里有什么、用什么构建、各部分在哪里。
版本和数字全部取自配置文件和构建产物，而不是描述。凡是通过运行验证过的值，都已标注。

其他文档：[README.md](README.md) — 仓库主页，
[ABOUT.md](ABOUT.md) — 用通俗的话说明应用做什么，
[DEVELOPMENT.md](DEVELOPMENT.md) — fork 指南与预设格式。

## 目录

**第一部分 结构**

1. [技术栈](#1-技术栈)
2. [模块](#2-模块)
3. [命名空间](#3-命名空间)
4. [源集](#4-源集)

**第二部分 界面**

5. [设计系统](#5-设计系统)
6. [屏幕](#6-屏幕)
7. [主题与语言](#7-主题与语言)

**第三部分 构建**

8. [构建](#8-构建)
9. [构建的坑](#9-构建的坑)
10. [APK 签名](#10-apk-签名)

**第四部分 检查与发布**

11. [测试](#11-测试)
12. [CI 构建与发布](#12-ci-构建与发布)
13. [文件地图](#13-文件地图)
14. [改动后的检查](#14-改动后的检查)
15. [还有哪些要做](#15-还有哪些要做)

---

# 第一部分 结构

## 1. 技术栈

| 层 | 内容 | 版本 | 定义位置 |
|---|---|---|---|
| 语言 | Kotlin | 2.4.20 | `APP/gradle/libs.versions.toml` |
| 构建 | Gradle | 9.4.1 | `APP/gradle/wrapper/gradle-wrapper.properties` |
| Android 插件 | AGP | 9.2.1 | `APP/gradle/libs.versions.toml` |
| 界面 | Compose Multiplatform | 1.12.1 | 同上 |
| 序列化 | kotlinx-serialization-json | 1.9.0 | 同上 |
| Android Activity | androidx.activity:activity-compose | 1.13.0 | 同上 |
| Android 核心 | androidx.core:core-ktx | 1.10.1 | 同上 |
| 测试 | kotlin("test") | 2.4.20 | `APP/composeApp/build.gradle.kts` |
| 桌面打包 | jpackage, jlink | JDK 21 | `APP/desktopApp/build.gradle.kts` |
| AppImage | appimagetool | continuous | `.github/workflows/build.yml` |
| Android 目标 OS | API 33 及以上 | — | `APP/app/build.gradle.kts` |
| Android 目标 OS | targetSdk 36 | — | 同上 |

版本的唯一权威来源是 `APP/gradle/libs.versions.toml`。在
`build.gradle.kts` 中不重复写版本，只用别名。

应用自身的版本单独放在 `APP/gradle.properties`：

```properties
zappVersion=2.7.0
```

`APP/app/build.gradle.kts` 把它读作 `versionName`，
`APP/desktopApp/build.gradle.kts` 读作 `packageVersion`，这样 APK 和桌面发行版
的版本不会分叉。已在构建出的 APK 上验证：
`versionName=2.7.0`、`versionCode=9`、`minSdk=33`、`targetSdk=36`、
`compileSdk=37.2`。

---

## 2. 模块

`APP/` 内有三个模块。公共代码只有一份，平台相关部分按源集分散在模块内部。

### 2.1. `:composeApp`

主模块。Kotlin Multiplatform，两个目标：

- `androidLibrary` — Android 库，`namespace = xyz.azraellab.zapp.shared`、
  `compileSdk = 37`、`minSdk = 33`、`jvmTarget = JVM_11`；
- `jvm("desktop")` — 普通 JVM 库。

`commonMain` 的依赖：

```kotlin
implementation(compose.runtime)
implementation(compose.foundation)
implementation(compose.material3)
implementation(compose.ui)
implementation(compose.materialIconsExtended)
implementation(libs.kotlinx.serialization.json)
```

`desktopMain` 追加 `compose.desktop.currentOs`，`androidMain` 追加
`androidx.activity.compose`，`desktopTest` 追加 `kotlin("test")`。

启用了 `androidResources`，因为设计系统要用 Compose 资源。

### 2.2. `:app`

只有 Android。是应用，不是库。

| 参数 | 值 |
|---|---|
| `namespace` | `xyz.azraellab.zapp` |
| `applicationId` | `xyz.azraellab.zapp` |
| `minSdk` | 33 |
| `targetSdk` | 36 |
| `compileSdk` | 37, `minorApiLevel` 2 |
| `sourceCompatibility` | Java 11 |

release 构建启用混淆和资源收缩：

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

只有一个可用的 Activity —— 基于 `ComponentActivity` 的 `MainActivity`：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AndroidCtx.attach(this)
    installPresetFileBridge(context = this, onPickRead = { ... }, onPickWrite = { ... })
    setContent { App() }
}
```

两个 SAF 启动器用类字段注册，必须早于 `onCreate`：`registerForActivityResult`
如果调用过晚会抛异常。`installPresetFileBridge` 只负责把回调搬进
`PresetFileGateway` —— 共同代码不知道具体怎么选文件，而应用必须用可挂起调用
（`suspend`）响应请求。

清单里除 `MainActivity` 外还声明了两个启动器 —— `LauncherLock` 和
`LauncherUnlock`。前者默认打开，并带禁止截屏的标志；后者是普通入口，没有这个限制。

`AndroidCtx` 让没有 `Context` 可传的代码也能访问 `Context`。它单独放在
`androidMain` 的 `AndroidCtx.android.kt`：和其他 `actual` 实现同处一处，
但不挂任何多余的东西。

清单里有九项权限：

```
INTERNET                    ACCESS_NETWORK_STATE
FOREGROUND_SERVICE          FOREGROUND_SERVICE_SPECIAL_USE
ACCESS_FINE_LOCATION        ACCESS_COARSE_LOCATION
ACCESS_MOCK_LOCATION        POST_NOTIFICATIONS
QUERY_ALL_PACKAGES
```

权限请求没有全部写进清单：`ACCESS_FINE_LOCATION` 和
`ACCESS_COARSE_LOCATION` 在欢迎页请求，`ACCESS_MOCK_LOCATION` 由用户在开发者设置里手动授予。

### 2.3. `:desktopApp`

只有桌面。Kotlin JVM，依赖 `:composeApp` 和
`compose.desktop.currentOs`。入口是 `xyz.azraellab.zapp.desktop.MainKt`。

```kotlin
mainClass = "xyz.azraellab.zapp.desktop.MainKt"
targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
packageName = "zapp"
packageVersion = zappVersion
description = "ZAPP - network toolkit"
vendor = "ZAPP"
linux {
    appCategory = "Network"
    menuGroup = "Network"
}
```

---

## 3. 命名空间

`:app` 和 `:composeApp` 不能有相同的 `namespace` —— 清单合并器会报重复。因此：

| 模块 | namespace | Kotlin 包 |
|---|---|---|
| `:app` | `xyz.azraellab.zapp` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` | `xyz.azraellab.zapp.*` |

Android 库的 `namespace` 和 Kotlin 包是两回事。namespace 进入生成的
`R` 和 `BuildConfig`，而 Kotlin 包决定代码在 classpath 上的可见性。
两个模块的包名相同没问题，namespace 相同不行。

---

## 4. 源集

`composeApp/src` 里有五个源集：

| 源集 | 里面是什么 |
|---|---|
| `commonMain` | 界面、页面、导航、字符串、模型、`expect` 声明 |
| `androidMain` | 通过 `Context`、`SharedPreferences` 和 SAF 实现 `expect` |
| `desktopMain` | 通过 `~/.config/zapp/` 中的文件实现 |
| `jvmShared` | Android 和桌面共用的 JVM 代码：进程、套接字、守护进程 |
| `desktopTest` | 测试：组件、调色板、语言、编解码、解析器 |

`jvmShared` 之所以出现，是因为部分代码两个平台相同，但不能放进 `commonMain`：
进程、套接字和文件操作只有 JVM 上才有。它在 `composeApp/build.gradle.kts` 中声明：

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

`commonMain` 有 39 个 `expect` 声明（32 个公开，7 个 `internal`）。`androidMain` 和 `jvmShared` 没有自己的测试源集：
公共 JVM 部分的测试放在 `desktopTest`，那里公共 JVM 已经在 classpath 上。

---

# 第二部分 界面

## 5. 设计系统

全部位于 `APP/composeApp/src/commonMain/kotlin/xyz/azraellab/zapp/ui/theme/`。

### 5.1. 调色板（`Color.kt`）

只有一套配色，深色：`AzraelDarkScheme`。代码里没有浅色配色 ——
也没有主题开关，没必要在两次启动之间保存「深色/浅色」。

| 令牌 | 值 | 角色 |
|---|---|---|
| `AzraelPrimary` | `#4ADE80` | 强调色 |
| `AzraelOnPrimary` | `#07170C` | 强调色上的文字 |
| `AzraelBgDeep` | `#0A0A0A` | 深色配色背景 |
| `AzraelBgCard` | `#0DFFFFFF` | 半透明卡片 |
| `AzraelBorder` | `#2EFFFFFF` | 装饰性边框 |
| `AzraelTextDim` | `#D9EDEDED` | 次要文字 |
| `AzraelDanger` | `#F43F5E` | 危险操作 |
| `AzraelWarning` | `#F59E0B` | 警告 |
| `AzraelInfo` | `#38BDF8` | 信息 |
| `AzraelSuccess` | `#22C55E` | 成功 |
| `AzraelStrong` | `#16A34A` | 强成功 |
| `AzraelErrorSoft` | `#F87171` | 柔和错误 |

评分依次是：`#F4585A` 差，`#F59E0B` 中，`#22C55E` 好，
`#16A34A` 优。`#FFB74D` 和 `#E6C86A` 的角色在代码里有，但目前不在屏幕上显示。

浅色配色：背景 `#FAFAFA`，表面 `#FFFFFF`，`primary` 是 `#166534`，
`outline` 是 `#78787F`。

选 `#166534` 而不用 `#15803D` 是有意的：`#15803D` 的对比度是 4.14:1，
按钮上的文字不够。

### 5.2. 两条边框

`outline` 和 `outlineVariant` 按语义分开，并有测试校验：

- `outline` 是控件的语义边框。WCAG 1.4.11 要求 3:1，
  两套配色都满足（对页面背景分别是 3.77 和 4.20）；
- `outlineVariant` 是装饰性的：玻璃卡片、分隔线、被动底衬。
  3:1 规则不适用于它：不需要用眼睛找到它才能开始交互。

以前两条边框取自同一处，后来悄悄分叉：某处用 `outline`，某处用 `onSurface`
的 `alpha = 0.10`。现在令牌按语义而不是按美观划分，公式单独放在 `Contrast.kt` ——
测试不应该重复它自己要校验的数字。

### 5.3. 间距（`Spacing.kt`）

刻度：`xxs` 2、`xs` 4、`sm` 8、`md` 12、`lg` 16、`xl` 24、`xxl` 32（单位 dp）。

页面：`screenPadding` 16、`cardPadding` 14、`cardGap` 12、`listGap` 8、
`fieldGap` 10、`controlHeight` 44、`controlHeightSm` 36、`touchTarget` 48、
`divider` 1、`stroke` 1。

`touchTarget` 48 是 WCAG 2.5.8 对手指的下限，而 `controlHeight` 44 是肉眼可见的。
不同任务用不同数字，所以有两个令牌。

### 5.4. 圆角（`Shape.kt`）

`xs` 6、`sm` 10、`md` 14、`lg` 20、`xl` 26、`glass` 22（单位 dp）。

### 5.5. 字体排版（`Type.kt`）

`FontFamily.Default`、`LineHeightStyle.Alignment.Center`。标题 `display*`
用 Bold 和 SemiBold，字距为负，从 −1.0 到 −0.25；`body` 和 `label` 用 Normal 和 Medium，
字距为正，从 0.1 到 0.4。大字用负字距、小字用正字距 —— 这是两条相反的规则，
否则要么标题散开，要么小字看不清。

### 5.6. Ripple（`Ripple.kt`）

`ripple(bounded, onSurface)`，包进独立的指示主题，这样组件不用每次重新设置 ripple。

### 5.7. 背景（`StarfieldBackground.kt`）

星空画在画布上，而不是用图片：图片无法随主题反色，也不会实时响应主题切换。

```kotlin
const val STAR_COUNT = 110
const val HIT_FRAMES = 90
const val MIN_COMETS = 1
const val MAX_COMETS = 5
const val COMET_SPAWN_MIN_MS = 2600
const val COMET_SPAWN_MAX_MS = 7200
private const val TRAIL_LEN_PX = 120f
```

星星坐标以屏幕比例（0…1）保存，否则屏幕旋转或换尺寸时星星会错位。
彗星半径则相反，用像素：无论窗口多大，彗尾长度都要一致。

没有浅色主题，所以星星始终是深色背景上的浅色，不需要反色。
给已有的星星重新上色用 `LaunchedEffect(palette)`：
不带 key 的 `remember` 会熬过配色切换，把星星留成旧颜色。

星星和彗尾相交时彗尾产生闪光，`HIT_FRAMES` 就是为此存在。

---

## 6. 屏幕

入口是 `App.kt`：创建 `AppState`，加载配置，把状态交给
`AppThemeRoot` 中的 `AppRoot(state)`。`App.kt` 里没有别的东西，
全部工作在 `core/` 和 `ui/`。

### 6.1. 导航

`ui/nav/AppRoot.kt` 维护底部栏和当前选中的分区。六个标签来自
`ui/nav/AppTab.kt`：

| 标签 | 内容 |
|---|---|
| `TOR` | 桥、来源、Tor 下载 |
| `VPN` | 配置、连接、启动隧道 |
| `ZAPRET` | DPI 绕过策略 |
| `GOODBYE_DPI` | 第二个绕过引擎 |
| `GPS` | 坐标伪装 |
| `SETTINGS` | 设置、帮助、日志 |

标签的可用性取决于平台：`AppTab.available(gpsSupported)`
在桌面隐藏 GPS，桌面不会有坐标伪装。Android 上六个标签，桌面上五个。

流量监控不是标签。`state.trafficWindowOpen` 把屏幕切换到
`ui/traffic/TrafficWindow.kt`，它上面的导航栏会消失：这个窗口从设置里打开，
用叉号关闭。

### 6.2. 页面

每个页面都在 `ui/pages/`，基于 `ui/pages/PageScaffold.kt` 中的
`PageScaffold` / `PageGroup` 构建：标题、设置分组、统一间距。

| 文件 | 作用 |
|---|---|
| `TorPage.kt` | 桥、来源、下载进度 |
| `VpnPage.kt` | 配置、路由、自动切换、连接状态 |
| `VpnProfileEditor.kt` | VPN 配置编辑器 |
| `ZapretPage.kt` | 策略、家族、高级区块、预设 |
| `GoodbyeDpiPage.kt` | 模式、TLS 分割器、域名列表 |
| `GpsPage.kt` | 模式、坐标、路由、应用白名单；没有该功能时显示空状态 |
| `SettingsPage.kt` | 语言、流量、预设（导出、导入、重置）、帮助 |
| `DaemonControl.kt` | 守护进程控制 |
| `PerAppSection.kt` | 应用选择 |
| `RoutingCards.kt` | 路由卡片 |
| `ErrorReason.kt` | 连接失败原因 |
| `DevTestsPage.kt` | 开发测试展示页 |

### 6.3. 状态

`core/AppState.kt` 是界面的唯一事实来源。内部有：
按分区的配置模型、流量历史与汇总、标志位
（`trafficWindowOpen`）、计数器，以及预设操作结果用的 `PresetMessage`。

有两条规则，使得消息和标签以 `Str` 而不是 `String` 进入界面：

- `tr(Str)` 是 `@Composable`，不能在 `onClick` 里调用；
  翻译在组合时完成，lambda 里放的是 `Str` 本身；
- 状态存的是 key 而不是现成字符串，所以切换语言不用重启就能刷新标签。

配置写入是延迟的：通过 `debounce` 改标志位才会送到 `ConfigStore`，
否则滑块每动一下都会往 `SharedPreferences` 写。

### 6.4. 预设

`PresetFileGateway` 是 `suspend read()` / `write(content, name)`，
每个平台各有实现：桌面用 AWT 对话框（headless 下不可用，
`available = false`），Android 用 SAF，经 `MainActivity` 调用的
`installPresetFileBridge`。`AppState.importPreset()` 和 `exportPreset()`
返回 `PresetMessage`，不直接写界面。

预设文件格式见 [DEVELOPMENT.md](DEVELOPMENT.md)。

---

## 7. 主题与语言

主题只有一套，深色：`AzraelTheme` 不带模式参数，始终返回
`AzraelDarkScheme`。代码里没有浅色配色，没有开关，也没有什么需要在两次启动之间保存。
`AppThemeStore`、`AppThemeMode` 及其所有 `actual` 实现已删除。

### 7.1. 语言存储

语言通过 `expect`/`actual` 实现，并能跨重启保留。

| 平台 | 存储位置 |
|---|---|
| Android | `SharedPreferences` |
| 桌面 | `~/.config/zapp/lang` |

桌面上的写入是原子的：先写临时文件，再重命名。
否则中途崩溃会留下一个下次启动读不了的文件。

### 7.2. 语言选择

三种语言：EN、RU、ZH。规则顺序：

1. 用户明确的选择，如果做过；
2. 否则按国家和语言代码取系统语言；
3. 否则用英语。

`AppLangStore.systemCode()` 返回完整标签（`ro-MD`、`zh-Hans-CN`），
而不只是语言。摩尔多瓦的系统语言是 `ro`，没有单独的「摩尔多瓦语」代码
（ISO 639-1 中 `mo` 已废弃），那里的俄语按地区 `MD` 判定。
以前只返回 `language`，真正的 `ro-MD` 会落到英语。

地区要和语言分开检查，因为没有地区的 `ro` 就是罗马尼亚语，
而罗马尼亚不在独联体。摩尔多瓦按 `ro-MD` 判为俄语。

代码的第二个组件不能直接取：`zh-Hans-CN` 中它是 script `Hans`，
不是国家。所以取长度恰好为两个字母的最后一个组件。

检查顺序很重要：中文先于地区判断，否则 `zh-TW` 和 `zh-HK` 会落到俄语。

给出俄语界面的独联体语言：`ru`、`be`、`uk`、`kk`、`ky`、`tg`、
`tk`、`uz`、`az`、`hy`、`ka`。国家：`RU`、`BY`、`UA`、`KZ`、`KG`、`TJ`、
`TM`、`UZ`、`AZ`、`AM`、`GE`、`MD`。

德语和土耳其语默认给英语：哈萨克斯坦会用俄语界面，
德国或土耳其不会。

### 7.3. 字符串（`core/Str.kt`）

key 是 `enum` 元素，不是字符串。漏翻译的 key 直接编译不过，
而不是以 `CORE_START` 的形式显示在屏幕上。

文件共 1349 行、**463 个** `enum` key，每个都翻成三种语言。
专有名词（`ZAPP`、`VPN`、`Zapret`、`GoodbyeDPI`、`GPS`）和缩写在三个分支里
写法一致 —— 这样一眼就能找到，留三份相同副本没有意义。

`Str.of` 设计上支持 `null` 表示「所有语言都一样」，但目前没有 key 用它：
退化情况已手工拆开，肉眼可见。如果 key 变多，可以把它加回来。

### 7.4. 应用内帮助

`core/Docs.kt` 是「文档」标签页的文本：**7 个部分、11 个条目**，
本地化同样是三种语言（EN、RU、ZH）。部分：VPN、Zapret、GoodbyeDPI、
GPS、Presets、Log、Permissions。

---

# 第三部分 构建

## 8. 构建

### 8.1. 需要什么

- JDK 21，完整版，带 `jpackage`（Android Studio 的 JBR 不行）；
- Android SDK，平台 37.2；
- NixOS 还需要 binutils 的 `objcopy`，见第 9 节。

### 8.2. Android

```bash
cd APP
./gradlew :app:assembleDebug      # apk/{root,noroot}/debug/
./gradlew :app:assembleRelease    # apk/{root,noroot}/release/
```

每条命令都构建两个 flavor（`root` 和 `noroot`），每个 flavor 再切成五个 APK。

### 8.3. 桌面

```bash
cd APP
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageMsi
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

### 8.4. 脚本 `APP/build-all.sh`

| 模式 | 作用 |
|---|---|
| `dev` | debug APK + uber-JAR |
| `release` | debug 和 release APK + 当前系统发行版 + uber-JAR |
| `linux` | AppImage + `.deb` |

```bash
./APP/build-all.sh dev
./APP/build-all.sh linux
./APP/build-all.sh release
```

脚本自己在 nix store 里找带 `jpackage` 的完整 JDK 和 `objcopy`，
因为 `PATH` 里没有它们。结果放到 `APP/build/dist/`。

脚本会调用 `scripts/finish-deb.sh`，而后者不在仓库里。
干净机器上没有它，此时 `.deb` 保持未加工状态，文件名为
`zapp_UNFINISHED_amd64.deb` —— 让加工过的包和原始包不可能混淆。

### 8.5. APK 按 ABI 拆分

每个变体构建五个 APK：四个按架构，加一个通用。

| 文件 | 这是什么 |
|---|---|
| `ZAPP[-ROOT]-<版本>[-debug].apk` | 通用，包含全部四个 ABI |
| `ZAPP[-ROOT]-<版本>[-debug]-<abi>.apk` | 单个 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |

拆分的原因是原生守护进程。不拆的话，APK 会带**四份**相同的 `lib/*.so`
（每份约 29 MB），release 达到 121 MB，而 dex 加资源只有 3.4 MB。拆分后设备只拿
自己的架构：`arm64-v8a` 手机约 32 MB，而不是 121。

由 `APP/app/build.gradle.kts` 中的 `splits.abi` 块启用；
每个输出的名称由 `androidComponents.onVariants` 按 `output.filters` 设定
（通用 APK 没有 filter，所以名字没有后缀）。`x86` 和 `x86_64` 真机不需要 ——
只为模拟器存在 —— 但保留在通用 APK 里。

同一变体的五个 APK `versionCode` 相同，所以任意覆盖安装都是正常替换，不是降级。

### 8.6. 产物可发布性

在 NixOS 上构建会把 `/nix/store` 写进启动器解释器和 jlink 运行时，
所以那里的 AppImage 和 `.deb` 在 Debian、Ubuntu 和 Windows 上跑不起来。已验证：
包内 ELF 文件 `bin/zapp` 的 `interpreter` 指向
`/nix/store/...-glibc-2.42/lib/ld-linux-x86-64.so.2`，`libapplauncher.so`
和 `lib/runtime/lib/*.so` 里也一样。

脚本解包每个产物并分到清单：

| 清单 | 收哪些 |
|---|---|
| `PUBLISHABLE` | APK 和 uber-JAR |
| `UNPUBLISHABLE` | AppImage 和 `.deb` |

解包是必须的。AppImage 是 squashfs，`.deb` 是 ar 里的压缩 tar，
`.apk` 和 `.jar` 是 zip。直接在压缩容器里的文件上搜字节什么都搜不到，
还会把 NixOS 构建判为可发布。所以 `.deb` 用 `dpkg-deb -x` 解包，zip 用 `unzip -p`，
AppImage 则按构建它的 appdir 检查。

完整可发布的发行版由 CI 在 `ubuntu` 和 `windows` 上构建。

本机构建的大小：APK 58.6 MB、uber-JAR 74.6 MB、加工后的
`.deb` 85.7 MB、AppImage 100.9 MB。uber-JAR 已实际运行验证：进程存活且不崩溃。

### 8.7. `.deb`

jpackage 产出的原始包有五个问题，`finish-deb.sh` 逐一解决：

1. `/usr/bin/zapp` 是启动器符号链接。jpackage 只把可执行文件放进
   `/opt/<包>/bin/`，不动 `/usr/bin`，装完后无法在终端里输入命令，只能用鼠标点菜单；
2. `/usr/share/applications/zapp.desktop`：jpackage 只在 `postinst` 里通过
   `xdg-desktop-menu install` 注册 `.desktop`，没有 `xdg-utils` 菜单条目根本不会出现；
3. `/usr/share/icons/hicolor/512x512/apps/zapp.png`：让图标按标准被找到，
   而不是靠 `/opt` 下的绝对路径；
4. `control` 中的 `Depends` 整体替换。jpackage 用 `jdeps` 算依赖，
   而 NixOS 上它找不到 `/usr/lib` 之外的库，`control` 里只剩孤零零的
   `xdg-utils`，没有 `libstdc++6` 和 `libx11-6`。在真实 Debian 机器上包能装上，
   一启动就崩；
5. jpackage 的 `prerm` 用裸 `xdg-desktop-menu uninstall` 卸载菜单，
   没有 `command -v`，在 `set -e` 下也没有 `|| true`，`apt remove` 会以退出码 1 失败。

`libasound2` 写成 `libasound2 | libasound2t64`：Debian 13（trixie）
的包名是 `libasound2t64`，bookworm 是 `libasound2`。

`finish-deb.sh` 要针对不带 `_zapp` 后缀的 `*_amd64.deb` 执行，
否则会得到 `*_zapp_zapp.deb`。

---

## 9. 构建的坑

这里收集经常出问题、且从症状看不出原因的情况。按求助频率排序。

### 9.1. APK 打包卡死：公共池饥饿

`APP/gradle.properties` 里有 `org.gradle.parallel=false` 和
`org.gradle.workers.max=1`。真正起作用的是第二个，原因值得搞清楚：症状有欺骗性。

AGP 通过 `ForkJoinPool.commonPool()` 写入并签名 APK（zipflinger 和
apksig），整个守护进程共用一个池。ABI 拆分不是 Gradle 启动的，是 AGP 自己：
每个 ABI 向 `workerExecutor.noIsolation()` 塞一个
`IncrementalSplitterRunnable`，同一个任务
`package<Variant>` 的五个打包器同时运行。接着：

1. 每个打包器通过 `ApkFlinger.writeFile` 向公共池写记录；
2. `ApkFlinger.close()` 先等自己的记录写完，再关闭归档；
3. 关闭归档就是 V2 签名：`computeOneMbChunkContentDigests`
   向同一个池恰好派发 `parallelism` 个任务，并**同时等它们全部完成**
   （`IncrementalPackagerBuilder.lambda$withSigning$0`）；
4. 如果池里躺着别的打包器的记录，空闲 worker 永远不够。这是饥饿，不是竞态：
   行为是确定性的。

构建停在 `packageRootRelease`，一行输出都没有，也不结束。
CI 日志里既没有异常也没有原因 —— 只有让你加 `--stacktrace` 的提示，
因为这里根本没有异常：没人崩溃，大家都只是在等。

`org.gradle.parallel=false` 对这种情况**无效**：它序列化的是任务，
而竞争发生在单个任务内部。关掉并行救不了构建。解药是
`org.gradle.workers.max=1` —— 打包 worker 只有一个，签名时池里没有别人的记录。
用三次干净的打包周期验证：每次全部 20 个 APK 都带完整的库。

卡在哪里看日志里的 `> Task :app:package...` 行：最后一行就是卡点。

### 9.2. 根 `build.gradle.kts` 中的插件

所有插件都带 `apply false` 列出。这不是风格问题：AGP 会传递依赖
`kotlin-gradle-plugin` 2.2.x，如果 classpath 上没有我们的 `kotlin.multiplatform`，
Gradle 就停在 AGP 给的版本。列出之后冲突按 2.4.20 解决，
否则 `--offline` 构建会报 `No cached version of ... 2.2.10`。

### 9.3. Gradle 9 与访问器

访问器 `projects.composeApp` 解析不了。改用 `project(":composeApp")`。

### 9.4. 不带 `--rerun-tasks` 的测试

未改动的任务返回 `UP-TO-DATE`，测试计数不会重算。始终加 `--rerun-tasks`。

### 9.5. NixOS 上的 `objcopy`

`jlink` 和 `jpackage` 需要 `objcopy`，而 `PATH` 里没有：

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

不加的话 `createRuntimeImage` 报 `External tool execution failed`。

### 9.6. `fakeroot` 与 `dpkg-deb`

`.deb` 通过 fakeroot 和 dpkg-deb 构建。NixOS 上它们也不在 `PATH`，
没有它们任务报 `Invalid or unsupported type: [deb]`。

### 9.7. `packageAppImage` 任务

任务以 `BUILD SUCCESSFUL` 结束，却**不生成 AppImage**：jpackage 只给出
`app/zapp` 目录，里面是二进制和库。真正的 AppImage 由外部 `appimagetool` 构建，
所以 CI 里显式安装它，而 `AppRun`、`.desktop` 和图标手工组装。已验证：
任务结束后目录里只有 `zapp` 子目录，没有 `.AppImage` 文件。

### 9.8. JDK 要求

Android Studio 的 JBR 没有 `jpackage`。桌面构建需要完整 JDK 21。
Gradle 守护进程占着 JBR，所以 release 构建前：

```bash
./gradlew --stop
```

### 9.9. 文件属主

以 root 构建会生成属于 root 的 `build/` 目录，破坏普通用户的 Android Studio。构建后：

```bash
chown -R azrael:users .
chmod -R u+w .
```

---

## 10. APK 签名

### 10.1. 本地签名

仓库里没有 keystore，也不该有：`.gitignore` 忽略 `signing/`。
签名通过 `APP/signing/keystore.properties` 在本地完成：

```properties
# signing/keystore.properties，该目录已在 .gitignore 中
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

没有这个文件，release 构建出来就是未签名的 —— fork 和 CI 正是这样不带密钥构建的。

### 10.2. 签名验证

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<版本>.apk
```

对 APK 用 `keytool -printcert -jarfile` 会返回 `Not a signed jar file`。
这不是签名错误 —— 只是这个工具做不到。

---

# 第四部分 检查与发布

## 11. 测试

测试集在 `composeApp/src/desktopTest` —— 18 个文件，**1303 个用例**。

| 文件 | 检查什么 |
|---|---|
| `ui/theme/ThemeTest` | 调色板配对的 WCAG 对比度 |
| `ui/components/ComponentsTest` | 组件的对比度 |
| `ui/nav/AppTabTest` | 标签的组成与顺序 |
| `core/AppConfigCodecTest` | `AppConfig` 和 `VpnConfig` 的编解码 |
| `core/dev/DevTestsParameterized` | 跑全部 `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | 协议链接解析 |
| `core/net/SingboxConfigTest` | sing-box 配置生成 |
| `core/net/WireGuardIniTest` | WireGuard 配置解析 |
| `core/TorBridgesTest` | 桥的选择 |
| `core/dpi/SplitRelayTest` | 分割中继的数学 |
| `core/gps/RouteProgressTest` | 路线进度 |
| `core/update/VersionCompareTest` | 版本比较 |
| `core/I18nTest`、`LinkSpeedTest`、`UsageTunerTest` | 语言环境、速度、配置 |
| `core/engine/TorBootstrapPercentTest` | Tor 下载百分比 |
| `core/VpnConnectsTest` | 连接列表 |

1303 个用例中 1165 个来自 `DevTestsParameterized` 的参数化运行 ——
即全部 `core/dev/*Suite.kt`（24 个 suite），其余 138 个在上面的直接测试里。

这里最有价值的不是数量，而是测试不会让人悄悄通过：

- `SparklineSuite` 和 `DnsFailoverSuite` 覆盖界面执行的纯数学：
  隧道状态卡上的迷你曲线，以及守护进程启动前的 DNS 服务器自动选择；
- 把 `outline` 换成 `#D4D4D8` 会让 `ThemeSuite` 失败 —— 手动验证过。
  在深色背景上读作可见边框的颜色，不该不被察觉地通过。

运行：

```bash
cd APP
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

`--rerun-tasks` 必须加，否则任务返回 `UP-TO-DATE`，计数不会重算。

---

## 12. CI 构建与发布

文件是 `.github/workflows/build.yml`，四个 job：

| Job | Runner | 产物 |
|---|---|---|
| `android` | `ubuntu-latest` | 按 ABI 拆分的 debug 和 release APK，release 用密钥 keystore 签名 |
| `windows` | `windows-latest` | MSI |
| `linux` | `ubuntu-latest` | AppImage |
| `release` | `ubuntu-latest` | 按 `v*` 标签发布 GitHub Release |

触发条件：push 到 `main`、push `v*` 标签、针对 `main` 的 pull request、手动运行。
运行按 `build-${{ github.ref }}` 分组，带 `cancel-in-progress`：
向分支 push 会取消该分支上未完成的运行，而标签在自己的分组里，与分支并行构建。

所有 Gradle 任务都在 `APP/` 目录下启动，因为仓库根目录还有
`README.md`、`ABOUT.md` 和其他文档。

### 12.1. 原生二进制

构建前，`android` job 从标签 `native-bins` 的 release 下载原生守护进程
（`native-jniLibs.tar.gz`）到 `APP/app/src/main/jniLibs/`。没有它们，
APK 能构建，但没有 VPN、Zapret 和 Tor，也过不了 `libbox.so` 检查。

仓库里只有脚本和补丁（`native/*.sh`、`native/patches/`），没有二进制：
`native/out/` 和 `native/src/` 在 `.gitignore` 中。

### 12.2. CI 中的签名

仓库里没有 keystore，也不该有。`android` job 在
`Prepare release keystore` 步骤把仓库密钥（`KS_B64`、
`KS_PASS`、`KS_ALIAS`、`KEY_PASS`）放进 `APP/signing/` 并写入
`keystore.properties`，之后 `release` build type 自动签名。
如果没设密钥，步骤打印警告，release 未签名构建 —— 但这种 APK 不会进入 release：
过滤器会剔除 `*unsigned*`。

### 12.3. Release 的内容

`release` job 从产物中挑出两组：

```bash
find artifacts -type f \( -name '*.msi' -o -name '*.AppImage' \)
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

每个 APK 添加前都检查内部是否有 `libbox.so`：没有原生守护进程就不进 release。
过滤器两边的星号都是必需的：拆分文件名以架构结尾
（`ZAPP-2.7.0-debug-x86.apk`），而 `*-debug.apk` 这种写法会放过八个 debug 构建，
同时通用包又被剔除。

如果根本没有 production 产物，就不创建 release，而不是创建一个空的。
v2.7.0 的内容：十个 release APK（`root` 和 `noroot` 各五个）、MSI 和 AppImage。

### 12.4. CI 中的 `.deb`

CI 完全不构建 `.deb`：jpackage 的原始包没有 `Depends`、
`/usr/bin` 符号链接、`.desktop` 和图标，这样的包不能发布。
加工靠 `scripts/finish-deb.sh`，但 `scripts/` 在 `.gitignore` 里，
不进仓库，所以 `.deb` 留给用 `build-all.sh` 的本地构建。

---

## 13. 文件地图

```
ZAPP/
├── README.md                      主页，三种语言
├── ABOUT.md                       应用做什么，三种语言
├── DOCX.md                        本文档
├── DEVELOPMENT.md                 Fork 指南与预设格式
├── LICENSE
├── lock.svg, unlock.svg           启动器图标
├── native/                        构建守护进程用的脚本与补丁
└── APP/
    ├── gradle/libs.versions.toml     所有库的版本
    ├── gradle.properties             应用版本
    ├── build.gradle.kts              插件：apply false、autoBuild
    ├── build-all.sh                  构建所有平台
    ├── app/                          Android 应用
    │   ├── proguard-rules.pro
    │   └── src/main/
    │       ├── AndroidManifest.xml        9 项权限，2 个启动器
    │       ├── java/xyz/azraellab/zapp/MainActivity.kt
    │       └── res/                       图标、主题、字符串、备份规则
    ├── composeApp/                   共享代码
    │   └── src/
    │       ├── commonMain/kotlin/xyz/azraellab/zapp/
    │       │   ├── App.kt              remember { AppState() }, AppRoot(state)
    │       │   ├── core/               AppConfig、AppState、模型、预设、
    │       │   │                       ConfigStore、TrafficSource、Str（463 个键）、
    │       │   │                       AppLang、Docs（7 个部分）、
    │       │   │                       engine/（隧道）、daemon/（守护进程、NFQUEUE）、
    │       │   │                       net/（SingboxConfig、DnsFailover、解析器）、
    │       │   │                       traffic/（TrafficMeter、Sparkline）、dev/（测试套件）
    │       │   └── ui/
    │       │       ├── AppLang.kt        语言状态、tr()
    │       │       ├── StarfieldBackground.kt
    │       │       ├── Theme.kt          AppThemeRoot
    │       │       ├── components/       按钮、横幅、输入框、卡片、配色
    │       │       ├── nav/              AppRoot、AppTab（6 个标签页）
    │       │       ├── pages/            Tor, VPN, Zapret, GoodbyeDPI, GPS, Settings
    │       │       ├── traffic/          TrafficWindow
    │       │       └── theme/            Color, Spacing, Shape, Type, Ripple
    │       ├── androidMain/.../core/      AndroidCtx, ConfigStore (SAF),
    │       │                              TrafficSource (JNI), PlatformCaps
    │       ├── desktopMain/.../core/      ConfigStore（文件）、TrafficSource（/proc）
    │       ├── jvmShared/.../core/        共享 JVM 部分：net、daemon、engine、
    │       │                              probe、dpi、root、geo、update（21 个文件）
    │       └── desktopTest/.../           1303 个用例，18 个文件
    └── desktopApp/                   桌面应用
        └── build.gradle.kts         mainClass、打包格式
```

---

## 14. 改动后的检查

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd APP
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopTest --offline --rerun-tasks
./gradlew :app:assembleDebug
./gradlew :desktopApp:createDistributable
```

以 root 提交前：

```bash
chown -R azrael:users .
chmod -R u+w .
```

验证应用在设备上不崩溃：

```bash
adb install -r app/build/outputs/apk/root/debug/ZAPP-ROOT-<版本>-debug.apk
adb shell am start -n xyz.azraellab.zapp/.MainActivity
adb logcat -d | grep -iE "FATAL|AndroidRuntime"
```

必须全新安装而不是升级：验证的正是首次启动。

---

## 15. 还有哪些要做

已完成：公共代码能在两个平台构建，设计系统完成，测试全绿（1303 个用例）。可用的连接 —— WireGuard/AmneziaWG、
OpenVPN、sing-box（vless、vmess、trojan、ss、hysteria2、tuic）、
经桥的 Tor、kill-switch；DPI 绕过 —— zapret 和 goodbyedpi 在同一条 NFQUEUE 链上，
策略自动选择；带迷你曲线和会话计数的流量监控；隧道启动前自动替换 DNS。

未完成：GPS 场景与地图（设置页已有）、Nix 和 PKGBUILD。
release APK 签名在本地（`APP/signing/keystore.properties`）和
CI（仓库密钥）都能用。

按阶段的队列，与 README 相同：

| 阶段 | 内容 | 标准 |
|---|---|---|
| 11 | GPS：场景与地图 | 第三方应用里能看到坐标伪装 |
| 12 | 打包：Nix、PKGBUILD | 所有格式都能从干净 checkout 构建 |
| 13 | 打磨、i18n、签名 | 已完成：EN/RU/ZH，签名 release 由 CI 发布 |

</details>
