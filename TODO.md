# TODO

What's not done yet, and why. Command numbers are SDCP `Cmd` ids (see `Protocol.kt`).

## Printer features not built

- [ ] **Upload gcode** from the phone, also as a share target (send from a slicer app).
  The printer's web UI uploads over HTTP; needs a progress UI.

## Tried and dropped

- **Centauri Carbon 2**: speaks MQTT instead of the WebSocket. Won't do (2026-10-21).

- **Canvas, multi-colour mapping** (to rethink, 2026-10-21): the CC1 doesn't report a file's
  colours over LAN. Elegoo's own client gets them from its cloud, slicers (EdgeSlicer) know them
  from slicing, and elegoo-homeassistant maps T0 only, like this app. The one LAN route found is
  reading the gcode tail (Orca's config block: `filament_colour`, `filament used [g]`) over
  `http://<printer>/downloadFile/<path>`; it's untested whether the printer honours Range
  requests, so it may mean downloading whole files. Cmd 500–503 are CC2-era: Elegoo's CC1
  client doesn't use them.

- **Power-loss recovery**: the CC1 has no SDCP state or command for it. Elegoo's own client
  (elegoo-link's CC1 adapter) stops at machine status 11 and maps no recovery command; only its
  CC2 adapter has one (machine status 15). Cmd 134 is a documented no-op, 135 is undocumented.
  Resume or discard on the touchscreen.

- **Rename file** (Cmd 257): the printer answers `Ack 0` but the file keeps its name (tested
  mid-print on firmware V1.4.49). Worth retrying while idle.
- **Timelapse on/off during a print** (Cmd 387): rejected with `Ack 1` (tested mid-print).

## Not verified on hardware

- Canvas: tray list and start print with a chosen tray follow Elegoo's elegoo-link source
  (`handleCanvasStatus`, `slot_map` = `[{t, canvas_id, tray_id}]`); no Canvas here to run them on.

- Delete files (Cmd 259) and delete history entries (Cmd 322): payloads follow the printer's web
  UI and work in demo mode, but weren't run against the real printer (irreversible).
- Jog (Cmd 401 `{Axis, Step}`, from elegoo-link, where it's switched off for the CC1), skip
  preheating (133) and stop filament feed (132, both `{}` per OpenCentauri): built, not run on
  the printer.
- Speed, fans, temperature targets, homing and start print from the app: built from the web UI
  and HA integration payloads; not exercised end to end on a real print yet.
- Android 17 local-network permission flow: only reachable on an Android 17 device.
- Labs added on `code-review-fixes`: USB drive in Files (Url `/usb`, from the SDCP v3 spec) and
  timelapses in History (`TimeLapseVideoStatus` 1 + `TimeLapseVideoUrl`, opened in the system player).
- Android 16 Live Update: promotion, status-bar chip and the Preheat/Level/Print segment order;
  Stop from the shade (unlock, then the in-app confirm).
- "Find printer again" after a real DHCP change; LAN traffic on Wi-Fi with mobile data on.
- Firmware logo at `/assets/images/network/logo.png`; a touchscreen stop shows as "Stopped"
  only if it records no error reason.
- Widgets, the Quick Settings tile and launcher shortcuts on real launchers.

## Release and distribution

Paused (2026-10-21).

- [ ] **IzzyOnDroid** first (publishes the signed GitHub APKs): request on their Codeberg tracker.
- [ ] **F-Droid**: fastlane metadata is done (en, nl, el). Left: the fdroiddata merge request,
  and possibly reproducible builds with the release key (`Binaries:` + `AllowedAPKSigningKeys:`)
  so F-Droid ships our signed APK. Check that F-Droid's build server supports AGP 9.4 /
  Gradle 9.8 / compileSdk 37.
- Release keystore: offline backup done (2026-10-21).

## Engineering

- Labs (gear → Labs): untested features (skip preheat / stop feed, Canvas tray choice, USB drive,
  timelapses) are off until switched on there. When one is confirmed on a printer, remove its `Lab` entry and the gate.

- TalkBack: the code pass is done (labels, merged rows, spoken row actions); no device
  run-through planned (2026-10-21).
- Screenshots: `./gradlew installDebug -Pshots` installs `app.centauri.remote.shots` beside the
  real app, so demo mode doesn't touch its printer. Use a test phone, not a daily one.
- [ ] More languages: Spanish, German, Chinese and Japanese (English, Dutch and Greek so far).
  Last, once everything else here is done.

Decided against: Compose UI and screenshot tests. Unit tests cover all non-UI code at 100% of
lines (`./gradlew createDebugUnitTestCoverageReport`).
