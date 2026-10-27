# ARCHITECTURE — iSee

Ce document décrit **l'arbre de ce dépôt**, relu dans le code le **27/10/2026** (comptes
recomptés ce jour-là, après l'entrée du banc lunettes dans `:app` ; première rédaction le 26/10
après 19 h). Le service SAM 3.1 (GPU) vit dans un dépôt séparé, non
public : rien de ce qui est serveur n'est vérifiable d'ici. Chaque chiffre ci-dessous est soit
une constante du code (fichier + ligne cités), soit un comptage reproductible (commande
donnée). Les mesures de temps et de débit sont dans `docs/PERF.md`, jamais ici.

## 1. Deux modules, leurs comptes réels

| Module | Type Gradle | Namespace / ID | minSdk | `src/main` | `src/test` | `src/androidTest` |
|---|---|---|---|---|---|---|
| `:core` | `com.android.library` | `dev.sam3vision.core` | 26 | **105** fichiers | 126 fichiers / **907** `@Test` | — |
| `:app` | `com.android.application` | `dev.sam3vision.app` | 29 | **165** fichiers | 71 fichiers / **466** `@Test` | 2 fichiers / **7** tests |
| `:app` → `glasses/bench/` | sous-dossier, **pas un module** | `dev.sam3vision.app.glasses.bench` | 29 | **18** fichiers | 9 fichiers / **84** `@Test` | 1 fichier / **1** test |

La dernière ligne compte un sous-dossier de `:app`, déjà comprise dans la ligne `:app` : le banc
d'essai lunettes (l'ancien module séparé) n'est plus un module, il vit dans l'APK d'iSee.

Les déclarations sont dans `core/build.gradle.kts:9,10,13` et `app/build.gradle.kts:79,80,84` ;
`settings.gradle.kts` n'inclut que `:core` et `:app`, et `compileSdk = 36` pour les deux. Le
dossier de l'ancien module (37 fichiers suivis, dont 30 `.kt`) est encore **sur le disque**, mais
aucun build ne l'inclut : il ne sert plus que de référence en lecture.

```bash
find core/src/main -name '*.kt' | wc -l            # 105
find core/src/test -name '*.kt' | wc -l            # 126
find app/src/main -name '*.kt' | wc -l             # 165
find app/src/test -name '*.kt' | wc -l             # 71
find app/src/main/java/dev/sam3vision/app/glasses/bench -name '*.kt' | wc -l        # 18
find app/src/test/java/dev/sam3vision/app/glasses/bench -name '*.kt' | wc -l        # 9
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench | wc -l        # 84
find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' | wc -l          # 503
find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' -exec cat {} + | wc -l  # 54 551
```

Les `@Test` se comptent au `grep -rho` : le poste `pc` n'a pas `rg` dans son `PATH`, et un
`rg` absent rend **0** sans le dire (piège à connaître avant de conclure « aucun test »).

- `:core` est une **bibliothèque Android**, pas un module JVM. Sur ses 105 fichiers `main`,
  **4** importent `android.*` : `mask/Masks.kt`, `settings/EncryptedSettingsStore.kt`,
  `settings/SettingsCipher.kt`, `settings/SettingsStoreFactory.kt`. Tout le reste — réseau,
  gestes, cadence, distances, masques, texte, prédiction — est du Kotlin pur, testé sur la JVM.
- Les 165 fichiers de `:app` comptent un **stub** hors du paquet applicatif :
  `app/src/main/java/javax/sound/sampled/AudioFormat.kt`, surface minimale de
  `javax.sound.sampled` pour que le port Java de LAME (`jump3r`) encode du MP3 sur Android.
- Le détail des tests (par module et par domaine) est dans `docs/TESTING.md`.

## 2. Le flux réel

### 2.0 La racine, l'accueil et les gestes

`app/src/main/AndroidManifest.xml` déclare une **activité unique** (`MainActivity`) et une
`Application` maison (`android:name=".SamApp"`). `MainActivity.buildRoot` installe
`IseeRootLayout` (un `FrameLayout` qui intercepte le toucher) et lui pose une `ComposeView`
→ `SamRoot` → `AppNav` (`app/AppNav.kt`). La route d'accueil est `Route.Isee` →
`IseeHome` → **`IseeScenePane`** en plein écran (`app/isee/IseeHome.kt:33`) : bulle de
verre, gros texte paginé, carte caméra qui se dissout.

Gestes, tous câblés dans `MainActivity.buildRoot` :

| Geste | Effet | Code |
|---|---|---|
| tap simple | démarre / arrête l'enregistrement vidéo | `IseeViewModel.onSingleTap` (`app/isee/IseeViewModel.kt:248`) |
| double tap | photo immédiate | `IseeViewModel.onDoubleTap` (`:254`) |
| appui long (300 ms, tolérance 24 px) | dictée micro maintenu | `core/gesture/LongPressTrigger.kt:102,105` |
| balayage horizontal | **reconnu mais sans effet** : `onHorizontalSwipe = Unit` | `app/IseeRootLayout.kt` |

Le **glissement vertical** (changement de bulle) a été supprimé : `IseeViewModel`
n'a plus `onVerticalDrag*`, le rail vertical de `core/gesture/IseeGestureBrain.kt`
verrouille le geste **sans rien émettre** (un glissement ne peut donc jamais passer
pour un tap), et il n'y a aucun moyen de revenir à un message antérieur.

### 2.1 Photo (double tap sur l'accueil)

1. `IseeViewModel.onDoubleTap` → `shots.takePhoto()` (`app/isee/vm/IseePhotoShot.kt`) :
   `media.newPhotoFile()` puis `camera().takePhoto(target)` — c'est `IseeCameraHost`
   (`app/isee/IseeCameraPane.kt`), le pilote CameraX d'iSee (`ImageCapture` +
   `VideoCapture<Recorder>` + `ImageAnalysis`, liés ensemble).
2. Photo écrite → `onSaved` : elle **gèle** le contexte de question (`IseePhotoContext`),
   s'affiche dans la fenêtre, puis s'évapore après un minuteur (`SHOW_MS`, allongé en
   `MEASURED_MS` si des pastilles sont posées).
3. **Pastilles de distance** : `IseePhotoDistances.measureOutcome` envoie la photo au
   `POST {sam}/segment` de `core/net/HttpSamClient` (`prompts = null`), puis le sténopé
   convertit les boîtes en mètres. Un échec est **dit** (`PhotoMeasure.Failed`,
   `NoEndpoint`, `NoObjects`, `Unreadable`), pas avalé.
4. **Aucun appel LLM au double tap** : la photo ne déclenche ni description ni synthèse.
   Le LLM n'intervient qu'à la question suivante (§2.3).

### 2.2 Enregistrement (tap simple) — le direct de la page caméra

1. `IseeViewModel.onSingleTap` → `cameraOps.startVideo()` / `stopVideo()`
   (`app/isee/vm/IseeCameraCoordinator.kt`) → `IseeCameraHost`.
2. **Hors enregistrement, aucune frame n'est convertie** : `host.wantsFrame` vaut
   `recordingVideo && probe.wantsFrame()` (`app/isee/IseeVisionCard.kt:262-265`).
3. Pendant l'enregistrement, `host.onFrame` → `LiveProbe.onFrame` (`app/isee/LiveProbe.kt`) :
   sérialisation JPEG au côté long **320 px** (`ISEE_FRAME_EDGE`,
   `app/isee/IseeCameraPane.kt:39`, qualité 55, `:42`).
4. `core/live/LiveSession` (`Cadence.Fast`) régule les créneaux, puis
   `core/net/WsLiveClient` envoie **un message JSON par frame sur `/live/ws`**, corrélé par
   `id` (une attente par `id`, pas une file FIFO).
5. Réponse → `LiveFrame` (`width`, `height`, `elapsed_ms`, `objects[]`, `mask_encoding`,
   `timings_ms`) : masques décodés et coloriés (`app/live/LiveMaskDecoder.kt`,
   `core/mask/MaskCore.kt` + `core/mask/Masks.kt`), distances calculées **sur le téléphone**
   (`core/distance/DistanceEstimator.kt`).
6. **Voix** : `LiveProbe.phraseOf` → `PositionAnnouncer` (`core/live/PositionAnnouncer.kt`),
   phrase confiée à `ProximityVoice` (`app/isee/ProximityVoice.kt`) → ElevenLabs →
   `SpeechPlayer`. Une phrase par scène ; la bascule des Réglages et la clé ElevenLabs
   conditionnent l'annonce (`Settings.announcePositions`, `core/settings/Settings.kt:131`).

### 2.3 Question (appui long → dictée → LLM → voix)

1. `LongPressTrigger` → `IseeViewModel.onHoldStart/onHoldEnd` → `IseeDictation`
   (`app/isee/vm/IseeDictation.kt`) : PCM 16 bits mono du micro.
2. `core/usecase/DictationUseCase` : PCM → WAV → transcription. Refus **local** si
   l'enregistrement dure moins de 400 ms (`MIN_DURATION_MS`), puis texte vide = « aucune
   parole détectée ».
3. Moteur de dictée : `app/SpeechClients.kt` est la **seule** règle — Meta ASR d'abord
   (`core/net/MetaAsrClient`), repli `core/net/ElevenLabsSttClient` dès qu'une clé de voix
   existe (`FallbackSpeechClient` quand les deux sont configurés).
4. `IseeAnswers` (`app/isee/vm/IseeAnswers.kt`) → `AskUseCase` (`core/usecase/UseCases.kt`)
   → `core/net/InferHubLlmClient.ask` → `POST {llm}/chat/completions`, image = dernière photo
   en data-URL, et **historique toujours vide** : iSee joint `emptyList()` (`IseeAnswers`),
   aucune ligne de la base n'est reconstruite en tour de conversation. Le plafond de
   **20 tours** (`AskUseCase.MAX_HISTORY_TURNS`) ne vaut plus que pour l'écran Direct.
5. Réponse : ligne Room + MP3 (`IseeVoice` → `ElevenLabsTtsClient`), puis lecture
   (`IseePlayback` → `app/SpeechPlayer.kt`).

**Contexte de journal caméra** : le paramètre `context` d'`AskUseCase` n'est alimenté que par
l'écran **Direct** (`app/live/LiveAskSession.kt:82`, `timelineContextOrNull` de
`app/LiveMessages.kt:37`). La question de l'accueil iSee part **sans** contexte.

### 2.4 Accueil classique (route `Capture`) — la chaîne photo + description

C'est ici que segmentation et description LLM s'enchaînent : `CaptureScreen` →
`AppViewModel.onPhoto` → `app/vm/AppAnalysis.kt` → `AnalyzeUseCase` (`core/usecase/UseCases.kt`)
→ `HttpSamClient.segment` (facultatif) puis `InferHubLlmClient.describe`. Un échec SAM ne
bloque rien : `AnalyzeUseCase` décrit quand même l'image, sans objets (`sam?.segment(...)` →
`getOrNull()`, `UseCases.kt:23`). Écrans : `AnalysisScreen`, `ChatScreen`.

### 2.5 Écran Direct (route `Live`)

`app/LiveViewModel.kt` (411 lignes) + `app/LiveScreen.kt` : frames au côté long **512 px**
(`LIVE_EDGE`, `LiveScreen.kt:45`), cadence `Cadence.Direct`, un `LiveClient` obtenu par
`liveClient(base, marks)` (`LiveViewModel.kt:161`). « Poser une question » joint la dernière
frame et le journal de pistes (`LiveAskSession`, §2.3).

### 2.6 Mode tap (vidéo → SAM 3.1)

Route `Tap` → `app/tap/TapCaptureScreen.kt` → `TapVideoSession` → `HttpSam31VideoClient`
(`core/net/HttpSam31VideoClient.kt`) : `POST {sam31}/video` en `multipart/form-data`
(`model`, `video`, `prompt` optionnel). Un drapeau `ON_DEVICE_INFERENCE` (`app/tap/TapConfig.kt:48`, `false`) refuse
l'envoi si l'inférence est locale.

## 3. Le contrat HTTP, endpoint par endpoint

Toutes les URL sont assemblées par `core/net/Http.kt:114` (`joinUrl` : base sans barre finale
+ chemin, une seule barre oblique). Aucune URL de production n'est « officielle » : elle vient
des Réglages, d'un preset de région (`core/settings/SamRegion.kt:21,26`) ou de
`local.properties`.

| Endpoint | Client (fichier:ligne) | Requête | Réponse lue |
|---|---|---|---|
| `GET {sam}/health` | `core/net/HttpLiveClient.kt:64` | — (délai de réveil 180 s) | `ok`, `model`, `device`, `gpu` → `LiveHealth` |
| `POST {sam}/segment` | `core/net/HttpSamClient.kt:22` | `{"image_b64", "prompts"\|null}` | `width`, `height`, `objects[{label,score,box,mask_b64}]` |
| `POST {sam}/live` | `core/net/HttpLiveClient.kt:60` | même corps que la trame WS, **sans `id`** | idem ci-dessous + `elapsed_ms` |
| `WS {sam}/live/ws` | `core/net/WsLiveClient.kt` (`LIVE_WS_PATH`, `WsTransport.kt:35`) | `{"id","image_b64","prompts","work_edge","max_objects","mask_encoding":"bits"}` | même forme que `/live`, **même `id`** ; erreur `{"error":{"status","detail"}}` |
| `POST {sam}/release` | `core/net/SamLifecycle.kt:42` | corps `{}`, en-tête `x-sam-release: confirm` | `{"released":true}` → `ReleaseOutcome` |
| `POST {sam31}/video` | `core/net/HttpSam31VideoClient.kt:67` | multipart : `model=sam3.1-video`, `video` (video/mp4), `prompt` | premier champ trouvé de `text`, `description`, `answer`, `caption` (tronqué à 600 caractères) |
| `POST {llm}/chat/completions` | `core/net/InferHubLlmClient.kt:78` | `{model, messages, temperature 0.2, max_tokens 800\|200}` ; image en `image_url` data-URL | `choices[0].message.content` |
| `POST {elevenlabs}/text-to-speech/{voiceId}?output_format=mp3_44100_128` | `core/net/ElevenLabsTtsClient.kt:55-62` | `{text, model_id, voice_settings}`, en-tête `xi-api-key` | octets MP3 (un corps texte en 2xx = échec) |
| `POST {elevenlabs}/speech-to-text` | `core/net/ElevenLabsSttClient.kt:42` | multipart `model_id` + `file` (audio/wav), en-tête `xi-api-key` | texte de transcription |
| `POST {meta}/asr/transcribe` | `core/net/MetaAsrClient.kt:40` | multipart **2 parts** : `request` (JSON `{"model","audioEncoding":"WAV"}`) + `audio` (audio/wav), en-tête `Authorization: Bearer` | texte de transcription |
| `POST {track}/session`, `POST {track}/frame`, `DELETE {track}/session/{id}`, `GET {track}/health` | `core/net/TrackClient.kt:39-74` | `{image_b64,prompts}` / `{session_id,image_b64}` | session + objets suivis (délai 15 s) |

Précisions qui comptent :

- **`POST /live` n'est plus le chemin des frames.** `liveClient()` (`core/net/LiveClientFactory.kt`)
  ne construit qu'un `WsLiveClient` ; le `HttpLiveClient` qu'il lui passe en délégué ne sert
  qu'à `health()` (`WsLiveClient.kt:82`). `HttpLiveClient.live` n'est donc appelé que par les
  tests.
- **`GET /health` n'est appelé que par le bouton « Tester SAM »** (`app/vm/AppDiagnostics.kt:63`)
  et par la délégation ci-dessus. Aucune sonde périodique.
- **Le tracker est câblé dans `:app`** : l'écran Direct construit `HttpTrackClient` +
  `TrackRunner` quand sa pastille « Tracker » est active (`app/LiveViewModel.kt`, `start()`) ;
  c'est le second transport des frames, à côté de `/live` en WebSocket qui reste le défaut.
  Le débit relevé pour ce chemin est dans `docs/TRACK-PERF.md` (§ 2 : 1,8 ips sur le téléphone,
  lors d'un relevé antérieur — soit à peine mieux que `/live`, le serveur étant le facteur limitant).
- **Il n'existe aucun endpoint `/ask`** : la question part au LLM par `POST {llm}/chat/completions`
  (`grep -rn '"/ask"' core/src/main app/src/main` : rien).
- **Erreurs typées**, un seul barème (`core/net/Http.kt:191-207`) : 401/403 → `InvalidKey`,
  402 → `PaymentRequired`, 409/429 → `RateLimited`, 404 « invalid function call » →
  `BackendStopped` (proxy Modal, pas de relance), sinon `Http`. Les clients renvoient des
  `Result` : un échec n'est jamais une exception qui remonte.
- **Délais** : générique 60 s (`DEFAULT_TIMEOUT_SECONDS`, `Http.kt:28`), frame 10 s
  (`LIVE_TIMEOUT_SECONDS`, `HttpLiveClient.kt:13`), réveil 180 s (`WAKE_TIMEOUT_SECONDS`,
  `:21`), extinction 45 s (`RELEASE_TIMEOUT_SECONDS`, `SamLifecycle.kt:10`), vidéo 120 s
  (`UPLOAD_TIMEOUT_SECONDS`, `HttpSam31VideoClient.kt:69`), tracker 15 s
  (`TRACK_TIMEOUT_SECONDS`, `TrackClient.kt:11`).

## 4. Les clés : d'où elles viennent, où elles vont

**Aucune valeur de clé n'est écrite dans ce dépôt**, ni en clair ni par défaut : `Settings`
les porte vides, l'app refuse d'appeler et le dit (`ApiError.InvalidKey` — LLM
`InferHubLlmClient.kt:68`, Meta ASR `MetaAsrClient.kt:33`, ElevenLabs STT
`ElevenLabsSttClient.kt:26`, ElevenLabs TTS `ElevenLabsTtsClient.kt:42`).

| Étape | Emplacement | Noms (jamais de valeurs) |
|---|---|---|
| 1. build | `local.properties` (gitignoré) | `llm.base.url`, `llm.api.key`, `llm.model`, `tts.api.key`, `tts.voice.id`, `asr.api.key`, `sam31.endpoint`, `sam.base.url`, `sam.us.base.url`, `mwdat.application.id`, `mwdat.client.token` |
| 2. injection | `BuildConfig` (`app/build.gradle.kts:100-116`) | `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `TTS_API_KEY`, `TTS_VOICE_ID`, `ASR_API_KEY`, `SAM31_ENDPOINT`, `SAM_BASE_URL`, `SAM_US_BASE_URL` |
| 2 bis. manifeste | placeholders `mwdat_*` (`app/build.gradle.kts:96-97`, lus dans `AndroidManifest.xml`) | `com.meta.wearable.mwdat.APPLICATION_ID`, `…CLIENT_TOKEN` |
| 3. amorçage | `app/SettingsSeed.kt` (`seeded`) | remplit les champs **vides** (clé LLM, voix, dictée) ; une valeur saisie dans Réglages gagne toujours |
| 4. stockage | `core/settings/EncryptedSettingsStore.kt` : `EncryptedSharedPreferences` fichier `sam3vision_secure_settings` (`:12`), clés `KEY_*` (`:90-108`) — repli `core/settings/PrivateFileSettingsStore.kt` : `filesDir/settings.enc`, AES-256/GCM, clé Keystore non exportable, choisi par `SettingsStoreFactory.create` | `llm_base_url`, `llm_api_key`, `llm_model`, `sam_base_url`, `sam_region`, `speech_language`, `tts_api_key`, `tts_voice_id`, `tts_autoplay`, `tts_announce_positions`, `asr_base_url`, `asr_api_key`, `asr_model`, `intro_enabled`, `intro_played`, `distance_overlay`, `live_sam`, `camera_fov_deg`, `max_objects` |
| 5. envoi | en-têtes HTTP | `Authorization: Bearer` (LLM, Meta ASR), `xi-api-key` (ElevenLabs TTS/STT) ; **les endpoints SAM ne portent aucune clé** ; `/release` porte `x-sam-release: confirm` |

Deux nuances : les URL SAM ne passent **pas** par `BuildConfig` à l'amorçage — elles sont
résolues à l'appel par `Settings.samEndpoint()` (`app/SamEndpoint.kt`) ; et `BuildConfig` est
bien la seule porte d'entrée d'une clé sans saisie manuelle, désormais pour la seule `:app`
(`app/build.gradle.kts:100-116`).

## 5. Réglages par défaut et endpoint SAM

`Settings.Default` (`core/settings/Settings.kt:84-102`) : base LLM
`https://inference.baseten.co/v1` (`:87`), modèle `meta-models/muse-spark-open-1026` (`:88`), clé LLM vide,
voix ElevenLabs = voix « premade » Sarah (`DEFAULT_TTS_VOICE_ID`, `Settings.kt:69` ; les voix
de bibliothèque répondent 402 sans crédits, commentaire `Settings.kt:67-70`), langue de la voix = **anglais** (`SpeechLanguage.DEFAULT = En`,
`core/speech/SpeechLanguage.kt:32`), ASR = base `https://api.meta.ai/v1` (`:81`), modèle
`muse-voice-transcribe-1.0` (`:82`), `ttsAutoplay = true`, `ttsAnnouncePositions = true`,
`introEnabled = true`, `distanceOverlayEnabled = true`, `liveSamEnabled = true`,
`samRegion = Eu`, `cameraFovDeg = 0f` (0 = lu sur le capteur), `maxObjects = 8`.

**Résolution de l'endpoint SAM** (`resolveSamEndpoint`, `core/settings/SamRegion.kt`), dans
cet ordre : saisie manuelle qui n'est *pas* un preset → URL injectée au build pour la région
choisie → preset de la région. Une URL qui n'est qu'un preset est effacée au chargement
(`app/SettingsSeed.kt`, `dropPresetUrl`), sinon le choix de région ne s'appliquerait jamais.
Choisir une région **n'allume aucune carte** : elle élit seulement l'URL appelée
(`SamRegion.kt:4-9`).

**Plafonds** : `core/model/ObjectLimits.kt:16-18` → 8 objets affichés par défaut, réglable de
1 à 12. `core/live/LivePrompts.kt` → **2 concepts suivis par défaut** (`person`, `door`, `:20`),
maximum 2 (`:28`), 32 caractères (`:31`). ⚠️ Le commentaire d'en-tête du même fichier parle
encore de « huit » concepts par défaut : **commentaire périmé**, la constante `Default` fait
foi.

## 6. Le direct : transport, cadence, délais

- **Transport** : WebSocket uniquement (`core/net/LiveClientFactory.kt`). Un seul
  `OkHttpClient` partagé (`core/net/Http.kt`, `liveHttpClient`) : pool de **8 connexions
  inactives / 5 min** (`:65`), et le client WS en dérive par `newBuilder()` (`liveWsClient`,
  `:81`) pour réutiliser le tunnel DNS/TCP/TLS du `/health`.
- **Délais du direct** : connexion 3 s, écriture 5 s, lecture 60 s, `callTimeout` 0
  (`Http.kt:86-88`) — le socket vit toute la session ; le budget d'une frame est appliqué par
  `WsLiveClient` (`withTimeoutOrNull`).
- **Cadence** (`core/live/LiveSession.kt`) : le Direct garde **350 / 500 / 900 ms**
  (`Cadence.Direct`, `:33`) ; la page caméra iSee part sur `Cadence.Fast` (`:42-47`) : pas de
  plancher, **un seul créneau**, budget de latence 150 ms, hystérésis à 3 mesures
  (`REGULATE_MIN_SAMPLES = 3`, `:171`) et réouverture seulement sous `RELAX_RATIO = 0,5` du
  budget (`:168`). Créneau pris = frame **jetée** (`runFrame` renvoie `null`), jamais mise en
  attente.
- **Réveil** : le conteneur est réveillé par la **première frame** ; `LiveProbe.markWarming()`
  ne pose qu'un drapeau d'affichage, aucun appel réseau (`app/isee/LiveProbe.kt:441`). Côté
  Direct, `LiveWakeGuard` (`app/live/LiveWakeGuard.kt`) ouvre une fenêtre de 60 s pendant
  laquelle un échec s'affiche « réveil du GPU… ». L'app appelle `POST /release` quand elle se
  ferme (`MainActivity.onPause`/`onDestroy` → `AppViewModel.releaseGpu` →
  `AppDiagnostics.releaseGpu`, `app/vm/AppDiagnostics.kt:97`).
- **Masques** : le client demande l'encodage `bits` (`MASK_ENCODING_BITS`,
  `core/model/Models.kt:36`) ; un backend qui ignore le champ sert `png`, et la réponse dit ce
  qu'elle a réellement envoyé (`mask_encoding`, `Json.kt:291`).
- **Distances** : sténopé sur le téléphone (`core/distance/DistanceEstimator.kt`), champ de
  vision par défaut 67°, bornes 15°–130° (`core/distance/LensSpec.kt:43,46,49`), hauteurs
  connues dans `core/distance/KnownSizes.kt`, lissage 0,35 / 0,85
  (`DistanceSmoother.kt:18-19`). **Aucune requête, aucun GPU.**

## 7. La carte des fichiers d'entrée

| Question | Où regarder |
|---|---|
| Point d'entrée Android | `app/src/main/AndroidManifest.xml`, `app/…/MainActivity.kt` (activité unique), `app/…/SamApp.kt` (planifie la purge) |
| Écrans et navigation | `app/…/AppNav.kt` (routes), `app/…/isee/IseeHome.kt` (accueil), `app/…/isee/IseeScenePane.kt` (la scène, plein écran) |
| Carte caméra de la scène | `app/…/isee/IseeVisionCard.kt` (`VisionCard`, `CameraLive`, `CameraGlue`, `rememberCameraGlue`) |
| Gestes | `app/…/IseeRootLayout.kt` (surface), `core/…/gesture/IseeGestureBrain.kt`, `TapOrHold.kt`, `LongPressTrigger.kt` |
| Caméra | `app/…/isee/IseeCameraPane.kt` (`IseeCameraHost`, pilote CameraX), `app/…/CameraSurface.kt` (aperçu + masques), `app/…/CameraLens.kt` |
| Client LLM | `core/…/net/InferHubLlmClient.kt`, prompts `core/…/net/LlmPrompts.kt` |
| Client SAM | `core/…/net/HttpSamClient.kt` (photo), `core/…/net/WsLiveClient.kt` (direct), `core/…/net/WsTransport.kt` (transport), `core/…/net/LiveClientFactory.kt` (fabrique unique) |
| Cycle de vie du GPU | `core/…/net/SamLifecycle.kt` (`/release`), `app/…/vm/AppDiagnostics.kt` (`releaseGpu`, `testSam`) |
| Cadence et régulation | `core/…/live/LiveSession.kt`, `core/…/live/FrameGate.kt`, `core/…/live/PipelineGate.kt`, `core/…/live/LatestFrameSlot.kt` |
| Sonde de la page caméra | `app/…/isee/LiveProbe.kt`, `LiveProbeState.kt`, `ProbeWiring.kt`, affichage `LiveProbeText.kt` + `LiveDebugPanel.kt` |
| Masques | `core/…/mask/MaskCore.kt` (pur : `M1`/bits, LUT, coloriage sans allocation), `core/…/mask/Masks.kt` (Bitmap), `app/…/live/LiveMaskDecoder.kt` |
| Distances | `core/…/distance/DistanceEstimator.kt` (`DistanceMeter`), `KnownSizes.kt`, `LensSpec.kt`, `DistanceSmoother.kt`, `DistanceText.kt` |
| Voix | `core/…/net/ElevenLabsTtsClient.kt`, `core/…/net/ElevenLabsSttClient.kt`, `core/…/net/MetaAsrClient.kt`, `core/…/net/FallbackSpeechClient.kt`, `app/…/SpeechPlayer.kt`, `app/…/VoiceAnnouncer.kt`, `app/…/SpeechClients.kt` |
| Réglages | `core/…/settings/Settings.kt`, `EncryptedSettingsStore.kt`, `PrivateFileSettingsStore.kt`, `SettingsStoreFactory.kt`, `SamRegion.kt`, `app/…/SettingsScreen.kt`, `app/…/SettingsSeed.kt` |
| Design iSee | `core/…/iseedesign/Dissolve.kt`, `core/…/iseedesign/IseeScene.kt`, `app/…/isee/IseeSceneArt.kt`, `IseeScenePane.kt`, `IseeSceneState.kt`, `IseeDissolve.kt` |
| Purge des captures | `app/…/cleanup/` (12 fichiers : Room + WorkManager), planificateur `CleanupScheduler.kt`, config `CleanupConfig.kt` (`PERIOD_HOURS = 1`) |
| Lunettes | `app/…/glasses/` (12 fichiers : état, contrôleur, caméra, audio, politique, section de réglages) et `app/…/glasses/bench/` (18 fichiers : banc d'essai, SDK DAT) — le dossier de l'ancien module n'est plus dans le build |
| Gardes | `tools/check-kotlin-limits.py`, `.github/workflows/ci.yml`, `.github/couche0/check_couche0.py` |

## 8. Ce que ce dépôt ne contient pas

- Le **backend SAM 3.1** (GPU) : dépôt séparé, non public. Ni son code, ni son image, ni sa
  configuration ne sont ici.
- **`docs/LIVE-SPEC.md` : cité par le code, absent du dépôt.** `core/net/HttpLiveClient.kt:30`
  et `core/net/TrackClient.kt:34` s'y réfèrent comme au contrat du backend ; `ls docs/` ne le
  trouve pas. Une autre passe le crée ; il ne doit pas être inventé ici.
- Le **protocole `/live/ws` côté serveur** : le client est ici, le serveur est ailleurs.
- Les **secrets** : aucune clé, aucun jeton, aucune URL signée.
- Le **tracker** (`sam3-track`) côté serveur : le client existe (`core/net/TrackClient.kt`,
  `core/live/TrackRunner.kt`) et l'écran Direct l'instancie à la demande (pastille
  « Tracker ») ; le **serveur**, lui, est ailleurs (dépôt non public).
