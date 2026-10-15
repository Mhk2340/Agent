package security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import memory.AesGcmCipher
import memory.MemoryCallerRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import multiagent.ExternalAiProviderPoolImpl
import multiagent.ExternalProviderDescriptor
import persistence.AgentDatabase
import skills.ExecutionStatus
import skills.SkillExecutionRequest
import testutil.RoomTestEnvironment
import testutil.TestOnlyIntegrityMacKeyProvider
import testutil.TestOnlyKeystoreKeyProvider
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentContainerAppLockIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var keyProvider: TestOnlyKeystoreKeyProvider
    private lateinit var integrityKeys: TestOnlyIntegrityMacKeyProvider
    private lateinit var appLockPreferencesName: String
    private lateinit var appLockIntegrityAlias: String
    private lateinit var epochPreferencesName: String
    private lateinit var epochIntegrityAlias: String
    private lateinit var lockdownPreferencesName: String
    private lateinit var lockdownIntegrityAlias: String
    private lateinit var epochStore: AndroidSecurityEpochStore
    private lateinit var lockdownStore: AndroidPanicResetLockdownStore
    private lateinit var container: runtime.di.AgentContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyProvider = TestOnlyKeystoreKeyProvider()
        integrityKeys = TestOnlyIntegrityMacKeyProvider()
        val suffix = UUID.randomUUID().toString()
        appLockPreferencesName = "container-app-lock-$suffix"
        appLockIntegrityAlias = "container-app-lock-hmac-$suffix"
        epochPreferencesName = "container-epoch-$suffix"
        epochIntegrityAlias = "container-epoch-hmac-$suffix"
        lockdownPreferencesName = "container-lockdown-$suffix"
        lockdownIntegrityAlias = "container-lockdown-hmac-$suffix"
        database = RoomTestEnvironment.inMemoryDatabase(
            context,
            AesGcmCipher(keyProvider, AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        )
        epochStore = AndroidSecurityEpochStore(
            context,
            integrityKeys,
            epochPreferencesName,
            epochIntegrityAlias
        )
        lockdownStore = AndroidPanicResetLockdownStore(
            context,
            integrityKeys,
            lockdownPreferencesName,
            lockdownIntegrityAlias
        )
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE).edit().clear().commit()
        container = newProductionContainer()
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.getSharedPreferences(appLockPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(epochPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE).edit().clear().commit()
        keyProvider.clearAllKeysForTest()
        integrityKeys.clearForTest()
    }

    @Test
    fun pinHashAndPersistentBackoffSurviveProductionContainerRecreationWithoutDeletingMemory() {
        val pin = "731946"
        configurePin(container, pin)
        container.memoryManager.saveLongTermMemory(
            memoryType = memory.MemoryType.SEMANTIC,
            content = "memory must remain after PIN failures",
            embedding = null,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        container.appLockManager.lock()

        repeat(4) {
            assertEquals(
                AppLockOperationResult.IncorrectPin,
                container.appLockManager.unlockWithPin("000000".toCharArray())
            )
        }
        assertTrue(container.appLockManager.unlockWithPin("000000".toCharArray()) is AppLockOperationResult.LockedOut)
        val stored = appLockStore().read() as AppLockStoreRead.Configured
        assertEquals(5, stored.state.failedAttempts)
        assertTrue(stored.state.lockedUntilEpochMs > System.currentTimeMillis())
        assertFalse(stored.state.toString().contains(pin))
        assertFalse(container.appLockManager.toString().contains(pin))
        assertEquals(1, database.longTermMemoryDao().count())

        val restartedContainer = newProductionContainer()
        assertEquals(AppLockStatus.LOCKED_OUT, restartedContainer.appLockManager.status())
        assertEquals(5, (appLockStore().read() as AppLockStoreRead.Configured).state.failedAttempts)
        val correctPinAttempt = pin.toCharArray()
        assertTrue(restartedContainer.appLockManager.unlockWithPin(correctPinAttempt) is AppLockOperationResult.LockedOut)
        assertTrue(correctPinAttempt.all { it == '\u0000' })
        assertEquals(1, database.longTermMemoryDao().count())
    }

    @Test
    fun productionContainerGatesMemorySkillsAndProviderKeysAndNeverReturnsCredentialValues() {
        configurePin(container)
        val geminiKey = "GEMINI-API-KEY-NOT-FOR-UI-OR-AUDIT"
        val groqKey = "GROQ-API-KEY-NOT-FOR-UI-OR-AUDIT"
        val geminiInput = geminiKey.toCharArray()
        val groqInput = groqKey.toCharArray()
        val securityViewModel = SecurityViewModel(container.appLockManager, container)
        assertTrue(
            securityViewModel.saveProviderKey(ProviderCredentialCatalog.GEMINI_FREE, geminiInput)
                is SecurityUiState.Settings
        )
        assertEquals(
            ProviderKeyOperationResult.SAVED,
            container.storeProviderCredential(ProviderCredentialCatalog.GROQ_FREE, groqInput)
        )
        assertTrue(geminiInput.all { it == '\u0000' })
        assertTrue(groqInput.all { it == '\u0000' })
        assertEquals(
            setOf(ProviderCredentialCatalog.GEMINI_FREE, ProviderCredentialCatalog.GROQ_FREE),
            container.configuredProviderCredentialIds()
        )
        assertEquals(geminiKey, container.secretManager.getSecret(ProviderCredentialCatalog.GEMINI_FREE))
        assertEquals(groqKey, container.secretManager.getSecret(ProviderCredentialCatalog.GROQ_FREE))

        val uiState = SecurityViewModel(container.appLockManager, container).openSettings()
        assertTrue(uiState is SecurityUiState.Settings)
        assertFalse(uiState.toString().contains(geminiKey))
        assertFalse(uiState.toString().contains(groqKey))
        val auditTextBeforeLock = container.actionAuditLog.getAllRecords().joinToString()
        assertFalse(auditTextBeforeLock.contains(geminiKey))
        assertFalse(auditTextBeforeLock.contains(groqKey))

        assertEquals(
            ProviderKeyOperationResult.REMOVED,
            container.removeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE)
        )
        assertEquals(
            setOf(ProviderCredentialCatalog.GROQ_FREE),
            container.configuredProviderCredentialIds()
        )
        assertNull(container.secretManager.getSecret(ProviderCredentialCatalog.GEMINI_FREE))
        assertEquals(groqKey, container.secretManager.getSecret(ProviderCredentialCatalog.GROQ_FREE))

        container.memoryManager.appendSessionMessage("user", "protected session", MemoryCallerRole.SYSTEM_CORE)
        container.appLockManager.lock()
        assertThrows(SecurityException::class.java) {
            container.memoryManager.getSessionMessages(MemoryCallerRole.SYSTEM_CORE)
        }
        assertThrows(SecurityException::class.java) {
            container.memoryManager.getTotalCount(MemoryCallerRole.SYSTEM_CORE)
        }
        assertThrows(SecurityException::class.java) {
            container.memoryManager.appendSessionMessage("user", "must not be written", MemoryCallerRole.SYSTEM_CORE)
        }

        val callCount = intArrayOf(0)
        container.skillExecutionEngine.registerHandler("protected-test-action") {
            callCount[0]++
            "must not execute"
        }
        val skillResult = container.skillExecutionEngine.executeSkill(
            SkillExecutionRequest(
                actionId = "locked-app-action",
                skillName = "protected-test-action",
                params = emptyMap()
            )
        )
        assertEquals(ExecutionStatus.FAILED, skillResult.status)
        assertTrue(skillResult.failureReason.orEmpty().contains("App Lock"))
        assertEquals(0, callCount[0])

        val blockedKey = "BLOCKED-API-KEY-SENTINEL".toCharArray()
        assertEquals(
            ProviderKeyOperationResult.AUTHENTICATION_REQUIRED,
            container.storeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE, blockedKey)
        )
        assertTrue(blockedKey.all { it == '\u0000' })
        assertEquals(
            ProviderKeyOperationResult.AUTHENTICATION_REQUIRED,
            container.removeProviderCredential(ProviderCredentialCatalog.GROQ_FREE)
        )
        assertTrue(container.configuredProviderCredentialIds().isEmpty())
        assertEquals(groqKey, container.secretManager.getSecret(ProviderCredentialCatalog.GROQ_FREE))
    }

    @Test
    fun providerCredentialIdsMatchDescriptorNamesConsumedByProviderPool() {
        configurePin(container)
        val geminiDescriptor = ExternalProviderDescriptor("gemini_free", "https://api.gemini.mock", 50)
        val groqDescriptor = ExternalProviderDescriptor("groq_free", "https://api.groq.mock", 100)
        assertEquals(
            setOf(geminiDescriptor.name, groqDescriptor.name),
            ProviderCredentialCatalog.providerNames.toSet()
        )

        val geminiKey = "POOL-GEMINI-CREDENTIAL-SENTINEL"
        val groqKey = "POOL-GROQ-CREDENTIAL-SENTINEL"
        val geminiInput = geminiKey.toCharArray()
        val groqInput = groqKey.toCharArray()
        assertEquals(
            ProviderKeyOperationResult.SAVED,
            container.storeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE, geminiInput)
        )
        assertEquals(
            ProviderKeyOperationResult.SAVED,
            container.storeProviderCredential(ProviderCredentialCatalog.GROQ_FREE, groqInput)
        )
        assertTrue(geminiInput.all { it == '\u0000' })
        assertTrue(groqInput.all { it == '\u0000' })

        val geminiProvider = unusedExternalProvider()
        val groqProvider = unusedExternalProvider()
        val geminiPool = ExternalAiProviderPoolImpl(container.secretManager).apply {
            registerProvider(geminiDescriptor, geminiProvider)
        }
        val groqPool = ExternalAiProviderPoolImpl(container.secretManager).apply {
            registerProvider(groqDescriptor, groqProvider)
        }
        assertSame(geminiProvider, geminiPool.getAvailableProvider())
        assertSame(groqProvider, groqPool.getAvailableProvider())

        assertEquals(
            ProviderKeyOperationResult.REMOVED,
            container.removeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE)
        )
        assertNull(geminiPool.getAvailableProvider())
        assertSame(groqProvider, groqPool.getAvailableProvider())
        assertFalse(geminiPool.toString().contains(geminiKey))
        assertFalse(groqPool.toString().contains(groqKey))
    }

    @Test
    fun panicResetRequiresExactConfirmationPreservesPinAndLeavesPersistentLockdown() {
        val pin = "482951"
        configurePin(container, pin)
        container.memoryManager.saveLongTermMemory(
            memoryType = memory.MemoryType.SEMANTIC,
            content = "pre-reset memory",
            embedding = null,
            callerRole = MemoryCallerRole.SYSTEM_CORE
        )
        container.secretManager.storeSecret(ProviderCredentialCatalog.GEMINI_FREE, "RESET-API-KEY-SENTINEL")

        assertEquals(
            PanicResetRequestResult.ConfirmationRequired,
            container.requestPanicReset("reset agent")
        )
        assertEquals(1, database.longTermMemoryDao().count())
        assertEquals(1, container.secretManager.listSecretAliases().size)
        assertFalse(lockdownStore.isLocked())

        val result = container.requestPanicReset(PanicResetConfirmationPolicy.REQUIRED_PHRASE)
        assertTrue(result is PanicResetRequestResult.Completed)
        assertTrue(lockdownStore.isLocked())
        assertEquals(0, database.longTermMemoryDao().count())
        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        assertEquals(AppLockStatus.LOCKED, container.appLockManager.status())
        assertEquals(1, container.actionAuditLog.getPanicResetGenesisRecords().size)

        val unlockPin = pin.toCharArray()
        assertEquals(AppLockOperationResult.Success, container.appLockManager.unlockWithPin(unlockPin))
        assertTrue(unlockPin.all { it == '\u0000' })
        assertEquals(LockdownCondition.PANIC_RESET_LATCHED, container.lockdownCondition())
    }

    @Test
    fun lockdownReleaseRequiresAppLockAndExactConfirmationThenClearsProviderKeysAndAuditsSafely() {
        val pin = "394817"
        configurePin(container, pin)
        val apiKey = "RECOVERY-API-KEY-SENTINEL"
        container.storeProviderCredential(ProviderCredentialCatalog.GROQ_FREE, apiKey.toCharArray())
        lockdownStore.latch()
        container.appLockManager.lock()

        assertEquals(
            LockdownReleaseResult.AuthenticationRequired,
            container.releaseSecurityLockdown(LockdownReleaseCoordinator.RELEASE_LOCKDOWN_CONFIRMATION)
        )
        assertTrue(lockdownStore.isLocked())
        assertEquals(apiKey, container.secretManager.getSecret(ProviderCredentialCatalog.GROQ_FREE))

        assertEquals(
            AppLockOperationResult.Success,
            container.appLockManager.unlockWithPin(pin.toCharArray())
        )
        assertEquals(
            LockdownReleaseResult.ConfirmationRequired,
            container.releaseSecurityLockdown("release security lockdown")
        )
        assertTrue(lockdownStore.isLocked())

        val result = container.releaseSecurityLockdown(LockdownReleaseCoordinator.RELEASE_LOCKDOWN_CONFIRMATION)
        assertTrue(result is LockdownReleaseResult.Released)
        assertFalse(lockdownStore.isLocked())
        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        val records = container.actionAuditLog.getAllRecords()
        assertEquals(1, records.size)
        assertEquals("lockdown_release_authorized", records.single().skillName)
        val auditText = records.joinToString()
        assertFalse(auditText.contains(apiKey))
        assertFalse(auditText.contains(pin))
        assertFalse(auditText.contains(LockdownReleaseCoordinator.RELEASE_LOCKDOWN_CONFIRMATION))
    }

    @Test
    fun failedHalfwayReleaseLeavesDurablePendingMarkerAndLockdownActive() {
        configurePin(container)
        container.storeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE, "API-KEY-TO-CLEAR".toCharArray())
        lockdownStore.latch()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_release_audit BEFORE INSERT ON action_audit_log " +
                "WHEN NEW.skill_name = 'lockdown_release_authorized' " +
                "BEGIN SELECT RAISE(ABORT, 'RELEASE-AUDIT-FAILURE-SENTINEL'); END"
        )

        val result = container.releaseSecurityLockdown(LockdownReleaseCoordinator.RELEASE_LOCKDOWN_CONFIRMATION)
        assertEquals(LockdownReleaseResult.Failed("release-audit"), result)
        assertTrue(lockdownStore.isLocked())
        assertTrue(
            context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE)
                .getBoolean("release_pending", false)
        )
        val afterRestart = AndroidPanicResetLockdownStore(
            context,
            integrityKeys,
            lockdownPreferencesName,
            lockdownIntegrityAlias
        )
        assertTrue(afterRestart.isLocked())
        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        assertFalse(container.appLockManager.isAppUnlocked())
        assertEquals(LockdownCondition.PANIC_RESET_LATCHED, container.lockdownCondition())
    }

    @Test
    fun finalizeFailureBestEffortPersistsReleasePendingMarker() {
        lockdownStore.latch()
        lockdownStore.prepareAuthorizedRelease()
        val prefs = context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("release_pending", false))
        // Simulate an interrupted/tampered pending bit immediately before the final durable write.
        assertTrue(prefs.edit().putBoolean("release_pending", false).commit())

        assertThrows(SecurityException::class.java) { lockdownStore.finalizeAuthorizedRelease() }
        assertTrue(prefs.getBoolean("release_pending", false))
        val restartedStore = AndroidPanicResetLockdownStore(
            context,
            integrityKeys,
            lockdownPreferencesName,
            lockdownIntegrityAlias
        )
        assertTrue(restartedStore.isLocked())
    }

    @Test
    fun corruptedLockdownIntegrityRequiresExplicitSecurityStateResetAndRotatesAlias() {
        configurePin(container)
        val apiKey = "CORRUPTED-STATE-API-KEY-SENTINEL"
        container.storeProviderCredential(ProviderCredentialCatalog.GEMINI_FREE, apiKey.toCharArray())
        lockdownStore.latch()
        val oldIntegrityKey = integrityKeys.getExistingKey(lockdownIntegrityAlias)!!.encoded.copyOf()
        val prefs = context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("integrity_mac", "tampered-value").commit())

        assertEquals(LockdownCondition.INTEGRITY_FAILURE, container.lockdownCondition())
        assertEquals(
            LockdownReleaseResult.ConfirmationRequired,
            container.releaseSecurityLockdown(LockdownReleaseCoordinator.RELEASE_LOCKDOWN_CONFIRMATION)
        )
        val result = container.releaseSecurityLockdown(LockdownReleaseCoordinator.RESET_SECURITY_STATE_CONFIRMATION)
        assertEquals(LockdownReleaseResult.Released(epoch = 1L, securityStateReset = true), result)
        assertFalse(lockdownStore.isLocked())
        val newIntegrityKey = integrityKeys.getExistingKey(lockdownIntegrityAlias)!!.encoded
        assertFalse(oldIntegrityKey.contentEquals(newIntegrityKey))
        oldIntegrityKey.fill(0)
        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        assertEquals(LockdownCondition.NONE, container.lockdownCondition())

        val records = container.actionAuditLog.getAllRecords()
        assertEquals(1, records.size)
        assertEquals("security_state_reset_authorized", records.single().skillName)
        val auditText = records.joinToString()
        assertFalse(auditText.contains(apiKey))
        assertFalse(auditText.contains(LockdownReleaseCoordinator.RESET_SECURITY_STATE_CONFIRMATION))
    }

    private fun unusedExternalProvider(): InferenceProvider = object : InferenceProvider {
        override fun capabilities() = ProviderCapabilities(
            provider_type = ProviderType.EXTERNAL,
            max_context_tokens = 4096,
            max_output_tokens = 512,
            supports_streaming = false,
            supports_structured_output = false,
            supports_seed = false,
            supports_cancellation = false
        )

        override fun generate(request: InferenceRequest): Result<InferenceResponse> =
            Result.failure(UnsupportedOperationException("Provider generation is not used by this test"))
    }

    private fun newProductionContainer(): runtime.di.AgentContainer {
        val freshLockdownStore = AndroidPanicResetLockdownStore(
            context,
            integrityKeys,
            lockdownPreferencesName,
            lockdownIntegrityAlias
        )
        val productionContainer = runtime.di.AgentContainer(
            applicationContext = context,
            databaseOverride = database,
            keystoreKeyProviderOverride = keyProvider,
            securityEpochStoreOverride = epochStore,
            panicResetLockdownStoreOverride = freshLockdownStore,
            benchmarkWorkCancellerOverride = inference.PanicResetBenchmarkWorkCanceller { }
        )
        productionContainer.setAppLockStateStoreForTest(appLockStore())
        return productionContainer
    }

    private fun appLockStore() = AndroidAppLockStateStore(
        context,
        integrityKeys,
        appLockPreferencesName,
        appLockIntegrityAlias
    )

    private fun configurePin(
        target: runtime.di.AgentContainer,
        pin: String = "123456"
    ) {
        val pinChars = pin.toCharArray()
        val confirmation = pin.toCharArray()
        assertEquals(AppLockOperationResult.Success, target.appLockManager.configurePin(pinChars, confirmation))
        assertTrue(pinChars.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
    }
}
