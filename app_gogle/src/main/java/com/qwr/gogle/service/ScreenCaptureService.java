package com.qwr.gogle.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.qwr.gogle.R;
import com.qwr.gogle.util.DaemonController;

/**
 * Foreground service that manages the streaming daemon lifecycle.
 * The daemon runs as a separate app_process (UID 1000) for screen capture via SurfaceControl.
 * This service handles: JAR installation, daemon start/stop, and status tracking.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1;

    public static volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Set eagerly BEFORE launching the background thread so that any concurrent
        // onStartCommand call (e.g. QwrGogleApplication auto-start racing with a
        // StreamCommandReceiver broadcast) sees isRunning=true and returns early.
        // If the actual daemon launch fails, isRunning is reset to false below.
        boolean wasRunning = isRunning;
        isRunning = true;
        startForeground(NOTIFICATION_ID, buildNotification());

        // Install or update daemon JAR if needed
        boolean jarUpdated = false;
        if (!DaemonController.isJarInstalled(this) || DaemonController.isJarOutdated(this)) {
            Log.i(TAG, "Installing/updating daemon JAR from assets");
            if (!DaemonController.installDaemonJar(this)) {
                Log.e(TAG, "Failed to install daemon JAR");
                isRunning = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            jarUpdated = true;
        }

        // If daemon is already running with current JAR, nothing to do
        if (!jarUpdated && (wasRunning || DaemonController.isDaemonRunning())) {
            Log.i(TAG, "Daemon already running with current JAR, syncing state");
            return START_STICKY;
        }

        // Start daemon in background thread (has a 1s wait)
        final boolean needsRestart = jarUpdated;
        new Thread(() -> {
            // Stop stale daemon if JAR was updated (old daemon uses old code)
            if (needsRestart && DaemonController.isDaemonRunning()) {
                Log.i(TAG, "Stopping stale daemon before launching updated one");
                DaemonController.stopDaemon();
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
            boolean started = DaemonController.startDaemon();
            if (started) {
                Log.i(TAG, "Daemon started successfully");
            } else {
                Log.e(TAG, "Failed to start daemon");
                isRunning = false;
                stopSelf();
            }
        }, "DaemonStartThread").start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        isRunning = false;
        // Don't stop daemon here — it should keep running after the app closes.
        // Daemon is only stopped via explicit user action (SplashScreenActivity toggle).
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while screen streaming is active");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("QWR Streamer")
                .setContentText("Streaming active")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }
}
