@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.remotecompose.overlay.OverlayKey
import com.example.remotecompose.overlay.RcMediaOverlayLayer
import com.example.remotecompose.overlay.RcPlayerHandle
import com.example.remotecompose.overlay.USER_PREFIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RC_PAGER"


/** Horizontal swipe between tabs. Off until in-binary carousels can opt out of the pager drag. */
private const val SWIPE_ENABLED = false


/**
 * Pages kept composed on each side of the current one. A page's first paint costs ~250 ms on a
 * low-end phone and lands inside the slide animation if it happens on the way to the tab, so with
 * up to [KEEP_ALL_UP_TO] tabs every page stays warm (memory is the price); beyond that only the
 * neighbours do, and a cold target snaps instead of sliding. A disposed page comes back with its
 * scroll offset (host LazyListState) and in-binary state (replayed host values) intact.
 */
private const val KEEP_ALL_UP_TO = 4
private fun pagesKeptAlive(pageCount: Int) = if (pageCount <= KEEP_ALL_UP_TO) (pageCount - 1).coerceAtLeast(0) else 1

/**
 * The PAGING host — the alternative to SectionStackHost for tabbed screens. Same manifest, same
 * binaries, one extra field: `binary[i].tab`.
 *
 *  - Sections with `tab == null` and `pin == "top"` are the screen header, rendered once.
 *  - Every distinct `tab` (in order of first appearance) becomes one page of a HorizontalPager.
 *    A page = that tab's `pin == "top"` strips, its plain rows in a LazyColumn, its
 *    `pin == "bottom"` footers.
 *  - Which pages stay composed is [pagesKeptAlive]; the first frame composes only the header and
 *    the current page (five inflates in one layout pass made a 1.4 s first frame), the other
 *    pages follow once that page has painted.
 *  - The header fires `setValue {"host.tab": i}`; the host only moves the pager. That is the
 *    only value the host answers: every other state (pills, accordions, the reviews overlay) is
 *    written and read inside its own binary.
 *  - A `pin == "overlay"` section is the screen's intro ([PagerIntro]): layered over the pages
 *    while its Lottie hole plays once, its playback pushed into every binary as host values.
 */
@Composable
fun RCFromLocalManifestPager(
    screen: String,
    /** The opener's facts (deeplink `?args=`): seeded as `USER:args.*` where declared, relayed to POST data links. */
    args: JsonObject = JsonObject(emptyMap()),
    /** Results for the opener — here the host result `media_shown{id}` (jar: a Fragment Result). */
    onResult: (JsonObject) -> Unit = {},
) {
    val context = LocalContext.current
    // jar `isGuidingLottieShown`: once the intro has counted as shown, this screen instance never
    // replays it — not even after a rotation recreates the composition.
    var introShown by rememberSaveable(screen) { mutableStateOf(false) }
    var manifest by remember(screen) { mutableStateOf<LocalScreenManifest?>(null) }
    var sections by remember(screen) { mutableStateOf<List<LoadedSection>>(emptyList()) }
    var values by remember(screen) { mutableStateOf(SlotValues(emptyMap())) }
    var error by remember(screen) { mutableStateOf<String?>(null) }

    LaunchedEffect(screen) {
        loadLocalScreen(context, screen)
            .onSuccess { (m, docs, flat) ->
                manifest = m
                sections = docs
                values = SlotValues(openedValues(m, docs, args, flat.map))
                Log.d(TAG, "$screen: ${docs.size} sections, tabs=${docs.mapNotNull { it.ref.tab }.distinct()}")
            }
            .onFailure {
                error = it.message
                Log.e(TAG, "manifest '$screen' failed to load", it)
            }
    }

    val scope = rememberCoroutineScope()
    val host = remember {
        PagerHostRuntime(
            scope = scope,
            toast = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
            // Mock `apiCall … "into"`: the response comes from assets/api/<url-as-filename>.json and
            // replaces that namespace in the live values, exactly what jar does with the network.
            mockApi = { url -> MockApi.load(context, url) },
            onValuesReplaced = { ns, flat ->
                values = SlotValues(values.map.filterKeys { !it.startsWith("$ns.") } + flat)
            },
        )
    }
    val loader = remember { CoilBitmapLoader(context = context, onImageReady = {}) }
    // Seeded before any player binds, so the header's first frame already waits on the intro.
    val intro = remember(sections, host) {
        host.declaredFloats = sections.associate { it.index to declaredFloatNames(it.document) }
        PagerIntro(sections.firstOrNull { it.ref.isOverlay && it.ref.tab == null }.takeUnless { introShown }).also {
            host.seedFloats(it.hostFloats())
            host.tabsLocked = it.isRunning
        }
    }
    LaunchedEffect(intro, host) { snapshotFlow { intro.hostFloats() }.collect { host.pushFloats(it) } }
    LaunchedEffect(intro, host) { snapshotFlow { intro.isRunning }.collect { host.tabsLocked = it } }
    LaunchedEffect(intro) { intro.prefetch(context, values.map) }
    // The header's waiting tabs pulse on the player clock; repaint them every frame while the
    // intro runs (jar: the chip's infinite transition), not only when a playback value changes.
    LaunchedEffect(intro, host) {
        val names = intro.hostFloats().keys
        while (intro.isRunning) withFrameNanos { host.invalidateReaders(names) }
    }
    val header = remember(sections) { sections.filter { it.ref.tab == null && it.ref.isPinnedTop } }
    val headerPainted by remember(sections, host) {
        derivedStateOf { header.isNotEmpty() && header.all { (host.heightsDp[it.index] ?: 0f) > 0f } }
    }
    LaunchedEffect(intro, host) {
        val pageSections = sections.filter { it.ref.tab != null }
        intro.awaitReady(
            headerPainted = { headerPainted },
            pagesPainted = { pageSections.all { (host.heightsDp[it.index] ?: 0f) > 0f } },
        )
    }
    LaunchedEffect(intro) { intro.runPageReveal() }
    val density = LocalDensity.current.density
    LaunchedEffect(sections, host) {
        if (sections.isNotEmpty()) measureSectionsLoop(host, sections, density)
    }
    LaunchedEffect(values) { host.flatValues = values.map }
    // Timing: composition start → every section has painted a non-zero height once.
    val t0 = remember(screen) { System.nanoTime() }
    LaunchedEffect(sections) {
        if (sections.isEmpty()) return@LaunchedEffect
        Log.d(TAG, "parsed ${sections.size} sections at +${(System.nanoTime() - t0) / 1_000_000} ms")
        snapshotFlow { sections.count { (host.heightsDp[it.index] ?: 0f) > 0f } }
            .collect { painted ->
                Log.d(TAG, "painted $painted/${sections.size} at +${(System.nanoTime() - t0) / 1_000_000} ms")
            }
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
                val pages = remember(sections) { TabPage.from(sections) }
                val overlayVisible by remember(intro) { derivedStateOf { intro.isOverlayVisible } }
                header.forEach { SectionRow(it, values, host, loader) }
                // jar WithdrawalOptionScreenV2: the pager fades / slides in over the intro Lottie.
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // With an intro the hidden pages are built only once the header has painted,
                    // so the header (and its waiting tabs) shows first.
                    if (intro.key == null || headerPainted || !intro.isRunning) TabPager(
                        pages = pages,
                        values = values,
                        host = host,
                        loader = loader,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val reveal = intro.pageReveal.value
                            alpha = reveal
                            translationY = (1f - reveal) * PagerIntro.PAGE_REVEAL_SLIDE.toPx()
                        },
                    )
                    val overlay = intro.section
                    if (overlay != null && overlayVisible) {
                        IntroOverlay(overlay, intro, values, host, loader) { key ->
                            introShown = true
                            onResult(mediaShownResult(key))
                        }
                    }
                }
            }
        }
    }
}

/** One horizontal page: a tab's fixed strips, scrolling rows and pinned footers. */
class TabPage(
    val key: String,
    val strips: List<LoadedSection>,
    val rows: List<LoadedSection>,
    val bottom: List<LoadedSection>,
) {
    /** A tab with exactly one plain binary: it fills the page (between strip and footer), no LazyColumn. */
    val fillsPage: Boolean get() = rows.size == 1

    companion object {
        fun from(sections: List<LoadedSection>): List<TabPage> =
            sections.mapNotNull { it.ref.tab }.distinct().map { key ->
                val own = sections.filter { it.ref.tab == key }
                TabPage(
                    key = key,
                    strips = own.filter { it.ref.isPinnedTop },
                    rows = own.filter { it.ref.pin == null },
                    bottom = own.filter { it.ref.isPinnedBottom },
                )
            }
    }
}

/**
 * The `pin == "overlay"` section over the pages, while the intro plays (jar `WithdrawalIntroLottie`
 * in its `zIndex(1f)` box): its Lottie hole is floated by [RcMediaOverlayLayer], fades out as the page
 * reveals, and swallows taps meant for the hidden page underneath.
 */
@Composable
private fun IntroOverlay(
    section: LoadedSection,
    intro: PagerIntro,
    values: SlotValues,
    host: PagerHostRuntime,
    loader: CoilBitmapLoader,
    onShown: (OverlayKey) -> Unit,
) {
    SectionFill(
        section = section,
        values = values,
        host = host,
        loader = loader,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - intro.pageReveal.value }
            .pointerInput(Unit) { detectTapGestures { } },
        overlay = { handle ->
            RcMediaOverlayLayer(handle, values.map, hold = !intro.ready) { key, playback -> intro.onPlayback(key, playback, onShown) }
        },
    )
}

@Composable
private fun TabPager(
    pages: List<TabPage>,
    values: SlotValues,
    host: PagerHostRuntime,
    loader: CoilBitmapLoader,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val keepAlive = pagesKeptAlive(pages.size)
    // Staggered first inflate: the other pages compose only after the first page has painted.
    var warm by remember(pages) { mutableStateOf(false) }
    LaunchedEffect(pages, host) {
        val first = pages.firstOrNull()?.let { it.rows + it.bottom } ?: return@LaunchedEffect
        snapshotFlow { first.all { (host.heightsDp[it.index] ?: 0f) > 0f } }.first { it }
        warm = true
    }

    // Header tap → setValue host.tab → move the pager (animated, like a native tab row).
    LaunchedEffect(pagerState, host) {
        host.tabRequests.collect { page ->
            if (page !in pages.indices || page == pagerState.currentPage) return@collect
            // A page outside the kept window would inflate mid-slide (265 ms frames): snap to it.
            if (abs(page - pagerState.currentPage) > keepAlive) { pagerState.scrollToPage(page); return@collect }
            try {
                pagerState.animateScrollToPage(page)
            } catch (e: CancellationException) {
                // A second tap or a gesture interrupted the animation. That cancellation is the
                // pager's, not ours — swallowing it keeps this collector alive (otherwise every
                // later tap recolours the header but the page never moves: "Gold selected,
                // Silver showing"). Snap to the requested page so header and page agree.
                if (!isActive) throw e
                pagerState.scrollToPage(page)
            }
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        beyondViewportPageCount = if (warm) keepAlive else 0,
        // Tap-only for now: swipe would need the host to know where in-binary carousels sit so
        // it can leave those drags alone. Turn on once sections publish their carousel rects.
        userScrollEnabled = SWIPE_ENABLED,
        key = { pages[it].key },
    ) { index ->
        val page = pages[index]
        Column(modifier = Modifier.fillMaxSize().background(StackPageBg)) {
            page.strips.forEach { SectionRow(it, values, host, loader) }
            if (page.fillsPage) {
                // One binary IS the page: it gets the whole height, scrolls its own content and
                // can layer anything over itself (the reviews sheet) — a row never could.
                SectionFill(page.rows[0], values, host, loader, Modifier.fillMaxWidth().weight(1f))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(page.rows, key = { it.index }) { SectionRow(it, values, host, loader) }
                }
            }
            page.bottom.forEach { SectionRow(it, values, host, loader) }
        }
    }
}

/**
 * Host-side runtime for the paging host. Selections inside a page are written inside their
 * binary (`setValue(variable, expression)`), and `{{host.*}}` placeholders in an `apiCall` are read
 * back from the documents' live values. The one named value it answers is
 * `setValue {"host.tab": i}`: pushed back into the header (which draws the selection from it) and
 * moves the pager — ignored while [tabsLocked] (jar: tabs can't be tapped during the intro).
 *
 * The values it owns — `host.tab` and the intro's playback floats ([pushFloats]) — go into every
 * live player that declares them, and are replayed on every bind because a `setDocument` wipes all
 * `USER:` values.
 */
@Stable
class PagerHostRuntime(
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    /** Returns the mock JSON body for an `apiCall` url, or null when no mock exists. */
    private val mockApi: suspend (url: String) -> JsonObject?,
    /** `into` namespace replaced: hand the host the new flat values for that namespace. */
    private val onValuesReplaced: (ns: String, flat: Map<String, JsonPrimitive>) -> Unit,
) : RcSectionHost {
    private val players = ConcurrentHashMap<Int, RemoteComposePlayer>()
    override val heightsDp = mutableStateMapOf<Int, Float>()
    private val layout = LayoutTick()
    override val layoutTick get() = layout.flow
    override fun requestMeasure() = layout.bump()

    private val tabChannel = Channel<Int>(Channel.CONFLATED)
    val tabRequests = tabChannel.receiveAsFlow()

    /** While true, header taps (`host.tab`) are ignored — the intro is playing. */
    @Volatile var tabsLocked: Boolean = false

    /** Float `USER:` names each section's document declares (bare), so a push only repaints its readers. */
    @Volatile var declaredFloats: Map<Int, Set<String>> = emptyMap()

    private val hostFloats = ConcurrentHashMap<String, Float>()

    /** Repaints the live players that declare any of [names] (paint only). */
    fun invalidateReaders(names: Set<String>) {
        players.forEach { (index, player) ->
            val declared = declaredFloats[index] ?: return@forEach
            if (names.any { it in declared }) player.invalidate()
        }
    }

    /** Initial host values, before any player binds (they reach each player through [replay]). */
    fun seedFloats(values: Map<String, Float>) {
        hostFloats.putAll(values)
    }

    /**
     * Pushes host floats into the live players that declare them and repaints only those
     * (a per-frame intro push must not repaint the pages). Paint-only: no layout is requested.
     */
    fun pushFloats(values: Map<String, Float>) {
        hostFloats.putAll(values)
        players.forEach { (index, player) ->
            val declared = declaredFloats[index] ?: return@forEach
            var touched = false
            for ((name, v) in values) {
                if (name !in declared) continue
                player.stateUpdater.setUserLocalFloat(name, v)
                touched = true
            }
            if (touched) player.invalidate()
        }
    }

    override fun register(index: Int, player: RemoteComposePlayer) {
        players[index] = player
    }

    override fun unregister(index: Int) {
        players.remove(index)
    }


    /** Re-applies the host floats after a bind (a name the document doesn't declare is a no-op). */
    override fun replay(updater: StateUpdater) {
        hostFloats.forEach { (name, v) -> updater.setUserLocalFloat(name, v) }
    }

    /** A `USER:<name>` float from whichever live document declares it (jar: RcNamedValueReader on the tapping document). */
    private fun readDocumentFloat(name: String): Float? = players.values.firstNotNullOfOrNull { player ->
        val ctx = RcPlayerHandle.remoteContextOf(player.stateUpdater) ?: return@firstNotNullOfOrNull null
        val id = runCatching { ctx.getVariableId("USER:$name") }.getOrNull()?.takeIf { it > 0 }
            ?: return@firstNotNullOfOrNull null
        runCatching { ctx.getFloat(id) }.getOrNull()?.takeIf { !it.isNaN() }
    }

    override fun onNamedAction(name: String, value: Any?) {
        val payload = (value as? String)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        when (name) {
            SET_VALUE_ACTION -> {
                payload?.forEach { (key, element) ->
                    val v = (element as? JsonPrimitive)?.floatOrNull ?: return@forEach
                    // The tab only moves the pager; any other value is the binary's own.
                    if (key == TAB_KEY) {
                        if (tabsLocked) { Log.d(TAG, "tab ${v.toInt()} ignored: intro playing"); return@forEach }
                        // The header shows the selection from `host.tab`: push it (and keep it for
                        // re-binds), then move the pager.
                        pushFloats(mapOf(TAB_KEY to v))
                        tabChannel.trySend(v.toInt()); Log.d(TAG, "tab → page ${v.toInt()}")
                    }
                    else Log.d(TAG, "setValue $key ignored: binaries own their values")
                }
                if (TRACE_ACTIONS) Log.d(TAG, Log.getStackTraceString(Throwable("setValue origin")))
            }
            API_CALL_ACTION -> {
                // Jar: RemoteComposeDataSource.execute → ScreenData.of(body, root = api.<key>.data) …
                // and, with `into`, a namespace replace. Demo: the body comes from a bundled mock.
                val url = payload?.get("url")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return
                val into = payload["into"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                val resolved = resolvePlaceholders(url)
                Log.d(TAG, "apiCall $resolved into=$into")
                scope.launch {
                    val body = mockApi(resolved)
                    if (body == null) { toast("apiCall (no mock): $resolved"); return@launch }
                    if (into != null) {
                        val flat = LinkedHashMap<String, JsonPrimitive>()
                        FlatScreenData.flattenInto(into, body, flat)
                        onValuesReplaced(into, flat)
                    }
                }
            }
            else -> {
                Log.d(TAG, "host action '$name' value=$value")
                toast("$name ${value ?: ""}".trim())
            }
        }
    }

    /** The live flat values, set by the host composable so `{{path}}` placeholders resolve (jar: RcPlaceholderResolver). */
    @Volatile var flatValues: Map<String, JsonPrimitive> = emptyMap()

    private fun resolvePlaceholders(url: String): String =
        Regex("""\{\{([A-Za-z0-9_.:#=-]+)\}\}""").replace(url) { m ->
            val path = m.groupValues[1]
            // `host.*` = the document's live value (unset → the binary's default 0), like jar's
            // resolver reading the tapping document; anything else = endpoint data.
            if (path.startsWith("host.")) (readDocumentFloat(path) ?: 0f).toInt().toString()
            else flatValues[path]?.contentOrNull ?: m.value
        }

    private companion object {
        const val SET_VALUE_ACTION = "setValue"
        const val API_CALL_ACTION = "apiCall"
        const val TAB_KEY = "host.tab"

        /** Debug: log the call stack of every setValue to tell a real tap from a replayed action. */
        const val TRACE_ACTIONS = false
    }
}

/** Bare (`USER:`-less) names of every float slot [document] declares. */
private fun declaredFloatNames(document: CoreDocument): Set<String> =
    runCatching { document.getNamedVariables(NamedVariable.FLOAT_TYPE) }
        .getOrNull().orEmpty().mapTo(HashSet()) { it.removePrefix(USER_PREFIX) }

/**
 * The screen's data as this opening sees it: each POST data link answered for the opener's [args]
 * ([MockBackend], the demo's backend), then the declared `args.*` seeded for the documents.
 */
private fun openedValues(
    manifest: LocalScreenManifest,
    sections: List<LoadedSection>,
    args: JsonObject,
    loaded: Map<String, JsonPrimitive>,
): Map<String, JsonPrimitive> {
    val answered = sections.flatMap { it.ref.dataEndpoints }.distinctBy { it.key to it.url }
        .fold(loaded) { values, endpoint -> MockBackend.respond(endpoint, args, values) }
    val declared = manifest.contract?.receives?.args.orEmpty().mapTo(HashSet()) { it.name }
    val seeded = args.filterKeys { it in declared }.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { "args.$k" to it } }
    return answered + seeded
}

/** Host result `media_shown{id}` (non-terminal) for the opener. */
private fun mediaShownResult(key: OverlayKey): JsonObject = buildJsonObject {
    put("action", ACTION_MEDIA_SHOWN)
    put("terminal", "false")
    put("id", key.id)
}
