package com.example.remotecompose

import kotlinx.serialization.Serializable

/**
 * Per-user data payload for a screen's named `USER:` slots, fetched from the manifest's
 * data link. Mirrors the server's ScreenData contract: values grouped by slot type so they
 * can be applied generically via `StateUpdater.setUserLocal*` — the client never hardcodes
 * a screen's field names, which is what keeps it a generic SDUI host. Keys are bare slot
 * names; the `USER:` prefix is added by StateUpdater itself.
 */
@Serializable
data class ScreenData(
    val strings: Map<String, String> = emptyMap(),
    /** Ints double as visibility toggles: 1 = VISIBLE, 0 = GONE, 2 = INVISIBLE. */
    val ints: Map<String, Int> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    /** ARGB hex, e.g. "#FF6B00" — parsed with android.graphics.Color.parseColor. */
    val colors: Map<String, String> = emptyMap(),
    /** URLs the client loads itself (Coil), then pushes via setUserLocalBitmap. */
    val bitmapUrls: Map<String, String> = emptyMap(),
)
