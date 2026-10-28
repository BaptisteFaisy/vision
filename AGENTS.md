# AGENTS.md — règles obligatoires pour tout agent LLM sur ce dépôt

> **À LIRE AVANT TOUTE MODIFICATION DE CODE.** Si tu es un agent (Claude Code, Codex, Cursor,
> Codebuff, Copilot…) travaillant sur ce dépôt : lis ce fichier en entier, puis les
> docs cités avant d'utiliser ce qu'ils documentent. Ne rien deviner à partir du seul code
> quand une règle est écrite ici.

## 0. Règle de datation (opposable)

Aucune date antérieure au **26/10/2026, 19 h** ne doit apparaître dans ce dépôt : ni dans le
code, ni dans les docs, ni dans les messages de commit. Un relevé plus ancien se cite
« relevé antérieur, hors de cet arbre », sans date ni sha. Le dépôt repart de son commit
unique du 26/10.

## 0 bis. Docs à lire avant d'agir (contrats détaillés)

- `README.md` — fonctionnalités réelles, transport du direct, endpoints, sécurité (clé debug).
- `NOTES.md` — versions épinglées et décisions techniques.
- `docs/ARCHITECTURE.md` + `docs/PERF.md` — état du produit et chiffres tenus.
- `docs/CI-QUEUE.md` — CI, budget 5 min par job, gardes `couche0` / `stale-base-guard`, contrat `## Scope`.
- `docs/NETTOYAGE.md` — purge horaire (Room + WorkManager).
- `docs/META-ASR.md` — contrat dictée Meta Muse (facturation 402, pièges).
- `docs/ELEVENLABS-TTS.md` — contrat voix (voix premade vs bibliothèque, 402).
- `docs/MODE-TAP.md` — mode tap (vidéo → SAM 3.1) · `docs/MODE-US.md` — région US.
- `docs/DISTANCES.md` — distances sténopé sur le téléphone.
- `docs/TESTING.md` — recette émulateur, limites connues.
- `docs/META-GLASSES.md` + `docs/glasses-dat-spike.md` — lunettes Ray-Ban Meta (lire avant de
  toucher `app/glasses/` ; l'ancien module séparé `glasses-spike` n'existe plus, ni dans
  `settings.gradle.kts` ni sur le disque).
- `docs/MASQUES-DEBUG.md` — z-order `AndroidView`/Compose et recette des masques.
- `docs/LIVE-SPEC.md` — contrat du direct (cité par `core/net/HttpLiveClient.kt:42`).
- `docs/ISEE-DESIGN-PORTRAIT.md` — design Portrait de l'écran iSee, obligatoire (§3.12).
- `server/README.md` + `server/docs/` — backend Modal (direct déployé, Valhalla CPU,
  tracker, legacy) : lire avant de toucher `server/` ou un endpoint.
- `suivi-parent/README.md` — page du proche, embarquée dans l'APK.
- `CHANGELOG.md` — historique.

## 1. C'est quoi ce dépôt

App Android native **Kotlin/Compose** : photo → description par un LLM vision → questions/réponses ;
dictée Meta ASR (repli ElevenLabs Scribe), voix ElevenLabs (TTS), segmentation **SAM 3.1** sur
endpoint Modal ; **iSee en écran d'accueil** (bulle de verre plein écran, caméra fondue en
haut, gestes, masques, distances, voix). Les anciens écrans (Capture/Analyse/Chat/Direct/Tap) restent
là, atteignables depuis les Réglages, section « Other screens »
(`app/SettingsScreen.kt:407-416`, routes `app/AppNav.kt:37-75`).
**Backend dans `server/`** (Modal : direct déployé, Valhalla CPU, tracker, legacy) ;
**aucune clé dans le dépôt.**

| Élément | Valeur |
|---|---|
| `:core` | domaine + réseau + logique pure JVM (`model, net, mask, distance, settings, live, gesture, predict, audio, usecase`), minSdk 26 |
| `:app` | Compose + CameraX : écrans, ViewModels, `dev.sam3vision.app`, minSdk **29** (`app/build.gradle.kts:87`) |
| Direct | **WebSocket `/live/ws`**, une frame en vol : `core/net/WsLiveClient.kt` sur `WsTransport` (`OkHttpWsTransport` en prod, faux JVM en test) |
| Concepts du direct | **6** (`person`, `door`, `bicycle`, `car`, `truck`, `electric scooter`), `LivePrompts.MAX = 6` (`core/live/LivePrompts.kt:27,34`) |
| Tests | **2 202 `@Test`** JVM (`:core` 1 418, `:app` 784, mesure statique du 28/10/2026, voir `docs/TESTING.md`) + 10 instrumentés (`:app` : 9 + 1 banc `MockGlassesTest`) |
| Taille | **733** fichiers `.kt`, **43 457** lignes de prod (`:core` 16 654, `:app` 26 803, mesure du 28/10/2026) |
| Versions | JDK Temurin 17.0.21 (par `JAVA_HOME`, hors dépôt) · Gradle 8.14.1 · AGP 8.11.1 · Kotlin 2.2.21 · compileSdk/targetSdk 36 |

## 2. Commandes de vérification (à passer avant chaque push)

```bash
export JAVA_HOME=~/toolchains/jdk17 ANDROID_HOME=$HOME/android-sdk
./gradlew :core:testDebugUnitTest --offline     # tests JVM :core
./gradlew :app:testDebugUnitTest --offline      # tests JVM :app
./gradlew assembleDebug --offline               # APK debug
python3 tools/check-kotlin-limits.py            # garde-fou tailles (§3.1)
```

Ce sont les chemins de **cette** machine (r630) : le JDK 17 est dans `~/toolchains/jdk17`, le
SDK Android dans `~/android-sdk`. **Aucun chemin en dur dans le dépôt** : la chaîne d'outils
vient de `JAVA_HOME` / `ANDROID_HOME` (§3.3).

Cache Gradle local, pas d'Android Studio, `--offline`. Toute commande Gradle passe sous
`timeout 280` : un build bloqué ne doit jamais manger la session. Tests JVM uniquement
(`TestHttpServer` maison, pas de MockWebServer) ; pas d'instrumentation sans demande explicite
(les 10 tests `androidTest` exigent un appareil).

## 3. Règles strictes — la CI échoue si elles sont violées

1. **Tailles Kotlin** : fichier `.kt` < 500 lignes, fonction/composable < 50 lignes
   (`tools/check-kotlin-limits.py`, `ci.yml:139-140`). **Zéro duplication** : paramétrer et
   extraire un helper plutôt que copier. Le garde-fou passe sans exception sur cet arbre ; les
   plus gros fichiers sont `app/isee/LiveProbe.kt` (**495** lignes) et `app/LiveViewModel.kt`
   (**493**) — ne pas les faire grossir, extraire comme ça a été fait pour
   `app/isee/IseeScenePane.kt` (487 → 222 lignes, en-tête/dissolution/couches sortis),
   `app/isee/LiveProbe.kt` (489 → 471, `cleared()`/`probeFailureText()` sortis et testés,
   remonté à 495 depuis — au plafond) et `app/LiveViewModel.kt` lui-même
   (498 → 492, `frameErrorState()`/`transportLabel()` purs et testés, remonté à 493).
2. **Aucune clé ni secret versionné** (`sk-…`, `sk_…`, `ghp_…`), jamais de `local.properties`.
   Les clés vivent dans Réglages (chiffré, Keystore, repli `PrivateFileSettingsStore`) ou
   `local.properties` (gitignoré) → `BuildConfig`. **Par défaut** (demande 2026-10-26) l'APK
   embarque les clés pour qu'une installation neuve n'ait rien à saisir
   (`docs/CLES-DU-BUILD.md`) : un APK est donc extractible, ne le distribuer qu'aux personnes à
   qui on prête ces clés, jamais en public.
3. **Jamais de ligne `org.gradle.java.home=`** dans le `gradle.properties` versionné —
   portabilité par `JAVA_HOME` (`gradle.properties:25-27`, garde CI `ci.yml:103-110`). Le JDK se
   règle dans `~/.gradle/gradle.properties` du poste.
4. **Dépendances** : toute dépendance Gradle nouvelle se déclare dans `app/DEPS.txt` **avant**
   usage (budget CI 5 min ; un artefact hors cache = téléchargement froid = échec).
5. **Périmètre de PR** (`docs/CI-QUEUE.md`) : corps de PR avec `## Scope` et une puce
   `` - `chemin` — raison `` par fichier touché ; tout fichier touché est déclaré, toute
   déclaration sert.
6. **Réseau** : un seul client HTTP (`core/net/Http.kt`, `defaultHttpClient()`, 60 s par
   défaut), **une seule fabrique pour le direct** (`core/net/LiveClientFactory.kt` →
   `liveClient()`, WebSocket ; `LiveProbe` et `LiveViewModel` partagent cette fabrique), un seul
   mapper JSON (`org.json`, `net/Json.kt`). Pas de Retrofit, pas de kotlinx-serialization, pas
   de MockWebServer. `Result.flatMap` n'existe pas → helper `Result.then` (`net/Http.kt:306`).
7. **Direct = WebSocket, pas de repli HTTP sur les frames.** `POST /live` **n'est plus appelé**
   en production : le seul appel de `LiveClient.live(…)` en prod part de `LiveSession.kt:138`,
   et le client vient toujours de `liveClient()` → `WsLiveClient`. `HttpLiveClient` ne garde
   `POST /live` que pour les tests JVM, et n'est monté dans la fabrique que pour déléguer
   `GET /health` (`LiveClientFactory.kt:47`, `WsLiveClient.kt:98`). Ne pas le réintroduire.
   `POST /segment` (photo) et `POST /video` (mode tap) restent HTTP — **`/video` n'a aucune
   implémentation backend connue** : le client est écrit, la route serveur n'est pas
   vérifiable ici (`git grep -n '"/video"' server/backend` vide). Toute frame WS porte un `id`
   et se corrèle par **file par `id`** (`core/net/WsLiveClient.kt`) ; le transport s'injecte
   via `WsTransport` pour rester testable sans réseau.
8. **Architecture JVM** : la logique testable vit dans `:core` ; les wrappers plateforme
   (Bitmap, MediaPlayer, Compose) restent fins, dans `:app`.
9. **GPU Modal — une seule carte à la fois**, toutes régions confondues (`docs/MODE-US.md`).
   Ne jamais déployer ni allumer une GPU de ta propre initiative, jamais une deuxième app GPU :
   `~/bin/sam3-gpu status|busy` d'abord, `on --reason "…"` seulement sur demande explicite de
   l'utilisateur. Changer de région dans l'app n'allume rien (`core/settings/SamRegion.kt:1-9`).
10. **Téléphone** : **jamais `adb` nu** — `~/bin/phone run --task "…" -- <cmd>` (code de sortie
    2 = appareil occupé).
11. **Ne jamais contourner un garde-fou** (CI, `check-kotlin-limits.py`, `couche0`) pour faire
    passer un build : corriger la cause. Pas de push direct sur `master`, pas de merge sans
    demande explicite, pas de réécriture de branche partagée ; seule tolérance,
    `--force-with-lease` après un push normal raté.
12. **Design iSee = Portrait, obligatoire** (`docs/ISEE-DESIGN-PORTRAIT.md`, décision user
    2026-10-27) : fond blanc, une seule boule de verre (aucun satellite, aucun pixel),
    caméra fondue en haut, seule la phrase en cours sous la boule, palette Amber figée.
    Ne pas réintroduire l'ancien design (`IseeSceneArt`, `IseeScenePixels`,
    `satellitePositions`, `sampleGrid`, `pixelTrip`, `bubbleLookIndex`, `BubbleLook`,
    `textPages` — quarantaine, garde `tools/check-isee-design.py`), ne pas modifier
    palettes ni géométrie sans demande explicite du user.

## 4. Git & merge

- Base de référence : **`origin/master`** du dépôt **`BaptisteFaisy/vision`** — le seul nom qui
  fait autorité. Aucun autre nom de dépôt ne doit apparaître dans la doc.
- Branche `feat/…` → PR → checks `ci` + `ci-budget` + `couche0` verts → **file de merge native
  GitHub** (`master` est protégé : PR obligatoire, checks requis, auto-merge, rebase). Aucun outil
  de file externe, et pas de gate IA : la seule porte est la CI (`docs/CI-QUEUE.md`).
- Une branche non fusionnée n'est pas la vérité du produit : elle se lit dans `master` après
  merge, un point c'est tout. L'état de `master` sur les deux points qui bougeaient le plus :
  **6 concepts** (`core/live/LivePrompts.kt:27,34`) et **deux cadences** — Direct
  350/500/900 ms (`core/live/LiveSession.kt:34`), sonde iSee `fastMs = 0`, `concurrency = 1`,
  budget 150 ms (`LiveSession.kt:43-50`).
- Langue du projet : **français** (code commenté, commits `feat(isee): …`, docs).

## 4 bis. Arbre de travail — partir d'un état vérifié, jamais d'un arbre sale

- Avant la première écriture : `git worktree list`, `git branch --show-current`,
  `git status --short`. Ne jamais empiler du travail sur un arbre sale, une
  branche périmée (upstream disparu) ou un tip en retard sur `origin/master` :
  partir de `origin/master` dans une branche (ou un worktree) dédiée.
- Ne jamais recopier des fichiers d'une branche à l'autre **à la main** : une
  copie manuelle diverge en silence (fichiers oubliés, doublons sous un autre
  nom) et la CI ne la voit pas. Passer par `git merge`, `git cherry-pick` ou
  `git checkout <branche> -- <chemin>`, qui gardent la traçabilité.
- Un worktree partagé qui sert de référence (miroir de build, arbre
  d'orchestration) n'est jamais un arbre d'édition : il reste sur `master`
  propre, tout le reste vit dans des worktrees dédiés.

## 5. À la livraison

Vérifier réellement ce qui est demandé : tests qui couvrent le changement, build vert, rapport
honnête de ce qui n'a pas pu être vérifié. **Un chiffre non mesuré s'écrit « non mesuré », pas
estimé.** Ne jamais déclarer « vérifié » sans avoir exécuté la vérification.
