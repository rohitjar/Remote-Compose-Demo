package com.example.remotecompose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Client mirror of the server's request envelope, sent as the JSON body of every manifest
 * link that declares `method: POST` (data, binary, …).
 *
 * - [args] — opaque screen arguments. Authored server-side into the host action that
 *   navigated here; the client relays them verbatim and never parses them.
 * - [context] — standard client capabilities, the same shape for every screen.
 */
@Serializable
data class ScreenRequest(
    val args: JsonObject? = null,
    val context: ClientContext = ClientContext(),
)

@Serializable
data class ClientContext(
    val density: Float? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val locale: String? = null,
)
