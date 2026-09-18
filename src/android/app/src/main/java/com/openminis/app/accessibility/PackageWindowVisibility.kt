package com.openminis.app.accessibility

/** A failed or timed-out query must never be reported as "the window is gone". */
enum class PackageWindowVisibility {
    VISIBLE,
    GONE,
    UNKNOWN,
}

object PackageWindowVisibilityResolver {
    /**
     * @param queried true only when the window snapshot was actually read.
     * @param packageResolved true when a window for the queried package was found.
     */
    fun resolve(queried: Boolean, packageResolved: Boolean): PackageWindowVisibility = when {
        !queried -> PackageWindowVisibility.UNKNOWN
        packageResolved -> PackageWindowVisibility.VISIBLE
        else -> PackageWindowVisibility.GONE
    }
}
