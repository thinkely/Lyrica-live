package live.lyrica.app.core.providers

import live.lyrica.app.core.logging.LyricaLogger
import live.lyrica.app.security.SecureTokenStorage

/**
 * Manages Apple Music authentication tokens separating Developer Token from User Token.
 */
class AppleMusicAuthManager(private val secureStorage: SecureTokenStorage) {
    private val TAG = "AppleMusicAuth"

    private val KEY_DEVELOPER_TOKEN = "apple_developer_token"
    private val KEY_USER_TOKEN = "apple_user_token"
    private val KEY_STOREFRONT = "apple_storefront"

    fun getDeveloperToken(): String? {
        return secureStorage.getString(KEY_DEVELOPER_TOKEN)
    }

    fun setDeveloperToken(token: String?) {
        secureStorage.putString(KEY_DEVELOPER_TOKEN, token)
        LyricaLogger.i(TAG, "Apple Developer Token updated")
    }

    fun getUserToken(): String? {
        return secureStorage.getString(KEY_USER_TOKEN)
    }

    fun setUserToken(token: String?) {
        secureStorage.putString(KEY_USER_TOKEN, token)
        LyricaLogger.i(TAG, "Apple Music User Token updated")
    }

    fun getStorefront(): String {
        return secureStorage.getString(KEY_STOREFRONT) ?: "us"
    }

    fun setStorefront(storefront: String) {
        secureStorage.putString(KEY_STOREFRONT, storefront.trim().lowercase())
    }

    val isConfigured: Boolean
        get() = !getDeveloperToken().isNullOrBlank()
}
