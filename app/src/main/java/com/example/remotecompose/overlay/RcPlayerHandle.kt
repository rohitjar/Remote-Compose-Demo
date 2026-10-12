@file:Suppress("RestrictedApi")

package com.example.remotecompose.overlay

import android.util.Log
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.core.state.StateUpdaterImpl

/**
 * Read access to one running document — port of jar's `RcPlayerHandle`. The library hands the
 * host only a write-only [StateUpdater]; this adds the read side the overlays need: hole rects
 * ([rememberOverlayRects]) and the facts a document publishes ([RcNamedValueReader]).
 *
 * [remoteContext] is the player's variable table, read reflectively off
 * `StateUpdaterImpl.mRemoteContext` (no public getter). Null when a library bump moves the field:
 * every reader then degrades to "slot absent" rather than crashing.
 */
class RcPlayerHandle(
    val document: CoreDocument,
    val stateUpdater: StateUpdater,
) {
    val remoteContext: AndroidRemoteContext? = remoteContextOf(stateUpdater)

    companion object {
        private const val TAG = "RcPlayerHandle"

        private val contextField by lazy {
            runCatching {
                StateUpdaterImpl::class.java.getDeclaredField("mRemoteContext").apply { isAccessible = true }
            }.onFailure { Log.e(TAG, "StateUpdaterImpl.mRemoteContext not found", it) }
                .getOrNull()
        }

        /** The player's variable table, as jar reaches it (no public getter on the stock player). */
        fun remoteContextOf(stateUpdater: StateUpdater): AndroidRemoteContext? {
            if (stateUpdater !is StateUpdaterImpl) return null
            val field = contextField ?: return null
            return runCatching { field.get(stateUpdater) as? AndroidRemoteContext }
                .onFailure { Log.e(TAG, "reading mRemoteContext failed", it) }
                .getOrNull()
        }
    }
}
