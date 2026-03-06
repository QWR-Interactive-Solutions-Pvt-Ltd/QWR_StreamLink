package com.qwr.gogle.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.qwr.gogle.service.ScreenCaptureService;

/**
 * Starts the streaming service on boot if the user had streaming enabled
 * before the device was rebooted.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            boolean shouldRun = context.getSharedPreferences("daemon_prefs", Context.MODE_PRIVATE)
                    .getBoolean("daemon_should_run", false);

            if (shouldRun) {
                Log.i(TAG, "Boot completed — daemon was running, restarting service");
                Intent serviceIntent = new Intent(context, ScreenCaptureService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.i(TAG, "Boot completed — daemon was not running, skipping");
            }
        }
    }
}
