package com.example.remotecompose

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.CoreDocument.DENSITY_BEHAVIOR_DP
import androidx.compose.remote.core.Limits
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.CapturedDocument
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.RemoteDensity
import androidx.compose.remote.creation.compose.capture.WriterEvents
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.player.compose.RemoteDocumentPlayer
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.core.state.StateUpdater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.example.remotecompose.ui.theme.RemoteComposeTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Limits.ENABLE_IMAGE_URLS = true
        Limits.MAX_BITMAP_MEMORY = 200 * 1024 * 1024;
        super.onCreate(savedInstanceState)
        RemoteScreenService.initialize(this)
        enableEdgeToEdge()
        Log.d("LOOKHERE", "onCreate() called with: savedInstanceState = ${(JarRemoteComposeView(context = this).toString())}")
        setContent {
            RemoteComposeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // innerPadding is deliberately not applied: rendered binaries paint
                    // edge-to-edge; the list screen insets itself (statusBarsPadding).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(RcBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        RCFromServer()
                    }
                }
            }
        }
    }
}

/**
 * Two-screen flow over the server's saved documents (`POST /v1/parse/{name}` output):
 *
 *  1. [DocumentListScreen] — `GET /v1/documents`, every saved binary as a tappable card.
 *  2. [DocumentPlayerScreen] — fetches `GET /v1/documents/{name}.rc` for the tapped name
 *     and renders it full-screen; system back (or the top-bar arrow) returns to the list.
 *
 * Saved documents are self-contained `.rc` bytes — no manifest, layoutVersion, or
 * per-user data round trip (see [RCFromManifest] for the registered-screen flow).
 * Two destinations, so plain state stands in for a navigation library.
 */
@Composable
fun RCFromServer() {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val name = selected
    if (name == null) {
        DocumentListScreen(onDocumentClick = { selected = it })
    } else {
        DocumentPlayerScreen(name = name, onBack = { selected = null })
    }
}

// Minimal dark palette for the document browser chrome.
private val RcBackground = Color(0xFF0E0F13)
private val RcSurface = Color(0xFF1A1C23)
private val RcAccent = Color(0xFF8B7CF8)
private val RcTextPrimary = Color(0xFFF2F3F7)
private val RcTextDim = Color(0xFF8A8F9E)

/** Lists every saved document on the server; tapping one navigates to its player. */
@Composable
private fun DocumentListScreen(onDocumentClick: (String) -> Unit) {
    var documents by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        error = null
        documents = null
        runCatching {
            withContext(Dispatchers.IO) { RemoteScreenService.fetchDocuments() }
        }.onSuccess { documents = it }.onFailure {
            error = it.message
            Log.e("RC_SERVER", "documents list fetch failed", it)
        }
    }

    val docs = documents
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RcBackground)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 24.dp)) {
            Text(
                text = "REMOTE COMPOSE",
                style = MaterialTheme.typography.labelSmall,
                color = RcAccent,
                letterSpacing = 3.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Screens",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = RcTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refreshTick++ }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = RcTextDim,
                    )
                }
            }
            Text(
                text = when {
                    docs == null && error == null -> "syncing…"
                    docs != null -> "${docs.size} saved on server"
                    else -> " "
                },
                style = MaterialTheme.typography.bodySmall,
                color = RcTextDim,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RcTextDim,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    TextButton(onClick = { refreshTick++ }) {
                        Text(text = "Retry", color = RcAccent)
                    }
                }
                docs == null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val brush = shimmerBrush()
                    repeat(6) { DocumentRowSkeleton(brush) }
                }
                docs.isEmpty() -> Text(
                    text = "Nothing here yet — POST /v1/parse/{name} to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RcTextDim,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(docs) { name -> DocumentRow(name = name, onClick = { onDocumentClick(name) }) }
                }
            }
        }
    }
}

/** A brush that sweeps a soft highlight across whatever it fills — shared by all skeletons. */
@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    return Brush.linearGradient(
        colors = listOf(RcSurface, Color(0xFF2A2D38), RcSurface),
        start = Offset(offset - 500f, 0f),
        end = Offset(offset, 100f),
    )
}

/** Placeholder mirroring [DocumentRow]'s layout while the list loads. */
@Composable
private fun DocumentRowSkeleton(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RcSurface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(brush),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(brush),
            )
        }
    }
}

/** One tappable row in the document list: monogram, name, chevron. */
@Composable
private fun DocumentRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RcSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(RcAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.first().uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = RcAccent,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = RcTextPrimary,
            )
            Text(
                text = "$name.rc",
                style = MaterialTheme.typography.bodySmall,
                color = RcTextDim,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = RcTextDim,
        )
    }
}

/** Fetches one saved document's binary and renders it edge-to-edge; system back returns to the list. */
@Composable
private fun DocumentPlayerScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<CoreDocument?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // One loader instance per screen; it triggers a repaint when an async image arrives.
    var repaintTick by remember { mutableStateOf(0) }
    val loader = remember { CoilBitmapLoader(context = context, onImageReady = { repaintTick++ }) }

    BackHandler(onBack = onBack)

    LaunchedEffect(name) {
        error = null
        document = null
        runCatching {
            withContext(Dispatchers.IO) {
                val bytes = RemoteScreenService.fetchDocument(name)
                CoreDocument().also {
                    it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
                }
            }
        }.onSuccess { document = it }.onFailure {
            error = it.message
            Log.e("RC_SERVER", "document '$name' fetch failed", it)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            document != null -> key(repaintTick) {
                RemoteDocumentPlayer(
                    modifier = Modifier.fillMaxSize(),
                    document = document!!,
                    documentWidth = document!!.width,
                    documentHeight = document!!.height,
                    bitmapLoader = loader,
                    onNamedAction = ::handleNamedAction,
                )
            }
            error != null -> Text(
                text = "Failed to load '$name': $error",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * Renders a screen as "one shared binary + per-user data":
 *
 *  1. The binary skeleton comes from the on-disk cache when the server's layoutVersion
 *     ([RemoteScreenService.fetchScreenCached]) matches — it is identical for every user
 *     and only re-downloads on a redesign.
 *  2. The per-user values come from `/<screen>/data` (fetched in parallel) and are pushed
 *     into the document's named `USER:` slots via [StateUpdater], generically — no screen
 *     field names appear in this client.
 *
 * The device's real metrics — density from [LocalDensity], width/height in dp from
 * [LocalConfiguration] — are sent to the server so it renders at the correct scale. Re-fetches
 * whenever [screen] or any metric changes (e.g. rotation).
 *
 * Requires the server running (`./gradlew :server:run --args="--serve 8080"`) and, for a physical
 * device, [RemoteScreenService.BASE_URL] pointed at your machine's LAN IP.
 */
@Composable
fun RCFromManifest(screen: String = "profile") {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp

    var document by remember { mutableStateOf<CoreDocument?>(null) }
    var screenData by remember { mutableStateOf<ScreenData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bitmap slots resolve asynchronously; each arrival recomposes and re-runs `update`.
    val slotBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    // One loader instance per screen; it triggers a repaint when an async image arrives.
    var repaintTick by remember { mutableStateOf(0) }
    val loader = remember { CoilBitmapLoader(context = context, onImageReady = { repaintTick++ }) }

    LaunchedEffect(screen, density, widthDp, heightDp) {
        error = null
        document = null
        screenData = null
        slotBitmaps.clear()
        runCatching {
            withContext(Dispatchers.IO) {
                // Bootstrap: the manifest is the only URL the app constructs; it tells us
                // where the binary and data live (and where analytics events go).
                val manifest = RemoteScreenService.fetchManifest(screen)
                coroutineScope {
                    // Skeleton and per-user data in parallel, both straight from the
                    // server (no binary disk cache — swap in fetchScreenCached to only
                    // re-download on layoutVersion changes). A data failure is non-fatal:
                    // the skeleton renders with its baked defaults rather than erroring
                    // the whole screen.
                    val data = async {
                        runCatching { RemoteScreenService.fetchData(manifest, density, widthDp, heightDp) }
                            .onFailure { Log.w("RC_SERVER", "data fetch '$screen' failed — using skeleton defaults", it) }
                            .getOrNull()
                    }
                    val bytes = RemoteScreenService.fetchBinary(manifest, density, widthDp, heightDp)
                    val doc = CoreDocument().also {
                        it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
                    }
                    doc to data.await()
                }
            }
        }.onSuccess { (doc, data) ->
            document = doc
            screenData = data
        }.onFailure {
            error = it.message
            Log.e("RC_SERVER", "fetch '$screen' failed", it)
        }
    }

    // Resolve ScreenData.bitmapUrls into Bitmaps off the main thread.
    LaunchedEffect(screenData) {
        val urls = screenData?.bitmapUrls ?: return@LaunchedEffect
        val imageLoader = SingletonImageLoader.get(context)
        urls.forEach { (slot, url) ->
            launch(Dispatchers.IO) {
                val result = imageLoader.execute(
                    ImageRequest.Builder(context).data(url).allowHardware(false).build()
                )
                ((result as? SuccessResult)?.image as? BitmapImage)
                    ?.let { slotBitmaps[slot] = it.bitmap }
                    ?: Log.w("RC_SERVER", "bitmap slot '$slot' failed to load: $url")
            }
        }
    }

    when {
        document != null -> key(repaintTick) {
            RemoteDocumentPlayer(
                modifier = Modifier.fillMaxSize(),
                document = document!!,
                documentWidth = document!!.width,
                documentHeight = document!!.height,
                bitmapLoader = loader,
                // Data MUST be applied in `update`, not `init`: init fires in the view
                // factory before setDocument, when the USER: slots aren't registered yet
                // and setUserLocal* silently no-ops. `update` runs after setDocument on
                // every recomposition, so values re-apply idempotently — including after
                // the player is recreated by a repaintTick bump.
                update = { player ->
                    screenData?.let { applyScreenData(player.stateUpdater, it) }
                    slotBitmaps.forEach { (slot, bmp) -> player.stateUpdater.setUserLocalBitmap(slot, bmp) }
                },
                onNamedAction = ::handleNamedAction,
            )
        }
        error != null -> Text(
            text = "Failed to load '$screen': $error",
            color = Color.White,
        )
        else -> CircularProgressIndicator(color = Color.White)
    }
}

/**
 * Pushes a [ScreenData] payload into the live document's named `USER:` slots. Generic by
 * design: it iterates the typed maps and never names a screen's fields, so new fields or
 * whole new screens need zero client changes.
 */
fun applyScreenData(stateUpdater: StateUpdater, data: ScreenData) {
    data.strings.forEach { (slot, value) -> stateUpdater.setUserLocalString(slot, value) }
    data.ints.forEach { (slot, value) -> stateUpdater.setUserLocalInt(slot, value) }
    data.floats.forEach { (slot, value) -> stateUpdater.setUserLocalFloat(slot, value) }
    data.colors.forEach { (slot, value) ->
        runCatching { android.graphics.Color.parseColor(value) }
            .onSuccess { stateUpdater.setUserLocalColor(slot, it) }
            .onFailure { Log.w("RC_SERVER", "bad color '$value' for slot '$slot'") }
    }
}

@Composable
fun RCFromFile() {
    val context = LocalContext.current
    var document by remember { mutableStateOf<CoreDocument?>(null) }
    // One loader instance per screen; it triggers a repaint when an async image arrives.
    var repaintTick by remember { mutableStateOf(0) }
    val loader = remember { CoilBitmapLoader(context = context, onImageReady = { repaintTick++ }) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val bytes = context.assets.open("weather_widget.rc").readBytes()
            val doc = CoreDocument().also {
                it.initFromBuffer(RemoteComposeBuffer.fromInputStream(bytes.inputStream()))
            }
            document = doc
        }
    }

    document?.let { doc ->
        Log.d("ROHIT", "RCFromFile: document width: ${doc.width}")
        Log.d("ROHIT", "RCFromFile: document height: ${doc.height}")
        Log.d("ROHIT", "RCFromFile: document density: ${doc.density}")
        Log.d("ROHIT", "RCFromFile: document density behaviour: ${doc.densityBehavior}")
        key(repaintTick) {
            RemoteDocumentPlayer(
                modifier = Modifier.fillMaxSize(),
                document = doc,
                documentWidth = doc.width,
                documentHeight = doc.height,
                bitmapLoader = loader,
                onNamedAction = ::handleNamedAction,
            )
        }
    }
}

/**
 * Central dispatcher for baked host actions.
 *  - [name]  = channel/verb baked on the server (e.g. "analytics", "jar:profile:manage_upi").
 *  - [value] = optional String payload (JSON) carried by `hostActionValue(...)`; null for
 *              plain name-only actions.
 *  - [stateUpdater] = handle to push values back into the document (e.g. setUserLocalString).
 *
 * Route on [name], parse [value] per channel. Add new branches here as the server grows.
 */
fun handleNamedAction(name: String, value: Any?, stateUpdater: StateUpdater) {
    Log.d("RC_ACTION", "onNamedAction name=$name value=$value")
    val payload = (value as? String)
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    when (name) {
        "analytics" -> {
            val eventName = payload?.get("eventName")?.jsonPrimitive?.content.orEmpty()
            Log.d("RC_ACTION", "analytics event=$eventName → forwarding to manifest endpoint")
            // Forward the baked payload as-is to the endpoint the manifest advertised.
            (value as? String)?.let { RemoteScreenService.postAnalytics(it) }
        }
        "deeplink" -> {
            Log.d("RC_ACTION", "handle deeplink upiId=$payload")
        }
        else -> Log.w("RC_ACTION", "Unhandled action '$name' value=$value")
    }
}

 fun JarRemoteComposeView(context: Context): ByteArray? {
     var document: CapturedDocument? = null
     runBlocking {

          document = captureSingleRemoteDocument(
             context = context,
             content = {
                 RemoteBox(
                     modifier = RemoteModifier.background(RemoteColor(0xFF000000.toInt())).fillMaxSize(),
                     contentAlignment = RemoteAlignment.Center
                 ) {

                     RemoteText(
                         text = "Remote Compose Rendered",
                         fontSize = 16.rsp,
                         color = RemoteColor(0xFFFFFFFF.toInt())
                     )
                     RemoteText(
                         text = "Remote Compose Rendered",
                         fontSize = 16.rsp,
                         color = RemoteColor(0xFFFFFFFF.toInt())
                     )

                 }
             }

         )
     }

    return document?.bytes

}

@Composable
fun RCFromBytes() {
    val context = LocalContext.current
    val bytes: ByteArray? = JarRemoteComposeView(context = context)
    if (bytes != null) {
        val document = CoreDocument().also {
            it.initFromBuffer(
                RemoteComposeBuffer.fromInputStream(bytes.inputStream())
            )
        }
        RemoteDocumentPlayer(
            document = document,
            documentWidth = document.width,
            documentHeight = document.height,
            modifier = Modifier.fillMaxSize(),
            debugMode = 1
        )
    }
}
