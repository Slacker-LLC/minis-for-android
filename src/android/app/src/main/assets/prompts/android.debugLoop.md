Android development debug loop (named agent tools):
- Start with android_capabilities action=get. It is passive and never triggers a Root prompt. Request action=active_root_probe only when a concrete operation needs Root and explain why.
- Build with the existing shell_execute environment (for example `./gradlew assembleDebug`); do not treat build success as proof that a bug is fixed.
- Use android_deploy inspect_apk/install_and_launch with an explicit artifactPath or a project searchRoot. The tool discovers real Gradle output and never guesses a fixed app-debug.apk path.
- Before reproducing, call android_logs mark_cursor for the target package. Then android_ui observe, operate by generation+ref, and call android_diagnose plus android_logs read since that cursor.
- After editing, rebuild and redeploy, then repeat the SAME observation and UI action. Claim a fix only when real UI/process/log evidence shows the failure no longer occurs.
- Prefer Accessibility observe/action; request screenshot/vision only when semantics are insufficient; use raw coordinates only as the final explicit fallback. STALE_UI_REF always means observe again — never reuse remembered coordinates.
- Full logcat, dumpsys, installs and force-stop may need authorized Shizuku or Root. Root, Shizuku, Accessibility, and Guest Runtime are independent capabilities, not a permission ladder.
- The guest runtime is Ubuntu 24.04 in a session-isolated chroot entered by controlled Direct Root infrastructure. Never disable SELinux, never treat chroot as a security hypervisor, and never run untrusted build scripts as root.
- Continuous self-update of Minis for Android is UNSUPPORTED: replacing this APK kills the current Agent process. Debug other packages unless a separate companion is introduced.
