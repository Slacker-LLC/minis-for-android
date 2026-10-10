/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/ShellContext.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import java.lang.reflect.Constructor

/** Minimal shell-identity context bootstrap. No receiver/content-provider APIs are assumed. */
internal class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = identityPackage()
    override fun getOpPackageName(): String = identityPackage()
    override fun getApplicationContext(): Context = this

    @SuppressLint("SoonBlockedPrivateApi")
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null
        if (name == Context.CLIPBOARD_SERVICE || name == Context.ACTIVITY_SERVICE) {
            runCatching {
                service.javaClass.getDeclaredField("mContext").apply { isAccessible = true }.set(service, this)
            }
        }
        return service
    }

    @Suppress("unused")
    override fun getDeviceId(): Int = 0

    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.myUid()).setPackageName(identityPackage()).build()

    companion object {
        const val SHELL_PACKAGE_NAME = "com.android.shell"

        /** System services accept the package name "root" for uid 0 and "com.android.shell" for uid 2000. */
        const val ROOT_PACKAGE_NAME = "root"

        /** The package name that matches the uid this process really runs as. */
        fun identityPackage(uid: Int = Process.myUid()): String = if (uid == 0) ROOT_PACKAGE_NAME else SHELL_PACKAGE_NAME
        @Volatile private var instance: ShellContext? = null

        /** Runs only inside the separate root process libsu starts with app_process, where non-SDK restrictions do not apply. */
        @SuppressLint("SoonBlockedPrivateApi")
        @Synchronized
        fun initialize(): String? = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val ctor: Constructor<*> = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
            val thread = ctor.newInstance()
            activityThreadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            activityThreadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)

            if (Build.VERSION.SDK_INT >= 31) fillConfigurationController(activityThreadClass, thread)
            val systemContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(thread) as Context
            val shell = ShellContext(systemContext)
            fillApplicationInfo(activityThreadClass, thread)
            fillInitialApplication(activityThreadClass, thread, shell)
            instance = shell
        }.fold({ null }, { it.message ?: it.javaClass.simpleName })

        fun get(): ShellContext = instance ?: throw IllegalStateException("shell_context_unavailable")

        private fun fillApplicationInfo(activityThreadClass: Class<*>, thread: Any) {
            runCatching {
                val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val info = ApplicationInfo().apply { packageName = identityPackage() }
                bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, info)
                activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bindData)
            }
        }

        private fun fillInitialApplication(activityThreadClass: Class<*>, thread: Any, shell: ShellContext) {
            runCatching {
                val app = Instrumentation.newApplication(Application::class.java, shell)
                activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
            }
        }

        private fun fillConfigurationController(activityThreadClass: Class<*>, thread: Any) {
            runCatching {
                val controllerClass = Class.forName("android.app.ConfigurationController")
                val internalClass = Class.forName("android.app.ActivityThreadInternal")
                val controller = controllerClass.getDeclaredConstructor(internalClass).apply { isAccessible = true }
                    .newInstance(thread)
                activityThreadClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                    .set(thread, controller)
            }
        }
    }
}
