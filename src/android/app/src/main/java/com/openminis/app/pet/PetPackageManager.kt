package com.openminis.app.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object PetPackageManager {
    private const val MAX_ENTRY_BYTES = 24L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    private const val MAX_FILES = 64

    fun petsRoot(context: Context): File = File(context.filesDir, "pets").apply { mkdirs() }

    fun listInstalled(context: Context): List<InstalledPet> =
        petsRoot(context).listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { loadDirectory(it).getOrNull() }
            .sortedBy { it.manifest.displayName.lowercase() }

    fun selected(context: Context): InstalledPet? {
        val id = PetPreferences.selectedPetId(context) ?: return null
        return loadDirectory(File(petsRoot(context), id)).getOrNull()
    }

    fun importZip(context: Context, uri: Uri): Result<InstalledPet> = runCatching {
        val staging = File(context.cacheDir, "pet-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "Cannot open selected file" }
                ZipInputStream(raw.buffered()).use { zip ->
                    var total = 0L
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        count++
                        require(count <= MAX_FILES) { "Pet package contains too many files" }
                        val out = safeChild(staging, entry.name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { sink ->
                            val buffer = ByteArray(32 * 1024)
                            var entryTotal = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                entryTotal += n
                                total += n
                                require(entryTotal <= MAX_ENTRY_BYTES) { "Pet package entry is too large" }
                                require(total <= MAX_TOTAL_BYTES) { "Pet package is too large" }
                                sink.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }

            val manifestFile = staging.walkTopDown()
                .maxDepth(2)
                .firstOrNull { it.isFile && it.name == "pet.json" }
                ?: error("pet.json not found")
            val packageRoot = manifestFile.parentFile ?: error("Invalid package root")
            val manifest = PetManifest.parse(manifestFile.readText(Charsets.UTF_8))
            val sprite = safeChild(packageRoot, manifest.spritesheetPath)
            require(sprite.isFile) { "${manifest.spritesheetPath} not found" }
            validateBitmap(sprite, manifest)

            val root = petsRoot(context)
            val finalDir = File(root, manifest.id)
            val installStaging = File(root, ".${manifest.id}.staging-${UUID.randomUUID()}")
            val backup = File(root, ".${manifest.id}.backup-${UUID.randomUUID()}")
            copyTree(packageRoot, installStaging)
            // Validate the exact staged copy before touching an existing install.
            loadDirectory(installStaging).getOrThrow()

            var movedOld = false
            try {
                if (finalDir.exists()) {
                    require(finalDir.renameTo(backup)) { "Failed to stage existing pet for replacement" }
                    movedOld = true
                }
                require(installStaging.renameTo(finalDir)) { "Failed to atomically install pet" }
                backup.deleteRecursively()
            } catch (t: Throwable) {
                installStaging.deleteRecursively()
                if (movedOld && !finalDir.exists()) backup.renameTo(finalDir)
                throw t
            }

            val installed = loadDirectory(finalDir).getOrThrow()
            PetPreferences.setSelectedPetId(context, manifest.id)
            installed
        } finally {
            staging.deleteRecursively()
        }
    }

    fun loadDirectory(dir: File): Result<InstalledPet> = runCatching {
        val manifestFile = File(dir, "pet.json")
        require(manifestFile.isFile) { "pet.json missing" }
        val manifest = PetManifest.parse(manifestFile.readText(Charsets.UTF_8))
        val sprite = safeChild(dir, manifest.spritesheetPath)
        require(sprite.isFile) { "spritesheet missing" }
        validateBitmap(sprite, manifest)
        InstalledPet(manifest, dir, sprite)
    }

    private fun validateBitmap(file: File, manifest: PetManifest) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported or corrupt spritesheet" }
        require(options.outWidth == manifest.atlasWidth && options.outHeight == manifest.atlasHeight) {
            "Spritesheet must be ${manifest.atlasWidth}x${manifest.atlasHeight}; got ${options.outWidth}x${options.outHeight}"
        }
        val pixels = options.outWidth.toLong() * options.outHeight.toLong()
        require(pixels <= 16_000_000L) { "Spritesheet is too large to decode safely" }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank()) { "Empty package path" }
        require(!File(relative).isAbsolute) { "Absolute paths are forbidden" }
        val out = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val outPath = out.canonicalFile.toPath()
        require(outPath.startsWith(rootPath)) { "Unsafe ZIP path: $relative" }
        return out
    }

    private fun copyTree(source: File, target: File) {
        if (target.exists()) target.deleteRecursively()
        source.walkTopDown().forEach { src ->
            val rel = src.relativeTo(source)
            val dst = File(target, rel.path)
            if (src.isDirectory) dst.mkdirs() else {
                dst.parentFile?.mkdirs()
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
