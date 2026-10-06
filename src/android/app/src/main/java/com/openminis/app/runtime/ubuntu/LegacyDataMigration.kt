package com.openminis.app.runtime.ubuntu

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one-time copy of legacy Root-owned user data into App-owned storage (07-OWNERSHIP-MIGRATION).
 * It runs inside the first Ubuntu readiness pass, which can come long after the app has started;
 * until then the App-owned trees may still be missing data that only exists in the legacy location.
 */
object LegacyDataMigration {
    private const val MARKER = "minis/.root-data-migrated-v1"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun marker(context: Context): File = File(context.filesDir, MARKER)

    /** True once the copy finished (a fresh install finishes it on its first readiness pass too). */
    fun isComplete(context: Context): Boolean = marker(context).isFile

    /** Run [listener] each time the migration completes in this process. */
    fun onComplete(listener: () -> Unit) {
        listeners += listener
    }

    internal fun notifyComplete() {
        listeners.forEach { runCatching { it() } }
    }
}
