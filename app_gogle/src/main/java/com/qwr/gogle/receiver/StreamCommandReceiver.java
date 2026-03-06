package com.qwr.gogle.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.qwr.gogle.QwrGogleApplication;
import com.qwr.gogle.service.ScreenCaptureService;
import com.qwr.gogle.util.DaemonController;

/**
 * Receives start/stop streaming commands from external apps (Unity, Unreal, etc).
 *
 * IMPORTANT: Must use explicit broadcast (with component name) so Android delivers
 * it even when the app is in stopped state (after forceStopPackage).
 *
 * Usage from Unity (C#):
 *   var intent = new AndroidJavaObject("android.content.Intent");
 *   intent.Call&lt;AndroidJavaObject&gt;("setAction", "com.qwr.gogle.START_STREAM");
 *   intent.Call&lt;AndroidJavaObject&gt;("setClassName", "com.qwr.gogle", "com.qwr.gogle.receiver.StreamCommandReceiver");
 *   activity.Call("sendBroadcast", intent);
 *
 * Usage from ADB:
 *   adb shell am broadcast -a com.qwr.gogle.START_STREAM -n com.qwr.gogle/.receiver.StreamCommandReceiver
 *   adb shell am broadcast -a com.qwr.gogle.STOP_STREAM -n com.qwr.gogle/.receiver.StreamCommandReceiver
 */
public class StreamCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "StreamCommandReceiver";
    public static final String ACTION_START = "com.qwr.gogle.START_STREAM";
    public static final String ACTION_STOP = "com.qwr.gogle.STOP_STREAM";

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

        QwrGogleApplication.setDaemonShouldRun(context, true);

        Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        Log.i(TAG, "Start stream command processed");
    }

    private void stopStream(Context context) {
        QwrGogleApplication.setDaemonShouldRun(context, false);

        new Thread(() -> {
            DaemonController.stopDaemon();
            ScreenCaptureService.isRunning = false;
            Log.i(TAG, "Stop stream command processed");
        }).start();

        context.stopService(new Intent(context, ScreenCaptureService.class));
    }
}
