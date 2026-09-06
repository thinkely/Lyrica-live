package live.lyrica.app.core.providers

import live.lyrica.app.core.logging.LyricaLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit breaker preventing repeated pounding of failing lyrics providers.
 */
class ProviderCircuitBreaker {
    private val TAG = "CircuitBreaker"

    enum class State {
        CLOSED,     // Normal operation
        OPEN,       // Tripped: fail-fast during cooldown
        HALF_OPEN   // Probing single test request
    }

    private data class BreakerInfo(
        var state: State = State.CLOSED,
        var consecutiveFailures: Int = 0,
        var tripTimestampMs: Long = 0L,
        var cooldownDurationMs: Long = 60_000L // 60s default
    )

    private val breakers = ConcurrentHashMap<String, BreakerInfo>()
    private val FAILURE_THRESHOLD = 3

    fun canExecute(providerName: String): Boolean {
        val info = breakers.computeIfAbsent(providerName.lowercase()) { BreakerInfo() }
        val now = System.currentTimeMillis()

        synchronized(info) {
            when (info.state) {
                State.CLOSED -> return true
                State.OPEN -> {
                    if (now - info.tripTimestampMs >= info.cooldownDurationMs) {
                        info.state = State.HALF_OPEN
                        LyricaLogger.i(TAG, "Circuit for '$providerName' entered HALF_OPEN state")
                        return true
                    }
                    LyricaLogger.d(TAG, "Circuit for '$providerName' is OPEN (cooldown remaining: ${(info.cooldownDurationMs - (now - info.tripTimestampMs)) / 1000}s)")
                    return false
                }
                State.HALF_OPEN -> {
                    // Only allow one probe at a time
                    return true
                }
            }
        }
    }

    fun recordSuccess(providerName: String) {
        val info = breakers.computeIfAbsent(providerName.lowercase()) { BreakerInfo() }
        synchronized(info) {
            if (info.state != State.CLOSED) {
                LyricaLogger.i(TAG, "Circuit for '$providerName' recovered to CLOSED state")
            }
            info.state = State.CLOSED
            info.consecutiveFailures = 0
            info.cooldownDurationMs = 60_000L
        }
    }

    fun recordFailure(providerName: String) {
        val info = breakers.computeIfAbsent(providerName.lowercase()) { BreakerInfo() }
        synchronized(info) {
            info.consecutiveFailures++
            val now = System.currentTimeMillis()

            if (info.state == State.HALF_OPEN || info.consecutiveFailures >= FAILURE_THRESHOLD) {
                info.state = State.OPEN
                info.tripTimestampMs = now
                // Double cooldown up to 10 minutes
                info.cooldownDurationMs = minOf(info.cooldownDurationMs * 2, 600_000L)
                LyricaLogger.w(TAG, "Circuit for '$providerName' TRIPPED to OPEN (cooldown=${info.cooldownDurationMs / 1000}s, failures=${info.consecutiveFailures})")
            }
        }
    }

    fun getState(providerName: String): State {
        val info = breakers[providerName.lowercase()] ?: return State.CLOSED
        return info.state
    }
}
