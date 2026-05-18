import java.util.Properties

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

    buildTypes {
        release {
            isMinifyEnabled = false
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
