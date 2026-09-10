package live.lyrica.app.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.core.model.TrackQuery
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Resolves high-definition album art for any track.
 *
 * Tiered Resolution Strategy:
 *  1. Local MediaMetadata Embedded Bitmap
 *  2. Local MediaMetadata Art URI
 *  3. iTunes Search API (1000x1000 High-Res Artwork)
 *  4. Deezer API (1000x1000 cover_xl Artwork)
 *  5. MusicBrainz + Cover Art Archive (500x500 front cover)
 *
 * Caching:
 *  - High-performance in-memory LruCache (50 entries)
 *  - Persistent disk cache in cache/cover_art/*.jpg
 */
class HdCoverArtResolver(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val TAG = "HdCoverArtResolver"

    private val memCache = LruCache<String, Bitmap>(50)
    private val diskCacheDir by lazy {
        File(context.cacheDir, "cover_art").apply { mkdirs() }
    }

    suspend fun resolveArtwork(
        query: TrackQuery,
        metadata: MediaMetadata?
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = sha256("${query.artist.trim().lowercase()}:::${query.title.trim().lowercase()}")

        // 1. Check memory cache
        memCache.get(cacheKey)?.let { return@withContext it }

        // 2. Check disk cache
        val diskFile = File(diskCacheDir, "$cacheKey.jpg")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bmp != null) {
                    memCache.put(cacheKey, bmp)
                    return@withContext bmp
                }
            } catch (e: Exception) {
                LyricaLogger.w(TAG, "Disk art decode failed: ${e.message}")
            }
        }

        // 3. Check MediaMetadata embedded bitmap
        metadata?.let { meta ->
            val embedded = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (embedded != null) {
                saveToDiskAndMem(cacheKey, embedded, diskFile)
                return@withContext embedded
            }

            // Check URI
            val artUriStr = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
            if (!artUriStr.isNullOrBlank()) {
                try {
                    val uri = Uri.parse(artUriStr)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            saveToDiskAndMem(cacheKey, bmp, diskFile)
                            return@withContext bmp
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (!query.isSearchable) return@withContext null

        // 4. Fetch HD Artwork from iTunes Search API
        val itunesBmp = fetchFromItunes(query)
        if (itunesBmp != null) {
            saveToDiskAndMem(cacheKey, itunesBmp, diskFile)
            return@withContext itunesBmp
        }

        // 5. Fetch HD Artwork from Deezer API
        val deezerBmp = fetchFromDeezer(query)
        if (deezerBmp != null) {
            saveToDiskAndMem(cacheKey, deezerBmp, diskFile)
            return@withContext deezerBmp
        }

        // 6. Fetch from MusicBrainz & Cover Art Archive
        val mbBmp = fetchFromMusicBrainz(query)
        if (mbBmp != null) {
            saveToDiskAndMem(cacheKey, mbBmp, diskFile)
            return@withContext mbBmp
        }

        null
    }

    private fun fetchFromItunes(query: TrackQuery): Bitmap? {
        return try {
            val term = URLEncoder.encode("${query.artist} ${query.title}", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$term&entity=song&limit=1"
            val req = Request.Builder().url(url).header("User-Agent", "LyricaLive/2.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val item = results.getJSONObject(0)
                var artUrl = item.optString("artworkUrl100")
                if (artUrl.isNotBlank()) {
                    // Upgrade 100x100 to 1000x1000 for HD
                    artUrl = artUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
                    downloadBitmap(artUrl)
                } else null
            }
        } catch (e: Exception) {
            LyricaLogger.d(TAG, "iTunes art fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchFromDeezer(query: TrackQuery): Bitmap? {
        return try {
            val q = URLEncoder.encode("${query.artist} ${query.title}", "UTF-8")
            val url = "https://api.deezer.com/search?q=$q&limit=1"
            val req = Request.Builder().url(url).header("User-Agent", "LyricaLive/2.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return null
                if (data.length() == 0) return null
                val albumObj = data.getJSONObject(0).optJSONObject("album") ?: return null
                val artUrl = albumObj.optString("cover_xl").ifBlank {
                    albumObj.optString("cover_big").ifBlank { albumObj.optString("cover_medium") }
                }
                if (artUrl.isNotBlank()) downloadBitmap(artUrl) else null
            }
        } catch (e: Exception) {
            LyricaLogger.d(TAG, "Deezer art fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchFromMusicBrainz(query: TrackQuery): Bitmap? {
        return try {
            val encodedQuery = URLEncoder.encode("recording:${query.title} AND artist:${query.artist}", "UTF-8")
            val mbUrl = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=1"
            val req = Request.Builder().url(mbUrl).header("User-Agent", "LyricaLive/2.0 ( contact@lyrica.live )").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val recs = json.optJSONArray("recordings") ?: return null
                if (recs.length() == 0) return null
                val releases = recs.getJSONObject(0).optJSONArray("releases") ?: return null
                if (releases.length() == 0) return null
                val mbid = releases.getJSONObject(0).optString("id")
                if (mbid.isNotBlank()) {
                    val caaUrl = "https://coverartarchive.org/release/$mbid/front-500"
                    downloadBitmap(caaUrl)
                } else null
            }
        } catch (e: Exception) {
            LyricaLogger.d(TAG, "MusicBrainz art fetch failed: ${e.message}")
            null
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.byteStream()?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            LyricaLogger.d(TAG, "Bitmap download failed from $url: ${e.message}")
            null
        }
    }

    private fun saveToDiskAndMem(key: String, bmp: Bitmap, file: File) {
        memCache.put(key, bmp)
        try {
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Failed to cache artwork to disk: ${e.message}")
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
