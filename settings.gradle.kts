pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deputy"

// The reference monitor. Pure Kotlin/JVM, no Android imports (docs/decisions.md D1).
include(":broker")
// The Android app: Keyring, Activity, adapters, and the host that runs the broker.
include(":app")
