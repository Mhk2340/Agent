package runtime.di

import android.content.Context
import inference.AndroidContinuousDeviceIdleGate
import inference.AndroidDeviceConditionSnapshotProvider
import inference.AndroidDeviceFingerprintProvider
import inference.AndroidGpuRuntimeSafetyStore
import inference.AndroidMemoryPressureSignalProvider
import inference.BenchmarkDiagnosticsStore
import inference.BenchmarkStarvationMonitorDependencies
import inference.DeviceFingerprintProvider
import inference.DeviceProfileStore
import inference.HardwareBenchmarkWorkEnqueuer
import inference.HardwareBenchmarkWorkScheduler
import inference.HardwareBenchmarkWorkerDependencies
import inference.HardwareDelegateSelector
import inference.LocalInferenceEngine
import inference.PanicResetBenchmarkWorkCanceller
import inference.ProductionDeviceBenchmarkMeasurement
import inference.UnconfiguredLocalModelRuntimeIdentitySource
import inference.WorkManagerPanicResetBenchmarkWorkCanceller
import inference.WorkManagerUniqueWorkEnqueuePort
import inference.deviceProfileStoreFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import memory.AesGcmCipher
import memory.AndroidKeystoreKeyProvider
import memory.EncryptedMemoryStorage
import memory.KeystoreKeyProvider
import memory.MemoryManager
import multiagent.AsyncJobManager
import multiagent.RealPanicResetPhaseXVIHooks
import multiagent.SharedWorkspaceManager
import persistence.AgentDatabase
import persistence.AgentDatabaseProvider
import persistence.DatabaseInitializationResult
import reliability.AgentOperatingModeManager
import reliability.OperatingMode
import runtime.AppInitializer
import security.AndroidAgentPolicyConfigResetHook
import security.AndroidAgentPolicyConfigStore
import security.AndroidAppLockStateStore
import security.AndroidPanicResetLockdownStore
import security.AndroidSecurityEpochStore
import security.AppLockManager
import security.AppLockStateStore
import security.ConfigStoreResetHook
import security.LockdownCondition
import security.LockdownReleaseCoordinator
import security.LockdownReleaseResult
import security.PanicResetConfirmationPolicy
import security.PanicResetIncompleteException
import security.PanicResetLockdownStore
import security.PanicResetRequestResult
import security.PanicResetPhaseXVIHooks
import security.ProviderCredentialCatalog
import security.ProviderKeyOperationResult
import security.RealSecurityLockdownEnforcer
import security.SecurityEpochStore
import security.SecurityRecoveryAuditSink
import security.SecurityUiActions
import security.KeystoreSecretManager
import safety.AccessMethod
import safety.SafetyGuard
import safety.ToolRegistryRow
import shared.change_management.ChangeAuditLogger
import shared.change_management.ChangeProvenanceLogger
import skills.ActionAuditLog
import skills.ActionRateLimiter
import skills.EpochBoundIdempotencyKey
import skills.SkillExecutionEngine

/**
 * GEN-RT-1 — Production dependency container. Panic Reset wiring is constructed here from real
 * memory, credential, epoch, config, WorkManager, AsyncJobManager, and workspace dependencies.
 * Optional constructor overrides are only platform/test seams (Room and Keystore implementations).
 */
class AgentContainer(
    private val applicationContext: Context? = null,
    val safetyGuard: SafetyGuard = SafetyGuard(),
    private val databaseOverride: AgentDatabase? = null,
    private val keystoreKeyProviderOverride: KeystoreKeyProvider? = null,
    private val securityEpochStoreOverride: SecurityEpochStore? = null,
    private val panicResetLockdownStoreOverride: PanicResetLockdownStore? = null,
    private val benchmarkWorkCancellerOverride: PanicResetBenchmarkWorkCanceller? = null
) : SecurityUiActions {
    private val hardwareProfileLoadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val benchmarkSchedulingLock = Any()

    val defaultTools = listOf(
        ToolRegistryRow(
            tool_name = "system_info",
            domain = "system",
            description = "Reads non-sensitive system status",
            access_method = AccessMethod.SKILL,
            risk_level = 1
        )
    )

    private val keystoreKeyProvider: KeystoreKeyProvider by lazy {
        keystoreKeyProviderOverride ?: AndroidKeystoreKeyProvider()
    }

    internal val database: AgentDatabase by lazy {
        databaseOverride ?: AgentDatabaseProvider.get(
            checkNotNull(applicationContext) { "AgentContainer needs an Android application Context for Room" }
        )
    }

    val deviceFingerprintProvider: DeviceFingerprintProvider by lazy {
        AndroidDeviceFingerprintProvider(UnconfiguredLocalModelRuntimeIdentitySource())
    }

    val deviceProfileStore: DeviceProfileStore by lazy {
        deviceProfileStoreFor(
            checkNotNull(applicationContext) {
                "AgentContainer needs an Android application Context for the hardware profile DataStore"
            }
        )
    }

    val gpuRuntimeSafetyStore by lazy {
        AndroidGpuRuntimeSafetyStore.forContext(
            checkNotNull(applicationContext) { "AgentContainer needs Context for persistent GPU safety state" }
        )
    }

    val benchmarkDiagnostics by lazy {
        BenchmarkDiagnosticsStore.forContext(
            checkNotNull(applicationContext) { "AgentContainer needs Context for local benchmark diagnostics" }
        )
    }

    val hardwareDelegateSelector: HardwareDelegateSelector by lazy {
        HardwareDelegateSelector(deviceProfileStore, gpuRuntimeSafetyStore)
    }

    /** The current backend remains a deterministic stub; this property only wires persisted selection. */
    val localInferenceEngine: LocalInferenceEngine by lazy {
        val context = checkNotNull(applicationContext) {
            "AgentContainer needs Context for local inference safety signals"
        }
        LocalInferenceEngine(
            delegateSelector = hardwareDelegateSelector,
            deviceFingerprint = deviceFingerprintProvider.currentFingerprint().stableKey,
            memoryPressureSignalProvider = AndroidMemoryPressureSignalProvider(context),
            modeDelegateOverride = { operatingModeManager.getConstraints().hardwareDelegateOverride },
            onMemoryPressureFallback = {
                operatingModeManager.transitionTo(OperatingMode.LOW_MEMORY, "GEN-EDGE-1 memory-pressure fallback")
            },
            diagnostics = benchmarkDiagnostics
        )
    }

    private var appLockStateStoreOverrideForTest: AppLockStateStore? = null
    private val appLockManagerLazy = lazy {
        val stateStore = appLockStateStoreOverrideForTest ?: AndroidAppLockStateStore(
            checkNotNull(applicationContext) { "AgentContainer needs Context for persistent App Lock state" }
        )
        AppLockManager(stateStore)
    }
    val appLockManager: AppLockManager get() = appLockManagerLazy.value

    /** TEST ONLY: injects persistent/faulting App Lock storage before its lazy production wiring is read. */
    internal fun setAppLockStateStoreForTest(store: AppLockStateStore) {
        check(!appLockManagerLazy.isInitialized()) { "App Lock manager is already initialized" }
        appLockStateStoreOverrideForTest = store
    }

    val securityEpochStore: SecurityEpochStore by lazy {
        securityEpochStoreOverride ?: AndroidSecurityEpochStore(
            checkNotNull(applicationContext) { "AgentContainer needs Context for the security epoch store" }
        )
    }

    val panicResetLockdownStore: PanicResetLockdownStore by lazy {
        panicResetLockdownStoreOverride ?: AndroidPanicResetLockdownStore(
            checkNotNull(applicationContext) { "AgentContainer needs Context for the persistent Panic Reset latch" }
        )
    }

    val agentPolicyConfigStore: AndroidAgentPolicyConfigStore by lazy {
        AndroidAgentPolicyConfigStore(
            checkNotNull(applicationContext) { "AgentContainer needs Context for the policy Config Store" }
        )
    }

    val configStoreResetHook: ConfigStoreResetHook by lazy {
        AndroidAgentPolicyConfigResetHook(agentPolicyConfigStore)
    }

    val actionRateLimiter: ActionRateLimiter by lazy {
        ActionRateLimiter(
            configuredMaxLevel3ActionsPerHour = {
                agentPolicyConfigStore.snapshot().maxLevel3ActionsPerHour
            }
        )
    }

    internal val memoryStorage: EncryptedMemoryStorage by lazy {
        EncryptedMemoryStorage(
            database = database,
            cipher = AesGcmCipher(keyProvider = keystoreKeyProvider)
        )
    }
    internal val secretManager: KeystoreSecretManager by lazy {
        KeystoreSecretManager(database, keystoreKeyProvider)
    }
    val memoryManager: MemoryManager by lazy {
        MemoryManager(storage = memoryStorage, appLockAccess = appLockManager)
    }
    internal val actionAuditLog: ActionAuditLog by lazy {
        ActionAuditLog(
            memoryManager = memoryManager,
            database = database,
            cipher = AesGcmCipher(keyProvider = keystoreKeyProvider)
        )
    }
    val changeProvenanceLogger: ChangeProvenanceLogger by lazy { ChangeProvenanceLogger(memoryManager) }
    val changeAuditLogger: ChangeAuditLogger by lazy { ChangeAuditLogger(database) }

    val sharedWorkspaceManager: SharedWorkspaceManager by lazy {
        SharedWorkspaceManager(
            cipher = AesGcmCipher(
                keyProvider = keystoreKeyProvider,
                keyAlias = "android_agent_workspace_vault_key"
            )
        )
    }

    val asyncJobManager: AsyncJobManager by lazy { AsyncJobManager() }

    val panicResetPhaseXVIHooks: PanicResetPhaseXVIHooks by lazy {
        RealPanicResetPhaseXVIHooks(asyncJobManager, sharedWorkspaceManager)
    }

    private val panicResetBenchmarkWorkCanceller: PanicResetBenchmarkWorkCanceller by lazy {
        benchmarkWorkCancellerOverride ?: WorkManagerPanicResetBenchmarkWorkCanceller(
            checkNotNull(applicationContext) { "AgentContainer needs Context to cancel WorkManager benchmark jobs" }
        )
    }

    val securityLockdownEnforcer: RealSecurityLockdownEnforcer by lazy {
        RealSecurityLockdownEnforcer(
            memoryManager = memoryManager,
            memoryStorage = memoryStorage,
            secretManager = secretManager,
            epochStore = securityEpochStore,
            rateLimiter = actionRateLimiter,
            actionAuditLog = actionAuditLog,
            databaseKeyProvider = keystoreKeyProvider,
            panicResetLockdownStore = panicResetLockdownStore,
            phaseXVIHooks = panicResetPhaseXVIHooks,
            benchmarkWorkCanceller = panicResetBenchmarkWorkCanceller,
            configStoreResetHook = configStoreResetHook,
            appLockManager = appLockManager
        )
    }

    val skillExecutionEngine: SkillExecutionEngine by lazy {
        SkillExecutionEngine(
            safetyGuard = safetyGuard,
            auditLog = actionAuditLog,
            appLockAccess = appLockManager,
            rateLimiter = actionRateLimiter,
            securityEpochProvider = { securityEpochStore.getCurrentEpoch() },
            isSecurityLockdownActive = {
                isPanicResetLockdownActive() || securityLockdownEnforcer.isMemoryReadOnly()
            }
        )
    }

    private val lockdownReleaseCoordinator: LockdownReleaseCoordinator by lazy {
        LockdownReleaseCoordinator(
            appLockAccess = appLockManager,
            lockdownStore = panicResetLockdownStore,
            secretManager = secretManager,
            epochStore = securityEpochStore,
            auditSink = SecurityRecoveryAuditSink { timestamp, epoch, securityStateReset ->
                actionAuditLog.recordSecurityLockdownReleaseAuthorized(
                    timestampEpochMs = timestamp,
                    resetEpoch = epoch,
                    securityStateReset = securityStateReset
                )
            },
            isModeLockdownActive = { securityLockdownEnforcer.isMemoryReadOnly() },
            onReleaseCompleted = {
                securityLockdownEnforcer.completeAuthorizedRelease()
                operatingModeManager.completeAuthenticatedSecurityRecovery()
            },
            onReleaseFailed = {
                securityLockdownEnforcer.retainLockdownAfterFailedRelease()
                runCatching {
                    operatingModeManager.transitionTo(
                        OperatingMode.SECURITY_LOCKDOWN,
                        "Security recovery incomplete"
                    )
                }
            }
        )
    }

    override fun lockdownCondition(): LockdownCondition = lockdownReleaseCoordinator.currentCondition()

    override fun releaseSecurityLockdown(confirmation: String): LockdownReleaseResult =
        lockdownReleaseCoordinator.release(confirmation)

    val operatingModeManager: AgentOperatingModeManager by lazy {
        AgentOperatingModeManager(
            delegateSelector = hardwareDelegateSelector,
            securityLockdownEnforcer = securityLockdownEnforcer
        ).also { manager ->
            if (isPanicResetLockdownActive()) {
                appLockManager.lock()
                manager.transitionTo(OperatingMode.SECURITY_LOCKDOWN, "Restore persisted Panic Reset latch")
            }
        }
    }

    val hardwareBenchmarkWorkerDependencies: HardwareBenchmarkWorkerDependencies by lazy {
        val context = checkNotNull(applicationContext) {
            "AgentContainer needs an Android application Context for hardware benchmark work"
        }
        HardwareBenchmarkWorkerDependencies(
            fingerprintProvider = deviceFingerprintProvider,
            profileStore = deviceProfileStore,
            measurement = ProductionDeviceBenchmarkMeasurement(),
            idleGate = AndroidContinuousDeviceIdleGate(context),
            conditionSnapshotProvider = AndroidDeviceConditionSnapshotProvider(context),
            diagnostics = benchmarkDiagnostics
        )
    }

    val benchmarkStarvationMonitorDependencies: BenchmarkStarvationMonitorDependencies by lazy {
        BenchmarkStarvationMonitorDependencies(
            fingerprintProvider = deviceFingerprintProvider,
            profileStore = deviceProfileStore,
            diagnostics = benchmarkDiagnostics
        )
    }

    private val hardwareBenchmarkWorkEnqueuer: HardwareBenchmarkWorkEnqueuer? by lazy {
        applicationContext?.let { context ->
            val scheduler = HardwareBenchmarkWorkScheduler(
                fingerprintProvider = deviceFingerprintProvider,
                profileStore = deviceProfileStore,
                enqueuePort = WorkManagerUniqueWorkEnqueuePort(context),
                diagnostics = benchmarkDiagnostics,
                starvationMonitorEnqueuer = WorkManagerUniqueWorkEnqueuePort(context)
            )
            HardwareBenchmarkWorkEnqueuer {
                synchronized(benchmarkSchedulingLock) {
                    if (!isPanicResetLockdownActive() && !securityLockdownEnforcer.isMemoryReadOnly()) {
                        scheduler.enqueue()
                    }
                }
            }
        }
    }

    /** Restores persistent lockdown before profile loading or any work can be scheduled. */
    fun restorePanicResetLockdown() {
        if (isPanicResetLockdownActive()) {
            appLockManager.lock()
            operatingModeManager.transitionTo(
                OperatingMode.SECURITY_LOCKDOWN,
                "Restore persisted Panic Reset latch"
            )
        }
    }

    /** Fail closed if the persistent lock store cannot be read or validated. */
    fun isPanicResetLockdownActive(): Boolean = try {
        panicResetLockdownStore.isLocked()
    } catch (_: Exception) {
        true
    }

    /** Android Application calls this only after restoring the latch; no work is enqueued while locked. */
    fun preloadCurrentHardwareProfile() {
        if (applicationContext == null || isPanicResetLockdownActive()) return
        val fingerprint = deviceFingerprintProvider.currentFingerprint()
        hardwareProfileLoadScope.launch {
            runCatching { deviceProfileStore.getResult(fingerprint) }
            // Enqueue only after the persisted snapshot is loaded, and recheck the latch immediately
            // before scheduling so a concurrent Panic Reset cannot enqueue fresh benchmark work.
            if (!isPanicResetLockdownActive()) {
                runCatching { hardwareBenchmarkWorkEnqueuer?.enqueue() }
            }
        }
    }

    /** UI/application entry point: App Lock and exact confirmation are checked before reset starts. */
    override fun requestPanicReset(confirmation: String): PanicResetRequestResult {
        if (!appLockManager.isAppUnlocked()) return PanicResetRequestResult.AuthenticationRequired
        if (!PanicResetConfirmationPolicy.isConfirmed(confirmation)) {
            return PanicResetRequestResult.ConfirmationRequired
        }
        return try {
            PanicResetRequestResult.Completed(executePanicReset("user-confirmed-panic-reset"))
        } catch (failure: PanicResetIncompleteException) {
            PanicResetRequestResult.Incomplete(failure.failedStages)
        } catch (_: Exception) {
            PanicResetRequestResult.Incomplete(listOf("panic-reset-unexpected-failure"))
        }
    }

    /** Internal mechanics retained for production wiring tests; user entry must use requestPanicReset. */
    internal fun executePanicReset(reason: String): Long = synchronized(benchmarkSchedulingLock) {
        try {
            operatingModeManager.transitionTo(OperatingMode.SECURITY_LOCKDOWN, "Panic Reset initiated")
        } catch (_: Exception) {
            // The enforcer will still latch and lock in-process before attempting any cleanup.
        }
        securityLockdownEnforcer.executePanicReset(reason)
    }

    /** New high-risk requests must bind their idempotency UUID to the currently persisted epoch. */
    fun newIdempotencyKey(): String {
        if (!appLockManager.isAppUnlocked()) {
            throw SecurityException("App Lock is locked; action rejected")
        }
        if (isPanicResetLockdownActive() || securityLockdownEnforcer.isMemoryReadOnly()) {
            throw SecurityException("Agent security lockdown is active")
        }
        return EpochBoundIdempotencyKey.create(securityEpochStore.getCurrentEpoch())
    }

    /** Status-only API; the stored credential itself is never returned to the UI. */
    override fun configuredProviderCredentialIds(): Set<String> {
        if (!appLockManager.isAppUnlocked()) return emptySet()
        return try {
            secretManager.listSecretAliases().filter(ProviderCredentialCatalog::contains).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Stores a newly entered key under the matching ExternalProviderDescriptor.name (`desc.name`). */
    override fun storeProviderCredential(providerName: String, apiKey: CharArray): ProviderKeyOperationResult {
        try {
            if (!appLockManager.isAppUnlocked()) return ProviderKeyOperationResult.AUTHENTICATION_REQUIRED
            if (isPanicResetLockdownActive() || securityLockdownEnforcer.isMemoryReadOnly()) {
                return ProviderKeyOperationResult.LOCKDOWN_ACTIVE
            }
            if (!ProviderCredentialCatalog.contains(providerName)) return ProviderKeyOperationResult.UNKNOWN_PROVIDER
            if (apiKey.none { !it.isWhitespace() }) return ProviderKeyOperationResult.EMPTY_KEY
            // SecretManager is the existing encryption boundary; no key value is logged or returned.
            secretManager.storeSecret(providerName, String(apiKey))
            return ProviderKeyOperationResult.SAVED
        } catch (_: Exception) {
            return ProviderKeyOperationResult.STORAGE_FAILURE
        } finally {
            apiKey.fill('\u0000')
        }
    }

    /** Removes only the requested provider ID/key pair. */
    override fun removeProviderCredential(providerName: String): ProviderKeyOperationResult {
        if (!appLockManager.isAppUnlocked()) return ProviderKeyOperationResult.AUTHENTICATION_REQUIRED
        if (isPanicResetLockdownActive() || securityLockdownEnforcer.isMemoryReadOnly()) {
            return ProviderKeyOperationResult.LOCKDOWN_ACTIVE
        }
        if (!ProviderCredentialCatalog.contains(providerName)) return ProviderKeyOperationResult.UNKNOWN_PROVIDER
        return try {
            secretManager.removeSecret(providerName)
            ProviderKeyOperationResult.REMOVED
        } catch (_: Exception) {
            ProviderKeyOperationResult.STORAGE_FAILURE
        }
    }

    val appInitializer: AppInitializer by lazy {
        AppInitializer(
            safetyGuard = safetyGuard,
            initialTools = defaultTools
        )
    }

    /** Forces Room open/migration during Android Application startup, surfacing DB errors explicitly. */
    fun initializePersistence(): DatabaseInitializationResult {
        val context = checkNotNull(applicationContext) { "AgentContainer needs an Android application Context for Room" }
        val override = databaseOverride
        if (override != null) {
            override.openHelper.writableDatabase
            return DatabaseInitializationResult(override, recoveredByReset = false)
        }
        return AgentDatabaseProvider.initialize(context)
    }
}
