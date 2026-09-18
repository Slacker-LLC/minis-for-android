package com.openminis.app.runtime.guest

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import org.json.JSONObject

/**
 * Whether this process is in the foreground, and the body to report when that
 * is the reason an activity launch failed.
 *
 * Android refuses activity starts from a background app and surfaces it as
 * [android.content.ActivityNotFoundException] — the same exception a genuinely
 * missing handler throws. Blaming the missing app is then wrong in a way the
 * user cannot act on: measured on the Xiaomi 24129PN74C, `android-alarm list`
 * opened the Clock while Minis was on screen, and with Minis backgrounded the
 * same call answered `no_clock_app … Install or re-enable a Clock app` with the
 * Clock app sitting right there.
 *
 * Answering `true` when the platform will not say keeps callers on their
 * existing path instead of inventing a new failure.
 */
internal object OffloadForeground {

    fun isAppForeground(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val pid = Process.myPid()
        am.runningAppProcesses.orEmpty().any {
            it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    } catch (_: Throwable) {
        true
    }

    /**
     * Body for "Android refused to launch [action] because Minis is in the
     * background". [subject] carries the URL when the launch had one, matching
     * the field the open path already publishes.
     */
    fun backgroundLaunchBody(action: String, subject: String? = null): JSONObject = JSONObject()
        .put("error", "background_launch_blocked")
        .put("action", action)
        .put("message", "Minis is not on screen, so Android refused to launch $action: background activity starts are blocked. Ask the user to open Minis and retry.")
        .apply { if (subject != null) put("subject", subject) }
}

