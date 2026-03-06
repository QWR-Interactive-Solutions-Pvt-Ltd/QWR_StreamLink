# app_stream_viewer

Android viewer app for QWR StreamLink. Discovers headsets on the local WiFi network and displays their live screen stream.

## How it works

### 1. Device discovery
On launch (and on each refresh), the app opens a UDP socket on port **8505** and listens continuously for **4 seconds**. The headset daemon broadcasts a packet every 3 seconds in the format:

```
QWR_STREAMLINK|IP|PORT|deviceName|deviceSerial
```

Devices are reported to the UI as they arrive — each discovered headset appears as a card in the device list immediately, without waiting for the full window to elapse. If multiple headsets are broadcasting, all of them are found within the same 4-second window. A `MulticastLock` is held during the scan to ensure broadcast packets are delivered on WiFi.

### 2. Reachability polling
Every 5 seconds the app attempts a TCP connection to each listed headset on port **6776** to check if it is reachable. The status dot on the card updates accordingly:
- **Green** — reachable (or actively streaming)
- **Red** — unreachable

> When a stream is actively running, the TCP probe is skipped for that headset (the daemon only accepts one connection at a time). The status is inferred directly from the service state instead.

### 3. Streaming
Tapping a device card saves its IP and opens `StreamActivity`, which starts `DownstreamService` in the foreground. The service:
1. Opens a TCP connection to port **6776**
2. Reads length-prefixed JPEG frames: `[4-byte int: length][JPEG bytes]`
3. Decodes each frame as a `Bitmap` and pushes it to the activity via `StreamViewerApplication`
4. Simultaneously sends the measured FPS back to the headset over port **6777**

If the connection drops, the service automatically retries every second. The status dot in the stream view updates in real time:
- **Yellow** — connecting
- **Amber** — reconnecting after a drop
- **Green** — stream flowing

### 4. Quality and FPS control
The stream view has two control bars (tap screen to toggle):

**Quality bar** — adjusts JPEG compression on the headset daemon via `quality=<value>` on TCP port **6779**.

| Level | Value |
|-------|-------|
| Low | 15 |
| Medium (default) | 30 |
| High | 50 |
| Max | 80 |

**FPS bar** — adjusts the screenshot polling interval via `delay=<ms>` on TCP port **6779**. Only shown when the daemon is in screenshot polling mode (`SS`), queried via the `mode` command on the control port. Hidden in virtual display mode (`VD`) where FPS is determined by SurfaceFlinger compose rate.

| Level | Delay | Target FPS |
|-------|-------|------------|
| 10 | 100 ms | ~10 |
| 15 | 66 ms | ~15 |
| 25 (default) | 40 ms | ~25 |
| Max | 16 ms | ~60 |

## File structure

| File | Responsibility |
|------|---------------|
| `DeviceListActivity.java` | Device list screen — discovery, reachability polling, card previews |
| `DeviceListAdapter.java` | RecyclerView adapter for device cards; also contains `GridSpacingDecoration` and `HeadsetIconConfig` |
| `DeviceItem.java` | Data model for a discovered headset; also contains `DeviceStatus` enum |
| `StreamActivity.java` | Full-screen stream view — quality controls, status dot, system UI |
| `DownstreamService.java` | Foreground service — TCP stream connection, frame decoding, FPS reporting |
| `StreamViewerApplication.java` | App class — holds the active `IStreamView` reference; also contains `IStreamView` interface |
| `AddressDiscoveryService.java` | UDP discovery logic; also contains `AddressDiscoveryType` and `AddressDiscovery` callback |
| `StreamValues.java` | Constants and shared utilities — ports, stream status, timing helpers, SharedPreferences |

## Adding a headset icon

1. Get the model name: `adb shell getprop ro.product.model`
2. Add a drawable to `src/main/res/drawable/`
3. Register the prefix → drawable mapping in the `HeadsetIconConfig` class inside `DeviceListAdapter.java`, above the default fallback entry
4. Rebuild and reinstall

## Support

- Issues: use your normal project tracker or repository issues.
- For integration questions, contact your QWR point of contact.
- Email: devs@questionwhatsreal.com
