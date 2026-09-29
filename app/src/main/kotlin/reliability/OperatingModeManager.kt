package reliability

import contracts.InferenceProvider
import contracts.ProviderCapabilities
import contracts.ProviderType
import inference.HardwareDelegate
import inference.HardwareDelegateSelector

/**
 * GEN-MODE-1 — Agent Operating Modes
 * Central state machine governing system runtime constraints across all subsystems:
 * NORMAL | LOW_MEMORY | THERMAL | OFFLINE | SECURITY_LOCKDOWN | RECOVERY
 *
 * MANDATORY ARCHITECTURAL INTEGRATION:
 * 1. THERMAL Mode connects to External AI Provider Pool & GEN-EDGE-1:
 *    Prefers offloading inference to External AI Provider (to eliminate on-device heat generation).
 *    Only if no external provider is available/allowed does it fallback to local on-device CPU execution.
 * 2. LOW_MEMORY Mode connects to GEN-NEW-3 (Circuit Breaker):
 *    Enforces context admission reduction (50%) and memory eviction.
 * 3. SECURITY_LOCKDOWN Mode:
 *    Enforces read-only memory store, full network isolation, and disables Level 2/3 skills.
 *    Forward-dependency to Phase XIII (Security & Guard) is managed via SecurityLockdownEnforcer interface.
 * 4. Immutable Safety Guard Constraint:
 *    Mode switches and recovery MUST NOT touch or mutate GEN-NEW-GUARD,
 *    SkillRiskRegistry, or ActionRateLimiter.
 */
enum class OperatingMode {
    NORMAL,
    LOW_MEMORY,
    THERMAL,
    OFFLINE,
    SECURITY_LOCKDOWN,
    RECOVERY
}

enum class InferenceRoutingTarget {
    LOCAL_ON_DEVICE,
    EXTERNAL_AI_PROVIDER
}

data class ModeConstraints(
    val mode: OperatingMode,
    val preferredInferenceRouting: InferenceRoutingTarget,
    val hardwareDelegateOverride: HardwareDelegate?,
    val allowLevel2Actions: Boolean,
    val allowLevel3Actions: Boolean,
    val isMemoryReadOnly: Boolean,
    val allowExternalNetwork: Boolean,
    val contextTokenLimitRatio: Float // Multiplier on max context budget
)

/**
 * Interface representing external AI provider availability.
 * Connects GEN-MODE-1 with the External AI Provider Pool (Phase XVI / contracts.InferenceProvider).
 */
interface ExternalAiProviderPool {
    fun getAvailableProvider(): InferenceProvider?
}

/**
 * Phase XIII (GEN-SEC-1, GEN-SEC-2, GEN-SEC-3) Security Lockdown Enforcement Contract.
 * Enforces memory read-only state, tool risk-level restriction, and network isolation.
 * Bound to RealSecurityLockdownEnforcer from Phase XIII.
 */
interface SecurityLockdownEnforcer {
    fun enforceLockdown(enabled: Boolean)
    fun isMemoryReadOnly(): Boolean
    fun areMutatingToolsAllowed(): Boolean
    fun isExternalNetworkAllowed(): Boolean
}

class DefaultSecurityLockdownEnforcer : SecurityLockdownEnforcer {
    @Volatile
    private var lockdownActive: Boolean = false

    override fun enforceLockdown(enabled: Boolean) {
        lockdownActive = enabled
        runtime.AgentLogger.w(
            "SecurityLockdownEnforcer",
            "Security lockdown state set to: $enabled"
        )
    }

    override fun isMemoryReadOnly(): Boolean = lockdownActive
    override fun areMutatingToolsAllowed(): Boolean = !lockdownActive
    override fun isExternalNetworkAllowed(): Boolean = !lockdownActive
}

class AgentOperatingModeManager(
    private val delegateSelector: HardwareDelegateSelector = HardwareDelegateSelector(),
    private val externalProviderPool: ExternalAiProviderPool? = null,
    // Integrated with Phase XIII SecurityLockdownEnforcer
    private val securityLockdownEnforcer: SecurityLockdownEnforcer = DefaultSecurityLockdownEnforcer()
) {
    @Volatile
    private var currentMode: OperatingMode = OperatingMode.NORMAL
    private val transitionListeners = mutableListOf<(OperatingMode, OperatingMode) -> Unit>()

    fun getCurrentMode(): OperatingMode = currentMode

    fun getConstraints(): ModeConstraints {
        return when (currentMode) {
            OperatingMode.NORMAL -> ModeConstraints(
                mode = OperatingMode.NORMAL,
                preferredInferenceRouting = InferenceRoutingTarget.LOCAL_ON_DEVICE,
                hardwareDelegateOverride = null, // Follow standard delegate selection
                allowLevel2Actions = true,
                allowLevel3Actions = true,
                isMemoryReadOnly = false,
                allowExternalNetwork = true,
                contextTokenLimitRatio = 1.0f
            )

            OperatingMode.THERMAL -> ModeConstraints(
                mode = OperatingMode.THERMAL,
                // Architectural requirement: Prefer External AI Provider to eliminate on-device heat generation
                preferredInferenceRouting = InferenceRoutingTarget.EXTERNAL_AI_PROVIDER,
                // Fallback to local CPU only if no external provider is available
                hardwareDelegateOverride = HardwareDelegate.CPU,
                allowLevel2Actions = true,
                allowLevel3Actions = false, // Throttle heavy operations
                isMemoryReadOnly = false,
                allowExternalNetwork = true,
                contextTokenLimitRatio = 0.75f
            )

            OperatingMode.LOW_MEMORY -> ModeConstraints(
                mode = OperatingMode.LOW_MEMORY,
                preferredInferenceRouting = InferenceRoutingTarget.LOCAL_ON_DEVICE,
                hardwareDelegateOverride = HardwareDelegate.CPU, // Force CPU to release GPU memory allocations
                allowLevel2Actions = true,
                allowLevel3Actions = false,
                isMemoryReadOnly = false,
                allowExternalNetwork = true,
                contextTokenLimitRatio = 0.5f // Halve context tokens to relieve pressure
            )

            OperatingMode.OFFLINE -> ModeConstraints(
                mode = OperatingMode.OFFLINE,
                preferredInferenceRouting = InferenceRoutingTarget.LOCAL_ON_DEVICE,
                hardwareDelegateOverride = null,
                allowLevel2Actions = true,
                allowLevel3Actions = false,
                isMemoryReadOnly = false,
                allowExternalNetwork = false, // Block network
                contextTokenLimitRatio = 1.0f
            )

            OperatingMode.SECURITY_LOCKDOWN -> ModeConstraints(
                mode = OperatingMode.SECURITY_LOCKDOWN,
                preferredInferenceRouting = InferenceRoutingTarget.LOCAL_ON_DEVICE,
                hardwareDelegateOverride = HardwareDelegate.CPU,
                allowLevel2Actions = securityLockdownEnforcer.areMutatingToolsAllowed(), // false
                allowLevel3Actions = false,
                isMemoryReadOnly = securityLockdownEnforcer.isMemoryReadOnly(), // true
                allowExternalNetwork = securityLockdownEnforcer.isExternalNetworkAllowed(), // false
                contextTokenLimitRatio = 0.5f
            )

            OperatingMode.RECOVERY -> ModeConstraints(
                mode = OperatingMode.RECOVERY,
                preferredInferenceRouting = InferenceRoutingTarget.LOCAL_ON_DEVICE,
                hardwareDelegateOverride = HardwareDelegate.CPU,
                allowLevel2Actions = false,
                allowLevel3Actions = false,
                isMemoryReadOnly = false,
                allowExternalNetwork = false,
                contextTokenLimitRatio = 0.5f
            )
        }
    }

    @Synchronized
    fun transitionTo(newMode: OperatingMode, reason: String) {
        if (currentMode == newMode) return
        val previous = currentMode
        currentMode = newMode

        // Forward-dependency call to Phase XIII Security Lockdown
        if (newMode == OperatingMode.SECURITY_LOCKDOWN) {
            securityLockdownEnforcer.enforceLockdown(true)
        } else if (previous == OperatingMode.SECURITY_LOCKDOWN) {
            securityLockdownEnforcer.enforceLockdown(false)
        }

        runtime.AgentLogger.i("OperatingModeManager", "Transitioned mode: $previous -> $newMode (Reason: $reason)")
        for (listener in transitionListeners) {
            listener(previous, newMode)
        }
    }

    /**
     * Resolves the inference routing target and provider based on operating mode.
     * In THERMAL mode:
     * - First attempts to route to External AI Provider Pool if available and network allowed.
     * - If no external provider is available, falls back to local on-device CPU execution.
     */
    fun resolveInferenceExecutionPath(deviceFingerprint: String?): Pair<InferenceRoutingTarget, HardwareDelegate?> {
        val constraints = getConstraints()

        if (constraints.mode == OperatingMode.THERMAL) {
            val externalProvider = externalProviderPool?.getAvailableProvider()
            if (externalProvider != null && constraints.allowExternalNetwork) {
                // Successfully offload inference to external provider to prevent device overheating
                return Pair(InferenceRoutingTarget.EXTERNAL_AI_PROVIDER, null)
            }
            // Fallback: No external provider available, execute locally on CPU to minimize thermal load
            return Pair(InferenceRoutingTarget.LOCAL_ON_DEVICE, HardwareDelegate.CPU)
        }

        val effectiveDelegate = if (constraints.hardwareDelegateOverride != null) {
            constraints.hardwareDelegateOverride
        } else {
            delegateSelector.selectDelegate(deviceFingerprint)
        }

        return Pair(constraints.preferredInferenceRouting, effectiveDelegate)
    }

    /**
     * Resolves effective hardware delegate under current operating mode.
     */
    fun resolveEffectiveHardwareDelegate(deviceFingerprint: String?): HardwareDelegate {
        val (_, delegate) = resolveInferenceExecutionPath(deviceFingerprint)
        return delegate ?: HardwareDelegate.CPU
    }

    fun addTransitionListener(listener: (OperatingMode, OperatingMode) -> Unit) {
        transitionListeners.add(listener)
    }
}
