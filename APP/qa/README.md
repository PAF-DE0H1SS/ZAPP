# ZAPP QA

Two layers guard a release:

* **Automated** -- `composeApp/src/desktopTest`. Unit tests cover the pure
  logic; `ui/e2e/CriticalPathsUiTest` runs the real Compose pages headless
  (VPN connect path, zapret start refusal, GPS page, navigation). Run with:

  ```bash
  ./gradlew :composeApp:desktopTest --rerun-tasks
  ```

  The UI layer needs Skia, which loads `libGL.so.1`; on NixOS point
  `LD_LIBRARY_PATH` at `libglvnd` before running.

* **Manual** -- `TEST_CASES.md`. Device-side scenarios (both flavors, real
  networks, GPS routes) that automation cannot reach. Record each run in
  the local `TESTLOG.md`: build, device, network, verdict.

Suites mirror the gpsspoof layout (`smoke` / `sanity` / `e2e`), so a
regression hunt starts the same way in both projects.
