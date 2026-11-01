import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets live in local.properties (gitignored), never in the repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.greg7gkb.deputy"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.greg7gkb.deputy"
        minSdk = 29
        targetSdk = 37
        // Versioning (CLAUDE.md): bump versionName for each milestone and versionCode on every
        // build installed on a phone (Android refuses an update with a lower code). Tag the commit vX.Y.
        versionCode = 6
        versionName = "0.3"

        // Links Deputy writes into what it creates (D19). An https App Link, verified by
        // /.well-known/assetlinks.json on this host, so calendar apps make it tappable.
        buildConfigField("String", "LINK_HOST", "\"greg7gkb.github.io\"")
        buildConfigField("String", "LINK_PATH", "\"/deputy/activity/\"")
        manifestPlaceholders["linkHost"] = "greg7gkb.github.io"
        manifestPlaceholders["linkPath"] = "/deputy/activity/"

        // Holds and invites are written only to a calendar with this display name (D22). Reading
        // busy times still covers every visible calendar, so availability stays real.
        val writeCalendar = localProps.getProperty("deputy.writeCalendar", "Deputy sandbox")
        buildConfigField("String", "WRITE_CALENDAR", "\"$writeCalendar\"")

        // Mail is read only from the Gmail label with this display name (D25). Google can't scope
        // access to a label, so this is Deputy's own restriction, enforced in GmailAdapter (D9).
        val mailLabel = localProps.getProperty("deputy.mailLabel", "Deputy")
        buildConfigField("String", "MAIL_LABEL", "\"$mailLabel\"")

        // The cloud planner's key (D20). Empty or the placeholder means "no planner configured".
        val apiKey = localProps.getProperty("anthropic.apiKey", "").takeUnless { it.startsWith("REPLACE_") }.orEmpty()
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$apiKey\"")
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
    implementation(project(":planner"))
    implementation(project(":reader"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.auth)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
