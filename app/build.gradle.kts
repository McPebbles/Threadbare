import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing.
 *
 * Two supported sources, checked in this order:
 *   1. keystore.properties in the repo root (local builds, git-ignored)
 *   2. environment variables (CI):
 *        KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
 *
 * If neither is present the release build still runs but produces an
 * *unsigned* APK, so `./gradlew assembleRelease` stays usable for inspection.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val ksFile: String? = signingValue("storeFile", "KEYSTORE_FILE")
val ksPassword: String? = signingValue("storePassword", "KEYSTORE_PASSWORD")
val ksAlias: String? = signingValue("keyAlias", "KEY_ALIAS")
val ksKeyPassword: String? = signingValue("keyPassword", "KEY_PASSWORD")

val canSignRelease: Boolean = !ksFile.isNullOrBlank() &&
    !ksPassword.isNullOrBlank() &&
    !ksAlias.isNullOrBlank() &&
    !ksKeyPassword.isNullOrBlank()

android {
    namespace = "com.threadbare.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.threadbare.client"
        minSdk = 30
        targetSdk = 36
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
                // minSdk is 30, so the legacy JAR signature is dead weight.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    dependenciesInfo {
        // Do not embed Google's signed dependency metadata blob in the artifact.
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX only. Nothing here pulls in Google Play services, Firebase,
    // an advertising ID library, or any analytics SDK. There is no networking
    // library either: the app never makes a request of its own.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Unit tests only. UrlRules, Blocklist and CookieSeed are deliberately free
    // of android.* imports so they can be tested on the plain JVM.
    testImplementation("junit:junit:4.13.2")
}
