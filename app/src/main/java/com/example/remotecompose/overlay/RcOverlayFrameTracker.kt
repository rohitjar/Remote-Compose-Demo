@file:Suppress("RestrictedApi")

package com.example.remotecompose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity

/** A hole's rect in dp, in the player's own coordinates. */
data class OverlayRect(val xDp: Float, val yDp: Float, val wDp: Float, val hDp: Float) {
    /** The document collapsed this hole (height 0). */
    val collapsed: Boolean get() = hDp <= 0f
}

/**
 * Tracks every hole's rect — port of jar's `rememberOverlayRects`: one `withFrameNanos` loop per
 * player, published only when a rect changes so the overlay layers recompose on a move / collapse
 * and stay quiet otherwise.
 *
 * Where a hole IS: the document tags the hole Box with an authored `componentId` and publishes it;
 * each frame the host asks that component for its laid-out location and size. A document that
 * never tagged the hole falls back to its baked `.x/.y/.w/.h` dp slots.
 */
@Composable
fun rememberOverlayRects(
    handle: RcPlayerHandle?,
    keys: List<OverlayKey>,
): State<Map<OverlayKey, OverlayRect>> {
    val density = LocalDensity.current.density
    val rects = remember(handle) { mutableStateOf<Map<OverlayKey, OverlayRect>>(emptyMap()) }
    if (handle != null && keys.isNotEmpty()) {
        val reader = remember(handle) { RcNamedValueReader(handle) }
        LaunchedEffect(handle, keys, density) {
            var last = rects.value
            while (true) {
                withFrameNanos {
                    val next = LinkedHashMap<OverlayKey, OverlayRect>(keys.size)
                    for (key in keys) {
                        next[key] = resolveHoleRect(handle, reader, key, density) ?: continue
                    }
                    if (next != last) {
                        last = next
                        rects.value = next
                    }
                }
            }
        }
    }
    return rects
}

/**
 * The hole's laid-out rect when it publishes a `componentId` (null until measured — the caller
 * skips that frame), else its baked dp rect.
 */
private fun resolveHoleRect(
    handle: RcPlayerHandle,
    reader: RcNamedValueReader,
    key: OverlayKey,
    density: Float,
): OverlayRect? {
    val componentId = reader.readFloat(OverlaySlots.componentId(key))?.toInt()?.takeIf { it > 0 }
    if (componentId != null) {
        val ctx = handle.remoteContext ?: return null
        val component = runCatching { handle.document.getComponent(componentId) }.getOrNull() ?: return null
        val w = component.width
        if (w <= 0f) return null // not measured yet (a hole is never authored 0 wide)
        val xy = FloatArray(2) // getLocationInWindow ADDS each ancestor's offset into the array
        runCatching { component.getLocationInWindow(ctx, xy) }.getOrElse { return null }
        return OverlayRect(xDp = xy[0] / density, yDp = xy[1] / density, wDp = w / density, hDp = component.height / density)
    }
    val w = reader.readFloat(OverlaySlots.w(key))?.takeIf { it > 0f } ?: return null
    return OverlayRect(
        xDp = reader.readFloat(OverlaySlots.x(key)) ?: 0f,
        yDp = reader.readFloat(OverlaySlots.y(key)) ?: 0f,
        wDp = w,
        hDp = reader.readFloat(OverlaySlots.h(key)) ?: 0f,
    )
}
