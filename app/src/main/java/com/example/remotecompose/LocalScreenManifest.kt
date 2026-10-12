package com.example.remotecompose

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client mirror of the server's CURRENT `ScreenManifest` wire shape (multi-section `binary[]`,
 * per-section `dataEndpoints`, optional `pin`). Decoded with unknown keys ignored so the server
 * can add fields (`sheet`, `contract`, …) without touching this file.
 *
 * `--compose <screen>` on the server writes `<screen>.manifest.json` next to the section
 * binaries with each `url` = the asset file name, which is what [loadAsset] reads.
 */
@Serializable
data class LocalScreenManifest(
    val screen: String,
    val layoutVersion: Int = 1,
    val minAppVersion: Int = 0,
    val binary: List<LocalBinaryRef>,
    val statusBarColor: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadAsset(context: Context, screen: String): LocalScreenManifest =
            json.decodeFromString(
                serializer(),
                context.assets.open("$screen.manifest.json").bufferedReader().readText(),
            )
    }
}

@Serializable
data class LocalBinaryRef(
    val url: String,
    val method: String = "GET",
    val sha256: String? = null,
    val dataEndpoints: List<LocalEndpointRef> = emptyList(),
    /** `"top"` / `"bottom"` = fixed outside the scrolling stack; null = a scrolling row. */
    val pin: String? = null,
    /** Tab key for the paging host (PagerStackHost); null = shared by every tab. Ignored by the stack host. */
    val tab: String? = null,
) {
    val isPinnedTop: Boolean get() = pin == "top"
    val isPinnedBottom: Boolean get() = pin == "bottom"
    val isOverlay: Boolean get() = pin == "overlay"
}

@Serializable
data class LocalEndpointRef(
    val url: String,
    val method: String = "GET",
    val key: String? = null,
)
