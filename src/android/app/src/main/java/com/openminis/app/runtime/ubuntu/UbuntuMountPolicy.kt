package com.openminis.app.runtime.ubuntu

/**
 * Functional guest pseudo-filesystem surface shared by provisioning and
 * launches. This follows the upstream/original runtime contract: the guest
 * receives the Android `/dev`, `/proc`, and `/sys` trees in its private mount
 * namespace. The namespace is made recursively private before these binds, so
 * guest mount changes do not propagate back to the host. Android's toybox
 * mount parser does not accept the GNU `--rbind`/`--make-rslave` spellings
 * used by desktop Linux; use its portable bind form here. Guest UID/capability
 * dropping remains a separate policy in [UbuntuKernel].
 */
internal object UbuntuMountPolicy {
    fun setupCommands(): List<String> = listOf(
        "test -d \"\$ROOTFS\" && test ! -L \"\$ROOTFS\"",
        "for mountpoint in dev proc sys; do " +
            "target=\"\$ROOTFS/\$mountpoint\"; " +
            "if [ -L \"\$target\" ]; then exit 72; fi; " +
            "if [ -e \"\$target\" ] && [ ! -d \"\$target\" ]; then exit 72; fi; " +
            "done",
        "mkdir -p \"\$ROOTFS/dev\" \"\$ROOTFS/proc\" \"\$ROOTFS/sys\"",
        "for mountpoint in dev proc sys; do " +
            "target=\"\$ROOTFS/\$mountpoint\"; " +
            "[ -d \"\$target\" ] && [ ! -L \"\$target\" ] || exit 73; " +
            "done",
        // Keep the complete pseudo-filesystem surface expected by Ubuntu,
        // Python, debuggers, child PTYs, and Android tooling. The enclosing
        // mount namespace is private; these mounts are not host-global.
        "/system/bin/mount -o bind /dev \"\$ROOTFS/dev\"",
        // A non-recursive bind of /dev leaves /dev/pts an empty directory, so the guest's own pty
        // slaves (tty, ttyname, script, tmux, a program opening a pty) could not be resolved. Mount the
        // host's devpts at the same place. Best effort: a device without it keeps the old behaviour.
        "if [ -d /dev/pts ] && [ -d \"\$ROOTFS/dev/pts\" ] && [ ! -L \"\$ROOTFS/dev/pts\" ]; then " +
            "/system/bin/mount -o bind /dev/pts \"\$ROOTFS/dev/pts\" || true; fi",
        "/system/bin/mount -o bind /proc \"\$ROOTFS/proc\"",
        "/system/bin/mount -o bind /sys \"\$ROOTFS/sys\"",
    )
}
