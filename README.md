# QWR StreamLink

Real-time screen streaming from VR headsets to an Android viewer app over WiFi.

## Prerequisites

- `adb` installed on your PC
- **Platform signing keys** from the headset manufacturer — this app runs as a system-level app (`android.uid.system`) and must be signed with the device's platform key. Without it, installation will fail with `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`

## Installation

```bash
# Headset app (must be platform-signed first)
adb install -r QWR-StreamLink-gogle_*.apk

# Viewer app (phone/tablet)
adb install -r app_stream_viewer-*.apk
```

The daemon JAR is bundled inside the APK and auto-installs on first use.

## Usage

### App UI

1. Open the headset app → tap **Start Streaming**
2. Open the viewer app on your phone/tablet → headset appears in device list → tap to view
3. To stop → open headset app → tap **Stop Streaming**
4. **Quit** closes the app UI — streaming continues in the background

### Intent Control (Unity / Unreal / ADB)

Start and stop streaming from external apps without opening the UI. Broadcasts must be **explicit** (include component name).

**Unity (C#):**
```csharp
var intent = new AndroidJavaObject("android.content.Intent");
intent.Call<AndroidJavaObject>("setAction", "com.qwr.gogle.START_STREAM");  // or STOP_STREAM
intent.Call<AndroidJavaObject>("setClassName", "com.qwr.gogle",
    "com.qwr.gogle.receiver.StreamCommandReceiver");
using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
    player.GetStatic<AndroidJavaObject>("currentActivity").Call("sendBroadcast", intent);
```

**Unreal (Java):**
```java
Intent intent = new Intent("com.qwr.gogle.START_STREAM");  // or STOP_STREAM
intent.setClassName("com.qwr.gogle", "com.qwr.gogle.receiver.StreamCommandReceiver");
sendBroadcast(intent);
```

**ADB:**
```bash
adb shell am broadcast -a com.qwr.gogle.START_STREAM -n com.qwr.gogle/.receiver.StreamCommandReceiver
adb shell am broadcast -a com.qwr.gogle.STOP_STREAM -n com.qwr.gogle/.receiver.StreamCommandReceiver
```

The app does **not** need to be open — Android starts the process automatically on receiving the broadcast.

### Stream Quality

```bash
adb shell "echo 'quality=50' | nc -w 1 127.0.0.1 6779"   # 1-100, default 30
```

### Frame Rate (screenshot polling mode only)

On headsets where the daemon uses screenshot polling (auto-detected), you can adjust FPS via the viewer app's FPS bar or via ADB:

```bash
adb shell "echo 'delay=33' | nc -w 1 127.0.0.1 6779"   # ~30 FPS (default)
adb shell "echo 'delay=16' | nc -w 1 127.0.0.1 6779"   # ~60 FPS max
```

This has no effect on headsets using virtual display mode (where FPS is determined by SurfaceFlinger compose rate).

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Headset not in viewer list | Ensure same WiFi network, streaming active (green dot) |
| Viewer connected to WiFi but still can't reach headset | Turn off mobile data — Android routes traffic over mobile data when WiFi has no internet, bypassing the local network |
| Stream stops on app close | Verify APK is platform-signed |
| Low FPS or lag | Reduce quality, check WiFi signal. FPS varies by headset firmware |

## Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 6776 | TCP | JPEG frame stream |
| 6779 | TCP | Control (stop, quality) |
| 8505 | UDP | Device discovery (headset broadcasts every 3 s) |

## Intent Actions

| Action | Description |
|--------|-------------|
| `com.qwr.gogle.START_STREAM` | Start streaming |
| `com.qwr.gogle.STOP_STREAM` | Stop streaming |

---

## Project Details

### Modules

| Module | Package | Description |
|--------|---------|-------------|
| [`app_gogle`](app_gogle/README.md) | `com.qwr.gogle` | Headset system app — manages daemon lifecycle, UI, intent receiver |
| [`app_stream_viewer`](app_stream_viewer/README.md) | `com.qwr.streamviewer` | Viewer app — discovers headsets, displays live stream |
| [`daemon_gogle`](daemon_gogle/README.md) | `com.qwr.daemon` | Standalone daemon — SurfaceControl screen capture, JPEG streaming |

### Architecture

```
  HEADSET                                          VIEWER
┌────────────────────────┐                    ┌────────────────────┐
│ ScreenCaptureService   │  UDP :8505         │ DeviceListActivity │
│   └─ Daemon            ├───────────────────▶│   └─ discovery     │
│      (SurfaceControl)  │  TCP :6776 (JPEG)  │ StreamActivity     │
│                        ├───────────────────▶│   └─ live view     │
│ SplashScreenActivity   │  TCP :6779 (ctrl)  │                    │
│ StreamCommandReceiver  │◀───────────────────│   (quality cmds)   │
└────────────────────────┘                    └────────────────────┘
```

### Building from Source

**Without platform keys** (requires external signing before install):
```bash
./gradlew :app_gogle:assembleDebug_develop :app_stream_viewer:assembleDebug
```

**With platform keys** (produces a ready-to-install signed APK):

1. Prepare your keystore — Gradle accepts **`.keystore` (PKCS12) or `.jks` (JKS)** format directly.
   If you already have one of those files, skip to step 2.
   If you only have raw `.pk8` + `.x509.pem` keys, convert them first (one-time):
```bash
openssl pkcs8 -in platform.pk8 -inform DER -out platform.pem -nocrypt
openssl pkcs12 -export -in platform.x509.pem -inkey platform.pem -out platform.p12 -name platform
keytool -importkeystore -srckeystore platform.p12 -srcstoretype PKCS12 -destkeystore platform.jks
```

2. Add to `gradle.properties` (never commit this):
```properties
PLATFORM_STORE_FILE=C:/path/to/platform.keystore   # .keystore or .jks both work
PLATFORM_STORE_PASSWORD=your_store_password
PLATFORM_KEY_ALIAS=your_key_alias
PLATFORM_KEY_PASSWORD=your_key_password
```

3. Build:
```bash
./gradlew :app_gogle:assembleDebug_develop_system :app_stream_viewer:assembleDebug
```

Outputs: `app_gogle/build/outputs/apk/debug_develop_system/`, `app_stream_viewer/build/outputs/apk/debug/`

> **Note:** If the platform key properties are absent from `gradle.properties`, the `debug_develop_system` variant is automatically hidden by Gradle — no warnings, no broken sync. All other build variants (`debug_develop`, etc.) are completely unaffected.

### Key Details

- **Java only**, system app (`sharedUserId="android.uid.system"`, UID 1000)
- Daemon escapes app's cgroup to survive VR launcher's `forceStopPackage`
- Auto-starts daemon on boot if streaming was previously active
- **Hybrid capture**: virtual display (event-driven, high FPS) with automatic fallback to screenshot polling (for headsets where VR compositor bypasses SurfaceFlinger)
- Wire format: `[4-byte big-endian int: JPEG length][JPEG bytes]`
- Discovery: `QWR_STREAMLINK|IP|PORT|deviceName|deviceSerial` on UDP :8505 every 3s

### Adding a Headset Icon

The viewer app shows a headset icon next to each discovered device. Icons are matched by device model name prefix (case-insensitive, first match wins). To add an icon for a new headset model:

1. Get the model name: `adb shell getprop ro.product.model` (e.g. `SXR_1`, `VRone_Edu`)
2. Add your icon drawable to `app_stream_viewer/src/main/res/drawable/`
3. Register the model prefix → drawable mapping in the `HeadsetIconConfig` class inside `DeviceListAdapter.java`, above the default fallback entry
4. Rebuild and reinstall `app_stream_viewer`

---

## Support

- Issues: use your normal project tracker or repository issues.
- For integration questions, contact your QWR point of contact.
- Email: devs@questionwhatsreal.com
