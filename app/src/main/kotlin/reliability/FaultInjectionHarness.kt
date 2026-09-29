package reliability

/**
 * GEN-448 & GEN-449 — Fault Injection & Chaos Testing Harness
 *
 * MANDATORY ARCHITECTURAL REQUIREMENT:
 * Fault injection must be guarded by a flag (BuildConfig / runtime harness flag)
 * and MUST ONLY be executable in test harness environments, NEVER in production flows.
 */
object FaultInjectionController {
    @Volatile
    var isFaultInjectionEnabled: Boolean = false

    fun reset() {
        isFaultInjectionEnabled = false
    }
}

enum class FaultType {
    INFERENCE_TIMEOUT,
    INFERENCE_CRASH,
    STORAGE_CORRUPTION,
    OUT_OF_MEMORY,
    THERMAL_SPIKE,
    NETWORK_DISCONNECT
}

class FaultInjectionHarness {

    fun injectFault(type: FaultType, modeManager: AgentOperatingModeManager? = null, sink: SliEventSink? = null) {
        check(FaultInjectionController.isFaultInjectionEnabled) {
            "Illegal invocation: Fault injection is DISABLED in this build configuration."
        }

        val now = System.currentTimeMillis()
        when (type) {
            FaultType.INFERENCE_TIMEOUT -> {
                sink?.recordEvent(SliEvent.TurnLatency(now, 35_000L)) // Exceeds 30s SLO
            }
            FaultType.INFERENCE_CRASH -> {
                sink?.recordEvent(SliEvent.TurnCompletion(now, isCrashFree = false))
            }
            FaultType.STORAGE_CORRUPTION -> {
                sink?.recordEvent(SliEvent.RollbackCount(now))
            }
            FaultType.OUT_OF_MEMORY -> {
                modeManager?.transitionTo(OperatingMode.LOW_MEMORY, "Fault injected: Out of memory")
            }
            FaultType.THERMAL_SPIKE -> {
                modeManager?.transitionTo(OperatingMode.THERMAL, "Fault injected: Thermal critical")
            }
            FaultType.NETWORK_DISCONNECT -> {
                modeManager?.transitionTo(OperatingMode.OFFLINE, "Fault injected: Network disconnected")
            }
        }
    }
}
