# Branding / Whitelabel Guide

How to rebrand `app_gogle` for a different product (e.g. Unity VR app, partner deployments).

## TL;DR

1. Edit `app_gogle/branding.gradle` — change the four values.
2. Replace the icon files in `app_gogle/src/main/res/mipmap-*/`.
3. Rebuild: `./gradlew :app_gogle:assembleDebug`.

Done.

## The Branding File

`app_gogle/branding.gradle` is the single source of truth for everything that changes between brands:

```groovy
ext.branding = [
    appName            : 'Streamer',
    applicationId      : 'com.streamer.core',
    intentActionPrefix : 'com.streamer.core',
    apkPrefix          : 'Streamer-Core'
]
```

### Field reference

| Field | What it controls | Example for Unity VR brand |
|-------|------------------|----------------------------|
| `appName` | User-visible name in launcher and task switcher | `Unity VR Streamer` |
| `applicationId` | Android package ID on the device. Two apps with the same ID can't coexist | `com.unityvr.streamer` |
| `intentActionPrefix` | Used to derive `<prefix>.START_STREAM` and `<prefix>.STOP_STREAM` broadcast actions that external apps send to control streaming | `com.unityvr.streamer` |
| `apkPrefix` | Output APK file name (full name = `<apkPrefix>_<buildType>_<version>.apk`) | `UnityVR-Streamer` |

## What Gets Updated Automatically

Editing `branding.gradle` propagates to:

- **App launcher label** — via gradle `resValue` overriding `app_gogle_name`
- **AndroidManifest.xml** — `applicationId` and intent-filter actions (via `${intentActionStart}` / `${intentActionStop}` placeholders)
- **Java code** — `StreamCommandReceiver` reads action names from `BuildConfig.INTENT_ACTION_START` / `INTENT_ACTION_STOP`
- **APK file name** — output naming uses `apkPrefix`

No need to edit `AndroidManifest.xml`, Java files, or any code by hand.

## What You Need to Swap Manually

These can't live in `branding.gradle` — they're binary assets or per-resource files:

| Asset | Location |
|-------|----------|
| Launcher icon | `app_gogle/src/main/res/mipmap-*/ic_launcher.*` |
| Theme colors | `app_gogle/src/main/res/values/colors.xml` |
| Splash drawable | `app_gogle/src/main/res/drawable*/` |
| Strings shown in UI | `app_gogle/src/main/res/values/strings.xml` |

## Example: Whitelabel for Unity VR App

1. Edit `app_gogle/branding.gradle`:

   ```groovy
   ext.branding = [
       appName            : 'Unity VR Streamer',
       applicationId      : 'com.unityvr.streamer',
       intentActionPrefix : 'com.unityvr.streamer',
       apkPrefix          : 'UnityVR-Streamer'
   ]
   ```

2. Replace icon files in `mipmap-*` folders with the new brand icon.

3. Build:

   ```bash
   ./gradlew :app_gogle:assembleDebug
   ```

4. Update Unity-side intent call to use the new action name:

   ```csharp
   intent.Call<AndroidJavaObject>("setAction", "com.unityvr.streamer.START_STREAM");
   intent.Call<AndroidJavaObject>("setClassName", "com.unityvr.streamer",
       "com.streamer.core.receiver.StreamCommandReceiver");
   ```

   > Note: `setClassName` first argument uses the new `applicationId`, but the second argument (`com.streamer.core.receiver.StreamCommandReceiver`) is a fixed framework contract — same for every brand.

## Gotchas

- **Two builds with the same `applicationId` can't coexist** on the same device. Use a different `applicationId` per brand if you need both installed for testing.
- **The Java package (`com.streamer.core`) is the stable framework contract** and does not change per brand. Only the `applicationId`, app name, intent prefix, and APK name change.
- **External apps must match the new `intentActionPrefix`** — Unity / Unreal partners need to update their broadcast action strings to match.
- After changing `branding.gradle`, do a clean build if you see stale values: `./gradlew :app_gogle:clean :app_gogle:assembleDebug`.
