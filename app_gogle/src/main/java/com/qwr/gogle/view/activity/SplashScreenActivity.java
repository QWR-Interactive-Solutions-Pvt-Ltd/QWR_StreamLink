package com.qwr.gogle.view.activity;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import com.qwr.gogle.QwrGogleApplication;
import com.qwr.gogle.R;
import com.qwr.gogle.service.ScreenCaptureService;
import com.qwr.gogle.util.DaemonController;

/**
 * Controller UI for QWR streaming.
 * Shows device info, streaming status, and Start/Stop toggle.
 * Can also be controlled via intent broadcasts (see StreamCommandReceiver).
 */
public class SplashScreenActivity extends AppCompatActivity {

    private static final String TAG = "SplashScreenActivity";
    private static final int STATUS_REFRESH_MS = 2000;
    private View statusDot;
    private TextView statusText;
    private TextView infoText;
    private TextView deviceName;
    private TextView deviceSerial;
    private TextView deviceIp;
    private Button toggleButton;
    private Button quitButton;
    private Handler handler;
    private boolean isChecking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        Log.i(TAG, "onCreate");

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        infoText = findViewById(R.id.infoText);
        deviceName = findViewById(R.id.deviceName);
        deviceSerial = findViewById(R.id.deviceSerial);
        deviceIp = findViewById(R.id.deviceIp);
        toggleButton = findViewById(R.id.toggleButton);
        quitButton = findViewById(R.id.quitButton);
        handler = new Handler(Looper.getMainLooper());

        toggleButton.setOnClickListener(v -> onToggleClicked());
        quitButton.setOnClickListener(v -> {
            Log.i(TAG, "Quit button pressed");
            finishAffinity();
        });
        populateDeviceInfo();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isChecking = true;
        refreshStatus();
        startStatusPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isChecking = false;
    }

    private void populateDeviceInfo() {
        deviceName.setText(Build.MODEL);

        new Thread(() -> {
            String serial = getAdbSerial();
            String ip = getDeviceIpAddress();
            String displayIp = ip != null ? ip : "No network";
            runOnUiThread(() -> {
                deviceSerial.setText(serial);
                deviceIp.setText(displayIp);
            });
        }).start();
    }

    private String getAdbSerial() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String serial = (String) get.invoke(null, "ro.serialno", "");
            if (!serial.isEmpty()) return serial;
        } catch (Exception e) {
            Log.w(TAG, "SystemProperties failed: " + e.getMessage());
        }
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"getprop", "ro.serialno"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String serial = reader.readLine();
            proc.waitFor();
            if (serial != null && !serial.trim().isEmpty()) {
                return serial.trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "getprop fallback failed: " + e.getMessage());
        }
        return Build.SERIAL;
    }

    private String getDeviceIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getDeviceIpAddress failed: " + e.getMessage());
        }
        return null;
    }

    private void startStatusPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isChecking) {
                    refreshStatus();
                    handler.postDelayed(this, STATUS_REFRESH_MS);
                }
            }
        }, STATUS_REFRESH_MS);
    }

    private void refreshStatus() {
        // Check both service flag and daemon process (daemon survives forceStopPackage)
        new Thread(() -> {
            boolean running = ScreenCaptureService.isRunning || DaemonController.isDaemonRunning();
            runOnUiThread(() -> updateUI(running));
        }).start();
    }

    private void updateUI(boolean running) {
        GradientDrawable dotBg = (GradientDrawable) statusDot.getBackground();

        if (running) {
            dotBg.setColor(0xFF22C55E);
            statusText.setText("Streaming Active");
            statusText.setTextColor(0xFF22C55E);
            infoText.setText("TCP port 6776  \u00b7  UDP discovery on 8505");
            infoText.setVisibility(View.VISIBLE);
            toggleButton.setText("Stop Streaming");
            toggleButton.setBackgroundResource(R.drawable.bg_button_stop);
        } else {
            dotBg.setColor(0xFFEF4444);
            statusText.setText("Streaming Stopped");
            statusText.setTextColor(0xFFEF4444);
            infoText.setVisibility(View.GONE);
            toggleButton.setText("Start Streaming");
            toggleButton.setBackgroundResource(R.drawable.bg_button_start);
        }
    }

    private void onToggleClicked() {
        toggleButton.setEnabled(false);
        toggleButton.setAlpha(0.4f);
        statusText.setTextColor(0xFF94A3B8);

        // Check daemon state on background thread (isDaemonRunning does a socket connect)
        new Thread(() -> {
            boolean running = ScreenCaptureService.isRunning || DaemonController.isDaemonRunning();

            if (running) {
                // Stop
                Log.i(TAG, "Stopping daemon");
                runOnUiThread(() -> statusText.setText("Stopping\u2026"));

                QwrGogleApplication.setDaemonShouldRun(this, false);
                DaemonController.stopDaemon();
                ScreenCaptureService.isRunning = false;

                runOnUiThread(() -> {
                    stopService(new Intent(this, ScreenCaptureService.class));
                    updateUI(false);
                    toggleButton.setAlpha(1.0f);
                    toggleButton.setEnabled(true);
                });
            } else {
                // Start
                Log.i(TAG, "Starting daemon");
                runOnUiThread(() -> {
                    statusText.setText("Starting\u2026");

                    QwrGogleApplication.setDaemonShouldRun(this, true);
                    Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                });

                // Wait for daemon to start, then refresh UI
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                boolean nowRunning = DaemonController.isDaemonRunning();

                runOnUiThread(() -> {
                    updateUI(nowRunning);
                    toggleButton.setAlpha(1.0f);
                    toggleButton.setEnabled(true);
                });
            }
        }).start();
    }
}
