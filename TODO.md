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
- [ ] **Canvas, multi-colour mapping**: trays (Cmd 324) and a single tray for T0 (`slot_map`) are
  built; mapping every tool of a multi-colour file isn't, because the CC1 doesn't report a file's
  colours over LAN (Elegoo's own client gets them from its cloud). Cmd 500–503 are CC2-era AMS
  commands: Elegoo's CC1 client doesn't use them.
- [ ] **Skip preheating / stop filament feed** (Cmd 133 / 132): payload unknown, not used by the
  printer's web UI.
- [ ] **Centauri Carbon 2**: speaks MQTT instead of the WebSocket; would be a second
  `PrinterLink` implementation.

## Tried and dropped

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
- Speed, fans, temperature targets, homing and start print from the app: built from the web UI
  and HA integration payloads; not exercised end to end on a real print yet.
- Android 17 local-network permission flow: only reachable on an Android 17 device.

## Release and distribution

Paused (2026-10-21).

- [ ] **IzzyOnDroid** first (publishes the signed GitHub APKs): request on their Codeberg tracker.
- [ ] **F-Droid**: fastlane metadata is done (en, nl, el). Left: the fdroiddata merge request,
  and possibly reproducible builds with the release key (`Binaries:` + `AllowedAPKSigningKeys:`)
  so F-Droid ships our signed APK. Check that F-Droid's build server supports AGP 9.4 /
  Gradle 9.8 / compileSdk 37.
- [ ] Keep an offline backup of the release keystore (losing it forces every user to reinstall).

## Engineering

- [ ] TalkBack run-through on a real device. The code pass is done (labels, merged rows, spoken
  row actions); listening to it end to end isn't.
- [ ] Release screenshots in light mode, and of the tablet layout. `./gradlew installDebug -Pshots`
  installs `app.centauri.remote.shots` beside the real app, so demo mode doesn't touch its printer.
- [ ] More languages: English, Dutch and Greek so far.

Decided against: Compose UI and screenshot tests. Unit tests cover all non-UI code at 100% of
lines (`./gradlew createDebugUnitTestCoverageReport`).
