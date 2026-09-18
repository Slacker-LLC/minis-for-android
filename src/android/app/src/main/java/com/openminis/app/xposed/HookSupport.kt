package com.openminis.app.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * [T-eta-xposed-entry] The reflection habits every hook group in this app shares.
 *
 * Ported from Eta `core/HookSupport.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. What makes these worth copying verbatim is the failure policy: a hook
 * group runs inside somebody else's process, so a class or method that is simply not on this ROM
 * build must come back as null (and end up as MISSING in the ledger), never as an exception that
 * takes system_server or another app down. LinkageError is therefore caught wherever a lookup
 * happens, and lookups walk the superclass chain because ROM classes move members around between
 * releases.
 */
object HookSupport {

    fun findClassOrNull(classLoader: ClassLoader, className: String): Class<*>? =
        try {
            Class.forName(className, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }

    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                // Keep looking on the superclass.
            } catch (_: SecurityException) {
                // A restricted class can still expose the entry point above it.
            } catch (_: LinkageError) {
                // A signature that references a type this ROM lacks means "not here".
            }
            current = current.superclass
        }
        return null
    }

    /** Installation-time structural lookup over the public methods. */
    fun findPublicMethod(clazz: Class<*>, predicate: (Method) -> Boolean): Method? = try {
        clazz.methods.firstOrNull(predicate)
    } catch (_: SecurityException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /** Installation-time structural lookup over declared methods; one bad candidate is skipped. */
    fun findDeclaredMethods(
        clazz: Class<*>,
        makeAccessible: Boolean = false,
        predicate: (Method) -> Boolean,
    ): List<Method> = try {
        clazz.declaredMethods
            .filter(predicate)
            .filter { method ->
                !makeAccessible || try {
                    method.isAccessible = true
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: LinkageError) {
                    false
                }
            }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: LinkageError) {
        emptyList()
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                // Keep looking on the superclass.
            } catch (_: SecurityException) {
                // See findMethod.
            } catch (_: LinkageError) {
                // See findMethod.
            }
            current = current.superclass
        }
        return null
    }

    fun getFieldValue(target: Any, name: String): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return try {
            field.get(target)
        } catch (_: IllegalAccessException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun invokeNoArgs(target: Any, name: String): Any? {
        val method = findMethod(target.javaClass, name) ?: return null
        return try {
            method.invoke(target)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Asks the framework to give up on compiled code for one target; a failure is only a warning. */
    fun deoptimize(
        module: XposedModule,
        logger: HookLogger,
        executable: Executable,
        description: String,
    ) {
        try {
            val deoptimized = module.deoptimize(executable)
            logger.debug { "deopt $description = $deoptimized" }
        } catch (exception: Exception) {
            logger.warn("deopt failed: $description, type=${exception.javaClass.simpleName}")
        }
    }

    /** "pkg/cls" or a bare package name both reduce to the package. */
    fun extractPackageName(componentOrPackage: String?): String? {
        if (componentOrPackage.isNullOrBlank()) return null
        return ComponentName.unflattenFromString(componentOrPackage)?.packageName
            ?: componentOrPackage.substringBefore('/', componentOrPackage)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun resolvesActivity(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null
}
