package architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Konsist Architectural Boundary Test
 * Verifies that no external or non-user classes directly access internal safety DAO/Store.
 * "هیچ یال وابستگی از :learning، :evolution، یا :multiagent به‌طور مستقیم به :safety.internal وجود ندارد"
 */
class SafetyArchitectureKonsistTest {

    @Test
    fun `classes outside safety should never access safety internal package`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { !it.hasPackage("safety..") }
            .assertTrue { file ->
                file.imports.none { importDirective ->
                    importDirective.name.startsWith("safety.internal")
                }
            }
    }
}
