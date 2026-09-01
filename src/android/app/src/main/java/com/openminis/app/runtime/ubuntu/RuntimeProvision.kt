package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.minisd.MinisdProtocol

/** Packaged runtime install paths and fail-closed shell snippets. */
object RuntimeProvision {
    const val ROOTFS_ASSET = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
    const val PACKAGED_BROKER_NAME = "libminisd.so"
    const val STAGED_ROOTFS_ARCHIVE = "/data/adb/minis/runtime/staging/ubuntu-arm64-rootfs.tar.gz"
    const val HOST_BIN = MinisdProtocol.DEFAULT_BIN

    fun installBrokerCommand(packagedBroker: String, destBin: String = HOST_BIN): String {
        val src = shellQuote(packagedBroker)
        val bin = shellQuote(destBin)
        val parent = shellQuote(destBin.substringBeforeLast('/', destBin))
        return """
SRC=$src
BIN=$bin
if [ -f "${'$'}SRC" ]; then
  mkdir -p $parent || { echo 'cannot create minisd bin directory' >&2; exit 41; }
  cp "${'$'}SRC" "${'$'}BIN.tmp" || { echo 'cannot copy packaged minisd' >&2; exit 42; }
  chmod 755 "${'$'}BIN.tmp" || { echo 'cannot chmod packaged minisd' >&2; exit 42; }
  mv -f "${'$'}BIN.tmp" "${'$'}BIN" || { echo 'cannot install packaged minisd' >&2; exit 42; }
fi
if [ ! -x "${'$'}BIN" ]; then echo "minisd missing or not executable: ${'$'}BIN" >&2; exit 40; fi
        """.trimIndent()
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
