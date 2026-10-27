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
- `docs/CI-QUEUE.md` — CI, budget 5 min par job, gardes `couche0` / `stale-base-guard`, contrat `## Scope`.
- `docs/NETTOYAGE.md` — purge horaire (Room + WorkManager).
- `docs/META-ASR.md` — contrat dictée Meta Muse (facturation 402, pièges).
- `docs/ELEVENLABS-TTS.md` — contrat voix (voix premade vs bibliothèque, 402).
- `docs/MODE-TAP.md` — mode tap (vidéo → SAM 3.1) · `docs/MODE-US.md` — région US.
- `docs/DISTANCES.md` — distances sténopé sur le téléphone.
- `docs/TESTING.md` — recette émulateur, limites connues.
- `docs/META-GLASSES.md` + `docs/glasses-dat-spike.md` — lunettes Ray-Ban Meta (lire avant de
  toucher `app/glasses/` ou `glasses-spike/`).
- `docs/MASQUES-DEBUG.md` — z-order `AndroidView`/Compose et recette des masques.
- `CHANGELOG.md` — historique. **`docs/LIVE-SPEC.md` est cité par `core/net/HttpLiveClient.kt:30`
  mais n'existe pas dans ce dépôt** : le contrat serveur est dans le dépôt backend.

## 1. C'est quoi ce dépôt

App Android native **Kotlin/Compose** : photo → description par un LLM vision → questions/réponses ;
dictée Meta ASR (repli ElevenLabs Scribe), voix ElevenLabs (TTS), segmentation **SAM 3.1** sur
endpoint Modal ; **iSee en écran d'accueil** (chat plein écran + fenêtre caméra flottante,
gestes, masques, distances, voix). Les anciens écrans (Capture/Analyse/Chat/Direct/Tap) restent
là, atteignables par « Autres écrans » des Réglages (`app/AppNav.kt:63-81`,
`app/SettingsScreen.kt:151-160`).
**Aucun code de backend ici, aucune clé dans le dépôt.**

| Élément | Valeur |
|---|---|
| `:core` | domaine + réseau + logique pure JVM (`model, net, mask, distance, settings, live, gesture, predict, audio, usecase`), minSdk 26 |
| `:app` | Compose + CameraX : écrans, ViewModels, `dev.sam3vision.app`, minSdk **29** (`app/build.gradle.kts:43`) |
| `:glasses-spike` | prototype DAT lunettes, `include` dans le build, **non production** |
| Direct | **WebSocket `/live/ws`**, une frame en vol : `core/net/WsLiveClient.kt` sur `WsTransport` (`OkHttpWsTransport` en prod, faux JVM en test) |
| Concepts du direct | **2** (`person`, `door`), `LivePrompts.MAX = 2` (`core/live/LivePrompts.kt:20,28`) |
| Tests | **1 011 `@Test`** = 1 004 JVM (`:core` 663, `:app` 305, `glasses-spike` 36) + 7 instrumentés (`:app` 6, spike 1) |
| Taille | **364** fichiers `.kt`, **22 565** lignes de prod (`:core` 7 666, `:app` 13 394, spike 1 505) |
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
(les 7 tests `androidTest` exigent un appareil).

## 3. Règles strictes — la CI échoue si elles sont violées

1. **Tailles Kotlin** : fichier `.kt` < 500 lignes, fonction/composable < 50 lignes
   (`tools/check-kotlin-limits.py`, `ci.yml:129-131`). **Zéro duplication** : paramétrer et
   extraire un helper plutôt que copier. Le garde-fou passe sans exception sur cet arbre ; le
   plus gros fichier est `app/ihear/IseeScenePane.kt` (**496** lignes) — ne pas le faire
   grossir, extraire comme ça a été fait pour `app/LiveMessages.kt` (45 lignes) et
   `app/ihear/LiveProbeText.kt` (94 lignes).
2. **Aucune clé ni secret versionné** (`sk-…`, `sk_…`, `ghp_…`), jamais de `local.properties`.
   Les clés vivent dans Réglages (chiffré, Keystore, repli `PrivateFileSettingsStore`) ou
   `local.properties` (gitignoré) → `BuildConfig`. **Par défaut** (demande 2026-10-26) l'APK
   embarque les clés pour qu'une installation neuve n'ait rien à saisir
   (`docs/CLES-DU-BUILD.md`) : un APK est donc extractible, ne le distribuer qu'aux personnes à
   qui on prête ces clés, jamais en public.
3. **Jamais de ligne `org.gradle.java.home=`** dans le `gradle.properties` versionné —
   portabilité par `JAVA_HOME` (`gradle.properties:21-24`, garde CI `ci.yml:95-100`). Le JDK se
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
   de MockWebServer. `Result.flatMap` n'existe pas → helper `Result.then` (`net/Http.kt:256`).
7. **Direct = WebSocket, pas de repli HTTP sur les frames.** `POST /live` **n'est plus appelé**
   en production : le seul appel de `LiveClient.live(…)` en prod part de `LiveSession.kt:134`,
   et le client vient toujours de `liveClient()` → `WsLiveClient`. `HttpLiveClient` ne garde
   `POST /live` que pour les tests JVM, et n'est monté dans la fabrique que pour déléguer
   `GET /health` (`LiveClientFactory.kt:37-38`, `WsLiveClient.kt:82`). Ne pas le réintroduire.
   `POST /segment` (photo) et `POST /video` (mode tap) restent HTTP — **`/video` n'a aucune
   implémentation backend connue** : le client est écrit, la route serveur n'est pas
   vérifiable ici. Toute frame WS porte un `id` et se corrélie par **file par `id`**
   (`core/net/WsLiveClient.kt`) ; le transport s'injecte via `WsTransport` pour rester
   testable sans réseau.
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

## 4. Git & merge

- Base de référence : **`origin/master`** du dépôt **`BaptisteFaisy/vision`** — le seul nom qui
  fait autorité. Aucun autre nom de dépôt ne doit apparaître dans la doc.
- Branche `feat/…` → PR → checks `ci` + `ci-budget` + `couche0` verts → **file de merge native
  GitHub** (`master` est protégé : PR obligatoire, checks requis, auto-merge, rebase). Aucun outil
  de file externe, et pas de gate IA : la seule porte est la CI (`docs/CI-QUEUE.md`).
- Une branche non fusionnée n'est pas la vérité du produit : elle se lit dans `master` après
  merge, un point c'est tout. L'état de `master` sur les deux points qui bougeaient le plus :
  **2 concepts** (`core/live/LivePrompts.kt:20,28`) et **deux cadences** — Direct
  350/500/900 ms (`core/live/LiveSession.kt:33`), sonde iSee `fastMs = 0`, `concurrency = 1`,
  budget 150 ms (`LiveSession.kt:42-47`).
- Langue du projet : **français** (code commenté, commits `feat(ihear): …`, docs).

## 5. À la livraison

Vérifier réellement ce qui est demandé : tests qui couvrent le changement, build vert, rapport
honnête de ce qui n'a pas pu être vérifié. **Un chiffre non mesuré s'écrit « non mesuré », pas
estimé.** Ne jamais déclarer « vérifié » sans avoir exécuté la vérification.