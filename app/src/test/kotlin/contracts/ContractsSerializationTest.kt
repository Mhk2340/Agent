package contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import safety.SkillContract
import safety.ToolRegistryRow
import safety.AccessMethod

class ContractsSerializationTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun testContract2_SkillContract() {
        val skill = SkillContract(
            name = "test_skill",
            input_schema = "{}",
            output_schema = "{}",
            risk_level = 2
        )
        val serialized = json.encodeToString(skill)
        val deserialized = json.decodeFromString<SkillContract>(serialized)
        assertEquals(skill, deserialized)
    }

    @Test
    fun testContract3_StructuredCognitiveCall() {
        val cognitiveCall = StructuredCognitiveCall(
            intent = "fetch_weather",
            goal = "provide current temperature",
            plan = listOf("lookup location", "call api"),
            needs_tool = true,
            tool_name = "weather_api",
            tool_params = mapOf("city" to "Tehran"),
            confidence = 0.85f
        )
        val serialized = json.encodeToString(cognitiveCall)
        val deserialized = json.decodeFromString<StructuredCognitiveCall>(serialized)
        assertEquals(cognitiveCall, deserialized)
    }

    @Test
    fun testContract4_ExperienceRecordV11() {
        val record = ExperienceRecord(
            interaction_id = "exp-001",
            timestamp = 1726000000L,
            input_summary = "what is the time?",
            output_summary = "it is 12:00",
            signal_type = "user_feedback",
            signal_value = 1.0f,
            signal_source = "user",
            execution = ExecutionDetails(
                strategy = "direct_answer",
                cost_ms = 450L,
                failure_reason = null
            )
        )
        val serialized = json.encodeToString(record)
        val deserialized = json.decodeFromString<ExperienceRecord>(serialized)
        assertEquals(record, deserialized)
    }

    @Test
    fun testContract5_ToolRegistryRow() {
        val tool = ToolRegistryRow(
            tool_name = "external_search",
            domain = "web",
            description = "Searches web via official API",
            access_method = AccessMethod.API,
            risk_level = 2
        )
        val serialized = json.encodeToString(tool)
        val deserialized = json.decodeFromString<ToolRegistryRow>(serialized)
        assertEquals(tool, deserialized)
    }

    @Test
    fun testContract6_ProcedureRecord() {
        val procedure = ProcedureRecord(
            trigger_condition = "when user asks for battery optimization",
            steps = listOf("check thermal status", "adjust background limits"),
            known_pitfalls = listOf("do not kill foreground service"),
            success_rate = 0.95f,
            version = 1,
            deprecated = false
        )
        val serialized = json.encodeToString(procedure)
        val deserialized = json.decodeFromString<ProcedureRecord>(serialized)
        assertEquals(procedure, deserialized)
    }
}
