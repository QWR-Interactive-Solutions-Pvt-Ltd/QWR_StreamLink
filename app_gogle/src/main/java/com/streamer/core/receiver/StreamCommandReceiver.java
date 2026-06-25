package com.streamer.core.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.streamer.core.BuildConfig;
import com.streamer.core.StreamerApplication;
import com.streamer.core.service.ScreenCaptureService;
import com.streamer.core.util.DaemonController;

/**
 * Receives start/stop streaming commands from external apps (Unity, Unreal, etc).
 *
 * IMPORTANT: Must use explicit broadcast (with component name) so Android delivers
 * it even when the app is in stopped state (after forceStopPackage).
 *
 * Action names depend on branding.gradle (intentActionPrefix). The class name
 * com.streamer.core.receiver.StreamCommandReceiver is the stable framework contract
 * and does NOT change per brand.
 *
 * Usage from Unity (C#):
 *   var intent = new AndroidJavaObject("android.content.Intent");
 *   intent.Call&lt;AndroidJavaObject&gt;("setAction", "&lt;intentActionPrefix&gt;.START_STREAM");
 *   intent.Call&lt;AndroidJavaObject&gt;("setClassName", "&lt;applicationId&gt;",
 *       "com.streamer.core.receiver.StreamCommandReceiver");
 *   activity.Call("sendBroadcast", intent);
 *
 * Usage from ADB:
 *   adb shell am broadcast -a &lt;intentActionPrefix&gt;.START_STREAM -n &lt;applicationId&gt;/com.streamer.core.receiver.StreamCommandReceiver
 *   adb shell am broadcast -a &lt;intentActionPrefix&gt;.STOP_STREAM  -n &lt;applicationId&gt;/com.streamer.core.receiver.StreamCommandReceiver
 */
public class StreamCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "StreamCommandReceiver";
    // Intent action names are brand-configurable via branding.gradle (intentActionPrefix).
    public static final String ACTION_START = BuildConfig.INTENT_ACTION_START;
    public static final String ACTION_STOP = BuildConfig.INTENT_ACTION_STOP;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case ACTION_START:
                Log.i(TAG, "Received START_STREAM intent");
                startStream(context);
                break;
            case ACTION_STOP:
                Log.i(TAG, "Received STOP_STREAM intent");
                stopStream(context);
                break;
        }
    }

    private void startStream(Context context) {
        if (DaemonController.isDaemonRunning()) {
            Log.i(TAG, "Daemon already running, ignoring start");
            return;
        }

        StreamerApplication.setDaemonShouldRun(context, true);

        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        Log.i(TAG, "Start stream command processed");
    }

    private void stopStream(Context context) {
        StreamerApplication.setDaemonShouldRun(context, false);

        new Thread(() -> {
            DaemonController.stopDaemon();
            ScreenCaptureService.isRunning = false;
            Log.i(TAG, "Stop stream command processed");
        }).start();

        context.stopService(new Intent(context, ScreenCaptureService.class));
    }
}
