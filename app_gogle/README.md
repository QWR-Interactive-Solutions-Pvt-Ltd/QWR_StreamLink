# app_gogle

Headset-side system app for QWR StreamLink. Manages the streaming daemon lifecycle — installing, starting, stopping, and keeping it alive across reboots and app restarts.

## How it works

### 1. App startup
`QwrGogleApplication` runs first. It reads the `daemon_should_run` SharedPreference to decide what to do:
- If the daemon is already alive (TCP probe on port 6779 succeeds) → sync the `isRunning` flag
- If it should be running but isn't (crash, reboot, OS kill) → reinstall the JAR if needed and relaunch the daemon automatically

### 2. Controller UI
`SplashScreenActivity` is the single screen in the app. It shows:
- Device model, serial number, and current IP address
- A status dot (green = streaming, red = stopped) that polls daemon liveness every 2 seconds

| Button | What it does |
|--------|-------------|
| **Start** | Saves intent to prefs, starts `ScreenCaptureService`, waits 1.5 s, refreshes UI |
| **Stop** | Saves intent to prefs, sends stop command to daemon, stops service, refreshes UI |
| **Quit** | Closes the app UI — daemon keeps running |

### 3. Daemon lifecycle
`ScreenCaptureService` is a foreground service that holds a persistent notification so Android doesn't kill the process. On start it:
1. Checks if `qwr-daemon.jar` needs installing or updating — compares the bundled JAR (in assets) against the installed one using **SHA-256 hash**. If the hashes differ, the new JAR is copied over.
2. If the JAR was updated and an old daemon is still running, sends a `stop` command to the old daemon and waits 1.5 s before launching the new one.
3. Launches the daemon via `app_process` in a new session:
   ```
   CLASSPATH=<jar> setsid app_process / com.qwr.daemon.Main &
   ```
4. Moves the daemon process out of the app's cgroup by writing its PID to `/sys/fs/cgroup/cgroup.procs` — this lets the daemon survive `forceStopPackage` from the VR launcher

On `onDestroy` the service intentionally does **not** stop the daemon. The daemon outlives the service.

### 4. Daemon control — `DaemonController`
All daemon interactions go through this static utility class:

| Method | Purpose |
|--------|---------|
| `installDaemonJar()` | Copies JAR from assets, sets world-readable permissions |
| `isJarInstalled()` | Checks if the JAR file exists on the filesystem |
| `isJarOutdated()` | Compares SHA-256 hash of installed JAR vs bundled asset — detects any content change |
| `isDaemonRunning()` | TCP probe to `127.0.0.1:6779` with 500 ms timeout |
| `startDaemon()` | Launches via `app_process`, waits for init, then escapes cgroup |
| `stopDaemon()` | Sends `stop\n` to port 6779; falls back to `pkill` if socket fails |

### 5. Boot auto-start
`BootReceiver` listens for `ACTION_BOOT_COMPLETED`. If `daemon_should_run` is `true` in prefs (streaming was active at last shutdown), it starts `ScreenCaptureService` so the daemon resumes automatically.

### 6. External control (Unity / Unreal / ADB)
`StreamCommandReceiver` accepts explicit broadcast intents to start or stop streaming without opening the UI:

| Action | Effect |
|--------|--------|
| `com.qwr.gogle.START_STREAM` | Saves intent, starts `ScreenCaptureService` if not already running |
| `com.qwr.gogle.STOP_STREAM` | Saves intent, stops daemon and service |

Must be sent as an **explicit broadcast** (with component name) so Android delivers it when the app is in stopped state. See the main README for Unity, Unreal, and ADB examples.

## File structure

| File | Responsibility |
|------|---------------|
| `QwrGogleApplication.java` | App init — auto-relaunch daemon on process restart |
| `view/activity/SplashScreenActivity.java` | Controller UI — Start/Stop/Quit, device info, status polling |
| `service/ScreenCaptureService.java` | Foreground service — daemon install, launch, persistent notification |
| `util/DaemonController.java` | Static utility — JAR install, daemon start/stop/probe, cgroup escape |
| `receiver/StreamCommandReceiver.java` | External start/stop via broadcast intents |
| `receiver/BootReceiver.java` | Auto-restarts daemon on device boot |

## Support

- Issues: use your normal project tracker or repository issues.
- For integration questions, contact your QWR point of contact.
- Email: devs@questionwhatsreal.com
