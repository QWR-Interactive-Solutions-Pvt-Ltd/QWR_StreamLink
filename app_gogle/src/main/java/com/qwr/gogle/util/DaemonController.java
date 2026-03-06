package com.qwr.gogle.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * Controls the QWR streaming daemon lifecycle.
 * Since the app runs as UID 1000 (system app), we can execute shell commands
 * directly via Runtime.exec() — no Shizuku needed.
 *
 * The daemon runs as a separate app_process (UID 1000) and escapes
 * the app's cgroup so it survives forceStopPackage/killProcessGroup.
 */
public class DaemonController {

    private static final String TAG = "DaemonController";

    private static final int CONTROL_PORT = 6779;
    private static final String DAEMON_CLASS = "com.qwr.daemon.Main";
    private static String daemonJarPath = null;

    /**
     * Installs the daemon JAR from app assets to the app's files directory
     * and makes it world-readable so the shell-UID daemon process can load it.
     */
    public static boolean installDaemonJar(Context context) {
        try {
            File jarFile = new File(context.getFilesDir(), "qwr-daemon.jar");
            try (InputStream is = context.getAssets().open("qwr-daemon.jar");
                 FileOutputStream fos = new FileOutputStream(jarFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                fos.flush();
            }

            // Make the JAR and its parent dirs world-readable/executable
            // so the shell-UID app_process can access it
            jarFile.setReadable(true, false);
            context.getFilesDir().setExecutable(true, false);
            context.getFilesDir().setReadable(true, false);
            File dataDir = context.getFilesDir().getParentFile();
            if (dataDir != null) {
                dataDir.setExecutable(true, false);
                dataDir.setReadable(true, false);
            }

            daemonJarPath = jarFile.getAbsolutePath();
            Log.i(TAG, "Daemon JAR installed to " + daemonJarPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "installDaemonJar failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the daemon JAR is installed.
     */
    public static boolean isJarInstalled(Context context) {
        File jarFile = new File(context.getFilesDir(), "qwr-daemon.jar");
        if (jarFile.exists()) {
            daemonJarPath = jarFile.getAbsolutePath();
            return true;
        }
        return false;
    }

    /**
     * Checks if the installed JAR differs from the one bundled in assets.
     * Compares file sizes — different size means the JAR needs updating.
     */
    public static boolean isJarOutdated(Context context) {
        File jarFile = new File(context.getFilesDir(), "qwr-daemon.jar");
        if (!jarFile.exists()) return false; // not installed yet, not "outdated"
        try {
            byte[] assetHash = hashStream(context.getAssets().open("qwr-daemon.jar"));
            byte[] installedHash = hashStream(new java.io.FileInputStream(jarFile));
            boolean outdated = !java.util.Arrays.equals(assetHash, installedHash);
            if (outdated) {
                Log.i(TAG, "JAR outdated: hash mismatch");
            }
            return outdated;
        } catch (Exception e) {
            Log.e(TAG, "isJarOutdated check failed: " + e.getMessage());
            return false;
        }
    }

    private static byte[] hashStream(InputStream is) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int len;
        try {
            while ((len = is.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
        } finally {
            is.close();
        }
        return md.digest();
    }

    /**
     * Checks if the daemon is running by trying to connect to the control port.
     */
    public static boolean isDaemonRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", CONTROL_PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts the daemon via app_process.
     * Runs as a detached process (setsid) and escapes the app's cgroup
     * so it survives forceStopPackage.
     */
    public static boolean startDaemon() {
        if (isDaemonRunning()) {
            Log.i(TAG, "Daemon already running");
            return true;
        }

        if (daemonJarPath == null) {
            Log.e(TAG, "Daemon JAR path not set — call installDaemonJar first");
            return false;
        }

        try {
            Log.i(TAG, "Starting daemon via app_process, jar=" + daemonJarPath);
            String[] cmd = {
                    "sh", "-c",
                    "CLASSPATH=" + daemonJarPath
                            + " setsid app_process / " + DAEMON_CLASS
                            + " </dev/null > /dev/null 2>&1 &"
            };
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
            Log.i(TAG, "Daemon start command sent");

            // Wait briefly for daemon to initialize
            Thread.sleep(1000);

            boolean running = isDaemonRunning();
            Log.i(TAG, "Daemon running after start: " + running);

            if (running) {
                escapeCgroup();
            }

            return running;
        } catch (Exception e) {
            Log.e(TAG, "startDaemon failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Moves the daemon process out of the app's cgroup into the root cgroup.
     * This prevents killProcessGroup() from killing the daemon when the app
     * is force-stopped by the VR launcher.
     */
    private static void escapeCgroup() {
        try {
            // Find daemon PID via procfs
            Process pidProc = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "pgrep -f " + DAEMON_CLASS});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pidProc.getInputStream()));
            String pid = reader.readLine();
            pidProc.waitFor();

            if (pid != null && !pid.trim().isEmpty()) {
                pid = pid.trim();
                // Move daemon to root cgroup so killProcessGroup won't find it
                Process moveProc = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c",
                                "echo " + pid + " > /sys/fs/cgroup/cgroup.procs"});
                moveProc.waitFor();
                Log.i(TAG, "Daemon PID " + pid + " moved to root cgroup");
            } else {
                Log.w(TAG, "Could not find daemon PID for cgroup escape");
            }
        } catch (Exception e) {
            Log.w(TAG, "Cgroup escape failed: " + e.getMessage());
        }
    }

    /**
     * Sends a command to the daemon's control port.
     * Commands: "stop", "quality=N", "delay=N"
     * Must be called from a background thread.
     */
    public static void sendCommand(String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", CONTROL_PORT), 500);
            socket.getOutputStream().write((command + "\n").getBytes());
            socket.getOutputStream().flush();
            Log.i(TAG, "Control command sent: " + command);
        } catch (Exception e) {
            Log.e(TAG, "sendCommand failed: " + e.getMessage());
        }
    }

    /**
     * Stops the daemon via control port command, with pkill fallback.
     */
    public static void stopDaemon() {
        // Try control port first (clean shutdown)
        try {
            Log.i(TAG, "Stopping daemon via control port");
            Socket socket = new Socket("127.0.0.1", CONTROL_PORT);
            socket.getOutputStream().write("stop\n".getBytes());
            socket.getOutputStream().flush();
            socket.close();
            Log.i(TAG, "Stop command sent");
            return;
        } catch (Exception e) {
            Log.w(TAG, "Control port stop failed: " + e.getMessage());
        }

        // Fallback: pkill
        try {
            Runtime.getRuntime().exec(new String[]{"pkill", "-f", DAEMON_CLASS});
            Log.i(TAG, "pkill sent");
        } catch (Exception e) {
            Log.e(TAG, "pkill failed: " + e.getMessage());
        }
    }
}
