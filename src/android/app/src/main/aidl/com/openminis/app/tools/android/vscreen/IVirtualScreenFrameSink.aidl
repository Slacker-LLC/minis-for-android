package com.openminis.app.tools.android.vscreen;

import android.hardware.HardwareBuffer;

/** Receives the virtual display's frames in the app process. Calls never block the service. */
oneway interface IVirtualScreenFrameSink {
    void onFrame(in HardwareBuffer buffer);
    void onEnded();
}
