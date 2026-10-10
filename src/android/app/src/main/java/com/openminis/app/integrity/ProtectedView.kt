package com.openminis.app.integrity

import com.openminis.app.util.shellQuote

/**
 * The protected view (Issue #182): the one place a privileged command actually runs when device
 * protection is on. It is what holds when [DeviceIntegrityPolicy] cannot read the target out of the
 * command (`sh -c "$(cat x)"`, a variable, a script file).
 *
 * The command runs in a throw-away mount namespace in which
 *  - the system partitions, `/data/system*`, `/data/misc*`, `/data/vendor*`, `/data/property`,
 *    `/data/apex`, `/data/unencrypted`, `/metadata` and `/data/adb` are read-only bind mounts
 *    (`/data/adb/minis` is bound back read-write),
 *  - `/dev/block` is replaced by an empty `nodev` tmpfs, so no partition node can be opened for
 *    writing. A read-only bind mount does not stop writes to device nodes, which is why the node
 *    directory is swapped out instead of remounted. Reading a partition goes through the separate
 *    `android-root-cli partition read` command,
 *  - every known `su` path is covered by a stub, so a protected command cannot start a fresh Root
 *    shell outside the view,
 * and the shell is then started with `CAP_SYS_ADMIN`, `CAP_MKNOD`, `CAP_SYS_MODULE` and
 * `CAP_SYS_RAWIO` removed from the bounding set, so none of this can be remounted, unmounted,
 * re-created or loaded around.
 *
 * Android's toybox has no `setpriv`; the Ubuntu rootfs one is run through the rootfs dynamic loader
 * without chroot. If any part of the view cannot be built the command does not run (exit 125,
 * `DEVICE_PROTECTION_UNAVAILABLE`): protection fails closed, and the user can turn it off.
 *
 * Nothing here has been run on a device; see `06-CURRENT-GAPS.md`.
 */
internal object ProtectedView {
    const val EXIT_UNAVAILABLE = 125
    const val UNAVAILABLE_PREFIX = "DEVICE_PROTECTION_UNAVAILABLE"

    /** Capabilities that could undo the view or write around it. Reboot and kill stay allowed. */
    internal const val DROPPED_CAPS = "-sys_admin,-mknod,-sys_module,-sys_rawio"

    /** Directories that become read-only. Globs are expanded by the shell inside the view. */
    internal val READ_ONLY_DIRS = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex", "/metadata",
        "/data/system*", "/data/misc*", "/data/vendor*", "/data/property", "/data/apex",
        "/data/unencrypted", "/data/adb",
    )

    internal val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su",
    )

    private const val LIBDIR_REL = "usr/lib/aarch64-linux-gnu"
    private const val LIBDIR2_REL = "lib/aarch64-linux-gnu"
    private const val LOADER_REL = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"

    /**
     * Wrap [script] so it runs inside the view. [rootfs] is the Ubuntu rootfs on the host; its
     * `setpriv` does the capability drop. [minisDir] stays writable under `/data/adb`.
     */
    fun wrap(script: String, rootfs: String, minisDir: String = "/data/adb/minis"): String {
        val inner = buildString {
            append("fail() { echo \"$UNAVAILABLE_PREFIX: \$1\" >&2; exit $EXIT_UNAVAILABLE; }; ")
            append("RF=${shellQuote(rootfs)}; ")
            append("LOADER=\"\$RF/$LOADER_REL\"; ")
            append("[ -x \"\$LOADER\" ] || fail 'rootfs loader not found'; ")
            append("[ -x \"\$RF/usr/bin/setpriv\" ] || fail 'rootfs setpriv not found'; ")
            append("/system/bin/mount -o rprivate,bind / / || fail 'cannot make mounts private'; ")
            // Block nodes: swap the directory for an empty nodev tmpfs before anything else needs it.
            append("/system/bin/mount -t tmpfs -o nodev,noexec,mode=755 tmpfs /dev/block || fail '/dev/block'; ")
            // The su stub lives on a second tmpfs, so /dev/block stays empty.
            append("mkdir -p /dev/.minis-guard && /system/bin/mount -t tmpfs -o nodev,mode=755 tmpfs /dev/.minis-guard || fail 'guard tmpfs'; ")
            append("printf '%s\\n' '#!/system/bin/sh' 'echo \"DEVICE_PROTECTED: su is not available inside the protected view\" >&2' 'exit 126' > /dev/.minis-guard/su || fail 'su stub'; ")
            append("chmod 755 /dev/.minis-guard/su; ")
            append("for s in ${SU_PATHS.joinToString(" ") { shellQuote(it) }}; do ")
            append("[ -e \"\$s\" ] && { /system/bin/mount -o bind /dev/.minis-guard/su \"\$s\" || fail \"su \$s\"; }; done; ")
            append("for d in ${READ_ONLY_DIRS.joinToString(" ")}; do ")
            append("[ -d \"\$d\" ] || continue; ")
            append("/system/bin/mount -o bind \"\$d\" \"\$d\" && /system/bin/mount -o remount,bind,ro \"\$d\" || fail \"\$d\"; done; ")
            append("if [ -d ${shellQuote(minisDir)} ]; then ")
            append("/system/bin/mount -o bind ${shellQuote(minisDir)} ${shellQuote(minisDir)} && ")
            append("/system/bin/mount -o remount,bind,rw ${shellQuote(minisDir)} || fail 'minis dir'; fi; ")
            append("exec \"\$LOADER\" --library-path \"\$RF/$LIBDIR_REL:\$RF/$LIBDIR2_REL\" \"\$RF/usr/bin/setpriv\" ")
            append("--bounding-set=$DROPPED_CAPS --inh-caps=$DROPPED_CAPS -- /system/bin/sh -c ${shellQuote(script)}")
        }
        return "if [ -x /system/bin/unshare ]; then exec /system/bin/unshare -m /system/bin/sh -c ${shellQuote(inner)}; " +
            "else echo \"$UNAVAILABLE_PREFIX: unshare not found\" >&2; exit $EXIT_UNAVAILABLE; fi"
    }

    /** True when a result is the view reporting that it could not be built. */
    fun isUnavailable(exitCode: Int, stderr: String): Boolean =
        exitCode == EXIT_UNAVAILABLE && stderr.contains(UNAVAILABLE_PREFIX)
}
