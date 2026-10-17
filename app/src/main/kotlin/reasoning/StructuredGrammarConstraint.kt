package reasoning

import contracts.StructuredCognitiveCall
import kotlinx.serialization.json.Json

/**
 * GEN-NEW-E — Structured Output Grammar Constraint
 *
 * The Phase H CPU JNI backend applies the exported GBNF at decoding time for the frozen cognitive-call schema.
 * JSON parsing remains a defensive post-generation check; unsupported schemas are rejected before JNI.
 */
object StructuredGrammarConstraint {

    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Canonical GBNF Grammar rule definition for llama.cpp Constrained Decoding
     * Enforces JSON structure at token sampling time.
     */
    // PROVISIONAL - pending GEN-PERF-1
    val gbnfGrammarDefinition: String = """
        root ::= "{" ws "\"intent\"" ws ":" ws string "," ws "\"goal\"" ws ":" ws string "," ws "\"plan\"" ws ":" ws array "," ws "\"needs_tool\"" ws ":" ws boolean optional-fields "," ws "\"confidence\"" ws ":" ws number ws "}"
        optional-fields ::= ("," ws "\"tool_name\"" ws ":" ws (string | "null"))? ("," ws "\"tool_params\"" ws ":" ws object)?
        ws ::= [ \t\n\r]*
        boolean ::= "true" | "false"
        number ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
        string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
        array ::= "[" ws (string (ws "," ws string)*)? ws "]"
        object ::= "{" ws (string ws ":" ws string (ws "," ws string ws ":" ws string)*)? ws "}"
    """.trimIndent()

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
