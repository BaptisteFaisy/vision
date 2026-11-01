import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.concurrent.TimeUnit

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key) ?: ""

/** Backend Valhalla public (Modal CPU, tuiles IdF) — pas un secret. */
val routeBaseUrlDefault = "https://baptiste-faisy--valhalla-idf-cpu-valhallarouter-web.us-east.modal.run"

/** Caractères admis dans une valeur venue de git : le manifeste reste sûr. */
val gitTokenChars = Regex("[^A-Za-z0-9._-]")

/** Une seule ligne : la première non vide, trimée (une sortie multi-lignes ne passe pas). */
fun firstLine(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * Première ligne de `git <args>` lancé dans la racine du projet, trimée.
 *
 * Rend `""` — jamais un message d'erreur — quand git est absent **ou** quand il sort
 * avec un code non nul : `runCatching` ne voit que les exceptions, pas un `exec` qui
 * répond 128 (« not a git repository », « bad revision ») sans rien lever. stderr est
 * jeté au lieu d'être fusionné dans stdout, et le code de retour est contrôlé
 * explicitement ; `waitFor` borné pour qu'un git qui attend (identifiants, pager)
 * ne bloque pas la configuration.
 */
fun gitValue(vararg args: String): String = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val finished = process.waitFor(10, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    if (!finished || process.exitValue() != 0) "" else firstLine(output)
}.getOrDefault("")

/** Valeur git réduite à `[A-Za-z0-9._-]` : c'est l'échappement de `versionName`. */
fun gitToken(raw: String, fallback: String): String = raw.replace(gitTokenChars, "").ifBlank { fallback }

/** Entier strictement positif, sinon `null` (une sortie vide ou bruitée ne devient pas un code). */
fun gitInt(raw: String): Int? = raw.toIntOrNull()?.takeIf { it > 0 }

/** versionCode de repli quand git n'est pas exploitable (build depuis un tarball). */
val gitlessVersionCode = 1

/**
 * Date du commit HEAD (secondes) : croissante, donc utilisable comme versionCode.
 * Ordre : `SAM_VERSION_CODE` (CI, tarball) → git → [gitlessVersionCode].
 * Hors dépôt git la valeur retombe à 1 : c'est une build de test, qu'Android refuse
 * d'installer par-dessus une build issue de git (INSTALL_FAILED_VERSION_DOWNGRADE) —
 * d'où l'avertissement, et la variable pour forcer le code attendu.
 */
val gitEnvVersionCode: Int? = gitInt(System.getenv("SAM_VERSION_CODE")?.trim().orEmpty())
val gitCommitSeconds: Int = gitEnvVersionCode
    ?: gitInt(gitValue("log", "-1", "--format=%ct"))
    ?: gitlessVersionCode
if (gitEnvVersionCode == null && gitCommitSeconds == gitlessVersionCode) {
    logger.warn(
        "gitValue : git indisponible (aucun dépôt git ou commande en échec) — " +
            "versionCode = $gitlessVersionCode, versionName sans empreinte. " +
            "Pour installer par-dessus une build issue de git : SAM_VERSION_CODE=<versionCode installé>.",
    )
}

/** Nombre de commits et empreinte courte : lisibles dans « À propos » et les logs. */
val gitCommitCount: Int = gitInt(gitValue("rev-list", "--count", "HEAD")) ?: 0
val gitShortSha: String = gitToken(gitValue("rev-parse", "--short=7", "HEAD"), "nogit")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.sam3vision.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sam3vision.app"
        minSdk = 29
        targetSdk = 36
        // versionCode = date du commit HEAD : Android REFUSE d'installer une build plus
        // ancienne (INSTALL_FAILED_VERSION_DOWNGRADE), donc un APK périmé ne peut plus
        // écraser la version du téléphone. Une PR bâtie sur un master plus vieux est
        // rejetée : rebâtir sur origin/master (règle user du 2026-10-26).
        versionCode = gitCommitSeconds
        versionName = "1.0.$gitCommitCount-$gitShortSha"

        // Device Access Toolkit Meta : « 0 »/« 0 » = Developer Mode, sans attestation.
        // Hors Developer Mode, mettre l'Application ID et le Client Token du Wearables
        // Developer Center dans local.properties (mwdat.application.id / mwdat.client.token).
        manifestPlaceholders["mwdat_application_id"] = localProp("mwdat.application.id").ifBlank { "0" }
        manifestPlaceholders["mwdat_client_token"] = localProp("mwdat.client.token").ifBlank { "0" }

        // Valeurs lues depuis local.properties (jamais commité) ; vides si absent.
        // Trio LLM : partiel (1-2/3) = BuildConfig silencieusement inutilisable,
        // donc erreur claire au lieu d'un APK qui semble configuré ; vide = APK
        // sans clé (saisie dans Réglages), le défaut assumé — sauf SAM_REQUIRE_KEYS=1.
        val llmBase = localProp("llm.base.url")
        val llmKey = localProp("llm.api.key")
        val llmModel = localProp("llm.model")
        val llmKeysSet = listOf(llmBase, llmKey, llmModel).count { it.isNotBlank() }
        if (llmKeysSet in 1..2) {
            error("clés LLM incomplètes ($llmKeysSet/3 dans local.properties : " +
                "llm.base.url, llm.api.key, llm.model se posent ensemble) — " +
                "un BuildConfig partiel ne répondrait jamais.")
        }
        if (llmKeysSet == 0 && System.getenv("SAM_REQUIRE_KEYS") == "1") {
            error("SAM_REQUIRE_KEYS=1 mais aucune clé LLM dans local.properties " +
                "(llm.base.url, llm.api.key, llm.model) — voir docs/CLES-DU-BUILD.md.")
        }
        if (llmKeysSet == 0) {
            logger.warn("APK sans clé LLM : questions et narration exigeront une saisie " +
                "dans Réglages (SAM_REQUIRE_KEYS=1 refuse ce build).")
        }
        buildConfigField("String", "LLM_BASE_URL", "\"$llmBase\"")
        buildConfigField("String", "LLM_API_KEY", "\"$llmKey\"")
        buildConfigField("String", "LLM_MODEL", "\"$llmModel\"")
        // Voix ElevenLabs : défauts de debug, la saisie dans Réglages prime.
        buildConfigField("String", "TTS_API_KEY", "\"${localProp("tts.api.key")}\"")
        buildConfigField("String", "TTS_VOICE_ID", "\"${localProp("tts.voice.id")}\"")
        // Dictée Meta Muse (facultative : sans clé, repli ElevenLabs Scribe avec la clé ci-dessus).
        buildConfigField("String", "ASR_API_KEY", "\"${localProp("asr.api.key")}\"")
        // Narration du Direct : clé dédiée (le modèle des questions ne narre pas).
        buildConfigField("String", "LIVE_LLM_API_KEY", "\"${localProp("llm.live.api.key")}\"")
        // Clé Google transmise au build par secret CI ou local.properties gitignoré.
        val mapsKey = localProp("google.maps.api.key").ifBlank { System.getenv("GOOGLE_MAPS_API_KEY").orEmpty() }
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$mapsKey\"")
        // Tap court : endpoint vidéo SAM 3.1. Vide → écran « Envoi désactivé » + tap refusé
        // avec la raison explicite (TapConfig.sendBlockedReason), aucun enregistrement inutile.
        // Renseigner : sam31.endpoint=https://<app sam3-eu-a100>. Si le serveur n'expose pas
        // POST /video (404 de route manquante), l'envoi bascule sur POST /segment avec la
        // première frame du clip (TapPhotoFallback) au lieu d'échouer.
        buildConfigField("String", "SAM31_ENDPOINT", "\"${localProp("sam31.endpoint")}\"")
        // Direct : base de l'app Modal sam3-eu-a100 (/live).
        buildConfigField("String", "SAM_BASE_URL", "\"${localProp("sam.base.url")}\"")
        // Endpoint de la région US (mode US des Réglages) : app Modal déployée en us-east.
        buildConfigField("String", "SAM_US_BASE_URL", "\"${localProp("sam.us.base.url")}\"")
        // Routeur Valhalla (guidage vocal piéton) : backend Modal CPU (tuiles
        // IdF), public, sans Tailscale. `route.base.url` prime ; sinon le
        // défaut ci-dessous. Réglages prime sur le build ; sans URL
        // joignable, Google Maps reste le guidage.
        buildConfigField("String", "ROUTE_BASE_URL", "\"${localProp("route.base.url").ifBlank { routeBaseUrlDefault }}\"")
        // Calque local (propagation entre deux keyframes) : vide = le réglage des Réglages
        // décide ; « true »/« false » = drapeau de build prioritaire (démo, tests).
        buildConfigField("String", "LOCAL_OVERLAY", "\"${localProp("live.local.overlay")}\"")
        // Transport WS texte forcé (jambe base64 du A/B M1, docs/LATENCE-M1-HARNESS.md) :
        // vide (défaut) = binaire M1 ; « true » = JSON texte même si le serveur lit le
        // binaire. Drapeau de campagne, jamais commité (local.properties gitignoré).
        buildConfigField("String", "WS_TEXT", "\"${localProp("live.ws.text")}\"")

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

    // Tests JVM du module app : `Log`, `SystemClock` et `Bitmap` rendent leur valeur par
    // défaut au lieu de lever « not mocked » — les sondes iSee se testent sans appareil.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// La WebView utilise la même interface que le site, livrée avec chaque APK.
val caregiverAssets = tasks.register<Sync>("caregiverAssets") {
    from(rootProject.file("suivi-parent")) {
        include("*.html", "*.css", "*.mjs", "*.png")
        into("caregiver")
    }
    into(layout.buildDirectory.dir("generated/caregiver-assets"))
}
android.sourceSets.getByName("main").assets.srcDir(caregiverAssets)
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(caregiverAssets) }

// Room : le schéma JSON est exporté (artifact du module de nettoyage), jamais régénéré à la main.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Pinning explicite du Fragment transitif (>= 1.3.0, lint) : aucun import direct.
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Pinning explicite du Guava transitif de Room (ListenableFuture) : aucun import direct.
    implementation(libs.guava)
// Encodage MP3 pur Java (port LAME) : la voix du micro est archivée en cache.
    implementation(libs.jump3r)
    // Lunettes Ray-Ban Meta : Device Access Toolkit (developer preview, Maven Central).
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.inputs)
    // Orientation tête (Motion bêta, même pin 1.0.0 : le runtime lunettes 1.0.0.0.0 coupe les sessions 1.0.1).
    implementation(libs.mwdat.motion)
    // Mock Device Kit (lunettes simulées) : build debug seulement, hors de l'APK public.
    debugImplementation(libs.mwdat.mockdevice)
    implementation(libs.kotlinx.coroutines.android)
    // Profondeur embarquée : DA2 Small (ONNX), seul runtime embarqué restant.
    // Carte du guidage vocal : MapLibre Native (MapView classique, Maven Central,
    // BSD-2) + tuiles raster OSM France gratuites sans clé (guidemap/GuideMapStyle).
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    testImplementation(libs.junit)
    // org.json réel pour les tests JVM du banc lunettes (android.jar répondrait « not mocked »).
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
}
