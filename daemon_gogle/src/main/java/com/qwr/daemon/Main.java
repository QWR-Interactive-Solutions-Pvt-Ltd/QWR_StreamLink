package com.qwr.daemon;

import android.graphics.Point;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Daemon entry point — launched via:
 * adb shell CLASSPATH=/data/local/tmp/qwr-daemon.jar app_process / com.qwr.daemon.Main
 *
 * Runs as a standalone process, survives app force-stop via cgroup escape.
 * Captures the display via SurfaceControl hidden APIs and streams JPEG frames
 * over TCP port 6776.
 */
public class Main {

    private static final String TAG = "QwrDaemon";

    // Defaults matching StreamValues in base module
    private static final int DEFAULT_QUALITY = 30;
    private static final int DEFAULT_RESIZE_PERCENT = 30;
    private static final int DEFAULT_PORT = 6776;
    private static final int DEFAULT_DISCOVERY_PORT = 8505;
    private static final int DEFAULT_CONTROL_PORT = 6779;
    private static final int DEFAULT_DELAY_MS = 33; // ~30 FPS — balanced for prolonged use alongside VR apps
    private static final String PID_FILE = "/data/local/tmp/qwr-daemon.pid";

    public static void main(String[] args) {
        println("Starting QWR daemon (pid=" + android.os.Process.myPid() + ")");

        // Parse command-line args: key=value pairs
        int quality = DEFAULT_QUALITY;
        int resizePercent = DEFAULT_RESIZE_PERCENT;
        int port = DEFAULT_PORT;
        int discoveryPort = DEFAULT_DISCOVERY_PORT;
        int delayMs = DEFAULT_DELAY_MS;
        String deviceName = getDeviceModel();

        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            if (parts.length != 2) continue;
            String key = parts[0].trim();
            String val = parts[1].trim();
            switch (key) {
                case "quality":
                    quality = Integer.parseInt(val);
                    break;
                case "resize":
                    resizePercent = Integer.parseInt(val);
                    break;
                case "port":
                    port = Integer.parseInt(val);
                    break;
                case "discovery_port":
                    discoveryPort = Integer.parseInt(val);
                    break;
                case "delay":
                    delayMs = Integer.parseInt(val);
                    break;
                case "name":
                    deviceName = val;
                    break;
            }
        }

        println("Config: quality=" + quality + " resize=" + resizePercent + "% port=" + port
                + " delay=" + delayMs + "ms name=" + deviceName);

        // Get display dimensions
        Point displaySize = DisplayInfo.getDisplaySize(0);
        println("Display size: " + displaySize.x + "x" + displaySize.y);

        // Calculate capture dimensions (apply resize percent)
        int captureWidth = Math.round(displaySize.x * (resizePercent / 100f));
        int captureHeight = Math.round(displaySize.y * (resizePercent / 100f));
        println("Capture size: " + captureWidth + "x" + captureHeight);

        // Create screen capture pipeline
        ScreenCapture screenCapture = new ScreenCapture(
                displaySize.x, displaySize.y,
                captureWidth, captureHeight,
                quality, delayMs);

        // Read ADB serial number
        String deviceSerial = getDeviceSerial();
        println("Device serial: " + deviceSerial);

        // Create TCP streaming server
        StreamServer streamServer = new StreamServer(port, discoveryPort, deviceName, deviceSerial, screenCapture);

        // Start capture and streaming
        screenCapture.start();
        streamServer.start();

        // Write PID file so the controller app can find us
        writePidFile();

        // Start control port listener (for stop commands from the controller app)
        startControlListener(DEFAULT_CONTROL_PORT, screenCapture, streamServer);

        println("Daemon running — streaming on port " + port + ", control on port " + DEFAULT_CONTROL_PORT);

        // Keep the main thread alive — all work runs on daemon threads
        // (capture thread, stream thread, discovery thread, control thread).
        // The control listener calls System.exit(0) on a stop command.
        try {
            Object lock = new Object();
            synchronized (lock) {
                lock.wait();
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static void writePidFile() {
        try {
            FileWriter fw = new FileWriter(PID_FILE);
            fw.write(String.valueOf(android.os.Process.myPid()));
            fw.close();
            println("PID file written: " + PID_FILE);
        } catch (Exception e) {
            println("Failed to write PID file: " + e.getMessage());
        }
    }

    private static void startControlListener(int controlPort,
                                             ScreenCapture screenCapture,
                                             StreamServer streamServer) {
        new Thread(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(controlPort);
                println("Control listener on port " + controlPort);

                while (true) {
                    Socket client = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream()));
                    String command = reader.readLine();

                    if ("mode".equals(command)) {
                        String mode = screenCapture.getMode();
                        client.getOutputStream().write((mode + "\n").getBytes());
                        client.getOutputStream().flush();
                        client.close();
                        continue;
                    }
                    client.close();

                    if ("stop".equals(command)) {
                        println("Received stop command — shutting down");
                        screenCapture.stop();
                        streamServer.stop();
                        System.exit(0);
                    } else if (command != null && command.startsWith("quality=")) {
                        try {
                            int q = Integer.parseInt(command.substring(8).trim());
                            q = Math.max(1, Math.min(100, q));
                            screenCapture.setJpegQuality(q);
                            println("JPEG quality changed to " + q);
                        } catch (NumberFormatException e) {
                            println("Invalid quality value: " + command);
                        }
                    } else if (command != null && command.startsWith("delay=")) {
                        try {
                            int d = Integer.parseInt(command.substring(6).trim());
                            d = Math.max(1, d);
                            screenCapture.setDelayMs(d);
                            println("Frame delay changed to " + d + "ms (~" + (1000 / d) + " FPS)");
                        } catch (NumberFormatException e) {
                            println("Invalid delay value: " + command);
                        }
                    } else {
                        println("Unknown control command: " + command);
                    }
                }
            } catch (Exception e) {
                println("Control listener error: " + e.getMessage());
            }
        }, "ControlThread").start();
    }

    private static String getDeviceModel() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"getprop", "ro.product.model"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String model = reader.readLine();
            proc.waitFor();
            if (model != null && !model.trim().isEmpty()) {
                return model.trim();
            }
        } catch (Exception e) {
            println("Failed to read model: " + e.getMessage());
        }
        return "gogle";
    }

    private static String getDeviceSerial() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"getprop", "ro.serialno"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String serial = reader.readLine();
            proc.waitFor();
            if (serial != null && !serial.trim().isEmpty()) {
                return serial.trim();
            }
        } catch (Exception e) {
            println("Failed to read serial: " + e.getMessage());
        }
        return "unknown";
    }

    static void println(String msg) {
        System.out.println("[QwrDaemon] " + msg);
        android.util.Log.i(TAG, msg);
    }
}
