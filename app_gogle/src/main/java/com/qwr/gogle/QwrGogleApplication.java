package com.qwr.gogle;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.qwr.gogle.service.ScreenCaptureService;
import com.qwr.gogle.util.DaemonController;

public class QwrGogleApplication extends Application {

    private static final String TAG = "QwrGogleApplication";
    private static final String PREFS_NAME = "daemon_prefs";
    private static final String KEY_DAEMON_SHOULD_RUN = "daemon_should_run";

    @Override
    public void onCreate() {
        super.onCreate();

        boolean shouldRun = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_DAEMON_SHOULD_RUN, false);

        new Thread(() -> {
            if (DaemonController.isDaemonRunning()) {
                ScreenCaptureService.isRunning = true;
                Log.i(TAG, "Daemon already running — synced status");
            } else if (shouldRun) {
                // Daemon was killed but user wanted it running.
                // App process was restarted (boot, intent, or user) — relaunch the daemon.
                Log.i(TAG, "Daemon should be running but is dead — relaunching");
                if (!DaemonController.isJarInstalled(QwrGogleApplication.this)) {
                    DaemonController.installDaemonJar(QwrGogleApplication.this);
                }
                if (DaemonController.startDaemon()) {
                    ScreenCaptureService.isRunning = true;
                    Log.i(TAG, "Daemon relaunched successfully");
                } else {
                    Log.e(TAG, "Daemon relaunch failed");
                }
            }
        }).start();
    }

    public static void setDaemonShouldRun(android.content.Context context, boolean shouldRun) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_DAEMON_SHOULD_RUN, shouldRun).apply();
    }
}
