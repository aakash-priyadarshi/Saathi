package org.saathi.android

import org.json.JSONObject

enum class TransportCapability {
    TEXT,
    SMALL_CONTROL,
    SMALL_STRUCTURED_EVENT,
    SMALL_ATTACHMENT,
    LARGE_ATTACHMENT,
    VOICE,
    VIDEO,
    HIGH_BANDWIDTH,
    SERVER_RELAY,
}

/** Shared capability policy for direct transports; transport names do not decide routing. */
internal object TransportCapabilities {
    fun of(media: Boolean, files: Boolean): Set<TransportCapability> = buildSet {
        add(TransportCapability.TEXT)
        add(TransportCapability.SMALL_CONTROL)
        add(TransportCapability.SMALL_STRUCTURED_EVENT)
        if (files) {
            add(TransportCapability.SMALL_ATTACHMENT)
            add(TransportCapability.LARGE_ATTACHMENT)
            add(TransportCapability.HIGH_BANDWIDTH)
        }
        if (media) {
            add(TransportCapability.VOICE)
            add(TransportCapability.VIDEO)
            add(TransportCapability.HIGH_BANDWIDTH)
        }
    }

    fun required(kind: String, value: Any): TransportCapability = when {
        kind == "MESSAGE" || kind == "CHAT_MESSAGE" || kind == "CHAT_REPLY" -> TransportCapability.TEXT
        kind == "EVENT" || kind == "RECEIPT" || kind == "INVENTORY" || kind == "NEED" || kind.startsWith("COMMUNITY_") -> TransportCapability.SMALL_STRUCTURED_EVENT
        kind == "CALL" -> if ((value as? JSONObject)?.optBoolean("video") == true) TransportCapability.VIDEO else TransportCapability.VOICE
        kind.startsWith("FILE_") -> TransportCapability.LARGE_ATTACHMENT
        else -> TransportCapability.SMALL_CONTROL
    }
}
