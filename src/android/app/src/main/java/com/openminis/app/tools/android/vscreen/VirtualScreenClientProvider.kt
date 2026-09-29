package com.openminis.app.tools.android.vscreen

import android.content.Context

/** One app-process binding shared by settings, native tools and the Android UI router. */
object VirtualScreenClientProvider {
    private val lock = Any()
    @Volatile private var instance: VirtualScreenClient? = null

    fun get(context: Context): VirtualScreenClient = instance ?: synchronized(lock) {
        instance ?: VirtualScreenClient(context.applicationContext).also { instance = it }
    }

    internal fun clearForTests() {
        synchronized(lock) {
            instance?.close()
            instance = null
        }
    }
}
