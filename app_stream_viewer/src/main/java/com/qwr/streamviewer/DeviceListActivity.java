package com.qwr.streamviewer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class DeviceListActivity extends AppCompatActivity
        implements AddressDiscovery, DeviceItemClickListener, DeviceListAdapter.PreviewToggleListener {

    private RecyclerView deviceRecyclerView;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView emptyText;
    private Button btnRefresh;

    private static final String TAG = "DeviceListActivity";
    private static final int PREVIEW_CONNECT_TIMEOUT_MS = 3000;
    private static final int PREVIEW_READ_TIMEOUT_MS = 5000;
    private static final int PREVIEW_REFRESH_MS = 10_000;
    private static final int REACHABILITY_CHECK_INTERVAL_MS = 5000;
    private static final int REACHABILITY_TIMEOUT_MS = 1500;

    private DeviceListAdapter adapter;
    private AddressDiscoveryService addressDiscoveryService;
    private int deviceCount = 0;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private final Handler reachabilityHandler = new Handler(Looper.getMainLooper());
    private boolean reachabilityRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);

        deviceRecyclerView = findViewById(R.id.deviceRecyclerView);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        btnRefresh = findViewById(R.id.btnRefresh);

        int columns = getResources().getInteger(R.integer.device_grid_columns);
        GridLayoutManager layoutManager = new GridLayoutManager(this, columns);
        deviceRecyclerView.setLayoutManager(layoutManager);
        deviceRecyclerView.setItemViewCacheSize(10);
        deviceRecyclerView.setItemAnimator(null);

        int spacing = getResources().getDimensionPixelSize(R.dimen.card_spacing);
        deviceRecyclerView.addItemDecoration(new GridSpacingDecoration(columns, spacing));

        adapter = new DeviceListAdapter(this, this);
        deviceRecyclerView.setAdapter(adapter);

        addressDiscoveryService = new AddressDiscoveryService(this, this);

        btnRefresh.setOnClickListener(v -> startDiscovery());

        startDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!adapter.getCurrentList().isEmpty()) {
            startReachabilityChecks();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopReachabilityChecks();
    }

    // --- Preview: fetch a live JPEG frame from the headset over TCP ---

    @Override
    public void onPreviewToggled(String ip, boolean enabled) {
        adapter.togglePreview(ip);
        if (enabled) {
            fetchPreview(ip);
        } else {
            previewHandler.removeCallbacksAndMessages(ip);
        }
    }

    /**
     * Connects to the headset's stream port, reads one JPEG frame, decodes it as a
     * thumbnail bitmap, and pushes it to the adapter. Schedules itself to re-run
     * every PREVIEW_REFRESH_MS while the preview toggle remains enabled.
     */
    private void fetchPreview(String ip) {
        new Thread(() -> {
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, PortValues.STREAM), PREVIEW_CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(PREVIEW_READ_TIMEOUT_MS);

                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = in.readInt();

                // Sanity-check length before allocating (5 MB upper bound)
                if (length > 0 && length < 5 * 1024 * 1024) {
                    byte[] data = new byte[length];
                    in.readFully(data);

                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            if (adapter.isPreviewEnabled(ip)) {
                                adapter.setPreview(ip, bmp);
                            } else {
                                bmp.recycle();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Preview fetch failed for " + ip + ": " + e.getMessage());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }

            // Schedule next refresh if preview is still enabled
            previewHandler.postDelayed(() -> {
                if (adapter.isPreviewEnabled(ip)) {
                    fetchPreview(ip);
                }
            }, PREVIEW_REFRESH_MS);
        }, "PreviewFetch-" + ip).start();
    }

    // --- Reachability checks ---

    private void startReachabilityChecks() {
        if (reachabilityRunning) return;
        reachabilityRunning = true;
        reachabilityHandler.post(reachabilityRunnable); // run immediately, then every REACHABILITY_CHECK_INTERVAL_MS
    }

    private void stopReachabilityChecks() {
        reachabilityRunning = false;
        reachabilityHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable reachabilityRunnable = new Runnable() {
        @Override
        public void run() {
            if (!reachabilityRunning) return;

            // Only skip the TCP probe when frames are actually flowing (STREAMING).
            // IS_STREAMING stays true during reconnect attempts, so it is not a reliable
            // indicator. STREAM_STATUS flips to WAITING/NOT_CONNECTED the moment the
            // connection drops, which correctly allows the probe to run and turn the icon red.
            String activeStreamIp = (DownstreamService.STREAM_STATUS == StreamStatus.STREAMING)
                    ? StorageOperations.readStringFromSharedPreferences(
                            getApplicationContext(), StorageOperations.GOGLE_ADDRESS_IP_KEY)
                    : null;

            java.util.List<DeviceItem> devices = adapter.getCurrentList();
            for (DeviceItem device : devices) {
                String ip = device.getAddressIp();

                if (ip.equals(activeStreamIp)) {
                    runOnUiThread(() -> adapter.updateDeviceStatus(ip, DeviceStatus.CONNECTED));
                    continue;
                }

                new Thread(() -> {
                    boolean reachable = false;
                    Socket socket = null;
                    try {
                        socket = new Socket();
                        socket.connect(new InetSocketAddress(ip, PortValues.STREAM), REACHABILITY_TIMEOUT_MS);
                        reachable = true;
                    } catch (Exception ignored) {
                    } finally {
                        if (socket != null) {
                            try { socket.close(); } catch (Exception ignored) {}
                        }
                    }
                    DeviceStatus newStatus = reachable ? DeviceStatus.CONNECTED : DeviceStatus.DISCONNECTED;
                    runOnUiThread(() -> adapter.updateDeviceStatus(ip, newStatus));
                }, "Reachability-" + ip).start();
            }

            reachabilityHandler.postDelayed(this, REACHABILITY_CHECK_INTERVAL_MS);
        }
    };

    // --- Discovery ---

    private void startDiscovery() {
        stopReachabilityChecks();
        deviceCount = 0;
        adapter.clear();
        showSearching();

        boolean isHotspot = isAccessPointActive();
        addressDiscoveryService.search(isHotspot ? AddressDiscoveryType.HOTSPOT : AddressDiscoveryType.WIFI);
    }

    @Override
    public void deviceFound(String name, String addressIP, String serial) {
        runOnUiThread(() -> {
            deviceCount++;
            adapter.addDevice(new DeviceItem(name, addressIP, serial, DeviceStatus.CONNECTED));
            showDeviceList();
            startReachabilityChecks();
        });
    }

    @Override
    public void deviceNotFound() {
        runOnUiThread(() -> {
            if (deviceCount == 0) {
                showEmpty();
            }
        });
    }

    @Override
    public void onDeviceClicked(DeviceItem item) {
        connectToDevice(item.getAddressIp());
    }

    private void connectToDevice(String ip) {
        StorageOperations.writeToSharedPreferences(
                this, StorageOperations.GOGLE_ADDRESS_IP_KEY, ip);
        startActivity(new Intent(this, StreamActivity.class));
    }

    // --- UI state helpers ---

    private void showSearching() {
        progressBar.setVisibility(View.VISIBLE);
        deviceRecyclerView.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
        btnRefresh.setVisibility(View.GONE);
        statusText.setText(R.string.searching_for_headsets);
    }

    private void showDeviceList() {
        progressBar.setVisibility(View.GONE);
        deviceRecyclerView.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        btnRefresh.setVisibility(View.VISIBLE);
        statusText.setText(getString(R.string.devices_found, deviceCount));
    }

    private void showEmpty() {
        progressBar.setVisibility(View.GONE);
        deviceRecyclerView.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        btnRefresh.setVisibility(View.VISIBLE);
        statusText.setText(R.string.no_devices_found);
    }

    /** Reflection-based check for whether the device is acting as a Wi-Fi hotspot/AP. */
    private boolean isAccessPointActive() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        stopReachabilityChecks();
        previewHandler.removeCallbacksAndMessages(null);
        adapter.recycleAllPreviews();
        deviceRecyclerView.setAdapter(null);
        super.onDestroy();
    }
}
