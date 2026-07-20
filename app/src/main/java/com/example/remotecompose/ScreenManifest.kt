package com.example.remotecompose

import kotlinx.serialization.Serializable

/**
 * Client mirror of the server's ScreenManifest — the bootstrap document listing the links
 * the app follows verbatim. Unknown fields are ignored on decode, so the server can add
 * capabilities without breaking shipped clients.
 */
@Serializable
data class ScreenManifest(
    val screen: String,
    val layoutVersion: Int,
    val binary: Endpoint,
    val data: Endpoint,
    val analytics: Endpoint,
) {
    /** A link the client follows using its declared method (see RemoteScreenService.request). */
    @Serializable
    data class Endpoint(
        val url: String,
        val method: String = "GET",
    )
}
