package skills

import safety.RiskLevel

/**
 * GEN-NEW-CONFIRM — Precise Confirmation Prompt Generator
 *
 * MANDATORY REQUIREMENT:
 * Generates an explicit, non-generic confirmation message detailing the exact recipient,
 * arguments, and impact derived from GEN-350 simulation.
 * Rejects vague prompts like "Are you sure?".
 * Implements the philosophy: "High-stakes / irreversible action = explicit user confirmation".
 */
class ConfirmationPromptGenerator {

    data class ConfirmationPrompt(
        val promptText: String,
        val requiresExplicitToken: Boolean,
        val riskLevel: RiskLevel,
        val targetSummary: String
    )

    fun generatePrompt(
        request: SkillExecutionRequest,
        simulation: ActionSimulationResult
    ): ConfirmationPrompt {
        val recipient = simulation.targetRecipient ?: request.params["recipient"] ?: "external recipient"
        val messageBody = request.params["message"] ?: request.params["text"] ?: request.params["body"]
        val targetPath = request.params["file_path"] ?: request.params["path"]

        val text = buildString {
            when {
                request.skillName.contains("message", ignoreCase = true) ||
                request.skillName.contains("email", ignoreCase = true) ||
                request.skillName.contains("sms", ignoreCase = true) -> {
                    append("ارسال پیام به [")
                    append(recipient)
                    append("] با محتوای: '")
                    append(messageBody ?: request.params.toString())
                    append("' — آیا تأیید می‌کنید؟")
                }
                request.skillName.contains("delete", ignoreCase = true) ||
                request.skillName.contains("remove", ignoreCase = true) -> {
                    append("حذف دائمی فایل/منبع در مسیر [")
                    append(targetPath ?: request.params.toString())
                    append("] — آیا تأیید می‌کنید؟")
                }
                else -> {
                    append("اجرای اقدام پرریسک [")
                    append(request.skillName)
                    append("] با پارامترهای ")
                    append(request.params.toString())
                    append(" — ")
                    append(simulation.simulatedImpact)
                    append(" — آیا تأیید می‌کنید؟")
                }
            }
        }

        return ConfirmationPrompt(
            promptText = text,
            requiresExplicitToken = simulation.resolvedRiskLevel == RiskLevel.LEVEL_3,
            riskLevel = simulation.resolvedRiskLevel,
            targetSummary = simulation.detailedSummary
        )
    }
}
