<div align="center">

# 🛰️ ZAPP

**Development, forking and presets**

[![Platform: Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Windows 10/11](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=flat&logo=windows&logoColor=white)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![Platform: Linux](https://img.shields.io/badge/Linux-deb%20%7C%20AppImage-FCC624?style=flat&logo=linux&logoColor=black)](https://github.com/PAF-DE0H1SS/ZAPP/releases)
[![CI](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml/badge.svg)](https://github.com/PAF-DE0H1SS/ZAPP/actions/workflows/build.yml)
[![License: Free for everyone](https://img.shields.io/badge/License-free%20for%20everyone-2EA44F?style=flat)](LICENSE)

</div>

How the application is put together, how to fork it, and the preset format
with every field described and worked examples. For the full technical
reference see [DOCX.md](DOCX.md); for the project overview see
[README.md](README.md).

---

## Language / Язык / 语言

<details open>
<summary><b>🇬🇧 English</b></summary>

For people building a fork and for anyone who wants to understand how the app
works. One section covers the preset format with a full field reference and
hand-written config examples.

Short product overview is in [ABOUT.md](ABOUT.md), full technical
documentation is in [DOCX.md](DOCX.md).

---

## 1. Stack

| What | Value |
|---|---|
| Language | Kotlin |
| UI | Compose Multiplatform |
| Build | Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.20 |
| JDK | 21 (required, pinned in `gradle-daemon-jvm.properties`) |
| Android | `minSdk=33` (Android 13), `targetSdk=36`, `versionCode=9` |
| Native part | C++ via CMake, `libbox.so` and daemons in `jniLibs` |

The app version lives in one place — `APP/gradle.properties`,
`zappVersion`. Both the APK and the desktop installers take it from there.

---

## 2. Modules

```
ZAPP/
├── APP/                     root Gradle project
│   ├── composeApp/          shared code: Android library + JVM("desktop")
│   ├── app/                 Android app: manifest, MainActivity, resources
│   └── desktopApp/          desktop entry point, jpackage packaging
├── ABOUT.md                 what the app does
├── DOCX.md                  technical documentation
└── DEVELOPMENT.md           this file
```

Two namespaces coexist on purpose:

| Module | Android namespace |
|---|---|
| `:app` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` |

A namespace is a library's identity in Android (generated `R` and
`BuildConfig` land in it), not a Kotlin package. The manifest merger forbids
matching namespaces, but Kotlin packages may well match.

---

## 3. `composeApp` sources

Source sets in `composeApp/src`:

| Source set | Purpose |
|---|---|
| `commonMain` | UI, design system, models, ViewModel, platform-independent core |
| `androidMain` | `actual` implementations for Android (SharedPreferences) |
| `desktopMain` | `actual` implementations for desktop (file system) |
| `jvmShared` | shared JVM part, 21 files; `androidMain` and `desktopMain` depend on it |
| `desktopTest` | the whole test suite: 1654 cases, 31 files |

`jvmShared` exists because part of the code is the same for Android and
desktop, but cannot live in `commonMain`: it is process, socket and file work
that only runs on the JVM. Declared in `composeApp/build.gradle.kts`:

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

It holds `core/net`, `core/daemon`, `core/engine`, `core/probe`,
`core/dpi`, `core/root`, `core/geo`, `core/update` — JVM implementations
whose contract alone lives in `commonMain`. `jvmShared` has no test source
sets: tests for the shared JVM part run from `desktopTest`, where the shared
JVM is already on the classpath.

Main packages:

- `ui/` — `components`, `nav`, `pages`, `theme`, `traffic`, `welcome`,
  `consistency`
- `ui/pages/` — `TorPage`, `VpnPage`, `ZapretPage`, `GoodbyeDpiPage`,
  `GpsPage`, `SettingsPage`, `DaemonControl`, `RoutingCards`, `PerAppSection`,
  `VpnProfileEditor`, `DevTestsPage`
- `core/` — configuration and logic: `AppConfig`, `VpnConfig`, `ZapretConfig`,
  `GoodbyeDpiConfig`, `GpsConfig`, `TrafficConfig`, `engine`, `daemon`,
  `net`, `probe`, `dpi`, `perApp`, `gps`, `log`, `update`, `routing`

---

## 4. How it works

### Configuration

All app state is a single `AppConfig` object. It is serialized to JSON via
`ConfigCodec` and stored on disk. Top-level fields:

```
zapret, goodbyeDpi, vpn, gps, traffic      settings blocks
perf                                       UI performance
usage                                      usage profile
region                                     country code (ISO 3166-1 alpha-2)
perApp                                     per-app tunnelling
relay                                      fine tuning of the shared relay
trafficMonitorEnabled, detailLogEnabled    toggles
welcomeDone                                onboarding done
updateCompareMode, updateSnoozeUntilMs     auto update
version                                    schema version
```

`ConfigCodec` is configured as follows:

```kotlin
prettyPrint = true        the file stays readable by eye
ignoreUnknownKeys = true  an old file does not break on a new field
encodeDefaults = true     every knob shows up in the file, not only the changed ones
```

Parsing a corrupted file returns defaults instead of an exception: a broken
config must not block startup.

### Tunnels

The app does not carry traffic itself — it is a set of local tools.

| Component | What it does |
|---|---|
| VPN | WireGuard / AmneziaWG, OpenVPN, sing-box (vless, vmess, trojan, ss, hysteria2, tuic). Config groups with `urltest`, kill-switch |
| Tor | Bridges from built-in sources, pick by ping, failover, local SOCKS 9050, pluggable transports over `lyrebird` |
| Zapret | `nfqws` under root, NFQUEUE chain `ZAPP_DPI`, DPI bypass strategies |
| GoodbyeDPI | `goodbyedpi` next to zapret on the same chain |
| DNS | With an empty user list, resolvers are probed publicly with a real A request before start |

Daemons start through `core/daemon`; traffic is counted by `core/traffic` —
on Android via the native `libzapp-traffic.so`, on desktop by a Kotlin
implementation.

### Daemons

Native binaries (`tor`, `lyrebird`, `nfqws`, `goodbyedpi`, `wg`,
`openvpn`, `box`) live in `APP/app/src/main/jniLibs/<abi>/`. They are not in
the repository — in CI they are downloaded from the release tagged
`native-bins`. Without them the build still passes, but the APK ships without
VPN/Zapret/Tor and does not go into the release (the release job checks for
`libbox.so`).

---

## 5. Building

### Variants

Two build flavours × two build types give four variants:

| Build flavour | Difference |
|---|---|
| `noroot` | no elevated privileges, works on a regular device |
| `root` | requests the superuser, runs `nfqws` and `goodbyedpi` |

Each variant is further cut by ABI into five APKs: four per architecture
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) plus a universal one. Output:

```
app/build/outputs/apk/{root,noroot}/{debug,release}/
    ZAPP[-ROOT]-<version>.apk             universal
    ZAPP[-ROOT]-<version>-<abi>.apk       split
    ZAPP[-ROOT]-<version>-debug[-<abi>].apk  debug
```

All five APKs of one variant share one `versionCode`, so installing any over
any other replaces it rather than downgrading.

### Commands

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=<path to JDK 21>

./gradlew :app:assembleDebug --offline
./gradlew :app:assembleRelease --offline

# desktop
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
./gradlew :desktopApp:packageMsi            # run it on Windows
```

`--offline` is required once the dependencies are cached: without it Gradle
goes to the network and stalls on unreachable repositories.

### Two settings you must not remove

In `APP/gradle.properties`:

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

This is not about speed. With splits AGP launches one
`IncrementalSplitterRunnable` per ABI through
`workerExecutor.noIsolation()`, so five packers run concurrently inside a
single task. Each writes entries through `ApkFlinger.writeFile` into the
shared `ForkJoinPool.commonPool()`, and when the archive closes the V2
signature submits exactly `parallelism` tasks to the same pool and waits for
all of them at once (`IncrementalPackagerBuilder.lambda$withSigning$0`).

While the pool holds entries from other packers, free workers are never
enough. The build stops at `packageRootRelease` with not a single line of
output and never finishes. `org.gradle.workers.max=1` serializes the splits
themselves, and by signing time nobody is in the pool.

`org.gradle.parallel=false` does not affect this race — it serializes tasks,
while the contention is inside one task. It stays so different projects are
not run at once.

Remove these flags = CI hangs, reproducibly.

### Building on NixOS

`jlink` and `jpackage` need `objcopy`, which is not in `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

NixOS artifacts must not be published — `/nix/store` paths get baked into the
launcher and the jlink runtime. `build-all.sh` sorts the output into
`build/dist/PUBLISHABLE` and `build/dist/UNPUBLISHABLE`.

### Signing

Locally: create a keystore and point to it in `signing/keystore.properties`.
Without that file the release builds unsigned — forks and CI work without
secrets. In CI the signature is applied with a secret keystore at the release
step.

`keytool -printcert -jarfile` answers "Not a signed jar file" for an APK —
that is not an error. Check with:

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<ver>.apk
```

---

## 6. Tests

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1654 cases: WCAG contrast for components and the palette, locale parsing,
config codecs, protocol link parsing, sing-box config generation, bridge
selection, sparkline math, DNS resolver probing.

`--rerun-tasks` is required: without it an unchanged task reports
`UP-TO-DATE`, and the test counter is not recomputed.

Test directories:

The whole suite lives in `composeApp/src/desktopTest/` — 31 files. The main
ones:

| File | What it checks |
|---|---|
| `ui/theme/ThemeTest` | WCAG contrast of palette pairs |
| `ui/components/ComponentsTest` | component contrast |
| `ui/nav/AppTabTest` | tab set and order |
| `core/AppConfigCodecTest` | codecs for `AppConfig` and `VpnConfig` |
| `core/dev/DevTestsParameterized` | run of all `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | protocol link parsing |
| `core/net/SingboxConfigTest` | sing-box config generation |
| `core/net/WireGuardIniTest` | WireGuard config parsing |
| `core/TorBridgesTest` | bridge selection |
| `core/dpi/SplitRelayTest` | split relay math |
| `core/gps/RouteProgressTest` | route progress |
| `core/update/VersionCompareTest` | version comparison |
| `core/I18nTest`, `LinkSpeedTest`, `UsageTunerTest` | locales, speed, usage profile |
| `core/engine/TorBootstrapPercentTest` | Tor bootstrap percentage |
| `core/VpnConnectsTest` | connection list |

`androidMain` and `jvmShared` have no separate test source sets.

---

## 7. CI and releases

`.github/workflows/build.yml`, four jobs:

| Job | What it does |
|---|---|
| Android APK | tests, build of four variants with splits |
| Windows 10/11 MSI | build on `windows-latest` |
| Linux AppImage | build on `ubuntu-latest` |
| GitHub Release | only on `v*` tags |

The build runs on push to `main` and on `v*` tags. The concurrency group is
`build-${{ github.ref }}` with `cancel-in-progress`: a push to a branch
cancels its unfinished run; a tag lives in its own group and builds in
parallel.

The release job collects `*.msi`, `*.AppImage` and APKs by the filter:

```
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

plus a check that `libbox.so` is present inside — an APK without native
daemons does not go into the release. The wildcards in the filter are
required on both sides: split names end with the architecture
(`ZAPP-2.7.0-debug-x86.apk`), and the `*-debug.apk` variant let eight debug
builds through.

---

## 8. Forking: order of work

1. **Copy the repository and remove secrets.** The fork must not contain
   `signing/keystore.properties` or CI keys. They are not in git anyway —
   make sure that is still true.

2. **Build locally.** `./gradlew :app:assembleDebug --offline`. If the
   build hangs at `package*Release` — home-made flags are back in
   `gradle.properties`, see section 5.

3. **Run the tests.** `./gradlew :composeApp:desktopTest --offline
   --rerun-tasks`, wait for 1654/1654.

4. **Check on a clean install.** Remove the old APK, install the new one:

   ```bash
   adb uninstall xyz.azraellab.zapp
   adb install app/build/outputs/apk/noroot/debug/ZAPP-2.7.0-debug-arm64-v8a.apk
   ```

   Installing over the old one is not allowed — what gets checked is a clean
   start.

5. **Check both variants.** `noroot` must work without a root request,
   `root` must show the superuser dialog right away. No request from the
   root build means the flavour did not build.

6. **Check split integrity.** The universal APK must contain 40 libraries
   (10 for each of the four ABIs), a split — 10:

   ```bash
   unzip -l app/build/outputs/apk/root/release/ZAPP-ROOT-2.7.0.apk | grep -c 'lib/'
   ```

   After an interrupted build the packaging task can report `UP-TO-DATE`
   and leave a half-written APK. Better to delete the packer's output
   directory by hand than to trust up-to-date.

7. **Update the documentation.** Version in `README.md` and `DOCX.md`,
   release contents, changed preset fields.

8. **Commit.** The Git identity is the project's regular name and email. The
   commit message describes the change, not the author.

---

## 9. Presets

### 9.1. What it is

A preset is a saved set of settings applied to the configuration as a whole.
In the app presets are imported and exported, live in a separate file, and
survive app updates.

The file is called `<name>.zapp.json`.

The format is JSON. Parsing properties:

| Property | Consequence |
|---|---|
| `ignoreUnknownKeys = true` | extra fields in the file are ignored |
| `encodeDefaults = true` | export writes all fields, not only changed ones |
| missing field | the `data class` default is used |

The third property is the key one for hand-writing: **write only what
changes**, the rest fills itself in. A file exported by the app is complete —
it has every field; a hand-written file can be ten lines.

### 9.2. File shapes

Two acceptable shapes.

Wrapper:

```json
{
  "kind": "zapp-preset",
  "preset": {
    "name": "My preset",
    "zapret": { "enabled": true }
  }
}
```

And a "bare" preset without a wrapper:

```json
{
  "name": "My preset",
  "zapret": { "enabled": true }
}
```

The second shape is accepted on purpose: a file placed by hand or exported
by another app version is worse to reject silently than to accept. Parsing a
wrapper with a wrong `kind` fails (import returns `null`).

### 9.3. Preset structure

The structure (a schema, not a file — `{ ... }` means a block from the
section below):

```text
{
  "id": "",
  "name": "",
  "description": "",
  "createdAtMs": 0,
  "region": "",
  "zapret":     { ... },
  "goodbyeDpi": { ... },
  "vpn":        { ... },
  "gps":        { ... },
  "perApp":     { ... }
}
```

| Field | Type | Purpose |
|---|---|---|
| `id` | string | identifier; may be left empty on import |
| `name` | string | name in the preset list |
| `description` | string | description shown in the list |
| `createdAtMs` | number | creation time in milliseconds, `0` is allowed |
| `region` | string | ISO 3166-1 alpha-2 country code, `""` means no setting |
| `zapret` | object | zapret block, see 9.4 |
| `goodbyeDpi` | object | GoodbyeDPI block, see 9.5 |
| `vpn` | object | VPN block, see 9.6 |
| `gps` | object | coordinate spoofing block, see 9.7 |
| `perApp` | object | app selection, see 9.8 |

On apply these five blocks and `region` are substituted into the app
configuration. The remaining `AppConfig` fields (`traffic`, `perf`, `relay`,
`updateCompareMode`, `welcomeDone`, `version`) are **not touched** by a
preset: system settings and interface state stay as they were.

The `region` field additionally goes through region auto-tuning: some
countries have a policy that hides non-working protocols and turns on bypass
mechanisms. An empty string is a neutral policy.

### 9.4. `zapret` block

All fields and defaults:

```json
"zapret": {
  "enabled": false,
  "binaryPath": "",
  "autoStrategy": true,
  "strategy": "auto",
  "mode": "bypass",
  "verifyStrategy": true,
  "autoRestart": true,

  "domains": "",
  "apps": "",
  "allTraffic": false,
  "exclusions": "",
  "ipv4Only": false,

  "filterTcp": "80,443",
  "filterUdp": "",
  "dpiPorts": "443",
  "filterIp": "",

  "newSyntax": true,
  "bgIncrement": false,
  "hostCase": true,
  "hostSpell": true,
  "hostNoSpace": false,
  "methodSpace": true,
  "methodEol": false,
  "unixEol": false,
  "tlsRec": false,
  "wsSize": null,
  "hostHash": false,

  "desyncMethods": ["fake"],
  "dpiSplit": "",
  "dpiSplitPos": "",
  "dpiSplitSeqovl": null,
  "dpiSplit2Pos": "",
  "dpiSplit2Seqovl": null,

  "fakeTlsMode": "",
  "fakeTlsHost": "",
  "fakeTlsPadLen": null,
  "fakeTlsCutLen": null,
  "fakePacket": false,
  "fakeSkip": false,
  "fakeTcp": false,
  "fakeDport": null,
  "fakeDportTtl": null,
  "fakeSport": null,
  "fakeSrcAddr": "",
  "fakeSeq": null,
  "fakeCsum": false,
  "foolingTs": false,
  "fakeTcpFlags": null,
  "fakeMss": null,
  "fakeRepeats": null,
  "fakeCut": false,
  "fakeCutTls": false,
  "fakeSniff": false,
  "fakeTlsMod": "",

  "autoTtl": "",
  "udpLenIncrement": null,
  "anyProtocol": false,
  "ttl": null,
  "ttlEnd": null,
  "ttl6": false,
  "skipTtl": false,
  "wsize": null,
  "mss": null,
  "multiply": null,
  "multipath": false,
  "ipdiag": false,

  "comment": "",
  "logging": false
}
```

Allowed values of the enumerated fields:

**`strategy`** — `auto`, `disabled`, `multipath`, `multiply-alpn`,
`desync-fake-ttl-tcp-ack`, `desync-fakedsplit`, `dpi-desync`, `fake-sni`,
`fake-tls-alpn`, `syntax-v3`, `fake`, `multisplit-seqovl`,
`fake-fakedsplit-ts`, `messaging`, `games`, `fake-tls-rnd`.

With `autoStrategy: true` the strategy is picked automatically and the
`strategy` field is ignored.

**`mode`** — `bypass` (evasion) or `block` (blocking).

**`desyncMethods`** — array of strings, packet substitution methods.
Default `["fake"]`.

**String lists** (`domains`, `apps`, `exclusions`, `filterTcp`,
`filterUdp`, `dpiPorts`, `filterIp`, `dpiSplit`, `dpiSplitPos`,
`fakeTlsHost`, `autoTtl`) — comma-separated enumerations, not arrays.
`null` in a numeric field means "do not set", and in JSON that is exactly
`null`, not `0`.

**Syntax flags** (`newSyntax`, `hostCase`, `hostSpell`, `hostNoSpace`,
`methodSpace`, `methodEol`, `unixEol`, `tlsRec`, `hostHash`) control
HTTP/TLS header substitution: Host case, spaces, line breaks, TLS record
splitting.

### 9.5. `goodbyeDpi` block

```json
"goodbyeDpi": {
  "mode": "auto",
  "splitPos": "2",
  "fakeSeq": null,
  "fakeCsum": false,
  "fakeSni": "",
  "fakeGen": null,
  "fakeResend": null,
  "autoTtl": "",
  "setTtl": null,
  "maxPayload": null,
  "reverseFrag": false,
  "fragBySni": false,
  "hostCheck": true,
  "domains": "",
  "daemonPath": "",
  "autoRestart": true,
  "autoStart": false,
  "logging": false
}
```

**`mode`** — `auto`, `disabled`, `split2`, `split-pos`, `fake-patch`,
`fake-sni`, `fake-ssl`.

### 9.6. `vpn` block

```json
"vpn": {
  "enabled": false,
  "autoStart": false,
  "activeProfileId": "",
  "profiles": [],
  "routing": {
    "routesAllTraffic": true,
    "excludePrivateRanges": true,
    "excludeMulticast": true
  },
  "killSwitch": false,
  "killSwitchDns": false,
  "ipv6": false,
  "dnsServers": "",
  "splitTunneling": false,
  "dnsFakeIp": false,
  "dnsViaProxy": false,
  "dnsBackup": "",
  "dnsType": "udp",
  "dnsBootstrap": "",
  "dnsStrategy": "off",
  "dnsRewriteTtl": 60,
  "clashApiEnabled": true,
  "clashApiPort": 9090,
  "clashApiSecret": "",

  "sources": [],
  "torEnabled": true,
  "torSocksPort": 9050,
  "groupMode": false,
  "urlTest": {
    "mode": "disabled",
    "includeNames": "",
    "includeRegex": "",
    "includeCountries": "",
    "excludeNames": "",
    "excludeRegex": "",
    "excludeCountries": "",
    "testingUrl": "",
    "interval": "1m",
    "toleranceMs": 10,
    "idleTimeout": ""
  }
}
```

**`sources`** — where connections come from: your own URLs and built-in
repositories. Each element:

```json
{
  "id": "my-list",
  "name": "Custom list",
  "url": "https://example.com/list.txt",
  "mirrors": ["https://mirror.example.com/list.txt"],
  "enabled": true,
  "kind": "proxy",
  "refreshSeconds": 7200,
  "top": false,
  "lastRefreshAtMs": 0
}
```

`kind` — `proxy` or `bridges`. `top: true` moves the source to the top of
the list.

**`torSocksPort`** — the local Tor SOCKS port; box sends traffic through
it. **`torEnabled`** — a legacy flag "show bridges in the VPN list":
bridges live on a separate tab, they never enter VPN lists, the field is
kept only so old configs still read.

**`groupMode`** — group connection: automatic pick over live connections
instead of a fixed node.

**`urlTest`** — node filter for the automatic pick.

**`urlTest.mode`** — `disabled`, `include`, `exclude`, `mixed`.
`includeNames` / `excludeNames` — comma-separated lists, `includeRegex` /
`excludeRegex` — regular expressions, `includeCountries` /
`excludeCountries` — country codes. `interval` — recheck period
(`1m`, `30s`, `5m`), `toleranceMs` — tolerance before switching nodes,
`testingUrl` — address to measure, `idleTimeout` — pause without checks.

**`dnsType`** — `udp`, `tcp`, `tls` (DoT), `https` (DoH). One type for the
whole config; it applies to `dnsServers` and to the DNS lists in profiles.

**`dnsStrategy`** — `off`, `prefer_ipv4`, `prefer_ipv6`, `disable_ipv6`.

**`dnsBootstrap`** — address used to resolve server names. Empty: servers
are resolved through the main DNS and you get a "DNS server is its own
resolver" loop.

A profile in the `profiles` array:

```json
{
  "id": "wg-1",
  "name": "My server",
  "protocol": "wireguard",
  "transport": "udp",
  "gateway": "203.0.113.10",
  "gatewayPort": 51820,
  "address": "10.0.0.2/32",
  "subnetCidr": "0.0.0.0/0",
  "dnsServers": "1.1.1.1",
  "privateKey": "",
  "publicKey": "",
  "presharedKey": "",
  "certPath": "",
  "certPassword": "",
  "keepaliveSeconds": 25,
  "mtu": 1420,
  "autoReconnect": true,
  "reconnectDelaySeconds": 5,
  "maxReconnectAttempts": 0,
  "ipv6": false
}
```

**`protocol`** — `wireguard`, `amneziawg`, `openvpn`, `vless`, `vmess`,
`trojan`, `shadowsocks`, `hysteria2`, `tuic`, `singbox`, `xray`, `tor`,
`socks5`, `http`, `ssh`.

**`transport`** — `udp`, `tcp`, `ws`, `grpc`, `http2`.

`activeProfileId` must match the `id` of the wanted profile, otherwise the
first one in the list becomes active.

### 9.7. `gps` block

```json
"gps": {
  "enabled": false,
  "mode": "fixed",
  "latitude": 0.0,
  "longitude": 0.0,
  "altitudeMeters": 0.0,
  "speedMps": 0.0,
  "speedJitterPercent": 0,
  "positionJitterMeters": 0.0,
  "driftMetersPerSecond": 0.0,
  "route": "",
  "routeLoop": false,
  "routeDwellSeconds": 0,
  "accuracyMeters": 5.0,
  "smoothingSamples": 1,
  "mockElapsedSeconds": 2,
  "reinstallProvider": true,
  "perApp": false,
  "apps": ""
}
```

**`mode`** — `disabled`, `fixed`, `route`, `vpn`.

`route` — a list of route points; each point has latitude, longitude, and
optionally speed and dwellSeconds. `accuracyMeters`, `smoothingSamples`,
`mockElapsedSeconds` tune the spoofing: accuracy, smoothing, update period.

Coordinates must be in range: latitude −90…90, longitude −180…180.

### 9.8. `perApp` block

```json
"perApp": {
  "vpn":     { "enabled": false, "mode": "include", "packages": [] },
  "tor":     { "enabled": false, "mode": "include", "packages": [] },
  "zapret":  { "enabled": false, "mode": "include", "packages": [] },
  "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
  "showSystemApps": false
}
```

`mode` — `include` (only the listed ones) or `exclude` (all but them).
`packages` — Android package names: `com.google.android.youtube`,
`org.telegram.messenger`, and so on.

### 9.9. Validation after writing

`Preset.validate()` returns a list of problem fields. Checked:

| Field | Condition |
|---|---|
| `vpn.profiles[].gatewayPort` | 1…65535 |
| `vpn.profiles[].mtu` | 576…9000 |
| `vpn.profiles[].keepaliveSeconds` | not negative |
| `zapret` | arguments must not be junk of the form `--field=-` |
| `gps.latitude` | −90…90 |
| `gps.longitude` | −180…180 |

An empty list means the config is usable. Import does not block apply on
problems, but broken values will error when the daemon starts.

---

## 10. Preset examples

### Example 1. Minimal: DPI bypass only for the listed domains

Everything not listed keeps its default.

```json
{
  "name": "Throttled resources",
  "description": "Bypass for two domains only",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "fake",
    "domains": "youtube.com,youtu.be",
    "allTraffic": false
  }
}
```

### Example 2. Zapret on all traffic with a manual split

```json
{
  "name": "Split by position",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "dpi-desync",
    "allTraffic": true,
    "filterTcp": "80,443",
    "dpiPorts": "443",
    "dpiSplit": "host",
    "dpiSplitPos": "2",
    "dpiSplitSeqovl": 1,
    "desyncMethods": ["fake"],
    "fakePacket": true,
    "hostCase": true,
    "methodSpace": true
  }
}
```

`allTraffic: true` and `domains` are mutually exclusive: with `allTraffic`
the domain list is not applied.

### Example 3. GoodbyeDPI with SNI substitution

GoodbyeDPI has no separate enable field — the mode is set through `mode`,
and the value `disabled` turns the daemon off.

```json
{
  "name": "SNI swap",
  "goodbyeDpi": {
    "mode": "fake-sni",
    "splitPos": "2",
    "fakeSni": "www.example.com",
    "fakeGen": 3,
    "fakeResend": 1,
    "hostCheck": true,
    "autoRestart": true,
    "domains": "example.com,example.org"
  }
}
```

### Example 4. Kill-switch and split tunneling

```json
{
  "name": "VPN for two apps only",
  "vpn": {
    "enabled": true,
    "killSwitch": true,
    "killSwitchDns": true,
    "splitTunneling": true,
    "dnsServers": "1.1.1.1,8.8.8.8",
    "dnsType": "https",
    "dnsBootstrap": "1.1.1.1",
    "activeProfileId": "wg-main",
    "profiles": [
      {
        "id": "wg-main",
        "name": "Main",
        "protocol": "wireguard",
        "gateway": "203.0.113.10",
        "gatewayPort": 51820,
        "address": "10.0.0.2/32",
        "dnsServers": "1.1.1.1",
        "publicKey": "qIx5...=",
        "privateKey": "0G8...=",
        "mtu": 1420,
        "keepaliveSeconds": 25
      }
    ]
  },
  "perApp": {
    "vpn": {
      "enabled": true,
      "mode": "include",
      "packages": [
        "com.google.android.youtube",
        "org.telegram.messenger"
      ]
    },
    "showSystemApps": false
  }
}
```

`killSwitch: true` with root raises iptables rules that pass only the
connection addresses while the tunnel is up. `killSwitchDns` allows the
config's DNS servers — without it the resolver dies along with the network,
and reconnecting by domain name cannot resolve.

### Example 5. Route and coordinate jitter

```json
{
  "name": "Route by city",
  "gps": {
    "enabled": true,
    "mode": "route",
    "route": "55.7558,37.6173,55.7601,37.6288,55.7710,37.6310",
    "routeLoop": true,
    "routeDwellSeconds": 5,
    "speedMps": 8.5,
    "positionJitterMeters": 12,
    "driftMetersPerSecond": 0.4,
    "accuracyMeters": 6.0,
    "smoothingSamples": 3,
    "mockElapsedSeconds": 2,
    "perApp": true,
    "apps": "com.yandex.taxi"
  }
}
```

`route` — comma-separated coordinates, latitude/longitude pairs.

### Example 6. A full preset as the app exports it

With every field, as after `encodeDefaults = true`. No need to read it all —
this shows what a fully filled file looks like.

```json
{
  "id": "preset-1728000000000",
  "name": "Combined",
  "description": "DPI bypass plus location",
  "createdAtMs": 1728000000000,
  "region": "ru",
  "zapret": {
    "enabled": true,
    "binaryPath": "",
    "autoStrategy": true,
    "strategy": "auto",
    "mode": "bypass",
    "verifyStrategy": true,
    "autoRestart": true,
    "domains": "youtube.com",
    "apps": "",
    "allTraffic": false,
    "exclusions": "",
    "ipv4Only": false,
    "filterTcp": "80,443",
    "filterUdp": "",
    "dpiPorts": "443",
    "filterIp": "",
    "newSyntax": true,
    "bgIncrement": false,
    "hostCase": true,
    "hostSpell": true,
    "hostNoSpace": false,
    "methodSpace": true,
    "methodEol": false,
    "unixEol": false,
    "tlsRec": false,
    "wsSize": null,
    "hostHash": false,
    "desyncMethods": ["fake"],
    "dpiSplit": "",
    "dpiSplitPos": "",
    "dpiSplitSeqovl": null,
    "dpiSplit2Pos": "",
    "dpiSplit2Seqovl": null,
    "fakeTlsMode": "",
    "fakeTlsHost": "",
    "fakeTlsPadLen": null,
    "fakeTlsCutLen": null,
    "fakePacket": false,
    "fakeSkip": false,
    "fakeTcp": false,
    "fakeDport": null,
    "fakeDportTtl": null,
    "fakeSport": null,
    "fakeSrcAddr": "",
    "fakeSeq": null,
    "fakeCsum": false,
    "foolingTs": false,
    "fakeTcpFlags": null,
    "fakeMss": null,
    "fakeRepeats": null,
    "fakeCut": false,
    "fakeCutTls": false,
    "fakeSniff": false,
    "fakeTlsMod": "",
    "autoTtl": "",
    "udpLenIncrement": null,
    "anyProtocol": false,
    "ttl": null,
    "ttlEnd": null,
    "ttl6": false,
    "skipTtl": false,
    "wsize": null,
    "mss": null,
    "multiply": null,
    "multipath": false,
    "ipdiag": false,
    "comment": "",
    "logging": false
  },
  "goodbyeDpi": {
    "mode": "auto",
    "splitPos": "2",
    "fakeSeq": null,
    "fakeCsum": false,
    "fakeSni": "",
    "fakeGen": null,
    "fakeResend": null,
    "autoTtl": "",
    "setTtl": null,
    "maxPayload": null,
    "reverseFrag": false,
    "fragBySni": false,
    "hostCheck": true,
    "domains": "",
    "daemonPath": "",
    "autoRestart": true,
    "autoStart": false,
    "logging": false
  },
  "vpn": {
    "enabled": false,
    "autoStart": false,
    "activeProfileId": "",
    "profiles": [],
    "routing": {
      "routesAllTraffic": true,
      "excludePrivateRanges": true,
      "excludeMulticast": true
    },
    "killSwitch": false,
    "killSwitchDns": false,
    "ipv6": false,
    "dnsServers": "",
    "splitTunneling": false,
    "dnsFakeIp": false,
    "dnsViaProxy": false,
    "dnsBackup": "",
    "dnsType": "udp",
    "dnsBootstrap": "",
    "dnsStrategy": "off",
    "dnsRewriteTtl": 60,
    "clashApiEnabled": true,
    "clashApiPort": 9090,
    "clashApiSecret": "",
    "sources": [],
    "torEnabled": true,
    "torSocksPort": 9050,
    "groupMode": false,
    "urlTest": {
      "mode": "disabled",
      "includeNames": "",
      "includeRegex": "",
      "includeCountries": "",
      "excludeNames": "",
      "excludeRegex": "",
      "excludeCountries": "",
      "testingUrl": "",
      "interval": "1m",
      "toleranceMs": 10,
      "idleTimeout": ""
    }
  },
  "gps": {
    "enabled": false,
    "mode": "fixed",
    "latitude": 0.0,
    "longitude": 0.0,
    "altitudeMeters": 0.0,
    "speedMps": 0.0,
    "speedJitterPercent": 0,
    "positionJitterMeters": 0.0,
    "driftMetersPerSecond": 0.0,
    "route": "",
    "routeLoop": false,
    "routeDwellSeconds": 0,
    "accuracyMeters": 5.0,
    "smoothingSamples": 1,
    "mockElapsedSeconds": 2,
    "reinstallProvider": true,
    "perApp": false,
    "apps": ""
  },
  "perApp": {
    "vpn":     { "enabled": false, "mode": "include", "packages": [] },
    "tor":     { "enabled": false, "mode": "include", "packages": [] },
    "zapret":  { "enabled": false, "mode": "include", "packages": [] },
    "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
    "showSystemApps": false
  }
}
```

---

## 11. Common pitfalls

| Symptom | Cause |
|---|---|
| Build hangs at `packageRootRelease`, no output | `parallel=false` or `workers.max=1` removed from `gradle.properties` |
| A split misses libraries | the build was interrupted, the packaging task reported `UP-TO-DATE`; delete `app/build/outputs/apk` and rebuild |
| Tests report `UP-TO-DATE` instead of a counter | no `--rerun-tasks` |
| `keytool` says "Not a signed jar file" | that is normal, check with `apksigner verify` |
| No `objcopy` on desktop build | binutils not added to `PATH` |
| Android Studio does not open the project for the main user | after a root build the directories became root-owned: `chown -R <user>:users .` |
| AGP pulls Kotlin 2.2.x, `--offline` fails | the root `build.gradle.kts` does not list all plugins with `apply false` |
| Manifest merger complains about namespace | the namespace of `:app` and `:composeApp` collided |
| `projects.composeApp` does not resolve | Gradle 9.x, write `project(":composeApp")` |

</details>

<details>
<summary><b>🇷🇺 Русский</b></summary>

Документ для тех, кто собирает форк, и для тех, кто хочет понять, как работает
приложение. Отдельный раздел — формат пресетов с полным описанием полей и
примерами рукописных конфигов.

Краткая справка по продукту — в [ABOUT.md](ABOUT.md), полная техническая
документация — в [DOCX.md](DOCX.md).

---

## 1. Стек

| Что | Значение |
|---|---|
| Язык | Kotlin |
| UI | Compose Multiplatform |
| Сборка | Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.20 |
| JDK | 21 (обязателен, закреплён в `gradle-daemon-jvm.properties`) |
| Android | `minSdk=33` (Android 13), `targetSdk=36`, `versionCode=9` |
| Нативная часть | C++ через CMake, `libbox.so` и демоны в `jniLibs` |

Версия приложения хранится в одном месте — `APP/gradle.properties`,
`zappVersion`. Оттуда её берут и APK, и десктопные установщики.

---

## 2. Модули

```
ZAPP/
├── APP/                     корневой проект Gradle
│   ├── composeApp/          общий код: Android-библиотека + JVM("desktop")
│   ├── app/                 Android-приложение: манифест, MainActivity, ресурсы
│   └── desktopApp/          десктопная точка входа, упаковка jpackage
├── ABOUT.md                 что делает приложение
├── DOCX.md                  техническая документация
└── DEVELOPMENT.md           этот файл
```

Два namespace сосуществуют намеренно:

| Модуль | namespace Android |
|---|---|
| `:app` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` |

Namespace — это идентичность библиотеки в Android (в него попадают
генерируемые `R` и `BuildConfig`), а не Kotlin-пакет. Merger манифестов
запрещает совпадение namespace, но Kotlin-пакеты совпадать могут.

---

## 3. Исходники `composeApp`

Наборы исходников в `composeApp/src`:

| Набор | Назначение |
|---|---|
| `commonMain` | UI, дизайн-система, модели, ViewModel, платформенно-независимое ядро |
| `androidMain` | `actual`-реализации под Android (SharedPreferences) |
| `desktopMain` | `actual`-реализации под десктоп (файловая система) |
| `jvmShared` | общая JVM-часть, 21 файл; `androidMain` и `desktopMain` зависят от неё |
| `desktopTest` | весь тестовый набор: 1654 кейса, 31 файле |

`jvmShared` появился потому, что часть кода одинакова для Android и десктопа,
но не может лежать в `commonMain`: это работа с процессами, сокетами и
файлами, доступная только на JVM. Объявлен в `composeApp/build.gradle.kts`:

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

Туда попали `core/net`, `core/daemon`, `core/engine`, `core/probe`,
`core/dpi`, `core/root`, `core/geo`, `core/update` — JVM-реализации, у
которых в `commonMain` лежит только контракт. Тестовых наборов у
`jvmShared` нет: тесты общей JVM-части запускаются из `desktopTest`, где
общая JVM уже в classpath.

Основные пакеты:

- `ui/` — `components`, `nav`, `pages`, `theme`, `traffic`, `welcome`,
  `consistency`
- `ui/pages/` — `TorPage`, `VpnPage`, `ZapretPage`, `GoodbyeDpiPage`,
  `GpsPage`, `SettingsPage`, `DaemonControl`, `RoutingCards`, `PerAppSection`,
  `VpnProfileEditor`, `DevTestsPage`
- `core/` — конфигурация и логика: `AppConfig`, `VpnConfig`, `ZapretConfig`,
  `GoodbyeDpiConfig`, `GpsConfig`, `TrafficConfig`, `engine`, `daemon`,
  `net`, `probe`, `dpi`, `perApp`, `gps`, `log`, `update`, `routing`

---

## 4. Как это работает

### Конфигурация

Всё состояние приложения — один объект `AppConfig`. Он сериализуется в JSON
через `ConfigCodec` и лежит на диске. Поля верхнего уровня:

```
zapret, goodbyeDpi, vpn, gps, traffic      блоки настроек
perf                                       производительность UI
usage                                      профиль использования
region                                     код страны (ISO 3166-1 alpha-2)
perApp                                     туннелирование по приложениям
relay                                      тонкая настройка общего релея
trafficMonitorEnabled, detailLogEnabled    переключатели
welcomeDone                                онбординг пройден
updateCompareMode, updateSnoozeUntilMs     автообновление
version                                    версия схемы
```

`ConfigCodec` сконфигурирован так:

```kotlin
prettyPrint = true        файл читается глазами
ignoreUnknownKeys = true  старый файл не падает на новом поле
encodeDefaults = true     в файле видно все ручки, а не только изменённые
```

Разбор повреждённого файла возвращает значения по умолчанию, а не исключение:
сломанный конфиг не должен блокировать запуск.

### Туннели

Приложение не хранит трафик у себя — это набор локальных инструментов.

| Компонент | Что делает |
|---|---|
| VPN | WireGuard / AmneziaWG, OpenVPN, sing-box (vless, vmess, trojan, ss, hysteria2, tuic). Группы конфигов с `urltest`, kill-switch |
| Tor | Мосты из встроенных источников, автовыбор по пингу, фейловер, локальный SOCKS 9050, pluggable-транспорты через `lyrebird` |
| Zapret | `nfqws` под root, цепочка NFQUEUE `ZAPP_DPI`, стратегии обхода DPI |
| GoodbyeDPI | `goodbyedpi` рядом с zapret на той же цепочке |
| DNS | При пустом списке пользователя резолверы публично пробуются настоящим A-запросом до старта |

Запуск демонов идёт через `core/daemon`, трафик считает `core/traffic`
на Android через нативный `libzapp-traffic.so`, на десктопе — Kotlin-реализация.

### Демоны

Нативные бинарники (`tor`, `lyrebird`, `nfqws`, `goodbyedpi`, `wg`,
`openvpn`, `box`) лежат в `APP/app/src/main/jniLibs/<abi>/`. В репозитории
их нет — в CI они скачиваются из релиза с тегом `native-bins`. Без них
сборка пройдёт, но APK будет без VPN/Zapret/Tor и не попадёт в релиз
(проверка `libbox.so` в release-джобе).

---

## 5. Сборка

### Варианты

Два флейвора × два build type дают четыре варианта:

| Флейвор | Отличие |
|---|---|
| `noroot` | без повышенных прав, работает на обычном устройстве |
| `root` | запрашивает суперпользователя, запускает `nfqws` и `goodbyedpi` |

Каждый вариант дополнительно режется по ABI на пять APK: четыре по
архитектурам (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) плюс
универсальный. Выход:

```
app/build/outputs/apk/{root,noroot}/{debug,release}/
    ZAPP[-ROOT]-<версия>.apk              универсал
    ZAPP[-ROOT]-<версия>-<abi>.apk        сплит
    ZAPP[-ROOT]-<версия>-debug[-<abi>].apk  debug
```

У всех пяти APK одного варианта один `versionCode`, поэтому установка
любого поверх любого — замена, а не понижение.

### Команды

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=<путь к JDK 21>

./gradlew :app:assembleDebug --offline
./gradlew :app:assembleRelease --offline

# десктоп
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
./gradlew :desktopApp:packageMsi            # запускать на Windows
```

`--offline` обязателен, если зависимости уже в кеше: без него Gradle ходит в
сеть и тормозит на недоступных репозиториях.

### Две настройки, которые нельзя убрать

В `APP/gradle.properties`:

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

Это не для скорости. При сплитах AGP запускает по одному
`IncrementalSplitterRunnable` на каждый ABI через
`workerExecutor.noIsolation()`, то есть пять упаковщиков работают
одновременно внутри одной задачи. Каждый пишет записи через
`ApkFlinger.writeFile` в общий `ForkJoinPool.commonPool()`, а при закрытии
архива подпись V2 отправляет в тот же пул ровно `parallelism` задач и ждёт
все сразу (`IncrementalPackagerBuilder.lambda$withSigning$0`).

Пока в пуле лежат записи чужих упаковщиков, свободных воркеров не хватает
никогда. Сборка останавливается на `packageRootRelease` без единой строки
вывода и не завершается. `org.gradle.workers.max=1` сериализует сами
сплиты, и к моменту подписи в пуле никого нет.

`org.gradle.parallel=false` на эту гонку не действует — он сериализует
задачи, а конкуренция внутри одной. Оставлен, чтобы не гонять разные
проекты одновременно.

Убрать эти флаги = зависание CI, воспроизводимое стабильно.

### Сборка на NixOS

`jlink` и `jpackage` требуют `objcopy`, которого нет в `PATH`:

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

Артефакты с NixOS публиковать нельзя — в launcher и runtime jlink
вписываются пути `/nix/store`. `build-all.sh` раскладывает результат по
`build/dist/PUBLISHABLE` и `build/dist/UNPUBLISHABLE`.

### Подпись

Локально: создать keystore, указать путь в `signing/keystore.properties`.
Без этого файла release собирается неподписанным — форки и CI работают без
секретов. В CI подпись ставится секретным keystore на шаге релиза.

`keytool -printcert -jarfile` для APK отвечает «Not a signed jar file» — это
не ошибка. Проверять через:

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<ver>.apk
```

---

## 6. Тесты

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1654 кейса: контраст WCAG для компонентов и палитры, разбор локали, кодеки
конфигов, разбор ссылок протоколов, генерация конфига sing-box, выбор мостов,
математика спарклайна, проба DNS-резолверов.

`--rerun-tasks` обязателен: без него неизменившаяся задача отдаёт
`UP-TO-DATE`, и счётчик тестов не пересчитывается.

Каталоги тестов:

Весь набор лежит в `composeApp/src/desktopTest/` — 31 файле. Основные:

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

Отдельных тестовых наборов у `androidMain` и `jvmShared` нет.

---

## 7. CI и релизы

`.github/workflows/build.yml`, четыре джоба:

| Джоб | Что делает |
|---|---|
| Android APK | тесты, сборка четырёх вариантов со сплитами |
| Windows 10/11 MSI | сборка на `windows-latest` |
| Linux AppImage | сборка на `ubuntu-latest` |
| GitHub Release | только на теги `v*` |

Сборка запускается на пуш в `main` и на теги `v*`. Группа конкурентности —
`build-${{ github.ref }}` с `cancel-in-progress`, пуш в ветку отменяет
незавершённый прогон по ней; тег живёт в своей группе и строится параллельно.

Release-джоб собирает `*.msi`, `*.AppImage` и APK по фильтру:

```
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

плюс проверка наличия `libbox.so` внутри — APK без нативных демонов в релиз
не идёт. Звёздочки в фильтре обязательны с обеих сторон: у сплитов имя
заканчивается на архитектуру (`ZAPP-2.7.0-debug-x86.apk`), и вариант
`*-debug.apk` пропускал восемь debug-сборок.

---

## 8. Форк: порядок работы

1. **Скопировать репозиторий и убрать секреты.** В форке не должно быть
   `signing/keystore.properties` и ключей CI. Они и так не в git —
   убедиться, что так и осталось.

2. **Собрать локально.** `./gradlew :app:assembleDebug --offline`. Если
   сборка виснет на `package*Release` — вернулись самодеятельные флаги в
   `gradle.properties`, см. раздел 5.

3. **Прогнать тесты.** `./gradlew :composeApp:desktopTest --offline
   --rerun-tasks`, дождаться 1654/1654.

4. **Проверить на чистой установке.** Старый APK снять, новый поставить:

   ```bash
   adb uninstall xyz.azraellab.zapp
   adb install app/build/outputs/apk/noroot/debug/ZAPP-2.7.0-debug-arm64-v8a.apk
   ```

   Ставить поверх старого нельзя — проверяется именно чистый запуск.

5. **Проверить оба варианта.** `noroot` должен работать без запроса root,
   `root` — сразу выкатить диалог суперпользователя. Отсутствие запроса у
   root-сборки означает, что флейвор не собрался.

6. **Проверить целостность сплитов.** Универсальный APK обязан содержать
   40 библиотек (10 на каждый из четырёх ABI), сплит — 10:

   ```bash
   unzip -l app/build/outputs/apk/root/release/ZAPP-ROOT-2.7.0.apk | grep -c 'lib/'
   ```

   После прерванной сборки задача упаковки может считаться `UP-TO-DATE` и
   оставить недописанный APK. Выходное каталог упаковки лучше удалить
   руками, чем надеяться на up-to-date.

7. **Обновить документацию.** Версия в `README.md` и `DOCX.md`, состав
   релиза, изменённые поля пресетов.

8. **Коммит.** Идентификатор Git — обычное имя и почта проекта. Текст
   коммита описывает изменение, а не автора.

---

## 9. Пресеты

### 9.1. Что это

Пресет — сохранённый набор настроек, который применяется к конфигурации
целиком. В приложении пресеты импортируются и выгружаются, лежат отдельным
файлом, переживают обновление приложения.

Файл называется `<имя>.zapp.json`.

Формат — JSON. Свойства разбора:

| Свойство | Следствие |
|---|---|
| `ignoreUnknownKeys = true` | лишние поля в файле игнорируются |
| `encodeDefaults = true` | при выгрузке пишутся все поля, не только изменённые |
| отсутствующее поле | берётся значение по умолчанию из `data class` |

Третье свойство главное для ручной записи: **писать можно только то, что
меняется**, остальное подставится само. Файл, выгруженный приложением,
полный — в нём все поля; файл, написанный руками, может быть в десять строк.

### 9.2. Вид файла

Два допустимых вида.

Обёртка:

```json
{
  "kind": "zapp-preset",
  "preset": {
    "name": "Мой пресет",
    "zapret": { "enabled": true }
  }
}
```

И «голый» пресет без обёртки:

```json
{
  "name": "Мой пресет",
  "zapret": { "enabled": true }
}
```

Второй вид принимается намеренно: файл, положенный руками или выгруженный
другой версией приложения, отклонять молча хуже, чем принять. Разбор
обёртки с неверным `kind` даёт отказ (импорт возвращает `null`).

### 9.3. Структура пресета

Структура (схема, не файл — `{ ... }` означает блок из раздела ниже):

```text
{
  "id": "",
  "name": "",
  "description": "",
  "createdAtMs": 0,
  "region": "",
  "zapret":     { ... },
  "goodbyeDpi": { ... },
  "vpn":        { ... },
  "gps":        { ... },
  "perApp":     { ... }
}
```

| Поле | Тип | Назначение |
|---|---|---|
| `id` | строка | идентификатор; при импорте можно оставить пустым |
| `name` | строка | имя в списке пресетов |
| `description` | строка | описание, показывается в списке |
| `createdAtMs` | число | время создания в миллисекундах, `0` допустимо |
| `region` | строка | код страны ISO 3166-1 alpha-2, `""` — без настройки |
| `zapret` | объект | блок zapret, см. 9.4 |
| `goodbyeDpi` | объект | блок GoodbyeDPI, см. 9.5 |
| `vpn` | объект | блок VPN, см. 9.6 |
| `gps` | объект | блок подмены координат, см. 9.7 |
| `perApp` | объект | выбор приложений, см. 9.8 |

При применении эти пять блоков и `region` подставляются в конфигурацию
приложения. Остальные поля `AppConfig` (`traffic`, `perf`, `relay`,
`updateCompareMode`, `welcomeDone`, `version`) пресет **не трогает**:
устройственные настройки и состояние интерфейса остаются как были.

Поле `region` дополнительно прогоняется через автонстройку региона: для
части стран есть политика, которая прячет неработающие протоколы и
включает обходные механизмы. Пустая строка — нейтральная политика.

### 9.4. Блок `zapret`

Все поля и значения по умолчанию:

```json
"zapret": {
  "enabled": false,
  "binaryPath": "",
  "autoStrategy": true,
  "strategy": "auto",
  "mode": "bypass",
  "verifyStrategy": true,
  "autoRestart": true,

  "domains": "",
  "apps": "",
  "allTraffic": false,
  "exclusions": "",
  "ipv4Only": false,

  "filterTcp": "80,443",
  "filterUdp": "",
  "dpiPorts": "443",
  "filterIp": "",

  "newSyntax": true,
  "bgIncrement": false,
  "hostCase": true,
  "hostSpell": true,
  "hostNoSpace": false,
  "methodSpace": true,
  "methodEol": false,
  "unixEol": false,
  "tlsRec": false,
  "wsSize": null,
  "hostHash": false,

  "desyncMethods": ["fake"],
  "dpiSplit": "",
  "dpiSplitPos": "",
  "dpiSplitSeqovl": null,
  "dpiSplit2Pos": "",
  "dpiSplit2Seqovl": null,

  "fakeTlsMode": "",
  "fakeTlsHost": "",
  "fakeTlsPadLen": null,
  "fakeTlsCutLen": null,
  "fakePacket": false,
  "fakeSkip": false,
  "fakeTcp": false,
  "fakeDport": null,
  "fakeDportTtl": null,
  "fakeSport": null,
  "fakeSrcAddr": "",
  "fakeSeq": null,
  "fakeCsum": false,
  "foolingTs": false,
  "fakeTcpFlags": null,
  "fakeMss": null,
  "fakeRepeats": null,
  "fakeCut": false,
  "fakeCutTls": false,
  "fakeSniff": false,
  "fakeTlsMod": "",

  "autoTtl": "",
  "udpLenIncrement": null,
  "anyProtocol": false,
  "ttl": null,
  "ttlEnd": null,
  "ttl6": false,
  "skipTtl": false,
  "wsize": null,
  "mss": null,
  "multiply": null,
  "multipath": false,
  "ipdiag": false,

  "comment": "",
  "logging": false
}
```

Допустимые значения перечислимых полей:

**`strategy`** — `auto`, `disabled`, `multipath`, `multiply-alpn`,
`desync-fake-ttl-tcp-ack`, `desync-fakedsplit`, `dpi-desync`, `fake-sni`,
`fake-tls-alpn`, `syntax-v3`, `fake`, `multisplit-seqovl`,
`fake-fakedsplit-ts`, `messaging`, `games`, `fake-tls-rnd`.

При `autoStrategy: true` стратегия подбирается автоматически, поле
`strategy` игнорируется.

**`mode`** — `bypass` (обход) или `block` (блокировка).

**`desyncMethods`** — массив строк, методы подмены пакетов.
Значение по умолчанию `["fake"]`.

**Строковые списки** (`domains`, `apps`, `exclusions`, `filterTcp`,
`filterUdp`, `dpiPorts`, `filterIp`, `dpiSplit`, `dpiSplitPos`,
`fakeTlsHost`, `autoTtl`) — перечисления через запятую, а не массивы.
`null` в числовых полях означает «не задавать», и в JSON это ровно `null`,
а не `0`.

**Флаги синтаксиса** (`newSyntax`, `hostCase`, `hostSpell`, `hostNoSpace`,
`methodSpace`, `methodEol`, `unixEol`, `tlsRec`, `hostHash`) правят
подмену HTTP/TLS-заголовков: регистр Host, пробелы, переводы строк,
разрез TLS-записей.

### 9.5. Блок `goodbyeDpi`

```json
"goodbyeDpi": {
  "mode": "auto",
  "splitPos": "2",
  "fakeSeq": null,
  "fakeCsum": false,
  "fakeSni": "",
  "fakeGen": null,
  "fakeResend": null,
  "autoTtl": "",
  "setTtl": null,
  "maxPayload": null,
  "reverseFrag": false,
  "fragBySni": false,
  "hostCheck": true,
  "domains": "",
  "daemonPath": "",
  "autoRestart": true,
  "autoStart": false,
  "logging": false
}
```

**`mode`** — `auto`, `disabled`, `split2`, `split-pos`, `fake-patch`,
`fake-sni`, `fake-ssl`.

### 9.6. Блок `vpn`

```json
"vpn": {
  "enabled": false,
  "autoStart": false,
  "activeProfileId": "",
  "profiles": [],
  "routing": {
    "routesAllTraffic": true,
    "excludePrivateRanges": true,
    "excludeMulticast": true
  },
  "killSwitch": false,
  "killSwitchDns": false,
  "ipv6": false,
  "dnsServers": "",
  "splitTunneling": false,
  "dnsFakeIp": false,
  "dnsViaProxy": false,
  "dnsBackup": "",
  "dnsType": "udp",
  "dnsBootstrap": "",
  "dnsStrategy": "off",
  "dnsRewriteTtl": 60,
  "clashApiEnabled": true,
  "clashApiPort": 9090,
  "clashApiSecret": "",

  "sources": [],
  "torEnabled": true,
  "torSocksPort": 9050,
  "groupMode": false,
  "urlTest": {
    "mode": "disabled",
    "includeNames": "",
    "includeRegex": "",
    "includeCountries": "",
    "excludeNames": "",
    "excludeRegex": "",
    "excludeCountries": "",
    "testingUrl": "",
    "interval": "1m",
    "toleranceMs": 10,
    "idleTimeout": ""
  }
}
```

**`sources`** — откуда берутся коннекты: свои URL и встроенные репозитории.
Каждый элемент:

```json
{
  "id": "my-list",
  "name": "Свой список",
  "url": "https://example.com/list.txt",
  "mirrors": ["https://mirror.example.com/list.txt"],
  "enabled": true,
  "kind": "proxy",
  "refreshSeconds": 7200,
  "top": false,
  "lastRefreshAtMs": 0
}
```

`kind` — `proxy` или `bridges`. `top: true` поднимает источник наверх списка.

**`torSocksPort`** — локальный SOCKS-порт Tor, через него box и пускает
трафик. **`torEnabled`** — исторический флаг «показывать мосты в списке
VPN»: мосты живут отдельной вкладкой, в списки VPN не попадают, поле
оставлено только чтобы старые конфиги читались.

**`groupMode`** — групповое подключение: автоподбор по живым коннектам
вместо фиксированного узла.

**`urlTest`** — фильтр узлов для автоподбора.

**`urlTest.mode`** — `disabled`, `include`, `exclude`, `mixed`.
`includeNames` / `excludeNames` — списки через запятую, `includeRegex` /
`excludeRegex` — регулярные выражения, `includeCountries` /
`excludeCountries` — коды стран. `interval` — период перепроверки
(`1m`, `30s`, `5m`), `toleranceMs` — допуск до смены узла,
`testingUrl` — адрес для замера, `idleTimeout` — пауза без проверок.

**`dnsType`** — `udp`, `tcp`, `tls` (DoT), `https` (DoH). Один тип на весь
конфиг, относится и к `dnsServers`, и к спискам DNS в профилях.

**`dnsStrategy`** — `off`, `prefer_ipv4`, `prefer_ipv6`, `disable_ipv6`.

**`dnsBootstrap`** — адрес для резолва имён серверов. Пусто: серверы
разрешаются через основной DNS и получается петля «DNS-сервер сам себе
резолвер».

Профиль в массиве `profiles`:

```json
{
  "id": "wg-1",
  "name": "Мой сервер",
  "protocol": "wireguard",
  "transport": "udp",
  "gateway": "203.0.113.10",
  "gatewayPort": 51820,
  "address": "10.0.0.2/32",
  "subnetCidr": "0.0.0.0/0",
  "dnsServers": "1.1.1.1",
  "privateKey": "",
  "publicKey": "",
  "presharedKey": "",
  "certPath": "",
  "certPassword": "",
  "keepaliveSeconds": 25,
  "mtu": 1420,
  "autoReconnect": true,
  "reconnectDelaySeconds": 5,
  "maxReconnectAttempts": 0,
  "ipv6": false
}
```

**`protocol`** — `wireguard`, `amneziawg`, `openvpn`, `vless`, `vmess`,
`trojan`, `shadowsocks`, `hysteria2`, `tuic`, `singbox`, `xray`, `tor`,
`socks5`, `http`, `ssh`.

**`transport`** — `udp`, `tcp`, `ws`, `grpc`, `http2`.

`activeProfileId` должен совпадать с `id` нужного профиля, иначе активным
станет первый в списке.

### 9.7. Блок `gps`

```json
"gps": {
  "enabled": false,
  "mode": "fixed",
  "latitude": 0.0,
  "longitude": 0.0,
  "altitudeMeters": 0.0,
  "speedMps": 0.0,
  "speedJitterPercent": 0,
  "positionJitterMeters": 0.0,
  "driftMetersPerSecond": 0.0,
  "route": "",
  "routeLoop": false,
  "routeDwellSeconds": 0,
  "accuracyMeters": 5.0,
  "smoothingSamples": 1,
  "mockElapsedSeconds": 2,
  "reinstallProvider": true,
  "perApp": false,
  "apps": ""
}
```

**`mode`** — `disabled`, `fixed`, `route`, `vpn`.

`route` — список точек маршрута; у каждой точки latitude, longitude,
опционально speed и dwellSeconds. `accuracyMeters`, `smoothingSamples`,
`mockElapsedSeconds` правят подделку: точность, сглаживание, период
обновления.

Координаты обязаны быть в диапазонах: широта −90…90, долгота −180…180.

### 9.8. Блок `perApp`

```json
"perApp": {
  "vpn":     { "enabled": false, "mode": "include", "packages": [] },
  "tor":     { "enabled": false, "mode": "include", "packages": [] },
  "zapret":  { "enabled": false, "mode": "include", "packages": [] },
  "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
  "showSystemApps": false
}
```

`mode` — `include` (только перечисленные) или `exclude` (все, кроме).
`packages` — имена пакетов Android: `com.google.android.youtube`,
`org.telegram.messenger` и так далее.

### 9.9. Проверка после записи

`Preset.validate()` возвращает список проблемных полей. Проверяется:

| Поле | Условие |
|---|---|
| `vpn.profiles[].gatewayPort` | 1…65535 |
| `vpn.profiles[].mtu` | 576…9000 |
| `vpn.profiles[].keepaliveSeconds` | не отрицательное |
| `zapret` | аргументы не должны быть мусором вида `--поле=-` |
| `gps.latitude` | −90…90 |
| `gps.longitude` | −180…180 |

Пустой список — конфиг пригоден. Импорт не блокирует применение при
проблемах, но битые значения дадут ошибку при старте демона.

---

## 10. Примеры пресетов

### Пример 1. Минимальный: обход DPI только для указанных доменов

Всё, что не перечислено, останется по умолчанию.

```json
{
  "name": "Замедленные ресурсы",
  "description": "Обход только для двух доменов",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "fake",
    "domains": "youtube.com,youtu.be",
    "allTraffic": false
  }
}
```

### Пример 2. Zapret на весь трафик с ручным разрезом

```json
{
  "name": "Разрез по позиции",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "dpi-desync",
    "allTraffic": true,
    "filterTcp": "80,443",
    "dpiPorts": "443",
    "dpiSplit": "host",
    "dpiSplitPos": "2",
    "dpiSplitSeqovl": 1,
    "desyncMethods": ["fake"],
    "fakePacket": true,
    "hostCase": true,
    "methodSpace": true
  }
}
```

`allTraffic: true` вместе с `domains` взаимоисключающи: при `allTraffic`
список доменов не применяется.

### Пример 3. GoodbyeDPI с подменой SNI

У GoodbyeDPI нет отдельного поля включения — режим задаётся через `mode`,
значение `disabled` выключает демон.

```json
{
  "name": "Подмена SNI",
  "goodbyeDpi": {
    "mode": "fake-sni",
    "splitPos": "2",
    "fakeSni": "www.example.com",
    "fakeGen": 3,
    "fakeResend": 1,
    "hostCheck": true,
    "autoRestart": true,
    "domains": "example.com,example.org"
  }
}
```

### Пример 4. Kill-switch и split tunneling

```json
{
  "name": "VPN только для двух приложений",
  "vpn": {
    "enabled": true,
    "killSwitch": true,
    "killSwitchDns": true,
    "splitTunneling": true,
    "dnsServers": "1.1.1.1,8.8.8.8",
    "dnsType": "https",
    "dnsBootstrap": "1.1.1.1",
    "activeProfileId": "wg-main",
    "profiles": [
      {
        "id": "wg-main",
        "name": "Основной",
        "protocol": "wireguard",
        "gateway": "203.0.113.10",
        "gatewayPort": 51820,
        "address": "10.0.0.2/32",
        "dnsServers": "1.1.1.1",
        "publicKey": "qIx5...=",
        "privateKey": "0G8...=",
        "mtu": 1420,
        "keepaliveSeconds": 25
      }
    ]
  },
  "perApp": {
    "vpn": {
      "enabled": true,
      "mode": "include",
      "packages": [
        "com.google.android.youtube",
        "org.telegram.messenger"
      ]
    },
    "showSystemApps": false
  }
}
```

`killSwitch: true` при наличии root поднимает iptables-правила, которые
пропускают только адреса коннекта, пока туннель поднят. `killSwitchDns`
разрешает DNS-серверы конфига — без него резолвер мёртв вместе с сетью,
и переподключение по доменному имени не сможет разрешиться.

### Пример 5. Маршрут и дрожание координат

```json
{
  "name": "Маршрут по городу",
  "gps": {
    "enabled": true,
    "mode": "route",
    "route": "55.7558,37.6173,55.7601,37.6288,55.7710,37.6310",
    "routeLoop": true,
    "routeDwellSeconds": 5,
    "speedMps": 8.5,
    "positionJitterMeters": 12,
    "driftMetersPerSecond": 0.4,
    "accuracyMeters": 6.0,
    "smoothingSamples": 3,
    "mockElapsedSeconds": 2,
    "perApp": true,
    "apps": "com.yandex.taxi"
  }
}
```

`route` — координаты через запятую, пары широта/долгота.

### Пример 6. Полный пресет в форме, которую выгружает приложение

Со всеми полями, как после `encodeDefaults = true`. Читать целиком не
нужно — это показывает, как выглядит полностью заполненный файл.

```json
{
  "id": "preset-1728000000000",
  "name": "Комплексный",
  "description": "Обход DPI плюс гео",
  "createdAtMs": 1728000000000,
  "region": "ru",
  "zapret": {
    "enabled": true,
    "binaryPath": "",
    "autoStrategy": true,
    "strategy": "auto",
    "mode": "bypass",
    "verifyStrategy": true,
    "autoRestart": true,
    "domains": "youtube.com",
    "apps": "",
    "allTraffic": false,
    "exclusions": "",
    "ipv4Only": false,
    "filterTcp": "80,443",
    "filterUdp": "",
    "dpiPorts": "443",
    "filterIp": "",
    "newSyntax": true,
    "bgIncrement": false,
    "hostCase": true,
    "hostSpell": true,
    "hostNoSpace": false,
    "methodSpace": true,
    "methodEol": false,
    "unixEol": false,
    "tlsRec": false,
    "wsSize": null,
    "hostHash": false,
    "desyncMethods": ["fake"],
    "dpiSplit": "",
    "dpiSplitPos": "",
    "dpiSplitSeqovl": null,
    "dpiSplit2Pos": "",
    "dpiSplit2Seqovl": null,
    "fakeTlsMode": "",
    "fakeTlsHost": "",
    "fakeTlsPadLen": null,
    "fakeTlsCutLen": null,
    "fakePacket": false,
    "fakeSkip": false,
    "fakeTcp": false,
    "fakeDport": null,
    "fakeDportTtl": null,
    "fakeSport": null,
    "fakeSrcAddr": "",
    "fakeSeq": null,
    "fakeCsum": false,
    "foolingTs": false,
    "fakeTcpFlags": null,
    "fakeMss": null,
    "fakeRepeats": null,
    "fakeCut": false,
    "fakeCutTls": false,
    "fakeSniff": false,
    "fakeTlsMod": "",
    "autoTtl": "",
    "udpLenIncrement": null,
    "anyProtocol": false,
    "ttl": null,
    "ttlEnd": null,
    "ttl6": false,
    "skipTtl": false,
    "wsize": null,
    "mss": null,
    "multiply": null,
    "multipath": false,
    "ipdiag": false,
    "comment": "",
    "logging": false
  },
  "goodbyeDpi": {
    "mode": "auto",
    "splitPos": "2",
    "fakeSeq": null,
    "fakeCsum": false,
    "fakeSni": "",
    "fakeGen": null,
    "fakeResend": null,
    "autoTtl": "",
    "setTtl": null,
    "maxPayload": null,
    "reverseFrag": false,
    "fragBySni": false,
    "hostCheck": true,
    "domains": "",
    "daemonPath": "",
    "autoRestart": true,
    "autoStart": false,
    "logging": false
  },
  "vpn": {
    "enabled": false,
    "autoStart": false,
    "activeProfileId": "",
    "profiles": [],
    "routing": {
      "routesAllTraffic": true,
      "excludePrivateRanges": true,
      "excludeMulticast": true
    },
    "killSwitch": false,
    "killSwitchDns": false,
    "ipv6": false,
    "dnsServers": "",
    "splitTunneling": false,
    "dnsFakeIp": false,
    "dnsViaProxy": false,
    "dnsBackup": "",
    "dnsType": "udp",
    "dnsBootstrap": "",
    "dnsStrategy": "off",
    "dnsRewriteTtl": 60,
    "clashApiEnabled": true,
    "clashApiPort": 9090,
    "clashApiSecret": "",
    "sources": [],
    "torEnabled": true,
    "torSocksPort": 9050,
    "groupMode": false,
    "urlTest": {
      "mode": "disabled",
      "includeNames": "",
      "includeRegex": "",
      "includeCountries": "",
      "excludeNames": "",
      "excludeRegex": "",
      "excludeCountries": "",
      "testingUrl": "",
      "interval": "1m",
      "toleranceMs": 10,
      "idleTimeout": ""
    }
  },
  "gps": {
    "enabled": false,
    "mode": "fixed",
    "latitude": 0.0,
    "longitude": 0.0,
    "altitudeMeters": 0.0,
    "speedMps": 0.0,
    "speedJitterPercent": 0,
    "positionJitterMeters": 0.0,
    "driftMetersPerSecond": 0.0,
    "route": "",
    "routeLoop": false,
    "routeDwellSeconds": 0,
    "accuracyMeters": 5.0,
    "smoothingSamples": 1,
    "mockElapsedSeconds": 2,
    "reinstallProvider": true,
    "perApp": false,
    "apps": ""
  },
  "perApp": {
    "vpn":     { "enabled": false, "mode": "include", "packages": [] },
    "tor":     { "enabled": false, "mode": "include", "packages": [] },
    "zapret":  { "enabled": false, "mode": "include", "packages": [] },
    "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
    "showSystemApps": false
  }
}
```

---

## 11. Типовые ошибки

| Симптом | Причина |
|---|---|
| Сборка виснет на `packageRootRelease`, вывода нет | в `gradle.properties` убраны `parallel=false` или `workers.max=1` |
| В сплите нет всех библиотек | сборку прерывали, задача упаковки отчелась `UP-TO-DATE`; удалить `app/build/outputs/apk` и пересобрать |
| Тесты отдают `UP-TO-DATE` вместо счётчика | нет `--rerun-tasks` |
| `keytool` говорит «Not a signed jar file» | это нормально, проверять через `apksigner verify` |
| Нет `objcopy` при десктопной сборке | не добавлен binutils в `PATH` |
| Android Studio не открывает проект от основного пользователя | после сборки от root каталоги стали root-владельными: `chown -R <пользователь>:users .` |
| AGP тянет Kotlin 2.2.x, `--offline` падает | в корневом `build.gradle.kts` не перечислены все плагины с `apply false` |
| Merger манифестов ругается на namespace | namespace `:app` и `:composeApp` совпали |
| `projects.composeApp` не резолвится | Gradle 9.x, писать `project(":composeApp")` |

</details>

<details>
<summary><b>🇨🇳 中文</b></summary>

本文写给构建 fork 的人，也写给想弄清应用原理的人。其中一个章节讲预设格式，完整描述
所有字段，并给出手写配置的示例。

产品简述见 [ABOUT.md](ABOUT.md)，完整技术文档见 [DOCX.md](DOCX.md)。

---

## 1. 技术栈

| 项目 | 内容 |
|---|---|
| 语言 | Kotlin |
| UI | Compose Multiplatform |
| 构建 | Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.20 |
| JDK | 21（必需，已在 `gradle-daemon-jvm.properties` 中固定） |
| Android | `minSdk=33`（Android 13）、`targetSdk=36`、`versionCode=9` |
| 原生部分 | C++ 通过 CMake，`libbox.so` 和守护进程放在 `jniLibs` |

应用版本只存一处 —— `APP/gradle.properties` 里的 `zappVersion`。APK 和桌面安装包
都从这里取版本号。

---

## 2. 模块

```
ZAPP/
├── APP/                     Gradle 根项目
│   ├── composeApp/          共享代码：Android 库 + JVM("desktop")
│   ├── app/                 Android 应用：清单、MainActivity、资源
│   └── desktopApp/          桌面入口、jpackage 打包
├── ABOUT.md                 应用做什么
├── DOCX.md                  技术文档
└── DEVELOPMENT.md           本文件
```

两个 namespace 是有意共存的：

| 模块 | Android namespace |
|---|---|
| `:app` | `xyz.azraellab.zapp` |
| `:composeApp` | `xyz.azraellab.zapp.shared` |

Namespace 是库在 Android 中的身份（生成的 `R` 和 `BuildConfig` 归入其中），不是
Kotlin 包。清单 merger 禁止 namespace 重名，但 Kotlin 包可以重名。

---

## 3. `composeApp` 源集

`composeApp/src` 中的源集：

| 源集 | 用途 |
|---|---|
| `commonMain` | UI、设计系统、模型、ViewModel、平台无关内核 |
| `androidMain` | Android 的 `actual` 实现（SharedPreferences） |
| `desktopMain` | 桌面的 `actual` 实现（文件系统） |
| `jvmShared` | 公共 JVM 部分，21 个文件；`androidMain` 和 `desktopMain` 依赖它 |
| `desktopTest` | 全部测试：1654 个用例，18 个文件 |

`jvmShared` 之所以存在，是因为一部分代码在 Android 和桌面上相同，却不能放进
`commonMain`：那是进程、套接字和文件操作，只有 JVM 上才可用。它在
`composeApp/build.gradle.kts` 中声明：

```kotlin
val jvmShared by creating { ... }
getByName("androidMain").dependsOn(jvmShared)
getByName("desktopMain").dependsOn(jvmShared)
```

放进去的有 `core/net`、`core/daemon`、`core/engine`、`core/probe`、
`core/dpi`、`core/root`、`core/geo`、`core/update` —— 这些 JVM 实现在
`commonMain` 只留了接口约定。`jvmShared` 没有自己的测试源集：公共 JVM 部分的
测试在 `desktopTest` 里跑，那里公共 JVM 已经在 classpath 上。

主要包：

- `ui/` —— `components`、`nav`、`pages`、`theme`、`traffic`、`welcome`、
  `consistency`
- `ui/pages/` —— `TorPage`、`VpnPage`、`ZapretPage`、`GoodbyeDpiPage`、
  `GpsPage`、`SettingsPage`、`DaemonControl`、`RoutingCards`、`PerAppSection`、
  `VpnProfileEditor`、`DevTestsPage`
- `core/` —— 配置与逻辑：`AppConfig`、`VpnConfig`、`ZapretConfig`、
  `GoodbyeDpiConfig`、`GpsConfig`、`TrafficConfig`、`engine`、`daemon`、
  `net`、`probe`、`dpi`、`perApp`、`gps`、`log`、`update`、`routing`

---

## 4. 工作原理

### 配置

应用的全部状态就是一个 `AppConfig` 对象。它经 `ConfigCodec` 序列化成 JSON 落到
磁盘。顶层字段：

```
zapret, goodbyeDpi, vpn, gps, traffic      配置块
perf                                       UI 性能
usage                                      使用档位
region                                     国家代码 (ISO 3166-1 alpha-2)
perApp                                     按应用分流
relay                                      共享中继的细调
trafficMonitorEnabled, detailLogEnabled    开关
welcomeDone                                引导已完成
updateCompareMode, updateSnoozeUntilMs     自动更新
version                                    结构版本
```

`ConfigCodec` 的配置如下：

```kotlin
prettyPrint = true        文件肉眼可读
ignoreUnknownKeys = true  旧文件遇到新字段不会报错
encodeDefaults = true     文件里能看到全部开关，而不只是改过的
```

解析损坏的文件时返回默认值而不是抛异常：坏配置不应该阻塞启动。

### 隧道

应用自己不转发流量 —— 它是一组本地工具。

| 组件 | 作用 |
|---|---|
| VPN | WireGuard / AmneziaWG、OpenVPN、sing-box（vless、vmess、trojan、ss、hysteria2、tuic）。配置分组支持 `urltest`，带 kill-switch |
| Tor | 桥接来自内置源，按 ping 自动选择，支持故障转移，本地 SOCKS 9050，pluggable 传输走 `lyrebird` |
| Zapret | `nfqws` 以 root 运行，NFQUEUE 链 `ZAPP_DPI`，多种 DPI 绕过策略 |
| GoodbyeDPI | `goodbyedpi` 与 zapret 并排跑在同一条链上 |
| DNS | 用户列表为空时，启动前用真实 A 查询公开测试解析器 |

守护进程由 `core/daemon` 启动；流量统计由 `core/traffic` 负责，Android 上用原生
`libzapp-traffic.so`，桌面上是 Kotlin 实现。

### 守护进程

原生二进制（`tor`、`lyrebird`、`nfqws`、`goodbyedpi`、`wg`、`openvpn`、
`box`）放在 `APP/app/src/main/jniLibs/<abi>/`。仓库里没有它们 —— CI 会从标签为
`native-bins` 的 release 下载。没有它们构建照样能过，但 APK 缺少 VPN/Zapret/Tor，
也进不了 release（release job 会检查 `libbox.so`）。

---

## 5. 构建

### 变体

两个构建变体 × 两种 build type，得到四个变体：

| 构建变体 | 区别 |
|---|---|
| `noroot` | 不提权，普通设备可用 |
| `root` | 请求超级用户，运行 `nfqws` 和 `goodbyedpi` |

每个变体还会按 ABI 拆成五个 APK：四个按架构（`arm64-v8a`、`armeabi-v7a`、
`x86`、`x86_64`）加一个通用包。输出：

```
app/build/outputs/apk/{root,noroot}/{debug,release}/
    ZAPP[-ROOT]-<版本>.apk                  通用包
    ZAPP[-ROOT]-<版本>-<abi>.apk            按架构拆分
    ZAPP[-ROOT]-<版本>-debug[-<abi>].apk      debug
```

同一变体的五个 APK 共用一个 `versionCode`，所以任何一个覆盖另一个都是覆盖安装，
不会降级。

### 命令

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=<JDK 21 的路径>

./gradlew :app:assembleDebug --offline
./gradlew :app:assembleRelease --offline

# 桌面
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageUberJarForCurrentOS
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
./gradlew :desktopApp:packageMsi            # 在 Windows 上运行
```

依赖已在缓存里时必须加 `--offline`：否则 Gradle 会去联网，在不可用的仓库上卡住。

### 两个不能去掉的设置

在 `APP/gradle.properties` 中：

```properties
org.gradle.parallel=false
org.gradle.workers.max=1
```

这不是为了速度。做拆分时，AGP 通过 `workerExecutor.noIsolation()` 为每个 ABI 各
起一个 `IncrementalSplitterRunnable`，也就是五个打包器同时跑在同一个任务里。每个
都通过 `ApkFlinger.writeFile` 把记录写进共享的 `ForkJoinPool.commonPool()`；归档
关闭时，V2 签名往同一个池里一次性投递正好 `parallelism` 个任务，并且全部等完
（`IncrementalPackagerBuilder.lambda$withSigning$0`）。

只要池里还躺着别的打包器的记录，空闲 worker 就永远不够。构建会卡在
`packageRootRelease`，一行输出都没有，也不会结束。`org.gradle.workers.max=1`
让各个拆分串行执行，到签名时池里已经没人了。

`org.gradle.parallel=false` 对这场竞争无效 —— 它串行化的是任务，而竞争发生在单个
任务内部。保留它是为了不让多个项目同时跑。

去掉这些标志 = CI 稳定复现的卡死。

### 在 NixOS 上构建

`jlink` 和 `jpackage` 需要 `objcopy`，而 `PATH` 里没有：

```bash
export PATH=/nix/store/*binutils-*/bin:$PATH
```

NixOS 上产出的构件不能发布 —— jlink 会把 `/nix/store` 路径写进启动器和运行时。
`build-all.sh` 会把结果分到 `build/dist/PUBLISHABLE` 和
`build/dist/UNPUBLISHABLE`。

### 签名

本地：创建 keystore，在 `signing/keystore.properties` 里写上路径。没有这个文件时
release 打出来是未签名的 —— 这样 fork 和 CI 不需要任何机密。CI 在 release 步骤用
机密 keystore 签名。

对 APK 执行 `keytool -printcert -jarfile` 会回答「Not a signed jar file」，这不是
错误。用下面的方式检查：

```bash
apksigner verify --print-certs app/build/outputs/apk/root/release/ZAPP-ROOT-<ver>.apk
```

---

## 6. 测试

```bash
./gradlew :composeApp:desktopTest --offline --rerun-tasks
```

1654 个用例：组件与调色板的 WCAG 对比度、区域设置解析、配置编解码、协议链接解析、
sing-box 配置生成、桥接选择、sparkline 计算、DNS 解析器探测。

必须加 `--rerun-tasks`：否则未变更的任务会返回 `UP-TO-DATE`，测试计数不会重新统计。

测试目录：

全部测试在 `composeApp/src/desktopTest/`，共 18 个文件。主要的：

| 文件 | 检查内容 |
|---|---|
| `ui/theme/ThemeTest` | 调色板配对的 WCAG 对比度 |
| `ui/components/ComponentsTest` | 组件对比度 |
| `ui/nav/AppTabTest` | 标签页的组成与顺序 |
| `core/AppConfigCodecTest` | `AppConfig` 和 `VpnConfig` 的编解码 |
| `core/dev/DevTestsParameterized` | 运行全部 `core/dev/*Suite.kt` |
| `core/net/ProxyUriTest` | 协议链接解析 |
| `core/net/SingboxConfigTest` | sing-box 配置生成 |
| `core/net/WireGuardIniTest` | WireGuard 配置解析 |
| `core/TorBridgesTest` | 桥接选择 |
| `core/dpi/SplitRelayTest` | 拆分中继的计算 |
| `core/gps/RouteProgressTest` | 路线进度 |
| `core/update/VersionCompareTest` | 版本比较 |
| `core/I18nTest`, `LinkSpeedTest`, `UsageTunerTest` | 区域设置、速度、使用画像 |
| `core/engine/TorBootstrapPercentTest` | Tor 启动进度百分比 |
| `core/VpnConnectsTest` | 连接列表 |

`androidMain` 和 `jvmShared` 没有独立的测试源集。

---

## 7. CI 与发布

`.github/workflows/build.yml`，四个 job：

| Job | 作用 |
|---|---|
| Android APK | 测试，构建四个变体及拆分包 |
| Windows 10/11 MSI | 在 `windows-latest` 上构建 |
| Linux AppImage | 在 `ubuntu-latest` 上构建 |
| GitHub Release | 只在 `v*` 标签触发 |

推送到 `main` 和打 `v*` 标签时触发构建。并发组是 `build-${{ github.ref }}`，带
`cancel-in-progress`：向分支推送会取消该分支上未完成的运行；标签在自己的组里，
与其他构建并行。

release job 按下面的过滤器收集 `*.msi`、`*.AppImage` 和 APK：

```
find artifacts -type f -name 'ZAPP-*.apk' ! -name '*-debug*' ! -name '*unsigned*'
```

另外还要检查包内有没有 `libbox.so` —— 没有原生守护进程的 APK 不进 release。
过滤器两边的星号都必须有：拆分包的文件名以架构结尾
（`ZAPP-2.7.0-debug-x86.apk`），而 `*-debug.apk` 这种写法会漏掉八个 debug 构建。

---

## 8. Fork：操作顺序

1. **复制仓库并清掉机密。** fork 里不应有 `signing/keystore.properties` 和 CI
   密钥。它们本来就不在 git 里 —— 确认仍然如此。

2. **本地构建。** `./gradlew :app:assembleDebug --offline`。如果构建卡在
   `package*Release`，说明 `gradle.properties` 里又被加回了自作主张的标志，
   见第 5 节。

3. **跑测试。** `./gradlew :composeApp:desktopTest --offline
   --rerun-tasks`，等到 1654/1654。

4. **验证全新安装。** 卸掉旧 APK，装新的：

   ```bash
   adb uninstall xyz.azraellab.zapp
   adb install app/build/outputs/apk/noroot/debug/ZAPP-2.7.0-debug-arm64-v8a.apk
   ```

   不能覆盖安装 —— 要验证的正是全新启动。

5. **两个变体都要验证。** `noroot` 应该在不请求 root 的情况下工作，`root` 应该
   立刻弹出超级用户对话框。root 构建没有请求，说明该构建变体没编出来。

6. **检查拆分完整性。** 通用 APK 必须含 40 个库（四个 ABI 各 10 个），拆分包
   是 10 个：

   ```bash
   unzip -l app/build/outputs/apk/root/release/ZAPP-ROOT-2.7.0.apk | grep -c 'lib/'
   ```

   构建被打断后，打包任务可能被判为 `UP-TO-DATE`，留下一个没写完的 APK。打包
   输出目录最好手动删掉，别指望 up-to-date。

7. **更新文档。** `README.md` 和 `DOCX.md` 里的版本、release 内容、预设中改动
   过的字段。

8. **提交。** Git 身份用项目的常用名字和邮箱。提交信息描述改动，不描述作者。

---

## 9. 预设

### 9.1. 是什么

预设是一组保存下来的设置，应用时整体写进配置。在应用里预设可以导入、导出，以单独
文件存放，应用升级后仍然有效。

文件名是 `<имя>.zapp.json`。

格式是 JSON。解析特性：

| 特性 | 结果 |
|---|---|
| `ignoreUnknownKeys = true` | 文件里多余的字段被忽略 |
| `encodeDefaults = true` | 导出时写入全部字段，不只是改过的 |
| 字段缺失 | 取 `data class` 的默认值 |

手写时第三条最关键：**只写要改的**，其余会自动补上。应用导出的文件是完整的，
包含所有字段；手写的文件可以只有十行。

### 9.2. 文件形态

两种合法形态。

带外层包装：

```json
{
  "kind": "zapp-preset",
  "preset": {
    "name": "我的预设",
    "zapret": { "enabled": true }
  }
}
```

以及不带包装的「裸」预设：

```json
{
  "name": "我的预设",
  "zapret": { "enabled": true }
}
```

第二种是有意支持的：手写的文件、或者别的应用版本导出的文件，默默拒绝不如接受。
外层包装的 `kind` 不对才会拒绝（导入返回 `null`）。

### 9.3. 预设结构

结构（这是结构定义，不是文件 —— `{ ... }` 表示下一节里的块）：

```text
{
  "id": "",
  "name": "",
  "description": "",
  "createdAtMs": 0,
  "region": "",
  "zapret":     { ... },
  "goodbyeDpi": { ... },
  "vpn":        { ... },
  "gps":        { ... },
  "perApp":     { ... }
}
```

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | 字符串 | 标识符；导入时可以留空 |
| `name` | 字符串 | 预设列表中的名称 |
| `description` | 字符串 | 描述，显示在列表里 |
| `createdAtMs` | 数字 | 创建时间（毫秒），`0` 也可以 |
| `region` | 字符串 | ISO 3166-1 alpha-2 国家代码，`""` 表示不设置 |
| `zapret` | 对象 | zapret 块，见 9.4 |
| `goodbyeDpi` | 对象 | GoodbyeDPI 块，见 9.5 |
| `vpn` | 对象 | VPN 块，见 9.6 |
| `gps` | 对象 | 坐标伪造块，见 9.7 |
| `perApp` | 对象 | 应用选择，见 9.8 |

应用预设时，这五个块和 `region` 会写入应用配置。`AppConfig` 的其余字段
（`traffic`、`perf`、`relay`、`updateCompareMode`、`welcomeDone`、`version`）
预设**不动**：设备设置和界面状态保持原样。

`region` 字段还会再过一遍区域自动调整：部分国家有对应策略，会隐藏不可用的协议并
开启绕过机制。空字符串表示中性策略。

### 9.4. `zapret` 块

全部字段与默认值：

```json
"zapret": {
  "enabled": false,
  "binaryPath": "",
  "autoStrategy": true,
  "strategy": "auto",
  "mode": "bypass",
  "verifyStrategy": true,
  "autoRestart": true,

  "domains": "",
  "apps": "",
  "allTraffic": false,
  "exclusions": "",
  "ipv4Only": false,

  "filterTcp": "80,443",
  "filterUdp": "",
  "dpiPorts": "443",
  "filterIp": "",

  "newSyntax": true,
  "bgIncrement": false,
  "hostCase": true,
  "hostSpell": true,
  "hostNoSpace": false,
  "methodSpace": true,
  "methodEol": false,
  "unixEol": false,
  "tlsRec": false,
  "wsSize": null,
  "hostHash": false,

  "desyncMethods": ["fake"],
  "dpiSplit": "",
  "dpiSplitPos": "",
  "dpiSplitSeqovl": null,
  "dpiSplit2Pos": "",
  "dpiSplit2Seqovl": null,

  "fakeTlsMode": "",
  "fakeTlsHost": "",
  "fakeTlsPadLen": null,
  "fakeTlsCutLen": null,
  "fakePacket": false,
  "fakeSkip": false,
  "fakeTcp": false,
  "fakeDport": null,
  "fakeDportTtl": null,
  "fakeSport": null,
  "fakeSrcAddr": "",
  "fakeSeq": null,
  "fakeCsum": false,
  "foolingTs": false,
  "fakeTcpFlags": null,
  "fakeMss": null,
  "fakeRepeats": null,
  "fakeCut": false,
  "fakeCutTls": false,
  "fakeSniff": false,
  "fakeTlsMod": "",

  "autoTtl": "",
  "udpLenIncrement": null,
  "anyProtocol": false,
  "ttl": null,
  "ttlEnd": null,
  "ttl6": false,
  "skipTtl": false,
  "wsize": null,
  "mss": null,
  "multiply": null,
  "multipath": false,
  "ipdiag": false,

  "comment": "",
  "logging": false
}
```

可枚举字段的取值：

**`strategy`** —— `auto`、`disabled`、`multipath`、`multiply-alpn`、
`desync-fake-ttl-tcp-ack`、`desync-fakedsplit`、`dpi-desync`、`fake-sni`、
`fake-tls-alpn`、`syntax-v3`、`fake`、`multisplit-seqovl`、
`fake-fakedsplit-ts`、`messaging`、`games`、`fake-tls-rnd`。

`autoStrategy: true` 时策略自动挑选，`strategy` 字段被忽略。

**`mode`** —— `bypass`（绕过）或 `block`（拦截）。

**`desyncMethods`** —— 字符串数组，数据包伪造方法。默认值 `["fake"]`。

**字符串列表**（`domains`、`apps`、`exclusions`、`filterTcp`、`filterUdp`、
`dpiPorts`、`filterIp`、`dpiSplit`、`dpiSplitPos`、`fakeTlsHost`、
`autoTtl`）用逗号分隔，不是数组。数值字段里的 `null` 表示「不设置」，在 JSON 里
就是 `null`，不是 `0`。

**语法标志**（`newSyntax`、`hostCase`、`hostSpell`、`hostNoSpace`、
`methodSpace`、`methodEol`、`unixEol`、`tlsRec`、`hostHash`）控制 HTTP/TLS 头的
伪造：Host 大小写、空格、换行、TLS 记录切分。

### 9.5. `goodbyeDpi` 块

```json
"goodbyeDpi": {
  "mode": "auto",
  "splitPos": "2",
  "fakeSeq": null,
  "fakeCsum": false,
  "fakeSni": "",
  "fakeGen": null,
  "fakeResend": null,
  "autoTtl": "",
  "setTtl": null,
  "maxPayload": null,
  "reverseFrag": false,
  "fragBySni": false,
  "hostCheck": true,
  "domains": "",
  "daemonPath": "",
  "autoRestart": true,
  "autoStart": false,
  "logging": false
}
```

**`mode`** —— `auto`、`disabled`、`split2`、`split-pos`、`fake-patch`、
`fake-sni`、`fake-ssl`。

### 9.6. `vpn` 块

```json
"vpn": {
  "enabled": false,
  "autoStart": false,
  "activeProfileId": "",
  "profiles": [],
  "routing": {
    "routesAllTraffic": true,
    "excludePrivateRanges": true,
    "excludeMulticast": true
  },
  "killSwitch": false,
  "killSwitchDns": false,
  "ipv6": false,
  "dnsServers": "",
  "splitTunneling": false,
  "dnsFakeIp": false,
  "dnsViaProxy": false,
  "dnsBackup": "",
  "dnsType": "udp",
  "dnsBootstrap": "",
  "dnsStrategy": "off",
  "dnsRewriteTtl": 60,
  "clashApiEnabled": true,
  "clashApiPort": 9090,
  "clashApiSecret": "",

  "sources": [],
  "torEnabled": true,
  "torSocksPort": 9050,
  "groupMode": false,
  "urlTest": {
    "mode": "disabled",
    "includeNames": "",
    "includeRegex": "",
    "includeCountries": "",
    "excludeNames": "",
    "excludeRegex": "",
    "excludeCountries": "",
    "testingUrl": "",
    "interval": "1m",
    "toleranceMs": 10,
    "idleTimeout": ""
  }
}
```

**`sources`** —— 连接的来源：自定义 URL 和内置仓库。每一项：

```json
{
  "id": "my-list",
  "name": "自定义列表",
  "url": "https://example.com/list.txt",
  "mirrors": ["https://mirror.example.com/list.txt"],
  "enabled": true,
  "kind": "proxy",
  "refreshSeconds": 7200,
  "top": false,
  "lastRefreshAtMs": 0
}
```

`kind` 为 `proxy` 或 `bridges`。`top: true` 把该来源置顶。

**`torSocksPort`** —— Tor 的本地 SOCKS 端口，box 就从这里发流量。
**`torEnabled`** 是历史遗留标志，原意是「在 VPN 列表里显示桥接」：桥接现在单独
占一个标签页，不进 VPN 列表，留着这个字段只是为了让老配置还能读。

**`groupMode`** —— 分组连接：按存活连接自动挑选，而不是固定节点。

**`urlTest`** —— 自动挑选时的节点过滤器。

**`urlTest.mode`** —— `disabled`、`include`、`exclude`、`mixed`。
`includeNames` / `excludeNames` 是逗号分隔的列表，`includeRegex` /
`excludeRegex` 是正则表达式，`includeCountries` / `excludeCountries` 是国家代码。
`interval` 是复查周期（`1m`、`30s`、`5m`），`toleranceMs` 是换节点的容差，
`testingUrl` 是测速地址，`idleTimeout` 是不检查的空闲时长。

**`dnsType`** —— `udp`、`tcp`、`tls`（DoT）、`https`（DoH）。整个配置只用一种
类型，同时作用于 `dnsServers` 和各配置文件里的 DNS 列表。

**`dnsStrategy`** —— `off`、`prefer_ipv4`、`prefer_ipv6`、`disable_ipv6`。

**`dnsBootstrap`** —— 解析服务器域名用的地址。留空时，服务器名走主 DNS 解析，
结果就是「DNS 服务器自己解析自己」的环路。

`profiles` 数组里的配置文件：

```json
{
  "id": "wg-1",
  "name": "我的服务器",
  "protocol": "wireguard",
  "transport": "udp",
  "gateway": "203.0.113.10",
  "gatewayPort": 51820,
  "address": "10.0.0.2/32",
  "subnetCidr": "0.0.0.0/0",
  "dnsServers": "1.1.1.1",
  "privateKey": "",
  "publicKey": "",
  "presharedKey": "",
  "certPath": "",
  "certPassword": "",
  "keepaliveSeconds": 25,
  "mtu": 1420,
  "autoReconnect": true,
  "reconnectDelaySeconds": 5,
  "maxReconnectAttempts": 0,
  "ipv6": false
}
```

**`protocol`** —— `wireguard`、`amneziawg`、`openvpn`、`vless`、`vmess`、
`trojan`、`shadowsocks`、`hysteria2`、`tuic`、`singbox`、`xray`、`tor`、
`socks5`、`http`、`ssh`。

**`transport`** —— `udp`、`tcp`、`ws`、`grpc`、`http2`。

`activeProfileId` 必须等于目标配置文件的 `id`，否则生效的会是列表里的第一个。

### 9.7. `gps` 块

```json
"gps": {
  "enabled": false,
  "mode": "fixed",
  "latitude": 0.0,
  "longitude": 0.0,
  "altitudeMeters": 0.0,
  "speedMps": 0.0,
  "speedJitterPercent": 0,
  "positionJitterMeters": 0.0,
  "driftMetersPerSecond": 0.0,
  "route": "",
  "routeLoop": false,
  "routeDwellSeconds": 0,
  "accuracyMeters": 5.0,
  "smoothingSamples": 1,
  "mockElapsedSeconds": 2,
  "reinstallProvider": true,
  "perApp": false,
  "apps": ""
}
```

**`mode`** —— `disabled`、`fixed`、`route`、`vpn`。

`route` 是路线点列表；每个点有 latitude、longitude，可选 speed 和 dwellSeconds。
`accuracyMeters`、`smoothingSamples`、`mockElapsedSeconds` 调整伪造效果：精度、
平滑、刷新周期。

坐标必须落在范围内：纬度 −90…90，经度 −180…180。

### 9.8. `perApp` 块

```json
"perApp": {
  "vpn":     { "enabled": false, "mode": "include", "packages": [] },
  "tor":     { "enabled": false, "mode": "include", "packages": [] },
  "zapret":  { "enabled": false, "mode": "include", "packages": [] },
  "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
  "showSystemApps": false
}
```

`mode` 为 `include`（仅列出的）或 `exclude`（除列出的之外全部）。`packages` 是
Android 包名：`com.google.android.youtube`、`org.telegram.messenger` 等。

### 9.9. 写入后的校验

`Preset.validate()` 返回有问题的字段列表。检查项：

| 字段 | 条件 |
|---|---|
| `vpn.profiles[].gatewayPort` | 1…65535 |
| `vpn.profiles[].mtu` | 576…9000 |
| `vpn.profiles[].keepaliveSeconds` | 非负 |
| `zapret` | 参数不应是 `--поле=-` 这类垃圾 |
| `gps.latitude` | −90…90 |
| `gps.longitude` | −180…180 |

列表为空说明配置可用。导入发现问题时不会阻止应用，但错误值会在守护进程启动时报错。

---

## 10. 预设示例

### 示例 1. 最小化：只对指定域名绕过 DPI

没列出的一切保持默认值。

```json
{
  "name": "被限速的资源",
  "description": "只为两个域名解绕",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "fake",
    "domains": "youtube.com,youtu.be",
    "allTraffic": false
  }
}
```

### 示例 2. Zapret 全流量加手动切分

```json
{
  "name": "按位置切分",
  "zapret": {
    "enabled": true,
    "autoStrategy": false,
    "strategy": "dpi-desync",
    "allTraffic": true,
    "filterTcp": "80,443",
    "dpiPorts": "443",
    "dpiSplit": "host",
    "dpiSplitPos": "2",
    "dpiSplitSeqovl": 1,
    "desyncMethods": ["fake"],
    "fakePacket": true,
    "hostCase": true,
    "methodSpace": true
  }
}
```

`allTraffic: true` 与 `domains` 互斥：`allTraffic` 开启时域名列表不生效。

### 示例 3. GoodbyeDPI 伪造 SNI

GoodbyeDPI 没有独立的开关字段 —— 模式由 `mode` 决定，取值 `disabled` 表示关闭
守护进程。

```json
{
  "name": "SNI 替换",
  "goodbyeDpi": {
    "mode": "fake-sni",
    "splitPos": "2",
    "fakeSni": "www.example.com",
    "fakeGen": 3,
    "fakeResend": 1,
    "hostCheck": true,
    "autoRestart": true,
    "domains": "example.com,example.org"
  }
}
```

### 示例 4. Kill-switch 与 split tunneling

```json
{
  "name": "VPN 只用于两个应用",
  "vpn": {
    "enabled": true,
    "killSwitch": true,
    "killSwitchDns": true,
    "splitTunneling": true,
    "dnsServers": "1.1.1.1,8.8.8.8",
    "dnsType": "https",
    "dnsBootstrap": "1.1.1.1",
    "activeProfileId": "wg-main",
    "profiles": [
      {
        "id": "wg-main",
        "name": "主配置",
        "protocol": "wireguard",
        "gateway": "203.0.113.10",
        "gatewayPort": 51820,
        "address": "10.0.0.2/32",
        "dnsServers": "1.1.1.1",
        "publicKey": "qIx5...=",
        "privateKey": "0G8...=",
        "mtu": 1420,
        "keepaliveSeconds": 25
      }
    ]
  },
  "perApp": {
    "vpn": {
      "enabled": true,
      "mode": "include",
      "packages": [
        "com.google.android.youtube",
        "org.telegram.messenger"
      ]
    },
    "showSystemApps": false
  }
}
```

有 root 时，`killSwitch: true` 会加上 iptables 规则：隧道存在期间只放行连接的
地址。`killSwitchDns` 放行配置里的 DNS 服务器 —— 否则解析器随网络一起失效，
按域名重连时无法解析。

### 示例 5. 路线与坐标抖动

```json
{
  "name": "按城市路由",
  "gps": {
    "enabled": true,
    "mode": "route",
    "route": "55.7558,37.6173,55.7601,37.6288,55.7710,37.6310",
    "routeLoop": true,
    "routeDwellSeconds": 5,
    "speedMps": 8.5,
    "positionJitterMeters": 12,
    "driftMetersPerSecond": 0.4,
    "accuracyMeters": 6.0,
    "smoothingSamples": 3,
    "mockElapsedSeconds": 2,
    "perApp": true,
    "apps": "com.yandex.taxi"
  }
}
```

`route` 是逗号分隔的坐标，纬度/经度成对。

### 示例 6. 应用导出格式的完整预设

字段齐全，就像 `encodeDefaults = true` 导出的结果。不必通读 —— 这只是展示文件
填满时长什么样。

```json
{
  "id": "preset-1728000000000",
  "name": "综合方案",
  "description": "DPI 解绕加定位",
  "createdAtMs": 1728000000000,
  "region": "ru",
  "zapret": {
    "enabled": true,
    "binaryPath": "",
    "autoStrategy": true,
    "strategy": "auto",
    "mode": "bypass",
    "verifyStrategy": true,
    "autoRestart": true,
    "domains": "youtube.com",
    "apps": "",
    "allTraffic": false,
    "exclusions": "",
    "ipv4Only": false,
    "filterTcp": "80,443",
    "filterUdp": "",
    "dpiPorts": "443",
    "filterIp": "",
    "newSyntax": true,
    "bgIncrement": false,
    "hostCase": true,
    "hostSpell": true,
    "hostNoSpace": false,
    "methodSpace": true,
    "methodEol": false,
    "unixEol": false,
    "tlsRec": false,
    "wsSize": null,
    "hostHash": false,
    "desyncMethods": ["fake"],
    "dpiSplit": "",
    "dpiSplitPos": "",
    "dpiSplitSeqovl": null,
    "dpiSplit2Pos": "",
    "dpiSplit2Seqovl": null,
    "fakeTlsMode": "",
    "fakeTlsHost": "",
    "fakeTlsPadLen": null,
    "fakeTlsCutLen": null,
    "fakePacket": false,
    "fakeSkip": false,
    "fakeTcp": false,
    "fakeDport": null,
    "fakeDportTtl": null,
    "fakeSport": null,
    "fakeSrcAddr": "",
    "fakeSeq": null,
    "fakeCsum": false,
    "foolingTs": false,
    "fakeTcpFlags": null,
    "fakeMss": null,
    "fakeRepeats": null,
    "fakeCut": false,
    "fakeCutTls": false,
    "fakeSniff": false,
    "fakeTlsMod": "",
    "autoTtl": "",
    "udpLenIncrement": null,
    "anyProtocol": false,
    "ttl": null,
    "ttlEnd": null,
    "ttl6": false,
    "skipTtl": false,
    "wsize": null,
    "mss": null,
    "multiply": null,
    "multipath": false,
    "ipdiag": false,
    "comment": "",
    "logging": false
  },
  "goodbyeDpi": {
    "mode": "auto",
    "splitPos": "2",
    "fakeSeq": null,
    "fakeCsum": false,
    "fakeSni": "",
    "fakeGen": null,
    "fakeResend": null,
    "autoTtl": "",
    "setTtl": null,
    "maxPayload": null,
    "reverseFrag": false,
    "fragBySni": false,
    "hostCheck": true,
    "domains": "",
    "daemonPath": "",
    "autoRestart": true,
    "autoStart": false,
    "logging": false
  },
  "vpn": {
    "enabled": false,
    "autoStart": false,
    "activeProfileId": "",
    "profiles": [],
    "routing": {
      "routesAllTraffic": true,
      "excludePrivateRanges": true,
      "excludeMulticast": true
    },
    "killSwitch": false,
    "killSwitchDns": false,
    "ipv6": false,
    "dnsServers": "",
    "splitTunneling": false,
    "dnsFakeIp": false,
    "dnsViaProxy": false,
    "dnsBackup": "",
    "dnsType": "udp",
    "dnsBootstrap": "",
    "dnsStrategy": "off",
    "dnsRewriteTtl": 60,
    "clashApiEnabled": true,
    "clashApiPort": 9090,
    "clashApiSecret": "",
    "sources": [],
    "torEnabled": true,
    "torSocksPort": 9050,
    "groupMode": false,
    "urlTest": {
      "mode": "disabled",
      "includeNames": "",
      "includeRegex": "",
      "includeCountries": "",
      "excludeNames": "",
      "excludeRegex": "",
      "excludeCountries": "",
      "testingUrl": "",
      "interval": "1m",
      "toleranceMs": 10,
      "idleTimeout": ""
    }
  },
  "gps": {
    "enabled": false,
    "mode": "fixed",
    "latitude": 0.0,
    "longitude": 0.0,
    "altitudeMeters": 0.0,
    "speedMps": 0.0,
    "speedJitterPercent": 0,
    "positionJitterMeters": 0.0,
    "driftMetersPerSecond": 0.0,
    "route": "",
    "routeLoop": false,
    "routeDwellSeconds": 0,
    "accuracyMeters": 5.0,
    "smoothingSamples": 1,
    "mockElapsedSeconds": 2,
    "reinstallProvider": true,
    "perApp": false,
    "apps": ""
  },
  "perApp": {
    "vpn":     { "enabled": false, "mode": "include", "packages": [] },
    "tor":     { "enabled": false, "mode": "include", "packages": [] },
    "zapret":  { "enabled": false, "mode": "include", "packages": [] },
    "goodbyeDpi": { "enabled": false, "mode": "include", "packages": [] },
    "showSystemApps": false
  }
}
```

---

## 11. 常见错误

| 现象 | 原因 |
|---|---|
| 构建卡在 `packageRootRelease`，没有任何输出 | `gradle.properties` 里去掉了 `parallel=false` 或 `workers.max=1` |
| 拆分包缺库 | 构建被打断过，打包任务被判为 `UP-TO-DATE`；删掉 `app/build/outputs/apk` 重新构建 |
| 测试只返回 `UP-TO-DATE`，没有计数 | 少了 `--rerun-tasks` |
| `keytool` 说「Not a signed jar file」 | 这是正常的，用 `apksigner verify` 检查 |
| 桌面构建时找不到 `objcopy` | 没把 binutils 加进 `PATH` |
| 主用户的 Android Studio 打不开项目 | 以 root 构建后目录归 root 所有：`chown -R <пользователь>:users .` |
| AGP 拉来 Kotlin 2.2.x，`--offline` 失败 | 根 `build.gradle.kts` 里没有把所有插件都写成 `apply false` |
| 清单 merger 报 namespace 错误 | `:app` 和 `:composeApp` 的 namespace 撞了 |
| `projects.composeApp` 解析不了 | Gradle 9.x 要写 `project(":composeApp")` |

</details>
