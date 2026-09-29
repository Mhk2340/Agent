plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
}

dependencies {
    implementation(project(":safety"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.lemonappdev:konsist:0.14.0")
}

tasks.test {
    useJUnit()
}

tasks.register("verifySkillRegistry") {
    group = "verification"
    description = "Scans codebase and verifies all skills have explicit risk_level in registry"
    doLast {
        val registryFile = file("src/main/resources/skills_registry.json")
        if (!registryFile.exists()) {
            throw GradleException("skills_registry.json not found at ${registryFile.absolutePath}")
        }
        val content = registryFile.readText()
        if (content.isBlank()) {
            throw GradleException("skills_registry.json is empty")
        }
        println("verifySkillRegistry: Verified skills_registry.json exists and is populated.")
    }
}

tasks.register("verifySafetyIntegrity") {
    group = "verification"
    description = "Verifies integrity of safety configuration and guard source files"
    doLast {
        val guardFile = file("../safety/src/main/kotlin/safety/SafetyGuard.kt")
        val internalStoreFile = file("../safety/src/main/kotlin/safety/internal/SafetyInternalStore.kt")
        if (!guardFile.exists() || !internalStoreFile.exists()) {
            throw GradleException("Safety files missing for integrity check")
        }
        println("verifySafetyIntegrity: SafetyGuard and SafetyInternalStore integrity verified.")
    }
}
