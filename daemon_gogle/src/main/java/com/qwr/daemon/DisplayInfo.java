package com.qwr.daemon;

import android.graphics.Point;
import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Retrieves display dimensions using hidden Android APIs via reflection.
 * Works from system app context (app_process).
 */
public class DisplayInfo {

    /**
     * Gets the current display size for the given display ID.
     * Uses IWindowManager.getBaseDisplaySize() via ServiceManager reflection.
     *
     * @param displayId 0 for primary display
     * @return Point with display width (x) and height (y)
     */
    public static Point getDisplaySize(int displayId) {
        try {
            // ServiceManager.getService("window") → IBinder
            Method getService = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class);

            // IWindowManager.Stub.asInterface(binder)
            IBinder wmBinder = (IBinder) getService.invoke(null, "window");
            Object wm = Class.forName("android.view.IWindowManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class)
                    .invoke(null, wmBinder);

            // wm.getBaseDisplaySize(displayId, outSize)
            Point size = new Point();
            wm.getClass().getMethod("getBaseDisplaySize", int.class, Point.class)
                    .invoke(wm, displayId, size);

            // Check for rotation and swap if needed
            int rotation = getRotation(getService, wm, displayId);
            if (rotation == 1 || rotation == 3) {
                // Landscape rotation — swap x and y
                int tmp = size.x;
                size.x = size.y;
                size.y = tmp;
            }

            Main.println("DisplayInfo: size=" + size.x + "x" + size.y + " rotation=" + rotation);
            return size;

        } catch (Exception e) {
            Main.println("DisplayInfo: failed to get display size — " + e.getMessage());
            // Fallback to known SSNWT resolution
            return new Point(3664, 1920);
        }
    }

    /**
     * Gets the current display rotation.
     * Tries IWindowManager.getRotation() first, falls back to DisplayInfo.rotation field.
     */
    private static int getRotation(Method getService, Object wm, int displayId) {
        // Try IWindowManager.getRotation() (older API)
        try {
            Object result = wm.getClass().getMethod("getRotation").invoke(wm);
            return (Integer) result;
        } catch (Exception ignored) {
        }

        // Fallback: IDisplayManager.getDisplayInfo(displayId).rotation
        try {
            IBinder dmBinder = (IBinder) getService.invoke(null, "display");
            Object dm = Class.forName("android.hardware.display.IDisplayManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class)
                    .invoke(null, dmBinder);

            Object displayInfo = dm.getClass().getMethod("getDisplayInfo", int.class)
                    .invoke(dm, displayId);
            return (Integer) displayInfo.getClass().getDeclaredField("rotation").get(displayInfo);
        } catch (Exception ignored) {
        }

        return 0; // Default: no rotation
    }
}
