package com.openminis.app.xposed.system

/**
 * [T-eta-xposed-groups] The facts about ColorOS SystemUI's OCR long press that the hook needs.
 *
 * Ported from Eta `hook/system/SystemUiHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The names are kept as a list, in the order they are tried, because that is
 * the part that silently stops working: the OCR surface carries its context under different member
 * names across SystemUI releases, and a name that no longer matches turns the takeover into a
 * takeover that never fires.
 */
object SystemUiOcrPolicy {

    /** The haptic effect the ROM's own OCR long press uses, replayed when the search takes over. */
    const val OCR_LONG_PRESS_HAPTIC_EFFECT_ID = 1

    /** Tried before any field: the surface's own accessor. */
    val CONTEXT_METHOD_NAMES: List<String> = listOf("getContext")

    /** Then the members, in the order the releases have carried them. */
    val CONTEXT_FIELD_NAMES: List<String> = listOf("context", "mContext", "mOcrContext")
}
