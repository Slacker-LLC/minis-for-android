package com.openminis.app.debug

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * JSON-RPC 2.0 method dispatcher for the debug server.
 * Mirrors iOS DebugServer methods for cross-platform parity.
 *
 * Call `rpc.discover` to retrieve the full method catalogue with parameter
 * schemas and examples. See [DebugMethodRegistry] for the source of truth.
 */
class DebugRPCHandler(internal val context: Context) {

    companion object {
        /** Set this from MainActivity to enable screenshot capture. */
        var currentActivity: WeakReference<Activity>? = null

        /** Both crash writers' filenames: ACRA's and NativeCrashHandler's. */
        internal val CRASH_NAME = Regex("""^(native-)?crash-.*\.log$""")
    }






























































}

internal class RPCException(val code: Int, override val message: String) : Exception(message)
