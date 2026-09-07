package live.lyrica.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.LyricLine
import live.lyrica.app.core.model.LyricsDocument
import live.lyrica.app.security.SecureTokenStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AiLyricsService — handles translation and transliteration (Romanization)
 * using user-provided API keys from Groq or OpenRouter.
 */
class AiLyricsService(
    private val storage: SecureTokenStorage,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val TAG = "AiLyricsService"

    // In-memory cache for translated/transliterated documents
    private val cache = ConcurrentHashMap<String, LyricsDocument>()

    suspend fun translateOrRomanize(
        doc: LyricsDocument,
        targetLanguage: String,
        isRomanize: Boolean
    ): Result<LyricsDocument> = withContext(Dispatchers.IO) {
        val provider = getSelectedProvider()
        val apiKey = getApiKey(provider)
        val model = getSelectedModel(provider)

        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("No API key configured for ${provider.displayName}. Please add your key in AI Settings.")
            )
        }

        val cacheKey = "${doc.artist}:::${doc.title}:::${if (isRomanize) "romanize" else "translate"}:::$targetLanguage:::$model"
        cache[cacheKey]?.let { return@withContext Result.success(it) }

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
                You are a professional lyrics transliterator/romanizer.
                1. Transliterate/romanize ONLY the song lyrics provided into $targetLanguage script (Latin/Roman alphabet).
                2. Return EXACTLY ${inputLines.size} lines — one transliterated line for each input line.
                3. Preserve the original pronunciation as closely as possible.
                4. Preserve line order exactly.
                5. Do NOT add line numbers, bullet points, explanations, notes, or extra text.
                6. If a line is already in the target script, return it unchanged.
                7. Return ONLY the transliterated lines, nothing else.
                """.trimIndent()
            } else {
                """
                You are a professional song lyrics translator.
                1. Translate ONLY the song lyrics provided into $targetLanguage.
                2. Return EXACTLY ${inputLines.size} lines — one translated line for each input line.
                3. Preserve line order exactly.
                4. Maintain the poetic and musical rhythm of the lyrics.
                5. Do NOT add line numbers, bullet points, explanations, notes, or extra text.
                6. Return ONLY the translated lines, nothing else.
                """.trimIndent()
            }

            val userContent = inputLines.joinToString("\n")

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
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
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            if (provider == AiProvider.OPENROUTER) {
                requestBuilder.addHeader("HTTP-Referer", "https://lyrica.live")
                requestBuilder.addHeader("X-Title", "Lyrica Live")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                LyricaLogger.e(TAG, "${provider.displayName} API error (${response.code}): $errorBody")
                return@withContext Result.failure(Exception("${provider.displayName} error (${response.code}): $errorBody"))
            }

            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(Exception("Empty choices in ${provider.displayName} response"))
            }

            val messageObj = choices.getJSONObject(0).optJSONObject("message")
            val rawOutput = messageObj?.optString("content")?.trim() ?: ""
            val outputLines = validateAndCleanOutput(inputLines.size, rawOutput)

            if (outputLines == null || outputLines.size != inputLines.size) {
                LyricaLogger.w(TAG, "Line count mismatch: input=${inputLines.size}, output=${outputLines?.size}")
                return@withContext Result.failure(Exception("AI returned an unexpected line count format. Please try again."))
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

            cache[cacheKey] = transformedDoc
            LyricaLogger.i(TAG, "Successfully ${if (isRomanize) "romanized" else "translated"} lyrics to $targetLanguage with ${provider.displayName}")
            return@withContext Result.success(transformedDoc)

        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Failed to process lyrics with AI: ${e.message}", e)
            return@withContext Result.failure(e)
        }
    }

    private fun validateAndCleanOutput(expectedCount: Int, rawOutput: String): List<String>? {
        val lines = rawOutput.lines().map { it.trim() }
        if (lines.size == expectedCount) return lines

        // Filter out empty leading/trailing lines
        val filtered = lines.filter { it.isNotEmpty() }
        if (filtered.size == expectedCount) return filtered

        return null
    }

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

    fun hasKeyForSelectedProvider(): Boolean {
        val provider = getSelectedProvider()
        return !getApiKey(provider).isNullOrBlank()
    }
}
