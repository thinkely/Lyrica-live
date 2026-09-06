package live.lyrica.app.core.model

import org.json.JSONArray
import org.json.JSONObject

enum class SyncPrecision {
    NONE,
    LINE,
    WORD,
    SYLLABLE
}

data class SyllableTiming(
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("start_time", startMs)
        put("end_time", endMs)
        put("text", text)
    }

    companion object {
        fun fromJson(json: JSONObject): SyllableTiming {
            return SyllableTiming(
                startMs = json.optLong("start_time", 0L),
                endMs = json.optLong("end_time", 0L),
                text = json.optString("text", "")
            )
        }
    }
}

data class WordTiming(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<SyllableTiming> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("start_time", startMs)
        put("end_time", endMs)
        put("text", text)
        val sylArray = JSONArray()
        syllables.forEach { sylArray.put(it.toJson()) }
        put("syllables", sylArray)
    }

    companion object {
        fun fromJson(json: JSONObject): WordTiming {
            val syllables = mutableListOf<SyllableTiming>()
            val sylArray = json.optJSONArray("syllables")
            if (sylArray != null) {
                for (i in 0 until sylArray.length()) {
                    val sylObj = sylArray.optJSONObject(i)
                    if (sylObj != null) syllables.add(SyllableTiming.fromJson(sylObj))
                }
            }
            return WordTiming(
                startMs = json.optLong("start_time", 0L),
                endMs = json.optLong("end_time", 0L),
                text = json.optString("text", ""),
                syllables = syllables
            )
        }
    }
}

data class LyricLine(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<WordTiming> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("start_time", startMs)
        put("end_time", endMs)
        put("text", text)
        val wordArray = JSONArray()
        words.forEach { wordArray.put(it.toJson()) }
        put("words", wordArray)
    }

    companion object {
        fun fromJson(json: JSONObject): LyricLine {
            val words = mutableListOf<WordTiming>()
            val wordArray = json.optJSONArray("words")
            if (wordArray != null) {
                for (i in 0 until wordArray.length()) {
                    val wObj = wordArray.optJSONObject(i)
                    if (wObj != null) words.add(WordTiming.fromJson(wObj))
                }
            }
            return LyricLine(
                id = json.optString("id", "line_${json.optLong("start_time", 0L)}"),
                startMs = json.optLong("start_time", 0L),
                endMs = json.optLong("end_time", 0L),
                text = json.optString("text", ""),
                words = words
            )
        }
    }
}

data class LyricsDocument(
    val provider: String,
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val plainLyrics: String = "",
    val lines: List<LyricLine> = emptyList(),
    val syncPrecision: SyncPrecision = SyncPrecision.NONE,
    val isInstrumental: Boolean = false,
    val timestamp: String = ""
) {
    val isSynced: Boolean get() = syncPrecision != SyncPrecision.NONE && lines.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("provider", provider)
        put("artist", artist)
        put("title", title)
        put("album", album ?: "")
        put("duration", durationMs ?: 0L)
        put("lyrics", plainLyrics)
        put("syncPrecision", syncPrecision.name)
        put("instrumental", isInstrumental)
        put("timestamp", timestamp)
        val linesArray = JSONArray()
        lines.forEach { linesArray.put(it.toJson()) }
        put("timed_lyrics", linesArray)
    }

    companion object {
        fun fromJson(json: JSONObject): LyricsDocument {
            val lines = mutableListOf<LyricLine>()
            val linesArray = json.optJSONArray("timed_lyrics")
            if (linesArray != null) {
                for (i in 0 until linesArray.length()) {
                    val lObj = linesArray.optJSONObject(i)
                    if (lObj != null) lines.add(LyricLine.fromJson(lObj))
                }
            }

            val precisionStr = json.optString("syncPrecision", "NONE").uppercase()
            val precision = try {
                SyncPrecision.valueOf(precisionStr)
            } catch (_: Exception) {
                if (lines.isNotEmpty()) SyncPrecision.LINE else SyncPrecision.NONE
            }

            return LyricsDocument(
                provider = json.optString("source", json.optString("provider", "unknown")),
                artist = json.optString("artist", ""),
                title = json.optString("title", ""),
                album = json.optString("album", "").takeIf { it.isNotEmpty() },
                durationMs = json.optLong("duration", 0L).takeIf { it > 0L },
                plainLyrics = json.optString("lyrics", ""),
                lines = lines,
                syncPrecision = precision,
                isInstrumental = json.optBoolean("instrumental", false),
                timestamp = json.optString("timestamp", "")
            )
        }
    }
}

data class NormalizedTrack(
    val rawArtist: String,
    val normalizedArtist: String,
    val rawTitle: String,
    val normalizedTitle: String,
    val rawAlbum: String? = null,
    val durationMs: Long = 0L,
    val mediaId: String? = null,
    val packageName: String? = null,
    val confidenceScore: Double = 1.0
) {
    val stableKey: String
        get() {
            val durBucket = if (durationMs > 0) (durationMs / 5000) * 5000 else 0
            val raw = "$normalizedArtist:::$normalizedTitle:::$durBucket"
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    val isValidForLyrics: Boolean
        get() {
            val cleanTitle = normalizedTitle.trim()
            val cleanArtist = normalizedArtist.trim()
            if (cleanTitle.isEmpty() && cleanArtist.isEmpty()) return false
            if (cleanTitle.equals("unknown", ignoreCase = true) && cleanArtist.equals("unknown", ignoreCase = true)) return false
            return cleanTitle.length >= 2
        }
}

data class CacheEntry(
    val schemaVersion: Int = 1,
    val trackKey: String,
    val provider: String,
    val lyricsDocument: LyricsDocument,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val sourceQuality: Double = 1.0,
    val syncPrecision: SyncPrecision = SyncPrecision.NONE,
    val matchConfidence: Double = 1.0
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("trackKey", trackKey)
        put("provider", provider)
        put("lyricsDocument", lyricsDocument.toJson())
        put("fetchedAtMs", fetchedAtMs)
        put("expiresAtMs", expiresAtMs)
        put("sourceQuality", sourceQuality)
        put("syncPrecision", syncPrecision.name)
        put("matchConfidence", matchConfidence)
    }

    companion object {
        fun fromJson(json: JSONObject): CacheEntry? {
            val schema = json.optInt("schemaVersion", 1)
            if (schema != 1) return null // Schema migration point
            val docObj = json.optJSONObject("lyricsDocument") ?: return null
            val doc = LyricsDocument.fromJson(docObj)
            val precStr = json.optString("syncPrecision", doc.syncPrecision.name)
            val precision = try { SyncPrecision.valueOf(precStr) } catch (_: Exception) { doc.syncPrecision }

            return CacheEntry(
                schemaVersion = schema,
                trackKey = json.optString("trackKey", ""),
                provider = json.optString("provider", doc.provider),
                lyricsDocument = doc,
                fetchedAtMs = json.optLong("fetchedAtMs", System.currentTimeMillis()),
                expiresAtMs = json.optLong("expiresAtMs", System.currentTimeMillis() + (7 * 24 * 3600 * 1000L)),
                sourceQuality = json.optDouble("sourceQuality", 1.0),
                syncPrecision = precision,
                matchConfidence = json.optDouble("matchConfidence", 1.0)
            )
        }
    }
}

data class ProviderStats(
    val providerName: String,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var totalRequests: Int = 0,
    var ewmaLatencyMs: Double = 500.0,
    var lastRequestTimeMs: Long = 0L,
    var consecutiveFailures: Int = 0
) {
    val successRate: Double
        get() = if (totalRequests > 0) successCount.toDouble() / totalRequests else 1.0

    fun recordSuccess(latencyMs: Long) {
        totalRequests++
        successCount++
        consecutiveFailures = 0
        lastRequestTimeMs = System.currentTimeMillis()
        // EWMA with alpha = 0.2
        ewmaLatencyMs = (0.2 * latencyMs) + (0.8 * ewmaLatencyMs)
    }

    fun recordFailure() {
        totalRequests++
        failureCount++
        consecutiveFailures++
        lastRequestTimeMs = System.currentTimeMillis()
    }
}
