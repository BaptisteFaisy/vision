import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// SPIKE — module jetable qui prouve la chaîne « lunettes Meta → SAM → voix ».
// Isolé de :app pour deux raisons :
//   1. mwdat-* déclare minSdk 29 ; à l'époque du spike, :app était en 26 et ne
//      devait pas être relevé pour un SDK encore en developer preview (depuis,
//      :app est passé en 29 avec l'intégration DAT — voir docs/META-GLASSES.md).
//   2. un SDK en preview n'a rien à faire dans l'APK de production.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key) ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.sam3vision.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sam3vision.glasses"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"

        // En Developer Mode, l'attestation n'est pas utilisée : les deux
        // placeholders peuvent rester à "0" (doc DAT Android, § Setup).
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"

        buildConfigField("String", "LLM_BASE_URL", "\"${localProp("llm.base.url")}\"")
        buildConfigField("String", "LLM_API_KEY", "\"${localProp("llm.api.key")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${localProp("llm.model")}\"")
        buildConfigField("String", "TTS_API_KEY", "\"${localProp("tts.api.key")}\"")
        buildConfigField("String", "TTS_VOICE_ID", "\"${localProp("tts.voice.id")}\"")
        buildConfigField("String", "SAM31_ENDPOINT", "\"${localProp("sam31.endpoint")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    // TODO(spike) : mockdevice ne doit pas survivre à un build public.
    implementation(libs.mwdat.mockdevice)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
}
