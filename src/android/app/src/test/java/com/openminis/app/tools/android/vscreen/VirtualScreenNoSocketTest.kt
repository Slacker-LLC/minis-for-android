package com.openminis.app.tools.android.vscreen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenNoSocketTest {
    @Test fun userServiceModuleContainsNoNetworkOrLocalSocketListeners() {
        var root: File? = File("").canonicalFile
        while (root != null && !File(root, "src/main/java/com/openminis/app/tools/android/vscreen").isDirectory) {
            root = root.parentFile
        }
        requireNotNull(root) { "Could not locate Android app module root from ${File("").canonicalPath}" }
        val module = root!!
        val sourceRoots = listOf(
            File(module, "src/main/java/com/openminis/app/tools/android/vscreen"),
            File(module, "src/main/aidl/com/openminis/app/tools/android/vscreen"),
        )
        val forbidden = listOf("ServerSocket", "LocalServerSocket", "DatagramSocket", "ServerSocketChannel")
        val offenders = sourceRoots.filter { it.exists() }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "aidl") }.flatMap { file ->
                val text = file.readText()
                forbidden.filter { text.contains(it) }.map { "${file.relativeTo(module)} contains $it" }
            }.toList()
        }
        assertTrue("VScreen must not open a local or network socket: ${offenders.joinToString()}", offenders.isEmpty())
    }
}
