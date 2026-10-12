@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.player.core.state.StateUpdater
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Demo stand-in for the jar app's data pipeline (`ScreenData` flattener + `ScreenDataApplier` +
 * `SlotBitmapBinder`): feeds a real API response into a binary's `USER:` slots so an asset
 * renders the way the feed / screen host would show it.
 *
 * `assets/data/<screen>.json` holds `{ "endpoints": { "<manifest key>": <raw API response> } }`
 * — the same shape a manifest section declares. Each payload is flattened under its key
 * (`nek` + `{view:[{header:{textList:[{text:"Explore"}]}}]}` → `nek.view.0.header.textList.0.text`),
 * arrays also emit `<path>.count`, JSON nulls are dropped so a slot keeps its baked default.
 *
 * [apply] then walks the slots the DOCUMENT declares (never a hardcoded field list) and pushes
 * each one by its own path, with the client-side derived ints jar supports:
 *  - `<path>.isPresent` → 1 when `<path>` exists in the payload, else 0;
 *  - `<path>.neq.<LITERAL>` → 0 when `<path>` equals LITERAL, else 1 (absent counts as not equal).
 * Image slots are registered as `<path>#rc-size=WxH#rc-scale=…`; the payload is looked up by the
 * bare path and the bitmap pushed under the FULL registered name ([slotUrl], [loadBitmap]).
 */
object FlatScreenData {
    private const val TAG = "RC_DATA"
    private const val USER_PREFIX = "USER:"
    private const val IS_PRESENT_SUFFIX = ".isPresent"
    private const val NEQ_INFIX = ".neq."
    private const val HINT_FRAGMENT_PREFIX = "#rc-"

    private val json = Json { ignoreUnknownKeys = true }

    /** Flat values for [assetName] (`x.rc` / `x.light.rc` → `data/x.json`), empty when there is no data file. */
    fun loadAsset(context: Context, assetName: String): Map<String, JsonPrimitive> {
        val file = "data/${assetName.substringBefore('.')}.json"
        val text = runCatching { context.assets.open(file).bufferedReader().readText() }.getOrNull()
            ?: return emptyMap<String, JsonPrimitive>().also { Log.d(TAG, "no $file — slots keep their baked defaults") }
        val endpoints = runCatching { json.parseToJsonElement(text).jsonObject["endpoints"]?.jsonObject }
            .onFailure { Log.e(TAG, "$file is not valid JSON", it) }
            .getOrNull() ?: return emptyMap()
        val flat = LinkedHashMap<String, JsonPrimitive>()
        for ((key, payload) in endpoints) flatten(key, payload, flat)
        Log.d(TAG, "$file → ${flat.size} flat values under ${endpoints.keys}")
        return flat
    }

    /** Flattens one endpoint body under its namespace [key] — the same walk [loadAsset] does per endpoint. */
    fun flattenInto(key: String, payload: JsonElement, into: MutableMap<String, JsonPrimitive>) = flatten(key, payload, into)

    private fun flatten(path: String, element: JsonElement, into: MutableMap<String, JsonPrimitive>) {
        when (element) {
            is JsonObject -> for ((key, value) in element) flatten("$path.$key", value, into)
            is JsonArray -> {
                into["$path.count"] = JsonPrimitive(element.size)
                element.forEachIndexed { i, value -> flatten("$path.$i", value, into) }
            }
            is JsonNull -> Unit
            is JsonPrimitive -> into[path] = element
        }
    }

    /** Where [apply] writes: the bind pass uses the StateUpdater, the live data pass the player's view setters. */
    private interface Sink {
        fun string(name: String, v: String)
        fun int(name: String, v: Int)
        fun float(name: String, v: Float)
        fun color(name: String, v: Int)
    }

    /** Pushes every string / int / float / colour slot the document declares. Idempotent; call from `update`. */
    fun apply(document: CoreDocument, stateUpdater: StateUpdater, values: Map<String, JsonPrimitive>) =
        apply(document, values, object : Sink {
            override fun string(name: String, v: String) = stateUpdater.setUserLocalString(name, v)
            override fun int(name: String, v: Int) = stateUpdater.setUserLocalInt(name, v)
            override fun float(name: String, v: Float) = stateUpdater.setUserLocalFloat(name, v)
            override fun color(name: String, v: Int) = stateUpdater.setUserLocalColor(name, v)
        })

    /**
     * Same as [apply] for a LIVE document (data arrived after the bind): the player's own setters
     * mark the document for re-layout, so text widths / gated heights update; the StateUpdater
     * path only repaints.
     */
    fun applyLive(document: CoreDocument, player: androidx.compose.remote.player.view.RemoteComposePlayer, values: Map<String, JsonPrimitive>) =
        apply(document, values, object : Sink {
            override fun string(name: String, v: String) = player.setUserLocalString(name, v)
            override fun int(name: String, v: Int) = player.setUserLocalInt(name, v)
            override fun float(name: String, v: Float) = player.setUserLocalFloat(name, v)
            override fun color(name: String, v: Int) = player.setUserLocalColor(name, v)
        })

    private fun apply(document: CoreDocument, values: Map<String, JsonPrimitive>, out: Sink) {
        if (values.isEmpty()) return
        forEachSlot(document, NamedVariable.STRING_TYPE) { name ->
            values[name]?.contentOrNull?.let { out.string(name, it) }
        }
        forEachSlot(document, NamedVariable.INT_TYPE) { name ->
            when {
                name.endsWith(IS_PRESENT_SUFFIX) -> {
                    val parent = name.removeSuffix(IS_PRESENT_SUFFIX)
                    out.int(name, if (values[parent] != null) 1 else 0)
                }
                name.contains(NEQ_INFIX) -> {
                    val parent = name.substringBeforeLast(NEQ_INFIX)
                    val literal = name.substringAfterLast(NEQ_INFIX)
                    out.int(name, if (values[parent]?.contentOrNull == literal) 0 else 1)
                }
                else -> intOf(values[name])?.let { out.int(name, it) }
            }
        }
        forEachSlot(document, NamedVariable.FLOAT_TYPE) { name ->
            // jar's flattener stamps `<path>.isPresent` / `<path>.count` into the value map, so a
            // FLOAT slot of that name gets a number there; mirror it here (was INT-only → every
            // float-gated element — chips, hero pages, badges — stayed at 0 px in the demo).
            when {
                name.endsWith(IS_PRESENT_SUFFIX) -> {
                    val parent = name.removeSuffix(IS_PRESENT_SUFFIX)
                    val present = values[parent] != null || values.keys.any { it.startsWith("$parent.") }
                    out.float(name, if (present) 1f else 0f)
                }
                else -> values[name]?.let { v ->
                    (v.contentOrNull?.toFloatOrNull() ?: v.booleanOrNull?.let { if (it) 1f else 0f })
                        ?.let { out.float(name, it) }
                }
            }
        }
        forEachSlot(document, NamedVariable.COLOR_TYPE) { name ->
            val hex = values[name]?.contentOrNull ?: return@forEachSlot
            runCatching { android.graphics.Color.parseColor(hex) }
                .onSuccess { out.color(name, it) }
                .onFailure { Log.w(TAG, "bad colour '$hex' for slot '$name' — keeping the baked default") }
        }
    }

    /**
     * The URL an image slot (registered name minus `USER:`) resolves to, or null. `http(s)://`
     * is fetched; `asset://img/x.png` is read from the APK's assets — the demo's stand-in for a
     * CDN while the real APIs do not exist yet (jar would receive CDN URLs in the payload).
     */
    fun slotUrl(values: Map<String, JsonPrimitive>, slot: String): String? =
        values[slot.substringBefore(HINT_FRAGMENT_PREFIX)]?.contentOrNull
            ?.takeIf { it.startsWith("http") || it.startsWith(ASSET_SCHEME) }

    private const val ASSET_SCHEME = "asset://"

    /** Fetches [url] and conforms it to exactly [w]×[h] (the slot's declared dims) — cover-crop, then scale. */
    /** See [loadBitmap]: true = push payload bitmaps at their original size, like jar does for
     *  hosts it cannot proxy; false = conform to the slot's declared size (the imgproxy-eligible case). */
    private const val DELIVER_ORIGINAL_SIZE = true

    suspend fun loadBitmap(context: Context, url: String, w: Int, h: Int): Bitmap? {
        if (url.startsWith(ASSET_SCHEME)) {
            val path = url.removePrefix(ASSET_SCHEME)
            val bmp = runCatching {
                context.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null.also { Log.w(TAG, "asset image missing: $path") }
            // Assets are design exports (often 1000–4000 px); scale to the slot like imgproxy would.
            if (bmp.width <= w && bmp.height <= h) return bmp
            val scale = minOf(w / bmp.width.toFloat(), h / bmp.height.toFloat())
            return Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context).data(url).allowHardware(false).build(),
        )
        val source = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
            ?: return null.also { Log.w(TAG, "image slot fetch failed: $url") }
        // Test bench for jar's delivery: jar only resizes through imgproxy for cdn.myjar.app, so
        // everything else (shopnek.com thumbnails) reaches the player at its ORIGINAL size. With
        // this on, the demo pushes originals too, so a slot drawn through a declared-dims
        // ImageLayout corner-crops here exactly as it does in jar.
        if (DELIVER_ORIGINAL_SIZE) return source
        if (source.width == w && source.height == h) return source
        val scale = maxOf(w / source.width.toFloat(), h / source.height.toFloat())
        val cropW = (w / scale).toInt().coerceIn(1, source.width)
        val cropH = (h / scale).toInt().coerceIn(1, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - cropW) / 2, (source.height - cropH) / 2, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    private inline fun forEachSlot(document: CoreDocument, type: Int, block: (name: String) -> Unit) {
        val names = runCatching { document.getNamedVariables(type) }.getOrNull() ?: return
        for (full in names) block(full.removePrefix(USER_PREFIX))
    }

    /** Numbers, numeric strings, or booleans — the natural JSON shapes a flag serialises to. */
    private fun intOf(v: JsonPrimitive?): Int? {
        if (v == null) return null
        v.booleanOrNull?.let { return if (it) 1 else 0 }
        val c = v.contentOrNull ?: return null
        return c.toIntOrNull() ?: c.toDoubleOrNull()?.toInt()
    }
}
