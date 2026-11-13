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
     * Transliterates Romanized Bengali (Benglish / Banglish) text to Bengali script
     * using Google Input Tools API (itc=bn-t-i0-und).
     * Preserves exact 1:1 word count without inserting missing words.
     */
    suspend fun transliterateBenglish(text: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.success("")
        }

        runCatching {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "https://inputtools.google.com/request?text=$encodedQuery&itc=bn-t-i0-und&num=5"
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
            parseInputToolsResponse(responseText)
        }
    }

    internal fun parseInputToolsResponse(responseText: String): String {
        val trimmed = responseText.trim()
        val rootArray = JSONArray(trimmed)
        if (rootArray.length() < 2) throw IllegalStateException("Invalid response format")
        val status = rootArray.optString(0, "")
        if (status != "SUCCESS") throw IllegalStateException("API returned status: $status")
        val dataArray = rootArray.optJSONArray(1) ?: throw IllegalStateException("No data array")
        if (dataArray.length() == 0) return ""

        val sb = StringBuilder()
        for (i in 0 until dataArray.length()) {
            val entry = dataArray.optJSONArray(i) ?: continue
            val candidates = entry.optJSONArray(1)
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.optString(0, "")
                if (candidate.isNotEmpty()) {
                    if (sb.isNotEmpty() && !sb.endsWith(" ") && !candidate.startsWith(" ")) {
                        sb.append(" ")
                    }
                    sb.append(candidate)
                }
            }
        }
        val result = sb.toString()
        if (result.isEmpty()) throw IllegalStateException("No candidate found")
        return result
    }

    /**
     * Romanizes Bengali script to Latin letters (Benglish / Banglish) using Google Translate transliteration endpoint (dt=rm).
     * e.g. "কত দেবো" -> "koto debo", "তুমি কেমন আছো" -> "tumi kemon acho"
     */
    suspend fun romanizeBengali(text: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext Result.success("")

        runCatching {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=bn&tl=en&dt=rm&q=$encodedQuery"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "GoogleTranslate/6.28.0.RC05.420624444 (Linux; U; Android 12; Pixel 6)")
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error $responseCode: ${connection.responseMessage}")
            }

            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRomanizeResponse(responseText)
        }
    }

    internal fun parseRomanizeResponse(responseText: String): String {
        val jsonArray = JSONArray(responseText.trim())
        val segments = jsonArray.optJSONArray(0) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONArray(i) ?: continue
            val roman = if (segment.length() > 3 && !segment.isNull(3)) {
                segment.optString(3, "")
            } else if (segment.length() > 2 && !segment.isNull(2)) {
                segment.optString(2, "")
            } else {
                ""
            }
            if (roman.isNotEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith(" ") && !roman.startsWith(" ")) {
                    sb.append(" ")
                }
                sb.append(roman)
            }
        }
        val result = sb.toString().trim()
        if (result.isEmpty()) throw IllegalStateException("No romanization found")
        return result
    }

    /**
     * Translates input text from [sourceLang] (e.g. "bn", "benglish", "en") to [targetLang] (e.g. "en", "hi", "bn").
     * When sourceLang is "benglish" or target is "bn" with Latin characters, performs Benglish-to-Bengali transliteration.
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

        // Bengali script to Romanized Benglish (Banglish) transliteration:
        if (targetLang == "benglish") {
            val romanizeResult = romanizeBengali(trimmed)
            if (romanizeResult.isSuccess && !romanizeResult.getOrNull().isNullOrBlank()) {
                return@withContext romanizeResult
            }
            Log.w(TAG, "Romanization failed, attempting translation fallback: ${romanizeResult.exceptionOrNull()?.message}")
        }

        // Benglish / Banglish (Roman Bengali) to Bengali script transliteration:
        // When source is "benglish", or target is "bn" and text contains Latin characters
        if (sourceLang == "benglish" || (targetLang == "bn" && text.any { it in 'a'..'z' || it in 'A'..'Z' })) {
            val transliterateResult = transliterateBenglish(trimmed)
            if (transliterateResult.isSuccess && !transliterateResult.getOrNull().isNullOrBlank()) {
                return@withContext transliterateResult
            }
            Log.w(TAG, "Transliteration failed, attempting translation fallback: ${transliterateResult.exceptionOrNull()?.message}")
        }

        // If target is Bengali and input is already in Bengali script without Latin characters, return as-is
        if (targetLang == "bn" && text.any { it in '\u0980'..'\u09FF' } && !text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            return@withContext Result.success(trimmed)
        }

        // Map "benglish" to "auto" if falling back to standard translation endpoints
        val effectiveSource = if (sourceLang == "benglish") "bn" else sourceLang
        val effectiveTarget = if (targetLang == "benglish") "en" else targetLang

        // Try primary translation endpoint (clients5.google.com)
        val primaryResult = tryClients5(trimmed, effectiveSource, effectiveTarget)
        if (primaryResult.isSuccess) {
            return@withContext primaryResult
        }

        Log.w(TAG, "Primary translation failed, attempting fallback: ${primaryResult.exceptionOrNull()?.message}")

        // Try fallback translation endpoint (translate.googleapis.com)
        val fallbackResult = tryGoogleApis(trimmed, effectiveSource, effectiveTarget)
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
