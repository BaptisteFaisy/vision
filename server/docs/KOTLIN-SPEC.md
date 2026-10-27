# SPEC — app Android native Kotlin (sam3-vision)

Remplace l'app Expo (conservée en legacy dans `legacy-expo-app/`). L'app Kotlin vit désormais à la racine du dépôt vision (`app/`, `core/`).
Phone = client unique : pas de Modal (fermé), pas de backend à nous. Le LLM passe directement à
**InferHub** (`https://api.inferhub.dev/v1`, compatible OpenAI), la segmentation SAM 3.1 passe par un
**endpoint HTTP optionnel** (URL saisie dans Réglages ; contrat identique à l'ancien backend Modal).

## Contraintes globales

- Kotlin 2.2.x, AGP 9.2.1 ou 8.11.1 (déjà dans `~/.gradle/caches`), Compose, minSdk 26, compileSdk 35.
- Android SDK : `~/Android/Sdk` (plates-formes 34/35/36, build-tools 34→37). JDK : Temurin 17 dans
  `~/.gradle/jdks` (le `java` système 21 est un JRE sans `javac`).
- Wrapper Gradle : récupérer celui qui a déjà téléchargé une distribution (`gradle-9.4.1-bin` ou
  `gradle-8.14.1-bin` dans `~/.gradle/wrapper/dists`) — ne pas télécharger une 3e distribution.
- Fichier < 500 lignes, fonction < 50 lignes, zéro duplication (un seul client HTTP, un seul mapper JSON).
- Aucune clé API en dur, jamais commitée. Clé lue depuis `local.properties` (gitignoré) OU saisie dans
  l'écran Réglages et stockée chiffrée (`EncryptedSharedPreferences` / Keystore).
- `./gradlew assembleDebug` doit passer sans Android Studio.

## Modules

- `:core` — domaine + réseau (pur, testable JVM) : modèles, clients InferHub et SAM, décodage de masques,
  stockage des réglages, cas d'usage (`AnalyzeUseCase`, `AskUseCase`).
- `:app` — Compose + CameraX : écrans, navigation, `ViewModel`, permissions, rendu des overlays.

## API publique de `:core` (signature exacte — `:app` code contre elle)

```kotlin
package dev.sam3vision.core.model
data class SegObject(val label: String, val score: Float, val box: List<Float>, val maskPngB64: String)
data class SegmentResult(val width: Int, val height: Int, val objects: List<SegObject>)
data class Analysis(val description: String, val tags: List<String>, val objects: List<SegObject>)
data class ChatTurn(val role: Role, val content: String)          // Role.USER | Role.ASSISTANT
enum class Role { USER, ASSISTANT }

package dev.sam3vision.core.net
interface LlmClient {                                             // InferHub, OpenAI-compatible
    suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis>
    suspend fun ask(jpegB64: String, question: String, history: List<ChatTurn>): Result<String>
}
interface SamClient {                                             // endpoint SAM (optionnel)
    suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<SegmentResult>
    suspend fun health(): Result<Boolean>
}

package dev.sam3vision.core.mask
fun decodeMaskPng(maskPngB64: String): Bitmap?                    // gris 8 bits → Bitmap
fun colorizeMask(src: Bitmap, argb: Int, alpha: Int): Bitmap       // overlay coloré

package dev.sam3vision.core.settings
data class Settings(
    val llmBaseUrl: String, val llmApiKey: String, val llmModel: String,
    val samBaseUrl: String,                                        // vide = segmentation désactivée
)
interface SettingsStore { suspend fun load(): Settings; suspend fun save(s: Settings) }
```

Valeurs par défaut : `llmBaseUrl = https://api.inferhub.dev/v1`, `llmModel = ali/qwen3.8-omni-flash`
(modèle vision déjà utilisé sur cette machine), `llmApiKey = ""`, `samBaseUrl = ""`.

## Contrat HTTP SAM (inchangé, celui de l'ancien backend Modal)

`GET /health` → `{"ok":true}` ; `POST /segment` corps `{"image_b64": str, "prompts": [str]|null}` →
`{"width":int,"height":int,"objects":[{"label":str,"score":float,"box":[x1,y1,x2,y2],"mask_b64":str}]}`
avec `mask_b64` = PNG gris 8 bits aux dimensions exactes de l'image, 255 = objet. Erreurs : 400 corps
invalide, 500 interne. Timeout 60 s, `User-Agent` custom, aucune clé.

## Contrat LLM (InferHub)

`POST {llmBaseUrl}/chat/completions`, `Authorization: Bearer <clé>`, corps OpenAI :
`{"model": ..., "messages": [{"role":"system","content":...}, {"role":"user","content":[{"type":"text","text":...},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]}], "temperature":0.2, "max_tokens":800}`
- `describe` : exige un JSON `{"description": str, "tags": [str]}` ; si le modèle renvoie du texte libre,
  on garde le texte brut en description et les labels segmentés en tags (tolérant, jamais d'échec dur).
- `ask` : system court + historique (20 derniers tours) + question + image ; renvoie le texte.
- Erreurs typées : `401` → « clé invalide », `429` → « limite atteinte », timeout → « backend injoignable ».

## Écrans (`:app`)

1. **Capture** : aperçu CameraX + bouton photo, bouton galerie (`ActivityResultContracts.PickVisualMedia`),
   bouton Réglages. Après capture : redimensionnement bord max 1024, JPEG q85, base64 sans préfixe data.
2. **Analyse** : photo + overlays des masques (toggle on/off, couleurs tournantes), liste des objets
   (label + score), description, tags. Si `samBaseUrl` vide : la photo part directement au LLM, sans
   masque, avec un bandeau « Segmentation désactivée — renseigne un endpoint SAM dans Réglages ».
3. **Chat** : fil de discussion sur la photo courante, `ChatTurn` envoyés à chaque question, bulles
   user/assistant, état « en cours… », bouton réessayer.
4. **Réglages** : 4 champs (base URL LLM, clé LLM, modèle LLM, base URL SAM), bouton « Tester le LLM »
   (appel `describe` sur une mini-image 16×16) et « Tester SAM » (`/health`), statut en clair, sauvegarde.

## Vérifications attendues

- `./gradlew :core:testDebugUnitTest` vert (fake HTTP locale, aucun appel réseau réel).
- `./gradlew assembleDebug` produit `app/build/outputs/apk/debug/app-debug.apk`.
- Installation + lancement sur un émulateur (`adb devices` en liste 5) sans crash (logcat propre),
  capture d'écran de l'écran Capture et de l'écran Réglages.
- Test réel du LLM seulement si une clé valide est disponible ; sinon le signaler, ne pas simuler.