# QWR StreamLink

Android viewer app for receiving real-time screen streams from QWR VR headsets over WiFi.

> **Looking for the headset side?** The headset controller app and streaming daemon live in a separate repo: **[QWR_Headset_Streamer](https://github.com/QWR-Interactive-Solutions-Pvt-Ltd/QWR_Headset_Streamer)**. This repo only contains the viewer.

## What this is paired with

This viewer is built specifically for the **QWRVisionCare-VR** Unity app running on the headset. The pairing matters because:

- **Discovery is owned by QWRVisionCare-VR**, not by the streaming daemon. The viewer only sees a headset when QWRVisionCare-VR is *running* on it — QWRVisionCare-VR is what broadcasts the `QWR_VR|…` UDP packet the viewer listens for.
- **Streaming starts automatically when a therapy session begins** in QWRVisionCare-VR. The viewer doesn't start the stream; it just connects to whatever the headset is already streaming.
- **If QWRVisionCare-VR isn't installed/running, the headset will not appear in the device list** — even if the streaming daemon is alive.

If you want to use this viewer with a headset that isn't running QWRVisionCare-VR, you need to re-enable the streaming daemon's own UDP discovery broadcast. It's intentionally disabled in [QWR_Headset_Streamer](https://github.com/QWR-Interactive-Solutions-Pvt-Ltd/QWR_Headset_Streamer) to avoid colliding with the Unity broadcast — see `daemon_gogle/src/main/java/com/qwr/daemon/StreamServer.java` (the `udpBroadcastLoop` thread start is commented out around line 60). Uncomment it, rebuild the daemon, and the viewer will find the headset without QWRVisionCare-VR being involved.

## Prerequisites

- `adb` installed on your PC
- Android phone or tablet running Android 8.0 (API 26) or later
- Phone/tablet on the same WiFi network as the headset
- Headset running **QWRVisionCare-VR** (or, alternatively, a daemon build with UDP broadcast re-enabled — see above)

## Installation

Grab the latest viewer APK from the [Releases page](https://github.com/QWR-Interactive-Solutions-Pvt-Ltd/QWR_StreamLink/releases), then:

```bash
adb install -r app_stream_viewer-*.apk
```

The viewer is a normal Android app — no platform signing required.

## Usage

1. Make sure the headset app is running and streaming (see [QWR_Headset_Streamer](https://github.com/QWR-Interactive-Solutions-Pvt-Ltd/QWR_Headset_Streamer))
2. Open the viewer app — discovered headsets appear in the device list
3. Tap a headset card to start viewing its live screen
4. Tap the screen to toggle quality and FPS controls
5. Back out to return to the device list

### Troubleshooting

| Problem | Solution |
|---------|----------|
| Headset not in viewer list | Confirm QWRVisionCare-VR is running on the headset (it owns discovery); ensure same WiFi network; tap refresh |
| Viewer connected to WiFi but still can't reach headset | Turn off mobile data — Android may route traffic over cellular when WiFi has no internet |
| Low FPS or lag | Lower quality in the in-stream control bar; check WiFi signal strength |

## Building from Source

```bash
./gradlew :app_stream_viewer:assembleDebug
```

Output: `app_stream_viewer/build/outputs/apk/debug/app_stream_viewer-debug.apk`

For a release build:

```bash
./gradlew :app_stream_viewer:assembleRelease
```

The release variant has no `signingConfig`, so it produces an unsigned APK that you'll need to sign with your own keystore before installation.

## Protocol

The viewer is a **consumer** of the stream — it never broadcasts and never originates a stream. All traffic is initiated by the viewer toward the headset.

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 8505 | UDP | listen | Discovery — listens for `QWR_VR\|IP\|PORT\|deviceName\|deviceSerial` broadcast from the headset's Unity partner app |
| 6776 | TCP | viewer → headset | JPEG frame stream. Wire format: `[4-byte big-endian int: length][JPEG bytes]` |
| 6777 | TCP | viewer → headset | Measured FPS report |
| 6779 | TCP | viewer → headset | Control commands — `quality=N` (1–100), `delay=N` (frame interval ms, screenshot mode only), `mode` (returns `VD` or `SS`) |

## Adding a Headset Icon

The device list shows an icon per discovered headset, matched by device model name prefix (case-insensitive, first match wins). To add one:

1. Get the model name: `adb shell getprop ro.product.model` (e.g. `SXR_1`, `VRone_Edu`)
2. Add an icon drawable to `app_stream_viewer/src/main/res/drawable/`
3. Register the prefix → drawable mapping in `HeadsetIconConfig` inside `DeviceListAdapter.java`, above the default fallback
4. Rebuild and reinstall

See `app_stream_viewer/README.md` for more on the discovery, reachability, and streaming flow.

## Support

- Issues: use your normal project tracker or repository issues.
- For integration questions, contact your QWR point of contact.
- Email: devs@questionwhatsreal.com
