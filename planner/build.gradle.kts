import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The cloud planner. Pure Kotlin/JVM so it can be tested on a laptop against the real API.
// It depends on the broker only through AgentPort: it proposes, the broker decides.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":broker"))
    api(libs.anthropic.java)

    testImplementation(kotlin("test"))
    testImplementation(project(":reader"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Live API tests run only on request: ./gradlew :planner:test -PliveApi
// They read the key from local.properties (gitignored) and cost a few cents.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

tasks.test {
    useJUnitPlatform()
    if (project.hasProperty("liveApi")) {
        systemProperty("deputy.liveApi", "true")
        environment("ANTHROPIC_API_KEY", localProps.getProperty("anthropic.apiKey", ""))
    }
}
