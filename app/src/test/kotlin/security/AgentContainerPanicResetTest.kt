package security

import android.content.Context
import chat.LocalChatArchive
import chat.LocalChatConversation
import chat.LocalChatMessage
import chat.LocalChatRole
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Configuration as WorkConfiguration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import inference.DeviceFingerprint
import inference.HARDWARE_BENCHMARK_STARVATION_WORK_NAME
import inference.HARDWARE_BENCHMARK_STARVATION_WORK_TAG
import inference.HARDWARE_BENCHMARK_WORK_TAG
import inference.hardwareBenchmarkWorkPlan
import memory.AesGcmCipher
import multiagent.JobStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import persistence.AgentDatabase
import reliability.OperatingMode
import safety.RiskLevel
import skills.ActionAuditRecord
import skills.EpochBoundIdempotencyKey
import skills.ExecutionStatus
import skills.SkillExecutionRequest
import testutil.RoomTestEnvironment
import testutil.TestOnlyIntegrityMacKeyProvider
import testutil.TestOnlyKeystoreKeyProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentContainerPanicResetTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var databaseName: String
    private lateinit var keyProvider: TestOnlyKeystoreKeyProvider
    private lateinit var macKeyProvider: TestOnlyIntegrityMacKeyProvider
    private lateinit var epochPreferencesName: String
    private lateinit var epochIntegrityAlias: String
    private lateinit var lockdownPreferencesName: String
    private lateinit var lockdownIntegrityAlias: String
    private lateinit var epochStore: AndroidSecurityEpochStore
    private lateinit var lockdownStore: AndroidPanicResetLockdownStore
    private lateinit var container: runtime.di.AgentContainer
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)

        keyProvider = TestOnlyKeystoreKeyProvider()
        macKeyProvider = TestOnlyIntegrityMacKeyProvider()
        databaseName = "panic-reset-${UUID.randomUUID()}.db"
        database = RoomTestEnvironment.fileDatabase(
            context,
            databaseName,
            AesGcmCipher(keyProvider, AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        )

        val suffix = UUID.randomUUID().toString()
        epochPreferencesName = "test-security-epoch-$suffix"
        epochIntegrityAlias = "test-epoch-hmac-$suffix"
        lockdownPreferencesName = "test-panic-lock-$suffix"
        lockdownIntegrityAlias = "test-panic-lock-hmac-$suffix"
        epochStore = AndroidSecurityEpochStore(
            context = context,
            keyProvider = macKeyProvider,
            preferencesName = epochPreferencesName,
            integrityKeyAlias = epochIntegrityAlias
        )
        lockdownStore = AndroidPanicResetLockdownStore(
            context = context,
            keyProvider = macKeyProvider,
            preferencesName = lockdownPreferencesName,
            integrityKeyAlias = lockdownIntegrityAlias
        )
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE)
            .edit().clear().commit()

        container = runtime.di.AgentContainer(
            applicationContext = context,
            databaseOverride = database,
            keystoreKeyProviderOverride = keyProvider,
            securityEpochStoreOverride = epochStore,
            panicResetLockdownStoreOverride = lockdownStore
        )
        container.setAppLockStateStoreForTest(InMemoryAppLockStateStore())
        assertEquals(
            AppLockOperationResult.Success,
            container.appLockManager.configurePin("123456".toCharArray(), "123456".toCharArray())
        )
    }

    @After
    fun tearDown() {
        runCatching { workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS) }
        runCatching { database.close() }
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(epochPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(lockdownPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent_policy_config", Context.MODE_PRIVATE).edit().clear().commit()
        keyProvider.clearAllKeysForTest()
        macKeyProvider.clearForTest()
    }

    @Test
    fun productionContainerResetWiresRealHooksAndRetiresOldHistory() {
        assertTrue(container.panicResetPhaseXVIHooks is multiagent.RealPanicResetPhaseXVIHooks)
        assertTrue(container.configStoreResetHook is AndroidAgentPolicyConfigResetHook)

        container.safetyGuard.riskRegistry.registerSkillRisk("guard-preserved", RiskLevel.LEVEL_2)
        val guardRiskBefore = container.safetyGuard.riskRegistry.getEffectiveRiskLevel("guard-preserved")

        container.agentPolicyConfigStore.setRiskLevelOverride("policy-only", 3)
        container.agentPolicyConfigStore.setMaxLevel3ActionsPerHour(3)
        assertEquals(3, container.actionRateLimiter.maxLevel3ActionsPerHour)

        val oldIdempotencyKey = container.newIdempotencyKey()
        assertTrue(EpochBoundIdempotencyKey.belongsToEpoch(oldIdempotencyKey, 1L))
        container.actionAuditLog.recordAction(
            ActionAuditRecord(
                auditId = "audit-before-reset",
                actionId = "action-before-reset",
                skillName = "pre_reset_action",
                riskLevel = 3,
                initiator = "user_turn",
                params = mapOf("sensitive" to "old-audit-payload"),
                confirmationPrompt = null,
                userConfirmed = true,
                executionStatus = "SUCCEEDED",
                output = "old-audit-output",
                failureReason = null,
                costMs = 1L,
                idempotencyKey = oldIdempotencyKey,
                guidedByProcedureId = null,
                origin = "real",
                timestamp = 1L
            )
        ).getOrThrow()

        container.memoryManager.saveLongTermMemory(
            memoryType = memory.MemoryType.SEMANTIC,
            content = "old encrypted memory",
            embedding = null,
            callerRole = memory.MemoryCallerRole.SYSTEM_CORE
        )
        container.memoryManager.appendSessionMessage("user", "old session", memory.MemoryCallerRole.SYSTEM_CORE)
        container.secretManager.storeSecret("gemini", "synthetic-provider-secret")
        val chatFile = File(context.noBackupFilesDir, "local-chat-history.enc")
        val chatConversation = LocalChatConversation(
            id = "chat-before-reset",
            title = "private chat",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            messages = listOf(
                LocalChatMessage("chat-user", LocalChatRole.USER, "private question", 1L),
                LocalChatMessage("chat-answer", LocalChatRole.ASSISTANT, "private answer", 2L)
            )
        )
        assertTrue(container.localChatHistoryStore.write(
            LocalChatArchive(
                activeConversationId = chatConversation.id,
                conversations = listOf(chatConversation)
            )
        ).isSuccess)
        assertTrue(chatFile.exists())
        val oldDatabaseKey = keyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        assertNotNull(oldDatabaseKey)
        assertEquals(listOf("gemini"), container.secretManager.listSecretAliases())
        assertEquals(1, database.providerSecretDao().getProviderIds().size)

        val runningJob = container.asyncJobManager.submitJob("running-job", "Task", "private input")
        container.asyncJobManager.startNextJob()
        val queuedJob = container.asyncJobManager.submitJob("queued-job", "Task 2", "private input 2")
        container.sharedWorkspaceManager.saveRoleOutput("running-job", "worker", "private workspace artifact")
        assertEquals(JobStatus.RUNNING, runningJob.status)
        assertEquals(JobStatus.QUEUED, queuedJob.status)
        assertEquals(1, container.sharedWorkspaceManager.getArtifactsForJob("running-job").size)

        val fingerprint = DeviceFingerprint("panic-test-device", "build", "a".repeat(64), "test-runtime")
        val benchmarkWorkName = hardwareBenchmarkWorkPlan(fingerprint).uniqueName
        enqueueBlockedBenchmarkWork(benchmarkWorkName)
        enqueueBlockedStarvationWork()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosForUniqueWork(benchmarkWorkName)
            .get(10, TimeUnit.SECONDS).single().state)

        assertEquals(1L, epochStore.getCurrentEpoch())
        assertEquals(0, container.actionRateLimiter.getUsageCount())
        assertTrue(container.appLockManager.isAppUnlocked())

        val newEpoch = container.executePanicReset("Sensitive reason must not be logged")

        assertEquals(2L, newEpoch)
        assertEquals(2L, epochStore.getCurrentEpoch())
        assertTrue(lockdownStore.isLocked())
        assertEquals(OperatingMode.SECURITY_LOCKDOWN, container.operatingModeManager.getCurrentMode())
        assertTrue(container.securityLockdownEnforcer.isMemoryReadOnly())
        assertFalse(container.securityLockdownEnforcer.areMutatingToolsAllowed())
        assertFalse(container.securityLockdownEnforcer.isExternalNetworkAllowed())
        assertFalse(container.appLockManager.isAppUnlocked())
        assertEquals(
            AppLockOperationResult.Success,
            container.appLockManager.unlockWithPin("123456".toCharArray())
        )

        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        assertFalse(chatFile.exists())
        assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
        assertTrue(keyProvider.aliases().none { it.startsWith("android_agent_provider_api_key_") })
        assertEquals(0, container.memoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE))
        assertTrue(container.memoryManager.getSessionMessages(memory.MemoryCallerRole.SYSTEM_CORE).isEmpty())
        container.appLockManager.lock()
        assertEquals(1, container.actionRateLimiter.getUsageCount())
        assertEquals(AgentPolicyConfig(), container.agentPolicyConfigStore.snapshot())
        assertEquals(guardRiskBefore, container.safetyGuard.riskRegistry.getEffectiveRiskLevel("guard-preserved"))
        assertEquals(JobStatus.CANCELLED, runningJob.status)
        assertEquals(JobStatus.CANCELLED, queuedJob.status)
        assertTrue(container.sharedWorkspaceManager.getArtifactsForJob("running-job").isEmpty())

        val genesis = container.actionAuditLog.getPanicResetGenesisRecords().single()
        assertEquals("GENESIS", genesis.executionStatus)
        assertEquals("Reset at ${genesis.timestamp}, epoch=2, prior history unreadable", genesis.output)
        val newDatabaseKey = keyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        assertNotNull(newDatabaseKey)
        assertFalse(oldDatabaseKey!!.encoded.contentEquals(newDatabaseKey!!.encoded))
        assertThrows(Exception::class.java) { container.actionAuditLog.getAllRecords() }

        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosForUniqueWork(benchmarkWorkName)
            .get(10, TimeUnit.SECONDS).single().state)
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosForUniqueWork(HARDWARE_BENCHMARK_STARVATION_WORK_NAME)
            .get(10, TimeUnit.SECONDS).single().state)
        container.preloadCurrentHardwareProfile()
        val benchmarkStates = workManager.getWorkInfosByTag(HARDWARE_BENCHMARK_WORK_TAG)
            .get(10, TimeUnit.SECONDS).map { it.state }
        assertTrue(benchmarkStates.isNotEmpty())
        assertTrue(benchmarkStates.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING })

        val reopenedEpochStore = AndroidSecurityEpochStore(
            context,
            macKeyProvider,
            epochPreferencesName,
            epochIntegrityAlias
        )
        assertEquals(2L, reopenedEpochStore.getCurrentEpoch())
        val reopenedLockdownStore = AndroidPanicResetLockdownStore(
            context,
            macKeyProvider,
            lockdownPreferencesName,
            lockdownIntegrityAlias
        )
        assertTrue(reopenedLockdownStore.isLocked())

        // A clean, unlocked test container still rejects the old key by epoch before invoking a handler.
        val unlockedLockPreferencesName = "test-unlocked-panic-lock-${UUID.randomUUID()}"
        val unlockedTestContainer = runtime.di.AgentContainer(
            applicationContext = context,
            databaseOverride = database,
            keystoreKeyProviderOverride = keyProvider,
            securityEpochStoreOverride = epochStore,
            panicResetLockdownStoreOverride = AndroidPanicResetLockdownStore(
                context,
                macKeyProvider,
                unlockedLockPreferencesName,
                "test-unlocked-panic-lock-hmac-${UUID.randomUUID()}"
            )
        )
        unlockedTestContainer.setAppLockStateStoreForTest(InMemoryAppLockStateStore())
        assertEquals(
            AppLockOperationResult.Success,
            unlockedTestContainer.appLockManager.configurePin("123456".toCharArray(), "123456".toCharArray())
        )
        val currentEpochKey = unlockedTestContainer.newIdempotencyKey()
        assertTrue(EpochBoundIdempotencyKey.belongsToEpoch(currentEpochKey, 2L))
        var handlerInvocations = 0
        unlockedTestContainer.skillExecutionEngine.registerHandler("unknown-test-action") {
            handlerInvocations++
            "must-not-run"
        }
        val retry = unlockedTestContainer.skillExecutionEngine.executeSkill(
            SkillExecutionRequest(
                actionId = "retry-old-epoch",
                skillName = "unknown-test-action",
                params = emptyMap(),
                idempotencyKey = oldIdempotencyKey,
                userConfirmed = true
            )
        )
        assertEquals(ExecutionStatus.FAILED, retry.status)
        assertTrue(retry.failureReason.orEmpty().contains("current reset epoch"))
        assertEquals(0, handlerInvocations)
        assertFalse(EpochBoundIdempotencyKey.belongsToEpoch(oldIdempotencyKey, 2L))
        context.getSharedPreferences(unlockedLockPreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun partialSecretAliasDeletionDoesNotStopOtherResetStagesAndKeepsLockdown() {
        val providerSecret = "partial-reset-secret-must-not-escape"
        container.secretManager.storeSecret("groq", providerSecret)
        val secretKeyAlias = keyProvider.aliases().single { it.startsWith("android_agent_provider_api_key_") }
        keyProvider.failSecretKeyDeletionForTest(secretKeyAlias, providerSecret)
        container.memoryManager.saveLongTermMemory(
            memoryType = memory.MemoryType.SEMANTIC,
            content = "partial-failure memory",
            embedding = null,
            callerRole = memory.MemoryCallerRole.SYSTEM_CORE
        )
        container.memoryManager.appendSessionMessage("user", "partial-failure session", memory.MemoryCallerRole.SYSTEM_CORE)
        val job = container.asyncJobManager.submitJob("partial-job", "task", "prompt")
        container.sharedWorkspaceManager.saveRoleOutput(job.jobId, "worker", "artifact")
        container.agentPolicyConfigStore.setMaxLevel3ActionsPerHour(2)

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset("must not appear in exception")
        }

        assertTrue(exception.failedStages.contains("provider-secrets"))
        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains(providerSecret))
        assertFalse(exception.message.orEmpty().contains("must not appear in exception"))
        assertTrue(lockdownStore.isLocked())
        container.securityLockdownEnforcer.enforceLockdown(false)
        container.operatingModeManager.transitionTo(OperatingMode.NORMAL, "ordinary transition cannot release panic latch")
        assertEquals(OperatingMode.SECURITY_LOCKDOWN, container.operatingModeManager.getCurrentMode())
        assertTrue(container.securityLockdownEnforcer.isMemoryReadOnly())
        assertFalse(container.securityLockdownEnforcer.areMutatingToolsAllowed())
        assertFalse(container.securityLockdownEnforcer.isExternalNetworkAllowed())
        assertFalse(container.appLockManager.isAppUnlocked())
        assertEquals(
            AppLockOperationResult.Success,
            container.appLockManager.unlockWithPin("123456".toCharArray())
        )
        assertEquals(2L, epochStore.getCurrentEpoch())
        assertEquals(0, container.memoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE))
        assertTrue(container.memoryManager.getSessionMessages(memory.MemoryCallerRole.SYSTEM_CORE).isEmpty())
        container.appLockManager.lock()
        assertEquals(JobStatus.CANCELLED, job.status)
        assertTrue(container.sharedWorkspaceManager.getArtifactsForJob(job.jobId).isEmpty())
        assertEquals(AgentPolicyConfig(), container.agentPolicyConfigStore.snapshot())
        assertEquals(1, container.actionRateLimiter.getUsageCount())
        assertEquals(1, container.actionAuditLog.getPanicResetGenesisRecords().size)
        assertTrue(keyProvider.aliases().contains(secretKeyAlias))
        assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
    }

    @Test
    fun databaseMemoryAndAuditIdempotencyPurgeFailuresDoNotStopLaterStages() {
        var benchmarkCancelCalls = 0
        container = newProductionContainer(onBenchmarkCancellation = { benchmarkCancelCalls++ })
        val label = "db-purge-failure"
        val oldIdempotencyKey = seedResetState(label)
        val databaseKeyAlias = AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS
        val keyFailureSentinel = "DATABASE-KEY-RETIRE-SECRET-SENTINEL"
        keyProvider.failSecretKeyDeletionForTest(databaseKeyAlias, keyFailureSentinel)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_long_term_delete BEFORE DELETE ON long_term_memory " +
                "BEGIN SELECT RAISE(ABORT, 'LONG-TERM-DB-FAILURE-SENTINEL'); END"
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_idempotency_delete BEFORE DELETE ON action_idempotency " +
                "BEGIN SELECT RAISE(ABORT, 'IDEMPOTENCY-DB-FAILURE-SENTINEL'); END"
        )
        val reason = "DB-RESET-REASON-SENTINEL"

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset(reason)
        }

        val expectedStages = listOf(
            "long-term-memory",
            "audit-key-retirement",
            "memory-fallback-purge",
            "audit-history-fallback-purge",
            "genesis-audit-history-not-retired"
        )
        assertFailureAndLockdown(
            exception = exception,
            expectedStages = expectedStages,
            plaintextSentinels = listOf(reason, keyFailureSentinel, "LONG-TERM-DB-FAILURE-SENTINEL", "IDEMPOTENCY-DB-FAILURE-SENTINEL")
        )
        assertFollowOnStagesRan(container, label, benchmarkCancelCalls, expectedEpoch = 2L, expectMemoryEmpty = false)
        assertTrue(database.longTermMemoryDao().count() > 0)
        assertEquals(1, container.actionAuditLog.getAllRecords().size)
        assertNotNull(container.actionAuditLog.findByIdempotencyKey(oldIdempotencyKey))
        assertTrue(container.actionAuditLog.getPanicResetGenesisRecords().isEmpty())
    }

    @Test
    fun databaseKeyRetirementFailureFallsBackToHistoryPurgeAndKeepsLockdown() {
        var benchmarkCancelCalls = 0
        container = newProductionContainer(onBenchmarkCancellation = { benchmarkCancelCalls++ })
        val label = "key-retirement-failure"
        val oldIdempotencyKey = seedResetState(label)
        val databaseKeyAlias = AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS
        val previousDatabaseKey = keyProvider.getSecretKey(databaseKeyAlias)
        assertNotNull(previousDatabaseKey)
        val keyFailureSentinel = "AES-KEY-RETIREMENT-SECRET-SENTINEL"
        keyProvider.failSecretKeyDeletionForTest(databaseKeyAlias, keyFailureSentinel)
        val reason = "KEY-RESET-REASON-SENTINEL"

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset(reason)
        }

        assertFailureAndLockdown(
            exception = exception,
            expectedStages = listOf("audit-key-retirement"),
            plaintextSentinels = listOf(reason, keyFailureSentinel)
        )
        assertFollowOnStagesRan(container, label, benchmarkCancelCalls, expectedEpoch = 2L)
        assertEquals(0, database.longTermMemoryDao().count())
        assertNull(container.actionAuditLog.findByIdempotencyKey(oldIdempotencyKey))
        val allRecords = container.actionAuditLog.getAllRecords()
        assertEquals(1, allRecords.size)
        assertEquals("GENESIS", allRecords.single().executionStatus)
        assertEquals(1, container.actionAuditLog.getPanicResetGenesisRecords().size)
        assertTrue(previousDatabaseKey!!.encoded.contentEquals(
            keyProvider.getSecretKey(databaseKeyAlias)!!.encoded
        ))
    }

    @Test
    fun epochWriteFailureStillRunsLaterStagesAndKeepsLockdown() {
        var benchmarkCancelCalls = 0
        val epochFailureSentinel = "EPOCH-WRITE-SECRET-SENTINEL"
        val failingEpochStore = object : SecurityEpochStore {
            override fun getCurrentEpoch(): Long = 1L
            override fun incrementEpoch(): Long = throw IllegalStateException(epochFailureSentinel)
        }
        container = newProductionContainer(
            epochStoreOverride = failingEpochStore,
            onBenchmarkCancellation = { benchmarkCancelCalls++ }
        )
        val label = "epoch-write-failure"
        seedResetState(label)
        val reason = "EPOCH-RESET-REASON-SENTINEL"

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset(reason)
        }

        assertFailureAndLockdown(
            exception = exception,
            expectedStages = listOf("security-epoch", "genesis-audit-epoch-unavailable"),
            plaintextSentinels = listOf(reason, epochFailureSentinel)
        )
        assertFollowOnStagesRan(container, label, benchmarkCancelCalls, expectedEpoch = 1L)
        assertTrue(container.actionAuditLog.getPanicResetGenesisRecords().isEmpty())
        assertNull(keyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS))
        assertThrows(Exception::class.java) { container.actionAuditLog.getAllRecords() }
    }

    @Test
    fun genesisAuditWriteFailureLeavesAllEarlierCleanupDoneAndKeepsLockdown() {
        var benchmarkCancelCalls = 0
        container = newProductionContainer(onBenchmarkCancellation = { benchmarkCancelCalls++ })
        val label = "genesis-write-failure"
        seedResetState(label)
        val previousDatabaseKey = keyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)
        assertNotNull(previousDatabaseKey)
        val genesisFailureSentinel = "GENESIS-WRITE-SECRET-SENTINEL"
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_genesis_insert BEFORE INSERT ON action_audit_log " +
                "WHEN NEW.skill_name = 'panic_reset_genesis' " +
                "BEGIN SELECT RAISE(ABORT, '$genesisFailureSentinel'); END"
        )
        val reason = "GENESIS-RESET-REASON-SENTINEL"

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset(reason)
        }

        assertFailureAndLockdown(
            exception = exception,
            expectedStages = listOf("genesis-audit"),
            plaintextSentinels = listOf(reason, genesisFailureSentinel)
        )
        assertFollowOnStagesRan(container, label, benchmarkCancelCalls, expectedEpoch = 2L)
        assertTrue(container.actionAuditLog.getPanicResetGenesisRecords().isEmpty())
        assertThrows(Exception::class.java) { container.actionAuditLog.getAllRecords() }
        assertFalse(previousDatabaseKey!!.encoded.contentEquals(
            keyProvider.getSecretKey(AesGcmCipher.DEFAULT_DATABASE_KEY_ALIAS)!!.encoded
        ))
    }

    @Test
    fun persistentLatchWriteFailureStillRunsLaterStagesAndKeepsProcessLocked() {
        var benchmarkCancelCalls = 0
        val latchFailureSentinel = "PERSISTENT-LATCH-SECRET-SENTINEL"
        val failingLatchStore = object : PanicResetLockdownStore {
            override fun isLocked(): Boolean = false
            override fun latch() {
                throw IllegalStateException(latchFailureSentinel)
            }
        }
        container = newProductionContainer(
            lockdownStoreOverride = failingLatchStore,
            onBenchmarkCancellation = { benchmarkCancelCalls++ }
        )
        val label = "persistent-latch-failure"
        seedResetState(label)
        val reason = "LATCH-RESET-REASON-SENTINEL"

        val exception = assertThrows(PanicResetIncompleteException::class.java) {
            container.executePanicReset(reason)
        }

        assertFailureAndLockdown(
            exception = exception,
            expectedStages = listOf("persistent-lockdown-latch"),
            plaintextSentinels = listOf(reason, latchFailureSentinel),
            persistedLatchReader = { failingLatchStore.isLocked() },
            expectedPersistentLatch = false
        )
        assertFollowOnStagesRan(container, label, benchmarkCancelCalls, expectedEpoch = 2L)
        assertEquals(1, container.actionAuditLog.getPanicResetGenesisRecords().size)
    }

    @Test
    fun securityEpochIntegrityDetectsTamperingAndCannotBeResetByConfigDefaults() {
        assertEquals(1L, epochStore.getCurrentEpoch())
        assertEquals(2L, epochStore.incrementEpoch())
        container.agentPolicyConfigStore.setRiskLevelOverride("temporary", 3)
        container.configStoreResetHook.resetConfigStoreToSeedDefaults()
        assertEquals(2L, epochStore.getCurrentEpoch())

        val preferences = context.getSharedPreferences(epochPreferencesName, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putLong("reset_epoch", 1L).commit())
        assertThrows(SecurityException::class.java) { epochStore.getCurrentEpoch() }
    }

    private fun newProductionContainer(
        epochStoreOverride: SecurityEpochStore = epochStore,
        lockdownStoreOverride: PanicResetLockdownStore = lockdownStore,
        onBenchmarkCancellation: () -> Unit = {}
    ): runtime.di.AgentContainer {
        val testContainer = runtime.di.AgentContainer(
            applicationContext = context,
            databaseOverride = database,
            keystoreKeyProviderOverride = keyProvider,
            securityEpochStoreOverride = epochStoreOverride,
            panicResetLockdownStoreOverride = lockdownStoreOverride,
            benchmarkWorkCancellerOverride = inference.PanicResetBenchmarkWorkCanceller {
                onBenchmarkCancellation()
            }
        )
        testContainer.setAppLockStateStoreForTest(InMemoryAppLockStateStore())
        assertEquals(
            AppLockOperationResult.Success,
            testContainer.appLockManager.configurePin("123456".toCharArray(), "123456".toCharArray())
        )
        return testContainer
    }

    /** Seeds real container-owned stores before a fault is injected. Returns the old retry key. */
    private fun seedResetState(label: String): String {
        container.agentPolicyConfigStore.setRiskLevelOverride("$label-policy", 3)
        container.agentPolicyConfigStore.setMaxLevel3ActionsPerHour(2)
        container.memoryManager.saveLongTermMemory(
            memoryType = memory.MemoryType.SEMANTIC,
            content = "$label-long-term-memory",
            embedding = null,
            callerRole = memory.MemoryCallerRole.SYSTEM_CORE
        )
        container.memoryManager.appendSessionMessage(
            "user",
            "$label-session-plaintext",
            memory.MemoryCallerRole.SYSTEM_CORE
        )
        container.secretManager.storeSecret("$label-provider", "$label-provider-secret-sentinel")
        val oldIdempotencyKey = container.newIdempotencyKey()
        container.actionAuditLog.recordAction(
            ActionAuditRecord(
                auditId = "$label-audit",
                actionId = "$label-action",
                skillName = "$label-skill",
                riskLevel = 3,
                initiator = "user_turn",
                params = mapOf("payload" to "$label-old-audit-payload"),
                confirmationPrompt = null,
                userConfirmed = true,
                executionStatus = "SUCCEEDED",
                output = "$label-old-audit-output",
                failureReason = null,
                costMs = 1L,
                idempotencyKey = oldIdempotencyKey,
                guidedByProcedureId = null,
                origin = "real",
                timestamp = 1L
            )
        ).getOrThrow()
        val jobId = "$label-job"
        container.asyncJobManager.submitJob(jobId, "$label-title", "$label-job-prompt")
        container.asyncJobManager.startNextJob()
        container.sharedWorkspaceManager.saveRoleOutput(jobId, "worker", "$label-workspace-artifact")
        assertTrue(container.appLockManager.isAppUnlocked())
        assertEquals(0, container.actionRateLimiter.getUsageCount())
        return oldIdempotencyKey
    }

    private fun assertFollowOnStagesRan(
        container: runtime.di.AgentContainer,
        label: String,
        benchmarkCancelCalls: Int,
        expectedEpoch: Long,
        expectMemoryEmpty: Boolean = true
    ) {
        assertFalse(container.appLockManager.isAppUnlocked())
        assertEquals(
            AppLockOperationResult.Success,
            container.appLockManager.unlockWithPin("123456".toCharArray())
        )
        try {
            if (expectMemoryEmpty) {
                assertEquals(0, container.memoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE))
            } else {
                assertTrue(container.memoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE) > 0)
            }
            assertTrue(container.memoryManager.getSessionMessages(memory.MemoryCallerRole.SYSTEM_CORE).isEmpty())
        } finally {
            container.appLockManager.lock()
        }
        assertTrue(container.secretManager.listSecretAliases().isEmpty())
        assertTrue(database.providerSecretDao().getProviderIds().isEmpty())
        assertTrue(keyProvider.aliases().none { it.startsWith("android_agent_provider_api_key_") })
        assertEquals(JobStatus.CANCELLED, container.asyncJobManager.getJob("$label-job")?.status)
        assertTrue(container.sharedWorkspaceManager.getArtifactsForJob("$label-job").isEmpty())
        assertEquals(AgentPolicyConfig(), container.agentPolicyConfigStore.snapshot())
        assertEquals(5, container.actionRateLimiter.maxLevel3ActionsPerHour)
        assertEquals(1, container.actionRateLimiter.getUsageCount())
        assertEquals(expectedEpoch, container.securityEpochStore.getCurrentEpoch())
        assertEquals(1, benchmarkCancelCalls)
        assertFalse(container.appLockManager.isAppUnlocked())
    }

    private fun assertFailureAndLockdown(
        exception: PanicResetIncompleteException,
        expectedStages: List<String>,
        plaintextSentinels: List<String>,
        persistedLatchReader: () -> Boolean = { lockdownStore.isLocked() },
        expectedPersistentLatch: Boolean = true
    ) {
        val expectedMessage = "Panic Reset remains locked; failed stages: ${expectedStages.joinToString(", ")}"
        assertEquals(expectedStages, exception.failedStages)
        assertEquals(expectedMessage, exception.message)
        assertNull(exception.cause)
        plaintextSentinels.forEach { sentinel ->
            assertFalse(exception.message.orEmpty().contains(sentinel))
        }
        assertTrue(container.securityLockdownEnforcer.isPanicResetLatched())
        assertEquals(expectedPersistentLatch, persistedLatchReader())

        container.securityLockdownEnforcer.enforceLockdown(false)
        container.operatingModeManager.transitionTo(
            OperatingMode.NORMAL,
            "ordinary transition cannot release panic latch"
        )
        assertEquals(OperatingMode.SECURITY_LOCKDOWN, container.operatingModeManager.getCurrentMode())
        assertTrue(container.securityLockdownEnforcer.isMemoryReadOnly())
        assertFalse(container.securityLockdownEnforcer.areMutatingToolsAllowed())
        assertFalse(container.securityLockdownEnforcer.isExternalNetworkAllowed())
        assertFalse(container.appLockManager.isAppUnlocked())
    }

    private fun enqueueBlockedBenchmarkWork(uniqueName: String) {
        val request = OneTimeWorkRequestBuilder<PanicResetQueuedTestWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            )
            .addTag(HARDWARE_BENCHMARK_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
            .result.get(10, TimeUnit.SECONDS)
    }

    private fun enqueueBlockedStarvationWork() {
        val request = PeriodicWorkRequestBuilder<PanicResetQueuedTestWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .addTag(HARDWARE_BENCHMARK_STARVATION_WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            HARDWARE_BENCHMARK_STARVATION_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        ).result.get(10, TimeUnit.SECONDS)
    }

    private companion object {
        @Volatile
        private var workManagerInitialized = false

        @Synchronized
        private fun ensureTestWorkManager(context: Context) {
            if (workManagerInitialized) return
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                WorkConfiguration.Builder().setExecutor(SynchronousExecutor()).build()
            )
            workManagerInitialized = true
        }
    }
}

class PanicResetQueuedTestWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = Result.success()
}
