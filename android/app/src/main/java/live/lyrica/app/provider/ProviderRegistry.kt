package live.lyrica.app.provider

import live.lyrica.app.core.logging.LyricaLogger

/**
 * Global provider registry.
 * Providers register themselves here; the ProviderRouter discovers them via this registry.
 *
 * To add a new provider (community extension pattern):
 *   ProviderRegistry.register(MyCustomProvider())
 *
 * Built-in providers are registered in [ProviderRegistry.initDefaults].
 */
object ProviderRegistry {
    private val TAG = "ProviderRegistry"
    private val providers = mutableListOf<LyricsProvider>()

    /**
     * Register a provider. Duplicate IDs are replaced (last registration wins).
     */
    fun register(provider: LyricsProvider) {
        val existing = providers.indexOfFirst { it.id == provider.id }
        if (existing >= 0) {
            providers[existing] = provider
            LyricaLogger.d(TAG, "Re-registered provider: ${provider.displayName} (${provider.id})")
        } else {
            providers.add(provider)
            LyricaLogger.i(TAG, "Registered provider: ${provider.displayName} (${provider.id}, priority=${provider.priority})")
        }
    }

    /**
     * Unregister a provider by ID.
     */
    fun unregister(id: String) {
        providers.removeAll { it.id == id }
        LyricaLogger.i(TAG, "Unregistered provider: $id")
    }

    /**
     * Returns all registered providers that are currently available,
     * sorted by priority (ascending = lower number tried first in fallback).
     */
    fun getAvailable(): List<LyricsProvider> =
        providers.filter { it.isAvailable() }.sortedBy { it.priority }

    /**
     * Returns all registered providers regardless of availability.
     */
    fun getAll(): List<LyricsProvider> = providers.toList()

    fun isEmpty(): Boolean = providers.isEmpty()
}
