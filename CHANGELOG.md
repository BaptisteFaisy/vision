# CHANGELOG — vision (iSee)

Le journal part du 2026-10-26 après 19 h : le commit de départ, puis une entrée par fait
livré et ses preuves (tâche, fichiers, chiffres) dans l'arbre.

## 2026-10-27 (9) — retour de mission : la base médias se recrée au lieu de crasher

- **Crash au lancement sur le téléphone** (`IllegalStateException: A migration from
  2 to 1 was required but not found`, vu sur `2c6852ac`) : l'appareil portait une
  base `ihear-media.db` v2 (build mission) et master est en v1 sans stratégie de
  migration. `MediaDatabase.build()` déclare désormais
  `fallbackToDestructiveMigrationOnDowngrade()` : en downgrade la base est recréée
  vide, l'app démarre. Perte acceptée : le ViewModel purge cette table à chaque
  démarrage (`IhearViewModel.kt:141`) ; les montées de version sans migration
  restent des crashs volontaires.
- **Non-régression instrumentée** (`MediaDowngradeTest`, exige l'appareil, la CI ne
  l'exécute pas) : base v1 tamponnée en v2 + une ligne, ouverture via `build()`,
  table vide sans crash. Ne touche jamais la vraie base (fichier de test effacé).
- **Preuves** : suites JVM `:core` + `:app` vertes, `assembleDebug` vert,
  `check-kotlin-limits.py` vert, test instrumenté vert sur `2c6852ac`, lancement
  `master` sans crash vérifié sur l'appareil.

## 2026-10-27 (8) — la GPU se réveille à l'ouverture, s'éteint hors plein écran

- **Ouverture → réveil** : `MainActivity.onResume` appelle `AppViewModel.warmGpu()` →
  `AppDiagnostics.warmGpu()` = un `GET /health` **silencieux** (best-effort, aucun
  verdict, aucune écriture UI) sur l'endpoint actif (région EU/US). Carte endormie :
  réveil via le snapshot Modal ; carte chaude : requête quasi gratuite.
- **Hors plein écran → extinction** (`POST /release`, existant, étendu) : `onPause`
  (déjà là : home, changement d'app, écran éteint) + nouveau
  `onMultiWindowModeChanged` (split-screen) + `onDestroy` (fermeture). Au retour en
  plein écran, `onResume` (ou la première frame) réveille.
- **Limite connue** : le réveil ne **déploie** rien — si l'app Modal n'est pas
  déployée, `/health` répond `BackendStopped` et il faut un `sam3-gpu on` manuel,
  une fois (`docs/MODE-US.md` § « Déploiement manuel, réveil auto »).
- **Preuves** : `AppDiagnosticsTest` +3 (`warmGpu` : appel unique, silence UI,
  sans URL = aucun appel, échec = silence) ; `:core` 955 + `:app` 516, 0 échec ;
  `assembleDebug` et `check-kotlin-limits.py` verts.

## 2026-10-27 (7) — la question porte sur ce qu'on filme, et l'échec se voit

- **Cause trouvée (10 agents, rapports `isee-diag-2026-10-27/`)** : le « gel » d'une photo du
  double tap n'avait **aucune fin de vie**. Il n'était levé que par `IhearAnswers.releaseFreeze()`
  sur le chemin nominal — jamais à l'évaporation, jamais au démarrage d'une vidéo, jamais sur une
  sortie précoce (clé LLM absente, texte vide, question déjà en vol). Conséquence : dès qu'une
  photo avait été prise, `captureForHold` devenait un no-op **à vie**, et toute question partait
  avec cette photo morte, y compris pendant un enregistrement vidéo.
- **Fin de vie explicite** : `IhearPhotoContext.release()` (gel + fichier à zéro) et
  `IhearPhotoShot.releaseContext()`, appelés au démarrage de la vidéo, à l'évaporation de la photo
  (sauf tenue/transcription/réflexion en cours), sur chaque sortie précoce du ViewModel et dans le
  `finally` de `IhearAnswers.answer`. Un gel dont le fichier a été purgé est reconnu mort :
  `captureForHold` le relâche et reprend la frame filmée.
- **Image inutilisable** : `IhearPhotoShot.imageBase64()` devient `suspend` et rend `null` (jamais
  `""`), sur `Dispatchers.IO` (injectable : les tests restent sur le dispatcheur virtuel). Une
  image vide partait en `data:image/jpeg;base64,` → 400 du backend → **aucune réponse, aucun
  message**. `InferHubLlmClient` envoie désormais la question **en texte seul** quand il n'y a pas
  d'image (une seule fabrique `userMessage` pour les trois appels).
- **Réponse jamais muette** : `IhearAnswers` prend une seule question à la fois (`AtomicBoolean`
  posé avant toute suspension — deux tenues rapprochées donnaient 409 sur la seconde), enveloppe
  `ask` **et** `spoken` dans un `Result`, et relâche le contexte dans un `finally`.
- **L'échec s'affiche** : `IhearUiState.status` était écrit de partout et **lu par aucun
  composable**. Nouveau `IseeStatusLine.kt` (`statusLineOf` pure + `SceneBottomBar`) : la ligne de
  statut est posée au-dessus de la pill d'instructions, dans toutes les scènes, et affiche
  « Thinking… » pendant la réflexion.
- **On voit ce qu'on filme** : `iseeSceneLayers` ne dépend plus de la scène mais de
  `captureLive` — la carte caméra reste à l'écran pendant la tenue du micro. La réflexion passe
  devant l'enregistrement dans `iseeSceneOf` (la pill passe en THINKING sans cacher la carte), et
  la dissolution ne part plus tant qu'une capture est vivante.
- **Tests** : 1 451 JVM verts (`:core` + `:app`), 0 échec ; `check-kotlin-limits.py` OK. Nouveaux
  cas : gel → vidéo → frame filmée ; gel au fichier purgé ; évaporation pendant une tenue ; gel
  relâché sur succès/échec/clé absente ; deux `answer()` concurrents → un seul appel ; corps HTTP
  sans `image_url` quand l'image est vide ; ligne de statut et carte visible sous tenue.

## 2026-10-27 (6) — la caméra iSee guide tout droit, sans LLM

- **La page caméra iSee dit la consigne de marche** (« Continue tout droit. », « Personne
  à 2 mètres devant, va à gauche. ») au lieu de la position des objets : couloir central
  0,34–0,66, portée 4 m, côté de sortie le moins encombré, distance inconnue = prudence
  (`core/.../live/Guidance.kt`, pur, aucun import réseau — le guidage ne peut pas appeler
  le LLM). Le déclencheur (`GuidanceAnnouncer.kt`) garde stabilité 2 frames et non-répétition,
  mais un obstacle qui entre dans le couloir parle aussitôt, sans attendre les 4 s.
- **Branchement** : `LiveProbe.phraseOf` préfère la consigne à l'annonce quand le réglage
  est actif (`ProbeWiring.configureFrom`, `Settings.guideStraight`), la voix reste
  `ProximityVoice` → ElevenLabs. Le Direct et l'analyse photo gardent les positions.
  Réglage `ttsGuidanceStraight` (défaut `true`, persisté par les deux stores),
  bascule « Guider tout droit (caméra iSee) » dans `TtsSettings.kt`.
- **Preuves** : 24 tests JVM nouveaux (`GuidanceTest` 10, `GuidanceAnnouncerTest` 7,
  `SettingsGuidanceTest` 4, `LiveProbeGuidanceTest` 3) ; suites complètes `:core` 943 et
  `:app` 479, 0 échec le 27/10 ; `tools/check-kotlin-limits.py` vert (extraction
  `LiveProbe.openSession`, qui supprime aussi la duplication des deux constructions de
  session) ; APK debug construit. Contrat et recette : `docs/GUIDAGE-TOUT-DROIT.md`.
- **Non mesuré** : sur appareil (exige téléphone + GPU SAM + clé ElevenLabs).

## 2026-10-27 (5) — une seule app : le banc lunettes entre dans iSee

- **Le module `:glasses-spike` est supprimé et son banc d'essai lunettes vit désormais dans
  `:app`**, paquet `dev.sam3vision.app.glasses.bench`, ouvert depuis l'écran Lunettes
  (`Route.Bench`, `AppNav.kt:45`, `:95`) : 30 `.kt` / 3 488 lignes copiés depuis
  `glasses-spike/src/{main,debug,release,test,androidTestDebug}` vers les source sets
  correspondants de `:app`, paquet renommé, assets compris (`mock_feed.mp4`, 54 964 o).
  Raison : un seul paquet déclaré dans la console Meta Wearables (`dev.sam3vision.app`), donc
  une seule app installée et plus de confusion entre « l'app iSee » et « l'app du banc » — le
  banc reste un banc, il n'est pas une seconde voie de production. Décision, tableau de
  migration vérifié et conséquences : `docs/GLASSES-DECISION.md`.
- **CI** : plus de job `spike` — `.github/workflows/ci.yml` ne contient plus aucune occurrence
  de `glasses-spike` et garde **quatre** jobs de 5 min (`ci`, `apk`, `apk-release`, `lint`) ; le
  banc est compilé et testé avec `:app`. Le module coûtait **50 s** de job, mesurées le 26/10/2026 (relevé conservé dans
  `docs/glasses-spike-ci.md` §1).
- **Docs** : `docs/GLASSES-DECISION.md` réécrit (la décision du 26/10 « `:glasses-spike` reste
  un module séparé » est remplacée), `docs/glasses-dat-spike.md` et `docs/glasses-spike-ci.md`
  réécrits pour décrire le paquet `bench/` (noms de fichiers conservés pour les liens),
  `docs/META-GLASSES.md` §10 et §5 alignés, `README.md` et `NOTES.md` recomptés le 27/10.
  Les entrées **antérieures** de ce journal gardent `:glasses-spike` : c'était le nom du module
  aux dates où elles ont été écrites.
- **Non mesuré** : aucun `gradlew` lancé ici (l'intégration est faite par l'orchestrateur) ; le
  banc n'a pas été exécuté sur des lunettes, ; `ChaineCompleteTest` embarque son JPEG 64×64 en base64, faute d'asset dans `app/src/test/resources/`.

## 2026-10-27 (3) — le build du téléphone rattrape un cache KSP corrompu

- **Constat** : vers 11 h, le cron `vision-phone-latest` échouait **en boucle** sur
  `[ksp] Storage corrupted …/app/build/kspCaches/debug/symbolLookups/id-to-file.tab_i`, puis sur
  `Storage … file-to-id.tab is already registered`. Le téléphone est resté ~15 min sur un `master`
  périmé, **sans alerte** — la panne la plus invisible possible pour « le banc suit master ».
- **Correctif** : `scripts/install-phone-latest.sh` fait un **second essai** quand le log du build
  contient l'un de ces deux messages, via `./gradlew --no-daemon clean assembleDebug --offline` :
  `clean` est exécuté par le démon lui-même (son registre de caches reste cohérent) et
  `--no-daemon` évite de tuer celui des autres agents qui compilent en parallèle. Un seul essai
  supplémentaire, jamais de boucle ; le JDK est retrouvé comme dans `scripts/build.sh`.
- **Preuves** : `bash scripts/test_scripts.sh` → **80 assertions, 80 ok** (dont les gardes
  statiques : aucun `rm -rf` brut, `set -euo pipefail`, zéro chemin `/home/` en dur) ; `bash -n` OK ;
  `python3 tools/check-kotlin-limits.py` OK.
- **Second correctif, trouvé au test** : l'essai de secours appelait Gradle en direct, donc **sans**
  `ANDROID_HOME` — `scripts/build.sh` le pose pour lui-même, pas pour son appelant. En cron (aucune
  variable d'environnement) le secours serait mort sur « SDK location not found ». Le défaut
  `$HOME/Android/Sdk` est maintenant reposé dans l'appel de secours ; mesuré ensuite :
  `./gradlew --no-daemon clean assembleDebug --offline` → **BUILD SUCCESSFUL in 37 s**, APK 76,7 Mo.

## 2026-10-27 (4) — le micro répond écran verrouillé

- **La panne** : `MainActivity.onPause()` annule la tenue du doigt et Android 11+ coupe de toute
  façon le micro d'une app en arrière-plan ; l'app n'avait **aucun** service de premier plan.
  Écran verrouillé, « activer le micro » ne pouvait donc rien donner.
- **Le service** (`app/locked/LockedMicService.kt`) : premier plan type `microphone`, armé par
  l'activité **visible** (`onResume` — seul démarrage autorisé depuis Android 12), piloté ensuite
  par l'action de la notification (visibilité publique) ou la tuile des réglages rapides. Réveil
  partiel pendant la capture seulement ; plafond de 20 s, faute de relâchement possible.
- **La réponse** : `LockedMicAnswer` enchaîne PCM → transcription (Meta, repli Scribe) → question
  **sans image** → voix ElevenLabs. `LlmClient.askText` + `LlmPrompts.askVoiceSystem` disent au
  modèle qu'il n'a aucune image : il l'annonce au lieu de décrire une photo absente.
- **Les règles** : `core/speech/LockedMic.kt` (6 phases, plafond, textes affichés et prononcés),
  11 tests ; `LockedMicAnswerTest` 5 ; `LlmContractTest` +2 (aucune image dans le corps, échec
  explicite d'un client qui ne sait pas répondre sans photo).
- **Permissions** : `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`,
  `WAKE_LOCK` ; `POST_NOTIFICATIONS` entre dans la demande groupée du premier lancement.
- **Doc** : `docs/MICRO-VERROUILLE.md` (séquence légale, recette téléphone, limites assumées).

## 2026-10-27 (2) — le téléphone suit `master` tout seul

- **`scripts/install-phone-latest.sh`** : le téléphone de test ne peut plus rester sur une build
  périmée. Le script lit le `versionName` installé (`1.0.<commits>-<sha>`), le compare au `master`
  de GitHub, reconstruit dans un **miroir dédié** (`~/vision-phone/master`, `git worktree` — jamais
  l'arbre de travail partagé où les agents écrivent) et installe sous le verrou `~/bin/phone`.
  `adb install -r -d` : un `versionCode` plus ancien (build de mission) est accepté, les données de
  l'app restent. Il **ne prend jamais** le verrou d'un autre agent : occupé = sortie `2`.
- **Options** : `--check` (0 = à jour / 3 = en retard / 2 = occupé), `--reinstall`, `--wait S`,
  `--init` (crée le miroir), `--hold 30m` (fige le téléphone pour tester une branche de mission).
- **Cron du poste** : `*/10 * * * * ~/bin/vision-phone-latest --quiet` ; ce wrapper exécute la
  copie du **miroir**, donc une correction du script arrive par `master` sans toucher au poste.
- **Tests** : `scripts/test_scripts.sh` passe de 53 à **75** assertions (le nouveau script est dans
  `SCRIPTS` : syntaxe, `--help`, argument inconnu, `< 500` lignes, zéro `/home/` en dur) et gagne
  quatre cas dédiés (miroir absent, `--init` conseillé, `--hold` qui écrit la pause, pause
  respectée).
- **Preuves** : téléphone `2c6852ac` réinstallé `1.0.36-e2f6984` puis `1.0.39-8f95191` par le script
  lui-même, `state.env` à jour, `bash scripts/test_scripts.sh` 75/75.

## 2026-10-27 — carte caméra : le voile de pixels s'efface

- **La carte caméra (scène VISION) n'est plus un damier permanent.** La mosaïque restait opaque
  pendant tout l'enregistrement — 26 × 16 = 416 cellules d'environ 39 × 32 px — si bien que la
  carte n'était que de gros blocs et que l'aperçu CameraX comme les masques SAM, dessinés dans la
  même surface native, étaient cachés dessous. Le paramètre `fill` (0,42 au repos / 1 en
  enregistrement) ne pilotait aucune opacité : il ne servait que de drapeau « dessine ou pas ».
- **Le voile est désormais une entrée** : `PixelMosaic` prend une opacité (`alpha`) appliquée à
  chaque cellule ; `VisionCard` la pose à 1 puis l'efface en `PIXEL_VEIL_MS` = **1 100 ms**
  (`rememberPixelVeil`). Sur une photo du double tap, voile à 0 comme avant. La dissolution vers la
  bulle (`DissolveOverlay`, plan 26 × 16) est **inchangée**.
- **Preuves** : PR **#33** (`app/…/ihear/IseeVisionCard.kt`, `IseeScenePixels.kt`),
  `tools/check-kotlin-limits.py` OK, `:app:compileDebugKotlin` et `:app:testDebugUnitTest`
  (`--offline`) OK, checks `ci` + `ci-budget` + `couche0` verts, APK debug installé sur `2c6852ac`.
- **Non mesuré** : le rendu à l'œil pendant l'enregistrement — l'objectif du téléphone de test ne
  renvoyait que du noir au moment de la vérification (aperçu et photo noirs, masques absents).

## 2026-10-26 — `master` protégé, file de merge native GitHub

- **Ruleset GitHub sur `master`** : PR obligatoire, checks requis `ci` + `ci-budget` + `couche0`
  (base à jour), historique linéaire, force-push et suppression interdits.
- **Merge queue native** : l'auto-merge d'une PR l'embarque dans la file GitHub ; le groupe de merge
  (`gh-readonly-queue/master/pr-<n>-<sha>`) rejoue les checks requis, `couche0` compris.
- **Plus de Mergify** : `.mergify.yml` supprimé, le step « brouillon de file » retiré de `couche0`,
  les docs (`AGENTS.md`, `docs/CI-QUEUE.md`, `docs/DOC-ETAT.md`, `docs/glasses-spike-ci.md`) décrivent
  le flot GitHub natif. Aucun outil de file externe sur ce dépôt.

## 2026-10-26 (26) — un keyframe SAM complet n'est redemandé que si les masques ont décroché

- **Le scheduler manquant de la lane L13** : `core/.../keyframe/` — `KeyframeTypes` (signal
  mesuré, décision, 6 `Trigger`, 5 `HoldReason`, `isUrgent`), `KeyframeConfig` (plancher 200 ms,
  plafond 500 ms étirable à 1 000 ms, erreur 0,12 sur 2 frames, EMA 0,40, scène 0,35, 3 req/s
  rafale 2, repli 0,5→8 s, timeout 12 s, validation au montage), `RequestBudget` (seau à jetons
  + **jeton d'urgence** à découvert, remboursé par la reconstitution), `KeyframeScheduler` (la
  machine à états, `FrameGate` de `:core/live` réutilisé pour **un seul** keyframe en vol,
  `onKeyframeResult` idempotent) et `KeyframeStats` (compteurs + p95 via `LiveStats`, fenêtre
  distincte de celle de la session).
- **37 tests** : 21 sur le scheduler (démarrage, antiblocage 2 frames, coupure de scène
  prioritaire, masque perdu, plafond, veille statique off par défaut, budget, jeton d'urgence
  unique, repli, timeout 12 s, réponses fantômes, `reset`, estimateur `NaN`), 7 sur le seau,
  9 sur le banc `8 scénarios × 4 politiques` — **0 violation partout** (budget d'une seconde, un
  seul keyframe en vol, plafond d'âge dû). Les politiques « cadence fixe » sont le même
  scheduler, aveuglé (seuils inatteignables, signal neutre) : aucune politique dupliquée.
- **Chiffres calculés, pas mesurés** : horloge virtuelle 33 ms, A/R 150 ms / 1 200 ms, modèle
  d'erreur synthétique ; tableau dans `core/build/keyframe-sim-u12.txt` (répertoire de build,
  non versionné). Aucun téléphone, aucun GPU, aucun appel réseau.
- **Contrat** : `docs/KEYFRAME-SCHEDULER.md` (entrées u10/u11, décision, garde-fous, câblage u13,
  ce qui reste à recaler avec l'erreur réelle d'u11).
- `:app` non touché : le scheduler est une brique de décision, u13 l'intégrera.

## 2026-10-26 (25) — lunettes : le mode réel dit ce qui manque

- **`glasses-spike`, démarrage sur de vraies lunettes** : le journal du mode « Lunettes » énumère les
  gestes à faire (Developer Mode, enregistrement, paire appairée, caméra *Always allow*) et décrit
  chaque paire que le SDK voit (type, lien, batterie, portée, charnière, compatibilité). Décision
  pure `realStartAdvice` (`GlassesSessionPolicy.kt`), lecture du SDK `GlassesRealCheck.kt`, bouton
  **Diag** pour rejouer le diagnostic sans ouvrir de session (`MainActivity.kt`). Détail :
  `docs/GLASSES-ETAT.md`, dernière section.
- **Une paire simulée ne peut plus passer pour une vraie** : `MockGlassesHarness.dropSimulatedPairs`
  retire les appareils simulés laissés par un run précédent (le SDK s'en souvient après un process
  tué, et `AutoDeviceSelector` les aurait pris) ; le mode réel l'appelle avant d'ouvrir la session.
- **`mockdevice` hors de l'APK public** : `debugImplementation` + `MockControl` (`src/debug` /
  `src/release`). Mesuré : `MockDeviceKit` → **29** occurrences dans l'APK debug, **0** dans le release.
- **La sortie audio se lit à l'écran** : `GlassesVoice.routeToGlasses()` rend la ligne du journal
  (« voix : Bluetooth « <nom de la sortie> » (type …) » ou « voix : haut-parleur — aucune sortie Bluetooth connectée »).
- **Vérifs** : `:glasses-spike:testDebugUnitTest` **62** tests / **0** échec (6 neufs), `assembleDebug`,
  `assembleRelease`, `assembleDebugAndroidTest`, `tools/check-kotlin-limits.py` OK.
- **Non mesuré** : tout le matériel (appairage, firmware, latence et débit Bluetooth, optique, routage
  audio réellement entendu) — aucune paire de Ray-Ban Meta n'est branchée sur le téléphone de test.

## 2026-10-26 (24) — banc : les six cas instrumentés de la purge

- **`CaptureCleanupTest` : 3 → 6 `@Test`.** Les trois cas manquants du contrat de la lane L14
  sont écrits, sur de vrais fichiers du stockage privé (`CapturePaths.privateRoots`) :
  capture de l'app supprimée · fichier déposé hors périmètre (`cacheDir`) intact · capture
  en cours d'écriture (mtime < 60 s) reportée. Les trois déjà présents (trace MediaStore
  dé-synchronisée, périmètre privé seulement, job unique sans réseau) sont inchangés.
- Ce que les documents annonçaient déjà est donc vrai : `docs/NETTOYAGE.md` et
  `docs/TESTING.md` comptaient **6 méthodes `@Test`** instrumentées avant le code.
  **Exécution non mesurée ici** : ces tests demandent un appareil
  (`./gradlew :app:connectedDebugAndroidTest`), aucun émulateur n'a été construit.

## 2026-10-26 (23) — docs : ce qui est vrai aujourd'hui

- **README corrigé** contre cet arbre. Trois affirmations fausses retirées : le dossier
  `backend/` (il n'existe pas ici — le backend SAM 3.1 est dans un dépôt séparé), `:core`
  présenté comme « Kotlin/JVM pur » (c'est une **bibliothèque Android** : 4 de ses 71 fichiers
  touchent `android.*`), et `minSdk 26` appliqué à toute l'app (**29** pour `:app` et
  `:glasses-spike`, 26 seulement pour `:core`). La CI n'est plus décrite comme « sans aucun
  secret » : le job `ci` lit des secrets **facultatifs** (`LLM_API_KEY`, …) pour embarquer des
  clés dans l'APK qu'il publie ; sans eux, l'APK sort sans clé.
- **`docs/ARCHITECTURE.md`** (197 lignes) : les trois modules, le flux capture → segmentation →
  LLM → voix, le **contrat HTTP réel** lu dans le code (`/health`, `/segment`, `/live`,
  `/live/ws`, `/release`, `/video`, `/chat/completions`, ElevenLabs, Meta ASR — **pas de
  `/ask`**, qui n'existe nulle part ici), les **chemins** des clés (aucune valeur), et la carte
  des fichiers d'entrée.
- **`docs/PERF.md`** : trois catégories séparées — mesuré dans cet arbre (282 fichiers `.kt`,
  29 803 lignes, 627 `@Test` JVM + 10 spike), constantes lues dans le code (délais, cadences,
  plafonds), relevés sur appareil **datés et sourcés**. Les latences du backend restent hors
  dépôt ; les chiffres de vision serveur ne sont pas repris faute de source ici.
- **`docs/DOC-ETAT.md`** : une ligne par document — à jour, périmée ou inexacte. Corrigés dans
  la même passe : `docs/TESTING.md` (comptages de tests **359/109 → 468/159**, JDK non épinglé,
  `/health` n'est plus une sonde, budget iSee **150 ms et un créneau**), `docs/MASQUES-DEBUG.md`
  (même cadence, frame **320 px** et non 448, ligne `gpu :` plus alimentée), `docs/CI-QUEUE.md`
  (cinq jobs, runners `ubuntu-latest`, budget 5 min par job),
  `docs/DISTANCES.md` (« les huit concepts par défaut » → **deux**), `NOTES.md` (comptages,
  `DistanceMeter` n'est pas un fichier).
- **Constat, pas corrigé (hors périmètre docs)** : `LivePrompts.kt` annonce « huit » concepts en
  commentaire alors que `Default` en contient **deux** et que `MAX = 2` ; `LiveProbeState.gpu`
  n'est plus renseigné depuis la suppression des sondes `/health` ; `SECURITY.md` et `AGENTS.md`
  portent encore des phrases de l'ancien dépôt (détaillé dans `docs/DOC-ETAT.md`).

## 2026-10-26 (22) — la carte caméra se dissout et son monde reste sur la bulle

- **Demande user** : « l'animation des pixels qui remplissent la bulle tu dois énormément la
  travailler » — feindre la vidéo de design : la carte caméra se dissout pendant que ses
  pixels plongent dans la grande bulle qui se reconstitue en monde.
- **Plan pur testé** `core/.../Dissolve.kt` + `DissolveTest.kt` : morph de la bulle (grandit
  de son anneau jusqu'au centre), damier de désagrégation de la carte, cubique bézier du vol
  de chaque pixel, spots du « monde » posés sur le disque (la carte se remappe sur la bulle).
- **App** : `IseeDissolve.kt` (carte gelée, overlay damier + gerbe, `WorldMemory` persistant
  lié à la réponse), `IseeVisionCard.kt` (carte caméra extraite de la fenêtre), satellites
  et ticks réavailés, découpages pour la garde de tailles.

## 2026-10-26 (21) — plus aucune sonde `/health` : la première frame réveille la carte

- **Demande user** : « ok supprime les /health » (après le constat : `/health` toutes les ~20 s
  tenait une A100 allumée en continu, 2,415 $/h, sans une seule analyse).
- **Supprimé côté client** (il ne reste que le bouton « Tester SAM » des Réglages) :
  - `AppViewModel.prewarmGpu()` — le réveil **préventif** à l'ouverture de l'app et à l'affichage
    de l'écran caméra (`MainActivity.kt:63,127`, `AppNav.kt:116`) : c'était la source principale,
    une sonde toutes les 120 s tant que l'app était au premier plan ;
  - `LiveViewModel.wakeGpu()` — une sonde au démarrage du direct ;
  - `ihear/LiveProbe.wake()` — une sonde à la saisie de l'endpoint et au début d'enregistrement ;
  - `core/live/PrewarmGate.kt` (`shouldPrewarm`) et son test, devenus inutiles.
- **Le réveil passe par la première frame** : Modal met la requête en file pendant le démarrage
  (mesuré 8-35 s), donc aucune sonde n'est nécessaire. `LiveViewModel` n'attend plus un « warm »
  pour envoyer les frames ; l'état `starting` (« Réveil du GPU A100 40 Go sur Modal… ») se lève à
  la **première frame publiée**, et une frame en échec reste une ligne « réveil » pendant 60 s
  (`WAKE_TIMEOUT_MS`) avant de devenir une erreur. `LiveProbe` garde son état `warming` (même
  message dans la pastille) mais il est posé localement, sans requête.
- **Conséquence d'affichage** : le nom du GPU venait de `/health` → `formatLiveStatus` n'affiche
  plus « GPU ? » quand il est inconnu (`LiveState.kt`, test mis à jour) ; `shortGpu()` retiré.
- `releaseGpu()` (POST `/release` à la fermeture) est **conservé** : c'est l'inverse d'une sonde,
  il éteint la carte.

## 2026-10-26 (20) — distances sur la vidéo : la pastille devient du verre, reliée à sa boîte

- **Demande user** : « je trouve que c'est assez moche la manière dont les mètres distances sont
  affichées sur la vidéo sur la caméra, et je me demandais s'il serait possible de les afficher
  autrement ? », puis « teste ce que tu proposes ».
- **Plus de rectangle plein sur l'image** (`app/DistanceOverlay.kt`, `app/DistanceLayout.kt`) : la
  pastille devient du **verre sombre** — le texte blanc reste lisible sur n'importe quelle image — avec
  une **puce** et un **liseré** de la couleur du masque. Elle se pose au-dessus du **coin haut-gauche**
  de sa boîte, et un **fil de rappel** de la même couleur la relie à un point posé sur ce coin. La
  couleur ne remplit plus une vignette : elle **désigne** l'objet. C'est le fil qui lève l'ambiguïté que
  les pastilles centrées laissaient quand deux objets se touchent — et qui garde attachée à sa boîte une
  pastille que l'empilement a poussée vers le bas.
- **Placement réécrit, pur et testé** : `placePills` remplace le couple « centré + `spreadLabels` ».
  Alignement sur le coin de la boîte, bornage dans le cadre, jamais sous le bandeau du haut (inset
  230 dp), empilement vers le bas par pas de `hauteur + PILL_STACK_GAP` quand deux pastilles se
  recouvrent ; `PillPlacement.leadY()` choisit le bord que le fil touche (le bas quand la pastille est
  au-dessus, le haut quand elle est dessous). Le texte est mesuré (`TextMeasurer`) avant le placement :
  la pastille a exactement la largeur de son contenu, et tout part dans un seul `Canvas` — fils dessous,
  pastilles dessus. `pillTranslationX`, `pillTranslationY` et `spreadLabels` disparaissent avec leurs
  tests.
- **Ce qui ne change pas** : la mesure elle-même (sténopé, voir `docs/DISTANCES.md`), les couleurs de
  masque, la ligne d'état, les pastilles de concepts. Seule la mise en forme de l'étiquette change.
- **Vérifié** (poste Windows, JDK 17, `--offline`) : `:app:testDebugUnitTest` (**0 échec**, dont
  `DistanceLayoutTest` — 15 tests) et `assembleDebug` OK, `check-kotlin-limits.py` OK. **Non vérifié** :
  le rendu sur un téléphone (aucun appareil sollicité) ; l'aspect a été contrôlé sur une maquette canvas
  qui rejoue les mêmes formules (`tmp/distances-etiquettes.html`, capture `shots/distances-etiquettes.png`,
  tous deux hors dépôt).
- **Précision de la mesure** : toujours **non mesurée** (voir `docs/DISTANCES.md`) — ce changement ne
  touche que l'affichage.
## 2026-10-26 (22) — les clés sont dans l'APK, personne n'a rien à saisir

- **Demande user** : « mets de base les clés dans l'app, qu'une autre personne n'ait pas
  besoin de rentrer ses clés si elle télécharge l'app ».
- **Ce qui manquait vraiment** : le build local embarquait déjà le LLM et ElevenLabs
  (`local.properties` → `BuildConfig` → `seededFromBuild`), mais l'APK publié par la CI
  sortait **sans aucune clé** : le runner fait un checkout neuf, sans `local.properties`.
  Deux personnes sur la même version avaient donc l'une une app prête, l'autre une app qui
  réclamait des clés.
- **Clé de dictée Meta ajoutée au build** : `asr.api.key` → `BuildConfig.ASR_API_KEY`,
  remplie par `seedAsr` quand le champ est vide. Sans elle, la dictée retombait sur
  ElevenLabs Scribe.
- **CI** : nouvelle étape « Clés du build » qui écrit `local.properties` depuis les
  secrets du dépôt (`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `TTS_API_KEY`,
  `TTS_VOICE_ID`, `ASR_API_KEY`) ; sans secret, elle ne fait rien et le comportement
  d'avant est conservé. Aucune valeur n'est affichée.
- **Testable** : `BuildDefaults` isole les valeurs du build, `seeded()` devient une
  fonction pure ; `SettingsSeedTest` (4) prouve qu'une installation neuve hérite de tout,
  qu'une clé saisie gagne, et qu'un build sans clé laisse les champs vides.
- **Réglages** : le bloc « build » annonce aussi la dictée et rappelle que les clés du
  build remplissent tout champ vide.
- Docs : `docs/CLES-DU-BUILD.md` (origine, priorité, secrets à créer, avertissement : une
  clé dans un APK est extractible, donc distribution restreinte).

## 2026-10-26 (21) — iSee : la réponse arrive, l'intro reste relisible

- **Réponse IA invisible et muette — cause trouvée** : `MediaCacheStore` insérait la ligne Room
  mais **rendait l'objet d'id 0** (`dao.insert(row)` sans récupérer la clé auto-générée).
  `setAnswerText` et `saveReplyAudio` faisaient donc un `UPDATE … WHERE id = 0` : zéro ligne
  touchée, texte jamais écrit, aucun MP3 attaché, donc rien à afficher ni à prononcer. Les trois
  insertions rendent maintenant `row.copy(id = dao.insert(row))`.
- **Plus de « … » qui reste** : `ask()` remet `thinking = false` dans un `finally`, garanti même
  si une étape inattendue échoue. Une seule question à la fois : une tenue pendant qu'une réponse
  est en cours est refusée avec un motif au lieu d'envoyer un second appel concurrent.
- **409 « limite atteinte »** : le proxy LLM a refusé la requête (quota ou requête concurrente —
  le corps brut ne dit pas laquelle). `httpError` mappe 409 sur `RateLimited` avec « limite
  atteinte côté IA (409) : réessaie dans un instant » au lieu du « erreur HTTP 409 : … » brut, et
  l'app n'envoie plus deux questions simultanées.
- **Fermeture complète = plus rien** : en plus de la purge d'`onDestroy`, le ViewModel purge au
  démarrage — un force-stop ou un balayage des apps récentes ne passe pas par `onDestroy` et
  laissait les messages sur le téléphone. À la réouverture, seule la bulle d'intro existe.
- **L'intro est sélectionnable avec les autres bulles** : la sélection est un index sur **toutes**
  les bulles affichées (`0` = intro), plus sur les seuls échanges. On peut remonter jusqu'à elle
  pour la relire (`swipeStep`, `selectedEntryIndex`, `IhearPlayback`).
- **Animation d'évaporation supprimée** : `PixelFade`/`PixelDissolve` disparaissent ; le bandeau
  « iSee » est un aplat noir opaque sous lequel le fil passe et disparaît d'un coup.
- **Icônes de message** : hauteur d'une bulle d'une ligne (20 sp + 24 dp, fontScale compris) au
  lieu de 28 dp fixes, avec glyphes ❚❚/▶ en 14 sp ; teinte tirée de l'identifiant parmi huit
  bulles de savon métal/verre (`BubblePalette.kt`), stable d'un rendu à l'autre.
- **Bulles plus étroites** : 230 dp au lieu de 250 dp ; les icônes ajoutent leur largeur.
- **Bulle sélectionnée toujours entière** : position calculée par `offset − viewportStartOffset`
  et complément borné (`revealShift`), et le fil ne se raccroche plus à la fin quand l'utilisateur
  relit une bulle ancienne.
- **Tests** : `MediaCacheStoreTest` (3, l'id rendu et la réponse rattachée sur un faux DAO),
  `SwipeStepTest` (9, intro comprise), `RevealShiftTest` (4), `BubblePaletteTest` (3),
  `HttpErrorTest` (4, dont 409) ; `check-kotlin-limits` OK.
