package security

/**
 * GEN-551 — Security Architecture & Threat Model Definitions
 * GEN-552 — Threat Model Table
 * GEN-553 — Simplified Security Policy
 *
 * Locked Architectural Invariants:
 * 1. Physical Access: Protected by GEN-SEC-2 App-Level Lock and GEN-SEC-3 Panic Reset.
 * 2. Indirect Prompt Injection: Mitigated by GEN-SEC-1 strict delimitation and instruction framing.
 * 3. Secret Management: Android Keystore AES-256 GCM backed; never hardcoded in source or plaintext files (GEN-579/580).
 * 4. Immutable Safety Guard Constraint: Security operations and Panic Reset NEVER touch or reset
 *    GEN-NEW-GUARD, SkillRiskRegistry, or ActionRateLimiter.
 */
enum class ThreatVector {
    PHYSICAL_DEVICE_ACCESS,
    INDIRECT_PROMPT_INJECTION,
    APK_REVERSE_ENGINEERING
}

data class ThreatModelEntry(
    val vector: ThreatVector,
    val likelihood: String,
    val severity: String,
    val mitigations: List<String>
)

object ThreatModel {
    val entries = listOf(
        ThreatModelEntry(
            vector = ThreatVector.PHYSICAL_DEVICE_ACCESS,
            likelihood = "Medium",
            severity = "High",
            mitigations = listOf(
                "App-level PIN/Biometric lock (GEN-SEC-2)",
                "Hardware-backed AES-256 GCM encrypted memory storage (GEN-578 / GEN-MEM-6)",
                "Panic Reset destruction of keys & sensitive active workspace (GEN-SEC-3)"
            )
        ),
        ThreatModelEntry(
            vector = ThreatVector.INDIRECT_PROMPT_INJECTION,
            likelihood = "Increasing with skills",
            severity = "High",
            mitigations = listOf(
                "Strict <untrusted_content> boundary framing (GEN-SEC-1)",
                "System instruction forbidding command execution from untrusted content (GEN-SEC-1)",
                "Independent Safety Guard validation for all actions (GEN-NEW-GUARD)"
            )
        ),
        ThreatModelEntry(
            vector = ThreatVector.APK_REVERSE_ENGINEERING,
            likelihood = "Low",
            severity = "Medium",
            mitigations = listOf(
                "Zero hardcoded secrets; user-supplied API keys stored exclusively in Keystore (GEN-579/580)",
                "Isolated credentials per external provider"
            )
        )
    )
}
