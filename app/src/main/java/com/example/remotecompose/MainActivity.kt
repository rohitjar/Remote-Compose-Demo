package com.example.remotecompose

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.example.remotecompose.ui.theme.RemoteComposeTheme
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Limits.ENABLE_IMAGE_URLS = true
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("LOOKHERE", "onCreate() called with: savedInstanceState = ${(JarRemoteComposeView(context = this).toString())}")
        setContent {
            RemoteComposeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color.Blue),
                        contentAlignment = Alignment.Center
                    ) {
                        RCFromFile()
                    }
                }
            }
        }
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
            val bytes = context.assets.open("profile.rc").readBytes()
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
    val payload = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
    when (name) {
        "analytics" -> {
            val eventName = payload?.optString("eventName").orEmpty()
            val paramsJson = payload?.optJSONObject("params")
            val params = buildMap {
                paramsJson?.keys()?.forEach { k -> put(k, paramsJson.opt(k)?.toString().orEmpty()) }
            }
            Log.d("RC_ACTION", "analytics event=$eventName params=$params")
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
