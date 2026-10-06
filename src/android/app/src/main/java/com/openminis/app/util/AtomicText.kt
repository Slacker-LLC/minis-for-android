package com.openminis.app.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to [file] through a temporary sibling and a rename, so a failure or a kill midway
 * leaves the previous content intact. False when anything failed.
 */
fun atomicWriteText(file: File, text: String): Boolean {
    val tmp = File(file.parentFile, file.name + ".tmp")
    return try {
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: Exception) {
        runCatching { tmp.delete() }
        false
    }
}
