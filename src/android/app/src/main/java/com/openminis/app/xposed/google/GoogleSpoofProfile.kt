package com.openminis.app.xposed.google

/**
 * [T-eta-xposed-groups] What the Google app is told this device is, and which of its questions the
 * module answers.
 *
 * Ported from Eta `hook/google/GoogleEligibilityHooks.kt`, the identity half of
 * `hook/google/GoogleAppHooks.kt` and the `SPOOF_*` constants in `core/ModuleConfig.kt`
 * (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. Google's feature gate decides
 * whether this device may run the assistant's screen features by asking about the device itself, so
 * every name and value lives in one table and the hooks are one-line reads of it: a field name typed
 * twice is how a spoof silently stops matching after a platform update.
 *
 * The identity is not this device's own - it is the persona Eta's feature gate recognises, and it is
 * only ever answered inside the Google app's process, because that is the only process the framework
 * injects this module into.
 */
object GoogleSpoofProfile {

    /** The property Google reads to decide whether the device is an eligible one. */
    const val OPA_ELIGIBLE_PROPERTY = "ro.opa.eligible_device"

    /** The features Google checks for a Google-experience build. */
    val GOOGLE_FEATURES: Set<String> = setOf(
        "com.google.android.feature.GOOGLE_BUILD",
        "com.google.android.feature.GOOGLE_EXPERIENCE",
    )

    /**
     * The statics of `android.os.Build` the Google app reads, and what they answer. The names are
     * matched exactly by the reflection that writes them: a misspelled one would leave the real
     * field in place and report success.
     */
    val BUILD_FIELDS: Map<String, String> = linkedMapOf(
        "MANUFACTURER" to "samsung",
        "BRAND" to "samsung",
        "MODEL" to "SM-S928B",
        "PRODUCT" to "e3s",
        "DEVICE" to "e3s",
    )

    /** Whether a `SystemProperties` read is one this module answers for. */
    fun forgedProperty(key: String?): Boolean = key == OPA_ELIGIBLE_PROPERTY

    /** Whether a `hasSystemFeature` query is one this module answers for. */
    fun forgedFeature(name: String?): Boolean = name in GOOGLE_FEATURES
}
