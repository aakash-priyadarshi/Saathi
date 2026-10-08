package org.saathi.android

import org.json.JSONObject

internal object PublicMediaCleanupPolicy {
    fun shouldClear(file: JSONObject, pendingPublicMediaIds: Set<String>): Boolean =
        file.optBoolean("publicOnly") &&
            !file.optBoolean("chatOnly") &&
            file.optBoolean("complete") &&
            file.optString("id").isNotBlank() &&
            file.optString("id") !in pendingPublicMediaIds
}
