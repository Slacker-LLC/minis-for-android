package com.openminis.app.runtime.files

/** Small JNI boundary for mkdirat-style creation of missing safe path parts. */
internal object SecureFileOps {
    private const val LIBRARY = "minis_securefs"

    @Volatile
    private var loaded = false

    private fun loadLibrary() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary(LIBRARY)
                loaded = true
            }
        }
    }

    /** Returns 0 on success or a negative errno value on failure. */
    fun ensureDirectories(root: String, components: List<String>, mode: Int = 448): Int {
        loadLibrary()
        return ensureDirectoriesNative(root, components.toTypedArray(), mode)
    }

    @JvmStatic
    private external fun ensureDirectoriesNative(root: String, components: Array<String>, mode: Int): Int
}
