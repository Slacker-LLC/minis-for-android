package com.openminis.app.data.repository

/** Availability facts already resolved for one declared model entry. */
internal data class MemberAvailability<T>(
    val value: T,
    val hidden: Boolean,
    val providerEnabled: Boolean,
    val credentialed: Boolean,
)

/**
 * Applies the provider repository's existing member eligibility checks while
 * retaining the declaration order and skipping ids without a corresponding row.
 */
internal fun <T> availableMembersInDeclarationOrder(
    declaredIds: List<String>,
    entriesById: Map<String, MemberAvailability<T>>,
): List<T> = declaredIds.mapNotNull { id ->
    entriesById[id]?.takeIf { !it.hidden && it.providerEnabled && it.credentialed }?.value
}
