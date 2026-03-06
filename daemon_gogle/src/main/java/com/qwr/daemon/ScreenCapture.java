package com.qwr.daemon;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Hybrid screen capture: tries virtual display (ImageReader) first for maximum FPS,
 * auto-falls back to SurfaceControl.screenshot() polling if virtual display produces
 * too few frames (e.g. YVR headsets where ATW bypasses SurfaceFlinger GPU composition).
 *
 * Virtual display path:  SurfaceControl.createDisplay() -> ImageReader -> Image -> Bitmap -> JPEG -> queue
 * Screenshot path:       SurfaceControl.screenshot() polling -> Bitmap -> JPEG -> queue
 */
public class ScreenCapture {

    private static final int MAX_JPEG_QUEUE = 3;
    private static final int MAX_RAW_QUEUE = 2;

    /** How long to wait before checking if virtual display is producing enough frames. */
    private static final long VD_EVAL_PERIOD_MS = 4000;
    /** Minimum frames expected during eval period to keep virtual display. */
    private static final int VD_MIN_FRAMES = 8;

    private final int displayWidth;
    private final int displayHeight;
    private final int captureWidth;
    private final int captureHeight;
    private volatile int jpegQuality;
    private volatile int delayMs;

    private volatile boolean running = false;

    // JPEG frame queue consumed by StreamServer
    private final Queue<byte[]> frameQueue = new LinkedList<>();
    private long frameCount = 0;
    private long lastLogTime = 0;

    // --- Virtual display state ---
    private ImageReader imageReader;
    private IBinder vdDisplayToken;
    private HandlerThread handlerThread;
    private Method destroyDisplay;
    // Reusable objects to avoid per-frame allocation in VD mode
    private Bitmap vdBitmap;
    private ByteArrayOutputStream vdJpegStream;

    // --- Screenshot polling state ---
    private Thread captureThread;
    private Thread compressThread;
    private final Queue<Bitmap> rawQueue = new LinkedList<>();
    private Method screenshotMethod;
    private IBinder ssDisplayToken;
    private boolean screenshotResolved = false;

    private enum CaptureMode { VIRTUAL_DISPLAY, SCREENSHOT_POLLING }
    private volatile CaptureMode mode;

    public ScreenCapture(int displayWidth, int displayHeight,
                         int captureWidth, int captureHeight,
                         int jpegQuality, int delayMs) {
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.captureWidth = captureWidth;
        this.captureHeight = captureHeight;
        this.jpegQuality = jpegQuality;
        this.delayMs = delayMs;
    }

    /**
     * Starts capture: tries virtual display first, evaluates FPS, falls back if needed.
     */
    public void start() {
        running = true;

        boolean vdStarted = tryStartVirtualDisplay();
        if (vdStarted) {
            mode = CaptureMode.VIRTUAL_DISPLAY;
            Main.println("ScreenCapture: started with VIRTUAL_DISPLAY mode");
            // Start evaluation thread to check if VD is producing enough frames
            new Thread(this::evaluateVirtualDisplay, "VDEvalThread").start();
        } else {
            Main.println("ScreenCapture: virtual display failed, starting screenshot polling");
            startScreenshotPolling();
        }
    }

    // =========================================================================
    // Virtual Display path
    // =========================================================================

    private boolean tryStartVirtualDisplay() {
        Main.println("ScreenCapture: trying virtual display " + captureWidth + "x" + captureHeight
                + " quality=" + jpegQuality);
        try {
            handlerThread = new HandlerThread("ScreenCaptureThread");
            handlerThread.start();
            Handler handler = new Handler(handlerThread.getLooper());

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::processImage, handler);

            createVirtualDisplay(imageReader.getSurface());

            return true;
        } catch (Exception e) {
            Main.println("ScreenCapture: virtual display setup failed — " + e.getMessage());
            cleanupVirtualDisplay();
            return false;
        }
    }

    private void createVirtualDisplay(Surface surface) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");

        Method createDisplay = sc.getDeclaredMethod("createDisplay", String.class, boolean.class);
        Method openTransaction = sc.getDeclaredMethod("openTransaction");
        Method closeTransaction = sc.getDeclaredMethod("closeTransaction");
        Method setDisplaySurface = sc.getDeclaredMethod("setDisplaySurface", IBinder.class, Surface.class);
        Method setDisplayProjection = sc.getDeclaredMethod("setDisplayProjection",
                IBinder.class, int.class, Rect.class, Rect.class);
        Method setDisplayLayerStack = sc.getDeclaredMethod("setDisplayLayerStack", IBinder.class, int.class);
        destroyDisplay = sc.getDeclaredMethod("destroyDisplay", IBinder.class);

        vdDisplayToken = (IBinder) createDisplay.invoke(null, "qwr-capture", false);
        Main.println("ScreenCapture: SurfaceControl display created, token=" + vdDisplayToken);

        Rect sourceRect = new Rect(0, 0, displayWidth, displayHeight);
        Rect destRect = new Rect(0, 0, captureWidth, captureHeight);

        openTransaction.invoke(null);
        try {
            setDisplaySurface.invoke(null, vdDisplayToken, surface);
            setDisplayProjection.invoke(null, vdDisplayToken, 0, sourceRect, destRect);
            setDisplayLayerStack.invoke(null, vdDisplayToken, 0);
        } finally {
            closeTransaction.invoke(null);
        }

        Main.println("ScreenCapture: display configured — source=" + sourceRect + " dest=" + destRect);
    }

    private void processImage(ImageReader reader) {
        if (!running || mode != CaptureMode.VIRTUAL_DISPLAY) return;

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();

            int rowPadding = rowStride - pixelStride * captureWidth;
            int bitmapWidth = captureWidth + rowPadding / pixelStride;

            // Reuse bitmap to avoid allocation + GC every frame
            if (vdBitmap == null || vdBitmap.getWidth() != bitmapWidth || vdBitmap.getHeight() != captureHeight) {
                if (vdBitmap != null) vdBitmap.recycle();
                vdBitmap = Bitmap.createBitmap(bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888);
            }
            buffer.rewind();
            vdBitmap.copyPixelsFromBuffer(buffer);

            // Close image early to free ImageReader buffer
            image.close();
            image = null;

            Bitmap toCompress = vdBitmap;
            Bitmap cropped = null;
            if (bitmapWidth != captureWidth) {
                cropped = Bitmap.createBitmap(vdBitmap, 0, 0, captureWidth, captureHeight);
                toCompress = cropped;
            }

            // Reuse JPEG stream to avoid reallocating buffer every frame
            if (vdJpegStream == null) {
                vdJpegStream = new ByteArrayOutputStream(captureWidth * captureHeight / 4);
            } else {
                vdJpegStream.reset();
            }
            toCompress.compress(Bitmap.CompressFormat.JPEG, jpegQuality, vdJpegStream);
            if (cropped != null) cropped.recycle();

            byte[] jpegData = vdJpegStream.toByteArray();
            synchronized (frameQueue) {
                if (frameQueue.size() >= MAX_JPEG_QUEUE) {
                    frameQueue.poll();
                }
                frameQueue.add(jpegData);
                frameQueue.notifyAll();
            }

            frameCount++;
            long now = System.currentTimeMillis();
            if (frameCount == 1 || now - lastLogTime > 5000) {
                Main.println("ScreenCapture [VD]: frame #" + frameCount
                        + " JPEG=" + jpegData.length + "B queue=" + frameQueue.size());
                lastLogTime = now;
            }
        } catch (Exception e) {
            Main.println("ScreenCapture [VD]: processImage error — " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    /**
     * Waits VD_EVAL_PERIOD_MS, then checks if virtual display produced enough frames.
     * If not, switches to screenshot polling.
     */
    private void evaluateVirtualDisplay() {
        try {
            Thread.sleep(VD_EVAL_PERIOD_MS);
        } catch (InterruptedException e) {
            return;
        }
        if (!running || mode != CaptureMode.VIRTUAL_DISPLAY) return;

        long frames = frameCount;
        Main.println("ScreenCapture: VD evaluation — " + frames + " frames in "
                + VD_EVAL_PERIOD_MS + "ms (min=" + VD_MIN_FRAMES + ")");

        if (frames < VD_MIN_FRAMES) {
            Main.println("ScreenCapture: VD underperforming, switching to screenshot polling");
            cleanupVirtualDisplay();
            frameCount = 0;
            lastLogTime = 0;
            startScreenshotPolling();
        } else {
            Main.println("ScreenCapture: VD performing well, keeping virtual display mode");
        }
    }

    private void cleanupVirtualDisplay() {
        if (vdDisplayToken != null && destroyDisplay != null) {
            try {
                destroyDisplay.invoke(null, vdDisplayToken);
            } catch (Exception e) {
                Main.println("ScreenCapture: error destroying display — " + e.getMessage());
            }
            vdDisplayToken = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        if (vdBitmap != null) {
            vdBitmap.recycle();
            vdBitmap = null;
        }
        vdJpegStream = null;
    }

    // =========================================================================
    // Screenshot polling path (fallback)
    // =========================================================================

    private void startScreenshotPolling() {
        mode = CaptureMode.SCREENSHOT_POLLING;
        Main.println("ScreenCapture: starting screenshot polling " + captureWidth + "x" + captureHeight
                + " quality=" + jpegQuality + " interval=" + delayMs + "ms");
        captureThread = new Thread(this::screenshotCaptureLoop, "SSCaptureThread");
        compressThread = new Thread(this::compressLoop, "CompressThread");
        captureThread.start();
        compressThread.start();
    }

    private void screenshotCaptureLoop() {
        while (running) {
            long start = System.currentTimeMillis();
            try {
                Bitmap frame = captureScreenshot();
                if (frame != null) {
                    synchronized (rawQueue) {
                        if (rawQueue.size() >= MAX_RAW_QUEUE) {
                            Bitmap dropped = rawQueue.poll();
                            if (dropped != null) dropped.recycle();
                        }
                        rawQueue.add(frame);
                        rawQueue.notifyAll();
                    }
                }
            } catch (Exception e) {
                Main.println("ScreenCapture [SS]: capture error — " + e.getMessage());
            }

            long elapsed = System.currentTimeMillis() - start;
            long sleepMs = delayMs - elapsed;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void compressLoop() {
        while (running) {
            Bitmap frame;
            synchronized (rawQueue) {
                while (rawQueue.isEmpty() && running) {
                    try {
                        rawQueue.wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frame = rawQueue.poll();
            }
            if (frame == null) continue;

            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
                frame.recycle();

                byte[] jpeg = out.toByteArray();
                synchronized (frameQueue) {
                    if (frameQueue.size() >= MAX_JPEG_QUEUE) {
                        frameQueue.poll();
                    }
                    frameQueue.add(jpeg);
                    frameQueue.notifyAll();
                }

                frameCount++;
                long now = System.currentTimeMillis();
                if (frameCount == 1 || now - lastLogTime > 5000) {
                    String tag = mode == CaptureMode.VIRTUAL_DISPLAY ? "VD" : "SS";
                    Main.println("ScreenCapture [" + tag + "]: frame #" + frameCount
                            + " JPEG=" + jpeg.length + "B queue=" + frameQueue.size());
                    lastLogTime = now;
                }
            } catch (Exception e) {
                Main.println("ScreenCapture [SS]: compress error — " + e.getMessage());
                frame.recycle();
            }
        }
    }

    // =========================================================================
    // Screenshot reflection resolution
    // =========================================================================

    private Bitmap captureScreenshot() throws Exception {
        if (!screenshotResolved) {
            resolveScreenshotReflection();
        }
        if (screenshotMethod == null || ssDisplayToken == null) {
            return null;
        }
        return invokeScreenshot();
    }

    private void resolveScreenshotReflection() {
        screenshotResolved = true;
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");

            ssDisplayToken = resolveDisplayToken(sc);
            if (ssDisplayToken == null) {
                Main.println("ScreenCapture [SS]: could not resolve display token");
                return;
            }
            Main.println("ScreenCapture [SS]: display token resolved: " + ssDisplayToken);

            screenshotMethod = resolveScreenshotMethod(sc);
            if (screenshotMethod == null) {
                Main.println("ScreenCapture [SS]: could not resolve screenshot method — polling disabled");
            } else {
                Main.println("ScreenCapture [SS]: method resolved: " + screenshotMethod);
            }
        } catch (Exception e) {
            Main.println("ScreenCapture [SS]: reflection setup failed — " + e.getMessage());
        }
    }

    private IBinder resolveDisplayToken(Class<?> sc) {
        // Android 9-11
        try {
            Method m = sc.getDeclaredMethod("getBuiltInDisplay", int.class);
            IBinder token = (IBinder) m.invoke(null, 0);
            if (token != null) return token;
        } catch (Exception ignored) {
        }
        // Android 12+
        try {
            Method m = sc.getDeclaredMethod("getInternalDisplayToken");
            IBinder token = (IBinder) m.invoke(null);
            if (token != null) return token;
        } catch (Exception ignored) {
        }
        return null;
    }

    private Method resolveScreenshotMethod(Class<?> sc) {
        // A: screenshot(IBinder, int, int)
        try {
            return sc.getDeclaredMethod("screenshot", IBinder.class, int.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // B: screenshot(IBinder, int, int, boolean)
        try {
            return sc.getDeclaredMethod("screenshot", IBinder.class, int.class, int.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
        }
        // C: screenshot(Rect, int, int, int, int, boolean, int)
        try {
            return sc.getDeclaredMethod("screenshot",
                    Rect.class, int.class, int.class, int.class, int.class, boolean.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // D: screenshot(IBinder, Rect, int, int, boolean, int)
        try {
            return sc.getDeclaredMethod("screenshot",
                    IBinder.class, Rect.class, int.class, int.class, boolean.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // E: screenshot(Rect, int, int, boolean, int)
        try {
            return sc.getDeclaredMethod("screenshot",
                    Rect.class, int.class, int.class, boolean.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // F: screenshotToBuffer(IBinder, Rect, int, int, boolean, int)
        try {
            return sc.getDeclaredMethod("screenshotToBuffer",
                    IBinder.class, Rect.class, int.class, int.class, boolean.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // G: screenshotToBuffer(IBinder, Rect, int, int, boolean, boolean)
        try {
            return sc.getDeclaredMethod("screenshotToBuffer",
                    IBinder.class, Rect.class, int.class, int.class, boolean.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
        }
        // H: screenshotToBuffer(IBinder, Rect, int, int, boolean, boolean, int)
        try {
            return sc.getDeclaredMethod("screenshotToBuffer",
                    IBinder.class, Rect.class, int.class, int.class, boolean.class, boolean.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        // Log available methods for diagnostics
        Main.println("ScreenCapture [SS]: no screenshot signature matched. Available methods:");
        for (java.lang.reflect.Method m : sc.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            if (name.contains("screenshot") || name.contains("capture") || name.contains("screen")) {
                Main.println("  SC method: " + m.toString());
            }
        }
        return null;
    }

    private Bitmap invokeScreenshot() throws Exception {
        if (screenshotMethod.getName().equals("screenshotToBuffer")) {
            return invokeScreenshotToBuffer();
        }
        Rect src = new Rect(0, 0, displayWidth, displayHeight);
        Class<?>[] params = screenshotMethod.getParameterTypes();
        int paramCount = params.length;

        if (params[0] == IBinder.class) {
            if (paramCount == 3) {
                return (Bitmap) screenshotMethod.invoke(null, ssDisplayToken, captureWidth, captureHeight);
            } else if (paramCount == 4 && params[1] == int.class) {
                return (Bitmap) screenshotMethod.invoke(null, ssDisplayToken, captureWidth, captureHeight, false);
            } else {
                return (Bitmap) screenshotMethod.invoke(null, ssDisplayToken, src, captureWidth, captureHeight, false, 0);
            }
        } else {
            if (paramCount == 5) {
                return (Bitmap) screenshotMethod.invoke(null, src, captureWidth, captureHeight, false, 0);
            } else {
                return (Bitmap) screenshotMethod.invoke(null, src, captureWidth, captureHeight,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, false, 0);
            }
        }
    }

    private Bitmap invokeScreenshotToBuffer() throws Exception {
        Rect src = new Rect(0, 0, displayWidth, displayHeight);
        Class<?>[] params = screenshotMethod.getParameterTypes();
        int paramCount = params.length;
        Object sgb;

        if (paramCount == 6 && params[5] == int.class) {
            sgb = screenshotMethod.invoke(null, ssDisplayToken, src, captureWidth, captureHeight, false, 0);
        } else if (paramCount == 6) {
            sgb = screenshotMethod.invoke(null, ssDisplayToken, src, captureWidth, captureHeight, false, false);
        } else {
            sgb = screenshotMethod.invoke(null, ssDisplayToken, src, captureWidth, captureHeight, false, false, 0);
        }
        if (sgb == null) return null;

        Object gb = sgb.getClass().getMethod("getGraphicBuffer").invoke(sgb);
        if (gb == null) return null;

        HardwareBuffer hb = (HardwareBuffer) gb.getClass().getMethod("toHardwareBuffer").invoke(gb);
        if (hb == null) return null;

        Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(hb, null);
        hb.close();
        if (hwBitmap == null) return null;

        Bitmap softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
        hwBitmap.recycle();
        return softBitmap;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public String getMode() {
        return mode == CaptureMode.VIRTUAL_DISPLAY ? "VD" : "SS";
    }

    public void setJpegQuality(int quality) {
        this.jpegQuality = quality;
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public byte[] popFrame() {
        synchronized (frameQueue) {
            while (frameQueue.isEmpty() && running) {
                try {
                    frameQueue.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return frameQueue.poll();
        }
    }

    public void stop() {
        Main.println("ScreenCapture: stopping (mode=" + mode + ")");
        running = false;

        // Cleanup virtual display
        cleanupVirtualDisplay();

        // Cleanup screenshot polling
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        synchronized (rawQueue) {
            rawQueue.notifyAll();
        }
        if (compressThread != null) {
            compressThread.interrupt();
            compressThread = null;
        }
        synchronized (rawQueue) {
            for (Bitmap b : rawQueue) b.recycle();
            rawQueue.clear();
        }
        synchronized (frameQueue) {
            frameQueue.clear();
        }

        Main.println("ScreenCapture: stopped");
    }
}
