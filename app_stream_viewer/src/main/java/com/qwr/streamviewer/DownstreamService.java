package com.qwr.streamviewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class DownstreamService extends Service {

    private static final String TAG = "DownstreamService";

    // Notification
    private static final int STREAM_NOTIFY_ID = 1000;
    private static final String NOTIFICATION_CHANNEL_ID = "com.qwr.streamviewer";
    private static final String NOTIFICATION_CHANNEL_NAME = "StreamLink service";

    public static StreamStatus STREAM_STATUS = StreamStatus.NOT_CONNECTED;
    public static boolean IS_RUNNING = false;
    public static boolean IS_STREAMING = false;

    private static Socket clientDownstreamSocket;
    private Socket clientFpsSocket;

    private List<byte[]> bytesReceivedStream = new ArrayList<>();

    private long startFpsTime;
    private int countFpsFrames = 0;
    private int lastFrameSize = 0;

    private StreamViewerApplication app;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotify(R.string.app_name, R.drawable.ic_notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        app = (StreamViewerApplication) getApplication();

        // Reset all state so a restart works identically to the first start
        IS_RUNNING = true;
        IS_STREAMING = true;
        STREAM_STATUS = StreamStatus.NOT_CONNECTED;
        clientDownstreamSocket = null;
        bytesReceivedStream.clear();
        countFpsFrames = 0;

        new Thread(downstreamRunnable).start();
        new Thread(saveAndDisplayImageRunnable).start();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (clientDownstreamSocket != null) {
            try {
                clientDownstreamSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket: " + e.getMessage());
            }
            stopStreaming();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Runnable downstreamRunnable = new Runnable() {
        @Override
        public void run() {
            String addressIP;
            byte[] stream;
            DataInputStream inStream;

            while (IS_RUNNING) {
                try {
                    if (clientDownstreamSocket == null || clientDownstreamSocket.isClosed()) {
                        addressIP = StorageOperations.readStringFromSharedPreferences(
                                getApplicationContext(), StorageOperations.GOGLE_ADDRESS_IP_KEY);
                        clientDownstreamSocket = new Socket();
                        clientDownstreamSocket.connect(
                                new InetSocketAddress(InetAddress.getByName(addressIP), PortValues.STREAM),
                                StreamValues.SOCKET_CONNECT_TIMEOUT_MS);
                        clientDownstreamSocket.setSoTimeout(StreamValues.SOCKET_TIMEOUT_MS);
                        Log.i(TAG, "Connected to headset at " + addressIP);
                    }

                    prepareStreaming();

                    while (IS_STREAMING) {
                        try {
                            inStream = new DataInputStream(clientDownstreamSocket.getInputStream());
                            int length = inStream.readInt();

                            if (length > 0) {
                                stream = new byte[length];
                                inStream.readFully(stream, 0, stream.length);
                                lastFrameSize = length;

                                if (bytesReceivedStream.size() < StreamValues.MAX_IMAGES_IN_QUEUE) {
                                    synchronized (bytesReceivedStream) {
                                        bytesReceivedStream.add(stream);
                                    }
                                }

                                if (StreamStatus.ENDED_STREAM == STREAM_STATUS) {
                                    clientDownstreamSocket.close();
                                    stopStreaming();
                                    return;
                                }
                            }

                        } catch (IOException e) {
                            Log.e(TAG, "Error while streaming: " + e.getMessage());
                            clientDownstreamSocket.close();
                            app.setStreamFrame(null);
                            break;
                        }
                    }

                } catch (IOException e) {
                    if (clientDownstreamSocket != null) {
                        try { clientDownstreamSocket.close(); } catch (IOException ignored) {}
                        clientDownstreamSocket = null;
                    }
                    if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
                        if (StreamStatus.ENDED_STREAM == STREAM_STATUS) {
                            stopStreaming();
                            return;
                        } else if (StreamStatus.STREAMING == STREAM_STATUS) {
                            STREAM_STATUS = StreamStatus.WAITING;
                        } else if (StreamStatus.WAITING != STREAM_STATUS) {
                            STREAM_STATUS = StreamStatus.NOT_CONNECTED;
                        }
                        app.setStreamFrame(null);
                    } else {
                        Log.e(TAG, "Socket error: " + e.getMessage());
                    }
                }

                ThreadUtils.sleep(StreamValues.SOCKET_RECONNECT_MS);
            }
        }
    };

    private void prepareStreaming() {
        STREAM_STATUS = StreamStatus.STREAMING;
        connectFpsSocket();
        startFpsTime = Timestamp.getUnix();
    }

    private Runnable saveAndDisplayImageRunnable = new Runnable() {
        @Override
        public void run() {
            boolean empty;
            byte[] data;
            Bitmap frame;

            while (IS_RUNNING) {
                while (IS_STREAMING) {
                    empty = false;
                    data = null;

                    synchronized (bytesReceivedStream) {
                        if (bytesReceivedStream.size() == 0) {
                            empty = true;
                        } else {
                            data = bytesReceivedStream.remove(0);
                        }
                    }

                    if (empty) {
                        ThreadUtils.sleep(StreamValues.DELAY_MS);
                        continue;
                    }

                    frame = BitmapFactory.decodeByteArray(data, 0, data.length);
                    app.setStreamFrame(frame);

                    countFps();
                }

                ThreadUtils.sleep(StreamValues.DELAY_MS);
            }
        }
    };

    private void stopStreaming() {
        STREAM_STATUS = StreamStatus.READY_TO_STREAM;
        IS_STREAMING = false;
        IS_RUNNING = false;
        if (app != null) app.setStreamFrame(null);
        stopSelf();
    }

    private void countFps() {
        countFpsFrames++;
        long endFpsTime = Timestamp.getUnix();

        if (endFpsTime - startFpsTime > 1000) {
            int fps = StreamUtils.getFps(startFpsTime, endFpsTime, countFpsFrames);
            sendFps(fps);
            app.onStreamStats(fps, lastFrameSize);
            countFpsFrames = 0;
            startFpsTime = endFpsTime;
        }
    }

    private void connectFpsSocket() {
        new Thread(() -> {
            try {
                if (clientFpsSocket != null) clientFpsSocket.close();
                String addressIP = StorageOperations.readStringFromSharedPreferences(
                        getApplicationContext(), StorageOperations.GOGLE_ADDRESS_IP_KEY);
                clientFpsSocket = new Socket(InetAddress.getByName(addressIP), PortValues.STREAM_FPS);
            } catch (IOException e) {
                Log.e(TAG, "FPS socket error: " + e.getMessage());
            }
        }).start();
    }

    private void sendFps(final int fps) {
        new Thread(() -> {
            try {
                if (clientFpsSocket == null) {
                    connectFpsSocket();
                    return;
                }
                DataOutputStream outData = new DataOutputStream(clientFpsSocket.getOutputStream());
                outData.writeInt(fps);
                outData.flush();
            } catch (IOException e) {
                Log.e(TAG, "Send FPS error: " + e.getMessage());
                try {
                    clientFpsSocket.close();
                } catch (IOException e1) {
                    Log.e(TAG, "Error closing FPS socket: " + e1.getMessage());
                } finally {
                    clientFpsSocket = null;
                }
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // Foreground notification helpers (previously in BaseService)
    // -----------------------------------------------------------------------

    private void startForegroundNotify(int titleId, int iconId, int foregroundServiceType) {
        NotificationCompat.Builder builder = buildNotification(this, iconId, titleId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted");
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(STREAM_NOTIFY_ID, builder.build(), foregroundServiceType);
        } else {
            startForeground(STREAM_NOTIFY_ID, builder.build());
        }
    }

    private static NotificationCompat.Builder buildNotification(
            Context context, int iconId, int titleId) {
        NotificationChannel chan = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(chan);

        return new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(iconId)
                .setContentTitle(context.getString(titleId))
                .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
                .setCategory(Notification.CATEGORY_SERVICE);
    }
}
