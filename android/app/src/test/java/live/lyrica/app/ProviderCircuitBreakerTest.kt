package live.lyrica.app

import live.lyrica.app.core.providers.ProviderCircuitBreaker
import org.junit.Assert.*
import org.junit.Test

class ProviderCircuitBreakerTest {

    @Test
    fun testCircuitBreakerStateTransitions() {
        val breaker = ProviderCircuitBreaker()
        val provider = "lrclib"

        // Initial state is CLOSED
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))

        // Record 2 failures -> still CLOSED
        breaker.recordFailure(provider)
        breaker.recordFailure(provider)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))

        // 3rd failure -> TRIPS to OPEN
        breaker.recordFailure(provider)
        assertEquals(ProviderCircuitBreaker.State.OPEN, breaker.getState(provider))
        assertFalse(breaker.canExecute(provider))

        // Recover on success -> CLOSED
        breaker.recordSuccess(provider)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, breaker.getState(provider))
        assertTrue(breaker.canExecute(provider))
    }
}
