package com.openminis.app

import android.content.Context
import android.content.Intent

/** Relaunches the app in a fresh process: the Xposed framework hands its settings service over only at process start. */
object AppRestart {
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
