package com.openminis.app.tools.android.vscreen;

import android.os.ParcelFileDescriptor;
import com.openminis.app.tools.android.vscreen.IVirtualScreenFrameSink;

interface IVirtualScreenService {
    String probe() = 1;
    int createDisplay(int width, int height, int dpi) = 2;
    void releaseDisplay() = 3;
    boolean launch(String packageName, @nullable String activityOrNull, int displayId) = 4;
    String dump(int displayId, String mode) = 5;
    boolean tap(int displayId, int x, int y) = 7;
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs) = 8;
    boolean longPress(int displayId, int x, int y, int durationMs) = 9;
    boolean key(int displayId, int keyCode) = 10;
    boolean inputText(int displayId, @nullable String text) = 11;
    boolean setText(int displayId, @nullable String text) = 12;
    boolean back(int displayId) = 13;
    boolean home(int displayId) = 14;
    ParcelFileDescriptor screenshot(int displayId, int maxDim, int jpegQuality) = 15;
    int getActiveDisplayId() = 16;
    boolean hasPackageWindow(int displayId, String packageName) = 17;
    // Locate + act + wait for the screen to settle + observe, as one call. Request/response are JSON.
    String act(int displayId, String requestJson) = 24;

    // Live viewing and manual control (service version 2).
    /** [displayId, width, height, dpi] of the active display, or an empty array when there is none. */
    int[] getDisplayInfo() = 20;
    /** Start pushing every new frame of the active display to [sink] as a HardwareBuffer. */
    void startFrameStream(IVirtualScreenFrameSink sink) = 21;
    void stopFrameStream() = 22;
    /** One raw touch event (MotionEvent action) at display-local x/y, for dragging by hand. */
    boolean touch(int displayId, int action, int x, int y, long downTimeMs) = 23;

    // Reserved by the Shizuku server. The server invokes transaction 16777115.
    void destroy() = 16777114;
}
