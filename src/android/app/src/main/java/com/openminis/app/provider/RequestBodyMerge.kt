package com.openminis.app.provider

import org.json.JSONObject

/**
 * [T-eta-provider-passthrough] Recursive merge of caller-supplied request-body
 * fields into the body the app builds.
 *
 * Ported from Eta `agent/model/RequestBodyMerge.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md.
 *
 * Rules (unchanged from Eta):
 * - both sides are objects → merge recursively, so overriding
 *   `reasoning.effort` keeps the rest of `reasoning`;
 * - otherwise the caller's value wins — arrays replace wholesale, scalars and
 *   nulls overwrite the app's value.
 *
 * Objects the app did not already have are copied, so later mutation of the
 * caller's map cannot alter the body that is about to be serialized.
 */
object RequestBodyMerge {

    fun mergeInto(target: JSONObject, customBody: Map<String, Any?>) {
        for ((key, value) in customBody) mergeValue(target, key, value)
    }

    private fun mergeValue(target: JSONObject, key: String, value: Any?) {
        if (value == null || value === JSONObject.NULL) {
            target.put(key, JSONObject.NULL)
            return
        }
        if (value is JSONObject) {
            val existing = target.optJSONObject(key)
            if (existing != null) {
                for (childKey in value.keys()) mergeValue(existing, childKey, value.opt(childKey))
            } else {
                target.put(key, JSONObject(value.toString()))
            }
            return
        }
        target.put(key, value)
    }
}
