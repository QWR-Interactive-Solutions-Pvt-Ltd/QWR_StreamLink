package com.qwr.streamviewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

public class StreamValues {

    // Stream loop timing
    public static final int DELAY_MS = 40;
    public static final int MAX_IMAGES_IN_QUEUE = 3;
    public static final int SOCKET_RECONNECT_MS = 1000;
    public static final int SOCKET_CONNECT_TIMEOUT_MS = 3000;
    public static final int SOCKET_TIMEOUT_MS = 8000;
}

// ---------------------------------------------------------------------------
// Network ports
// ---------------------------------------------------------------------------
class PortValues {
    static final int STREAM = 6776;
    static final int STREAM_FPS = 6777;
    static final int GOGLE_ADDRESS_DISCOVERY = 8505;
    static final int DAEMON_CONTROL = 6779;
}

// ---------------------------------------------------------------------------
// Stream lifecycle status
// ---------------------------------------------------------------------------
enum StreamStatus {
    NOT_CONNECTED,
    READY_TO_STREAM,
    WAITING,
    STREAMING,
    ENDED_STREAM
}

// ---------------------------------------------------------------------------
// Monotonic timestamp helper (wraps SystemClock so callers stay testable)
// ---------------------------------------------------------------------------
class Timestamp {
    static long getUnix() {
        return SystemClock.elapsedRealtime();
    }
}

// ---------------------------------------------------------------------------
// Thread sleep wrapper — restores interrupt flag on InterruptedException
// ---------------------------------------------------------------------------
class ThreadUtils {
    private static final String TAG = "ThreadUtils";

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Log.w(TAG, "Thread interrupted during sleep");
            Thread.currentThread().interrupt();
        }
    }
}

// ---------------------------------------------------------------------------
// FPS calculation
// ---------------------------------------------------------------------------
class StreamUtils {
    static int getFps(long startMs, long endMs, int frames) {
        return (int) ((float) frames / (float) (endMs - startMs) * 1000f);
    }
}

// ---------------------------------------------------------------------------
// SharedPreferences persistence for the selected headset IP
// ---------------------------------------------------------------------------
class StorageOperations {

    static final String SHARED_PREFS_NAME = "remmed_prefs";
    static final String GOGLE_ADDRESS_IP_KEY = "gogle_address_ip_key";
    static final String STREAM_QUALITY_KEY = "stream_quality";
    static final String STREAM_FPS_DELAY_KEY = "stream_fps_delay";

    static String readStringFromSharedPreferences(Context context, String key) {
        SharedPreferences settings = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        return settings.getString(key, "");
    }

    static void writeToSharedPreferences(Context context, String key, String value) {
        SharedPreferences settings = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        settings.edit().putString(key, value).apply();
    }

    static int readIntFromSharedPreferences(Context context, String key, int defaultValue) {
        SharedPreferences settings = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        return settings.getInt(key, defaultValue);
    }

    static void writeIntToSharedPreferences(Context context, String key, int value) {
        SharedPreferences settings = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
        settings.edit().putInt(key, value).apply();
    }
}
