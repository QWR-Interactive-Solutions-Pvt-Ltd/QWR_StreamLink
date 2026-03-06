package com.qwr.daemon;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * TCP streaming server that sends JPEG frames to connected clients (tablet/mobile).
 *
 * Protocol on port 6776 (matching existing UpstreamService/DownstreamService):
 *   [4-byte big-endian int: JPEG data length][JPEG byte array]
 *
 * Discovery on port 8505 (matching existing AddressSenderService):
 *   [4-byte big-endian int: device name length][device name bytes]
 */
public class
StreamServer {

    private static final int RECONNECT_MS = 1000;
    private static final int SOCKET_TIMEOUT_MS = 8000;

    /** How often to send the UDP discovery broadcast. */
    private static final int UDP_BROADCAST_INTERVAL_MS = 3000;

    private final int streamPort;
    private final int discoveryPort;
    private final String deviceName;
    private final String deviceSerial;
    private final ScreenCapture screenCapture;

    private volatile boolean running = false;
    private ServerSocket streamServerSocket;
    private ServerSocket discoveryServerSocket;
    private DatagramSocket udpBroadcastSocket;

    public StreamServer(int streamPort, int discoveryPort, String deviceName,
                        String deviceSerial, ScreenCapture screenCapture) {
        this.streamPort = streamPort;
        this.discoveryPort = discoveryPort;
        this.deviceName = deviceName;
        this.deviceSerial = deviceSerial;
        this.screenCapture = screenCapture;
    }

    /**
     * Starts the streaming and discovery threads.
     */
    public void start() {
        running = true;
        new Thread(this::streamLoop, "StreamThread").start();
        new Thread(this::discoveryLoop, "DiscoveryThread").start();
        new Thread(this::udpBroadcastLoop, "UdpBroadcastThread").start();
        Main.println("StreamServer: started on port=" + streamPort + " discovery=" + discoveryPort
                + " udp_broadcast_interval=" + UDP_BROADCAST_INTERVAL_MS + "ms");
    }

    /**
     * Main streaming loop — accepts TCP connections and sends JPEG frames.
     */
    private void streamLoop() {
        try {
            streamServerSocket = new ServerSocket(streamPort);
            Main.println("StreamServer: listening on port " + streamPort);

            while (running) {
                Main.println("StreamServer: waiting for client connection...");
                Socket client = streamServerSocket.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                Main.println("StreamServer: client connected from " + client.getRemoteSocketAddress());

                try {
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    long sentFrames = 0;
                    long lastSentLogTime = System.currentTimeMillis();
                    long emptyPolls = 0;

                    while (running && !client.isClosed()) {
                        byte[] frame = screenCapture.popFrame();

                        if (frame == null) {
                            continue;
                        }

                        // Write frame: [int length][JPEG data]
                        out.writeInt(frame.length);
                        out.write(frame);
                        out.flush();

                        sentFrames++;
                        long now = System.currentTimeMillis();
                        if (sentFrames == 1 || now - lastSentLogTime > 5000) {
                            Main.println("StreamServer: sent frame #" + sentFrames
                                    + " size=" + frame.length + " bytes to " + client.getRemoteSocketAddress());
                            lastSentLogTime = now;
                        }
                    }
                } catch (IOException e) {
                    Main.println("StreamServer: client write error — " + e.getMessage());
                } finally {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }

                Main.println("StreamServer: client disconnected, waiting to reconnect...");
                try {
                    Thread.sleep(RECONNECT_MS);
                } catch (InterruptedException ignored) {
                }
            }
        } catch (IOException e) {
            Main.println("StreamServer: server socket error — " + e.getMessage());
        }
        Main.println("StreamServer: stream loop exited");
    }

    /**
     * Discovery loop — responds to connections on port 8505 with device name.
     * Matching the protocol used by AddressSenderService in the base module.
     */
    private void discoveryLoop() {
        try {
            discoveryServerSocket = new ServerSocket(discoveryPort);
            Main.println("StreamServer: discovery listening on port " + discoveryPort);

            while (running) {
                try {
                    Socket client = discoveryServerSocket.accept();
                    Main.println("StreamServer: discovery request from " + client.getRemoteSocketAddress());

                    byte[] nameBytes = deviceName.getBytes();
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    out.writeInt(nameBytes.length);
                    out.write(nameBytes);
                    out.flush();
                    client.close();
                } catch (IOException e) {
                    if (running) {
                        Main.println("StreamServer: discovery error — " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Main.println("StreamServer: discovery server socket error — " + e.getMessage());
        }
        Main.println("StreamServer: discovery loop exited");
    }

    /**
     * Periodically broadcasts a UDP discovery packet so that clients using the WiFi
     * discovery path (AddressDiscoveryService) can locate this headset automatically.
     *
     * Packet format: "QWR_STREAMLINK|<ip>|<streamPort>|<deviceName>|<serial>"
     * Destination:   255.255.255.255 : discoveryPort (8505)
     *
     * The receiver (AddressDiscoveryService.parseUdpBroadcast) uses:
     *   parts[1] = IP   — falls back to UDP sender IP if "0.0.0.0"
     *   parts[3] = device name
     */
    private void udpBroadcastLoop() {
        Main.println("StreamServer: UDP broadcast starting on discovery port " + discoveryPort);

        try {
            udpBroadcastSocket = new DatagramSocket();
            udpBroadcastSocket.setBroadcast(true);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");

            String ip = getLocalIp();
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // Re-read IP only if not yet resolved (e.g. WiFi not up at boot)
                    if ("0.0.0.0".equals(ip)) {
                        ip = getLocalIp();
                    }
                    String message = "QWR_STREAMLINK|" + ip + "|" + streamPort + "|" + deviceName + "|" + deviceSerial;
                    byte[] data = message.getBytes();
                    DatagramPacket packet = new DatagramPacket(
                            data, data.length, broadcastAddr, discoveryPort);
                    udpBroadcastSocket.send(packet);
                    Main.println("StreamServer: UDP broadcast sent → 255.255.255.255:" + discoveryPort + " payload=\"" + message + "\"");
                } catch (IOException e) {
                    if (running) {
                        Main.println("StreamServer: UDP send error — " + e.getMessage());
                    }
                }

                try {
                    Thread.sleep(UDP_BROADCAST_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            Main.println("StreamServer: UDP broadcast socket error — " + e.getMessage());
        } finally {
            if (udpBroadcastSocket != null && !udpBroadcastSocket.isClosed()) {
                udpBroadcastSocket.close();
            }
        }
        Main.println("StreamServer: UDP broadcast loop exited");
    }

    /**
     * Returns the first non-loopback IPv4 address of this device.
     * Falls back to "0.0.0.0" — the receiver uses the UDP packet's source IP in that case.
     */
    private static String getLocalIp() {
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
            Main.println("StreamServer: getLocalIp error — " + e.getMessage());
        }
        return "0.0.0.0";
    }

    /**
     * Stops the server and closes all sockets.
     */
    public void stop() {
        Main.println("StreamServer: stopping");
        running = false;

        try {
            if (streamServerSocket != null) streamServerSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (discoveryServerSocket != null) discoveryServerSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (udpBroadcastSocket != null) udpBroadcastSocket.close();
        } catch (Exception ignored) {
        }

        Main.println("StreamServer: stopped");
    }
}
