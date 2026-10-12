package com.example.remotecompose

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Stand-in for the backend while the Withdraw APIs do not exist: an `apiCall` url maps to
 * `assets/api/<sanitised url>.json` (`/v1/api/nek/silver/pdp?category=her` →
 * `v1_api_nek_silver_pdp_category_her.json`). The file is the FULL response body
 * (`{"data": {...}}`) — the host replaces the whole namespace, like jar's `into`.
 */
object MockApi {
    private val json = Json { ignoreUnknownKeys = true }

    fun fileFor(url: String): String =
        "api/" + url.trimStart('/').replace(Regex("[^A-Za-z0-9]+"), "_").trim('_') + ".json"

    suspend fun load(context: Context, url: String): JsonObject? = withContext(Dispatchers.IO) {
        val file = fileFor(url)
        runCatching { context.assets.open(file).bufferedReader().readText() }
            .onFailure { Log.w("RC_MOCK", "no mock for $url ($file)") }
            .getOrNull()
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
    }
}
