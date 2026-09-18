package com.openminis.app.voicecall.bloub

import android.content.Context

/** SVG 素材读取缓存：按名称从 assets/moods/ 读一次并缓存。 */
object AssetCache {
    private val svgCache = mutableMapOf<String, String>()

    fun getSvg(context: Context, name: String): String {
        return svgCache.getOrPut(name) {
            try {
                context.assets.open("moods/$name.svg").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        }
    }
}
