package multiagent

import contracts.InferenceError
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import contracts.TokenUsage
import security.SecretManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * GEN-MA-6 — External AI Provider Pool
 *
 * Requirements:
 * 1. Pools external official free-tier providers (Gemini, Groq, OpenRouter, etc.).
 * 2. Implements InferenceProvider and ExternalAiProviderPool (integrated with GEN-MODE-1/THERMAL).
 * 3. Enforces provider credential isolation via GEN-579/580 (SecretManager).
 * 4. Circuit Breaker per provider:
 *    - CLOSED (Healthy)
 *    - OPEN (Backoff / Quota reached)
 *    - HALF_OPEN (Probe with small test request)
 * 5. Backoff strategy:
 *    - 429 with Retry-After: wait exact duration.
 *    - 429 without Retry-After: exponential backoff.
 *    - QuotaExceeded: disabled until daily quota reset.
 *    - 401/403 (AuthFailed): immediate failure notification, never retried.
 */
enum class ProviderCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

data class ExternalProviderDescriptor(
    val name: String,
    val endpoint: String,
    val freeQuotaPerDay: Int,
    var currentUsage: Int = 0,
    var circuitState: ProviderCircuitState = ProviderCircuitState.CLOSED,
    var nextAllowedAttemptMs: Long = 0L,
    var consecutiveFailures: Int = 0
)

class ExternalAiProviderPoolImpl(
    private val secretManager: SecretManager,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : reliability.ExternalAiProviderPool {

    private val providers = ConcurrentHashMap<String, ExternalProviderDescriptor>()
    private val providerInstances = ConcurrentHashMap<String, InferenceProvider>()

    fun registerProvider(
        descriptor: ExternalProviderDescriptor,
        providerInstance: InferenceProvider
    ) {
        providers[descriptor.name] = descriptor
        providerInstances[descriptor.name] = providerInstance
    }

    override fun getAvailableProvider(): InferenceProvider? {
        val now = clock()
        for (desc in providers.values) {
            // Check credentials exist in isolated Secret Vault (GEN-579/580)
            val secretKey = secretManager.getSecret(desc.name)
            if (secretKey.isNullOrBlank()) {
                continue
            }

            // Check quota and circuit state
            if (desc.currentUsage >= desc.freeQuotaPerDay) {
                continue
            }

            when (desc.circuitState) {
                ProviderCircuitState.CLOSED -> {
                    return providerInstances[desc.name]
                }
                ProviderCircuitState.OPEN -> {
                    if (now >= desc.nextAllowedAttemptMs) {
                        desc.circuitState = ProviderCircuitState.HALF_OPEN
                        return providerInstances[desc.name]
                    }
                }
                ProviderCircuitState.HALF_OPEN -> {
                    return providerInstances[desc.name]
                }
            }
        }
        return null
    }

    fun recordOutcome(
        providerName: String,
        success: Boolean,
        error: InferenceError? = null,
        retryAfterMs: Long? = null
    ) {
        val desc = providers[providerName] ?: return
        val now = clock()

        if (success) {
            desc.circuitState = ProviderCircuitState.CLOSED
            desc.consecutiveFailures = 0
            desc.currentUsage++
        } else {
            desc.consecutiveFailures++
            when (error) {
                is InferenceError.AuthFailed -> {
                    // 401/403: Never retry. Open circuit indefinitely
                    desc.circuitState = ProviderCircuitState.OPEN
                    desc.nextAllowedAttemptMs = Long.MAX_VALUE
                    runtime.AgentLogger.e("ProviderPool", "Auth failed for ${desc.name}: disabled permanently until key update.")
                }
                is InferenceError.RateLimited -> {
                    desc.circuitState = ProviderCircuitState.OPEN
                    val backoff = retryAfterMs ?: min(2000L * (1 shl desc.consecutiveFailures), 60_000L)
                    desc.nextAllowedAttemptMs = now + backoff
                }
                is InferenceError.QuotaExceeded -> {
                    desc.circuitState = ProviderCircuitState.OPEN
                    desc.nextAllowedAttemptMs = now + (retryAfterMs ?: (24L * 3600 * 1000))
                }
                else -> {
                    // 5xx / Timeout: Exponential backoff
                    desc.circuitState = ProviderCircuitState.OPEN
                    val backoff = min(1000L * (1 shl desc.consecutiveFailures), 30_000L)
                    desc.nextAllowedAttemptMs = now + backoff
                }
            }
        }
    }

    fun getDescriptor(name: String): ExternalProviderDescriptor? = providers[name]
}
