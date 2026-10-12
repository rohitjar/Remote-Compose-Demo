@file:Suppress("RestrictedApi")

package com.example.remotecompose.overlay

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.ui.layout.ContentScale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `USER:` — the prefix every document slot carries; `setUserLocal*` adds it. */
internal const val USER_PREFIX = "USER:"

/**
 * What the host floats over a hole. [wire] is the middle segment of every slot name
 * (`overlay.<wire>.<id>.*`); a hole exists once the document declares its [discoveryKey] slot.
 * Port of jar's `OverlayKind`, trimmed to what the demo floats (jar also has video, audio,
 * text-field and button holes, which need its native widgets).
 */
enum class OverlayKind(val wire: String, val discoveryKey: String) {
    Lottie("lottie", "url"),
}

/** One hole: its kind and the document-chosen id (`overlay.<kind>.<id>.*`). */
data class OverlayKey(val kind: OverlayKind, val id: String)

/**
 * The overlay-hole slot contract — port of jar's `OverlaySlots`, mirror of the server's
 * `core-ui/.../overlay/OverlaySlots.kt`. Remote Compose cannot host a Lottie, so a document
 * reserves a HOLE (a sized Box tagged with a `componentId`) and publishes facts about it as named
 * `USER:` slots; the host floats the native widget over the hole.
 *
 * Beyond jar's copy: `.contentScale` (DOC → host) and the playback slots `.time` / `.done`
 * (HOST → doc) the server added so a document can follow a play-once Lottie's timeline.
 *
 * All names here are BARE (no `USER:` prefix): `setUserLocal*` and [RcNamedValueReader] add it.
 */
object OverlaySlots {
    private const val PREFIX = "overlay."

    private fun base(key: OverlayKey) = "$PREFIX${key.kind.wire}.${key.id}"

    // ── DOC → host: geometry ────────────────────────────────────────────────────────
    /** float — authored `componentId` of the hole Box → `document.getComponent(id)` (primary rect source). */
    fun componentId(key: OverlayKey) = "${base(key)}.componentId"
    /** float dp — baked fallback rect. */
    fun x(key: OverlayKey) = "${base(key)}.x"
    fun y(key: OverlayKey) = "${base(key)}.y"
    fun w(key: OverlayKey) = "${base(key)}.w"
    fun h(key: OverlayKey) = "${base(key)}.h"
    /** float dp — clip radius for the host widget. */
    fun radius(key: OverlayKey) = "${base(key)}.radius"

    // ── DOC → host: media. `.url` is the DISCOVERY KEY ──────────────────────────────
    /** string — `http(s)://…`, or a flat data path resolved against the screen data ([resolveUrl]). */
    fun url(key: OverlayKey) = "${base(key)}.url"
    /** float 0/1 — restart at the end (absent → 1). 0 plays once and reports [done]. */
    fun loop(key: OverlayKey) = "${base(key)}.loop"
    /** float 0..1 — play-once only: playback fraction at which the view counts → host result `media_shown{id}`. */
    fun shownAt(key: OverlayKey) = "${base(key)}.shownAt"
    /** string — `crop` (absent) | `fillWidth` | `fit` ([contentScaleOf]). */
    fun contentScale(key: OverlayKey) = "${base(key)}.contentScale"

    // ── HOST → doc: playback, pushed into every binary that declares it ─────────────
    /** float — seconds played, every frame while it plays. */
    fun time(key: OverlayKey) = "${base(key)}.time"
    /** float 0/1 — 0 while loading / playing, 1 once finished, failed or never started. */
    fun done(key: OverlayKey) = "${base(key)}.done"

    /** Every hole of [kind] the document declares, found by its [OverlayKind.discoveryKey] string slot. */
    fun keys(document: CoreDocument, kind: OverlayKind): List<OverlayKey> {
        val prefix = "$PREFIX${kind.wire}."
        val suffix = ".${kind.discoveryKey}"
        return runCatching { document.getNamedVariables(NamedVariable.STRING_TYPE) }.getOrNull()
            .orEmpty()
            .mapNotNull { raw ->
                val name = raw.removePrefix(USER_PREFIX)
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) return@mapNotNull null
                val id = name.substring(prefix.length, name.length - suffix.length)
                if (id.isEmpty() || '.' in id) null else OverlayKey(kind, id)
            }
    }

    /**
     * A media slot's value is either a URL or a flat DATA PATH (e.g. `options.data.guidingLottie.url`)
     * the document uses to bind a per-user URL without the generic layer knowing the feature.
     * Resolves either form; null when blank / unresolvable.
     */
    fun resolveUrl(raw: String?, values: Map<String, JsonPrimitive>?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (isHttpUrl(v)) return v
        val fromData = values?.get(v)?.contentOrNull?.trim().orEmpty()
        return fromData.takeIf { isHttpUrl(it) }
    }

    fun contentScaleOf(wire: String?): ContentScale = when (wire) {
        "fillWidth" -> ContentScale.FillWidth
        "fit" -> ContentScale.Fit
        else -> ContentScale.Crop
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
