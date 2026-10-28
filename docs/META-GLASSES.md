# Lunettes Ray-Ban Meta — la partie lunettes de l'app iSee

Périmètre de cette page : **`:app` seulement**, c'est-à-dire
`app/src/main/java/dev/sam3vision/app/glasses/`, ses tests JVM et le manifeste de `:app`.
Le banc d'essai lunettes (paquet `app/src/main/java/dev/sam3vision/app/glasses/bench/`, ouvert
depuis l'écran Lunettes) a sa propre fiche de référence (`docs/glasses-dat-spike.md`) et ses
propres conclusions : **rien ici ne se déduit du banc** (§10). Le module `:glasses-spike` a été
supprimé le 2026-10-27 — décision et chemins dans `docs/GLASSES-DECISION.md`.

Méthode, le 26/10/2026 : lecture intégrale des fichiers cités, et relevé des signatures du
SDK au `javap` **sur les AAR en cache Gradle** (`com.meta.wearable:mwdat-*:1.0.1`, relevé du
26/10 ; le pin du build est **1.0.0** depuis le 27/10, voir §13) — pas sur
une doc. Tout ce qui exige une paire physique est marqué **jamais essayé** : il n'y a ici ni
lunettes, ni téléphone, ni émulateur, ni `adb`. Aucun chiffre de terrain n'est repris comme
s'il était mesuré.

## 1. Les fichiers, et ce qu'ils font

| Fichier | Rôle |
|---|---|
| `GlassesController.kt` (471 l.) | le seul point d'entrée : init SDK, enregistrement, session, permissions, routage des gestes, analyse des frames collectées, connexion mock de recette |
| `GlassesPolicy.kt` (225 l.) | **logique pure** : gravité + textes des messages, phases joignables, libellés, raison de non-collecte — testée en JVM |
| `GlassesStatus.kt` (44 l.) | les 4 lignes d'état, en texte pur (testées) ; l'écran ne fait que les afficher |
| `GlassesCamera.kt` (203 l.) | flux 15 i/s `MEDIUM` `compressVideo = true`, photo à la demande, pompe vidéo vers l'accueil + collecteur derrière flag |
| `GlassesFrameCollector.kt` (83 l.) | pompe de production : `videoStream` → `JpegFrame`, 1 frame / 1,5 s, testée sur JVM |
| `GlassesVideoDecoder.kt` (253 l.) | bitstream H.265 → `ArgbImage` via `MediaCodec` (collecte), **jamais essayé** |
| `GlassesLiveDecoder.kt` (321 l.) | rendu du direct sur la surface de l'accueil (bitmap, sinon H.265 `MediaCodec` Annex B + `csd-0`), prouvé sur téléphone |
| `GlassesVideoLink.kt` (169 l.) | passerelle frames + `streaming`/`live` + taille vidéo entre les lunettes et l'accueil iSee |
| `GlassesFrameConvert.kt` (98 l.) | conversions partagées banc + production (YUV → ARGB → JPEG 85 → base64) |
| `GlassesFramePolicy.kt` (144 l.) | politique de frame partagée (étranglement, verdict, recadrage, horodatage), pure, testée en JVM |
| `GlassesAnalysisStep.kt` (67 l.) | maillon partagé « frame → `AnalyzeUseCase` → voix », testé en JVM |
| `FrameTypes.kt` (158 l.) | `JpegFrame`, `Yuv420Layout`, `YuvConverter` partagés, testés en JVM |
| `GlassesInputs.kt` (95 l.) | bouton capture → `CaptureAction`, tick 80 ms |
| `GlassesFrames.kt` (68 l.) | `PhotoData`/`VideoFrame` → `Bitmap` → JPEG du cache, lecture partagée des buffers |
| `GlassesMock.kt` (144 l.) | Mock Device Kit via le harnais du banc (réutilise la paire, allume/porte/déplie) + vidéo mock |
| `GlassesSection.kt` (127 l.) | bloc « Lunettes » des Réglages (Compose) |
| `GlassesState.kt` (42 l.) | `GlassesUiState`, `GlassesEvent`, `SessionPhase` |
| `core/…/glasses/CaptureGesture.kt` (71 l.) | lissage des appuis, pur, dans `:core` avec son test |

Branchements hors du dossier : `MainActivity.kt:52-55` (création + `initialize`),
`:71` (`handleIntent`), `:137` (`close()` si l'activité se termine), extra de recette
`--ez glasses_mock_connect` (`MainActivity.kt`, `GlassesController.debugMockConnect()`),
`SettingsScreen.kt:103-106` (le bloc), `AppNav.kt:91-101` (`GlassesBridge` : photo → analyse
ou chat), accueil iSee (`isee/IseeGlassesLive.kt`, `IseeScenePane.kt`, `IseePortraitCamera.kt` :
le direct occupe la rue du Portrait quand il est vivant).

## 2. Le parcours réel, bout en bout

1. **Enregistrement** — Réglages → Lunettes → « Enregistrer auprès de Meta AI »
   (`GlassesSection.kt:23-64`, texte du bouton : `connectLabel`, `GlassesPolicy.kt:142`).
   `register()` (`GlassesController.kt:133`) appelle `Wearables.startRegistration(activity)`
   et annonce « L'app Meta AI demande confirmation : accepte, puis reviens ici. ».
   La confirmation se joue **dans l'app Meta AI**, pas ici.
2. **Retour** — le deep-link `samvision` (`AndroidManifest.xml:49-54`) ramène dans
   l'activité ; `onNewIntent` → `routeIntent` → `handleIntent`
   (`MainActivity.kt:63-72`) → `Wearables.handleIntent(intent) { it.continueRegistration(host) }`
   (`GlassesController.kt:145`).
3. **Session** — « Démarrer la session » → `connect()` (`:150`), qui exige
   `registered` (`GlassesUiState.canConnect`, `GlassesState.kt:26`).
4. **Capacités** — à l'état SDK `STARTED`, `attachCapabilities` (`:199`) attache la caméra,
   puis, si la permission caméra est accordée, le bouton capture.
5. **Photo** — appui court → tick 80 ms → `PHOTO` → `capture()` (`:229`) →
   `stream.capturePhoto()` → JPEG écrit dans le cache (`GlassesFrames.kt:49`) →
   `GlassesEvent.PhotoTaken` → `AppNav.kt:91-101` → `viewModel.onPhoto(file)` → écran
   d'analyse, ou chat pour l'appui long.

## 3. Cycle de vie de la session

| État SDK (`DeviceSessionState`) | Phase affichée | Détail |
|---|---|---|
| `IDLE` | `IDLE` | rien d'ouvert |
| `STARTING` | `STARTING` | bouton « Session en cours de démarrage… » |
| `STARTED` | `STARTED` | caméra + bouton capture attachés |
| `PAUSED` | `STARTING` | reste « en cours » : une pause reprend, on ne propose pas de redémarrer |
| `STOPPING`, `STOPPED` | `STOPPED` | capacités libérées (`releaseCapabilities`, `:249`) |

Mapping : `GlassesController.kt:293` (`toPhase`), testé en JVM.

Règles de cycle de vie, toutes dans `GlassesController` :

- `initialize()` est idempotent (drapeau `started`, `:65-67`) et tourne **une fois par
  instance d'activité**. Le SDK étant un singleton de processus, une seconde initialisation
  renvoie `WearablesError.ALREADY_INITIALIZED` : ce cas est **traité comme un succès**
  (`:70-76`), sinon le bloc Lunettes restait inerte après une simple rotation.
- Une seule session à la fois. Une session **`STOPPED` n'est plus une session** : `connect()`
  l'oublie avant d'en rouvrir une (`dropSession`, `:182`), et `SessionPhase.allowsConnect()`
  (`GlassesPolicy.kt:123`) rend le bouton à nouveau actif — sans quoi le bouton restait grisé
  pour toujours après une perte de lien.
- Les deux collecteurs d'une session (`state`, `errors`) sont liés à **cette** session
  (`:168-180`) et annulés avec elle : une session passée ne peut plus réécrire l'état de la
  nouvelle (`if (created !== session) return`, `:190`).
- `disconnect()` (`:242`) libère les capacités, arrête puis oublie la session, et remet la
  phase à `IDLE`. `close()` (`:277`) fait la même chose et annule le scope.
- **Pas de garde-fou de durée** : si le SDK n'émet ni `STARTED` ni `STOPPED` après `start()`,
  la phase reste `STARTING` indéfiniment. Aucun délai n'a été ajouté (ce serait une règle
  non vérifiable sans matériel) — c'est un point ouvert, §12.

## 4. Permissions

Trois permissions distinctes, à ne pas confondre :

| Permission | Qui la porte | Où dans le code |
|---|---|---|
| Caméra **de l'app Meta AI** pour iSee | le SDK, côté Meta AI | `checkPermissionStatus(Permission.CAMERA)` (`GlassesController.kt:210-219`) ; si elle manque, message qui dit exactement quoi cocher |
| `BLUETOOTH_CONNECT` (runtime Android) | le téléphone | demandée au premier lancement avec caméra et micro (`FirstRunPermissions.kt:15-23`, `:46`, appelée par `MainActivity.kt:149`) |
| `android.permission.CAMERA` (runtime Android) | le téléphone | écran de capture du téléphone ; **inutile** pour le flux des lunettes |

Le manifeste de `:app` déclare `BLUETOOTH` (`maxSdkVersion="30"`) et `BLUETOOTH_CONNECT`
(`AndroidManifest.xml:10-13`) — ce que les AAR `mwdat-*` déclarent aussi (relevé au `javap`
le 26/10). Les `uses-feature` caméra et Bluetooth sont `required="false"` (`:15-20`) : l'app
reste installable sans.

Le refus de caméra côté Meta AI **n'est pas silencieux** : il produit
« Caméra non autorisée : app Meta AI → App connections → SAM Vision → Always allow. »
(`GlassesMessages.cameraPermissionMissing`, `GlassesPolicy.kt:55`) et le libellé photo
l'explique à l'écran (`photoHint`, `GlassesPolicy.kt:131`).

## 5. Flux d'images : 15 i/s, photo à la demande

- Configuration demandée au SDK : `StreamConfiguration(audioCodec = choice.toCodec(),
  videoQuality = MEDIUM, frameRate = FRAME_RATE, compressVideo = true)`
  (`GlassesCamera.kt:59-63`), `FRAME_RATE = 15` (`:129`). Le commentaire du code justifie la
  limite (« au-delà, le lien Bluetooth Classic casse l'image ») : c'est un **constat matériel
  repris tel quel, non remesuré ici**.
- L'audio n'est pas `null` : `grep -n "audioCodec = null" app/src/main` → 0. Le code monte un
  **palier** (`GlassesAudioPlan.ladder`, `GlassesAudioPlan.kt:28-29`) — 16 kHz mono préféré,
  48 kHz mono en repli, puis « sans micro » — et `GlassesCamera.start()` essaie les trois
  (`:43-54`), en annonçant chaque refus (`GlassesMessages.audioRefused`). Un refus de micro
  laisse donc la caméra seule, sans code mort. Ce qui reste **absent**, c'est le **routage de la
  voix vers les lunettes** : il n'existe que dans le banc (`bench/GlassesVoice.kt`).
- Le flux vidéo a **deux consommateurs** dans `GlassesCamera.kt` : le collecteur de production
  (`GlassesFrameCollector.kt` : `videoStream` → décodage H.265 ou YUV → `JpegFrame` →
  `AnalyzeUseCase` → voix haut-parleur), derrière un flag **éteint par défaut** (drapeau
  `collectFrames` : éteint, le flux n'est pas lu et la raison est écrite,
  `frameCollectionIdleReason`, testée en JVM) ; et la pompe vers l'accueil iSee, qui envoie
  chaque frame en copie défensive (`GlassesFrame`, jamais de buffer SDK vivant) vers
  `GlassesVideoLink`, rendue sur la surface de la rue.
- Rendu (`GlassesLiveDecoder`) : config codec → nourrit le décodeur ; bitmap décodable
  (JPEG compressé, ou brut ARGB_8888 via `VideoFrame.toBitmapOrNull()`, qui n'est donc plus
  du code mort) → dessiné ; sinon bitstream H.265 → `MediaCodec` vers la même surface.
  `live` ne passe à vrai qu'à la première image **rendue** : l'accueil n'ouvre jamais une
  rue noire. Entre le démarrage du flux (`streaming`) et cette première image, la rue existe
  en fantôme invisible (surface attachée, alpha nul) : sans elle aucun rendu ne serait
  possible et `live` resterait faux — même pendant une capture téléphone (l'enregistrement
  continue, la rue montre les lunettes). Chaque poussée est numérotée (le mock rejoue la
  même image : sans compteur, une seule émission) et chaque poussée vaut un essai de rendu
  (pas de boucle d'attente : elle affamait le décodeur, mesuré sur téléphone).
  Le recadrage « remplit la vue » (`cropTransform`) est pur et testé en JVM ;
  le reste (codec, surface, `Bitmap`) ne peut pas l'être, comme tout le dossier.
- Guidage (parité téléphone) : quand le direct vit, `GlassesProbeWiring` photographie
  la rue affichée (exactement ce que l'utilisateur voit), l'encode comme le téléphone
  (JPEG 320 q55 redressé, `uprightScaled` + `bitmapToJpegBase64` partagés) et nourrit
  `LiveProbe.onFrame` — masques, distances, consignes et voix suivent le même chemin.
  Pendant ce temps la caméra du téléphone n'envoie plus rien (mélanger les deux vues
  brouillerait le guidage). Config H.265 posée en `csd-0` (les décodeurs ignorent la
  config en buffer) ; le flux simulé n'envoie que des tranches (NAL 0/1, jamais
  32/33/34), donc le mock extrait les paramètres de `mock_feed.mp4` en repli.
- La lecture des buffers (`readRemainingBytes`, `GlassesFrames.kt`) est partagée par la
  pompe vers l'accueil : une seule copie des buffers.
- Les deux décodeurs coexistent (`GlassesVideoDecoder` pour la collecte, jamais essayé ;
  `GlassesLiveDecoder` pour le direct, prouvé sur téléphone) : les fusionner est un
  chantier à part, quand le premier aura vu du matériel.
- Seul `PhotoData.toBitmapOrNull()` sert (`GlassesFrames.kt:40`), appelé par
  `GlassesCamera.capture()` (`:66`) : `PhotoData.Bitmap` est utilisé tel quel, `PhotoData.HEIC`
  passe par `BitmapFactory.decodeByteArray`.
- Cadence réellement obtenue, latence, perte de trames : **non mesuré** (aucun matériel).
- La photo suit le même chemin qu'une capture téléphone (JPEG qualité 85 dans le cache,
  `JPEG_QUALITY`, `ImageSource.kt:17`) : une seule I/O, un seul format pour la suite.

## 6. SDK, placeholders, `minSdk 29`

- Dépendances : `mwdat-core`, `-camera`, `-inputs` en `implementation` et `-mockdevice` en
  `debugImplementation` (`app/build.gradle.kts:177-181`) : l'artefact de simulation n'entre plus
  dans l'APK public (voir §12). Version `1.0.0` — la seule que Meta AI installe
  sur la paire (voir §13)
  (`gradle/libs.versions.toml:21,53-56`), depuis Maven
  Central. Le Toolkit est en **developer preview** : c'est une donnée du vendeur
  (« Meta Platforms, Inc. », POM des artefacts), à considérer comme instable.
- Les placeholders `com.meta.wearable.mwdat.APPLICATION_ID` / `.CLIENT_TOKEN` sont dans le
  manifeste (`AndroidManifest.xml:32-37`) et sont injectés par Gradle
  (`app/build.gradle.kts:55-56`) depuis `local.properties`
  (`mwdat.application.id` / `mwdat.client.token`), **défaut `"0"` si absent** : en
  Developer Mode (aucun compte partenaire, aucune attestation), c'est le comportement par
  défaut du build, pas une constante du manifeste. Aucun secret n'est versionné.
- `minSdk = 29` (`app/build.gradle.kts:43`) : **imposé par les AAR**, dont le manifeste
  déclare `minSdkVersion="29"` (relevé sur les 4 artefacts le 26/10). Conséquence : Android
  10+ ; un téléphone en API 26-28 ne peut pas installer l'APK, lunettes ou pas. C'est le SDK
  qui l'exige, pas la paire.
- Prérequis externes (app Meta AI récente, firmware lunettes à jour, Developer Mode activé
  dans l'app Meta AI) : **donnée vendeur, non revérifiée ici** — c'est ce que l'app Meta AI
  impose à l'utilisateur, pas ce code.

## 7. Ce qui est annoncé à l'utilisateur, et ce qui restait muet

Règle tenue par le code : **un échec ne reste jamais silencieux** (accessibilité : l'app est
pensée pour être entendue). Chaque message porte sa gravité (`MessageKind`, `GlassesPolicy.kt:18`)
et tous les textes sont écrits une seule fois dans `GlassesMessages` (`:36-98`) :

- `ERROR` → bandeau rouge (`GlassesSection.kt:68`) : SDK indisponible, enregistrement refusé,
  session refusée/interrompue, caméra ou flux indisponible, bouton capture indisponible,
  photo refusée ou illisible, appareil simulé indisponible, retour d'enregistrement sans
  activité ;
- `WARNING` → bandeau orange : session déjà ouverte, caméra inactive, flux en erreur ou
  arrêté, aucun appareil simulé ;
- `INFO` → bandeau neutre : demande d'enregistrement lancée, mode Direct pas porté sur
  lunettes, lunettes simulées prêtes.

Chemins muets corrigés dans cette branche (aucun changement de comportement matériel, que du
diagnostic) :

| Avant | Maintenant |
|---|---|
| bouton de session grisé sans explication après `STOPPED` | session terminée rejoignable (`GlassesPolicy.kt:123`) |
| `connect()` sur une session déjà ouverte : `return` muet | « Une session lunettes tourne déjà » (`GlassesMessages.sessionAlreadyRunning`) |
| capture avec flux arrêté : `return null` muet | « Flux caméra arrêté… » (`GlassesCamera.kt:66-70`) |
| capture d'une photo non décodable : `null` muet | « Photo des lunettes illisible… » (`photoUndecodable`) |
| échec d'enregistrement côté Meta AI invisible | `registrationErrorStream` écouté (`GlassesController.kt:97-101`) ; `ALREADY_REGISTERED`/`ALREADY_UNREGISTERED` filtrés pour ne pas crier pour rien (`registrationErrorWorthTelling`, `GlassesPolicy.kt:104`) |
| rotation d'écran : `initialize()` → `ALREADY_INITIALIZED` pris pour une panne | traité comme un succès (`:74`) |

**Reste muet hors de l'écran Réglages** (défaut signalé, pas corrigé ici) : `GlassesEvent.Note`
(`GlassesState.kt:38`) est émis à chaque message mais **seul `PhotoTaken` est consommé**
(`AppNav.kt:92-95`). Un échec qui survient pendant que l'utilisateur regarde l'accueil iSee
n'apparaît nulle part : le bandeau n'existe que dans Réglages → Lunettes. Le corriger demande
de toucher `AppNav.kt`/la synthèse vocale, hors du périmètre de cette branche.

Le mode Direct n'est pas porté sur les lunettes : un double appui répond en clair
« Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone. »
(`GlassesMessages.liveNotOnGlasses`).

## 8. Ce qui marche / ce qui ne marche pas

Marche **par construction** (lu dans le code, pas constaté sur matériel) : enregistrement →
session → caméra → photo → analyse, les trois gestes du bouton capture, l'arrêt propre, les
lunettes simulées en debug, et le direct affiché à l'accueil (la rue du Portrait montre les
lunettes connectées, la boule dessous est inchangée).

Ne marche pas, et c'est écrit dans le code :

- pas de collecte des trames vidéo **par défaut** : le collecteur existe
  (`GlassesFrameCollector.kt`) mais son flag est éteint, et son décodeur H.265 n'a
  jamais vu de matériel — donc pas de descriptions automatiques sans geste explicite ;
  le direct affiché et le guidage, eux, tournent dès que les lunettes streament ;
- pas de routage de la voix vers les lunettes : le micro des lunettes est demandé (palier
  `GlassesAudioPlan`), mais la sortie audio reste le haut-parleur du téléphone (annoncé
  comme tel quand la collecte tourne) ; la permission `MODIFY_AUDIO_SETTINGS` est déclarée
  (`AndroidManifest.xml:15`), pour le banc et non pour l'app ;
- pas de tap ni tap-long du pavé : l'app ne s'abonne qu'à `InputSource.CAPTURE_BUTTON`
  (`GlassesInputs.kt:39-43`), ces gestes étant réservés par le système (pause/arrêt) ;
- pas d'invocation vocale par mot-clé : exigerait un projet Developer Center et une version
  publiée, donc au-delà du Developer Mode.

## 9. Ce qui n'a jamais été essayé sur des lunettes réelles

> **Mise à jour du 27/10/2026** : les six premiers points de cette liste ont depuis été
> exercés sur une paire réelle. Constats, chemins et blocages restants : **§13**.

Rien de cette liste n'a été exécuté ici : **jamais essayé**, à vérifier avant d'affirmer.

- l'appairage réel app Meta AI ↔ iSee, la confirmation, l'apparition dans
  *App connections → Developer mode apps* ;
- toute session réelle : `createSession`, `start()`, `PAUSED` sur paire reposée ;
- la permission caméra réelle du SDK et son passage en « Always allow » ;
- la photo réelle et sa latence ; le décodage `PhotoData.HEIC` sur un vrai téléphone ;
- le lien à 15 i/s : qualité, perte de trames, débit réel ;
- le comportement du bouton capture réel (le doublon `SHORT_PRESS` puis `DOUBLE_PRESS` est
  traité par le code, pas constaté) ;
- le mock : il n'a pas été lancé non plus (il demande un appareil Android) ;
- tout ce qui est thermique, autonomie, distance, optique : hors du périmètre du code.

## 10. Frontière avec le banc (`glasses/bench/`)

Le banc d'essai est dans **le même module** que cette page depuis le 2026-10-27, mais dans un
autre paquet : `app/src/main/java/dev/sam3vision/app/glasses/bench/`. Les helpers de frames
sont partagés dans le paquet parent (`FrameTypes.kt`, `GlassesFramePolicy.kt`,
`GlassesFrameConvert.kt`, `GlassesAnalysisStep.kt`) : le banc importe le parent, jamais
l'inverse — `glasses/` n'importe rien de `glasses/bench/` (mesuré : aucune occurrence de
`glasses.bench` hors du paquet), et **aucune conclusion du banc ne vaut pour l'app** :

| | production (`glasses/`, ici) | banc (`glasses/bench/`) |
|---|---|---|
| Cadence | 15 i/s (`GlassesCamera.kt`, `:62`) | 24 i/s (`bench/GlassesDeviceSession.kt:12`, `:46`) |
| Compression | `compressVideo = true` : bitstream H.265 | défaut SDK (`false`, YUV brut) + encodeur dédié (`bench/GlassesFrameEncoder.kt`) |
| Trames vidéo | direct vers l'accueil + guidage (`GlassesLiveDecoder`), collecteur derrière flag éteint (`GlassesFrameCollector.kt`, H.265 via `MediaCodec`, jamais essayé) | pompées et décodées (`bench/GlassesFramePump.kt:38-44`) |
| Audio | piste PCM demandée par palier (`GlassesAudioPlan`), pas de routage de sortie | routage de la voix vers les lunettes (`bench/GlassesVoice.kt:63`, `:97-110`) |
| Entrée | l'app elle-même (accueil, Réglages) | une route d'iSee : `Route.Bench` (`AppNav.kt:45`, `:95`) |
| Doc | cette page | `docs/glasses-dat-spike.md`, `docs/glasses-spike-ci.md` |

Les deux chemins coexistent volontairement : le banc peut essayer, l'app reste prudente. Les
chiffres, les formats et les conclusions de l'un ne se transposent pas à l'autre.

## 11. Tests JVM : ce qui est couvert, ce qui ne peut pas l'être

41 tests, aucun matériel requis (`app/src/test/java/dev/sam3vision/app/glasses/`) :

| Fichier | Couvre |
|---|---|
| `GlassesPolicyTest.kt` | phases joignables (`STOPPED` redevient joignable), libellés, phrases d'état, `photoHint`, raison de non-collecte documentée |
| `GlassesMessagesTest.kt` | gravité de chaque message, aucun échec en `INFO`, filtrage des faux positifs d'enregistrement, aucun secret recopié, voix HP annoncée |
| `GlassesUiStateTest.kt` | `connected`/`canConnect`/`hasDevice`, les 4 lignes d'état en français |
| `GlassesPhaseMappingTest.kt` | `DeviceSessionState` → phase affichée, dont `PAUSED` |
| `GlassesFrameCollectorTest.kt` | étranglement par rejet, routage compressé/brut/config, silences journalisés, libération |
| `GlassesVideoDecoderTest.kt` | pas de chroma et disposition de sortie du décodeur (partie pure) |

Reste dans `:core`, déjà testé là-bas : `CaptureGestureBuffer` (appui court retenu 350 ms,
double appui prioritaire, `reset`).

**Ne peut pas être testé en JVM** (et n'est donc pas couvert) : tout ce qui touche au SDK ou à
Android — `Wearables.*`, `DeviceSession`, `Stream`, `MockDeviceKit`, `Bitmap`, Compose. Aucun
test Robolectric n'est configuré dans `:app` ; on ne prétend pas tester ces chemins, on les
isole dans des fichiers courts et on met la décision dans `GlassesPolicy.kt`.

## 12. À trancher avant publication

| Point | État |
|---|---|
| `mwdat-mockdevice` embedding : **corrigé le 26/10** — `debugImplementation` (`app/build.gradle.kts:181`), `GlassesMock.kt` en `app/src/debug/`, `main` passe par l'interface `GlassesMockDevice.kt` + une fabrique réflexive (AGP interdit de redéclarer la classe dans `main`, qui ne peut pas la lier) ; les boutons restent gatés debug (`GlassesSection.kt:61-63`, `SettingsScreen.kt:106`) | **fait** — `:app:compileDebugKotlin`, `:app:compileReleaseKotlin` et `:app:testDebugUnitTest` verts (334 tests) |
| Aucun délai n'expire un `STARTING` ; **jamais essayé** sur lunettes | ouvert, assumé |
| Messages visibles seulement dans Réglages (§7) | ouvert, hors périmètre |
| `DonState.UNKNOWN` est affiché « non portées » (`GlassesStatus.kt:22-27`) : l'état inconnu et l'état « retirées » se ressemblent | ouvert, sans risque |
| Qualité d'image à 15 i/s, décodage HEIC, latences | **non mesuré** |
| Aucun test d'instrumentation des lunettes | assumé : il n'y a pas de matériel |
## 13. Mesuré sur une paire réelle (27/10/2026)

Paire `RB Meta 01TK`, téléphone `2c6852ac`, app en **Developer Mode** (`APPLICATION_ID` et
`CLIENT_TOKEN` à `"0"`), app Meta AI 295.0.0.37.164. Rien ici n'est déduit : chaque ligne a
été lue dans le journal du banc sur le téléphone.

| Constat | Preuve / conséquence |
|---|---|
| Le runtime DAT des lunettes est **1.0.0.0.0**, seule version que Meta sert | Meta AI → ☰ → Paramètres → Informations sur l'application → « Version du SDK DAT » ; repasse à « non installée » si le Developer Mode est désactivé (5 taps sur le numéro de version) |
| **SDK 1.0.1 refuse la paire** | session `STARTING → STARTED` puis coupée en ~70 ms, « Your glasses need an update » (`START_ERROR_DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`, constante absente du 1.0.0). D'où `mwdat = "1.0.0"` et la garde `DatPinTest` |
| Avec 1.0.0, la session **reste ouverte** | `session démarrée`, `caméra ajoutée`, `flux démarré` ; seul refus restant : `CAPABILITY_DENIED` (permission caméra) |
| Le lien se monte en ~2 à 10 s après l'allumage | `linkState` = `CONNECTING` puis `CONNECTED` ; une session créée avant rend `NO_ELIGIBLE_DEVICE` (déjà géré : `hasLinkedDevice`) |
| Le SDK publie son verdict de compatibilité | `Device.compatibility` = `COMPATIBLE` / `DEVICE_UPDATE_REQUIRED` / `SDK_UPDATE_REQUIRED`, déjà affiché par `GlassesRealCheck` |
| En Developer Mode, la page de permission caméra **n'existe pas** | ☰ → Lunettes → paire → Paramètres → Applications → « Applications du mode développeur(se) » → app : une seule ligne « Déconnecter » ; les libellés « Always allow » n'existent pas dans l'APK 295. Texte de `GlassesMessages` aligné sur ce chemin réel |
| Pas de canal public | Meta ne propose que le Developer Mode (sans invitation) et un canal privé « Beta », **sur invitation par e-mail du compte Meta** (wearables.meta.com) |
