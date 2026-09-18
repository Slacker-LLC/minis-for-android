package com.openminis.app.runtime.ubuntu

import android.content.Context

/**
 * [T-root-manager-detection-android] Which root manager is installed, for the case where
 * su itself is not reachable from this process.
 *
 * Found on the real device (Xiaomi 24129PN74C, HyperOS, Android 37): the su binary exists
 * and is world-executable for the shell, but from the app's own uid it is ENOENT - the
 * shell at /system/bin/sh runs, su does not - because KernelSU Next hides su from apps
 * that are not on its allowlist. "No su binary on this device" was therefore true for this
 * process and useless to the user, who does have root. The manager's package stays
 * visible, which is what makes the difference actionable.
 */
internal object RootManagerDetector {

    data class Manager(val label: String, val packageName: String)

    /**
     * Proper names, so they are not translated. Order is the order a device is most likely
     * to carry them; a device with two managers reports the first.
     */
    private val KNOWN = listOf(
        Manager("KernelSU Next", "com.rifsxd.ksunext"),
        Manager("KernelSU", "me.weishu.kernelsu"),
        Manager("APatch", "me.bmax.apatch"),
        Manager("Magisk", "com.topjohnwu.magisk"),
    )

    /** The manager whose package is installed, if any. Pure so the list is testable. */
    fun managerFor(installedPackages: Set<String>): Manager? =
        KNOWN.firstOrNull { it.packageName in installedPackages }

    /** The installed manager, asked package by package (needs the manifest queries). */
    fun detect(context: Context): Manager? {
        val installed = KNOWN.mapNotNull { manager ->
            runCatching { context.packageManager.getPackageInfo(manager.packageName, 0) }
                .getOrNull()
                ?.let { manager.packageName }
        }.toSet()
        return managerFor(installed)
    }
}
