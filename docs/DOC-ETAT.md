# État de la documentation — vérifié le 26/10, repris et complété le 27/10

Une ligne par document de ce dépôt. **Verdict** : `à jour` (ce qu'il dit se lit dans le code
de cet arbre), `périmée` (décrivait juste, le code a bougé — corrigé), `inexacte` (contient
une affirmation fausse pour cet arbre — corrigée ou encadrée).

| Document | Verdict | Ce qui a été vérifié / corrigé ici |
|---|---|---|
| `README.md` | **à jour** (réécrit 26/10, suivi depuis) | Pas de `backend/`, `:core` = bibliothèque Android, minSdk **29** pour `:app`, CI = quatre workflows sur runners **auto-hébergés**, et l'accueil **est** la scène iSee branchée (carte caméra en damier) depuis la fusion `opt/isee-branche` |
| `docs/ARCHITECTURE.md` | **nouveau** (26/10) | Modules, flux, contrat HTTP, chemins des clés, carte des fichiers d'entrée |
| `docs/PERF.md` | **nouveau** (26/10) | Sépare trois choses que la doc confondait : mesuré ici, lu dans le code, relevé sur appareil daté |
| `docs/DOC-ETAT.md` | **nouveau** (26/10) | Ce tableau |
| `docs/TESTING.md` | **périmée → corrigée** (27/10, trois passes) | Comptages refaits le 27/10 soir : **947** annotations `@Test` dans `:core`, **515** dans `:app` (dont **92** pour le banc `glasses/bench`, **10** fichiers avec `LinkGateTest`), soit **1 462** JVM — confortés par un run Gradle complet (194 classes, 0 échec) ; le test instrumenté du banc est dans `app/src/androidTestDebug/` et passe par `:app:connectedDebugAndroidTest` ; le `JAVA_HOME` cité est un chemin de poste, pas une vérité du dépôt |
| `docs/MASQUES-DEBUG.md` | **inexacte → corrigée** | Cadence iSee : « 2 requêtes en vol, budget 200 ms » alors que `Cadence.Fast` est à **1 créneau / 150 ms** ; « cadre 448 px » alors que `ISEE_FRAME_EDGE = 320` |
| `docs/CI-QUEUE.md` | **périmée → recorrigée** (28/10, découpe v9) | **Un** job dans `ci.yml` (`ci`) + natif (`apk`, `apk-release`, `lint`) dans `ci-native.yml` hors PR, runners **auto-hébergés** `[self-hosted, linux, x64, vision]` ; `couche0` (scope + forbidden, juge pris sur `master`), `stale-base-guard` informatif, budget 5 min par job, `master` protégé (PR obligatoire, 3 checks requis, file de merge native) |
| `docs/NETTOYAGE.md` | **à jour** | 12 fichiers, 599 lignes, purge horaire (`PERIOD_HOURS = 1`), Room + WorkManager — vérifié à l'identique le 26/10 |
| `docs/DISTANCES.md` | **à jour**, une correction | Formule et constantes justes (lissage 0,35 / 0,85 ; `KnownSizes` ; `LensSpec` 67° borné 15–130°). « Les **huit** concepts par défaut du direct sont calibrés » : les concepts par défaut sont **2** (`LivePrompts.Default`) |
| `docs/ELEVENLABS-TTS.md` | **à jour** | En-tête `xi-api-key`, base `https://api.elevenlabs.io/v1`, modèle `eleven_v4_turbo`, voix premade vs bibliothèque (402) |
| `docs/META-ASR.md` | **à jour** | Chemin `{base}/asr/transcribe`, `Authorization: Bearer`, modèle `muse-voice-transcribe-1.0` |
| `docs/MODE-TAP.md` | **à jour** | Route `Route.Tap`, `POST /video` en `multipart/form-data`, `TapConfig.ON_DEVICE_INFERENCE = false` |
| `docs/MODE-US.md` | **à jour**, chiffres à lire comme historique | Le choix de région n'allume rien et n'élit que l'URL (`SamRegion.kt`, `SamEndpoint.kt`). Les durées eu/us (472–503 ms / 1107–1115 ms) sont des relevés du 26/10 **hors de ce dépôt**, antérieurs au WebSocket |
| `docs/META-GLASSES.md` | **à jour** | Placeholders `mwdat_*` injectés par Gradle (pas des « 0 » en dur), minSdk 29, SDK en developer preview |
| `docs/glasses-dat-spike.md` | **à jour** (relevé d'API, daté) | Reste vrai comme relevé d'API, mais **le module n'existe plus** : `settings.gradle.kts` n'inclut que `:core` et `:app`, et le dossier de l'ancien module a disparu du disque. Le doc dit lui-même qu'il ne décrit pas `:app` |
| la page CI du banc sous `docs/` (`*-ci.md`, nom d'origine conservé pour les liens) | **périmée → corrigée** (27/10) | Elle ne décrit plus un module : **plus de job `spike`** (la CI en compte **quatre**), le banc est compilé et testé par `:app` (`:app:testDebugUnitTest` au job `ci`, `:app:assembleDebug` au job `apk`), runners auto-hébergés `[self-hosted, linux, x64, vision]` + `gradle/actions/setup-gradle@v4`, et la durée de l'ancien job reste citée comme relevé daté. Corrigé : **92** annotations `@Test` pour `app/…/bench/` (était 84), commande Gradle en `ci.yml:149`, `BenchScreen.kt` (350 l.) |
| `docs/VOIX-ANGLAIS.md` | **à jour** | `SpeechLanguage.DEFAULT = En` ; l'interface reste en français |
| `docs/GUIDAGE-TOUT-DROIT.md` | **nouveau** (27/10) | Consigne de marche iSee : couloir 0,34–0,66, portée 4 m, danger prioritaire sur les 4 s, zéro appel LLM — ancres `Guidance.kt`, `GuidanceAnnouncer.kt`, `LiveProbe.phraseOf` |
| `docs/TEST-LONGPRESS-DICTEE.md` | **à jour** (recette datée) | Seuil d'appui long 300 ms et tolérance 24 px lus dans `LongPressTrigger.kt` ; la recette elle-même n'a pas été rejouée (le doc le dit) |
| `docs/L13-FUSION.md` | **périmée → corrigée** (27/10) | Contrat de fusion de la lane L13. Tableau d'état recalé : les six artefacts **existent** (A et B non branchés, D inerte) — ce qui manque, c'est le **câblage** A→B→C→D, pas le code. La couture D (`setMotion`) consomme encore `core.local.MotionSource` |
| `docs/CLES-DU-BUILD.md` | **à jour** (relu 27/10) | Clés `local.properties` → `BuildConfig` relues dans `app/build.gradle.kts`, `SettingsSeed.kt` présent, étape « Clés du build » en `ci.yml:108` |
| `docs/GLASSES-DECISION.md` | **périmée → corrigée** (27/10) | « Deux arbres côte à côte » passé au passé (module supprimé du disque), `frame-64.jpg` marqué résolu (JPEG embarqué en base64), ancres `AppNav`/manifeste/`build.gradle.kts` recalées |
| `docs/GLASSES-ETAT.md` | **périmée → corrigée** (27/10) | Banc : 18 fichiers / 2 015 lignes, **10** fichiers / **92** `@Test` (nouveau `LinkGateTest`, `GlassesFramePolicyTest` à 11, ex-`FramePolicyTest` homonyme renommé), garde de taille OK (510 fichiers, 0 dépassement), ancres `AppNav` recalées |
| `docs/KEYFRAME-SCHEDULER.md` | **à jour** (relu 27/10) | 5 fichiers `keyframe/`, constantes (0,12 / 0,35 / 12 s / 3 req/s), `FrameGate` réutilisé, simulateur `KeyframeScene`/`KeyframeSim` — relus à l'identique |
| `docs/LATENCE-BUDGET.md` | **périmée → corrigée** (27/10) | `scripts/budget_latence.py --check-doc` OK (6 tableaux) et `--selftest` 14/14 ; deux ancres `LiveSession.kt:33` → `:34` |
| `docs/LIVE-SPEC.md` | **périmée → corrigée** (27/10) | Démarrage via `LiveMaskTransport.clientFor` (`LiveViewModel.kt:200`, `LiveProbe.kt:178`), `Json.kt` décalé (+40 lignes), cadence/dimensionnement recalés, preuve `git grep "POST /live"` refaite (aucun site d'appel) |
| `docs/LUNETTES-LANCEMENT.md` | **périmée → corrigée** (27/10) | Comptes (3 debug, 10 tests), ancres manifeste/build/`AppNav`/`Settings` recalées, `test-mock-sam.sh` rejoué (26 ok / 0 KO), lien `ref/` mort retiré (dossier absent du dépôt) |
| `docs/MASK-DECODE.md` | **à jour** (relu 27/10) | Codecs `rle`/`poly`, knobs `MASK_FORMAT`/`MASK_EDGE`, `MASK_MAX_PIXELS`, 6 suites de tests citées — relus à l'identique |
| `docs/MICRO-VERROUILLE.md` | **périmée → corrigée** (27/10) | `LlmContractTest` 13 → **15** ; tout le reste (service, `MAX_RECORD_MS`, receiver debug, recette) relu à l'identique |
| `docs/OPTIMISATIONS.md` | **inexacte → corrigée** (27/10) | Jobs CI 5 → **4** (contredisait le §11), note de datation ajoutée : les chiffres « Après » sont ceux de la fin de campagne (26/10), pas de cet arbre |
| `docs/T8-PREDICTION.md` | **périmée → corrigée** (27/10) | §4 réécrit : le module **est** branché derrière `Settings.boxPredictionEnabled` (`LiveViewModel.kt:94,235,368,373`), §1/§6 recalés |
| `docs/TRACK-PERF.md` | **à jour** (relu 27/10) | `TrackClient`/`TrackRunner`, constantes de cadence, `FrameGate` — relus à l'identique ; chiffres serveur marqués « relevé antérieur » |
| `docs/VOIX-POSITIONS.md` | **périmée → corrigée** (27/10) | `LiveProbe.phraseOf` → `:418-423`, `Settings.kt:28` → `:36`, `:148` → `:184` ; seuils, machine d'annonce et 5 suites de tests relus à l'identique |
| `docs/chat-voix.png` | ressource | Capture d'écran, pas une affirmation |
| `NOTES.md` | **périmée → corrigée** (27/10, deux passes) | Passe 1 : comptages de tests, « 53 entrées » dans `app/…/app/`, `DistanceMeter` = classe de `DistanceEstimator.kt`. Passe 2 : `TrackClient`/`TrackRunner` **sont** appelés (`LiveViewModel`, `TrackFrameBridge`), `docs/LIVE-SPEC.md` **existe**, KDoc `SamRegion` corrigé ; versions épinglées justes |
| `CHANGELOG.md` | **à jour** | Le journal des faits livrés depuis le 26/10 ; `docs/PERF.md` reste le seul endroit où chercher un chiffre tenu |

## Trois affirmations qui circulaient, et ce qu'il en est dans cet arbre

- **« `backend/` »** — **faux**. Aucun dossier de ce nom : `ls` à la racine donne `app`,
  `core`, `docs`, `gradle`, `scripts`, `tools` (le dossier de l'ancien module lunettes a
  disparu du disque ; `settings.gradle.kts` n'inclut que `:core` et `:app`). Le backend
  SAM 3.1 est dans un dépôt séparé.
- **« la carte caméra qui se dissout en damier dans la bulle »** — **vrai depuis la fusion
  `opt/isee-branche`**. La dissolution est écrite et testée (`core/…/iseedesign/Dissolve.kt`,
  `app/…/isee/IseeDissolve.kt`, `app/…/isee/IseeScenePane.kt`), et l'accueil est
  désormais `IseeHome` → `IseeScenePane` : `IseeChatPane` et `IseeCameraWindow` sont
  supprimés. Reste à confirmer visuellement sur appareil (jamais regardé sur écran).
- **« 8 objets par défaut »** — **vrai pour les objets affichés** (`ObjectLimits.DEFAULT_ON_SCREEN = 8`,
  borné 1–12), **faux pour les concepts suivis** : `LivePrompts.Default` en contient **2**
  (`person`, `door`) et `LivePrompts.MAX = 2`. Le commentaire d'en-tête de
  `LivePrompts.kt`, qui parlait encore de « huit » concepts, a été **corrigé le 27/10**.

## Hors périmètre de cette passe (à reprendre)

- `SECURITY.md` : corrigé le 27/10 (« aucune clé **versionnée** », lecture des secrets par
  l'étape « Clés du build » de `ci.yml`).
- `AGENTS.md` (racine) ne cite ni `docs/ARCHITECTURE.md` ni `docs/PERF.md`.
- Commentaires de code périmés : `LivePrompts.kt` et `LiveProbeText.kt` corrigés le 27/10 ;
  reste le cas `LiveState.kt` signalé sans localisation précise — non retrouvé.