# daemon_gogle

Standalone streaming daemon for QWR StreamLink. Runs as a bare `app_process` JAR with system UID — no Android `Application` context. Captures the headset display via `SurfaceControl`, encodes frames as JPEG, and streams them over TCP to the viewer app.

## How it works

### 1. Entry point — `Main`
`app_gogle` launches the daemon as:
```bash
CLASSPATH=<jar> setsid app_process / com.qwr.daemon.Main &
```

`Main.main()` wires everything together:
1. Parses optional `key=value` CLI arguments (quality, resize, port, etc.)
2. Resolves the display dimensions via `DisplayInfo`
3. Starts `ScreenCapture` (capture pipeline)
4. Starts `StreamServer` (network layer)
5. Writes its own PID to `/data/local/tmp/qwr-daemon.pid`
6. Opens the **control port** (6779) and listens for commands
7. Blocks the main thread with `Object.wait()` to keep the process alive (all work runs on daemon threads)

**Default configuration:**

| Parameter | Default | CLI key |
|-----------|---------|---------|
| JPEG quality | 30 | `quality` |
| Resize | 30% of display | `resize` |
| Stream port | 6776 | `port` |
| Discovery port | 8505 | `discovery_port` |
| Frame delay | 33 ms (~30 FPS) | `delay` |

**Tuning frame rate via ADB** (screenshot polling mode only — no effect in virtual display mode):
```powershell
adb shell "echo 'delay=40' | nc -w 2 127.0.0.1 6779"   # ~25 FPS (default)
adb shell "echo 'delay=33' | nc -w 2 127.0.0.1 6779"   # ~30 FPS
adb shell "echo 'delay=16' | nc -w 2 127.0.0.1 6779"   # ~60 FPS max
```
Takes effect immediately — no daemon restart required. The viewer app also has an FPS bar for this.

### 2. Display resolution — `DisplayInfo`
The daemon has no `Context`, so it gets the display size via reflection on hidden Android service APIs (`IWindowManager`, `IDisplayManager`). Handles screen rotation by swapping width/height for landscape orientations. Falls back to `3664 × 1920` if reflection fails.

### 3. Screen capture pipeline — `ScreenCapture`
Hybrid capture with automatic fallback. Tries the fastest approach first, switches if it underperforms:

**Mode 1 — Virtual Display (primary, event-driven)**
```
SurfaceControl.createDisplay() → ImageReader.getSurface()
       ↓
  OnImageAvailable callback (fires at SurfaceFlinger compose rate)
       ↓
  Image → Bitmap → JPEG → frameQueue
```
Event-driven — no polling overhead. Achieves full frame rate on headsets where the VR compositor routes through SurfaceFlinger GPU composition (e.g. SWR headsets).

**Mode 2 — Screenshot Polling (fallback, two-thread pipeline)**
```
CaptureThread (every delayMs)
       ↓
  SurfaceControl.screenshot(displayToken, ...) → Bitmap → rawQueue (max 2)

CompressThread (runs as fast as rawQueue has frames)
       ↓
  Bitmap.compress(JPEG, quality) → byte[] → frameQueue (max 3)
```
Active polling — forces a GPU readback on demand. Used on headsets where the VR compositor bypasses SurfaceFlinger (e.g. YVR headsets with ATW hardware overlay), causing the virtual display to receive very few frames.

**Auto-switching**: The daemon starts in virtual display mode. After 4 seconds, if fewer than 8 frames were captured (~2 FPS), it automatically cleans up the virtual display and switches to screenshot polling. No restart needed.

**Screenshot reflection chain** — handles API differences across Android 9–12+:
- **Display token**: `getBuiltInDisplay(int)` (Android 9–11) → `getInternalDisplayToken()` (Android 12+)
- **Screenshot** (tried in order until one resolves):
  - `screenshot(IBinder, int, int)` — Android 9
  - `screenshot(IBinder, int, int, boolean)` — Android 9 variant
  - `screenshot(Rect, int, int, int, int, boolean, int)` — Android 9 variant
  - `screenshot(IBinder, Rect, int, int, boolean, int)` — YVR Android 10 ROM
  - `screenshot(Rect, int, int, boolean, int)` — YVR ROM variant
  - `screenshotToBuffer(...)` variants — Android 10/11 (returns `ScreenshotGraphicBuffer` → `HardwareBuffer` → Bitmap)

Key points:
- `setJpegQuality(int)` takes effect on the next frame — called live from the control port
- `setDelayMs(int)` adjusts the polling interval at runtime (screenshot mode only; no effect in virtual display mode)
- `popFrame()` blocks with wait/notify until a frame is available — zero latency between capture and send
- The queue cap of 3 prevents unbounded memory growth when the network is slower than the capture rate
- The HandlerThread naturally self-throttles: while compressing one frame, `acquireLatestImage()` skips to the latest on the next callback
- Virtual display mode reuses a single Bitmap and ByteArrayOutputStream across frames to reduce GC pressure
- `getMode()` returns `"VD"` or `"SS"` — queried by the viewer app to show/hide the FPS control bar

### 4. Network layer — `StreamServer`
Three concurrent threads:

| Thread | Port | Protocol | Purpose |
|--------|------|----------|---------|
| `StreamThread` | 6776 | TCP | Push JPEG frames to the viewer |
| `DiscoveryThread` | 8505 | TCP | Legacy TCP discovery responses |
| `UdpBroadcastThread` | 8505 | UDP broadcast | Beacon every 3 s for viewer discovery |

**Stream thread** — single-client model. Calls `screenCapture.popFrame()` in a loop (blocks until a frame is available) and writes each frame as:
```
[4-byte big-endian int: JPEG length][JPEG bytes]
```
On disconnect, waits 1 s then accepts the next client.

**UDP broadcast** — sends to `255.255.255.255:8505` every 3 seconds:
```
QWR_STREAMLINK|<ip>|<streamPort>|<deviceName>|<deviceSerial>
```
This is what the viewer app's `AddressDiscoveryService` listens for. If the local IP is not yet resolved (WiFi still connecting), it retries on each cycle.

### 5. Control port (6779)
A background thread accepts one-line text commands:

| Command | Effect |
|---------|--------|
| `stop` | Clean shutdown — stops capture, closes sockets, exits |
| `quality=N` | Changes JPEG compression quality live (1–100) |
| `delay=N` | Changes frame interval in ms live — e.g. `delay=20` ≈ 50 FPS, `delay=33` ≈ 30 FPS |

`app_gogle` sends `stop\n` here first when stopping. The viewer app sends `quality=N\n` when the user changes the quality level.

### 6. Cgroup escape
After launch, `app_gogle`'s `DaemonController` writes the daemon PID to `/sys/fs/cgroup/cgroup.procs`, moving it out of the app's cgroup. This is what allows the daemon to survive the VR launcher calling `forceStopPackage` on `app_gogle`.

## File structure

| File | Responsibility |
|------|---------------|
| `Main.java` | Entry point — wires all subsystems, runs control port, keeps process alive |
| `DisplayInfo.java` | Resolves display dimensions via reflection on hidden Android APIs |
| `ScreenCapture.java` | SurfaceControl capture pipeline → JPEG frame queue |
| `StreamServer.java` | TCP stream server, UDP discovery broadcast, TCP discovery responder |

## Building

The daemon builds as a DEX JAR (not an APK):
```bash
./gradlew :daemon_gogle:buildDaemonDex
```
Output: `daemon_gogle/build/outputs/daemon/qwr-daemon.jar`

This JAR is automatically copied into `app_gogle`'s assets during the `app_gogle` build and bundled into the APK.

## Future scope

### Hardware JPEG encoding
Use `MediaCodec` with `image/jpeg` MIME type to offload JPEG compression to the device's hardware encoder. This would drastically reduce CPU usage since `Bitmap.compress()` currently does all JPEG encoding in software on the CPU. Not all devices expose a hardware JPEG encoder, so this would need a capability check with software fallback.

### H.264 video stream
Replace per-frame JPEG with a hardware-accelerated H.264 video stream via `MediaCodec`. Benefits:
- **Near-zero CPU** — encoding runs on dedicated video hardware
- **Much better compression** — H.264 inter-frame compression yields 5-10x smaller data than individual JPEGs at similar quality
- **Lower bandwidth** — smaller frames mean faster TCP transmission and less network contention
- Requires changing the viewer app's decoder from JPEG to `MediaCodec` H.264 decoding (also hardware-accelerated), so this is a protocol-breaking change that affects both daemon and viewer

### Multi-client streaming
Currently the stream server accepts a single TCP client. Supporting multiple simultaneous viewers would allow monitoring from multiple tablets. The frame queue is already thread-safe; the main change would be in `StreamServer` to manage multiple client sockets.

## Support

- Issues: use your normal project tracker or repository issues.
- For integration questions, contact your QWR point of contact.
- Email: devs@questionwhatsreal.com
