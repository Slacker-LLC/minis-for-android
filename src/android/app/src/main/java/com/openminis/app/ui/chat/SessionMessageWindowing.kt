package com.openminis.app.ui.chat

/**
 * Pure helper for pagination and windowing long chat session histories.
 */
object SessionMessageWindowing {
    const val DEFAULT_WINDOW_SIZE = 100

    data class Window(
        val offset: Int,
        val limit: Int,
        val isWindowed: Boolean,
    )

    /**
     * Calculate initial pagination window based on total message count and configured window size.
     */
    fun calculateInitialWindow(totalCount: Int, windowSize: Int = DEFAULT_WINDOW_SIZE): Window {
        val safeWindowSize = if (windowSize > 0) windowSize else DEFAULT_WINDOW_SIZE
        return if (totalCount > safeWindowSize) {
            Window(
                offset = totalCount - safeWindowSize,
                limit = safeWindowSize,
                isWindowed = true,
            )
        } else {
            Window(
                offset = 0,
                limit = totalCount,
                isWindowed = false,
            )
        }
    }
}
