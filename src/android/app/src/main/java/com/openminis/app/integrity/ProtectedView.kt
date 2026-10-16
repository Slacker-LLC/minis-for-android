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
 * Android's toybox has no `setpriv`, and its `mount` cannot do the propagation change or a read-only
 * bind remount: `-o rprivate,bind / /` is autodetected as a plain bind that leaves propagation shared
 * (toys/lsb/mount.c:113, :189-197), and `-o remount` takes the mount's device and type from
 * /proc/mounts (:318-355), so the bind flag is dropped and the whole filesystem is remounted. That
 * flips a writable partition such as /metadata to read-only for the entire device. The Ubuntu rootfs
 * `setpriv` and `mount` (util-linux) are therefore run through the rootfs dynamic loader without
 * chroot; toybox `mount` only makes fresh tmpfs mounts and the su stub binds. If any part of the view
 * cannot be built the command does not run (exit 125, `DEVICE_PROTECTION_UNAVAILABLE`): protection
 * fails closed, and the user can turn it off.
 *
 * Device evidence (Xiaomi 24129PN74C, KernelSU): the mount steps were replayed by hand on 2026-10-17,
 * see `06-CURRENT-GAPS.md`. The capability drop and the full wrapped command have not been run.
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
            append("[ -x \"\$RF/usr/bin/mount\" ] || fail 'rootfs mount not found'; ")
            append("um() { \"\$LOADER\" --library-path \"\$RF/$LIBDIR_REL:\$RF/$LIBDIR2_REL\" \"\$RF/usr/bin/mount\" \"\$@\"; }; ")
            // Everything below must stay inside this namespace: slave propagation keeps what we mount
            // from reaching the host (an empty /dev/block, stubs over system binaries).
            append("um --make-rslave / || fail 'cannot make mounts private'; ")
            // Block nodes: swap the directory for an empty nodev tmpfs before anything else needs it.
            append("/system/bin/mount -t tmpfs -o nodev,noexec,mode=755 tmpfs /dev/block || fail '/dev/block'; ")
            // The su stub lives on a second tmpfs, so /dev/block stays empty.
            append("mkdir -p /dev/.minis-guard && /system/bin/mount -t tmpfs -o nodev,mode=755 tmpfs /dev/.minis-guard || fail 'guard tmpfs'; ")
            append("printf '%s\\n' '#!/system/bin/sh' 'echo \"DEVICE_PROTECTED: su is not available inside the protected view\" >&2' 'exit 126' > /dev/.minis-guard/su || fail 'su stub'; ")
            append("chmod 755 /dev/.minis-guard/su; ")
            // KernelSU shows root processes a /system/bin/su that has no entry in the filesystem
            // (`[ -e ]` says yes, mount(2) says ENOENT, init does not see it), so there is nothing to
            // cover. Only that error is let through; any other failure still fails closed.
            append("for s in ${SU_PATHS.joinToString(" ") { shellQuote(it) }}; do ")
            append("[ -e \"\$s\" ] || continue; ")
            append("e=\$(/system/bin/mount -o bind /dev/.minis-guard/su \"\$s\" 2>&1) && continue; ")
            append("case \"\$e\" in *'No such file'*) continue;; esac; fail \"su \$s\"; done; ")
            append("for d in ${READ_ONLY_DIRS.joinToString(" ")}; do ")
            append("[ -d \"\$d\" ] || continue; ")
            // `--rbind`, not a plain bind: a plain bind copies only the top mount and hides everything
            // mounted below it. On /apex that hides the APEX modules and the runtime linker every
            // Android binary needs, so the next command fails with "No such file".
            append("um --rbind \"\$d\" \"\$d\" && um -o remount,bind,ro \"\$d\" || fail \"\$d\"; done; ")
            append("if [ -d ${shellQuote(minisDir)} ]; then ")
            append("um --rbind ${shellQuote(minisDir)} ${shellQuote(minisDir)} && ")
            append("um -o remount,bind,rw ${shellQuote(minisDir)} || fail 'minis dir'; fi; ")
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
