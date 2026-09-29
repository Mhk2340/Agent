package reasoning

import contracts.GenerateParams
import contracts.InferenceProvider
import contracts.InferenceRequest
import contracts.InferenceResponse
import contracts.ProviderCapabilities
import contracts.ProviderType
import contracts.StructuredCognitiveCall
import contracts.TokenUsage
import inference.ContextWindowBudgetPolicy
import memory.LongTermMemoryEntry
import memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase VII Tier 1 Test Suite:
 * - GEN-NEW-E: Structured Output Grammar Constraint (GBNF export + JSON Schema)
 * - GEN-NEW-A: Structured Cognitive Call Execution (Contract #3)
 * - GEN-244: Context Manager assembly within budget
 * - GEN-310: Model Failure Recovery & Bounded Retry
 */
class PhaseVIIReasoningBaseTest {

    private lateinit var mockRetrievalSource: ContextRetrievalSource
    private lateinit var contextManager: ContextManager

    @Before
    fun setup() {
        mockRetrievalSource = object : ContextRetrievalSource {
            override fun retrieveRelevantContext(query: String, maxTokens: Int): List<LongTermMemoryEntry> {
                return listOf(
                    LongTermMemoryEntry(
                        id = "mem-1",
                        memoryType = MemoryType.SEMANTIC,
                        content = "User lives in Tehran",
                        createdAt = 1000L,
                        lastAccessedAt = 1000L
                    )
                )
            }
        }
        contextManager = ContextManager(
            budgetPolicy = ContextWindowBudgetPolicy(maxContextTokens = 4096),
            retrievalSource = mockRetrievalSource
        )
    }

    // 1. GEN-NEW-E Grammar Constraint Validation & GBNF Definition
    @Test
    fun testStructuredGrammarConstraintValidationSuccess() {
        val validJson = """
            {
              "intent": "check_weather",
              "goal": "Get weather for user location",
              "plan": ["Lookup location", "Call weather API"],
              "needs_tool": true,
              "tool_name": "weather_api",
              "tool_params": { "city": "Tehran" },
              "confidence": 0.95
            }
        """.trimIndent()

        val parseResult = StructuredGrammarConstraint.validateAndParse(validJson)
        assertTrue(parseResult.isSuccess)
        val cognitiveCall = parseResult.getOrNull()!!
        assertEquals("check_weather", cognitiveCall.intent)
        assertEquals("weather_api", cognitiveCall.tool_name)
        assertTrue(cognitiveCall.needs_tool)
        assertEquals(0.95f, cognitiveCall.confidence, 0.001f)

        // Verify GBNF definition exists and contains essential grammar elements
        val gbnf = StructuredGrammarConstraint.gbnfGrammarDefinition
        assertTrue("GBNF must define root rule", gbnf.startsWith("root ::="))
        assertTrue("GBNF must enforce intent", gbnf.contains("intent"))
    }

    @Test
    fun testStructuredGrammarConstraintValidationFailure() {
        // Missing required field "plan" and invalid confidence > 1.0
        val invalidJson = """
            {
              "intent": "check_weather",
              "goal": "Get weather",
              "needs_tool": false,
              "confidence": 1.5
            }
        """.trimIndent()

        val parseResult = StructuredGrammarConstraint.validateAndParse(invalidJson)
        assertTrue("Malformed JSON violating Contract #3 schema must fail", parseResult.isFailure)
        assertTrue(parseResult.exceptionOrNull()!!.message!!.contains("Grammar constraint violation"))
    }

    // 2. GEN-244 Context Manager Assembly
    @Test
    fun testContextManagerAssembly() {
        val assembled = contextManager.assembleContext(
            userMessage = "What is the temperature right now?",
            sessionHistory = listOf("User: Hello", "Assistant: Hi there!"),
            maxOutputTokensTarget = 512
        )

        assertNotNull(assembled.fullPrompt)
        assertTrue(assembled.fullPrompt.contains("System: You are an autonomous Android Agent"))
        assertTrue(assembled.fullPrompt.contains("Tehran")) // From retrieved memory
        assertTrue(assembled.fullPrompt.contains("User: Hello")) // From session history
        assertTrue(assembled.fullPrompt.contains("What is the temperature right now?"))
        assertEquals(1, assembled.retrievedEntriesCount)
    }

    // 3. GEN-NEW-A Cognitive Call Execution with Mock InferenceProvider
    @Test
    fun testCognitiveExecutionEngineSuccess() {
        val validCognitiveCallOutput = """
            {
              "intent": "query_battery",
              "goal": "Check system battery level",
              "plan": ["Call system_info"],
              "needs_tool": true,
              "tool_name": "system_info",
              "tool_params": {},
              "confidence": 0.88
            }
        """.trimIndent()

        var recordedRequest: InferenceRequest? = null
        val mockProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                recordedRequest = request
                return Result.success(
                    InferenceResponse(
                        text = validCognitiveCallOutput,
                        usage = TokenUsage(prompt_tokens = 80, completion_tokens = 25),
                        finish_reason = "stop"
                    )
                )
            }
        }

        val engine = CognitiveExecutionEngine(mockProvider, contextManager)
        val result = engine.executeCognitiveCall("Check my battery percentage")

        assertTrue(result is CognitiveExecutionEngine.CognitiveCallResult.Success)
        val call = (result as CognitiveExecutionEngine.CognitiveCallResult.Success).call
        assertEquals("query_battery", call.intent)
        assertEquals("system_info", call.tool_name)
        assertTrue(call.needs_tool)
        assertEquals(1, result.attemptsMade)

        // Verify request was constrained with JSON schema from Contract #1 / GEN-NEW-E
        assertNotNull(recordedRequest)
        assertEquals(StructuredGrammarConstraint.cognitiveCallSchemaJson, recordedRequest!!.outputSchema)
        assertEquals(0.0f, recordedRequest!!.params.temperature, 0.0f)
    }

    // 4. GEN-310 Model Failure Recovery: Corrupted first output recovers on bounded retry
    @Test
    fun testCognitiveExecutionEngineFailureRecoverySuccessOnRetry() {
        val corruptedOutput = "Broken JSON response"
        val validOutput = """
            {
              "intent": "open_settings",
              "goal": "Navigate to settings",
              "plan": ["open"],
              "needs_tool": false,
              "tool_name": null,
              "tool_params": {},
              "confidence": 0.99
            }
        """.trimIndent()

        var callCount = 0
        val mockProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                callCount++
                val text = if (callCount == 1) corruptedOutput else validOutput
                return Result.success(
                    InferenceResponse(text = text, usage = TokenUsage(50, 10), finish_reason = "stop")
                )
            }
        }

        val engine = CognitiveExecutionEngine(mockProvider, contextManager, maxParseRetries = 1)
        val result = engine.executeCognitiveCall("Open settings")

        assertTrue("Failure recovery should succeed on attempt 2", result is CognitiveExecutionEngine.CognitiveCallResult.Success)
        val success = result as CognitiveExecutionEngine.CognitiveCallResult.Success
        assertEquals(2, success.attemptsMade)
        assertEquals("open_settings", success.call.intent)
    }

    // 5. GEN-310 Model Failure Recovery: Total exhaustion leads to clean failure
    @Test
    fun testCognitiveExecutionEngineFailureRecoveryExhaustion() {
        val corruptedOutput = "Permanently unparseable text"
        val mockProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                return Result.success(
                    InferenceResponse(text = corruptedOutput, usage = TokenUsage(50, 10), finish_reason = "stop")
                )
            }
        }

        val engine = CognitiveExecutionEngine(mockProvider, contextManager, maxParseRetries = 1)
        val result = engine.executeCognitiveCall("Trigger broken model")

        assertTrue(result is CognitiveExecutionEngine.CognitiveCallResult.Failure)
        val failure = result as CognitiveExecutionEngine.CognitiveCallResult.Failure
        assertEquals(2, failure.attemptsMade)
        assertTrue(failure.reason.contains("Model failure recovery exhausted"))
    }

    // 6. GEN-244 <-> GEN-MEM-4 Real Integration Test: ContextManager consumes real pipeline
    @Test
    fun testContextManagerRealRetrievalPipelineIntegration() {
        val memoryStorage = memory.EncryptedMemoryStorage()
        val realMemoryManager = memory.MemoryManager(storage = memoryStorage)

        // Seed real long-term memory
        realMemoryManager.saveLongTermMemory(
            memoryType = MemoryType.PROCEDURAL,
            content = "Always verify battery status before starting background sync",
            embedding = List(384) { 0.15f },
            importance = 0.95f,
            origin = "real",
            callerRole = memory.MemoryCallerRole.SYSTEM_CORE
        )

        val realPipeline = memory.ContextRetrievalPipeline(memoryManager = realMemoryManager)
        val integratedContextManager = ContextManager(
            budgetPolicy = ContextWindowBudgetPolicy(maxContextTokens = 4096),
            retrievalSource = realPipeline
        )

        val assembled = integratedContextManager.assembleContext(
            userMessage = "Sync background data",
            sessionHistory = emptyList(),
            maxOutputTokensTarget = 512
        )

        assertNotNull(assembled)
        assertTrue("Assembled context must contain real retrieved procedural memory",
            assembled.fullPrompt.contains("Always verify battery status before starting background sync"))
        assertEquals(1, assembled.retrievedEntriesCount)
    }

    // 7. GEN-NEW-D Turn Budget Governor: 4th LLM Call in a turn is strictly blocked
    @Test
    fun testTurnBudgetGovernorCeilingBlocksFourthCall() {
        val governor = TurnBudgetGovernor(maxLlmCallsPerTurn = 3, timeoutPerTurnSeconds = 30)
        governor.startTurn()

        // Call 1 permitted
        val c1 = governor.recordAndCheckCall("Call 1: GEN-NEW-A")
        assertEquals(1, c1)

        // Call 2 permitted
        val c2 = governor.recordAndCheckCall("Call 2: GEN-NEW-B")
        assertEquals(2, c2)

        // Call 3 permitted
        val c3 = governor.recordAndCheckCall("Call 3: GEN-NEW-C")
        assertEquals(3, c3)

        // Call 4 MUST be strictly blocked
        try {
            governor.recordAndCheckCall("Call 4: Illegal additional call")
            assertTrue("Fourth LLM call in a single turn must throw TurnBudgetExceededException", false)
        } catch (e: TurnBudgetExceededException) {
            assertEquals(3, e.callsMade)
            assertTrue(e.reason.contains("Turn budget call limit exceeded"))
        }
    }

    // 8. GEN-NEW-C Conditional Critic: Runs on low confidence or tool failure, SKIPPED on high confidence + success
    @Test
    fun testConditionalCriticExecutionAndSkipping() {
        val governor = TurnBudgetGovernor(maxLlmCallsPerTurn = 3)
        governor.startTurn()

        val mockProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                return Result.success(
                    InferenceResponse(
                        text = "Critic advice: Tool failed due to timeout, suggest retry or informing user.",
                        usage = TokenUsage(40, 20),
                        finish_reason = "stop"
                    )
                )
            }
        }

        val critic = ConditionalCriticCall(mockProvider, governor, confidenceThreshold = 0.6f)

        val highConfidenceCall = StructuredCognitiveCall(
            intent = "read_battery",
            goal = "Check battery",
            plan = listOf("read"),
            needs_tool = false,
            confidence = 0.95f
        )

        // Scenario A: High confidence (0.95 >= 0.6) and tool succeeded -> Critic SKIPPED
        val skippedEval = critic.evaluate(
            cognitiveCall = highConfidenceCall,
            toolFailed = false,
            toolError = null
        )
        assertFalse("Critic must be skipped when confidence is high and no tool failure", skippedEval.triggered)
        assertEquals(0, governor.getCallsMade())

        // Scenario B: Low confidence (0.45 < 0.6) -> Critic TRIGGERED
        val lowConfidenceCall = highConfidenceCall.copy(confidence = 0.45f)
        val triggeredEvalConfidence = critic.evaluate(
            cognitiveCall = lowConfidenceCall,
            toolFailed = false
        )
        assertTrue("Critic must trigger when confidence < 0.6", triggeredEvalConfidence.triggered)
        assertEquals(1, governor.getCallsMade())
        assertNotNull(triggeredEvalConfidence.criticFeedback)

        // Scenario C: Tool failed -> Critic TRIGGERED regardless of confidence
        val triggeredEvalToolFailure = critic.evaluate(
            cognitiveCall = highConfidenceCall,
            toolFailed = true,
            toolError = "Network connection timeout"
        )
        assertTrue("Critic must trigger when tool execution fails", triggeredEvalToolFailure.triggered)
        assertEquals(2, governor.getCallsMade())
    }

    // 9. GEN-NEW-B Response Generation Call: Generates final response & persists ExperienceRecord v1.1
    @Test
    fun testResponseGenerationCallAndContract4Integration() {
        val governor = TurnBudgetGovernor(maxLlmCallsPerTurn = 3)
        governor.startTurn()

        val memoryStorage = memory.EncryptedMemoryStorage()
        val realMemoryManager = memory.MemoryManager(storage = memoryStorage)

        val mockProvider = object : InferenceProvider {
            override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
                provider_type = ProviderType.LOCAL,
                max_context_tokens = 4096,
                max_output_tokens = 512,
                supports_streaming = false,
                supports_structured_output = true,
                supports_seed = true,
                supports_cancellation = true
            )

            override fun generate(request: InferenceRequest): Result<InferenceResponse> {
                return Result.success(
                    InferenceResponse(
                        text = "The current weather in Tehran is 24°C and clear.",
                        usage = TokenUsage(60, 15),
                        finish_reason = "stop"
                    )
                )
            }
        }

        val responseGenerator = ResponseGenerationCall(
            inferenceProvider = mockProvider,
            governor = governor,
            memoryManager = realMemoryManager
        )

        val cognitiveCall = StructuredCognitiveCall(
            intent = "get_weather",
            goal = "Fetch current weather",
            plan = listOf("call weather_api"),
            needs_tool = true,
            tool_name = "weather_api",
            confidence = 0.9f
        )

        val toolResult = skills.SkillExecutionResult(
            actionId = "tool-act-weather-1",
            skillName = "weather_api",
            status = skills.ExecutionStatus.SUCCEEDED,
            output = "{ temperature: 24, condition: 'clear' }",
            failureReason = null,
            executionCostMs = 120L,
            strategy = "network_api_bridge"
        )

        val response = responseGenerator.generateResponse(
            userMessage = "How is the weather in Tehran?",
            cognitiveCall = cognitiveCall,
            toolResult = toolResult
        )

        assertNotNull(response.finalText)
        assertTrue(response.finalText.contains("Tehran"))
        assertEquals(1, governor.getCallsMade())

        // Verify Contract #4 (ExperienceRecord v1.1) integration
        val exp = response.experienceRecord
        assertNotNull(exp.interaction_id)
        assertEquals("How is the weather in Tehran?", exp.input_summary)
        assertEquals("GEN-NEW-B", exp.signal_source)
        assertEquals(1.0f, exp.signal_value, 0.001f)
        assertEquals("network_api_bridge", exp.execution.strategy)

        // Verify persistence in shared memoryManager
        assertEquals(1, realMemoryManager.getTotalCount(memory.MemoryCallerRole.SYSTEM_CORE))
    }
}
