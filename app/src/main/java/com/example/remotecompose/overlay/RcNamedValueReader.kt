@file:Suppress("RestrictedApi")

package com.example.remotecompose.overlay

/**
 * Reads named `USER:` variables OUT of a live player — port of jar's `RcNamedValueReader`. Overlay
 * layers use it every frame for hole geometry and every recomposition for the URLs / facts a
 * document publishes. Takes BARE names ([OverlaySlots]); name → id lookups are cached per handle.
 */
class RcNamedValueReader(private val handle: RcPlayerHandle) {

    private val ids = HashMap<String, Int?>()

    fun readFloat(name: String): Float? {
        val ctx = handle.remoteContext ?: return null
        val id = idOf(name) ?: return null
        return runCatching { ctx.getFloat(id) }.getOrNull()?.takeIf { !it.isNaN() }
    }

    fun readText(name: String): String? {
        val ctx = handle.remoteContext ?: return null
        val id = idOf(name) ?: return null
        return runCatching { ctx.getText(id) }.getOrNull()
    }

    private fun idOf(name: String): Int? {
        ids[name]?.let { return it }
        if (ids.containsKey(name)) return null
        val ctx = handle.remoteContext ?: return null
        val resolved = runCatching { ctx.getVariableId(USER_PREFIX + name) }.getOrNull()?.takeIf { it > 0 }
        ids[name] = resolved
        return resolved
    }
}
