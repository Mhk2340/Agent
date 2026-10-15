plugins {
    // PROVISIONAL: AGP 8.6.1 pairs with the repository's Gradle 8.7 wrapper and supports compileSdk 34.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // PROVISIONAL: KSP release matched to Kotlin 1.9.22; the roadmap does not pin a processor version.
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

tasks.register("localVerify") {
    group = "verification"
    description = "Layer 1 (Unit) + Layer 2 (Integration) + Android lint + Safety Checks"
    dependsOn(":app:testDebugUnitTest")
    dependsOn(":app:lintDebug")
    dependsOn(":safety:test")
    dependsOn(":app:verifySkillRegistry")
    dependsOn(":app:verifySafetyIntegrity")
}
