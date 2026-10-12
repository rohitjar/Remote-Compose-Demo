@file:Suppress("RestrictedApi")

package com.example.remotecompose

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.remote.player.core.state.StateUpdaterImpl
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
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
 *    written and read inside its own binary, so nothing is stored or pushed here.
 */
@Composable
fun RCFromLocalManifestPager(screen: String) {
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
                val header = sections.filter { it.ref.tab == null && it.ref.isPinnedTop }
                val pages = remember(sections) { TabPage.from(sections) }
                header.forEach { SectionRow(it, values, host, loader) }
                TabPager(pages, values, host, loader)
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

@Composable
private fun TabPager(
    pages: List<TabPage>,
    values: SlotValues,
    host: PagerHostRuntime,
    loader: CoilBitmapLoader,
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
        modifier = Modifier.fillMaxSize(),
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
 * Host-side runtime for the paging host. It stores NO values: every selection is written inside
 * its binary (`setValue(variable, expression)`), and `{{host.*}}` placeholders in an `apiCall`
 * are read back from the documents' live values. The one named value it answers is
 * `setValue {"host.tab": i}`, which moves the pager.
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

    override fun register(index: Int, player: RemoteComposePlayer) {
        players[index] = player
    }

    override fun unregister(index: Int) {
        players.remove(index)
    }


    /** Nothing to replay: the binaries own their values. */
    override fun replay(updater: StateUpdater) = Unit

    /** A `USER:<name>` float from whichever live document declares it (jar: RcNamedValueReader on the tapping document). */
    private fun readDocumentFloat(name: String): Float? = players.values.firstNotNullOfOrNull { player ->
        val ctx = remoteContextOf(player.stateUpdater) ?: return@firstNotNullOfOrNull null
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
                    if (key == TAB_KEY) { tabChannel.trySend(v.toInt()); Log.d(TAG, "tab → page ${v.toInt()}") }
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

        private val contextField by lazy {
            runCatching { StateUpdaterImpl::class.java.getDeclaredField("mRemoteContext").apply { isAccessible = true } }
                .onFailure { Log.e(TAG, "StateUpdaterImpl.mRemoteContext not found", it) }
                .getOrNull()
        }

        /** The player's variable table, as jar reaches it (no public getter on the stock player). */
        fun remoteContextOf(stateUpdater: StateUpdater): AndroidRemoteContext? {
            if (stateUpdater !is StateUpdaterImpl) return null
            val field = contextField ?: return null
            return runCatching { field.get(stateUpdater) as? AndroidRemoteContext }.getOrNull()
        }
    }
}
