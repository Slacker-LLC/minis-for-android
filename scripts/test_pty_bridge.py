"""Run the production PTY JNI implementation on Linux with a real JVM and child processes."""

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

JAVA = r'''
package com.openminis.app.sandbox;

public class PtyBridge {
    static { System.load(System.getProperty("fixture.library")); }
    static native int forkExec(String cmd, String[] argv, String[] env, String cwd, int cols, int rows, int[] pid);
    static native int readBytes(int fd, byte[] bytes, int off, int len);
    static native int writeBytes(int fd, byte[] bytes, int off, int len);
    static native int closeFd(int fd);
    static native int terminateAndWait(int pid);

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static String readUntil(int fd, String marker) {
        byte[] bytes = new byte[128];
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            int count = readBytes(fd, bytes, 0, bytes.length);
            if (count == -11) continue;
            if (count <= 0) return output.toString();
            output.append(new String(bytes, 0, count));
            if (marker != null && output.toString().contains(marker)) return output.toString();
        }
        throw new AssertionError("PTY read exceeded deadline: " + output);
    }

    public static void main(String[] args) {
        int[] pid = new int[1];
        int fd = forkExec("/bin/sh", new String[]{"sh", "-c", "printf fixture; exit 7"},
            new String[]{"PATH=/usr/bin:/bin"}, "/", 80, 24, pid);
        check(fd >= 0 && pid[0] > 0, "fork failed");
        try {
            check(readUntil(fd, null).contains("fixture"), "output lost before EOF");
            check(terminateAndWait(pid[0]) == 7, "normal exit status lost");
            check(terminateAndWait(pid[0]) == -10, "child must be reaped exactly once");
        } finally {
            closeFd(fd);
            terminateAndWait(pid[0]);
        }

        fd = forkExec("/bin/sh", new String[]{"sh", "-c", "trap '' HUP TERM; printf ready; exec sleep 60"},
            new String[]{"PATH=/usr/bin:/bin"}, "/", 80, 24, pid);
        check(fd >= 0, "second fork failed");
        try {
            check(readUntil(fd, "ready").contains("ready"), "child did not become ready");
            byte[] bytes = new byte[8];
            check(readBytes(fd, bytes, 1, 8) == -22, "read must reject array overrun");
            check(writeBytes(fd, bytes, -1, 8) == -22, "write must reject negative offset");
            long started = System.nanoTime();
            check(readBytes(fd, bytes, 0, 8) == -11, "quiet PTY must yield for cancellation");
            check(System.nanoTime() - started < 2_000_000_000L, "quiet read blocked");
            started = System.nanoTime();
            check(terminateAndWait(pid[0]) == -137, "SIGTERM-resistant child was not killed and reaped");
            check(System.nanoTime() - started < 5_000_000_000L, "termination exceeded grace period");
        } finally {
            closeFd(fd);
            terminateAndWait(pid[0]);
        }
    }
}
'''


@unittest.skipUnless(sys.platform.startswith("linux"), "PTY host regression requires Linux")
class PtyBridgeTest(unittest.TestCase):
    def test_real_jni_io_eof_bounds_and_reaping(self):
        javac = shutil.which("javac")
        compiler = shutil.which("cc")
        self.assertIsNotNone(javac, "JDK is required")
        self.assertIsNotNone(compiler, "C compiler is required")
        java_home = pathlib.Path(javac).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="minis-pty-") as directory:
            work = pathlib.Path(directory)
            (work / "android").mkdir()
            # Only replace Android logging; all PTY/JNI/process code is production code.
            (work / "android/log.h").write_text(
                "#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_ERROR 6\n"
                "static inline int __android_log_print(int p, const char *tag, const char *fmt, ...) { return 0; }\n"
            )
            library = work / "libpty_bridge.so"
            subprocess.run([
                compiler, "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                f"-I{java_home / 'include'}", f"-I{java_home / 'include/linux'}", f"-I{work}",
                str(ROOT / "src/android/app/src/main/cpp/pty_bridge.c"), "-lutil", "-o", str(library),
            ], check=True, timeout=30)
            source = work / "PtyBridge.java"
            source.write_text(JAVA)
            subprocess.run([javac, "-d", str(work), str(source)], check=True, timeout=30)
            subprocess.run([
                str(java_home / "bin/java"), f"-Dfixture.library={library}", "-cp", str(work),
                "com.openminis.app.sandbox.PtyBridge",
            ], check=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
