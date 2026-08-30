package io.github.slackerllc.minis.pet

import org.json.JSONObject

/**
 * Universal pet package manifest.
 *
 * The minimal contract intentionally stays Codex-compatible:
 *   pet.json + spritesheet.webp, 8x9 cells, 192x208 each.
 * Optional `format` and `animations` keys let other pet packs override the
 * atlas geometry/timing without making them incompatible with the default.
 */
data class PetManifest(
    val id: String,
    val displayName: String,
    val description: String?,
    val spritesheetPath: String,
    val columns: Int = 8,
    val rows: Int = 9,
    val cellWidth: Int = 192,
    val cellHeight: Int = 208,
    val animations: Map<PetState, PetAnimation> = emptyMap(),
) {
    val atlasWidth: Int get() = columns * cellWidth
    val atlasHeight: Int get() = rows * cellHeight

    fun animation(state: PetState): PetAnimation = animations[state] ?: state.defaultAnimation

    companion object {
        fun parse(text: String): PetManifest {
            val root = JSONObject(text)
            val format = root.optJSONObject("format")
            val id = root.getString("id").trim()
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Invalid pet id" }

            val sprite = root.optString("spritesheetPath", "spritesheet.webp").trim()
            require(sprite.isNotEmpty()) { "spritesheetPath is empty" }
            require(!sprite.startsWith('/') && !sprite.startsWith('\\')) { "spritesheetPath must be relative" }
            require(sprite.split('/', '\\').none { it == ".." }) { "spritesheetPath may not contain .." }

            val columns = format?.optInt("columns", 8) ?: 8
            val rows = format?.optInt("rows", 9) ?: 9
            val cellWidth = format?.optInt("cellWidth", 192) ?: 192
            val cellHeight = format?.optInt("cellHeight", 208) ?: 208
            require(columns in 1..32 && rows in 1..32) { "Invalid atlas grid" }
            require(cellWidth in 16..2048 && cellHeight in 16..2048) { "Invalid cell size" }
            val atlasPixels = columns.toLong() * rows.toLong() * cellWidth.toLong() * cellHeight.toLong()
            require(atlasPixels <= 16_000_000L) { "Atlas dimensions are too large" }

            val animationOverrides = mutableMapOf<PetState, PetAnimation>()
            root.optJSONObject("animations")?.let { objectNode ->
                PetState.entries.forEach { state ->
                    val node = objectNode.optJSONObject(state.jsonName) ?: return@forEach
                    val fallback = state.defaultAnimation
                    val row = node.optInt("row", fallback.row)
                    val frames = node.optInt("frames", fallback.frameCount)
                    val duration = node.optLong("frameDurationMs", fallback.frameDurationMs)
                    require(row in 0 until rows) { "${state.jsonName}.row is outside atlas" }
                    require(frames in 1..columns) { "${state.jsonName}.frames must be 1..$columns" }
                    require(duration in 40L..10_000L) { "${state.jsonName}.frameDurationMs out of range" }
                    animationOverrides[state] = PetAnimation(row, frames, duration)
                }
            }

            // The nine default states must still be representable when the package
            // omits explicit animation metadata.
            PetState.entries.forEach { state ->
                val animation = animationOverrides[state] ?: state.defaultAnimation
                require(animation.row < rows) { "Atlas does not contain ${state.jsonName}" }
                require(animation.frameCount <= columns) { "Atlas has too few columns for ${state.jsonName}" }
            }

            return PetManifest(
                id = id,
                displayName = root.optString("displayName", id).ifBlank { id }.take(64),
                description = root.optString("description").takeIf { it.isNotBlank() }?.take(512),
                spritesheetPath = sprite,
                columns = columns,
                rows = rows,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                animations = animationOverrides,
            )
        }
    }
}

data class PetAnimation(
    val row: Int,
    val frameCount: Int,
    val frameDurationMs: Long,
)

/** Default row contract for existing Codex-style 8x9 pet packages. */
enum class PetState(
    val jsonName: String,
    val defaultAnimation: PetAnimation,
) {
    IDLE("idle", PetAnimation(0, 6, 150)),
    RUNNING_RIGHT("running-right", PetAnimation(1, 8, 95)),
    RUNNING_LEFT("running-left", PetAnimation(2, 8, 95)),
    WAVING("waving", PetAnimation(3, 4, 150)),
    JUMPING("jumping", PetAnimation(4, 5, 120)),
    FAILED("failed", PetAnimation(5, 8, 180)),
    WAITING("waiting", PetAnimation(6, 6, 180)),
    RUNNING("running", PetAnimation(7, 6, 110)),
    REVIEW("review", PetAnimation(8, 6, 160)),
}

data class InstalledPet(
    val manifest: PetManifest,
    val directory: java.io.File,
    val spritesheet: java.io.File,
)
