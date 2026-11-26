import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.secrets)
    alias(libs.plugins.ktlint)
}

// Formatting: the rules are in .editorconfig at the project root, which Android Studio reads too.
// `ktlintCheck` reports, `ktlintFormat` fixes what it can.
ktlint {
    version = libs.versions.ktlint
    // The root build.gradle.kts and settings.gradle.kts too, not only this module's script.
    kotlinScriptAdditionalPaths {
        include(fileTree(rootDir) { include("*.kts") })
    }
}

secrets {
    // The real key lives in local.properties, which Git ignores. The committed defaults
    // file holds a placeholder, so a fresh checkout still builds (the map stays blank).
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "secrets.defaults.properties"
}

room {
    // Committed to Git so future database upgrades can be checked against old versions.
    schemaDirectory("$projectDir/schemas")
}

// The release signing key's location and passwords, from keystore.properties at the project
// root, which Git ignores (see docs/release.md). Not local.properties: the Secrets plugin
// copies every value there into BuildConfig, which would put the passwords inside the APK.
// Without the file the release build still works, unsigned, so a fresh checkout builds.
val releaseSigning: Map<String, String>? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file ->
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
        require(missing.isEmpty()) { "keystore.properties is missing $missing (see docs/release.md)" }
        keys.associateWith { props.getProperty(it).trim() }
    }

android {
    namespace = "com.danielbrog.activitystats"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.danielbrog.activitystats"
        minSdk = 26
        targetSdk = 37
        // Each release needs a higher versionCode than the last, or phones won't take it as an
        // update. versionName is what people see; see docs/release.md.
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Cognito's sign-in page sends the user back to this scheme; AppAuth listens for it.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.danielbrog.activitystats"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed only where keystore.properties exists; elsewhere the APK comes out unsigned.
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        // Java 17: Maps Compose 9 is compiled for it, and Kotlin can't inline its code into
        // an older target. Android supports Java 17 bytecode on every version we run on.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // The sign-in settings from local.properties reach the code as BuildConfig fields.
        buildConfig = true
    }
}

dependencies {
    // Compose rules for ktlint (Modifier parameters, state, ViewModels, previews); see .editorconfig.
    ktlintRuleset(libs.compose.rules.ktlint)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.location)
    implementation(libs.maps.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.appauth)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // MigrationTestHelper: runs real database migrations against the saved schemas.
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
