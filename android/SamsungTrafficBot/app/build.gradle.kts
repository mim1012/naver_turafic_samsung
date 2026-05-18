import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

fun localProp(key: String, default: String = "") =
    (localProps.getProperty(key) ?: default).trim()

fun localOrEnv(propKey: String, envKey: String, default: String = "") =
    (localProps.getProperty(propKey) ?: System.getenv(envKey) ?: default).trim()

val releaseStoreFile = localOrEnv("release.store.file", "ANDROID_RELEASE_STORE_FILE")
val releaseStorePassword = localOrEnv("release.store.password", "ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localOrEnv("release.key.alias", "ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = localOrEnv("release.key.password", "ANDROID_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured =
    releaseStoreFile.isNotBlank() &&
        releaseStorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank()
val releaseSigningRequested =
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

android {
    namespace = "com.navertraffic.samsung"
    compileSdk = 34

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.navertraffic.samsung"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEBUG_DEVICE_NAME", "\"${localProp("debug.device.name", "z1-1")}\"")
        buildConfigField("int",    "DEBUG_LOOP_COUNT",  localProp("debug.loop.count", "10"))
        buildConfigField("String", "DEBUG_NAVER_ID",    "\"${localProp("debug.naver.id")}\"")
        buildConfigField("String", "DEBUG_NAVER_PW",    "\"${localProp("debug.naver.pw")}\"")
        buildConfigField("String", "SUPABASE_URL",         "\"${localProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_KEY",         "\"${localProp("supabase.key")}\"")
        buildConfigField("String", "DEFAULT_SERVER_URL",   "\"${localProp("server.url", "https://www.sellermate.ai.kr")}\"")
        buildConfigField("String", "DEVICE_API_TOKEN",     "\"${localProp("android.device.api.token")}\"")
        buildConfigField("int",    "ROTATE_EVERY",         localProp("rotate.every", "5"))
        buildConfigField("int",    "ROTATION_DRAIN_WAIT_SEC", localProp("rotation.drain.wait.sec", "90"))
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            } else if (releaseSigningRequested) {
                throw GradleException(
                    "Release signing is required. Set release.store.file, release.store.password, release.key.alias, and release.key.password in local.properties.",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
