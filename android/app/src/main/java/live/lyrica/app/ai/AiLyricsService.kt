package live.lyrica.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.core.model.SyncPrecision
import live.lyrica.app.security.SecureTokenStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AiLyricsService — handles translation and transliteration (Romanization)
 * using user-provided API keys from Groq or OpenRouter with persistent disk caching.
 */
class AiLyricsService(
    private val context: Context,
    private val storage: SecureTokenStorage = SecureTokenStorage(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "AiLyricsService"

    // In-memory cache for translated/transliterated documents
    private val memoryCache = ConcurrentHashMap<String, LyricsDocument>()

    private val diskCacheDir: File by lazy {
        File(context.cacheDir, "ai_lyrics").apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun translateOrRomanize(
        doc: LyricsDocument,
        targetLanguage: String,
        isRomanize: Boolean
    ): Result<LyricsDocument> = withContext(Dispatchers.IO) {
        val provider = getSelectedProvider()
        val apiKey = getApiKey(provider)
        val model = getSelectedModel(provider)

        val rawKey = "${doc.artist}:::${doc.title}:::${if (isRomanize) "romanize" else "translate"}:::$targetLanguage:::$model"
        val cacheKey = hashKey(rawKey)

        // 1. Check in-memory cache
        memoryCache[cacheKey]?.let { return@withContext Result.success(it) }

        // 2. Check persistent disk cache
        loadFromDisk(cacheKey)?.let {
            memoryCache[cacheKey] = it
            LyricaLogger.d(TAG, "Loaded AI ${if (isRomanize) "romanization" else "translation"} from persistent disk cache (0 tokens consumed)")
            return@withContext Result.success(it)
        }

        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("No API key configured for ${provider.displayName}. Please add your key in AI Settings.")
            )
        }

        try {
            val nonBlankIndices = mutableListOf<Int>()
            val inputLines = mutableListOf<String>()

            doc.lines.forEachIndexed { idx, line ->
                if (line.text.isNotBlank()) {
                    nonBlankIndices.add(idx)
                    inputLines.add(line.text)
                }
            }

            if (inputLines.isEmpty()) {
                return@withContext Result.success(doc)
            }

            val systemPrompt = if (isRomanize) {
                """
                You are a professional lyrics transliterator.
                1. Transliterate ONLY the song lyrics provided into $targetLanguage script (Latin/Roman alphabet).
                2. Return EXACTLY ${inputLines.size} lines matching the ${inputLines.size} input lines.
                3. Preserve original pronunciation as closely as possible.
                4. Preserve line order exactly.
                5. Do NOT add line numbers, bullet points, introductory headers, notes, or explanations.
                6. If a line is already in Latin script, return it unchanged.
                7. Return ONLY the transliterated lines, nothing else.
                """.trimIndent()
            } else {
                """
                You are a professional song lyrics translator.
                1. Translate ONLY the song lyrics provided into $targetLanguage.
                2. Return EXACTLY ${inputLines.size} lines matching the ${inputLines.size} input lines.
                3. Preserve line order exactly.
                4. Maintain poetic and musical rhythm of the lyrics.
                5. Do NOT add line numbers, bullet points, introductory headers, notes, or explanations.
                6. Return ONLY the translated lines, nothing else.
                """.trimIndent()
            }

            val userContent = inputLines.joinToString("\n")

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                if (provider == AiProvider.GROQ) {
                    put("reasoning_effort", "low")
                }
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                })
            }

            val requestBuilder = Request.Builder()
                .url(provider.endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "LyricaLive/2.0")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            if (provider == AiProvider.OPENROUTER) {
                requestBuilder.addHeader("HTTP-Referer", "https://lyrica.live")
                requestBuilder.addHeader("X-Title", "Lyrica Live")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                LyricaLogger.e(TAG, "${provider.displayName} API error (${response.code}): $responseBody")
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
                } catch (_: Exception) {
                    responseBody
                }
                return@withContext Result.failure(Exception("${provider.displayName} error (${response.code}): $errorMsg"))
            }

            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(Exception("Empty choices in ${provider.displayName} response"))
            }

            val messageObj = choices.getJSONObject(0).optJSONObject("message")
            val rawOutput = messageObj?.optString("content")?.trim() ?: ""

            // Clean output from reasoning tags, markdown headers, and blockquotes
            val outputLines = validateAndCleanOutput(inputLines.size, rawOutput)

            if (outputLines == null || outputLines.size != inputLines.size) {
                LyricaLogger.w(TAG, "Line count mismatch: input=${inputLines.size}, output=${outputLines?.size}. Raw output: $rawOutput")
                return@withContext Result.failure(Exception("AI output formatting issue. Expected ${inputLines.size} lines, got ${outputLines?.size ?: 0}. Please try again."))
            }

            // Reconstruct full list of lines with exact original timestamps
            val newLines = doc.lines.mapIndexed { idx, origLine ->
                val nonBlankPos = nonBlankIndices.indexOf(idx)
                if (nonBlankPos >= 0) {
                    origLine.copy(text = outputLines[nonBlankPos])
                } else {
                    origLine
                }
            }

            val transformedDoc = doc.copy(
                lines = newLines,
                provider = "${doc.provider} [${if (isRomanize) "Romanized" else "Translated"}]"
            )

            // Save to memory and disk cache
            memoryCache[cacheKey] = transformedDoc
            saveToDisk(cacheKey, transformedDoc)

            LyricaLogger.i(TAG, "Successfully ${if (isRomanize) "romanized" else "translated"} lyrics to $targetLanguage with ${provider.displayName} ($model)")
            return@withContext Result.success(transformedDoc)

        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Failed to process lyrics with AI: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    private fun validateAndCleanOutput(expectedCount: Int, rawOutput: String): List<String>? {
        if (rawOutput.isBlank()) return null

        // 1. Remove <think>...</think> reasoning blocks if present
        var text = rawOutput.replace(Regex("""(?s)<think>.*?</think>"""), "").trim()

        // 2. Strip leading introductory headers like "**Translation (to English):**" or "Translation:"
        text = text.replace(Regex("""^(?i)\*?\*?(Translation|Romanized|Transliteration)[^\n]*\*?\*?\n+"""), "").trim()

        // 3. Process line by line, stripping blockquote prefixes `> ` and list markers `1. ` or `* `
        val cleanedLines = text.lines()
            .map { line ->
                var l = line.trim()
                l = l.replace(Regex("""^>\s*"""), "")
                l = l.replace(Regex("""^[\*\-•]\s*"""), "")
                l = l.replace(Regex("""^\d+[\.\)]\s*"""), "")
                l = l.replace(Regex("""^\*\*|\*\*$"""), "")
                l.trim()
            }
            .filter { it.isNotEmpty() }

        if (cleanedLines.size == expectedCount) {
            return cleanedLines
        }

        // If line count is slightly larger due to intro header not stripped, attempt drop of first line
        if (cleanedLines.size == expectedCount + 1 && (cleanedLines[0].contains("Translation", ignoreCase = true) || cleanedLines[0].contains("Romanized", ignoreCase = true))) {
            return cleanedLines.drop(1)
        }

        return if (cleanedLines.size == expectedCount) cleanedLines else null
    }

    // ── Persistent Disk Caching ────────────────────────────────────────────

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun saveToDisk(cacheKey: String, doc: LyricsDocument) {
        try {
            val file = File(diskCacheDir, "$cacheKey.json")
            val json = JSONObject().apply {
                put("artist", doc.artist)
                put("title", doc.title)
                put("provider", doc.provider)
                put("precision", doc.syncPrecision.name)
                val linesArr = JSONArray()
                doc.lines.forEach { line ->
                    linesArr.put(JSONObject().apply {
                        put("id", line.id)
                        put("startMs", line.startMs)
                        put("endMs", line.endMs)
                        put("text", line.text)
                    })
                }
                put("lines", linesArr)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Failed to save AI lyrics to disk: ${e.message}")
        }
    }

    private fun loadFromDisk(cacheKey: String): LyricsDocument? {
        val file = File(diskCacheDir, "$cacheKey.json")
        if (!file.exists()) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val linesArr = json.optJSONArray("lines") ?: return null
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until linesArr.length()) {
                val obj = linesArr.getJSONObject(i)
                lines.add(
                    LyricLine(
                        id = obj.optString("id", "l_$i"),
                        startMs = obj.optLong("startMs", 0L),
                        endMs = obj.optLong("endMs", 0L),
                        text = obj.optString("text", "")
                    )
                )
            }
            val precisionStr = json.optString("precision", "LINE")
            LyricsDocument(
                artist = json.optString("artist", ""),
                title = json.optString("title", ""),
                provider = json.optString("provider", ""),
                syncPrecision = try { SyncPrecision.valueOf(precisionStr) } catch (_: Exception) { SyncPrecision.LINE },
                lines = lines
            )
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Error loading cached AI lyrics from disk: ${e.message}")
            null
        }
    }

    // ── Preferences Management ─────────────────────────────────────────────

    fun getSelectedProvider(): AiProvider {
        val id = storage.getString("ai_provider") ?: AiProvider.GROQ.id
        return AiProvider.fromId(id)
    }

    fun setSelectedProvider(provider: AiProvider) {
        storage.putString("ai_provider", provider.id)
    }

    fun getApiKey(provider: AiProvider): String? {
        return storage.getString("ai_key_${provider.id}")
    }

    fun setApiKey(provider: AiProvider, key: String?) {
        storage.putString("ai_key_${provider.id}", key?.trim())
    }

    fun getSelectedModel(provider: AiProvider): String {
        return storage.getString("ai_model_${provider.id}") ?: provider.defaultModel
    }

    fun setSelectedModel(provider: AiProvider, model: String) {
        storage.putString("ai_model_${provider.id}", model.trim())
    }

    fun getPreferredLanguage(): String {
        return storage.getString("ai_preferred_language") ?: "English"
    }

    fun setPreferredLanguage(language: String) {
        storage.putString("ai_preferred_language", language.trim())
    }

    fun getAutoAiMode(): AutoAiMode {
        val id = storage.getString("ai_auto_mode") ?: AutoAiMode.OFF.id
        return AutoAiMode.fromId(id)
    }

    fun setAutoAiMode(mode: AutoAiMode) {
        storage.putString("ai_auto_mode", mode.id)
    }

    fun hasKeyForSelectedProvider(): Boolean {
        val provider = getSelectedProvider()
        return !getApiKey(provider).isNullOrBlank()
    }
}
