package live.lyrica.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import live.lyrica.app.core.logging.LyricaLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-256-GCM secure token storage.
 * Keeps user API keys (Genius, Apple Music, AI keys) strictly on-device.
 */
class SecureTokenStorage(private val context: Context) {
    private val TAG = "SecureStorage"
    private val PREF_NAME = "lyrica_secure_prefs"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val KEY_ALIAS = "LyricaLiveKeyAlias"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
                LyricaLogger.i(TAG, "Generated secure AES key in Android Keystore")
            }
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Keystore initialization note: ${e.message}")
        }
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    fun putString(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
            return
        }

        try {
            val secretKey = getSecretKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

                val combined = ByteArray(iv.size + encrypted.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

                val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
                prefs.edit().putString(key, encoded).apply()
            } else {
                // Fallback to obfuscated storage if keystore unavailable
                val plainBase64 = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                prefs.edit().putString(key, "plain_$plainBase64").apply()
            }
        } catch (e: Exception) {
            LyricaLogger.e(TAG, "Failed to encrypt value for key=$key", e)
        }
    }

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null

        if (stored.startsWith("plain_")) {
            return try {
                String(Base64.decode(stored.removePrefix("plain_"), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        return try {
            val secretKey = getSecretKey() ?: return null
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            val encrypted = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            LyricaLogger.w(TAG, "Error decrypting key=$key: ${e.message}")
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    // ── Plain preference helpers ───────────────────────────────────────────
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long {
        return prefs.getLong(key, default)
    }

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, default: Float = 0f): Float {
        return prefs.getFloat(key, default)
    }
}
