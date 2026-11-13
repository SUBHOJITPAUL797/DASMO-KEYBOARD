// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fast, resilient Google Translate client for Bengali -> English / Hindi translation.
 * Uses lightweight HttpURLConnection with JSON parsing and background coroutines.
 */
object TranslationService {
    private const val TAG = "TranslationService"
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 8000
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Translates input text from [sourceLang] (e.g. "bn") to [targetLang] (e.g. "en" or "hi").
     * Returns Result with translated String or exception.
     */
    suspend fun translate(
        text: String,
        sourceLang: String = "bn",
        targetLang: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.success("")
        }

        // Try primary translation endpoint (clients5.google.com)
        val primaryResult = tryClients5(trimmed, sourceLang, targetLang)
        if (primaryResult.isSuccess) {
            return@withContext primaryResult
        }

        Log.w(TAG, "Primary translation failed, attempting fallback: ${primaryResult.exceptionOrNull()?.message}")

        // Try fallback translation endpoint (translate.googleapis.com)
        val fallbackResult = tryGoogleApis(trimmed, sourceLang, targetLang)
        if (fallbackResult.isSuccess) {
            return@withContext fallbackResult
        }

        primaryResult
    }

    private fun tryClients5(text: String, sourceLang: String, targetLang: String): Result<String> {
        return runCatching {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$sourceLang&tl=$targetLang&q=$encodedQuery"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseClients5Response(responseText)
        }
    }

    private fun tryGoogleApis(text: String, sourceLang: String, targetLang: String): Result<String> {
        return runCatching {
            val encodedQuery = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedQuery"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseGoogleApisResponse(responseText)
        }
    }

    private fun parseClients5Response(jsonString: String): String {
        val trimmed = jsonString.trim()
        val jsonArray = JSONArray(trimmed)
        if (jsonArray.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.get(i)
            when (item) {
                is String -> sb.append(item)
                is JSONArray -> {
                    if (item.length() > 0) {
                        val first = item.get(0)
                        if (first is String) {
                            sb.append(first)
                        } else if (first is JSONArray) {
                            for (j in 0 until first.length()) {
                                val sub = first.get(j)
                                if (sub is String) sb.append(sub)
                            }
                        }
                    }
                }
                else -> sb.append(item.toString())
            }
        }
        return sb.toString()
    }

    private fun parseGoogleApisResponse(jsonString: String): String {
        val trimmed = jsonString.trim()
        val jsonArray = JSONArray(trimmed)
        val sentences = jsonArray.optJSONArray(0) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            val sentence = sentences.optJSONArray(i)
            if (sentence != null && sentence.length() > 0) {
                sb.append(sentence.optString(0, ""))
            }
        }
        return sb.toString()
    }
}
