# Fiche de référence — DAT (Meta Wearables Device Access Toolkit) et module `:glasses-spike`

> **Périmètre** : le module `glasses-spike/` (namespace `dev.sam3vision.glasses`) et cette fiche.
> **Relu le 26/10/2026** sur cet arbre (`BaptisteFaisy/vision`, commit unique de ce dépôt), après
> durcissement des tests du module. Deux sources seulement :
> - **le code**, cité `fichier:ligne` — c'est l'autorité ;
> - **le bytecode** des AAR `com.meta.wearable:mwdat-*` **1.0.1** présentes dans le cache Gradle de
>   la machine (`javap` sur `classes.jar` extrait de l'AAR) : rejouable à tout moment, sans réseau.
>
> **Aucune paire de lunettes n'était disponible ici.** Tout ce qui touche au matériel est marqué
> **non vérifié** (§5) et n'est jamais présenté comme mesuré.

## 0. Le module en chiffres (mesuré ici)

| | |
|---|---|
| fichiers Kotlin | **21** — 14 dans `src/main` (1 596 l.), 6 dans `src/test` (642 l.), 1 dans `src/androidTest` (135 l.) = **2 373 l.** |
| plus gros fichier | `GlassesVoice.kt` (253 l.) ; plus grosse fonction `play` (43 l.) — limites 500 / 50 respectées |
| tests | **56 tests JVM verts** (`:glasses-spike:testDebugUnitTest`) + **1** test d'instrumentation (`MockGlassesTest`), jamais exécuté en CI |
| Android | `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`, JVM 17, `versionName = "0.1-spike"` (`glasses-spike/build.gradle.kts:24-31`) |
| dépendances | `project(":core")`, `mwdat-core`, `mwdat-camera`, `mwdat-mockdevice` (1.0.1, `gradle/libs.versions.toml:21`) |
| hors `.kt` | `build.gradle.kts`, `proguard-rules.pro` (vide), `src/main/res/values/themes.xml`, asset `src/androidTest/assets/mock_feed.mp4` (54 964 o) |

Déclaré une seule fois (`settings.gradle.kts:20`) ; `:app` ne dépend pas du module. `mockdevice`
part dans l'APK de debug sous `TODO(spike)` (`build.gradle.kts:76`) : à retirer avant tout build
public.

## 1. Deux codes, deux responsabilités

Le spike et `:app` parlent au même SDK mais **ne font pas la même chose**. Comparaison lue dans les
deux arbres (le code de `:app` n'est cité que pour la comparaison, il n'est pas modifié ici) :

| | Spike `glasses-spike/` | App `app/…/glasses/` |
|---|---|---|
| cadence demandée | **24** (`GlassesDeviceSession.kt:12`, `:44-47`) | **15** (`GlassesCamera.kt:80`, `:46`) |
| `compressVideo` | jamais passé → défaut SDK `false` : YUV brut | **`true`** (`GlassesCamera.kt:47`) : bitstream H.265 |
| flux vidéo | `videoStream` collecté, étranglé à 1 frame / 1,5 s (`GlassesFramePump.kt:20-57`) | **aucun abonnement** à `videoStream` (0 occurrence dans `app/src`) |
| image | convertisseur YUV420 écrit pour le spike (`FrameTypes.kt:30-151`) | photo à la demande `capturePhoto()` (`GlassesCamera.kt:62`) → `PhotoData` (`GlassesFrames.kt:40`) |
| décodage des frames | YUV → ARGB → `Bitmap` → JPEG 85 (`GlassesFrameEncoder.kt:42-91`) | `VideoFrame.toBitmapOrNull()` existe (`GlassesFrames.kt:22`) mais **n'a aucun appelant** |
| audio | routage Bluetooth des lunettes (`GlassesVoice.kt:56`, `:194`) | aucun routage (0 occurrence d'`AudioManager` dans `app/src`) |
| clés | `BuildConfig` rempli depuis `local.properties` (`build.gradle.kts:38-43`) | store de réglages (`AppViewModel.kt:83`, `:144`) |
| UI | un écran, un journal, 4 boutons (`MainActivity.kt:213-238`) | bloc « Lunettes » des Réglages |

Conséquence : **une conclusion prise sur le spike ne s'applique pas à l'app** (24 i/s + YUV ne sont
pas ce que l'app envoie), et l'inverse non plus. Voir §9.

## 2. Le pipeline, étape par étape

```
Wearables (SDK DAT) → Stream.videoStream
  → collectFrames      : jette les frames de config codec, dépose dans un canal CONFLATÉ
  → consumeFrames      : attend la fenêtre de 1,5 s, garde la frame la plus récente
  → GlassesFrameEncoder: verdict → octets → disposition YUV → ARGB → Bitmap → ≤1024 px → JPEG 85 → base64
  → MainActivity.onFrame (journal toujours ; réseau seulement si « Analyser » est activé)
  → AnalyzeUseCase de :core (SAM 3.1 + LLM) → GlassesVoice (ElevenLabs + MediaPlayer)
```

1. **Collecte** — `collectFrames` (`GlassesFramePump.kt:20-27`) ignore les frames
   `isCodecConfig` (elles n'ont pas d'image et évinceraient une vraie frame du canal) puis fait
   `frames.trySend` : l'envoi ne suspend jamais, donc une consommation lente ne bloque pas le flux.
2. **Canal** — `Channel<VideoFrame>(Channel.CONFLATED)` (`GlassesSessionRuntime.kt:43`) : la frame la
   plus récente écrase l'attente, rien ne s'empile.
3. **Étranglement** — `consumeFrames` (`GlassesFramePump.kt:41-57`) : attente calculée par
   `throttleDelayMs` (`GlassesSessionPolicy.kt:100`), puis re-lecture non bloquante
   (`frames.tryReceive()`, `:48`) pour préférer une frame plus fraîche à celle tenue pendant 1,5 s.
4. **Verdict** — `frameVerdict` (`GlassesSessionPolicy.kt:148`) écarte compressé, config codec et
   dimensions nulles avant toute copie (`GlassesFrameEncoder.kt:50`).
5. **Copie** — `readRemainingBytes` (`GlassesFrameEncoder.kt:97`) duplique le `ByteBuffer` sans
   déplacer la position du buffer du SDK (testé : `GlassesFrameEncoderTest`).
6. **Disposition** — `inferYuv420Layout` (`FrameTypes.kt:134-151`) déduit les strides **de la seule
   taille du buffer** : alignements de ligne 1 à 256 px, deux conventions de chroma (alignée comme Y,
   ou à la moitié du pas de Y), puis repli « serré ». Aucun format n'est exposé par le SDK.
7. **Conversion** — `YuvConverter.yuv420ToArgb` (`FrameTypes.kt:64-118`) : BT.601 en arithmétique
   entière, sans allocation dans la boucle, refus explicite des entrées incohérentes.
8. **Encodage** — `encode` (`GlassesFrameEncoder.kt:65-83`) : `Bitmap` ARGB → réduction sous
   `MAX_IMAGE_EDGE = 1024` (`:13`, via `scaledDimensions`) → JPEG qualité 85 (`:16`) → base64 sans
   préfixe `data:`.
9. **Horodatage** — `frameTimestampMs` (`GlassesFramePolicy.kt`) : µs du SDK → ms, sinon
   `SystemClock.elapsedRealtime()` (`GlassesFrameEncoder.kt:77`). Les deux branches ne sont **pas**
   dans le même référentiel (voir §5).
10. **Aval** — `MainActivity.onFrame` (`:151-166`) journalise toujours ; si « Analyser » est actif, il
    appelle `AnalyzeUseCase` (`:core`) puis `GlassesVoice.say` (`GlassesVoice.kt:81`).

Toute la décision vit hors Android : `GlassesSessionPolicy.kt` (états d'enregistrement, erreurs de
flux, verdict de frame, étranglement, `CycleLatch`), `GlassesFramePolicy.kt` (horodatage, réduction),
`SpikeLog.kt` (journal, messages courts). C'est ce qui rend les 56 tests JVM possibles sans stub du
SDK.

## 3. Les 24 i/s : ce qui est demandé, ce qui est mesuré

- **Demandé** : `StreamConfiguration(videoQuality = MEDIUM, frameRate = 24)`
  (`GlassesDeviceSession.kt:44-47`), appliqué seulement quand la session atteint `STARTED`
  (`:33-40`).
- **Identique au défaut SDK** (bytecode) : `StreamConfiguration()` vaut `audioCodec = null`,
  `videoQuality = MEDIUM`, `frameRate = 24`, `compressVideo = false`. La configuration explicite du
  spike ne change donc **rien** aux défauts ; l'app, elle, les change (15 + compressé).
- **Jamais mesuré** : aucun débit, aucune latence, aucune perte de frame observés — pas de lunettes
  ici. 24 est une valeur **demandée**, pas une cadence constatée.
- **Sortie bien plus lente que l'entrée** : même si le flux tient 24 i/s, le spike ne convertit et
  n'envoie qu'au plus une frame toutes les `FRAME_THROTTLE_MS = 1_500L`
  (`GlassesSessionPolicy.kt:22`), soit ≈ **0,67 image/s**. Les frames intermédiaires sont jetées par
  le canal conflaté : « pipeline à 24 fps » désigne le flux d'entrée, pas la cadence utile.

## 4. Ce que le bytecode prouve (vérifié ici, `javap`)

| Élément | Constat |
|---|---|
| `VideoFrame` | `(ByteBuffer, int width, int height, long presentationTimeUs, boolean isCompressed, boolean isCodecConfig)` + 6 accesseurs. **Ni format, ni rowStride, ni pixelStride** → justifie `inferYuv420Layout` |
| `StreamConfiguration` | 4 paramètres, défauts **null / MEDIUM / 24 / false** (constructeur synthétique) |
| `VideoFormat.getDefaultFormat()` | **H.265, 504×896, 30 i/s** — confirme le commentaire de `GlassesFrameEncoder.kt:43-48` |
| `Stream` | `state`, `videoStream`, `audioStream`, `errorStream`, `start()`, `capturePhoto()`, `stop()` |
| `Camera` | `state`, `stream`, `photo`, `stop()` |
| `DeviceSession` | `state`, `errors`, `start()`, `stop()` ; `addCamera` est une **extension** du module camera |
| énumérations | `RegistrationState` (5), `StreamError` (8, dont `CRITICAL_STREAM_ERROR`), `DeviceSessionState` (6), `StreamState` (7), `CameraState` (4), `VideoQuality` (3) |
| `minSdk` des AAR | **29** dans les trois manifestes (core, camera, mockdevice) → c'est ce qui impose `minSdk = 29` au module |
| tailles des AAR 1.0.1 | core 8 979 777 o, camera 784 799 o, mockdevice 871 060 o = **10 635 636 o ≈ 10,64 Mo** (10,14 Mio) |

Ces faits viennent des AAR du cache Gradle local, pas d'un téléchargement de cette relecture.

## 5. Supposé, non vérifié, invérifiable ici

À lire avant toute conclusion matérielle. **Rien de cette liste n'a été observé sur lunettes.**

- **Format du buffer** : le SDK ne l'expose pas. `inferYuv420Layout` le déduit de la taille, et
  l'ambiguïté est **mesurée** : un NV12 serré fait exactement la même taille qu'un I420 serré
  (24 o en 4×4) → l'inférence renvoie une disposition planaire (test
  `un buffer NV12 serre est indiscernable d un I420 par la taille`). Une vraie frame NV12 serait donc
  lue avec des couleurs fausses. Seul le commentaire du code affirme que le décodeur négocie
  `color-format = 19` (`GlassesFrameEncoder.kt:30-33`) : **non vérifié** dans le SDK ici.
- **Padding de ligne réel** : les alignements testés vont de 1 à 256 px ; si le matériel en utilise un
  autre, le repli « serré » décale l'image (commentaire `FrameTypes.kt:146-148`).
- **Cadence, latence, pertes, reprises de session** : non mesurées.
- **`presentationTimeUs`** : référentiel inconnu (horloge média côté lunettes/décodeur vs horloge
  monotone du téléphone) — documenté dans `frameTimestampMs`, à unifier si l'aval compare ces valeurs.
- **Contenu de `mock_feed.mp4`** : le harnais attend du H.265 ; le fichier n'a pas été inspecté
  (lecture binaire hors périmètre).
- **Sortie audio vers les lunettes** : le code de routage existe (`GlassesVoice.kt:56-73`, `:194-224`)
  mais n'a jamais tourné ici ; `MODIFY_AUDIO_SETTINGS` est déclarée (`AndroidManifest.xml:12`).
- **Appairage réel, Developer Mode, firmware, app Meta AI** : aucun essai possible.

## 6. Prérequis et placeholders

- **Developer Mode** dans l'app Meta AI : c'est lui qui rend l'attestation facultative. D'où
  `manifestPlaceholders["mwdat_application_id"] = "0"` et `["mwdat_client_token"] = "0"`
  (`build.gradle.kts:35-36`), injectés dans les deux `meta-data`
  `com.meta.wearable.mwdat.APPLICATION_ID` / `CLIENT_TOKEN` (`AndroidManifest.xml:25-30`). Avec une
  vraie attestation, ces deux valeurs devraient être remplies.
- **`minSdk 29`** : imposé par les AAR (§4), pas un choix du module.
- **`Wearables.initialize`** une seule fois, avant tout autre appel (`GlassesApp.kt:16-21`).
- **`addCamera` seulement à `STARTED`** (`GlassesDeviceSession.kt:33-40`), sinon la session répond
  `SESSION_IDLE`.
- **Deep-link de retour d'enregistrement** : filtre `VIEW` + `BROWSABLE` sur le schéma `samglasses`
  (`AndroidManifest.xml:45-50`), consommé par `Wearables.handleIntent`
  (`MainActivity.kt:195-199`). Sans ce filtre, l'enregistrement ne se termine jamais dans l'app.
- **Permissions demandées en runtime** : `BLUETOOTH_CONNECT` (API 31+) et `CAMERA`
  (`MainActivity.kt:185-193`). `RECORD_AUDIO` est **déclarée et jamais demandée**
  (`AndroidManifest.xml:10`) ; `BLUETOOTH` et `BLUETOOTH_CONNECT` sont redondants avec les AAR
  (`:5-6`), sans conséquence.

## 7. Erreurs typées à gérer

- `DatResult<T, E>` : `fold`, `onFailure(error, cause)`, `getOrNull`, `errorOrNull`, `getOrThrow`
  (tests seulement). Le code du spike n'utilise que les quatre premiers.
- `DatError` : `description` + `getLocalizedDescription(Context)` — ce dernier exige un `Context`,
  d'où le repli de `GlassesRuntime.describe` (`GlassesSessionRuntime.kt:76`).
- `StreamError` : 8 valeurs. Seule `CRITICAL_STREAM_ERROR` arrête le cycle
  (`GlassesSessionPolicy.kt:120-124`) ; batterie, charnière, thermique et timeout sont transitoires
  côté DAT et ne coupent rien.
- `DeviceSessionError`, `PermissionError`, `CaptureError` : jamais traités nommément ici — les
  `fold`/`onFailure` se contentent de `description`. Assumé pour un spike.

## 8. Le mock : ce qu'il prouve, ce qu'il ne prouve pas

`MockGlassesHarness` (`MockGlassesHarness.kt`) active `MockDeviceKit`, apparie des `RAYBAN_META`
simulées (`:137`), les allume / porte / déplie (`:148-165`, sinon le mock refuse de streamer), puis
alimente la caméra simulée par la caméra arrière du téléphone (`:71-94`) ou par un fichier vidéo
(`:100-115`). L'asset `mock_feed.mp4` est copié depuis l'APK de test
(`MockGlassesTest.kt:112-119`).

**Prouvé par le mock** : la chaîne DAT s'ouvre sans matériel (session, caméra, flux, frames), et le
harnais ne lève jamais — toutes les erreurs repartent dans le journal.

**Non prouvé** : latence et débit Bluetooth, optique (champ, distorsion, exposition, rolling shutter),
routage audio réel, permissions réelles de l'app Meta AI, firmware, thermique et batterie, mise en
pause du flux quand les lunettes sont retirées. La résolution `MEDIUM` (504×896, §4) est un format de
flux, pas une mesure d'image.

## 9. Conclusions du spike qui ne valent que pour le spike

1. **Le « pipeline YUV 24 fps » est un chemin de spike, pas celui de la production.** `:app` demande
   15 i/s avec `compressVideo = true` (`GlassesCamera.kt:80`, `:47`) et ne consomme pas le flux vidéo
   (0 occurrence de `videoStream` dans `app/src`) : elle prend une photo à la demande. Aucun chiffre de
   frame, de latence ou de qualité obtenu ici ne se transpose à l'app.
2. **24 i/s est une demande de configuration**, égale au défaut du SDK, jamais une cadence observée ;
   et la sortie du spike est bornée à ≈ 0,67 image/s par l'étranglement (§3).
3. **Le convertisseur YUV n'a de sens que parce que `compressVideo = false`.** Dès que le bitstream est
   compressé, le spike jette la frame (`FrameVerdict.DROP_COMPRESSED`) : décoder du H.265 demanderait
   un `MediaCodec` complet, hors périmètre. Ce rejet est un choix, pas une mesure.
4. **Le routage audio vers les lunettes n'existe que dans le spike** (`GlassesVoice.kt`). `:app` ne
   route rien (0 occurrence d'`AudioManager`).
5. **Les constantes recopiées** (`MAX_IMAGE_EDGE = 1024`, `JPEG_QUALITY = 85`,
   `GlassesFrameEncoder.kt:13,16`) sont **identiques** à celles de `:app`
   (`app/src/main/java/dev/sam3vision/app/ImageSource.kt:16-17`) et pourtant dupliquées : le spike ne
   dépend pas de `:app` et ne doit pas en dépendre. Si l'une bouge, l'autre ne suit pas — c'est voulu.
6. **Le mock ne remplace pas le matériel** (§8) : tout ce qui est « ça marche » ici vaut pour le
   harnais, pas pour une paire de lunettes.

## 10. Reprendre le spike : ce qui bloque

- **Du matériel** pour tout ce que le mock ne prouve pas (§5, §8) : appairage, Developer Mode,
  firmware, audio, thermique.
- **Le format de frame** : à trancher sur matériel — I420 ou NV12, et avec quel padding. C'est la
  première chose à regarder, et l'ambiguïté est déjà écrite noir sur blanc dans les tests (§5).
- **Un appareil ou un émulateur** pour `MockGlassesTest` (instrumentation, avec asset vidéo) : la CI ne
  l'exécute pas (voir `docs/glasses-spike-ci.md`).
- **`mockdevice`** doit sortir de l'APK avant tout build public (`TODO(spike)`,
  `build.gradle.kts:76`).
