# État de la documentation — vérifié le 26/10, repris le 27/10

Une ligne par document de ce dépôt. **Verdict** : `à jour` (ce qu'il dit se lit dans le code
de cet arbre), `périmée` (décrivait juste, le code a bougé — corrigé), `inexacte` (contient
une affirmation fausse pour cet arbre — corrigée ou encadrée).

| Document | Verdict | Ce qui a été vérifié / corrigé ici |
|---|---|---|
| `README.md` | **à jour** (réécrit 26/10) | Retiré `backend/` (n'existe pas ici), `:core` n'est pas « JVM pur » (bibliothèque Android), minSdk **29** pour `:app`, CI = quatre workflows sur `ubuntu-latest` avec des secrets **facultatifs**, et la « carte caméra qui se dissout en damier » n'est pas l'accueil livré (voir plus bas) |
| `docs/ARCHITECTURE.md` | **nouveau** (26/10) | Modules, flux, contrat HTTP, chemins des clés, carte des fichiers d'entrée |
| `docs/PERF.md` | **nouveau** (26/10) | Sépare trois choses que la doc confondait : mesuré ici, lu dans le code, relevé sur appareil daté |
| `docs/DOC-ETAT.md` | **nouveau** (26/10) | Ce tableau |
| `docs/TESTING.md` | **périmée → corrigée** (27/10) | Comptages refaits au 27/10 : **907** annotations `@Test` dans `:core`, **466** dans `:app` (dont **84** pour le banc `glasses/bench`), soit **1 373** JVM ; les commandes et chemins du module lunettes supprimé ont disparu, le test instrumenté du banc est dans `app/src/androidTestDebug/` et passe par `:app:connectedDebugAndroidTest` ; le `JAVA_HOME` cité est un chemin de poste, pas une vérité du dépôt |
| `docs/MASQUES-DEBUG.md` | **inexacte → corrigée** | Cadence iSee : « 2 requêtes en vol, budget 200 ms » alors que `Cadence.Fast` est à **1 créneau / 150 ms** ; « cadre 448 px » alors que `IHEAR_FRAME_EDGE = 320` |
| `docs/CI-QUEUE.md` | **périmée → corrigée** (27/10) | **Quatre** jobs dans `ci.yml` (`ci`, `apk`, `apk-release`, `lint`) sur runners **auto-hébergés** `[self-hosted, linux, x64, vision]` — la ligne du job `spike` et le « `ubuntu-latest` » ne valaient plus ; `couche0` (scope + forbidden, juge pris sur `master`), `stale-base-guard` informatif, budget 5 min par job, `master` protégé (PR obligatoire, checks requis, file de merge native) |
| `docs/NETTOYAGE.md` | **à jour** | 12 fichiers, 599 lignes, purge horaire (`PERIOD_HOURS = 1`), Room + WorkManager — vérifié à l'identique le 26/10 |
| `docs/DISTANCES.md` | **à jour**, une correction | Formule et constantes justes (lissage 0,35 / 0,85 ; `KnownSizes` ; `LensSpec` 67° borné 15–130°). « Les **huit** concepts par défaut du direct sont calibrés » : les concepts par défaut sont **2** (`LivePrompts.Default`) |
| `docs/ELEVENLABS-TTS.md` | **à jour** | En-tête `xi-api-key`, base `https://api.elevenlabs.io/v1`, modèle `eleven_v4_turbo`, voix premade vs bibliothèque (402) |
| `docs/META-ASR.md` | **à jour** | Chemin `{base}/asr/transcribe`, `Authorization: Bearer`, modèle `muse-voice-transcribe-1.0` |
| `docs/MODE-TAP.md` | **à jour** | Route `Route.Tap`, `POST /video` en `multipart/form-data`, `TapConfig.ON_DEVICE_INFERENCE = false` |
| `docs/MODE-US.md` | **à jour**, chiffres à lire comme historique | Le choix de région n'allume rien et n'élit que l'URL (`SamRegion.kt`, `SamEndpoint.kt`). Les durées eu/us (472–503 ms / 1107–1115 ms) sont des relevés du 26/10 **hors de ce dépôt**, antérieurs au WebSocket |
| `docs/META-GLASSES.md` | **à jour** | Placeholders `mwdat_*` injectés par Gradle (pas des « 0 » en dur), minSdk 29, SDK en developer preview |
| `docs/glasses-dat-spike.md` | **à jour** (relevé d'API, daté) | Reste vrai comme relevé d'API, mais **le module n'existe plus** : `settings.gradle.kts` n'inclut que `:core` et `:app` ; le dossier de l'ancien module traîne encore à la racine (sources + `build.gradle.kts`) sans être construit. Le doc dit lui-même qu'il ne décrit pas `:app` |
| la page CI du banc sous `docs/` (`*-ci.md`, nom d'origine conservé pour les liens) | **réécrite 27/10 → à jour, un chiffre à reprendre** | Elle ne décrit plus un module : **plus de job `spike`** (la CI en compte **quatre**), le banc est compilé et testé par `:app` (`:app:testDebugUnitTest` au job `ci`, `:app:assembleDebug` au job `apk`), runners auto-hébergés `[self-hosted, linux, x64, vision]` + `gradle/actions/setup-gradle@v4`, et la durée de l'ancien job reste citée comme relevé daté. **Reste faux** : elle annonce **86** annotations `@Test` pour `app/…/bench/`, le comptage du 27/10 en donne **84** (`grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench --include='*.kt' | wc -l`) |
| `docs/VOIX-ANGLAIS.md` | **à jour** | `SpeechLanguage.DEFAULT = En` ; l'interface reste en français |
| `docs/TEST-LONGPRESS-DICTEE.md` | **à jour** (recette datée) | Seuil d'appui long 300 ms et tolérance 24 px lus dans `LongPressTrigger.kt` ; la recette elle-même n'a pas été rejouée (le doc le dit) |
| `docs/L13-FUSION.md` | **nouveau** (26/10) | Contrat de fusion de la lane L13 (7 points de `u13`) ramené aux ancres de **cet** arbre, protocole de recalage d'`errorThreshold`, schéma du tableau de simulation. Dit lui-même ce qui manque : A→B→C→D et le décodage L11 sont **absents** — c'est un plan, pas une mesure |
| `docs/chat-voix.png` | ressource | Capture d'écran, pas une affirmation |
| `NOTES.md` | **périmée → corrigée** | Comptages de tests et « 48 entrées dans `app/…/app/` » (mesuré : **53 entrées**, 110 fichiers `.kt` dans `app/src/main`), `DistanceMeter` n'est pas un fichier mais une classe de `DistanceEstimator.kt` ; les versions épinglées sont justes |
| `CHANGELOG.md` | **à jour** | Le journal des faits livrés depuis le 26/10 ; `docs/PERF.md` reste le seul endroit où chercher un chiffre tenu |

## Trois affirmations qui circulaient, et ce qu'il en est dans cet arbre

- **« `backend/` »** — **faux**. Aucun dossier de ce nom : `ls` à la racine donne `app`,
  `core`, `docs`, `gradle`, `scripts`, `tools` — plus le dossier de l'ancien module lunettes,
  resté sur disque mais **retiré de `settings.gradle.kts`** (seuls `:core` et `:app` sont
  inclus, donc il n'est ni compilé ni testé). Le backend SAM 3.1 est dans un dépôt séparé.
- **« la carte caméra qui se dissout en damier dans la bulle »** — **faux pour l'accueil
  livré**. La dissolution est écrite et testée (`core/…/iseedesign/Dissolve.kt`,
  `app/…/ihear/IseeDissolve.kt`, `app/…/ihear/IseeScenePane.kt`), mais sur `master`
  `IseeScenePane` **n'a aucun appelant** : l'accueil est `IhearHome` → `IhearChatPane`, où la
  caméra est une fenêtre posée dans une bulle du fil et **s'évapore en fondu** (alpha animé,
  `app/…/ihear/IhearCameraWindow.kt`). C'est une pièce montée, pas encore branchée : la PR
  `opt/isee-branche` la branche (elle supprime `IhearChatPane` et fait de `IseeVisionCard` la
  carte caméra unique) — après fusion, relire `README.md` et `docs/ARCHITECTURE.md`.
- **« 8 objets par défaut »** — **vrai pour les objets affichés** (`ObjectLimits.DEFAULT_ON_SCREEN = 8`,
  borné 1–12), **faux pour les concepts suivis** : `LivePrompts.Default` en contient **2**
  (`person`, `door`) et `LivePrompts.MAX = 2`. Le commentaire d'en-tête de
  `LivePrompts.kt` parle encore de « huit » concepts : **commentaire de code périmé**, hors du
  périmètre de cette passe (il n'ajoute rien aux **1 373** tests JVM recensés au 27/10, mais il
  égare le lecteur).

## Hors périmètre de cette passe (à reprendre)

- `SECURITY.md` écrit « La CI n'en utilise aucune [clé] » : faux depuis l'étape facultative
  « Clés du build (secrets → `local.properties`) » de `.github/workflows/ci.yml`. Le dépôt
  reste sans clé **versionnée** ; la phrase doit être nuancée.
- `AGENTS.md` (racine) ne cite ni `docs/ARCHITECTURE.md` ni `docs/PERF.md`.
- Les fichiers de code touchés par ces contredits (`LivePrompts.kt`, `LiveState.kt`,
  `LiveProbeText.kt` — commentaires périmés signalés dans `docs/TESTING.md` et
  `docs/MASQUES-DEBUG.md`) relèvent d'une passe code, pas de la doc.