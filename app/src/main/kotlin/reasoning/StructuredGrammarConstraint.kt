package reasoning

import contracts.StructuredCognitiveCall
import kotlinx.serialization.json.Json

/**
 * GEN-NEW-E — Structured Output Grammar Constraint
 *
 * PROVISIONAL - pending on-device llama.cpp / MediaPipe GBNF Grammar integration.
 * In a native ARM64 on-device environment with llama.cpp, this constraint is enforced at
 * decoding time (Constrained Sampling via GBNF grammar rules) to structurally prevent invalid tokens.
 *
 * IN CURRENT SANDBOX (x86_64 JVM without native llama.cpp library):
 * 1. Exports GBNF grammar specification & JSON Schema for the native backend.
 * 2. Implements strict schema validation and recovery rejection for model outputs.
 */
object StructuredGrammarConstraint {

    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Canonical GBNF Grammar rule definition for llama.cpp Constrained Decoding
     * Enforces JSON structure at token sampling time.
     */
    val gbnfGrammarDefinition: String = buildString {
        append("root ::= \"{\" ws \"\\\"intent\\\":\" ws string \",\" ws \"\\\"goal\\\":\" ws string \",\" ws \"\\\"plan\\\":\" ws array \",\" ws \"\\\"needs_tool\\\":\" ws boolean \",\" ws \"\\\"tool_name\\\":\" ws (string | \"null\") \",\" ws \"\\\"tool_params\\\":\" ws object \",\" ws \"\\\"confidence\\\":\" ws number ws \"}\"\n")
        append("ws ::= [ \\t\\n\\r]*\n")
        append("number ::= [0-9]+ (\".\" [0-9]+)?\n")
        append("boolean ::= \"true\" | \"false\"\n")
        append("string ::= \"\\\"\" [^\"\\\\]* \"\\\"\"\n")
        append("array ::= \"[\" ws (string (ws \",\" ws string)*)? ws \"]\"\n")
        append("object ::= \"{\" ws (string ws \":\" ws string (ws \",\" ws string ws \":\" ws string)*)? ws \"}\"")
    }

    /**
     * Canonical JSON Schema representing Contract #3:
     * { intent, goal, plan[], needs_tool, tool_name, tool_params, confidence }
     */
    val cognitiveCallSchemaJson: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "StructuredCognitiveCall",
          "type": "object",
          "properties": {
            "intent": { "type": "string" },
            "goal": { "type": "string" },
            "plan": { "type": "array", "items": { "type": "string" } },
            "needs_tool": { "type": "boolean" },
            "tool_name": { "type": ["string", "null"] },
            "tool_params": { "type": "object", "additionalProperties": { "type": "string" } },
            "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 }
          },
          "required": ["intent", "goal", "plan", "needs_tool", "confidence"]
        }
    """.trimIndent()

    fun validateAndParse(rawOutput: String): Result<StructuredCognitiveCall> {
        return try {
            val parsed = json.decodeFromString<StructuredCognitiveCall>(rawOutput)
            require(parsed.confidence in 0.0f..1.0f) { "Confidence must be within [0.0, 1.0]" }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Grammar constraint violation: Output is not valid Contract #3 JSON. Detail: ${e.message}", e))
        }
    }
}
