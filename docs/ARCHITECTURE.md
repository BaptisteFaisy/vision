# ARCHITECTURE — iSee

Ce document décrit **l'arbre de ce dépôt**, relu dans le code le **27/10/2026**, comptes
recomptés le **28/10/2026**, le **29/10/2026** puis le **01/11/2026** (première rédaction
le 26/10 après 19 h). Le service SAM 3.1 (GPU)
vit dans **`server/` de ce dépôt** : source déployée `server/backend/live_fast/` (app
`sam3-eu-a100`), contrats et mesures dans `server/docs/` (voir §8). Chaque chiffre ci-dessous
est soit une constante du code (fichier + ligne cités), soit un comptage reproductible (commande
donnée). Les mesures de temps et de débit sont dans `docs/PERF.md`, jamais ici.

## 1. Deux modules, leurs comptes réels

| Module | Type Gradle | Namespace / ID | minSdk | `src/main` | `src/test` | `src/androidTest` |
|---|---|---|---|---|---|---|
| `:core` | `com.android.library` | `dev.sam3vision.core` | 26 | **258** fichiers | 325 fichiers / **2 957** `@Test` | — |
| `:app` | `com.android.application` | `dev.sam3vision.app` | 29 | **376** fichiers | 252 fichiers / **1 838** `@Test` | 4 fichiers / **9** tests |
| `:app` → `glasses/bench/` | sous-dossier, **pas un module** | `dev.sam3vision.app.glasses.bench` | 29 | **14** fichiers | 9 fichiers / **98** `@Test` | 1 fichier / **1** test |

La dernière ligne compte un sous-dossier de `:app`, déjà comprise dans la ligne `:app` : le banc
d'essai lunettes (l'ancien module séparé) n'est plus un module, il vit dans l'APK d'iSee.

Les déclarations sont dans `core/build.gradle.kts:9,10,13` et `app/build.gradle.kts:82,83,87` ;
`settings.gradle.kts` n'inclut que `:core` et `:app`, et `compileSdk = 36` pour les deux. Le
dossier de l'ancien module a disparu du disque ; `git ls-files` ne trouve plus aucun chemin
`glasses-spike/`.

```bash
find core/src/main -name '*.kt' | wc -l            # 258
find core/src/test -name '*.kt' | wc -l            # 325
find app/src/main -name '*.kt' | wc -l             # 376
find app/src/test -name '*.kt' | wc -l             # 252
find app/src/main/java/dev/sam3vision/app/glasses/bench -name '*.kt' | wc -l        # 14
find app/src/test/java/dev/sam3vision/app/glasses/bench -name '*.kt' | wc -l        # 9
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench | wc -l        # 98
find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' | wc -l          # 1224
find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' -exec cat {} + | wc -l  # 153 303
```

Les `@Test` se comptent au `grep -rho` : le poste `pc` n'a pas `rg` dans son `PATH`, et un
`rg` absent rend **0** sans le dire (piège à connaître avant de conclure « aucun test »).

- `:core` est une **bibliothèque Android**, pas un module JVM. Sur ses 216 fichiers `main`,
  **4** importent `android.*` : `mask/Masks.kt`, `settings/EncryptedSettingsStore.kt`,
  `settings/SettingsCipher.kt`, `settings/SettingsStoreFactory.kt`. Tout le reste — réseau,
  gestes, cadence, distances, masques, texte, prédiction — est du Kotlin pur, testé sur la JVM.
- Les 283 fichiers de `:app` comptent un **stub** hors du paquet applicatif :
  `app/src/main/java/javax/sound/sampled/AudioFormat.kt`, surface minimale de
  `javax.sound.sampled` pour que le port Java de LAME (`jump3r`) encode du MP3 sur Android.
- Le détail des tests (par module et par domaine) est dans `docs/TESTING.md`.

## 2. Le flux réel

### 2.0 La racine, l'accueil et les gestes

`app/src/main/AndroidManifest.xml` déclare une **activité unique** (`MainActivity`) et une
`Application` maison (`android:name=".SamApp"`). `MainActivity.buildRoot` installe
`IseeRootLayout` (un `FrameLayout` qui intercepte le toucher) et lui pose une `ComposeView`
→ `SamRoot` → `AppNav` (`app/AppNav.kt`). La route d'accueil est `Route.Isee` →
`IseeHome` → **`IseeScenePane`** en plein écran (`app/isee/IseeHome.kt:40`) : bulle de
verre, gros texte paginé, carte caméra qui se dissout.

Gestes, tous câblés dans `MainActivity.buildRoot` :

| Geste | Effet | Code |
|---|---|---|
| tap simple | démarre / arrête l'enregistrement vidéo | `IseeViewModel.onSingleTap` (`app/isee/IseeViewModel.kt:363`) |
| appui long (300 ms, tolérance 24 px) | dictée micro maintenu + photo filmée jointe | `core/gesture/LongPressTrigger.kt:102,105`, `IseePhotoShot` |
| balayage horizontal | **reconnu mais sans effet** : `onHorizontalSwipe = Unit` | `app/IseeRootLayout.kt` |

Le **glissement vertical** (changement de bulle) et le **double tap** (photo) ont
été supprimés : `IseeViewModel` n'a plus `onVerticalDrag*` ni `onDoubleTap`, le
rail vertical de `core/gesture/IseeGestureBrain.kt` verrouille le geste **sans
rien émettre** (un glissement ne peut donc jamais passer pour un tap), et il n'y
a aucun moyen de revenir à un message antérieur.

### 2.1 Photo-contexte (appui long pendant qu'on filme)

1. `IseeViewModel.onHoldStart` → `shots.captureForHold()`
   (`app/isee/vm/IseePhotoShot.kt`) : `media.newPhotoFile()` puis
   `camera().takePhoto(target)` — c'est `IseeCameraHost`
   (`app/isee/IseeCameraPane.kt`), le pilote CameraX d'iSee (`ImageCapture` +
   `VideoCapture<Recorder>` + `ImageAnalysis`, liés ensemble). Muet : ni
   affichage, ni pastilles (le double tap n'existe plus).
2. **La lecture attend la capture** : `imageBase64()` attend le rappel CameraX
   (borné, `CAPTURE_TIMEOUT_MS = 4 s`) au lieu de lire un contexte encore vide —
   sans ça, une tenue courte partait en texte seul (« aucune image transmise »).
   Si rien n'avait démarré (caméra froide refusant la capture du début de
   tenue), la lecture retente une fois, la caméra étant chaude à ce moment-là.
3. `IseeAnswers` joint l'image (§2.3) ; la fin de réponse relâche le contexte
   (`releaseContext`), succès comme échec.

### 2.2 Enregistrement (tap simple) — le direct de la page caméra

1. `IseeViewModel.onSingleTap` → `cameraOps.startVideo()` / `stopVideo()`
   (`app/isee/vm/IseeCameraCoordinator.kt`) → `IseeCameraHost`.
2. **Hors enregistrement, aucune frame n'est convertie** : `host.wantsFrame` vaut
   `recordingVideo && probe.wantsFrame()` (`app/isee/IseeVisionCard.kt:262-265`).
3. Pendant l'enregistrement, `host.onFrame` → `LiveProbe.onFrame` (`app/isee/LiveProbe.kt`) :
   sérialisation JPEG au côté long **320 px** (`ISEE_FRAME_EDGE`,
   `app/isee/IseeCameraPane.kt:43`, qualité 55, `:46`).
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
   → `core/net/HttpLlmClient.ask` → `POST {llm}/chat/completions`, image = frame
   captée à l'appui long en data-URL (attendue, §2.1 ; texte seul sinon), et
   **historique toujours vide** : iSee joint `emptyList()` (`IseeAnswers`),
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
→ `HttpSamClient.segment` (facultatif) puis `HttpLlmClient.describe`. Un échec SAM ne
bloque rien : `AnalyzeUseCase` décrit quand même l'image, sans objets (`sam?.segment(...)` →
`getOrNull()`, `UseCases.kt:23`). Écrans : `AnalysisScreen`, `ChatScreen`.

### 2.5 Écran Direct (route `Live`)

`app/LiveViewModel.kt` (498 lignes) + `app/LiveScreen.kt` : frames au côté long **480 px**
(`LIVE_EDGE = FramePolicy.EDGE`, `LiveScreen.kt:59`), cadence `Cadence.Direct`, un `LiveClient`
obtenu par `LiveMaskTransport.clientFor(base, marks)` (`LiveViewModel.kt:205`,
`app/LiveMaskTransport.kt:17-45`). « Poser une question » joint la dernière
frame et le journal de pistes (`LiveAskSession`, §2.3).

### 2.6 Mode tap (vidéo → SAM 3.1)

Route `Tap` → `app/tap/TapCaptureScreen.kt` → `TapVideoSession` → `HttpSam31VideoClient`
(`core/net/HttpSam31VideoClient.kt`) : `POST {sam31}/video` en `multipart/form-data`
(`model`, `video`, `prompt` optionnel). Un drapeau `ON_DEVICE_INFERENCE` (`app/tap/TapConfig.kt:48`, `false`) refuse
l'envoi si l'inférence est locale.

## 3. Le contrat HTTP, endpoint par endpoint

Toutes les URL sont assemblées par `core/net/Http.kt:201` (`joinUrl` : base sans barre finale
+ chemin, une seule barre oblique). Aucune URL de production n'est « officielle » : elle vient
des Réglages, d'un preset de région (`core/settings/SamRegion.kt:22,27`) ou de
`local.properties`.

| Endpoint | Client (fichier:ligne) | Requête | Réponse lue |
|---|---|---|---|
| `GET {sam}/health` | `core/net/HttpLiveClient.kt:129` | — (délai de réveil 180 s) | `ok`, `model`, `device`, `gpu` → `LiveHealth` |
| `POST {sam}/segment` | `core/net/HttpSamClient.kt:15` | `{"image_b64", "prompts"\|null}` | `width`, `height`, `objects[{label,score,box,mask_b64}]` |
| `POST {sam}/live` | `core/net/HttpLiveClient.kt:80` | même corps que la trame WS, **sans `id`** | idem ci-dessous + `elapsed_ms` |
| `WS {sam}/live/ws` | `core/net/WsLiveClient.kt` (`LIVE_WS_PATH`, `WsTransport.kt:50`) | `{"id","image_b64","prompts","work_edge","max_objects","mask_encoding":"bits"}` | même forme que `/live`, **même `id`** ; erreur `{"error":{"status","detail"}}` |
| `POST {sam}/release` | `core/net/SamLifecycle.kt:40` | corps `{}`, en-tête `x-sam-release: confirm` | `{"released":true}` → `ReleaseOutcome` |
| `POST {sam31}/video` | `core/net/HttpSam31VideoClient.kt:35` | multipart : `model=sam3.1-video`, `video` (video/mp4), `prompt` | premier champ trouvé de `text`, `description`, `answer`, `caption` (tronqué à 600 caractères) |
| `POST {llm}/chat/completions` | `core/net/HttpLlmClient.kt:87` | `{model, messages, temperature 0.2, max_tokens 800\|200}` ; image en `image_url` data-URL | `choices[0].message.content` |
| `POST {elevenlabs}/text-to-speech/{voiceId}?output_format=mp3_44100_128` | `core/net/ElevenLabsTtsClient.kt:38` | `{text, model_id, voice_settings}`, en-tête `xi-api-key` | octets MP3 (un corps texte en 2xx = échec) |
| `POST {elevenlabs}/speech-to-text` | `core/net/ElevenLabsSttClient.kt:24` | multipart `model_id` + `file` (audio/wav), en-tête `xi-api-key` | texte de transcription |
| `POST {meta}/asr/transcribe` | `core/net/MetaAsrClient.kt:28` | multipart **2 parts** : `request` (JSON `{"model","audioEncoding":"WAV"}`) + `audio` (audio/wav), en-tête `Authorization: Bearer` | texte de transcription |
| `POST {track}/session`, `POST {track}/frame`, `DELETE {track}/session/{id}`, `GET {track}/health` | `core/net/TrackClient.kt:34-53` | `{image_b64,prompts}` / `{session_id,image_b64}` | session + objets suivis (délai 15 s) |

Précisions qui comptent :

- **`POST /live` n'est plus le chemin des frames.** `liveClient()` (`core/net/LiveClientFactory.kt`)
  ne construit qu'un `WsLiveClient` ; le `HttpLiveClient` qu'il lui passe en délégué ne sert
  qu'à `health()` (`WsLiveClient.kt:197`). `HttpLiveClient.live` n'est donc appelé que par les
  tests.
- **`GET /health` n'est appelé que par le bouton « Tester SAM »** (`app/vm/AppDiagnostics.kt:56`)
  et par la délégation ci-dessus. Aucune sonde périodique.
- **Le tracker est câblé dans `:app`** : l'écran Direct construit `HttpTrackClient` +
  `TrackRunner` quand sa pastille « Tracker » est active (`app/LiveViewModel.kt`, `start()`) ;
  c'est le second transport des frames, à côté de `/live` en WebSocket qui reste le défaut.
  Le débit relevé pour ce chemin est dans `docs/TRACK-PERF.md` (§ 2 : 1,8 ips sur le téléphone,
  lors d'un relevé antérieur — soit à peine mieux que `/live`, le serveur étant le facteur limitant).
- **Il n'existe aucun endpoint `/ask`** : la question part au LLM par `POST {llm}/chat/completions`
  (`grep -rn '"/ask"' core/src/main app/src/main` : rien).
- **Erreurs typées**, un seul barème (`core/net/Http.kt:308-324`) : 401/403 → `InvalidKey`,
  402 → `PaymentRequired`, 409/429 → `RateLimited`, 404 « invalid function call » →
  `BackendStopped` (proxy Modal, pas de relance), sinon `Http`. Les clients renvoient des
  `Result` : un échec n'est jamais une exception qui remonte.
- **Délais** : générique 60 s (`DEFAULT_TIMEOUT_SECONDS`, `Http.kt:29`), frame 10 s
  (`LIVE_TIMEOUT_SECONDS`, `HttpLiveClient.kt:17`), réveil 180 s (`WAKE_TIMEOUT_SECONDS`,
  `:25`), extinction 45 s (`RELEASE_TIMEOUT_SECONDS`, `SamLifecycle.kt:12`), vidéo 120 s
  (`UPLOAD_TIMEOUT_SECONDS`, `HttpSam31VideoClient.kt:75`), tracker 15 s
  (`TRACK_TIMEOUT_SECONDS`, `TrackClient.kt:12`).

## 4. Les clés : d'où elles viennent, où elles vont

**Aucune valeur de clé n'est écrite dans ce dépôt**, ni en clair ni par défaut : `Settings`
les porte vides, l'app refuse d'appeler et le dit (`ApiError.InvalidKey` — LLM
`HttpLlmClient.kt:68`, Meta ASR `MetaAsrClient.kt:33`, ElevenLabs STT
`ElevenLabsSttClient.kt:26`, ElevenLabs TTS `ElevenLabsTtsClient.kt:42`).

| Étape | Emplacement | Noms (jamais de valeurs) |
|---|---|---|
| 1. build | `local.properties` (gitignoré) | `llm.base.url`, `llm.api.key`, `llm.model`, `tts.api.key`, `tts.voice.id`, `asr.api.key`, `llm.live.api.key`, `google.maps.api.key`, `sam31.endpoint`, `sam.base.url`, `sam.us.base.url`, `route.base.url`, `live.local.overlay`, `mwdat.application.id`, `mwdat.client.token` |
| 2. injection | `BuildConfig` (`app/build.gradle.kts:123-153`) | `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `TTS_API_KEY`, `TTS_VOICE_ID`, `ASR_API_KEY`, `LIVE_LLM_API_KEY`, `GOOGLE_MAPS_API_KEY`, `SAM31_ENDPOINT`, `SAM_BASE_URL`, `SAM_US_BASE_URL`, `ROUTE_BASE_URL`, `LOCAL_OVERLAY` |
| 2 bis. manifeste | placeholders `mwdat_*` (`app/build.gradle.kts:99-100`, lus dans `AndroidManifest.xml`) | `com.meta.wearable.mwdat.APPLICATION_ID`, `…CLIENT_TOKEN` |
| 3. amorçage | `app/SettingsSeed.kt` (`seeded`) | remplit les champs **vides** (clé LLM, voix, dictée) ; une valeur saisie dans Réglages gagne toujours |
| 4. stockage | `core/settings/EncryptedSettingsStore.kt` : `EncryptedSharedPreferences` fichier `sam3vision_secure_settings` (`:12`), clés `KEY_*` (`:90-108`) — repli `core/settings/PrivateFileSettingsStore.kt` : `filesDir/settings.enc`, AES-256/GCM, clé Keystore non exportable, choisi par `SettingsStoreFactory.create` | `llm_base_url`, `llm_api_key`, `llm_model`, `sam_base_url`, `sam_region`, `speech_language`, `tts_api_key`, `tts_voice_id`, `tts_autoplay`, `tts_announce_positions`, `asr_base_url`, `asr_api_key`, `asr_model`, `intro_enabled`, `intro_played`, `distance_overlay`, `live_sam`, `camera_fov_deg`, `max_objects` |
| 5. envoi | en-têtes HTTP | `Authorization: Bearer` (LLM, Meta ASR), `xi-api-key` (ElevenLabs TTS/STT) ; **les endpoints SAM ne portent aucune clé** ; `/release` porte `x-sam-release: confirm` |

Deux nuances : les URL SAM ne passent **pas** par `BuildConfig` à l'amorçage — elles sont
résolues à l'appel par `Settings.samEndpoint()` (`app/SamEndpoint.kt`) ; et `BuildConfig` est
bien la seule porte d'entrée d'une clé sans saisie manuelle, désormais pour la seule `:app`
(`app/build.gradle.kts:123-153`).

## 5. Réglages par défaut et endpoint SAM

`Settings.Default` (`core/settings/Settings.kt:304-309`) : base LLM
`https://inference.baseten.co/v1` (`:225`), modèle `meta-models/muse-spark-open-1026` (`:226`), clé LLM vide,
voix ElevenLabs = Laure (`DEFAULT_TTS_VOICE_ID = LAURE_VOICE_ID`, `Settings.kt:236`, id `:229` ;
Sarah n'est plus que l'ancien défaut, `SARAH_VOICE_ID` `:244`, migré par `SettingsSeed`), langue
de la voix = **anglais** (`SpeechLanguage.DEFAULT = En`, `core/speech/SpeechLanguage.kt:33`),
ASR = base `https://api.meta.ai/v1` (`:269`), modèle `muse-voice-transcribe-1.0` (`:270`),
`ttsAutoplay = true`, `ttsAnnouncePositions = true`, `introEnabled = true`,
`distanceOverlayEnabled = true`, `liveSamEnabled = true`, `samRegion = Eu`, `cameraFovDeg = 0f`
(0 = lu sur le capteur), `maxObjects = 16` (`:179`, `ObjectLimits.DEFAULT_ON_SCREEN`).

**Résolution de l'endpoint SAM** (`resolveSamEndpoint`, `core/settings/SamRegion.kt`), dans
cet ordre : saisie manuelle qui n'est *pas* un preset → URL injectée au build pour la région
choisie → preset de la région. Une URL qui n'est qu'un preset est effacée au chargement
(`app/SettingsSeed.kt`, `dropPresetUrl`), sinon le choix de région ne s'appliquerait jamais.
Choisir une région **n'allume aucune carte** : elle élit seulement l'URL appelée
(`SamRegion.kt:4-9`).

**Plafonds** : `core/model/ObjectLimits.kt:26-28` → 16 objets affichés par défaut, réglable de
1 à 32. `core/live/LivePrompts.kt` → **21 concepts suivis par défaut** (`:75-81`), maximum 22
(`:156`), 32 caractères (`:159`).

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
  plancher, **deux créneaux**, budget de latence 210 ms, hystérésis à 3 mesures
  (`REGULATE_MIN_SAMPLES = 3`, `:171`) et réouverture après 5 moyennes consécutives sous
  le budget (`REOPEN_AFTER = 5`). Créneau pris = frame **jetée** (`runFrame` renvoie `null`),
  jamais mise en
  attente.
- **Réveil** : le conteneur est réveillé par la **première frame** ; `LiveProbe.markWarming()`
  ne pose qu'un drapeau d'affichage, aucun appel réseau (`app/isee/LiveProbe.kt:480`). Côté
  Direct, `LiveWakeGuard` (`app/live/LiveWakeGuard.kt`) ouvre une fenêtre de 60 s pendant
  laquelle un échec s'affiche « réveil du GPU… ». L'app appelle `POST /release` quand elle se
  ferme (`MainActivity.onPause`/`onDestroy` → `AppViewModel.releaseGpu` →
  `AppDiagnostics.releaseGpu`, `app/vm/AppDiagnostics.kt:100`).
- **Masques** : le client demande l'encodage `bits` (`MASK_ENCODING_BITS`,
  `core/model/Models.kt:36`) ; un backend qui ignore le champ sert `png`, et la réponse dit ce
  qu'elle a réellement envoyé (`mask_encoding`, `Json.kt:291`).
- **Distances** : sténopé sur le téléphone (`core/distance/DistanceEstimator.kt`), champ de
  vision par défaut 67°, bornes 15°–130° (`core/distance/LensSpec.kt:80,83,86`), hauteurs
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
| Client LLM | `core/…/net/HttpLlmClient.kt`, prompts `core/…/net/LlmPrompts.kt` |
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

## 8. Ce que ce dépôt ne contient pas (et où est le reste)

- Le **conteneur réellement déployé** : le source de référence est dans `server/` (`live_fast/`
  pour le direct, `modal_app.py` legacy, `track_app.py`, `valhalla_cpu.py`), mais la version qui
  sert, ses leviers (`FAST_*`) et sa région ne se lisent que sur son `/health` (champs
  `levers`/`applied`) — voir `server/docs/LIVE-FAST.md`.
- **`docs/LIVE-SPEC.md` existe** : contrat du direct déduit du client (311 lignes).
- Le **protocole `/live/ws` côté serveur** : implémenté dans `server/backend/live_fast/`
  (`service.py`, `live_ws.py`) ; contrat dans `docs/LIVE-SPEC.md` §7.
- Les **secrets** : aucune clé, aucun jeton, aucune URL signée.
- Le **tracker déployé** : le code est ici (`server/backend/track_app.py`), mais aucun workflow
  ne le redéploie — déploiement manuel. Le client (`core/net/TrackClient.kt`,
  `core/live/TrackRunner.kt`) parle `POST /session`, `POST /frame`, `DELETE /session/{id}`
  (`TrackClient.kt:30-40`).
- **`POST /video`** : toujours sans implémentation serveur connue
  (`git grep -n '"/video"' server/backend` vide) — client écrit et testé, voir `docs/MODE-TAP.md`.
