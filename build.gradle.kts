plugins {
    kotlin("jvm") version "1.9.22" apply false
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

tasks.register("localVerify") {
    group = "verification"
    description = "Layer 1 (Unit) + Layer 2 (Integration) + Consistency Checks + Safety Checksums"
    dependsOn(subprojects.map { "${it.path}:test" })
    dependsOn(":app:verifySkillRegistry")
    dependsOn(":app:verifySafetyIntegrity")
}
