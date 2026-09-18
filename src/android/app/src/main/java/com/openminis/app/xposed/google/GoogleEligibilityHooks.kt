package com.openminis.app.xposed.google

import android.os.Build
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * [T-eta-xposed-groups] Making the Google app believe this device may run its assistant features.
 *
 * Ported from Eta `hook/google/GoogleEligibilityHooks.kt` and the identity half of
 * `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md.
 * Google's gate asks the platform questions - the device build, `ro.opa.eligible_device`, two
 * `hasSystemFeature` features - and refuses those features when the answers say "not this device".
 * Eta treats the answers as one thing and applies them wherever the module runs, because the screen
 * features this module is for cannot be reached without them; the port keeps that, and the ledger
 * records each answer separately so "which one is still refused" is diagnosable instead of guessed.
 */
object GoogleEligibilityHooks {

    private const val GROUP = "GoogleEligibility"

    private const val SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties"
    private const val PACKAGE_MANAGER_CLASS = "android.app.ApplicationPackageManager"

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "google.eligibility",
                        description = "Google eligibility answers",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            reportIdentity(hooks)
            hookSystemProperties(hooks, classLoader)
            hookPackageManagerFeatures(hooks, classLoader)
        }.report
    }

    /**
     * The identity is written into the statics rather than hooked: the Google app reads them when it
     * starts deciding, and there is no call to intercept.
     */
    private fun reportIdentity(hooks: HookRegistrar) {
        GoogleSpoofProfile.BUILD_FIELDS.forEach { (name, value) ->
            val id = "google.identity.${name.lowercase()}"
            val field = runCatching {
                Build::class.java.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field == null) {
                hooks.missing(id, "Build.$name", "Google: this build has no Build.$name")
                return@forEach
            }
            if (writeField(field, value)) {
                hooks.applied(id, "Build.$name", value)
            } else {
                hooks.failed(id, "Build.$name", "Google: writing Build.$name failed")
            }
        }
    }

    /**
     * Reflection first; where the platform refuses it the static field is written in place, which is
     * what makes the rewrite visible to code that has already read the field.
     */
    private fun writeField(field: Field, value: String): Boolean {
        if (runCatching { field.set(null, value) }.isSuccess) return true
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null)
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .invoke(unsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getDeclaredMethod(
                "putObjectVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Any::class.java,
            ).invoke(unsafe, base, offset, value)
        }.isSuccess
    }

    private fun hookSystemProperties(hooks: HookRegistrar, classLoader: ClassLoader) {
        val systemProperties = HookSupport.findClassOrNull(classLoader, SYSTEM_PROPERTIES_CLASS)
        if (systemProperties == null) {
            hooks.skipped(
                "google.system-properties",
                "SystemProperties reads",
                "Google: no SystemProperties in this process, the property answer is skipped",
            )
            return
        }

        val get = HookSupport.findMethod(systemProperties, "get", String::class.java)
        if (get != null) {
            hooks.intercept("google.system-properties.get", get, "SystemProperties.get(String)") { chain ->
                if (GoogleSpoofProfile.forgedProperty(chain.getArg(0) as? String)) "true" else chain.proceed()
            }
        } else {
            hooks.missing(
                "google.system-properties.get",
                "SystemProperties.get(String)",
                "Google: SystemProperties.get(String) not found",
            )
        }

        val getWithDefault = HookSupport.findMethod(
            systemProperties,
            "get",
            String::class.java,
            String::class.java,
        )
        if (getWithDefault != null) {
            hooks.intercept(
                "google.system-properties.get-default",
                getWithDefault,
                "SystemProperties.get(String,String)",
            ) { chain ->
                if (GoogleSpoofProfile.forgedProperty(chain.getArg(0) as? String)) "true" else chain.proceed()
            }
        } else {
            hooks.missing(
                "google.system-properties.get-default",
                "SystemProperties.get(String,String)",
                "Google: SystemProperties.get(String,String) not found",
            )
        }

        val getBoolean = HookSupport.findMethod(
            systemProperties,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        if (getBoolean != null) {
            hooks.intercept(
                "google.system-properties.get-boolean",
                getBoolean,
                "SystemProperties.getBoolean(String,boolean)",
            ) { chain ->
                if (GoogleSpoofProfile.forgedProperty(chain.getArg(0) as? String)) true else chain.proceed()
            }
        } else {
            hooks.missing(
                "google.system-properties.get-boolean",
                "SystemProperties.getBoolean(String,boolean)",
                "Google: SystemProperties.getBoolean(String,boolean) not found",
            )
        }
    }

    private fun hookPackageManagerFeatures(hooks: HookRegistrar, classLoader: ClassLoader) {
        val packageManager = HookSupport.findClassOrNull(classLoader, PACKAGE_MANAGER_CLASS)
        if (packageManager == null) {
            hooks.missing(
                "google.package-manager-features",
                "ApplicationPackageManager.hasSystemFeature",
                "Google: ApplicationPackageManager not found",
            )
            return
        }

        // Every overload that takes the feature name first is the same question.
        val methods = HookSupport.findDeclaredMethods(clazz = packageManager, makeAccessible = true) { method ->
            method.name == "hasSystemFeature" &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.firstOrNull() == String::class.java
        }
        if (methods.isEmpty()) {
            hooks.missing(
                "google.package-manager-features",
                "ApplicationPackageManager.hasSystemFeature",
                "Google: no hasSystemFeature(String, ...) on this build",
            )
            return
        }
        methods.forEach { method ->
            hooks.intercept(
                "google.package-manager-features.${method.parameterTypes.size}",
                method,
                "ApplicationPackageManager.hasSystemFeature/${method.parameterTypes.size}",
            ) { chain ->
                if (GoogleSpoofProfile.forgedFeature(chain.getArg(0) as? String)) true else chain.proceed()
            }
        }
    }
}
