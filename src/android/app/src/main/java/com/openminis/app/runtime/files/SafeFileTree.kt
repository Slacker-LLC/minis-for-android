package com.openminis.app.runtime.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Host-side recursive file operations for guest-controlled trees.
 *
 * Kotlin's java.io.File tree walker follows directory symlinks because it
 * classifies children with File.isDirectory(). Guest files may contain
 * symlinks, so recursive host operations must never use that walker.
 */
internal object SafeFileTree {
    fun isSymbolicLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    fun existsNoFollow(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun deleteRecursively(file: File): Boolean {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
