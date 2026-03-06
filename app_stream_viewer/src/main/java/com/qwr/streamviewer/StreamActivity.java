package com.qwr.streamviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.net.Socket;


public class StreamActivity extends AppCompatActivity implements IStreamView {

    private static final String TAG = "StreamActivity";

    private static final int QUALITY_LOW    = 15;
    private static final int QUALITY_MEDIUM = 30;
    private static final int QUALITY_HIGH   = 50;
    private static final int QUALITY_MAX    = 80;
    private static final int DEFAULT_QUALITY = QUALITY_MEDIUM;

    // FPS presets — values are delay in ms sent to daemon (only affects screenshot polling mode)
    private static final int FPS_10_DELAY  = 100;
    private static final int FPS_15_DELAY  = 66;
    private static final int FPS_25_DELAY  = 40;
    private static final int FPS_MAX_DELAY = 16;
    private static final int DEFAULT_FPS_DELAY = FPS_25_DELAY;

    private static final int DOT_CONNECTING  = 0xFFFFEB3B;  // yellow
    private static final int DOT_WAITING     = 0xFFFF9800;  // amber
    private static final int DOT_STREAMING   = 0xFF4CAF50;  // green

    private ImageView streamImageView;
    private TextView statusText;
    private View streamStatusDot;
    private ImageButton btnClose;
    private View streamContainer;
    private View qualityBar;
    private TextView btnQualityLow, btnQualityMedium, btnQualityHigh, btnQualityMax;
    private View fpsBar;
    private TextView btnFps10, btnFps15, btnFps25, btnFpsMax;

    private StreamViewerApplication app;
    private boolean controlsVisible = true;
    private int currentQuality = DEFAULT_QUALITY;
    private int currentFpsDelay = DEFAULT_FPS_DELAY;
    private boolean fpsControlEnabled = false;
    private String headsetIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream);

        app = (StreamViewerApplication) getApplication();

        streamImageView = findViewById(R.id.streamImageView);
        statusText = findViewById(R.id.statusText);
        streamStatusDot = findViewById(R.id.streamStatusDot);
        btnClose = findViewById(R.id.btnClose);
        streamContainer = findViewById(R.id.streamContainer);

        qualityBar = findViewById(R.id.qualityBar);
        btnQualityLow = findViewById(R.id.btnQualityLow);
        btnQualityMedium = findViewById(R.id.btnQualityMedium);
        btnQualityHigh = findViewById(R.id.btnQualityHigh);
        btnQualityMax = findViewById(R.id.btnQualityMax);

        fpsBar = findViewById(R.id.fpsBar);
        btnFps10 = findViewById(R.id.btnFps10);
        btnFps15 = findViewById(R.id.btnFps15);
        btnFps25 = findViewById(R.id.btnFps25);
        btnFpsMax = findViewById(R.id.btnFpsMax);

        btnClose.setOnClickListener(v -> {
            stopStream();
            finish();
        });

        btnQualityLow.setOnClickListener(v -> selectQuality(QUALITY_LOW));
        btnQualityMedium.setOnClickListener(v -> selectQuality(QUALITY_MEDIUM));
        btnQualityHigh.setOnClickListener(v -> selectQuality(QUALITY_HIGH));
        btnQualityMax.setOnClickListener(v -> selectQuality(QUALITY_MAX));

        btnFps10.setOnClickListener(v -> selectFps(FPS_10_DELAY));
        btnFps15.setOnClickListener(v -> selectFps(FPS_15_DELAY));
        btnFps25.setOnClickListener(v -> selectFps(FPS_25_DELAY));
        btnFpsMax.setOnClickListener(v -> selectFps(FPS_MAX_DELAY));

        streamContainer.setOnClickListener(v -> toggleControls());

        headsetIp = StorageOperations.readStringFromSharedPreferences(
                getApplicationContext(), StorageOperations.GOGLE_ADDRESS_IP_KEY);

        currentQuality = StorageOperations.readIntFromSharedPreferences(
                getApplicationContext(), qualityPrefKey(), DEFAULT_QUALITY);
        currentFpsDelay = StorageOperations.readIntFromSharedPreferences(
                getApplicationContext(), fpsDelayPrefKey(), DEFAULT_FPS_DELAY);

        updateQualityHighlight();
        updateFpsHighlight();
        fpsBar.setVisibility(View.GONE);
        hideSystemUi();
        requestNotificationPermission();
        startStream();
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.setStreamView(this);
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        app.setStreamView(null);
    }

    @Override
    protected void onDestroy() {
        stopStream();
        super.onDestroy();
    }

    @Override
    public void setStreamFrame(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (bitmap != null) {
                streamImageView.setImageBitmap(bitmap);
            } else {
                statusText.setText(R.string.waiting_for_stream);
                setStatusDotColor(DOT_WAITING);
            }
        });
    }

    @Override
    public void onStreamStats(int fps, int frameSizeBytes) {
        runOnUiThread(() -> {
            String qualityLabel = getQualityLabel();
            int sizeKb = frameSizeBytes / 1024;
            statusText.setText(fps + " FPS | " + sizeKb + " KB | " + qualityLabel);
            setStatusDotColor(DOT_STREAMING);
        });
    }

    private void startStream() {
        DownstreamService.IS_STREAMING = true;
        startService(new Intent(this, DownstreamService.class));
        statusText.setText(R.string.connecting);
        setStatusDotColor(DOT_CONNECTING);
        queryCaptureMode();
        if (currentQuality != DEFAULT_QUALITY) {
            sendQualityCommand(currentQuality);
        }
        if (currentFpsDelay != DEFAULT_FPS_DELAY) {
            sendDelayCommand(currentFpsDelay);
        }
    }

    private void queryCaptureMode() {
        if (headsetIp == null || headsetIp.isEmpty()) return;

        new Thread(() -> {
            try (Socket socket = new Socket(headsetIp, PortValues.DAEMON_CONTROL)) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write("mode\n".getBytes());
                out.flush();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream()));
                String mode = reader.readLine();
                fpsControlEnabled = "SS".equals(mode);
                Log.d(TAG, "Daemon capture mode: " + mode + ", FPS control: " + fpsControlEnabled);
                runOnUiThread(() -> fpsBar.setVisibility(fpsControlEnabled && controlsVisible ? View.VISIBLE : View.GONE));
            } catch (Exception e) {
                Log.w(TAG, "Failed to query capture mode: " + e.getMessage());
            }
        }, "ModeQuery").start();
    }

    private void stopStream() {
        DownstreamService.IS_STREAMING = false;
        DownstreamService.STREAM_STATUS = StreamStatus.ENDED_STREAM;
        stopService(new Intent(this, DownstreamService.class));
    }

    // --- Controls ---

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        int visibility = controlsVisible ? View.VISIBLE : View.GONE;
        btnClose.setVisibility(visibility);
        qualityBar.setVisibility(visibility);
        fpsBar.setVisibility(fpsControlEnabled && controlsVisible ? View.VISIBLE : View.GONE);
        // statusBar (dot + text) is always visible — not toggled
    }

    private void setStatusDotColor(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        streamStatusDot.setBackground(dot);
    }

    // --- Quality control ---

    private void selectQuality(int quality) {
        currentQuality = quality;
        updateQualityHighlight();
        sendQualityCommand(quality);
        StorageOperations.writeIntToSharedPreferences(
                getApplicationContext(), qualityPrefKey(), quality);
    }

    private void updateQualityHighlight() {
        applyQualityButtonState(btnQualityLow,    currentQuality == QUALITY_LOW);
        applyQualityButtonState(btnQualityMedium, currentQuality == QUALITY_MEDIUM);
        applyQualityButtonState(btnQualityHigh,   currentQuality == QUALITY_HIGH);
        applyQualityButtonState(btnQualityMax,    currentQuality == QUALITY_MAX);
    }

    private void applyQualityButtonState(TextView btn, boolean selected) {
        if (selected) {
            btn.setBackgroundResource(R.drawable.bg_quality_selected);
            btn.setTextColor(Color.BLACK);
        } else {
            btn.setBackground(null);
            btn.setTextColor(0xAAFFFFFF);
        }
    }

    private String getQualityLabel() {
        if (currentQuality == QUALITY_LOW)  return getString(R.string.quality_low);
        if (currentQuality == QUALITY_HIGH) return getString(R.string.quality_high);
        if (currentQuality == QUALITY_MAX)  return getString(R.string.quality_max);
        return getString(R.string.quality_medium);
    }

    // --- FPS control (screenshot polling mode only) ---

    private void selectFps(int delayMs) {
        currentFpsDelay = delayMs;
        updateFpsHighlight();
        sendDelayCommand(delayMs);
        StorageOperations.writeIntToSharedPreferences(
                getApplicationContext(), fpsDelayPrefKey(), delayMs);
    }

    private void updateFpsHighlight() {
        applyQualityButtonState(btnFps10,  currentFpsDelay == FPS_10_DELAY);
        applyQualityButtonState(btnFps15,  currentFpsDelay == FPS_15_DELAY);
        applyQualityButtonState(btnFps25,  currentFpsDelay == FPS_25_DELAY);
        applyQualityButtonState(btnFpsMax, currentFpsDelay == FPS_MAX_DELAY);
    }

    private void sendDelayCommand(int delayMs) {
        if (headsetIp == null || headsetIp.isEmpty()) {
            Log.w(TAG, "No headset IP available, cannot send delay command");
            return;
        }
        new Thread(() -> {
            try (Socket socket = new Socket(headsetIp, PortValues.DAEMON_CONTROL)) {
                OutputStream out = socket.getOutputStream();
                out.write(("delay=" + delayMs + "\n").getBytes());
                out.flush();
                Log.d(TAG, "Sent delay=" + delayMs + " to " + headsetIp);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send delay command: " + e.getMessage());
            }
        }, "DelayCommand").start();
    }

    private void sendQualityCommand(int quality) {
        if (headsetIp == null || headsetIp.isEmpty()) {
            Log.w(TAG, "No headset IP available, cannot send quality command");
            return;
        }
        new Thread(() -> {
            try (Socket socket = new Socket(headsetIp, PortValues.DAEMON_CONTROL)) {
                OutputStream out = socket.getOutputStream();
                out.write(("quality=" + quality + "\n").getBytes());
                out.flush();
                Log.d(TAG, "Sent quality=" + quality + " to " + headsetIp);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send quality command: " + e.getMessage());
            }
        }, "QualityCommand").start();
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    // --- Per-device preference keys ---

    private String qualityPrefKey() {
        return StorageOperations.STREAM_QUALITY_KEY + "_" + (headsetIp != null ? headsetIp : "default");
    }

    private String fpsDelayPrefKey() {
        return StorageOperations.STREAM_FPS_DELAY_KEY + "_" + (headsetIp != null ? headsetIp : "default");
    }
}
