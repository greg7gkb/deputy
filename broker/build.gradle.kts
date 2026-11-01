import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The broker is the reference monitor: deterministic, no LLM, no Android.
// Keep dependencies to the Kotlin stdlib and kotlinx so it can go multiplatform later.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)

    testImplementation(kotlin("test"))
    // The mail tests run the real rule-based reader and its sample inbox. Test scope only: the
    // broker itself never depends on a reader implementation.
    testImplementation(project(":reader"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
