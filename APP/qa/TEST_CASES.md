# ZAPP Manual Test Cases

Manual scenarios for the paths that automation does not cover: real devices,
real networks, real tunnels. Automated counterparts live in
`composeApp/src/desktopTest` (unit + Compose UI tests); this document is the
device-side checklist that gates a release.

Mark each run in the developer's test log with the build version,
device, network and result.

Conventions:

* **Suites** mirror gpsspoof: `smoke` (app opens, core screen works),
  `sanity` (feature toggles round-trip), `e2e` (full user journey).
* Every case states pre-conditions, steps and the expected result. If a step
  cannot be executed on the given flavor (root/noroot), the case is marked
  **N/A** -- never "passed".

## Smoke

**TC001 · App launch**
* **Flavors:** root, noroot.
* **Pre-conditions:** clean install, no profile imported.
* **Steps:**
  1. Launch ZAPP.
  2. Complete onboarding (or skip it).
* **Expected:** main screen renders, bottom navigation shows all five tabs,
  no crash, no root mention anywhere in noroot build.

**TC002 · VPN page renders**
* **Steps:**
  1. Open the VPN tab.
* **Expected:** tunnel status card with the orb button, empty profile picker
  with a hint on how to add a connection.

## Sanity

**TC010 · Connect / disconnect round-trip**
* **Pre-conditions:** at least one imported profile.
* **Steps:**
  1. Tap the orb to connect.
  2. Wait for CONNECTED (or a visible error with a reason line).
  3. Tap the orb to disconnect.
* **Expected:** status transitions DISCONNECTED -> CONNECTING -> CONNECTED;
  the orb label flips Start/Stop; disconnect leaves no background service;
  traffic counters start only while connected.

**TC011 · Preset import**
* **Steps:**
  1. Import a Clash YAML subscription from URL.
  2. Check the parsed list of nodes.
  3. Export the preset back and compare.
* **Expected:** nodes parsed with protocols and addresses; rule-providers
  converted to remote rule_sets with a note; export does not leak unrelated
  credentials.

**TC012 · Theme switch**
* **Steps:**
  1. Settings -> Theme: follow system / dark / light.
* **Expected:** whole app re-themes without restart; dynamic colors (Material
  You) apply on supported Android devices and the toggle is honest elsewhere.

## End-to-end

**TC020 · DPI bypass without root**
* **Flavors:** noroot.
* **Pre-conditions:** byedpi relay configured, target site blocked by DPI.
* **Steps:**
  1. Connect via the DPI-bypass profile.
  2. Open the blocked site in a browser.
  3. Stop the session.
* **Expected:** site loads while connected; log shows the relay session, not a
  TUN tunnel; stopping frees the port (a restart must not fail with
  "bind: address already in use").

**TC021 · Route GPS tick**
* **Pre-conditions:** GPS tab, a start point and a destination set.
* **Steps:**
  1. Start the route (Directions mode).
  2. Watch position updates for one full segment.
  3. Change speed mid-route.
  4. Stop spoofing.
* **Expected:** mock position advances along the OSRM polyline at the chosen
  speed; ETA updates; joystick mode overrides the route; stop leaves the
  system mock provider disabled.

**TC022 · Node duel A-vs-B**
* **Pre-conditions:** two profiles imported.
* **Steps:**
  1. Start a duel between two nodes.
  2. Wait for the verdict.
* **Expected:** interleaved probes A,B,A,B; winner highlighted; both-dead case
  shows the tunnel hint instead of a fake winner.

**TC023 · Update flow**
* **Pre-conditions:** a newer release exists on the feed.
* **Steps:**
  1. Check for updates.
  2. Download and install.
  3. Roll back deliberately (install an older build over it).
* **Expected:** changelog shown before download; progress bar; anti-rollback
  warns; desktop core update card offers a new sing-box binary and stores it
  next to the app data.

**TC024 · Backup and restore**
* **Steps:**
  1. Backup to a zip with preview.
  2. Wipe app data, install fresh.
  3. Restore from the zip.
* **Expected:** profiles, presets and settings restored; preview lists what
  will be restored before writing.

## Regression watchlist

Carry-over defects that once shipped broken -- re-check on every release
candidate:

* Traffic counters must be non-zero after real traffic (clash_api
  `/connections` source, netd fallback on noroot).
* Tunnel watchdog drops only a dead tunnel, never a healthy one.
* The noroot build contains no root wording anywhere (automated guard exists;
  this case covers free-form strings like help texts).
* Error line shows the real cause on any tab, not only on the VPN session tab.
