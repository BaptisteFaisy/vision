# CHANGELOG — vision (iSee)

Le journal part du 2026-10-26 après 19 h : le commit de départ, puis une entrée par fait
livré et ses preuves (tâche, fichiers, chiffres) dans l'arbre.

## 2026-10-28 — intro : voix Laure et fin de la phrase du balayage ; narration Direct : Baseten partout, InferHub retiré

- `intro_isee.mp3` régénéré en voix Laure (`3b8fXc91YHS1i2DYAlBQ`, `eleven_v3`,
  réglages du runtime) : 401 701 octets, 25,08 s (`ffprobe`), voix réelle
  (mean −19,3 dB, max −0,4 dB, `volumedetect`), 135 caractères (creator,
  28 078 → 28 213 / 130 005). Texte `ISEE_INTRO_TEXT` mot pour mot (307 caractères).
- La phrase « To listen to previous messages, swipe down » n'est plus ni dans le
  texte ni dans le MP3 : le balayage entre messages était déjà supprimé du code
  (27/10), seule la voix le disait encore. Jade US reste proposée dans Réglages.
- Commentaires et docs réalignés (`IntroAudio`, `IntroLogic`, `Settings`,
  `SpeechLanguage`, `TtsSettings`, `ELEVENLABS-TTS.md`, `VOIX-ANGLAIS.md`).
- Le cache de l'intro s'invalide quand l'asset change (`introCacheStale`,
  comparaison des tailles) : sans ça, une mise à jour rejouerait l'ancienne
  voix jusqu'au vidage du cache par Android. 1 test JVM (`UiLogicTest`).
  Preuve d'écoute : à confirmer à l'oreille sur le téléphone (pas de sortie
  audio sur le poste de travail).
- La narration du Direct parle au même endpoint et au même modèle Meta que les
  questions (`https://inference.baseten.co/v1`, `meta-models/muse-spark-open-1026`,
  `Settings.DEFAULT_LIVE_LLM_*`) : un seul fournisseur LLM dans l'app.
- Le budget de 200 tokens reste suffisant : le client coupe déjà le raisonnement
  interne (`enable_thinking=false`), la phrase ne paie plus les pensées. Clé de
  narration toujours dédiée (`liveLlmApiKey`, désormais Baseten) ; sans elle, muet.
- `InferHubLlmClient` renommé `HttpLlmClient` (plus aucun chemin d'exécution ne
  touche InferHub) ; `LlmFactory.inferHub` devient `httpLlm`. La migration des
  réglages d'avant (`migrateLegacyLlm`, InferHub + qwen → build) est conservée.
- Preuves : `ModelPinTest` (lignes questions + direct citées séparément),
  `LlmClientTest`, `HttpLlmClientTest`, CI verte exigée au merge.

## 2026-10-28 — lunettes : l'enregistrement du banc ne bloque plus le main thread

- ANR du 27/10 (`executing service …ACDCRegistrationService`) : `requestRegistration`
  appelait `Wearables.startRegistration` en ligne dans le collecteur `registrationState`,
  sur le main, pendant que le SDK tenait son verrou d'enregistrement — interblocage
  AB-BA avec un thread binder (traces `data_app_anr` du 27/10 11:22).
- L'étape `REQUEST_REGISTRATION` quitte désormais le thread de l'émetteur
  (`runsOffEmitter`, `GlassesSessionPolicy`, verrouillé par 3 tests JVM) et l'appel
  bloquant part sur IO, comme tous les autres appels DAT du cycle.

## 2026-10-28 — micro : le démarrage verrouillé ne tue plus le processus

- Le FATAL `ForegroundServiceDidNotStartInTime` (2× au relevé du jour, démarrage à froid,
  livraison d'`onStartCommand` hors délai) est structurellement impossible : l'armement
  passe par `startService`, et le service prend lui-même le premier plan en millisecondes
  (ou s'arrête, arrêt parlé).
- La décision de démarrage vit dans `:core` (`LockedMicStartRunner`, pur, chaque effet
  gardé) : premier plan ou arrêt, sans jamais lever — y compris si la notification, la
  publication d'état, la voix ou le premier plan (`SecurityException` micro, Android 14+)
  lèvent. 15 tests JVM verrouillent les chemins, dont l'injection d'effets qui lèvent.
- Comportement produit inchangé : mêmes états parlés, mêmes notifications, `START_STICKY`
  quand armé, service toujours debout après retrait des récentes (HyperOS).

## 2026-10-28 — caregiver : bleu et vert, proportions et badge Portrait

- Roue dentée noire, état dans une pastille arrondie comme sur la page blind person.
- Carte plus haute et remontée, sans défilement ; boule plus petite au liquide bleu et vert.
- Couleurs du verre préservées dans le port WebGL ; le shader natif blind person reste inchangé.
- Concurrence Gradle plafonnée à deux workers par build sur les runners partagés,
  sans relever les délais de CI ni retirer de contrôle.

## 2026-10-28 — caregiver : écran réellement livré dans l'APK

- WebView explicitement en MATCH_PARENT : évite un viewport CSS de hauteur nulle
  et l'écran blanc constaté sur le Samsung, notamment après rotation.
- Suppression de Retour, du titre Caregiver et d'Actualiser ; état du flux en
  haut à droite, paramètres par la roue dentée.
- Interface embarquée dans l'APK : fond blanc, même boule Portrait en gris,
  carte réduite en bas sans défilement, aucun bloc hors ligne ni légende dessous.
- Clé Google Maps injectée au build, préremplie et active depuis l'installation,
  champ déplacé dans les paramètres, sans clé en clair versionnée.

## 2026-10-27 — caregiver : Portrait gris et session distante

- Fond blanc, boule Portrait grise, phrase courante puis carte en bas, sur le
  site et dans la WebView Caregiver. Le shader Android gelé reste inchangé.
- Import du lien reçu depuis un autre téléphone ; aucune session choisie à
  partir de l'identifiant local du caregiver.
- iSee et Direct partagent le même relais pour les objets et la phrase. Le
  déplacement GPS est décrit seulement au-delà de son incertitude ; les
  repères se figent à la perte du signal et disparaissent après deux minutes.

## 2026-10-27 (15) — réglages en anglais : fond blanc, chevron, verre, THE USER / META GLASSES / TECHNICAL SETTINGS

- **Dire « settings » ouvre les Réglages** : `SettingsVoiceCommand` (pur, `:core`)
  reconnaît « settings », « open settings », « réglages », « paramètres » avant
  l'envoi au LLM ; `IseeViewModel` lève `IseeUiState.openSettings`, consommé par
  `IseeHome` qui pousse `Route.Settings`.
- **Page blanche, texte noir, tout en anglais** : palette locale `SettingsLight`,
  en-tête « Settings » + **chevron** dessiné au trait (plus de bouton « Retour »),
  boutons **verre** (`GlassButton` : fond translucide, bordure claire, ombre légère).
- **Trois sections** : THE USER (rôle Blind person / Caregiver + **lien ou code
  d'invitation** que l'aveugle envoie, bouton « Save & open caregiver map » désactivé
  tant que l'invitation est vide) ; META GLASSES (bloc lunettes sur carte sombre,
  ses textes testés en français inchangés) ; TECHNICAL SETTINGS (tout le reste,
  traduit). L'ancien titre « Réglages du backend » disparaît.
- **L'invitation ouvre le bon suivi** : `Settings.caregiverInvite` persisté dans les
  deux stores (parité `SettingsStoreParityTest` OK) ; `parseCaregiverInvite` accepte
  le code nu, « ?share= » ou le lien complet ; `caregiverShareLink` gagne sur les
  champs historiques pour la page Caregiver.
- **Vérifié** : `:app:testDebugUnitTest` vert (562 tests, dont `CaregiverShareLinkTest`)
  ; `:core:testDebugUnitTest` 1137 tests avec les 5 échecs connus
  `PrivateFileSettingsStoreTest` (préexistants, POSIX sur Windows), plus
  `MotionBenchTest` (budget µs/frame, machine lente — même flaque à froid déjà vu
  sans ce changement) et `LivePipelineTest` (passé au re-run isolé) ;
  `tools/check-kotlin-limits.py` OK ; `assembleDebug` vert.


## 2026-10-27 (14) — zéro mémoire des anciens messages + balayage supprimé

- **L'IA n'a plus aucune mémoire** : `IhearTranscript` perd `questionTurns()` /
  `turnsOf()` / `CHAT_HISTORY` ; `IhearAnswers` joint `emptyList()` à chaque question
  (`app/ihear/vm/IhearAnswers.kt`) — aucun tour n'est lu de la base, même en pleine
  session. `MediaCacheStore.history()` (« historique complet en mémoire ») supprimé.
  Le plafond `AskUseCase.MAX_HISTORY_TURNS` (20 tours) ne vaut plus que pour le Direct.
- **Les messages passés ne sont plus mémorisés en cache** : rien d'une session
  antérieure n'est restauré — table Room et `cacheDir/ihear` vidés à l'ouverture
  (`IhearViewModel.init`) et à la fermeture (`IhearSession.onClosed`), donc rien ne
  revient, ne s'affiche et ne repart vers le modèle.
- **Balayage entre messages supprimé** : machine `swipeStep`/`SwipeStep`,
  `firstSwipeDownDone`, `hasBubbleChoice`, `selectedEntryIndex` (`IhearState.kt`) ;
  callbacks `onVerticalDragStart/Move/End` (`IhearViewModel`, `IhearRootLayout`,
  `MainActivity`) ; rail vertical de `IhearGestureBrain` (le glissement verrouille
  toujours le geste — il ne devient jamais un tap — mais n'émet plus rien) ; haptique
  dédiée (`IhearHaptics.verticalSwipe*`, `HapticPattern.swipeCrest`) ; lecture ciblée
  (`IhearPlayback.playSelected`, `IhearIntro.replay`). Le message affiché est toujours
  le dernier (`IhearTranscript.refresh`).
- **UI** : pill d'instructions réduit à « Tap see / Hold ask » (`IseeStatusLine.kt`) ;
  intro sans la phrase de balayage (`IntroLogic.kt`, 351 → 307 caractères).
  ⚠️ `intro_ihear.mp3` annonce encore le swipe : à régénérer (procédure
  `docs/ELEVENLABS-TTS.md`).
- **Docs** : README (liste des gestes), `docs/ARCHITECTURE.md` (table des gestes +
  « historique borné à 20 tours » → historique vide), `docs/ELEVENLABS-TTS.md`,
  `docs/TESTING.md` (comptes de tests).
- **Tests** : `SwipeStepTest` (9) renommé `BubbleCountTest` (2) ; `IhearTranscriptTest`
  : les 2 tests d'historique deviennent « la sélection suit toujours la dernière
  bulle » ; `IhearAnswersTest` : « aucun historique — la question part seule, même si
  la base garde des messages » ; `UiLogicTest` : plus de « swipe down » dans l'intro ;
  `WaveformTest` −2 ; `IhearGestureBrainTest` sans callbacks verticaux.
- **Vérifié** (après rebase sur le master de 49 commits) :
  `:app:testDebugUnitTest` vert ; `:core:testDebugUnitTest` avec les 5 échecs
  `PrivateFileSettingsStoreTest` **retrouvés à l'identique sur un `origin/master`
  vierge** (permissions POSIX non supportées sur Windows) ;
  `tools/check-kotlin-limits.py` vert ; `assembleDebug` vert.

## 2026-10-27 (15) — film + parole : la photo est jointe, le double tap n'existe plus

- **Course capture/lecture réparée** (`IseePhotoShot`) : la lecture attend le rappel
  CameraX (borné, `CAPTURE_TIMEOUT_MS = 4 s`) et retente une capture si rien n'avait
  démarré (caméra froide refusant au début de la tenue). Avant : une tenue courte
  lisait un contexte encore vide et partait en texte seul — le modèle répondait
  « aucune image transmise » (vu dans `isee-media.db` sur le téléphone : une
  question avec image, la suivante sans).
- **Double tap supprimé** (décision produit) : détection retirée du cerveau des
  gestes (le tap simple part dès le relâchement, plus de fenêtre 450 ms),
  `onDoubleTap`, photo gelée/évaporation/pastilles, `photoPath`/`distanceLayer`
  isee et leurs branches d'affichage (carte + Portrait). L'appui long reste la
  seule prise de photo, silencieuse, jointe à la question. Onboarding et aide
  Réglages réduits aux deux gestes.
- **Preuves** : `IseePhotoShotTest` (10, dont attente du rappel et retentative),
  `IseeGestureBrainTest` (5), `IseeAnswersTest`, `OnboardingGesturesTest`,
  `IseeSceneTest` adaptés ; `:core:testDebugUnitTest` 1126/1126,
  `:app:testDebugUnitTest` 529/529 sur r630 (après rebase sur le master
  renommé `isee`) ; `check-kotlin-limits.py` OK. Recette téléphone (photo
  jointe à la question en filmant) après merge.

## 2026-10-27 (13) — cap vocal : le guidage marche vers la direction demandée

- **Ordre vocal de cap** (`SteeringCommand`, avant Maps et le LLM) : « va à gauche »,
  « tourne à droite », « tout droit » (+ anglais) ; une question (« qu'y a-t-il à
  gauche ? ») et une phrase ambiguë ne déclenchent pas. Confirmation parlée
  (`SteeringText`), qui dit aussi quand le guidage est coupé dans les Réglages.
- **Chemin calculé vers le cap** (`Guidance`, couloir = moitié visée, passage = plus
  libre des deux autres secteurs, centre en premier) : « Va à gauche. »,
  « Personne à 2 mètres à 10 heures, passe au centre. » L'obstacle se dit au cadran
  comme en cap droit ; cap droit inchangé au caractère près.
- **Flèche sur la vidéo** (`SteeringLayer`, verte voie libre, ambre + passage si
  bouché) ; chemin recalculé à chaque frame, voix débouncée comme avant ; cap de
  session (retombe à tout droit), coupé en mode Traversée.
- **Preuves** : `SteeringTest` (12), `GuidanceSteeringTest` (13),
  `GuidanceAnnouncerSteeringTest` (2), `LiveProbeSteeringTest` (5) ;
  `:core:testDebugUnitTest` 1125/1125, `:app:testDebugUnitTest` 556/556,
  `check-kotlin-limits.py` OK. Non vérifié sur téléphone (ni à l'oreille, ni la
  flèche à l'écran).

## 2026-10-27 (12) — premier plan = carte active : réveil à 3 essais, stop franc si backend arrêté

- **Activation fiable à l'ouverture** : `warmGpu()` fait jusqu'à 3 `GET /health`
  silencieux espacés de 2 s (`AppDiagnostics.kt`, `WARM_ATTEMPTS`,
  `WARM_RETRY_DELAY_MS`) — une ouverture dans un trou de réseau ne laisse plus
  la carte éteinte. Arrêt au premier succès, silence UI et sans `Log` inchangés.
- **Arrêt immédiat si backend stoppé** : sur `ApiError.BackendStopped` (404 Modal
  « invalid function call », app plus déployée), aucune nouvelle tentative —
  réessayer est inutile, seul un redéploiement (`deploy-live-fast.sh`) rallume.
- **Pas de rallumage derrière l'extinction** : `releaseGpu` périme la génération du
  réveil (`warmGeneration`) — passer en arrière-plan en pleine série d'essais
  arrête les essais suivants. Un nouveau réveil périme le précédent (pas de séries
  empilées après deux `onResume` rapprochés).
- **Rappel d'architecture** : le téléphone ne peut pas `modal deploy` ; l'état stable
  est « app déployée + conteneur à zéro » (0 $ au repos), le premier plan réveille,
  l'arrière-plan éteint (`POST /release` + `scaledown=300` en garde). Un `app stop`
  manuel casse le réveil jusqu'au prochain déploiement.
- **Preuves** : `AppDiagnosticsTest` +3 (succès au 2e essai, `BackendStopped` sans
  retentative, nouveau réveil annule le précédent), test d'échec passé à 3 essais ;
  `FakeSam.onHealthSeq` scripté. Sur téléphone : fond → `released:true` (logcat),
  aucun appelant SAM hors couche UI (suivi = positions seules).

## 2026-10-27 (11) — positions et consignes au cadran horaire (9 h … midi … 3 h)

- **Vocabulaire unique au cadran** (`PositionText.clockHour`/`clockLabel`, sept bandes
  égales) : « Personne à 9 heures, à 1,4 mètre. », « Personne à 2 mètres à midi, va
  à gauche. » (en : « at 9 o'clock » … « at 12 o'clock »). « à midi » pour 12 h,
  « à 1 heure » au singulier — la convention des déplacements sans voir.
- **Remplace les secteurs** « à gauche / au centre / à droite » et le repère vertical
  partout où l'app met des positions en mots : annonces (Direct, iSee, photo),
  journal caméra (`TimelineText`, mêmes mots dans les deux langues), consignes du
  couloir (heure de l'obstacle dans la phrase et dans la clé `b|label|côté|mètre|heure`).
- `horizontal()`, `vertical()`, `positionLabel()` supprimés (remplacés, sans autre
  usage) ; `SIDE_EDGE` gardé pour le seul couloir du guidage. Aide des Réglages et
  fiches `VOIX-POSITIONS`, `VOIX-ANGLAIS`, `GUIDAGE-TOUT-DROIT`, `ELEVENLABS-TTS`,
  `DISTANCES`, `MASQUES-DEBUG` alignées.
- **Preuves** : `PositionTextTest` (cadran, libellés), `PositionTextEnglishTes
[... tronqué ...]
