package security

/**
 * Credential IDs are the provider descriptor names passed to ExternalAiProviderPoolImpl.registerProvider.
 * Keep these stable: SecretManager stores and resolves secrets by exactly this `desc.name` value.
 */
object ProviderCredentialCatalog {
    const val GEMINI_FREE = "gemini_free"
    const val GROQ_FREE = "groq_free"

    val providerNames: List<String> = listOf(GEMINI_FREE, GROQ_FREE)

    fun contains(providerName: String): Boolean = providerName in providerNames
}

enum class ProviderKeyOperationResult {
    SAVED,
    REMOVED,
    AUTHENTICATION_REQUIRED,
    LOCKDOWN_ACTIVE,
    UNKNOWN_PROVIDER,
    EMPTY_KEY,
    STORAGE_FAILURE
}

/** Exact confirmation string required before entering the destructive Panic Reset request path. */
object PanicResetConfirmationPolicy {
    const val REQUIRED_PHRASE = "RESET AGENT"

    fun isConfirmed(input: String): Boolean = input == REQUIRED_PHRASE
}

sealed class PanicResetRequestResult {
    object AuthenticationRequired : PanicResetRequestResult()
    object ConfirmationRequired : PanicResetRequestResult()
    data class Completed(val epoch: Long) : PanicResetRequestResult()
    data class Incomplete(val failedStageIds: List<String>) : PanicResetRequestResult() {
        override fun toString(): String = "PanicResetRequestResult.Incomplete(failedStageIds=$failedStageIds)"
    }
}
