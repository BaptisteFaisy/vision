# Lunettes Ray-Ban Meta — la partie lunettes de l'app iSee

Périmètre de cette page : **`:app` seulement**, c'est-à-dire
`app/src/main/java/dev/sam3vision/app/glasses/`, ses tests JVM et le manifeste de `:app`.
Le prototype `:glasses-spike` a sa propre doc (`docs/glasses-dat-spike.md`) et ses propres
conclusions : **rien ici ne se déduit du spike** (§10).

Méthode, le 26/10/2026 : lecture intégrale des fichiers cités, et relevé des signatures du
SDK au `javap` **sur les AAR en cache Gradle** (`com.meta.wearable:mwdat-*:1.0.1`) — pas sur
une doc. Tout ce qui exige une paire physique est marqué **jamais essayé** : il n'y a ici ni
lunettes, ni téléphone, ni émulateur, ni `adb`. Aucun chiffre de terrain n'est repris comme
s'il était mesuré.

## 1. Les fichiers, et ce qu'ils font

| Fichier | Rôle |
|---|---|
| `GlassesController.kt` (298 l.) | le seul point d'entrée : init SDK, enregistrement, session, permissions, routage des gestes |
| `GlassesPolicy.kt` (147 l.) | **logique pure** : gravité + textes des messages, phases joignables, libellés — testée en JVM |
| `GlassesStatus.kt` (32 l.) | les 4 lignes d'état, en texte pur (testées) ; l'écran ne fait que les afficher |
| `GlassesCamera.kt` (90 l.) | flux 15 i/s `MEDIUM` `compressVideo = true`, photo à la demande |
| `GlassesInputs.kt` (95 l.) | bouton capture → `CaptureAction`, tick 80 ms |
| `GlassesFrames.kt` (52 l.) | `PhotoData`/`VideoFrame` → `Bitmap` → JPEG du cache |
| `GlassesMock.kt` (90 l.) | Mock Device Kit + scène 640×480 |
| `GlassesSection.kt` (94 l.) | bloc « Lunettes » des Réglages (Compose) |
| `GlassesState.kt` (38 l.) | `GlassesUiState`, `GlassesEvent`, `SessionPhase` |
| `core/…/glasses/CaptureGesture.kt` (71 l.) | lissage des appuis, pur, dans `:core` avec son test |

Branchements hors du dossier : `MainActivity.kt:52-55` (création + `initialize`),
`:71` (`handleIntent`), `:137` (`close()` si l'activité se termine),
`SettingsScreen.kt:103-106` (le bloc), `AppNav.kt:91-101` (`GlassesBridge` : photo → analyse
ou chat).

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

- Configuration demandée au SDK : `StreamConfiguration(audioCodec = null,
  videoQuality = MEDIUM, frameRate = 15, compressVideo = true)`
  (`GlassesCamera.kt:45-50`), `FRAME_RATE = 15` (`:89`). Le commentaire du code justifie la
  limite (« au-delà, le lien Bluetooth Classic casse l'image ») : c'est un **constat matériel
  repris tel quel, non remesuré ici**.
- Le flux vidéo est **démarré mais jamais consommé** : l'app n'appelle nulle part
  `stream.videoStream` (mesuré : zéro occurrence dans `app/src/main`). `compressVideo = true`
  est donc un choix de lien, pas un chemin d'image.
- Conséquence assumée : `VideoFrame.toBitmapOrNull()` et son `rawToBitmap`
  (`GlassesFrames.kt:22-37`) **ne sont appelés par personne** — code mort côté app, gardé
  parce qu'une autre mission tient `:glasses-spike` et qu'il n'a rien à y faire. Il n'est pas
  testé (il faudrait un `Bitmap` Android).
- Seul `PhotoData.toBitmapOrNull()` sert (`GlassesFrames.kt:40`), appelé par
  `GlassesCamera.capture()` (`:66`) : `PhotoData.Bitmap` est utilisé tel quel, `PhotoData.HEIC`
  passe par `BitmapFactory.decodeByteArray`.
- Cadence réellement obtenue, latence, perte de trames : **non mesuré** (aucun matériel).
- La photo suit le même chemin qu'une capture téléphone (JPEG qualité 85 dans le cache,
  `JPEG_QUALITY`, `ImageSource.kt:17`) : une seule I/O, un seul format pour la suite.

## 6. SDK, placeholders, `minSdk 29`

- Dépendances : `mwdat-core`, `-camera`, `-inputs`, `-mockdevice`, version `1.0.1`
  (`gradle/libs.versions.toml:21,53-56`, `app/build.gradle.kts:136-139`), depuis Maven
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
lunettes simulées en debug.

Ne marche pas, et c'est écrit dans le code :

- pas de trames vidéo consommées par l'app (donc pas de mode Direct sur lunettes) ;
- aucun audio : `audioCodec = null` (`GlassesCamera.kt:46`) ; pas de routage Bluetooth de la
  voix vers les lunettes, et `MODIFY_AUDIO_SETTINGS` n'est pas déclaré par `:app` ;
- pas de tap ni tap-long du pavé : l'app ne s'abonne qu'à `InputSource.CAPTURE_BUTTON`
  (`GlassesInputs.kt:39-43`), ces gestes étant réservés par le système (pause/arrêt) ;
- pas d'invocation vocale par mot-clé : exigerait un projet Developer Center et une version
  publiée, donc au-delà du Developer Mode.

## 9. Ce qui n'a jamais été essayé sur des lunettes réelles

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

## 10. Frontière avec `:glasses-spike`

`glasses-spike/` est un **module séparé** (package `dev.sam3vision.glasses`, `:app` ne dépend
pas de lui). Il explore autre chose, et **aucune conclusion du spike ne vaut pour l'app** :

| | `:app` (ici) | `:glasses-spike` |
|---|---|---|
| Cadence | 15 i/s (`GlassesCamera.kt:89`) | 24 i/s (`GlassesDeviceSession.kt:12`) |
| Compression | `compressVideo = true` | autre choix, et un encodeur dédié (`GlassesFrameEncoder.kt`) |
| Trames vidéo | jamais consommées | pompées et décodées (`GlassesFramePump.kt:21`) |
| Audio | aucun | routage SCO (`GlassesVoice.kt:197-204`) + `MODIFY_AUDIO_SETTINGS` |
| Doc | cette page | `docs/glasses-dat-spike.md`, `docs/glasses-spike-ci.md` |

Les deux chemins coexistent volontairement : le spike peut essayer, l'app reste prudente.
Les chiffres, les formats et les conclusions de l'un ne se transposent pas à l'autre.

## 11. Tests JVM : ce qui est couvert, ce qui ne peut pas l'être

18 tests, aucun matériel requis (`app/src/test/java/dev/sam3vision/app/glasses/`) :

| Fichier | Couvre |
|---|---|
| `GlassesPolicyTest.kt` | phases joignables (`STOPPED` redevient joignable), libellés, phrases d'état, `photoHint` |
| `GlassesMessagesTest.kt` | gravité de chaque message, aucun échec en `INFO`, filtrage des faux positifs d'enregistrement, aucun secret recopié |
| `GlassesUiStateTest.kt` | `connected`/`canConnect`/`hasDevice`, les 4 lignes d'état en français |
| `GlassesPhaseMappingTest.kt` | `DeviceSessionState` → phase affichée, dont `PAUSED` |

Reste dans `:core`, déjà testé là-bas : `CaptureGestureBuffer` (appui court retenu 350 ms,
double appui prioritaire, `reset`).

**Ne peut pas être testé en JVM** (et n'est donc pas couvert) : tout ce qui touche au SDK ou à
Android — `Wearables.*`, `DeviceSession`, `Stream`, `MockDeviceKit`, `Bitmap`, Compose. Aucun
test Robolectric n'est configuré dans `:app` ; on ne prétend pas tester ces chemins, on les
isole dans des fichiers courts et on met la décision dans `GlassesPolicy.kt`.

## 12. À trancher avant publication

| Point | État |
|---|---|
| `mwdat-mockdevice` en `implementation` : l'artefact est **embarqué dans l'APK de release**, seuls les boutons sont en debug (`GlassesSection.kt:61-63`, `SettingsScreen.kt:106`) | ouvert — sortir l'artefact du build public |
| Aucun délai n'expire un `STARTING` ; **jamais essayé** sur lunettes | ouvert, assumé |
| Messages visibles seulement dans Réglages (§7) | ouvert, hors périmètre |
| `DonState.UNKNOWN` est affiché « non portées » (`GlassesStatus.kt:22-27`) : l'état inconnu et l'état « retirées » se ressemblent | ouvert, sans risque |
| Qualité d'image à 15 i/s, décodage HEIC, latences | **non mesuré** |
| Aucun test d'instrumentation des lunettes | assumé : il n'y a pas de matériel |