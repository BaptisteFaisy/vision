# Fiche de référence — DAT (Meta Wearables Device Access Toolkit) et banc lunettes

> **Réécrit le 2026-10-27.** Le module `:glasses-spike` n'existe plus : le banc d'essai vit
> dans `:app`, paquet `dev.sam3vision.app.glasses.bench`, et il est ouvert depuis l'écran
> Lunettes d'iSee (voir [`GLASSES-DECISION.md`](GLASSES-DECISION.md)). **Le nom de ce fichier
> est conservé** pour ne pas casser les liens existants ; il ne désigne plus un module.
>
> **Périmètre** : le paquet `app/src/main/java/dev/sam3vision/app/glasses/bench/` et ses
> sources de test. Deux sources seulement :
> - **le code**, cité `fichier:ligne` — c'est l'autorité ;
> - **le bytecode** des AAR `com.meta.wearable:mwdat-*` **1.0.1** présentes dans le cache
>   Gradle du poste (`javap` sur le `classes.jar` extrait de l'AAR) : relevé du 26/10/2026,
>   rejouable à tout moment, sans réseau.
>
> **Aucune paire de lunettes n'était disponible ici.** Tout ce qui touche au matériel est
> marqué **non vérifié** (§5) et n'est jamais présenté comme mesuré. Aucun `gradlew` n'a été
> lancé pour cette page.

## 0. Le banc en chiffres (mesuré sur le disque)

| | |
|---|---|
| contenu copié du module supprimé | **30 `.kt` / 3 488 lignes** — 17 en `main` (1 824 l.), 2 en `debug` (243 l.), 1 en `release` (24 l.), 9 en `test` (1 239 l.), 1 en `androidTestDebug` (158 l.) |
| état du paquet au 27/10, 11 h 15 | 18 `.kt` en `main` : l'activité `MainActivity.kt` et `GlassesApp.kt` ont cédé la place à l'écran `BenchScreen.kt` (337 l.) et au contrat `BenchMock.kt` — le paquet bouge encore au moment de ce relevé |
| plus gros fichier | `BenchScreen.kt` (337 l.) ; plus gros fichier hérité, `GlassesVoice.kt` (261 l.) — limites 500 / 50 respectées |
| tests JVM | **86 annotations `@Test`** sous `app/src/test/.../bench/` (`grep -h '@Test' … \| wc -l`) ; leur **exécution** n'est pas mesurée ici, aucun build lancé |
| tests instrumentés | **1** (`MockGlassesTest`), jamais exécuté en CI : il faut un appareil |
| Android | `minSdk = 29` (`app/build.gradle.kts:84`), `compileSdk`/`targetSdk` = 36 (`:80`, `:85`) — le banc suit `:app`, il n'a plus de `build.gradle.kts` à lui |
| dépendances | celles de `:app` : `mwdat-core`, `mwdat-camera`, `mwdat-inputs`, et `mwdat-mockdevice` en `debugImplementation` (`app/build.gradle.kts:180-184`) |
| hors `.kt` copié | asset `app/src/androidTest/assets/mock_feed.mp4` (54 964 o) — **seul** fichier non-Kotlin repris |
| hors `.kt` **non** repris | `frame-64.jpg` → **manquant**, alors que `app/src/test/.../bench/ChaineCompleteTest.kt:205-216` l'exige sur le classpath (voir `GLASSES-DECISION.md` §5) |

## 1. Deux codes, deux responsabilités — désormais dans le même module

Le banc et la production parlent au même SDK mais **ne font pas la même chose**. Le fait d'être
dans le même module Gradle ne change rien à cette séparation : ce sont deux paquets, et un seul
des deux est sur le chemin de production.

| | production `app/…/glasses/` | banc `app/…/glasses/bench/` |
|---|---|---|
| cadence demandée | **15** (`GlassesCamera.kt:129`, `:62`) | **24** (`GlassesDeviceSession.kt:12`, `:46`) |
| `compressVideo` | **`true`** (`GlassesCamera.kt:63`) : bitstream H.265 | jamais passé → défaut SDK `false` : YUV brut |
| flux vidéo | **aucun** abonnement à `videoStream` (0 occurrence hors `bench/`, mesuré) | `videoStream` collecté, étranglé à 1 frame / 1,5 s (`GlassesFramePump.kt:38-44`) |
| image | photo à la demande `capturePhoto()` → `PhotoData` (`GlassesFrames.kt:40`) | convertisseur YUV propre (`FrameTypes.kt:76`, `:134`) puis encodage JPEG (`GlassesFrameEncoder.kt:42`) |
| audio | piste PCM du flux (`GlassesAudioPlan`, `GlassesCamera.kt:43-44`), pas de routage Bluetooth | routage de la voix vers la sortie des lunettes (`GlassesVoice.kt:63`, `:97-110`) |
| UI | bloc « Lunettes » des Réglages (`GlassesSection.kt`) | un écran, un journal, des boutons (`BenchScreen.kt`) |
| point d'entrée | l'app elle-même (accueil, Réglages) | `Route.Bench` dans la navigation d'iSee (`AppNav.kt:45`, `:95`) |

Conséquence, inchangée : **une conclusion prise sur le banc ne s'applique pas à la production**
(24 i/s + YUV ne sont pas ce que `:app` envoie), et l'inverse non plus. Voir §9.

## 2. Le pipeline du banc, étape par étape

```
Wearables (SDK DAT) → Stream.videoStream
  → collectFrames      : jette les frames de config codec, dépose dans un canal CONFLATÉ
  → consumeFrames      : attend la fenêtre de 1,5 s, garde la frame la plus récente
  → GlassesFrameEncoder: verdict → octets → disposition YUV → ARGB → Bitmap → 16:9 → ≤1024 px → JPEG 85 → base64
  → BenchController.onFrame (BenchScreen.kt:290) — journal toujours ; réseau seulement si « Analyser » est activé
  → AnalyzeUseCase de :core (SAM 3.1 + LLM) → GlassesVoice (ElevenLabs + MediaPlayer)
```

1. **Collecte** — `GlassesFramePump.kt` : les frames `isCodecConfig` sont ignorées (elles n'ont
   pas d'image et évinceraient une vraie frame du canal) puis envoyées par `trySend` : l'envoi
   ne suspend jamais, donc une consommation lente ne bloque pas le flux.
2. **Canal** — `Channel<VideoFrame>(Channel.CONFLATED)` (`GlassesSessionRuntime.kt:43`) : la
   frame la plus récente écrase l'attente, rien ne s'empile.
3. **Étranglement** — `consumeFrames` (`GlassesFramePump.kt:38-44`) : attente calculée par
   `throttleDelayMs` (`GlassesSessionPolicy.kt:100`), puis re-lecture non bloquante pour
   préférer une frame plus fraîche à celle tenue pendant 1,5 s (`FRAME_THROTTLE_MS`,
   `GlassesSessionPolicy.kt:22`).
4. **Verdict** — `frameVerdict` (`GlassesSessionPolicy.kt:148`) écarte compressé, config codec
   et dimensions nulles avant toute copie. La compression est testée **en premier**.
5. **Copie** — `readRemainingBytes` (`GlassesFrameEncoder.kt:97`) duplique le `ByteBuffer` sans
   déplacer la position du buffer du SDK (testé : `GlassesFrameEncoderTest`).
6. **Disposition** — `inferYuv420Layout` (`FrameTypes.kt:134`) déduit les strides **de la seule
   taille du buffer** : alignements de ligne 1 à 256 px, deux conventions de chroma, puis repli
   « serré ». Le SDK n'expose aucun format.
7. **Conversion** — `YuvConverter.yuv420ToArgb` (`FrameTypes.kt:76`) : BT.601 en arithmétique
   entière, sans allocation dans la boucle, refus explicite des entrées incohérentes.
8. **Encodage** — `encode` (`GlassesFrameEncoder.kt:65`) : `Bitmap` ARGB → recadrage
   centré 16:9 (`cropTo16x9`, `GlassesFramePolicy.kt` : le 504×896 du SDK perd le haut et
   le bas) → réduction sous `MAX_IMAGE_EDGE = 1024` (`:13`, via `scaleDown`, `:86`) →
   JPEG qualité 85 (`:16`) → base64 sans préfixe `data:`.
9. **Horodatage** — `frameTimestampMs` (`GlassesFramePolicy.kt`) : µs du SDK → ms, sinon
   `SystemClock.elapsedRealtime()`. Les deux branches ne sont **pas** dans le même référentiel
   (voir §5).
10. **Aval** — le contrôleur de l'écran (`BenchController.onFrame`, `BenchScreen.kt:290`) journalise
    toujours ; si « Analyser » est actif, il appelle `AnalyzeUseCase` (`:core`) puis
    `GlassesVoice.say`.

Toute la décision vit hors Android : `GlassesSessionPolicy.kt` (états d'enregistrement, erreurs
de flux, verdict de frame, étranglement, `CycleLatch`), `GlassesFramePolicy.kt` (horodatage,
recadrage 16:9, réduction), `SpikeLog.kt` (journal, messages courts). C'est ce qui rend les tests
JVM possibles sans stub du SDK.

## 3. Les 24 i/s : ce qui est demandé, ce qui est mesuré

- **Demandé** : `StreamConfiguration(videoQuality = MEDIUM, frameRate = 24)`
  (`GlassesDeviceSession.kt:44-46`).
- **Identique au défaut SDK** (bytecode) : `StreamConfiguration()` vaut `audioCodec = null`,
  `videoQuality = MEDIUM`, `frameRate = 24`, `compressVideo = false`. La configuration explicite
  du banc ne change donc **rien** aux défauts ; la production, elle, les change (15 + compressé).
- **Jamais mesuré** : aucun débit, aucune latence, aucune perte de frame observés — pas de
  lunettes ici. 24 est une valeur **demandée**, pas une cadence constatée.
- **Sortie bien plus lente que l'entrée** : même si le flux tient 24 i/s, le banc ne convertit
  et n'envoie qu'au plus une frame toutes les `FRAME_THROTTLE_MS = 1_500L`
  (`GlassesSessionPolicy.kt:22`), soit ≈ **0,67 image/s**. Les frames intermédiaires sont jetées
  par le canal conflaté : « pipeline à 24 fps » désigne le flux d'entrée, pas la cadence utile.

## 4. Ce que le bytecode prouve (relevé du 26/10, `javap`)

| Élément | Constat |
|---|---|
| `VideoFrame` | `(ByteBuffer, int width, int height, long presentationTimeUs, boolean isCompressed, boolean isCodecConfig)` + 6 accesseurs. **Ni format, ni rowStride, ni pixelStride** → justifie `inferYuv420Layout` |
| `StreamConfiguration` | 4 paramètres, défauts **null / MEDIUM / 24 / false** (constructeur synthétique) |
| `VideoFormat.getDefaultFormat()` | **H.265, 504×896, 30 i/s** |
| `Stream` | `state`, `videoStream`, `audioStream`, `errorStream`, `start()`, `capturePhoto()`, `stop()` |
| `Camera` | `state`, `stream`, `photo`, `stop()` |
| `DeviceSession` | `state`, `errors`, `start()`, `stop()` ; `addCamera` est une **extension** du module camera |
| énumérations | `RegistrationState` (5), `StreamError` (8, dont `CRITICAL_STREAM_ERROR`), `DeviceSessionState` (6), `StreamState` (7), `CameraState` (4), `VideoQuality` (3) |
| `minSdk` des AAR | **29** dans les trois manifestes (core, camera, mockdevice) → c'est ce qui impose `minSdk = 29` à `:app` |
| tailles des AAR 1.0.1 | core 8 979 777 o, camera 784 799 o, mockdevice 871 060 o = **10 635 636 o ≈ 10,64 Mo** (10,14 Mio) |

Ces faits viennent des AAR du cache Gradle local, pas d'un téléchargement de cette relecture.

## 5. Supposé, non vérifié, invérifiable ici

À lire avant toute conclusion matérielle. **Rien de cette liste n'a été observé sur lunettes.**

- **Format du buffer** : le SDK ne l'expose pas. `inferYuv420Layout` le déduit de la taille, et
  l'ambiguïté est **mesurée** : un NV12 serré fait exactement la même taille qu'un I420 serré
  (24 o en 4×4) → l'inférence renvoie une disposition planaire (test
  `un buffer NV12 serre est indiscernable d un I420 par la taille`). Une vraie frame NV12 serait
  donc lue avec des couleurs fausses. Le décodeur du SDK **demande** bien `color-format = 19`
  (`COLOR_FormatYUV420Planar`), vérifié au `javap` le 26/10 ; ce qui reste ouvert n'est pas la
  constante demandée mais le format **effectivement** livré et les strides, que le SDK n'expose
  pas.
- **Padding de ligne réel** : les alignements testés vont de 1 à 256 px ; si le matériel en
  utilise un autre, le repli « serré » décale l'image.
- **Cadence, latence, pertes, reprises de session** : non mesurées.
- **`presentationTimeUs`** : référentiel inconnu (horloge média côté lunettes/décodeur vs
  horloge monotone du téléphone) — documenté dans `frameTimestampMs`, à unifier si l'aval
  compare ces valeurs.
- **Contenu de `mock_feed.mp4`** : le harnais attend du H.265 ; le fichier n'a pas été inspecté.
- **Sortie audio vers les lunettes** : le code de routage existe (`GlassesVoice.kt:63-110`) mais
  n'a jamais tourné ici.
- **Micro des lunettes** : la production sait collecter la piste audio du flux en WAV 16 kHz
  mono (`glasses/GlassesAudio.kt`) ; jamais exécuté sur une paire.
- **Appairage réel, Developer Mode, firmware, app Meta AI** : aucun essai possible.

## 6. Prérequis et placeholders (portés par `:app`)

- **Developer Mode** dans l'app Meta AI : c'est lui qui rend l'attestation facultative. D'où des
  placeholders à `"0"` par défaut, injectés par Gradle dans les deux `meta-data`
  `com.meta.wearable.mwdat.APPLICATION_ID` / `CLIENT_TOKEN`
  (`app/src/main/AndroidManifest.xml:34-39`). Avec une vraie attestation, ces deux valeurs
  doivent être remplies.
- **`minSdk 29`** : imposé par les AAR (§4), pas un choix — il vaut maintenant pour toute
  l'app, banc compris.
- **`Wearables.initialize` une seule fois, avant tout autre appel** : c'est `:app` qui
  l'initialise (`glasses/GlassesController.kt:75`), au démarrage. Le banc n'a plus
  d'initialisation à lui ; le KDoc de `bench/GlassesSession.kt:20` cite encore l'ancienne
  classe `GlassesApp`, supprimée — lien mort à corriger, sans effet sur le comportement.
- **`addCamera` seulement à `STARTED`**, sinon la session répond `SESSION_IDLE`
  (`GlassesDeviceSession.kt:39`).
- **Deep-link de retour d'enregistrement** : le filtre `VIEW` + `BROWSABLE` et le schéma
  `samvision` sont déclarés une seule fois (`AndroidManifest.xml:51-56`) et consommés par
  `Wearables.handleIntent` dans `MainActivity.kt`. Le banc n'a plus de schéma à lui : c'est
  l'app qui reçoit le retour, et c'est exactement ce que la fusion cherchait.
- **Permissions demandées en runtime** : `BLUETOOTH_CONNECT` (API 31+) et `CAMERA`.
  `MODIFY_AUDIO_SETTINGS` est déclarée (`AndroidManifest.xml:15`) pour le routage de la voix du
  banc.

## 7. Erreurs typées à gérer

- `DatResult<T, E>` : `fold`, `onFailure(error, cause)`, `getOrNull`, `errorOrNull`,
  `getOrThrow` (tests seulement). Le code du banc n'utilise que les quatre premiers.
- `DatError` : `description` + `getLocalizedDescription(Context)` — ce dernier exige un
  `Context`, d'où le repli de `GlassesRuntime.describe` (`GlassesSessionRuntime.kt:76`).
- `StreamError` : 8 valeurs. Seule `CRITICAL_STREAM_ERROR` arrête le cycle
  (`streamErrorDisposition`, `GlassesSessionPolicy.kt:120`) ; batterie, charnière, thermique et
  timeout sont transitoires côté DAT et ne coupent rien.
- `DeviceSessionError`, `PermissionError`, `CaptureError` : jamais traités nommément ici — les
  `fold`/`onFailure` se contentent de `description`. Assumé pour un banc.

## 8. Le mock : ce qu'il prouve, ce qu'il ne prouve pas

`MockGlassesHarness` (variante `debug`) active `MockDeviceKit` (`:45`), réutilise ou apparie des
`RAYBAN_META` simulées (`:166`), les prépare au streaming — allumées, portées, dépliées, sinon
le mock refuse de streamer — (`:183`), puis alimente la caméra simulée par la caméra arrière du
téléphone (`:75`) ou par un fichier vidéo (`:106`). L'asset `mock_feed.mp4` est copié depuis
l'APK de test (`MockGlassesTest.kt:179`, asset décrit `:41`). `dropSimulatedPairs` (`:144`) retire les appareils
simulés laissés par un run précédent : le SDK s'en souvient après un process tué, et
`AutoDeviceSelector` les aurait pris pour une vraie paire.

Depuis la fusion, l'API du mock est un **contrat** : `BenchMock` (`bench/BenchMock.kt`) est
implémenté deux fois, `MockControl` en `src/debug` (le vrai mock) et en `src/release` (un stub
muet). Une signature qui bouge d'un côté casse la compilation de l'autre au lieu de dériver en
silence.

**Prouvé par le mock** : la chaîne DAT s'ouvre sans matériel (session, caméra, flux, frames), et
le harnais ne lève jamais — toutes les erreurs repartent dans le journal.

**Non prouvé** : latence et débit Bluetooth, optique (champ, distorsion, exposition, rolling
shutter), routage audio réel, permissions réelles de l'app Meta AI, firmware, thermique et
batterie, mise en pause du flux quand les lunettes sont retirées. La résolution `MEDIUM`
(504×896, §4) est un format de flux, pas une mesure d'image.

## 9. Conclusions qui ne valent que pour le banc

1. **Le « pipeline YUV 24 fps » est un chemin de banc, pas celui de la production.** La
   production demande 15 i/s avec `compressVideo = true` (`GlassesCamera.kt:129`, `:63`) et ne
   consomme pas le flux vidéo : elle prend une photo à la demande. Aucun chiffre de frame, de
   latence ou de qualité obtenu ici ne se transpose à l'app.
2. **24 i/s est une demande de configuration**, égale au défaut du SDK, jamais une cadence
   observée ; et la sortie du banc est bornée à ≈ 0,67 image/s par l'étranglement (§3).
3. **Le convertisseur YUV n'a de sens que parce que `compressVideo = false`.** Dès que le
   bitstream est compressé, le banc jette la frame (`FrameVerdict.DROP_COMPRESSED`) : décoder du
   H.265 demanderait un `MediaCodec` complet, hors périmètre. Ce rejet est un choix, pas une
   mesure.
4. **Le routage audio vers les lunettes n'existe que dans le banc** (`GlassesVoice.kt`).
5. **Deux constantes restent dupliquées, et ce n'est plus une nécessité** :
   `MAX_IMAGE_EDGE = 1024` et `JPEG_QUALITY = 85` sont redéclarées dans
   `bench/GlassesFrameEncoder.kt:13,16` alors que `app/src/main/java/dev/sam3vision/app/ImageSource.kt:17-18`
   les définit déjà. L'ancienne justification — « le spike ne dépend pas de `:app` » — est morte
   avec le module : les deux fichiers sont maintenant dans le même module. À réutiliser.
6. **Le mock ne remplace pas le matériel** (§8) : tout ce qui est « ça marche » ici vaut pour le
   harnais, pas pour une paire de lunettes.

## 10. Reprendre le banc : ce qui bloque encore

- **Du matériel** pour tout ce que le mock ne prouve pas (§5, §8) : appairage, Developer Mode,
  firmware, audio, thermique.
- **Le format de frame** : à trancher sur matériel — I420 ou NV12, et avec quel padding. C'est la
  première chose à regarder, et l'ambiguïté est déjà écrite noir sur blanc dans les tests (§5).
- **L'asset de test `frame-64.jpg`**, absent du classpath de `:app` (§0) : `ChaineCompleteTest`
  échoue sans lui.
- **Un appareil ou un émulateur** pour `MockGlassesTest` (instrumentation, avec asset vidéo) : la
  CI ne l'exécute pas (voir [`glasses-spike-ci.md`](glasses-spike-ci.md)).
- **`mockdevice` hors de l'APK public** : déjà fait — `debugImplementation`
  (`app/build.gradle.kts:184`), le stub `src/release` neutralise le bouton « Mock ».