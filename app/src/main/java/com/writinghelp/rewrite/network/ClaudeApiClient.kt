package com.writinghelp.rewrite.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ClaudeResult {
    data class Success(val text: String) : ClaudeResult()
    data class Failure(val message: String, val isAuthError: Boolean = false) : ClaudeResult()
}

/**
 * Minimal client for the Claude Messages API (https://api.anthropic.com/v1/messages).
 *
 * This talks to the API directly over HTTP rather than pulling in Anthropic's Java server SDK:
 * that SDK targets backend JVM services, and bundling it into a phone app for one endpoint would
 * add a lot of dead weight (and its own HTTP stack) for no benefit over a plain OkHttp call.
 */
class ClaudeApiClient(
    private val apiKey: String,
    private val model: String
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun sendMessage(systemPrompt: String, userText: String): ClaudeResult =
        withContext(Dispatchers.IO) {
            try {
                val requestJson = buildRequestBody(systemPrompt, userText)
                val request = Request.Builder()
                    .url(API_URL)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("content-type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return@withContext ClaudeResult.Failure(
                            message = extractErrorMessage(bodyString, response.code),
                            isAuthError = response.code == 401 || response.code == 403
                        )
                    }

                    val text = extractText(bodyString)
                    if (text.isNullOrBlank()) {
                        ClaudeResult.Failure("Claude returned an empty response. Please try again.")
                    } else {
                        ClaudeResult.Success(text.trim())
                    }
                }
            } catch (e: IOException) {
                ClaudeResult.Failure("Couldn't reach Claude: ${e.message ?: "network error"}")
            } catch (e: Exception) {
                ClaudeResult.Failure("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun buildRequestBody(systemPrompt: String, userText: String): JSONObject {
        val userMessage = JSONObject()
            .put("role", "user")
            .put("content", userText)

        // effort "low" keeps latency and cost down for this kind of short, single-shot
        // text transformation - see Anthropic's guidance on tuning effort per workload.
        val outputConfig = JSONObject().put("effort", "low")

        return JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(userMessage))
            .put("output_config", outputConfig)
    }

    /** Concatenates every text content block in the response (there may be a thinking block first). */
    private fun extractText(body: String): String? {
        val content = JSONObject(body).optJSONArray("content") ?: return null
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                builder.append(block.optString("text"))
            }
        }
        return builder.toString()
    }

    private fun extractErrorMessage(body: String, code: Int): String = try {
        val message = JSONObject(body).optJSONObject("error")?.optString("message")
        if (!message.isNullOrBlank()) message else "Claude API error ($code)"
    } catch (e: Exception) {
        "Claude API error ($code)"
    }

    private companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_OUTPUT_TOKENS = 4096
    }
}
