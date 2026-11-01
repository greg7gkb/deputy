import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The quarantined side of the dual-model split (docs/decisions.md D25, D26). It reads untrusted
// content into typed slots and holds no authority: it depends on the broker for the slot types
// only, and gets no port, no adapter and no network. Pure Kotlin/JVM, like the broker.
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

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
