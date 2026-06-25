# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

QWR_StreamLink is a multi-module Android project for real-time screen streaming from VR headsets to an Android viewer app over WiFi. Three modules:

- **app_gogle** — Headset controller app (`com.qwr.gogle`). System app (UID 1000) that manages a standalone daemon for screen capture. Shows device info, streaming status, and Start/Stop toggle. Also accepts intent-based start/stop from external apps (Unity/Unreal).
- **app_stream_viewer** — Viewer app (`com.qwr.streamviewer`). Discovers headsets via UDP, shows device list with live previews, renders live JPEG stream full-screen.
- **daemon_gogle** — Standalone daemon JAR (`com.qwr.daemon`). Runs via `app_process` as an independent process, captures screen using SurfaceControl hidden API, serves JPEG stream over TCP.

## Build Commands

```bash
# Build headset app (debug)
./gradlew :app_gogle:assembleDebug

# Build headset app (release with ProGuard)
./gradlew :app_gogle:assembleRelease

# Build headset app (platform-signed — requires PLATFORM_* in gradle.properties)
./gradlew :app_gogle:assembleRelease_system

# Build viewer app
./gradlew :app_stream_viewer:assembleDebug

# Build both release
./gradlew :app_gogle:assembleRelease :app_stream_viewer:assembleRelease

# Build daemon JAR only
./gradlew :daemon_gogle:buildDaemonDex

# Run tests
./gradlew test                          # All unit tests
./gradlew connectedAndroidTest          # Instrumented tests (requires device)

# Version management
./gradlew incrementAllVersions -Ptype=PATCH    # MAJOR, MINOR, or PATCH
./gradlew incrementApp_gogleVersion -Ptype=MINOR

# Install via ADB
adb install -r app_gogle/build/outputs/apk/debug_develop/QWR-StreamLink-gogle_debug_develop_*.apk
adb install -r app_stream_viewer/build/outputs/apk/debug/app_stream_viewer-debug.apk
```

## Build Configuration

- **AGP**: 8.11.1, **Kotlin**: 2.2.0, **Java**: 21, **NDK**: 27.0.12099909
- **SDK**: compileSdk 35, minSdk 26, targetSdk 35
- Versions are centralized in the top-level `build.gradle` `ext.versions` block
- The default `debug` and `release` build types are filtered out in app_gogle; use named variants instead
- APK output naming: `QWR-StreamLink-gogle_{buildType}_{versionName}.apk`
- The `debug_system` / `release_system` variants require platform signing keys configured in `gradle.properties`
- The daemon JAR is automatically built and bundled into app_gogle's assets via `copyDaemonJar` task

### app_gogle Build Variants

| Variant | Debuggable | ProGuard | Signing | Notes |
|---------|------------|----------|---------|-------|
| `debug` | yes | no | develop | Day-to-day development |
| `debug_system` | yes | no | platform | Debug on real headsets (system app) |
| `release` | no | yes | develop | Release build, sign externally |
| `release_system` | no | yes | platform | Release for real headsets (system app) |

## Architecture

### Language and Patterns
- **Java only** — no Kotlin source files
- **Activity + Service pattern** — not MVVM/MVI; uses traditional Android services for background work
- **Threading**: Handler/Looper for main thread, explicit `Thread` for background work
- **System app**: `sharedUserId="android.uid.system"` runs as UID 1000
- **Daemon process**: Standalone `app_process` that captures screen via SurfaceControl and survives app force-stop via cgroup escape

### Streaming Protocol
- **TCP port 6776**: JPEG frame stream. Wire format: `[4-byte big-endian int: length][JPEG bytes]`
- **UDP port 8505**: Discovery broadcast is **disabled** (commented out in `daemon_gogle/StreamServer.java`). Discovery is delegated to the partner VR app that integrates this stack. Historical payload was `QWR_STREAMLINK|IP|PORT|deviceName|deviceSerial` every 3s — kept in code for easy re-enable.
- **TCP port 8505**: Discovery listener still active (idle) — responds with `[4-byte big-endian int: length][device name bytes]` if a client connects
- **TCP port 6779**: Control port. Text commands: `stop` (shutdown), `quality=N` (JPEG quality 1-100), `delay=N` (frame interval ms, screenshot mode only), `mode` (returns `VD` or `SS`)

### Screen Capture (daemon_gogle)
- Uses **SurfaceControl** hidden API (same technique as scrcpy) — no MediaProjection, no consent dialog
- Pipeline: SurfaceControl.createDisplay() → VirtualDisplay (mirrors main display) → ImageReader (RGBA_8888) → Bitmap → JPEG → frame queue (max 3)
- Captures at 30% of display size, JPEG quality 30 (adjustable via control port)
- sourceRect must be full display size, destRect is capture size — do NOT make sourceRect equal to destRect
- **Hybrid capture**: Starts in virtual display mode (event-driven). After 4 seconds, if fewer than 8 frames captured (~2 FPS), auto-switches to screenshot polling mode. This handles headsets where VR compositor bypasses SurfaceFlinger (e.g. YVR with ATW overlay)
- Achieves ~60 FPS on WYWK firmware headsets

### Daemon Lifecycle
- **Start**: `ScreenCaptureService` checks if daemon JAR needs installing or updating (SHA-256 hash comparison of bundled vs installed JAR). If a stale daemon is running with an outdated JAR, it stops it first, waits 1.5s, then launches the new one via `setsid app_process`. Moves daemon PID to root cgroup (`/sys/fs/cgroup/cgroup.procs`) so it survives `forceStopPackage`
- **Survive**: VR launcher (`XRVDManager`) calls `killApplication`/`forceStopPackage` when dismissing app's VR panel. Daemon survives because it's in the root cgroup, not the app's cgroup
- **Auto-relaunch**: SharedPreference `daemon_should_run` tracks user intent. On app restart (or boot), daemon is relaunched if the flag is set
- **Stop**: Explicit user action sends `stop` command to control port 6779. Also clears `daemon_should_run`

### Intent-Based Control
External apps (Unity/Unreal) can start/stop streaming via broadcasts:
- `com.qwr.gogle.START_STREAM` — starts daemon (app process auto-created if needed)
- `com.qwr.gogle.STOP_STREAM` — stops daemon

### app_gogle Flow
`SplashScreenActivity` — single-screen controller UI. Shows device info (model, serial, IP) and streaming status (red/green dot). Tap Start → `ScreenCaptureService` starts → daemon launches. Tap Stop → daemon stops. Tap Quit → `finishAffinity()` (daemon keeps running). Status polls every 2s via `DaemonController.isDaemonRunning()`.

### app_stream_viewer Flow
`DeviceListActivity` (discovers devices via `AddressDiscoveryService`, grid layout with optional live preview thumbnails) → `StreamActivity` (full-screen landscape stream) backed by `DownstreamService` (TCP connection to port 6776, JPEG frame receiving, FPS monitoring, auto-reconnect). Viewer sends quality commands (15/30/50/80) to headset control port 6779.

### Adding a New Headset Icon
Map device model name prefix to drawable in `HeadsetIconConfig.java` (case-insensitive prefix matching, first match wins). Current: `SXR`, `VRone_Edu`, default fallback.

### SSNWT VR Headset Constraints
- VR launcher (`XRVDManager`) calls `forceStopPackage()` when any app's VR panel is dismissed — hardcoded, no workaround
- Daemon must escape app's cgroup to survive force-stop
- SurfaceControl capture gets ~60 FPS on WYWK firmware, ~2 FPS on QWR firmware (firmware-level throttling)

### Port Constants
| Port | Protocol | Purpose |
|------|----------|---------|
| 6776 | TCP | JPEG frame stream |
| 6777 | TCP | FPS data (viewer side) |
| 6779 | TCP | Control port |
| 8505 | TCP | Discovery listener (UDP broadcast disabled) |
