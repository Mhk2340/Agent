package reasoning

import inference.ContextWindowBudgetPolicy
import memory.LongTermMemoryEntry

/**
 * Interface representing the Retrieval & Packing Pipeline (GEN-MEM-4).
 * Implemented by ContextRetrievalPipeline in Phase V and consumed by GEN-244 ContextManager.
 */
interface ContextRetrievalSource {
    fun retrieveRelevantContext(query: String, maxTokens: Int): List<LongTermMemoryEntry>
}

/**
 * GEN-244 — Context Manager (also absorbs GEN-245 Attention Manager)
 * Assembles user prompt, session memory history, and retrieved long-term memory
 * into a single unified context within the 4096 token context window (GEN-285).
 */
class ContextManager(
    private val budgetPolicy: ContextWindowBudgetPolicy = ContextWindowBudgetPolicy(),
    private val retrievalSource: ContextRetrievalSource
) {
    data class AssembledContext(
        val fullPrompt: String,
        val contextTokensEstimate: Int,
        val retrievedEntriesCount: Int
    )

    fun assembleContext(
        userMessage: String,
        sessionHistory: List<String> = emptyList(),
        maxOutputTokensTarget: Int = 512,
        externalUntrustedContents: List<Pair<String, String>> = emptyList() // Pair(source, content)
    ): AssembledContext {
        val usableTokens = budgetPolicy.calculateUsableContextTokens(maxOutputTokensTarget)
        val sb = StringBuilder()

        // 1. Core Instruction / Framing
        sb.append("System: You are an autonomous Android Agent running locally.\n")
        // GEN-SEC-1: Invariant defense instruction against prompt injection
        sb.append(security.PromptInjectionMitigator.INJECTION_DEFENSE_INSTRUCTION).append("\n")

        // 2. Untrusted External Data Boundary (GEN-SEC-1)
        if (externalUntrustedContents.isNotEmpty()) {
            sb.append("\n[External Data Input]:\n")
            for ((source, rawContent) in externalUntrustedContents) {
                sb.append(security.PromptInjectionMitigator.wrapUntrustedContent(rawContent, source)).append("\n")
            }
        }

        // 3. Retrieved Memory Context (from GEN-MEM-4 pipeline)
        val retrieved = retrievalSource.retrieveRelevantContext(userMessage, usableTokens / 2)
        if (retrieved.isNotEmpty()) {
            sb.append("\n[Relevant Long-Term Memories]:\n")
            for (entry in retrieved) {
                sb.append("- (${entry.memoryType}): ").append(entry.content).append("\n")
            }
        }

        // 4. Session Conversation History
        if (sessionHistory.isNotEmpty()) {
            sb.append("\n[Conversation History]:\n")
            for (msg in sessionHistory) {
                sb.append(msg).append("\n")
            }
        }

        // 5. Current User Turn
        sb.append("\nUser: ").append(userMessage)

        val fullText = sb.toString()
        val estimatedTokens = fullText.length // Approximate 1 token ~= 1 char in local tests

        return AssembledContext(
            fullPrompt = fullText,
            contextTokensEstimate = estimatedTokens,
            retrievedEntriesCount = retrieved.size
        )
    }
}
