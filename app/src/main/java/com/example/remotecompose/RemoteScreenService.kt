package com.example.remotecompose

import android.content.Context
import android.util.Log
import com.chuckerteam.chucker.api.ChuckerInterceptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Manifest-first client for the screen server.
 *
 * The app hardcodes exactly ONE URL shape — the manifest ([fetchManifest]):
 *
 *   GET {API_BASE}/screens/{key}  →  { layoutVersion, binary.url, data.url, analytics.url }
 *
 * Every other request follows a URL taken from the manifest verbatim, using the link's
 * declared method ([request]). The backend can move the binary to a CDN, split data onto
 * another service, flip a link from GET to POST, or add endpoints — the client keeps
 * working unchanged.
 *
 * All wire models ([ScreenManifest], [ScreenData], [ScreenRequest]) are kotlinx-serialization
 * mirrors of the server's, decoded with `ignoreUnknownKeys` so additive server changes
 * never break shipped clients.
 */
object RemoteScreenService {

    /**
     * The single static entry point the app knows.
     *
     * Uses `localhost` together with an adb reverse tunnel, which works for BOTH an emulator
     * and a USB-attached physical device — run once per device:
     *   adb reverse tcp:8091 tcp:8091
     *
     * Alternatives: emulator-only `http://10.0.2.2:8091/v1`, LAN IP, or an HTTPS tunnel
     * (e.g. `https://devtestingmyjarapp.link/rc/v1`).
     */
    const val API_BASE = "https://devtestingmyjarapp.link/rc/v1"

    private const val TAG = "RemoteScreenService"

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var client: OkHttpClient

    /** Analytics ingest link from the most recent manifest; null until a manifest loads. */
    @Volatile
    private var analyticsEndpoint: ScreenManifest.Endpoint? = null

    /**
     * Builds the shared OkHttp client. Call once before any fetch (e.g. in
     * MainActivity.onCreate). Every request is recorded twice:
     *  - Chucker: persistent in-app inspector — tap its notification (or the "Chucker"
     *    launcher shortcut) to browse full request/response headers and bodies.
     *  - HttpLoggingInterceptor: mirrors the same traffic to logcat under tag RC_HTTP.
     */
    fun initialize(context: Context) {
        if (::client.isInitialized) return
        val logcat = HttpLoggingInterceptor { message -> Log.d("RC_HTTP", message) }
            .apply { level = HttpLoggingInterceptor.Level.BODY }
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(ChuckerInterceptor.Builder(context.applicationContext).build())
            .addInterceptor(logcat)
            .build()
    }

    /** Fetches the screen's manifest — the bootstrap for everything else. Blocking I/O. */
    @Throws(IOException::class)
    fun fetchManifest(screen: String): ScreenManifest =
        getJson<ScreenManifest>("$API_BASE/screens/$screen")
            .also { analyticsEndpoint = it.analytics }

    /**
     * Fetches the per-user values for the document's USER: slots, following the manifest's
     * declared method — POST sends the standard [ScreenRequest] envelope. [args] is an
     * opaque blob (screen arguments baked into whatever navigated here); the client relays
     * it without parsing. Blocking I/O.
     */
    @Throws(IOException::class)
    fun fetchData(
        manifest: ScreenManifest,
        density: Float? = null,
        widthDp: Int? = null,
        heightDp: Int? = null,
        args: JsonObject? = null,
    ): ScreenData = requestJson(manifest.data, args, density, widthDp, heightDp)

    /**
     * Fetches the screen's binary from the manifest's version-pinned URL — always over the
     * network (visible in Chucker/RC_HTTP on every load), via the manifest's declared
     * method. See [fetchScreenCached] for the disk-cached variant that only downloads on a
     * layoutVersion change.
     */
    @Throws(IOException::class)
    fun fetchBinary(
        manifest: ScreenManifest,
        density: Float,
        widthDp: Int,
        heightDp: Int,
        args: JsonObject? = null,
    ): ByteArray {
        Log.d(TAG, "fetching ${manifest.screen} v${manifest.layoutVersion} from server (${manifest.binary.method})")
        return request(manifest.binary, args, density, widthDp, heightDp)
    }

    /**
     * Returns the screen's binary, from the on-disk cache when we already hold the bytes
     * the manifest's version-pinned URL names, downloading and caching them otherwise.
     * Stale versions of the same screen are evicted on download. Blocking I/O.
     *
     * The cache key includes the render metrics because the current density model bakes
     * them into the binary; once generation is device-independent they can drop out.
     */
    @Throws(IOException::class)
    fun fetchScreenCached(
        context: Context,
        manifest: ScreenManifest,
        density: Float,
        widthDp: Int,
        heightDp: Int,
    ): ByteArray {
        val cacheDir = File(context.filesDir, "rc-cache").apply { mkdirs() }
        val file = File(cacheDir, "${manifest.screen}-v${manifest.layoutVersion}-$density-${widthDp}x$heightDp.rc")
        if (file.exists()) {
            Log.d(TAG, "cache HIT ${manifest.screen} v${manifest.layoutVersion} (${file.length()} bytes)")
            return file.readBytes()
        }
        Log.d(TAG, "cache MISS ${manifest.screen} v${manifest.layoutVersion} — downloading")
        val bytes = request(manifest.binary, null, density, widthDp, heightDp)
        cacheDir.listFiles()
            ?.filter { it.name.startsWith("${manifest.screen}-") && it != file }
            ?.forEach { it.delete() }
        file.writeBytes(bytes)
        return bytes
    }

    /** Wire shape of `GET /v1/documents` — the saved-binary listing. */
    @Serializable
    private data class DocumentList(val documents: List<String> = emptyList())

    /**
     * Lists the binaries saved on the server by `POST /v1/parse/{name}` (the ad-hoc
     * documents directory, distinct from registered screens). Blocking I/O.
     */
    @Throws(IOException::class)
    fun fetchDocuments(): List<String> =
        getJson<DocumentList>("$API_BASE/documents").documents

    /**
     * Fetches a saved document's compiled `.rc` bytes from `GET /v1/documents/{name}.rc`.
     * Saved documents are self-contained — no manifest, layoutVersion, or data round trip.
     * Blocking I/O.
     */
    @Throws(IOException::class)
    fun fetchDocument(name: String): ByteArray =
        httpGet("$API_BASE/documents/$name.rc")

    /**
     * Forwards a baked analytics host-action payload to the manifest's analytics endpoint.
     * Fire-and-forget (async), safe to call from the main thread; drops with a log if no
     * manifest has been loaded yet.
     */
    fun postAnalytics(payloadJson: String) {
        val endpoint = analyticsEndpoint ?: run {
            Log.w(TAG, "analytics event dropped — no manifest loaded yet")
            return
        }
        val httpRequest = Request.Builder()
            .url(endpoint.url)
            .method(endpoint.method, payloadJson.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "analytics post failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * Executes a manifest link by its declared method — the mechanical rule that keeps the
     * client generic:
     *  - GET  → render metrics as query params; [args] as a URL-encoded `args` JSON param.
     *  - POST → the standard [ScreenRequest] `{args, context}` envelope as a JSON body.
     * The server can flip a link's verb in the manifest and shipped clients follow.
     */
    private fun request(
        endpoint: ScreenManifest.Endpoint,
        args: JsonObject?,
        density: Float?,
        widthDp: Int?,
        heightDp: Int?,
    ): ByteArray {
        if (endpoint.method.equals("POST", ignoreCase = true)) {
            val envelope = ScreenRequest(
                args = args,
                context = ClientContext(
                    density = density,
                    widthDp = widthDp,
                    heightDp = heightDp,
                    locale = Locale.getDefault().toLanguageTag(),
                ),
            )
            return httpPost(endpoint.url, json.encodeToString(envelope))
        }
        val query = buildList {
            if (density != null) add("density=$density")
            if (widthDp != null) add("width=$widthDp")
            if (heightDp != null) add("height=$heightDp")
            if (args != null) add("args=" + URLEncoder.encode(args.toString(), "UTF-8"))
        }
        val url = if (query.isEmpty()) endpoint.url else "${endpoint.url}?${query.joinToString("&")}"
        return httpGet(url)
    }

    /**
     * The single place where response bytes become typed models — the same role Retrofit's
     * kotlinx converter plays; with raw OkHttp the converter is simply ours. Call sites
     * just declare the type: `getJson<ScreenManifest>(url)` / `requestJson<ScreenData>(…)`.
     */
    private inline fun <reified T> getJson(url: String): T =
        json.decodeFromString(httpGet(url).decodeToString())

    /** Typed variant of [request] for JSON endpoints (manifest-declared method + envelope). */
    private inline fun <reified T> requestJson(
        endpoint: ScreenManifest.Endpoint,
        args: JsonObject?,
        density: Float?,
        widthDp: Int?,
        heightDp: Int?,
    ): T = json.decodeFromString(request(endpoint, args, density, widthDp, heightDp).decodeToString())

    @Throws(IOException::class)
    private fun httpGet(urlString: String): ByteArray =
        execute(Request.Builder().url(urlString).build())

    @Throws(IOException::class)
    private fun httpPost(urlString: String, jsonBody: String): ByteArray =
        execute(
            Request.Builder()
                .url(urlString)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
        )

    @Throws(IOException::class)
    private fun execute(httpRequest: Request): ByteArray {
        check(::client.isInitialized) { "RemoteScreenService.initialize(context) must be called first" }
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw IOException("Server returned HTTP ${response.code} for ${httpRequest.url}: $err")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }
}
