package com.openminis.app.xposed.hyperos

import android.content.Context
import android.os.Bundle
import java.lang.Boolean
import com.openminis.app.xposed.AssistantLaunch
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.PowerAssistantTarget
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] The power-key long press on HyperOS, routed to the assistant the user chose.
 *
 * Ported from Eta `hook/hyperos/HyperOsPowerHooks.kt` and `HyperOsPowerPolicy.kt` (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. HyperOS dispatches every power-key shortcut
 * through `ShortCutActionsUtils.triggerFunction`; Eta hooks both arities of that method and only
 * claims the call whose function AND source are the assistant long press, so the power menu, SOS
 * or any other shortcut keeps working.
 *
 * The target comes from the module settings and defaults to the OEM assistant (see
 * [PowerAssistantTarget.parse]), so an installed-but-unconfigured module still behaves like an
 * uninstalled one: the long press opens whatever the ROM would have opened.
 */
object HyperOsPowerHooks {

    private const val GROUP = "HyperOsPower"
    private const val DISPATCHER_CLASS = "com.miui.server.input.util.ShortCutActionsUtils"

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "hyperos.power-shortcut",
                        description = "ShortCutActionsUtils.triggerFunction",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val dispatcher = HookSupport.findClassOrNull(classLoader, DISPATCHER_CLASS)
            if (dispatcher == null) {
                skipped(
                    "hyperos.power-shortcut",
                    "ShortCutActionsUtils.triggerFunction",
                    "HyperOS: this build has no shortcut dispatcher",
                )
                return@install
            }
            val contextField = HookSupport.findField(dispatcher, "mContext")
            if (contextField == null || !Context::class.java.isAssignableFrom(contextField.type)) {
                missing(
                    "hyperos.power-shortcut",
                    "ShortCutActionsUtils.mContext",
                    "HyperOS: the shortcut dispatcher carries no context",
                )
                return@install
            }
            val parameters = arrayOf(
                String::class.java,
                String::class.java,
                Bundle::class.java,
                java.lang.Boolean.TYPE,
            )
            listOf(parameters, parameters + String::class.java).forEach { signature ->
                val id = "hyperos.power-shortcut-${signature.size}"
                val method = HookSupport.findMethod(dispatcher, "triggerFunction", *signature)
                if (method == null || method.returnType != java.lang.Boolean.TYPE) {
                    missing(
                        id,
                        "ShortCutActionsUtils.triggerFunction/${signature.size}",
                        "HyperOS: shortcut dispatch signature ${signature.size} does not match",
                    )
                    return@forEach
                }
                intercept(id, method, "ShortCutActionsUtils.triggerFunction/${signature.size}") { chain ->
                    // Function AND source: the power menu, SOS and every other shortcut must keep
                    // working, so only the assistant long press is claimed.
                    if (!HyperOsPowerPolicy.isAssistantShortcut(
                            chain.getArg(0) as? String,
                            chain.getArg(1) as? String,
                        )
                    ) {
                        return@intercept chain.proceed()
                    }
                    val target = PowerAssistantTarget.parse(
                        ModulePrefs.string(
                            ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                            PowerAssistantTarget.OEM.wire,
                        ),
                    )
                    val context = contextField.get(chain.getThisObject()) as? Context
                    if (context == null || AssistantLaunch.targetFor(target) == null) {
                        return@intercept chain.proceed()
                    }
                    if (AssistantLaunch.launch(context, target, hooks.logger, "HyperOsPower")) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
            }
        }.report
    }
}
