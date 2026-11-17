package inference

import contracts.GenerateParams
import contracts.InferenceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DedicatedCpuInferenceDispatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class AffinityBridge : LlamaNativeBridge {
        private val threadAffinity: ThreadLocal<MutableList<Int>> =
            ThreadLocal.withInitial { BASELINE_CORES.toMutableList() }
        private val pinOutstanding: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        val affinityReset = CountDownLatch(1)
        val affinityReads = mutableListOf<Pair<String, List<Int>?>>()
        val setAttempts = mutableListOf<Pair<String, List<Int>>>()
        val loadThreadNames = mutableListOf<String>()
        val generateThreadNames = mutableListOf<String>()
        val releaseThreadNames = mutableListOf<String>()
        val loadAffinityMasks = mutableListOf<List<Int>>()
        val generatedAffinityMasks = mutableListOf<List<Int>>()
        val releasedAffinityMasks = mutableListOf<List<Int>>()
        val configuredThreadCounts = mutableListOf<Int>()
        var rejectedMasks: Set<List<Int>> = emptySet()
        var effectiveOverrides: Map<List<Int>, List<Int>> = emptyMap()
        var generation: (() -> NativeGenerationResult)? = null
        var cancelAction: (() -> Unit)? = null
        var cancelCalls = ZERO
        private var nextHandle = HANDLE_START

        override fun currentThreadAffinity(): IntArray? {
            val current = checkNotNull(threadAffinity.get()).toList()
            synchronized(affinityReads) {
                affinityReads += Thread.currentThread().name to current
            }
            if (pinOutstanding.get() == true && current == BASELINE_CORES) {
                pinOutstanding.set(false)
                affinityReset.countDown()
            }
            return current.toIntArray()
        }

        override fun setThreadAffinity(cpuIds: IntArray): Boolean {
            val requested = cpuIds.toList().distinct().sorted()
            synchronized(setAttempts) { setAttempts += Thread.currentThread().name to requested }
            if (requested in rejectedMasks) return false
            val effective = effectiveOverrides[requested] ?: requested
            threadAffinity.set(effective.toMutableList())
            if (effective != BASELINE_CORES) pinOutstanding.set(true)
            return true
        }

        override fun loadModel(
            modelPath: String,
            contextTokens: Int,
            nThreads: Int,
            nThreadsBatch: Int
        ): Long {
            synchronized(loadThreadNames) {
                loadThreadNames += Thread.currentThread().name
                loadAffinityMasks += checkNotNull(threadAffinity.get()).toList()
                configuredThreadCounts += nThreads
            }
            return nextHandle++
        }

        override fun systemInfo(): String = "CPU fixture"

        override fun generate(
            handle: Long,
            context: String,
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            grammar: String?
        ): NativeGenerationResult {
            synchronized(generateThreadNames) {
                generateThreadNames += Thread.currentThread().name
                generatedAffinityMasks += checkNotNull(threadAffinity.get()).toList()
            }
            return generation?.invoke() ?: NativeGenerationResult(
                text = "fixed fake output",
                promptTokens = PROMPT_TOKEN_COUNT,
                completionTokens = COMPLETION_TOKEN_COUNT,
                finishReason = "stop",
                prefillElapsedNanos = PREFILL_NANOS,
                firstTokenElapsedNanos = FIRST_TOKEN_NANOS,
                decodeElapsedNanos = DECODE_NANOS
            )
        }

        override fun releaseModel(handle: Long) {
            synchronized(releaseThreadNames) {
                releaseThreadNames += Thread.currentThread().name
                releasedAffinityMasks += checkNotNull(threadAffinity.get()).toList()
            }
        }

        override fun cancelGeneration(handle: Long) {
            synchronized(this) { cancelCalls++ }
            cancelAction?.invoke()
        }

        companion object {
            // PROVISIONAL - pending GEN-PERF-1; arbitrary JVM fixture CPU IDs only.
            val BASELINE_CORES = listOf(101, 102, 103, 104)
            // PROVISIONAL - pending GEN-PERF-1; arbitrary JVM fixture CPU IDs only.
            val STRONG_CORES = listOf(201, 202)
            // PROVISIONAL - pending GEN-PERF-1
            const val ZERO = 0
            // PROVISIONAL - pending GEN-PERF-1
            const val HANDLE_START = 10L
            // PROVISIONAL - pending GEN-PERF-1
            const val PROMPT_TOKEN_COUNT = 4
            // PROVISIONAL - pending GEN-PERF-1
            const val COMPLETION_TOKEN_COUNT = 2
            // PROVISIONAL - pending GEN-PERF-1
            const val PREFILL_NANOS = 20_000_000L
            // PROVISIONAL - pending GEN-PERF-1
            const val FIRST_TOKEN_NANOS = 40_000_000L
            // PROVISIONAL - pending GEN-PERF-1
            const val DECODE_NANOS = 30_000_000L
        }
    }

    @Test
    fun productionGenerationRunsOnDedicatedWorkerPinnedBeforeContextAndRestoresTheMask() {
        val bridge = AffinityBridge()
        val dispatcher = dispatcher(bridge)
        val caller = Executors.newSingleThreadExecutor { task -> Thread(task, "shared-caller").apply { isDaemon = true } }
        try {
            val future = caller.submit<Pair<List<List<Int>>, Result<*>>> {
                val before = bridge.currentThreadAffinity()!!.toList()
                val result = dispatcher.generate(request())
                val after = bridge.currentThreadAffinity()!!.toList()
                listOf(before, after) to result
            }
            val (callerMasks, result) = future.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertTrue(result.isSuccess)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES, AffinityBridge.BASELINE_CORES), callerMasks)
            assertEquals(listOf(AffinityBridge.STRONG_CORES), bridge.loadAffinityMasks)
            assertEquals(listOf(AffinityBridge.STRONG_CORES), bridge.generatedAffinityMasks)
            assertEquals(listOf(2), bridge.configuredThreadCounts)
            assertEquals(listOf("agent-dedicated-cpu-inference"), bridge.loadThreadNames)
            assertEquals(bridge.loadThreadNames, bridge.generateThreadNames)
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
        } finally {
            dispatcher.close()
            caller.shutdownNow()
        }
    }

    @Test
    fun sysfsReadFailureUsesGenericNoticeAndTwoThreadBaselineFallback() {
        val bridge = AffinityBridge()
        val notices = mutableListOf<String>()
        val dispatcher = dispatcher(bridge, { throw IllegalStateException("private sysfs details") }, notices::add)
        try {
            val result = dispatcher.generate(request())

            assertTrue(result.isSuccess)
            assertEquals(listOf(2), bridge.configuredThreadCounts)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.loadAffinityMasks)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.generatedAffinityMasks)
            assertTrue(notices.single().contains("two-thread CPU fallback"))
            assertFalse(notices.single().contains("private sysfs details"))
            assertTrue(bridge.setAttempts.isEmpty())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun rejectedPinResetsAndFallsBackToTwoThreadsWithoutThrowing() {
        val bridge = AffinityBridge().apply { rejectedMasks = setOf(AffinityBridge.STRONG_CORES) }
        val notices = mutableListOf<String>()
        val dispatcher = dispatcher(bridge, report = notices::add)
        try {
            val result = dispatcher.generate(request())

            assertTrue(result.isSuccess)
            assertEquals(listOf(2), bridge.configuredThreadCounts)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.generatedAffinityMasks)
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
            assertTrue(notices.isNotEmpty())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun kernelReadbackMismatchIsTreatedAsPinFailureAndRestoredBeforeFallback() {
        val bridge = AffinityBridge().apply {
            effectiveOverrides = mapOf(AffinityBridge.STRONG_CORES to listOf(AffinityBridge.STRONG_CORES.first()))
        }
        val dispatcher = dispatcher(bridge)
        try {
            val result = dispatcher.generate(request())

            assertTrue(result.isSuccess)
            assertEquals(listOf(2), bridge.configuredThreadCounts)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.loadAffinityMasks)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.generatedAffinityMasks)
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun appLockCancellationAbortsGenerationAndRestoresBeforeReturning() {
        val bridge = AffinityBridge()
        val generationStarted = CountDownLatch(COUNT_ONE)
        val abortObserved = CountDownLatch(COUNT_ONE)
        val allowed = AtomicBoolean(true)
        bridge.generation = {
            generationStarted.countDown()
            check(abortObserved.await(WAIT_SECONDS, TimeUnit.SECONDS))
            NativeGenerationResult(
                "fixed fake output",
                AffinityBridge.PROMPT_TOKEN_COUNT,
                AffinityBridge.COMPLETION_TOKEN_COUNT,
                "stop",
                AffinityBridge.PREFILL_NANOS,
                AffinityBridge.FIRST_TOKEN_NANOS,
                AffinityBridge.DECODE_NANOS
            )
        }
        bridge.cancelAction = { abortObserved.countDown() }
        val dispatcher = dispatcher(bridge)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val future = caller.submit<Result<*>> { dispatcher.generate(request()) { allowed.get() } }
            assertTrue(generationStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            allowed.set(false)
            val result = future.get(WAIT_SECONDS, TimeUnit.SECONDS)

            assertTrue(result.isFailure)
            assertTrue(bridge.cancelCalls > 0)
            assertTrue(bridge.affinityReset.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
            dispatcher.unloadModel()
            allowed.set(true)
            assertTrue(dispatcher.generate(request()) { allowed.get() }.isSuccess)
            assertEquals(2, bridge.loadAffinityMasks.size)
        } finally {
            dispatcher.close()
            caller.shutdownNow()
        }
    }

    @Test
    fun unloadRunsOnDedicatedWorkerOnlyAfterBaselineAffinityIsRestored() {
        val bridge = AffinityBridge()
        val dispatcher = dispatcher(bridge)
        try {
            assertTrue(dispatcher.generate(request()).isSuccess)
            dispatcher.unloadModel()

            assertEquals(listOf("agent-dedicated-cpu-inference"), bridge.releaseThreadNames)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.releasedAffinityMasks)
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun changingStrongCoreProfileReloadsContextWithNewThreadCount() {
        val bridge = AffinityBridge()
        val strongCores = AtomicReference(AffinityBridge.STRONG_CORES)
        val dispatcher = dispatcher(bridge, strongCoreIdsProvider = strongCores::get)
        try {
            assertTrue(dispatcher.generate(request()).isSuccess)
            // PROVISIONAL - pending GEN-PERF-1; arbitrary test-only CPU IDs, not hardware assignments.
            val secondStrongSet = listOf(203, 204, 205)
            strongCores.set(secondStrongSet)
            assertTrue(dispatcher.generate(request()).isSuccess)

            assertEquals(listOf(2, 3), bridge.configuredThreadCounts)
            assertEquals(listOf(AffinityBridge.STRONG_CORES, secondStrongSet), bridge.loadAffinityMasks)
            assertEquals(listOf(AffinityBridge.BASELINE_CORES), bridge.releasedAffinityMasks)
            assertEquals(AffinityBridge.BASELINE_CORES, workerMaskReads(bridge).last())
        } finally {
            dispatcher.close()
        }
    }

    private fun dispatcher(
        bridge: AffinityBridge,
        strongCoreIdsProvider: () -> List<Int>? = { AffinityBridge.STRONG_CORES },
        report: (String) -> Unit = {}
    ): DedicatedCpuInferenceDispatcher = DedicatedCpuInferenceDispatcher(
        backend = LlamaCppInferenceBackend(
            modelAssetStore = installedStore(),
            contextTokens = ContextWindowBudgetPolicy().maxContextTokens,
            bridge = bridge,
            nThreads = INITIAL_THREADS,
            nThreadsBatch = INITIAL_THREADS
        ),
        strongCoreIdsProvider = strongCoreIdsProvider,
        reportGenericFallback = report
    )

    private fun installedStore(): ModelAssetStore {
        val payload = "local CPU dispatcher fixture".toByteArray()
        val store = ModelAssetStore(temporaryFolder.newFolder(), checksumOf(payload), "fixture.gguf")
        assertTrue(store.importModel(ByteArrayInputStream(payload)) is ModelAssetStore.ImportResult.Installed)
        return store
    }

    private fun checksumOf(payload: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private fun request() = InferenceRequest(
        prompt = "Fixed harmless request",
        context = "CPU inference test",
        params = GenerateParams(max_tokens = OUTPUT_TOKENS, temperature = 0.0f, top_p = 1.0f)
    )

    private fun workerMaskReads(bridge: AffinityBridge): List<List<Int>?> = synchronized(bridge.affinityReads) {
        bridge.affinityReads.filter { it.first == "agent-dedicated-cpu-inference" }.map { it.second }
    }

    private companion object {
        // PROVISIONAL - pending GEN-PERF-1
        const val INITIAL_THREADS = 4
        // PROVISIONAL - pending GEN-PERF-1
        const val OUTPUT_TOKENS = 8
        // PROVISIONAL - pending GEN-PERF-1
        const val WAIT_SECONDS = 5L
        // PROVISIONAL - pending GEN-PERF-1
        const val COUNT_ONE = 1
    }
}
