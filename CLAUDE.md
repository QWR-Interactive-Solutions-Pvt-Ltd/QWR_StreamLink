# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

QWR_StreamLink is the **viewer side** of the QWR VR screen-streaming stack. Single Android module:

- **app_stream_viewer** — Phone/tablet viewer app (`com.qwr.streamviewer`). Discovers headsets via UDP, shows a device list with live preview thumbnails, renders the live JPEG stream full-screen, and sends quality / FPS commands back to the headset.

> The headset controller app and streaming daemon (formerly `app_gogle` and `daemon_gogle` in this repo) now live in a separate repository: **[QWR_Headset_Streamer](https://github.com/QWR-Interactive-Solutions-Pvt-Ltd/QWR_Headset_Streamer)**. If a task asks about the daemon, system app, SurfaceControl capture, cgroup escape, or platform signing — that's the other repo.

## Build Commands

```bash
# Debug build (auto-signed with debug keystore)
./gradlew :app_stream_viewer:assembleDebug

# Release build (produces unsigned APK — sign externally)
./gradlew :app_stream_viewer:assembleRelease

# Tests
./gradlew test                          # All unit tests
./gradlew connectedAndroidTest          # Instrumented tests (requires device)

# Version management
./gradlew incrementAllVersions -Ptype=PATCH    # MAJOR, MINOR, or PATCH
./gradlew incrementApp_stream_viewerVersion -Ptype=MINOR

# Install via ADB
adb install -r app_stream_viewer/build/outputs/apk/debug/app_stream_viewer-debug.apk
```

## Build Configuration

- **AGP**: 8.11.1, **Kotlin**: 2.2.0, **Java**: 21, **NDK**: 27.0.12099909
- **SDK**: compileSdk 35 (viewer module uses 36), minSdk 26, targetSdk 35
- Versions are centralized in the top-level `build.gradle` `ext.versions` block
- No platform signing required — viewer is a normal Android app

## Architecture

### Language and Patterns
- **Java only** — no Kotlin source files
- **Activity + Service pattern** — not MVVM/MVI; uses traditional Android services for background work
- **Threading**: Handler/Looper for main thread, explicit `Thread` for background work

### Streaming Protocol (consumer side)

The viewer is a pure consumer — it never broadcasts and never originates a stream. All connections are viewer → headset.

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 8505 | UDP | listen | Discovery — listens for `QWR_VR\|IP\|PORT\|deviceName\|deviceSerial` broadcast from the headset's Unity partner app |
| 6776 | TCP | viewer → headset | JPEG frame stream. Wire format: `[4-byte big-endian int: length][JPEG bytes]` |
| 6777 | TCP | viewer → headset | Measured FPS report (back-channel for the headset's monitoring) |
| 6779 | TCP | viewer → headset | Control commands — `quality=N` (1–100), `delay=N` (frame interval ms, screenshot mode only), `mode` (returns `VD` or `SS`), `stop` |

**Discovery prefix is `QWR_VR`**, not `QWR_STREAMLINK`. The Unity partner app on the headset owns discovery; the streaming daemon does not broadcast (its UDP broadcast is intentionally disabled in the headset repo). If you change the prefix here, the viewer will stop seeing any headsets.

### Viewer Flow
`DeviceListActivity` (discovers devices via `AddressDiscoveryService`, grid layout with optional live preview thumbnails) → tap a card → `StreamActivity` (full-screen landscape stream) backed by `DownstreamService` (TCP connection to port 6776, JPEG frame receiving, FPS monitoring, auto-reconnect). Viewer sends quality commands (15/30/50/80) and delay commands to the headset control port 6779.

### Discovery Flow
1. Open UDP socket on 8505, hold a `MulticastLock`, listen for 4 seconds
2. Parse incoming packets — must start with `QWR_VR` prefix, pipe-delimited, at least 4 parts (`prefix|ip|port|deviceName[|serial]`)
3. Dedup by IP; report each new headset immediately to the UI
4. Every 5 seconds, TCP-probe each listed headset on port 6776 to update its reachability dot
5. While a stream is active, skip the TCP probe for that headset (daemon only accepts one connection at a time) — infer status from service state

### Adding a New Headset Icon
Map device model name prefix to drawable in `HeadsetIconConfig` inside `DeviceListAdapter.java` (case-insensitive prefix matching, first match wins). Current entries: `SXR`, `VRone_Edu`, default fallback.
