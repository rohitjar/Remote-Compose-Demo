package com.example.remotecompose

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "RC_OPENER"

/** Host result: a play-once overlay media hole played past its `.shownAt` (server `RcWire.ACTION_MEDIA_SHOWN`). */
const val ACTION_MEDIA_SHOWN = "media_shown"

/** The opener fact the withdraw backend gates its intro on (server `WithdrawArgs.VISIT_COUNT`). */
private const val VISIT_COUNT = "visitCount"

private const val OPENER_PREFS = "rc_opener"

/**
 * The demo's stand-in for the native code that OPENS the withdraw screen — jar's
 * `WithdrawalOptionFragment` + `WithdrawalOptionViewModel`:
 *  - it keeps the persisted guiding-Lottie visit count (jar `WITHDRAWAL_OPTIONS_VISIT_COUNT`) and
 *    passes it as `args.visitCount`, omitted until the first counted view (jar sends no
 *    `visitCount` while the pref is null);
 *  - on the host result `media_shown` it bumps the count — jar's `OnGuidingLottieShown` at 60 %.
 *
 * The count is read once per opening, as jar's view model reads it once per fetch. Reset it with
 * `adb shell pm clear com.example.remotecompose`.
 */
@Composable
fun RcScreenOpener(screen: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(OPENER_PREFS, Context.MODE_PRIVATE) }
    val countKey = "$screen.$VISIT_COUNT"
    val args = remember(screen) {
        buildJsonObject { if (prefs.contains(countKey)) put(VISIT_COUNT, prefs.getInt(countKey, 0)) }
    }
    RCFromLocalManifestPager(
        screen = screen,
        args = args,
        onResult = { result ->
            Log.d(TAG, "result $result")
            if (result["action"]?.jsonPrimitive?.contentOrNull == ACTION_MEDIA_SHOWN) {
                val count = prefs.getInt(countKey, 0) + 1
                prefs.edit { putInt(countKey, count) }
                Log.d(TAG, "$screen: guiding lottie view counted → $VISIT_COUNT $count")
            }
        },
    )
}

/**
 * Stand-in for the product backend behind POST data links — the demo has no server, so the data
 * comes from `assets/data/<screen>.json` and this applies what the backend would decide from the
 * host's `{"args": …}` envelope. Only `options` reacts: like jar's `/v1/api/sellGold/options`, it
 * sends `guidingLottie` only while `visitCount` is below [GUIDING_LOTTIE_MAX_VISITS].
 *
 * The real limit is the backend's; this number only lets the demo show both paths.
 */
object MockBackend {
    const val GUIDING_LOTTIE_MAX_VISITS = 3

    fun respond(endpoint: LocalEndpointRef, args: JsonObject, values: Map<String, JsonPrimitive>): Map<String, JsonPrimitive> {
        if (!endpoint.method.equals("POST", ignoreCase = true)) return values
        Log.d(TAG, "POST ${endpoint.url} {\"args\": $args}")
        if (endpoint.key != "options") return values
        val visits = args[VISIT_COUNT]?.jsonPrimitive?.intOrNull ?: 0
        if (visits < GUIDING_LOTTIE_MAX_VISITS) {
            Log.d(TAG, "mock backend: visitCount $visits < $GUIDING_LOTTIE_MAX_VISITS → guidingLottie sent")
            return values
        }
        Log.d(TAG, "mock backend: visitCount $visits ≥ $GUIDING_LOTTIE_MAX_VISITS → no guidingLottie")
        return values.filterKeys { !it.startsWith("options.data.guidingLottie") }
    }
}
