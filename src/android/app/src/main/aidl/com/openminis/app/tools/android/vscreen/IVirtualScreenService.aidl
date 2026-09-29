package com.openminis.app.tools.android.vscreen;

import android.os.ParcelFileDescriptor;

interface IVirtualScreenService {
    String probe() = 1;
    int createDisplay(int width, int height, int dpi) = 2;
    void releaseDisplay() = 3;
    boolean launch(String packageName, @nullable String activityOrNull, int displayId) = 4;
    String dump(int displayId, String mode) = 5;
    boolean clickTarget(int displayId, int targetIndex) = 6;
    boolean tap(int displayId, int x, int y) = 7;
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs) = 8;
    boolean longPress(int displayId, int x, int y, int durationMs) = 9;
    boolean key(int displayId, int keyCode) = 10;
    boolean inputText(int displayId, @nullable String text) = 11;
    boolean setText(int displayId, @nullable String text) = 12;
    boolean back(int displayId) = 13;
    boolean home(int displayId) = 14;
    ParcelFileDescriptor screenshot(int displayId, int maxDim, int jpegQuality) = 15;

    // Reserved by the Shizuku server. The server invokes transaction 16777115.
    void destroy() = 16777114;
}
