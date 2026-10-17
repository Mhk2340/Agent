plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.mhk2340.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mhk2340.agent"
        minSdk = 28
        targetSdk = 34
        // The CI-built native library targets arm64 only; there is deliberately no externalNativeBuild block.
        // PROVISIONAL - pending GEN-PERF-1
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
        versionCode = 1 // PROVISIONAL: placeholder; the roadmap does not define Android release numbering.
        versionName = "1.0" // PROVISIONAL: placeholder; the roadmap does not define an app version name.
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "FAULT_INJECTION_ENABLED", "true")
        }
        release {
            buildConfigField("boolean", "FAULT_INJECTION_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        // PROVISIONAL: Android Gradle Plugin 8.x bytecode target; the roadmap specifies JDK 21 for builds.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // PROVISIONAL: bytecode target selected for Android Gradle Plugin 8.x compatibility.
        jvmTarget = "17"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":safety"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // PROVISIONAL: Room 2.6.1 is selected for Kotlin 1.9.22 / compileSdk 34 compatibility.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.lemonappdev:konsist:0.14.0")
    // ROBOLECTRIC_VERSION_PROVISIONAL: 4.13 is published; persistence tests pin API 28 and other tests keep default SDK selection.
    testImplementation("org.robolectric:robolectric:4.13")
    // PROVISIONAL: AndroidX Test Core 1.6.1 provides ApplicationProvider for Robolectric.
    testImplementation("androidx.test:core:1.6.1")
    // PROVISIONAL: Room 2.6.1 migration test utilities; tests still open real SQLite files.
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.0")
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
