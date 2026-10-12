@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.animation.AnimationSpec
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.remote.player.view.platform.RemoteComposeView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test bench that hosts the card the way jar's home feed does (`RemoteComposeCard` in
 * feature-dynamic-ui), so jar-only behaviour reproduces here:
 *
 *  - a LazyColumn item, not a centred Box; filler items above so it scrolls in like the feed;
 *  - ONE parsed document per composition, the player kept for the item's lifetime (no
 *    `key(...)` recreation), `RemoteDocumentPlayer` sized from the DOC_HEIGHT tag;
 *  - `init`: jar's accessibility veto (kills the click ripple);
 *  - per document: every component's AnimationSpec pinned to DISABLED (jar's
 *    `disableStateUpdateAnimations`);
 *  - BIND pass (`update`): values + already-loaded bitmaps re-pushed after every setDocument;
 *  - DATA pass (LaunchedEffect keyed on the player): values + `invalidate()`, then bitmaps
 *    loaded on Dispatchers.IO and pushed FROM THAT IO THREAD with `invalidate()` — exactly
 *    jar's `SlotBitmapBinder.bind` callback.
 */
@Composable
fun JarLikeFeedScreen(assetName: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF16141C))
            .statusBarsPadding(),
    ) {
        repeat(JAR_LIKE_FILLER_ITEMS) { i ->
            item(key = "filler-$i") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(16.dp)
                        .background(Color(0xFF2A2440)),
                ) {
                    Text("filler $i", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
        item(key = "rc-card") { JarLikeCard(assetName) }
        repeat(6) { i ->
            item(key = "tail-$i") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(16.dp)
                        .background(Color(0xFF2A2440)),
                )
            }
        }
    }
}

@Composable
private fun JarLikeCard(assetName: String) {
    val context = LocalContext.current
    var document by remember(assetName) { mutableStateOf<CoreDocument?>(null) }
    var slotValues by remember(assetName) { mutableStateOf<Map<String, JsonPrimitive>>(emptyMap()) }
    LaunchedEffect(assetName) {
        withContext(Dispatchers.IO) {
            val bytes = context.assets.open(assetName).readBytes()
            val doc = CoreDocument().also { it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream())) }
            slotValues = FlatScreenData.loadAsset(context, assetName)
            document = doc
        }
    }
    val doc = document ?: return
    val heightDp = doc.featureIntValue(Header.DOC_HEIGHT)
    remember(doc) { doc.disableStateUpdateAnimationsLikeJar(); doc }

    val loader = remember { CoilBitmapLoader(context = context, onImageReady = {}) }
    // Bitmaps loaded so far — the bind pass re-pushes them after every setDocument (jar's applyTo).
    val loaded = remember(doc) { java.util.concurrent.ConcurrentHashMap<String, Bitmap>() }
    var player by remember { mutableStateOf<RemoteComposePlayer?>(null) }

    RemoteDocumentPlayer(
        document = doc,
        documentWidth = doc.width,
        documentHeight = heightDp,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp),
        bitmapLoader = loader,
        init = { it.disableClickRippleAnimationLikeJar() },
        update = { p ->
            FlatScreenData.apply(doc, p.stateUpdater, slotValues)
            loaded.forEach { (slot, bmp) -> p.stateUpdater.setUserLocalBitmap(slot, bmp) }
            player = p
        },
        onAction = { _, _ -> },
        onNamedAction = ::handleNamedAction,
    )

    // DATA pass, jar-style: values on Main + invalidate; bitmaps decoded and PUSHED on IO.
    LaunchedEffect(doc, slotValues, player) {
        val p = player ?: return@LaunchedEffect
        FlatScreenData.apply(doc, p.stateUpdater, slotValues)
        p.invalidate()
        val slots = ProfileSlotBitmaps.imageSlots(doc)
        supervisorScope {
            for ((slot, size) in slots) {
                val url = FlatScreenData.slotUrl(slotValues, slot) ?: continue
                launch(Dispatchers.IO) {
                    val bmp = FlatScreenData.loadBitmap(context, url, size.first, size.second) ?: return@launch
                    loaded[slot] = bmp
                    if (JAR_LIKE_LATE_SLOT != null && slot.startsWith(JAR_LIKE_LATE_SLOT)) {
                        // Simulate jar's worst case: a slow 1024² decode that lands after the entrance
                        // frames stopped, pushed from IO with an invalidate() that never redraws.
                        kotlinx.coroutines.delay(JAR_LIKE_LATE_DELAY_MS)
                        p.stateUpdater.setUserLocalBitmap(slot, bmp)
                        Log.d("RC_JARLIKE", "LATE push ${bmp.width}x${bmp.height} into '$slot' (no invalidate)")
                        return@launch
                    }
                    if (JAR_LIKE_PUSH_ON_IO) {
                        p.stateUpdater.setUserLocalBitmap(slot, bmp)
                        p.invalidate()
                        Log.d("RC_JARLIKE", "pushed ${bmp.width}x${bmp.height} into '$slot' from ${Thread.currentThread().name}")
                    } else {
                        withContext(Dispatchers.Main) {
                            p.stateUpdater.setUserLocalBitmap(slot, bmp)
                            p.invalidate()
                            Log.d("RC_JARLIKE", "pushed ${bmp.width}x${bmp.height} into '$slot' from ${Thread.currentThread().name}")
                        }
                    }
                }
            }
        }
    }
}

/** jar's `disableStateUpdateAnimations`: pin every component's spec to DISABLED. */
internal fun CoreDocument.disableStateUpdateAnimationsLikeJar() {
    val root = rootLayoutComponent ?: return
    val stack = ArrayList<Component>()
    val children = ArrayList<Component>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val c = stack.removeAt(stack.size - 1)
        c.mAnimationSpec = AnimationSpec.DISABLED
        children.clear()
        c.getComponents(children)
        stack.addAll(children)
    }
}

/** jar's `disableClickRippleAnimation`: accessibility veto on the inner view's context. */
internal fun RemoteComposePlayer.disableClickRippleAnimationLikeJar() {
    val ctx = findRemoteComposeView(this)?.remoteContext
    if (ctx is AndroidRemoteContext) ctx.setAccessibilityAnimationEnabled(false)
}

private fun findRemoteComposeView(group: ViewGroup): RemoteComposeView? {
    for (i in 0 until group.childCount) {
        when (val child = group.getChildAt(i)) {
            is RemoteComposeView -> return child
            is ViewGroup -> findRemoteComposeView(child)?.let { return it }
        }
    }
    return null
}

/** Items above the card so it scrolls into view like the feed's 11th section. */
const val JAR_LIKE_FILLER_ITEMS = 2

/** Slot prefix whose bitmap is pushed late and WITHOUT an invalidate (null = off). */
val JAR_LIKE_LATE_SLOT: String? = null
const val JAR_LIKE_LATE_DELAY_MS = 3000L

/** true = push bitmaps from the IO thread like jar's binder callback; false = hop to Main first. */
const val JAR_LIKE_PUSH_ON_IO = true
