package com.threadbare.client.privacy

/**
 * How aggressively [Blocklist] refuses requests.
 *
 * Free of `android.*` so the whole filtering decision can be tested on the JVM.
 */
enum class BlockMode(val key: String) {

    /**
     * Nothing is blocked. Present so a user can prove to themselves that a
     * broken page is the blocklist's fault, without uninstalling the app.
     */
    OFF("off"),

    /** Reddit's own telemetry plus the usual third-party trackers. The default. */
    BALANCED("balanced"),

    /**
     * Adds embedded third-party media and everything Reddit serves purely for
     * measurement. Some embeds will not render. That is the point.
     */
    STRICT("strict");

    companion object {
        fun fromKey(key: String?): BlockMode =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
