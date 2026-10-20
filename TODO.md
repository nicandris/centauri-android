# TODO

What's not done yet, and why. Command numbers are SDCP `Cmd` ids (see `Protocol.kt`).

## Printer features not built

- [ ] **Timelapses**: list them per History entry, download, open in a video player.
  History entries carry `TimeLapseVideoStatus` / `TimeLapseVideoUrl`; Cmd 323 fetches the video.
  Playing in-app would need a player library; handing off to the system player doesn't.
- [ ] **Upload gcode** from the phone, also as a share target (send from a slicer app).
  The printer's web UI uploads over HTTP; needs a progress UI.
- [ ] **Jog axes**: move X/Y/Z by a step (Cmd 401 `{Axis, Step}`). Needs care: only when idle,
  sensible limits, confirmation for Z.
- [ ] **Power-loss recovery**: resume after a blackout (Cmd 134 status / 135 action). Can't be
  tested without cutting power mid-print.
- [ ] **Canvas (multi-filament)**: show tray status (Cmd 324) and map file colours to trays when
  starting a print (Cmd 500–503, `slot_map`). Start print is currently blocked when a Canvas is
  connected. Needs a Canvas to test.
- [ ] **Skip preheating / stop filament feed** (Cmd 133 / 132): payload unknown, not used by the
  printer's web UI.
- [ ] **Centauri Carbon 2**: speaks MQTT instead of the WebSocket; would be a second
  `PrinterLink` implementation.

## Tried and dropped

- **Rename file** (Cmd 257): the printer answers `Ack 0` but the file keeps its name (tested
  mid-print on firmware V1.4.49). Worth retrying while idle.
- **Timelapse on/off during a print** (Cmd 387): rejected with `Ack 1` (tested mid-print).

## Not verified on hardware

- Delete files (Cmd 259) and delete history entries (Cmd 322): payloads follow the printer's web
  UI and work in demo mode, but weren't run against the real printer (irreversible).
- Speed, fans, temperature targets, homing and start print from the app: built from the web UI
  and HA integration payloads; not exercised end to end on a real print yet.
- Android 17 local-network permission flow: only reachable on an Android 17 device.

## Release and distribution

- [ ] Make the GitHub repository public.
- [ ] **IzzyOnDroid** first (publishes the signed GitHub APKs): request on their Codeberg tracker.
- [ ] **F-Droid**: fastlane metadata is done (en, nl, el). Left: the fdroiddata merge request,
  and possibly reproducible builds with the release key (`Binaries:` + `AllowedAPKSigningKeys:`)
  so F-Droid ships our signed APK. Check that F-Droid's build server supports AGP 9.4 /
  Gradle 9.8 / compileSdk 37.
- [ ] Keep an offline backup of the release keystore (losing it forces every user to reinstall).

## Engineering

- [ ] TalkBack run-through on a real device. The code pass is done (labels, merged rows, spoken
  row actions); listening to it end to end isn't.
- [ ] Release screenshots in light mode, and of the tablet layout. Needs demo mode on the
  screenshot phone, which means forgetting the saved printer there.
- [ ] More languages: English, Dutch and Greek so far.

Decided against: Compose UI and screenshot tests. Unit tests cover all non-UI code at 100% of
lines (`./gradlew createDebugUnitTestCoverageReport`).
