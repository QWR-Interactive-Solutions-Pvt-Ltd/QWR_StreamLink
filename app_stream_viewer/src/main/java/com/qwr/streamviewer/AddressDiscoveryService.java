package com.qwr.streamviewer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;

/**
 * Discovers headsets on the local network.
 *
 * Listens for UDP broadcast packets from the daemon on port 8505.
 * The daemon broadcasts: "QWR_STREAMLINK|IP|PORT|deviceName|serial"
 *
 * HOTSPOT mode is not supported — passes through without scanning.
 */
public class AddressDiscoveryService {

    private static final String TAG = "AddressDiscoveryService";

    private static final String UDP_BROADCAST_PREFIX = "QWR_STREAMLINK";
    private static final int UDP_LISTEN_TIMEOUT_MS = 4000;
    private static final int UDP_BUFFER_SIZE = 1024;

    private volatile boolean deviceFound;

    private final Context context;
    private final AddressDiscovery addressDiscovery;

    private Thread thread;

    public AddressDiscoveryService(Context context, AddressDiscovery addressDiscovery) {
        this.context = context;
        this.addressDiscovery = addressDiscovery;
    }

    public void search(final AddressDiscoveryType type) {
        if (thread != null) thread.interrupt();
        thread = new Thread(() -> {
            deviceFound = false;
            Log.i(TAG, "Starting address discovery for type: " + type);

            if (type == AddressDiscoveryType.WIFI) {
                searchViaUdpBroadcast();
            } else {
                searchViaHotspot();
            }

            if (!deviceFound) {
                Log.i(TAG, "No device found after all attempts.");
                addressDiscovery.deviceNotFound();
            }
        });
        thread.setName("AddressDiscoveryThread");
        thread.start();
    }

    private void searchViaUdpBroadcast() {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = wifiManager.createMulticastLock("AddressDiscoveryLock");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();

        DatagramSocket socket = null;
        Set<String> seenIps = new HashSet<>();
        try {
            socket = new DatagramSocket(PortValues.GOGLE_ADDRESS_DISCOVERY);
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
            socket.setSoTimeout(UDP_LISTEN_TIMEOUT_MS);

            byte[] buffer = new byte[UDP_BUFFER_SIZE];

            // Listen continuously for UDP_LISTEN_TIMEOUT_MS. The socket timeout
            // triggers the end. Multiple headsets broadcasting at different times
            // are all discovered within the window.
            long deadline = System.currentTimeMillis() + UDP_LISTEN_TIMEOUT_MS;
            while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < deadline) {
                try {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    socket.setSoTimeout((int) remaining);

                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength()).trim();
                    parseUdpBroadcast(message, packet.getAddress().getHostAddress(), seenIps);
                } catch (SocketTimeoutException e) {
                    Log.d(TAG, "UDP listen timeout — discovery window ended");
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "UDP receive error: " + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to open UDP socket on port "
                    + PortValues.GOGLE_ADDRESS_DISCOVERY + ": " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
            multicastLock.release();
        }
    }

    private void parseUdpBroadcast(String message, String senderIp, Set<String> seenIps) {
        if (!message.startsWith(UDP_BROADCAST_PREFIX)) {
            return;
        }

        String[] parts = message.split("\\|");
        if (parts.length < 4) {
            Log.w(TAG, "Malformed UDP broadcast (expected at least 4 parts): " + message);
            return;
        }

        String ip = parts[1];
        String deviceName = parts[3];
        String serial = parts.length >= 5 ? parts[4] : "N/A";

        if (ip == null || ip.isEmpty() || "0.0.0.0".equals(ip)) {
            ip = senderIp;
        }

        if (seenIps.contains(ip)) {
            return;
        }
        seenIps.add(ip);

        Log.i(TAG, "Device found via UDP: " + deviceName + " at " + ip + " serial=" + serial);
        addressDiscovery.deviceFound(deviceName, ip, serial);
        deviceFound = true;
    }

    private void searchViaHotspot() {
        Log.d(TAG, "Hotspot scan not supported on this build.");
        // Legacy TCP subnet scan removed — HOTSPOT mode requires adb/manual connection.
    }
}

// ---------------------------------------------------------------------------
// Transport type used when starting a discovery search
// ---------------------------------------------------------------------------
enum AddressDiscoveryType {
    WIFI,
    HOTSPOT
}

// ---------------------------------------------------------------------------
// Callback delivered by AddressDiscoveryService on the calling thread
// ---------------------------------------------------------------------------
interface AddressDiscovery {
    void deviceFound(String name, String addressIP, String serial);
    void deviceNotFound();
}
