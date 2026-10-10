package com.openminis.app.tools.android.vscreen.service

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * Host of [VirtualScreenUserService] in a root process.
 *
 * libsu starts the process with `app_process` through su and hands this service's binder back to the
 * app ([com.openminis.app.tools.android.vscreen.VirtualScreenClient] binds with
 * [RootService.CATEGORY_DAEMON_MODE]). In daemon mode the process, and the virtual display it holds,
 * outlives the app process; libsu ends it when the app is updated or uninstalled, so a new build never
 * talks to a service built from older code.
 */
class VirtualScreenRootService : RootService() {
    /** One service per process, created on the first bind; daemon mode reuses it across app launches. */
    private var service: VirtualScreenUserService? = null

    override fun onBind(intent: Intent): IBinder =
        service ?: VirtualScreenUserService().also { service = it }

    override fun onDestroy() {
        service?.shutdown()
        super.onDestroy()
    }
}
