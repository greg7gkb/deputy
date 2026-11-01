import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.greg7gkb.deputy"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.greg7gkb.deputy"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        // Links Deputy writes into what it creates (D19). An https App Link, verified by
        // /.well-known/assetlinks.json on this host, so calendar apps make it tappable.
        buildConfigField("String", "LINK_HOST", "\"greg7gkb.github.io\"")
        buildConfigField("String", "LINK_PATH", "\"/deputy/activity/\"")
        manifestPlaceholders["linkHost"] = "greg7gkb.github.io"
        manifestPlaceholders["linkPath"] = "/deputy/activity/"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":broker"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
