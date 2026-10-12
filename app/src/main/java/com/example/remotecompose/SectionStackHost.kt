@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.remotecompose.overlay.RcPlayerHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val TAG = "RC_STACK"

/**
 * Prototype of jar's multi-section full-screen host with the two additions the tabbed
 * Withdraw screen needs, fed from a LOCAL manifest (`assets/<screen>.manifest.json`, written by
 * the server's `--compose`):
 *
 *  1. **Pinned sections** — `binary[i].pin == "top" | "bottom"` render outside the scrolling
 *     stack (toolbar with tabs / sticky CTA bar). Everything else is a row of a LazyColumn.
 *  2. **`setValue` host action** — a section fires `{"host.tab": 1}`; the host stores the value
 *     and pushes it into EVERY live section (and replays it on each bind pass). Sections size
 *     themselves from it, so a tab switch is one float write, not navigation.
 *
 * Row height = the document's painted content (the root's direct children), polled each frame
 * like jar's bottom-sheet host: a section that collapses itself to 0 disappears from the stack,
 * one that grows pushes the rows below it down.
 */
@Composable
fun RCFromLocalManifest(screen: String) {
    val context = LocalContext.current
    var manifest by remember(screen) { mutableStateOf<LocalScreenManifest?>(null) }
    var sections by remember(screen) { mutableStateOf<List<LoadedSection>>(emptyList()) }
    var values by remember(screen) { mutableStateOf(SlotValues(emptyMap())) }
    var error by remember(screen) { mutableStateOf<String?>(null) }

    LaunchedEffect(screen) {
        loadLocalScreen(context, screen)
            .onSuccess { (m, docs, flat) ->
                manifest = m
                sections = docs
                values = flat
                Log.d(TAG, "$screen: ${docs.size} sections, pinned top=${docs.count { it.ref.isPinnedTop }} bottom=${docs.count { it.ref.isPinnedBottom }}")
            }
            .onFailure {
                error = it.message
                Log.e(TAG, "manifest '$screen' failed to load", it)
            }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val host = remember(listState) {
        HostRuntime(scope, listState) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
    val loader = remember { CoilBitmapLoader(context = context, onImageReady = {}) }
    val density = LocalDensity.current.density
    LaunchedEffect(sections, host) {
        if (sections.isNotEmpty()) host.frameLoop(sections, density)
    }
    val chrome = manifest?.statusBarColor
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: StackPageBg

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(chrome)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when {
            error != null -> Text(
                text = "Failed to load '$screen': $error",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
            sections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            else -> {
                sections.filter { it.ref.isPinnedTop }.forEach { SectionRow(it, values, host, loader) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(StackPageBg),
                    state = listState,
                ) {
                    items(sections.filter { it.ref.pin == null }, key = { it.index }) {
                        SectionRow(it, values, host, loader)
                    }
                }
                sections.filter { it.ref.isPinnedBottom }.forEach { SectionRow(it, values, host, loader) }
            }
        }
    }
}

internal val StackPageBg = Color(0xFF141020)

/** Manifest + parsed section documents + flat data for `assets/<screen>.manifest.json`, off the main thread. */
internal suspend fun loadLocalScreen(
    context: android.content.Context,
    screen: String,
): Result<Triple<LocalScreenManifest, List<LoadedSection>, SlotValues>> = runCatching {
    withContext(Dispatchers.IO) {
        val m = LocalScreenManifest.loadAsset(context, screen)
        val docs = m.binary.mapIndexed { i, ref ->
            val bytes = context.assets.open(ref.url).readBytes()
            val doc = CoreDocument().also {
                it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
                it.disableStateUpdateAnimationsLikeJar()
            }
            LoadedSection(i, ref, doc)
        }
        // Same data convention as RCFromFile: assets/data/<screen>.json → flat USER: values.
        val flat = FlatScreenData.loadAsset(context, "$screen.rc")
        Triple(m, docs, SlotValues(flat))
    }
}

@Stable
class LoadedSection(val index: Int, val ref: LocalBinaryRef, val document: CoreDocument) {
    /**
     * The baked canvas height — the player always lays out at this size; the row crops to content.
     * (Tried the player's wrap-content path — FEATURE_PAINT_MEASURE off + wrapContentHeight — on
     * alpha17: it re-requests layout from paint and the pages redrew ~26 fps while idle. Fixed
     * canvas + per-frame crop idles at 0.)
     */
    val ceilingDp: Int = document.featureIntValue(Header.DOC_HEIGHT).takeIf { it > 0 } ?: 400
}

/**
 * Host-owned values shared by every section on the screen. `setValue` writes them, every
 * live player gets them pushed immediately, and every bind pass replays them (a setDocument
 * wipes all `USER:` values, so the bind pass must re-apply — jar's `RcHostValues` rule).
 */
/** What a section row needs from its host — implemented by the stack and the pager hosts. */
@Stable
interface RcSectionHost {
    /** Painted content height per section index, dp. Written by [measureSectionsLoop], read by the rows. */
    val heightsDp: SnapshotStateMap<Int, Float>
    /** Bumped by [requestMeasure]; [measureSectionsLoop] samples the rows only after a bump. */
    val layoutTick: StateFlow<Int>
    /** Call after anything that can change a player's layout: a bind, a host push, a data pass. */
    fun requestMeasure()
    fun replay(updater: StateUpdater)
    fun register(index: Int, player: RemoteComposePlayer)
    /** The section left the composition (pager page disposed): stop pushing into its player. */
    fun unregister(index: Int)
    fun onNamedAction(name: String, value: Any?)
}

/**
 * One frame clock for every row. Measuring all sections here (instead of one effect per row)
 * lets a host react in the same frame as the height change — the stack host applies a pending
 * scroll restore there; otherwise the list shows the new tab at the OLD offset for a frame or two
 * before jumping (visible blink). [onChanged] runs in the frame where any height changed.
 *
 * Event-driven, not a free-running poll: a player lays out inside its next paint, so after each
 * [RcSectionHost.requestMeasure] the rows are sampled once per frame until they have sat still
 * for [SETTLE_FRAMES] frames (capped). Polling every vsync forever cost ~2.3 ms per frame — 19 %
 * of a core while the screen was idle and the same again on every scroll frame.
 */
suspend fun measureSectionsLoop(
    host: RcSectionHost,
    sections: List<LoadedSection>,
    density: Float,
    onChanged: () -> Unit = {},
) {
    host.layoutTick.collectLatest {
        var quiet = 0
        var frames = 0
        while (quiet < SETTLE_FRAMES && frames < MAX_SETTLE_FRAMES) {
            withFrameNanos { }
            frames++
            var changed = false
            for (s in sections) {
                val dp = rootChildrenBottomPx(s.document) / density
                if (abs(dp - (host.heightsDp[s.index] ?: 0f)) > 0.5f) {
                    host.heightsDp[s.index] = dp
                    changed = true
                }
            }
            if (changed) { quiet = 0; onChanged() } else quiet++
        }
    }
}

/** Consecutive unchanged frames before a measure burst ends. */
private const val SETTLE_FRAMES = 3
/** Hard cap per burst (a first paint can be a few frames away after a bind). */
private const val MAX_SETTLE_FRAMES = 60

/** [RcSectionHost.layoutTick] + [RcSectionHost.requestMeasure] for both runtimes. */
class LayoutTick {
    private val tick = MutableStateFlow(0)
    val flow: StateFlow<Int> = tick.asStateFlow()
    fun bump() { tick.value++ }
}

@Stable
class HostRuntime(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val toast: (String) -> Unit,
) : RcSectionHost {
    private val values = ConcurrentHashMap<String, Float>()
    private val players = ConcurrentHashMap<Int, RemoteComposePlayer>()

    /** `"<key>=<value>"` → (first visible item, offset px) — per-tab scroll memory for `restoreScroll`. */
    private val scrollMemory = HashMap<String, Pair<Int, Int>>()

    override val heightsDp = mutableStateMapOf<Int, Float>()
    private val layout = LayoutTick()
    override val layoutTick get() = layout.flow
    override fun requestMeasure() = layout.bump()

    /** Scroll to apply in the SAME frame the rows re-measure after a push; null = nothing pending. */
    private var pendingScroll: Pair<Int, Int>? = null
    private var pendingScrollArmed = false

    /** `requestScrollToItem` is applied on the next measure, i.e. the height-change frame's layout pass. */
    suspend fun frameLoop(sections: List<LoadedSection>, density: Float) =
        measureSectionsLoop(this, sections, density) {
            if (pendingScrollArmed) {
                pendingScrollArmed = false
                val target = pendingScroll
                pendingScroll = null
                if (target != null) listState.requestScrollToItem(target.first, target.second)
                else listState.requestScrollToItem(0)
            }
        }

    override fun register(index: Int, player: RemoteComposePlayer) {
        players[index] = player
    }

    override fun unregister(index: Int) {
        players.remove(index)
    }

    override fun replay(updater: StateUpdater) {
        for ((name, value) in values) updater.setUserLocalFloat(name, value)
    }

    override fun onNamedAction(name: String, value: Any?) {
        val payload = (value as? String)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        when (name) {
            SET_VALUE_ACTION -> {
                var scrollToTop = false
                var restoreScroll = false
                // (key, previous value) for every key whose value actually changed.
                val changed = ArrayList<Pair<String, Float>>()
                payload?.forEach { (key, element) ->
                    val primitive = element as? JsonPrimitive ?: return@forEach
                    when (key) {
                        SCROLL_TO_TOP_KEY -> { scrollToTop = primitive.booleanOrNull == true; return@forEach }
                        RESTORE_SCROLL_KEY -> { restoreScroll = primitive.booleanOrNull == true; return@forEach }
                    }
                    val new = primitive.floatOrNull ?: return@forEach
                    // Unset = the binary's baked default. The demo assumes 0; jar reads the live
                    // named value from the document (RcNamedValueReader) instead of assuming.
                    val old = values[key] ?: 0f
                    if (old != new) changed += key to old
                    values[key] = new
                }
                // LIVE push goes through the VIEW's setter, not the StateUpdater: only the view
                // path calls `CoreDocument.invalidate()`, which marks the root as needing measure.
                // Heights are read at LAYOUT, so a StateUpdater write alone repaints (the header's
                // paint-time alpha follows) while every `ifElse` height keeps its old value.
                for (player in players.values) {
                    for ((name, v) in values) player.setUserLocalFloat(name, v)
                    player.invalidate()
                }
                requestMeasure()
                when {
                    restoreScroll && changed.isNotEmpty() -> {
                        // Save where the OLD value's stack was, then jump to where the NEW value's
                        // stack last was (top if never visited). Item indices are stable — collapsed
                        // rows stay in the list at 0 dp — so (index, offset) reproduces the exact view
                        // once the rows have re-measured; hence the two-frame wait.
                        val (key, old) = changed.first()
                        scrollMemory["$key=$old"] =
                            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                        val target = scrollMemory["$key=${values[key]}"]
                        // Applied by frameLoop in the frame the rows re-measure, not now.
                        pendingScroll = target
                        pendingScrollArmed = true
                        Log.d(TAG, "restoreScroll $key: saved $old@${scrollMemory["$key=$old"]} → restore ${values[key]}@$target")
                    }
                    scrollToTop -> {
                        pendingScroll = null
                        pendingScrollArmed = true
                    }
                }
                Log.d(TAG, "setValue $payload → pushed to ${players.size} sections")
            }
            else -> {
                // deeplink / apiCall / onBack / analytics — jar's hosts own these; the demo just shows them.
                Log.d(TAG, "host action '$name' value=$value")
                toast("$name ${value ?: ""}".trim())
            }
        }
    }

    private companion object {
        const val SET_VALUE_ACTION = "setValue"
        const val SCROLL_TO_TOP_KEY = "scrollToTop"
        const val RESTORE_SCROLL_KEY = "restoreScroll"
    }
}

/**
 * A tab PAGE: one binary that fills the height the host gives it ([modifier], e.g. `weight(1f)`)
 * and scrolls its own content. No row cropping, no polling — the document lays out to the view.
 */
@Composable
internal fun SectionFill(
    section: LoadedSection,
    values: SlotValues,
    host: RcSectionHost,
    loader: CoilBitmapLoader,
    modifier: Modifier,
    /** Composed over the player once it is live, in its coordinates — the overlay holes' native widgets. */
    overlay: (@Composable (RcPlayerHandle) -> Unit)? = null,
) {
    val data = remember(section) { MutableStateFlow<Map<String, JsonPrimitive>>(emptyMap()) }
    val namespaces = remember(section) { section.ref.dataEndpoints.mapNotNull { it.key }.map { "$it." } }
    SideEffect { data.value = values.map.filterKeys { k -> namespaces.any { k.startsWith(it) } } }
    Box(modifier = modifier) { SectionPlayer(section, data, host, loader, fill = true, overlay = overlay) }
}

@Composable
internal fun SectionRow(
    section: LoadedSection,
    values: SlotValues,
    host: RcSectionHost,
    loader: CoilBitmapLoader,
) {
    // Only this section's namespaces (jar: ScreenData.scopedTo). A StateFlow drops equal maps, so
    // a Silver pill that replaces `silver.*` never reaches the header's player at all.
    val data = remember(section) { MutableStateFlow<Map<String, JsonPrimitive>>(emptyMap()) }
    val namespaces = remember(section) { section.ref.dataEndpoints.mapNotNull { it.key }.map { "$it." } }
    SideEffect { data.value = values.map.filterKeys { k -> namespaces.any { k.startsWith(it) } } }

    // The row follows the painted content height, measured by measureSectionsLoop for every
    // section in one frame. Missing = 0 so collapsed tabs never flash.
    val heightDp = host.heightsDp[section.index] ?: 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clipToBounds(),
        contentAlignment = Alignment.TopStart,
    ) {
        // Stable inputs only (the flow instance never changes): the stock RemoteDocumentPlayer
        // re-runs setDocument (= full re-inflate + one frame at default layout — the "stretch")
        // on every recomposition, so neither a height change nor NEW DATA may recompose it.
        SectionPlayer(section, data, host, loader)
    }
}

@Composable
private fun SectionPlayer(
    section: LoadedSection,
    data: StateFlow<Map<String, JsonPrimitive>>,
    host: RcSectionHost,
    loader: CoilBitmapLoader,
    /** true = the player fills its parent (a viewport-filling page); false = baked canvas cropped by the row. */
    fill: Boolean = false,
    overlay: (@Composable (RcPlayerHandle) -> Unit)? = null,
) {
    val doc = section.document
    val context = LocalContext.current
    // Per player instance on purpose: this effect restarts with a new player, and the restart must
    // reload + re-push every slot (a cache shared across restarts left every photo blank).
    val bitmaps = remember(doc) { ConcurrentHashMap<String, Bitmap>() }
    var liveplayer by remember(doc) { mutableStateOf<RemoteComposePlayer?>(null) }
    DisposableEffect(section) { onDispose { host.unregister(section.index) } }
    // DATA pass (jar's pattern): every new payload goes straight into the live player through
    // its VIEW setters (layout-dirtying), images whose URL changed are re-fetched — no setDocument.
    LaunchedEffect(liveplayer) {
        val p = liveplayer ?: return@LaunchedEffect
        val slots = ProfileSlotBitmaps.imageSlots(doc)
        val loadedUrl = HashMap<String, String>()
        data.collect { map ->
            FlatScreenData.applyLive(doc, p, map)
            p.invalidate()
            host.requestMeasure()
            for ((slot, size) in slots) {
                val url = FlatScreenData.slotUrl(map, slot) ?: continue
                if (loadedUrl[slot] == url) continue
                loadedUrl[slot] = url
                launch(Dispatchers.IO) {
                    val bmp = FlatScreenData.loadBitmap(context, url, size.first, size.second) ?: return@launch
                    bitmaps[slot] = bmp
                    withContext(Dispatchers.Main) {
                        p.stateUpdater.setUserLocalBitmap(slot, bmp)
                        p.invalidate()
                    }
                }
            }
        }
    }
    // The player keeps the baked canvas so layout always runs (a 0-height view would never
    // lay out, and a collapsed section could never grow back); the row Box crops it.
    RemoteDocumentPlayer(
        // TOP-aligned overflow: `requiredHeight` alone CENTRES a child taller than the row, which
        // shifted every tall page up by (ceiling − painted) / 2 — the Silver hero top and the
        // bestsellers bottom were cut off on device.
        modifier = if (fill) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .height(section.ceilingDp.dp)
        },
        document = doc,
        documentWidth = doc.width,
        documentHeight = section.ceilingDp,
        bitmapLoader = loader,
        init = { it.disableClickRippleAnimationLikeJar() },
        update = { player ->
            // BIND pass — after a setDocument: re-apply current data from an UNOBSERVED source
            // (StateFlow.value), then host values on top.
            FlatScreenData.apply(doc, player.stateUpdater, data.value)
            bitmaps.forEach { (slot, bmp) -> player.stateUpdater.setUserLocalBitmap(slot, bmp) }
            host.replay(player.stateUpdater)
            host.register(section.index, player)
            host.requestMeasure()
            liveplayer = player
            // setDocument re-inflated the component tree: fresh components carry the default
            // ~300 ms bounds tween, so the pin has to be re-applied after EVERY bind.
            doc.disableStateUpdateAnimationsLikeJar()
        },
        onAction = { _, _ -> },
        onNamedAction = { name, value, _ -> host.onNamedAction(name, value) },
    )
    // Same box as the player, so hole rects (document coordinates) line up (jar: RcOverlayStack).
    val live = liveplayer
    if (overlay != null && live != null) {
        val handle = remember(live, live.stateUpdater) { RcPlayerHandle(doc, live.stateUpdater) }
        overlay(handle)
    }
}

/** Flattened `USER:` values wrapped so Compose treats them as one immutable input. */
@Immutable
class SlotValues(val map: Map<String, JsonPrimitive>)

/** Lowest edge of the root's DIRECT children — a section's gated box, or a wrap-content column. */
internal fun rootChildrenBottomPx(document: CoreDocument): Float {
    val root = document.rootLayoutComponent ?: return 0f
    val children = ArrayList<Component>()
    root.getComponents(children)
    var bottom = 0f
    for (child in children) {
        val edge = child.y + child.height
        if (edge > bottom) bottom = edge
    }
    return bottom
}
