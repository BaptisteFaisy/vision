# NOTES — versions épinglées et décisions techniques

Relu le **26/10/2026**, puis recompté le **27/10/2026** après la suppression du module
`:glasses-spike` (le banc lunettes vit maintenant dans `:app`, `docs/GLASSES-DECISION.md`), puis
recompté le **28/10/2026** et le **29/10/2026**. Les versions viennent
des fichiers du dépôt, pas du souvenir d'un build : `gradle/libs.versions.toml`,
`gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, `core/build.gradle.kts`.
Les comptages sont refaits sur ce commit ; ce qui n'a pas été exécuté est marqué **non mesuré**.

## Versions retenues (celles du dépôt)

| Élément | Version | Preuve |
|---|---|---|
| Gradle | **8.14.1** (wrapper) | `gradle/wrapper/gradle-wrapper.properties:3` |
| AGP | **8.11.1** | `gradle/libs.versions.toml:2` (`agp`) |
| Kotlin | **2.2.21** (+ plugin compose 2.2.21, même référence) | `libs.versions.toml:3` |
| KSP | **2.2.21-2.0.5** | `libs.versions.toml:16` |
| JDK | **17 par `JAVA_HOME` — PAS épinglé dans le dépôt** | `gradle.properties:25-27` |
| compileSdk / targetSdk | **36** partout | `app/build.gradle.kts:83,88`, `core/build.gradle.kts:10` |
| minSdk | **29 (`:app`, banc lunettes compris)** · 26 (`:core`, lib) | `app/build.gradle.kts:87`, `core/build.gradle.kts:13` |
| Compose BOM | **2026.05.01** (activity-compose 1.13.0, lifecycle 2.10.0) | `libs.versions.toml:7,8,9` |
| CameraX | **1.6.0** (core, camera2, lifecycle, view, **video**) | `libs.versions.toml:12` |
| OkHttp | **4.12.0** | `libs.versions.toml:6` |
| coroutines | **1.10.2** | `libs.versions.toml:5` |
| Room | **2.8.5** (+ compiler via KSP) | `libs.versions.toml:14` |
| WorkManager | **2.12.0** | `libs.versions.toml:15` |
| security-crypto | **1.1.0** | `libs.versions.toml:13` |
| core-ktx | **1.18.0** | `libs.versions.toml:4` (`coreKtx`) |
| JUnit / androidx.test | **4.13.2** / ext-junit **1.3.0**, core **1.7.0**, runner+rules **1.7.0** | `libs.versions.toml:17,18,19,20` |
| org.json (test only) | **20240303** | `libs.versions.toml:22`, `core/build.gradle.kts:40` |
| jump3r (MP3 pur Java) | **1.0.4** | `libs.versions.toml:21` |
| mwdat (lunettes Meta) | **1.0.0** (core, camera, inputs, mockdevice) — la seule version que les lunettes servent ; garde `DatPinTest`, raison dans le toml | `libs.versions.toml:27` |
| guava | **33.3.1-android** (pinning transitif Room, sans import direct) | `libs.versions.toml:28`, `app/build.gradle.kts:216-217` |
| onnxruntime-android (profondeur) | **1.18.0** | `libs.versions.toml:29` |
| fragment (pinning lint) | **1.8.9** (un transitif tire 1.1.0, lint exige ≥ 1.3.0) | `libs.versions.toml:10-11` |

**Le JDK n'est pas dans le dépôt.** `gradle.properties` (17-24) ne contient que
`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `daemon=false`, `parallel`, `workers.max=2`,
`caching`, useAndroidX, nonTransitiveRClass, code.style, plus un **commentaire** (:25-27) disant
de mettre `org.gradle.java.home` dans `~/.gradle/gradle.properties` (perso, non versionné). La CI
bloque le retour de cette ligne (`.github/workflows/ci.yml:113-120`). Sur cette machine (r630) :
`JAVA_HOME=~/toolchains/jdk17`, `ANDROID_HOME=$HOME/android-sdk`.

## Taille et tests : chiffres réels

Relevé du 29/10/2026, compté sur le disque (arbre propre). L'arbre reçoit du code en parallèle :
les totaux bougent de quelques dizaines de lignes, les proportions non.

| Mesure | Valeur |
|---|---|
| Fichiers `.kt` | **913** |
| Lignes de prod (`*/src/main`) | **54 684** — `:core` 216 f./22 458 l. · `:app` 283 f./32 226 l. (dont banc 15 f./1 641 l.) |
| `@Test` | **2 893** = **2 884 JVM** + **9 instrumentés** |
| dont `core/src/test` | **1 835** |
| dont `app/src/test` | **1 049** (dont banc **95**) |
| dont `app/src/androidTest` (nettoyage, profondeur, média) | **8** (appareil requis) |
| dont `app/src/androidTestDebug` (`bench/MockGlassesTest`) | **1** (appareil requis) |
| `@Preview` Compose | **11** |
| Plus gros fichiers | `app/AppViewModel.kt` et `app/isee/IseeViewModel.kt` — **498** lignes chacun (côté Python : `conflict_agent.py`, `fast_core.py` — 499) |

Le nombre de tests réellement **exécutés** est **non mesuré ici** : aucun `gradlew` n'a été
lancé sur cette machine pour ce comptage.

## Décisions techniques toujours vraies

- **Un seul client HTTP** : `defaultHttpClient(timeoutSeconds)` dans `core/net/Http.kt:57`,
  60 s par défaut (`DEFAULT_TIMEOUT_SECONDS`, `Http.kt:28`). Un seul mapper JSON : `org.json`
  (`net/Json.kt:1-7`) — fourni par Android, donc **aucune dépendance de prod ajoutée pour le
  JSON**. Pas de Retrofit, pas de kotlinx-serialization, pas de MockWebServer.
- **`Result.flatMap` n'existe pas** dans la stdlib → helper interne `Result.then`
  (`net/Http.kt:306`).
- **Erreurs typées et centralisées** : `Throwable.toApiError()` (`net/Http.kt:232`) et
  `httpError(code, body)` (`net/Http.kt:241`) — 401/403 → clé invalide, 402 → facture,
  409 → une réponse LLM est encore en cours, 429 → limite, et un 404 Modal « invalid function
  call » → `BackendStopped` (pas de nouvelle tentative sur une app arrêtée).
- **Tests JVM sans réseau** : serveur maison
  `core/src/test/java/dev/sam3vision/core/TestHttpServer.kt` (**129 lignes**), un thread par
  requête, `Content-Length` garanti, `delayMs` pour tester les timeouts.
- **`org.json` côté JVM de test** : le jar mockable d'Android renvoie des valeurs par défaut →
  `testImplementation(libs.json)` (`core/build.gradle.kts:40`).
- **`javax.imageio` / `java.awt` masqués** par le mockable jar : les tests de masque travaillent
  sur des en-têtes PNG synthétiques et des `IntArray` (`core/mask/MaskCore.kt`), les wrappers
  `Bitmap` (`core/mask/Masks.kt`) restent fins et ne sont pas testables en JVM.
- **Segmentation non bloquante** : `AnalyzeUseCase` borne SAM à 10 s puis décrit quand même
  l'image (`withTimeoutOrNull(samBudgetMs) { it.segment(..) }`, `core/usecase/UseCases.kt:40`,
  `SAM_BUDGET_MS`, `:34`).
- **Réglages chiffrés, avec repli silencieux** : `security-crypto` en priorité, sinon
  `PrivateFileSettingsStore` (fichier privé chiffré, clé Keystore non exportable) —
  `core/settings/SettingsStoreFactory.kt:16-22`, repli autour de chaque opération
  (`ResilientSettingsStore`). `EncryptedSharedPreferences` est marqué
  *deprecated* : warning de compilation seulement (**non revérifié ici**, pas de build lancé).
- **Jamais de clé en dur** : `llmApiKey` vide par défaut, message « clé API absente —
  renseigne-la dans Réglages », échec immédiat sans appel réseau.
- **Logique pure d'abord** : cadence (`core/live/FrameRate.kt`), régulation
  (`FrameGate.kt`, `PipelineGate.kt`), journal (`LiveTimeline.kt`), annonces
  (`PositionAnnouncer.kt`, `LiveNarrator.kt`), gestes (`gesture/IseeGestureBrain.kt`,
  `LongPressTrigger.kt`, `TapOrHold.kt`), distances (`distance/DistanceEstimator.kt`,
  `LensSpec.kt`) vivent dans `:core` et se testent sans Android.
- **Distances sur le téléphone** : sténopé + hauteurs connues, 0 GPU et aucune requête
  (`DistanceMeter`, `core/distance/DistanceEstimator.kt:105`, `docs/DISTANCES.md`). La précision
  « ±15-20 % » annoncée n'a **jamais** été confrontée à un mètre ruban (`docs/DISTANCES.md:57`).
- **Masques dans la même surface que l'aperçu** (`app/CameraSurface.kt`) : un `AndroidView`
  est une `View` dessinée **après** le canevas Compose, donc les masques composés au-dessus
  passaient derrière l'aperçu — piège consigné dans `docs/MASQUES-DEBUG.md`.
- **Intro sans marqueur persisté** : l'audio se rejoue à chaque ouverture complète et ne se
  coupe que par la voix (`app/isee/IseeIntro.kt:25`) — choix assumé plutôt que « une
  seule fois à vie ».

## Direct : WebSocket `/live/ws`, une frame en vol

- **Une seule fabrique** `liveClient()` (`core/net/LiveClientFactory.kt:19-30`) sert **les deux
  écrans** (Direct et page caméra iSee) : point d'entrée unique, pas un `new` dispersé. Elle
  construit un `WsLiveClient` dont le transport est un `OkHttpWsTransport` sur
  `ws(s)://…/live/ws` (`core/net/WsTransport.kt:35`).
- **`POST /live` n'est plus appelé en production.** Le seul appel de `LiveClient.live(…)` sur
  le chemin exécuté est `LiveSession.kt:138`, et le client vient toujours de `liveClient()` →
  WebSocket (l'autre, `LivePipeline.kt:75`, est dans du code sans appelant — voir § Écrit mais
  pas appelé). `HttpLiveClient` reste dans la fabrique pour **`/health` seulement**
  (`LiveClientFactory.kt:47`, `WsLiveClient.kt:98`) ; son `POST /live` (`HttpLiveClient.kt:75`)
  ne sert qu'aux tests JVM (`core/src/test/…/LiveClientTest.kt`). Ne pas le reprendre pour des frames.
- **Corrélation par `id`, une file par `id`** (`core/net/WsLiveClient.kt`) : le serveur peut
  répondre dans le désordre, un message dont l'`id` n'est pas en vol est ignoré ; l'attente est
  enregistrée **avant** l'ouverture du socket, sinon un refus de connexion laisse la frame
  pendue.
- **Transport injectable** : `WsTransport` (`core/net/WsTransport.kt`) avec
  `OkHttpWsTransport` en prod et un faux JVM en test — le WebSocket est testable sans réseau
  ni GPU. Le socket vit toute la session ; **chaque frame** est bornée par son propre
  `withTimeoutOrNull` (`WsLiveClient.kt:169`).
- **Trois délais distincts, assumés** : frame 10 s (`LIVE_TIMEOUT_SECONDS`,
  `HttpLiveClient.kt:14`), première frame / session froide 60 s (`COLD_TIMEOUT_SECONDS`,
  `app/isee/ProbeFrame.kt:61`), `/health` 180 s (`WAKE_TIMEOUT_SECONDS`,
  `HttpLiveClient.kt:22`). Motif : un transport figé à 10 s échouait pendant les 26-50 s de
  réveil du conteneur (`LiveClientFactory.kt:15-17`).
- **Pastille de transport honnête** : « WebSocket » / « WebSocket KO », sans prétendre à un
  repli qui n'existe plus (`app/LiveMessages.kt`).
- **`LiveMessages.kt`** (61 lignes) et **`app/isee/LiveProbeText.kt`** (101 lignes) : messages
  d'état et libellés extraits des ViewModels pour tenir sous les 500 lignes et rester
  testables en JVM.
- **Douze concepts par défaut, seize au maximum** : `LivePrompts.default` = person, car,
  bicycle, electric scooter, pole, sidewalk, crosswalk, wall, window, stairs + door, barrier
  (12 le 29/10, ordre user) et `LivePrompts.MAX = 16` (`core/live/LivePrompts.kt:31-35,43`) ;
  le backend tronque à `PROMPT_LIMIT = 16` (`server/backend/tracking.py:53`, décision user du
  28/10). Historique : 2 le 27/10, 6 puis 16 le 28/10.
- **Preset extérieur 12 concepts** : `LivePrompts.outdoor` = person, pole, bollard, fence,
  barrier, gate, sign, tree + trash can, dumpster, animal, traffic light (cheminement
  urbain, pastille « Extérieur », ordre user du 29/10 : poubelles, bennes, animaux, feu),
  `app/HomePresetRow.kt`) — le défaut rue (12 dangers) et les presets maison restent
  inchangés.
- **Cadence** : `Cadence.Direct` = 350 / 500 / 900 ms (`core/live/LiveSession.kt:34`), le
  Direct n'ayant pas de budget de latence. La sonde iSee utilise `Cadence.Fast`
  (`app/isee/LiveProbe.kt:191`) : `fastMs = 0`, `targetMs = 0`, `slowMs = 900`,
  **`concurrency = 1`**, `latencyBudgetMs = 150` (`LiveSession.kt:43-49`).
- **Régulation par hystérésis** (`LiveSession.kt`, régulateur) : on ne rouvre un créneau que sous la
  moitié du budget (`RELAX_RATIO = 0.5`, `:195`) et seulement après trois mesures
  (`REGULATE_MIN_SAMPLES = 3`, `:198`), la première frame payant le démarrage du conteneur.
- **Une frame en vol** : si le créneau est pris, `runFrame` renvoie `null` et la frame est
  jetée — l'appelant caméra n'est jamais mis en attente (`LiveSession.kt:133-138`).

## Région = choix d'endpoint, jamais allumage de carte

`resolveSamEndpoint()` (`core/settings/SamRegion.kt:50-56`, appelé par
`app/SamEndpoint.kt:15-16`) applique l'ordre du code : **saisie manuelle hors preset** → **URL
injectée au build** pour la région → **preset de la région**. (Le KDoc de
`SamRegion.kt:46-49`, qui annonçait l'ordre inverse, a été corrigé le 27/10.) Choisir une région
efface une
URL qui n'était que le preset (`SamRegion.isPreset`, `SamRegion.kt:39-44`). **Une seule GPU à
la fois**, toutes régions confondues (`docs/MODE-US.md`) : changer de région n'allume rien.

## Écrit mais pas appelé (ne pas s'y fier)

- **`POST /video` (mode tap)** : le client est complet et testé
  (`core/net/HttpSam31VideoClient.kt`, `DEFAULT_PATH = "/video"` `:69`), l'écran l'utilise
  (`app/tap/TapCaptureScreen.kt:182`), mais **aucune implémentation backend connue** de cette
  route n'est vérifiable depuis ce dépôt (`git grep -n '"/video"' server/backend` vide).
  `TapConfig.ON_DEVICE_INFERENCE = false` (`app/tap/TapConfig.kt:48`) : sans endpoint saisi,
  rien ne part (`TapConfig.sendConfigured`, `:83`).
- **`core/live/LivePipeline.kt`** — pompe multi-frames du direct (`maxParallel` en vol) :
  écrite et testée (`LivePipelineTest`), **sans appelant en production** (le Direct et iSee
  passent par `LiveSession`, une frame en vol). Ne pas la présenter comme le chemin actuel.
- **`core/predict/*`** — le Direct l'appelle depuis `app/…/live/LivePrediction.kt`, **derrière le flag
  `Settings.boxPredictionEnabled`, éteint par défaut** — le branchement (T8) n'est allumé
  qu'après la mesure de la latence perçue. Flag éteint, le direct est celui d'avant.
- Commentaires de code recalés le 29/10 : `HttpLiveClient.kt:42` cite
  `docs/LIVE-SPEC.md`, qui **existe** (311 lignes, contrat du direct), et le KDoc de
  `LivePrompts.kt:22-24` retrace l'historique des plafonds (2 → 6 → 16), comme le code
  (`default` 12 concepts, `MAX = 16`).

## Non mesuré ici

- Tout ce qui est **serveur** : latences, réveil du conteneur, `/live/ws` côté backend. Les
  chiffres de latence cités dans `docs/PERF.md` sont soit des constantes lues dans le code,
  soit des mesures appareil datées et sourcées ; rien n'a été re-mesuré sur cet arbre.
- **Aucun build ni aucune suite de tests n'a été lancée** pour ces notes : les comptages sont
  des `grep`/`wc`, les versions sont lues dans les fichiers.

## Micro des lunettes — décision et recette (27/10/2026)

Le SDK DAT 1.0.1 expose bien l'audio public : `Stream.getAudioStream(): Flow<AudioFrame>`
(**aucune** annotation `@Deprecated`, `@RequiresOptIn` ni `@Experimental*` — vérifié au
`javap` sur `mwdat-camera-1.0.1.aar`, seule `@NotNull` est présente). Ce 1.0.1 inspecté
n'est **pas** la version pincée : le runtime lunettes exige le **1.0.0**
(`libs.versions.toml:23-27`, garde `DatPinTest`). `AudioFrame.buffer` est
du PCM déjà décodé par le SDK (le transport, lui, est AAC : `internal.codec.types.AudioCodec`,
`AudioCodecProfile.AAC_LC`, décodé par `AudioDecoder` via `MediaCodec`). `Permission.MICROPHONE`
existe côté `mwdat-core`, à côté de `Permission.CAMERA`.

Décision : `GlassesCamera` demande `AudioCodec.PCM(RATE_16000, 1)` — le format exact de la
dictée. Si le flux refuse, on descend le plan `GlassesAudioPlan.ladder` : 48 kHz mono (ramené à
16 kHz par `PcmRate.to16k`, moyenne de 3 échantillons), puis caméra seule. `GlassesAudio`
collecte les trames et écrit un WAV 16 kHz mono sous `filesDir/glasses-audio/lunettes-*.wav`
(`TempWavStore`, préfixe `lunettes`, 5 derniers gardés) ; la dictée le prend via `PcmSource` /
`MuseTranscript` — `DictationUseCase` + `SpeechClient` restent la seule transcription.
`MicBus` n'a pas bougé : la dictée des lunettes prend le même jeton `DICTATION`, donc la vidéo
du mode tap reste exclue pendant qu'on parle.

Non vérifié (aucune paire ici) : que 16 kHz soit accepté par le firmware, que les trames
arrivent à la fréquence annoncée, et le rendu réel de la transcription. Recette : voir le
corps de la PR.
