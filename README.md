# iSee — les yeux qui parlent

iSee est une application Android qui aide une **personne aveugle ou malvoyante** à
comprendre ce qui l'entoure : le téléphone filme, une IA **segmente** ce qu'elle voit
(objets, personnes, portes), une autre IA **décrit** la scène et répond aux questions,
et le téléphone **parle** — distances, consignes de marche, guidage. Un **proche** peut
suivre à distance, en direct, depuis son navigateur.

> Ce dépôt (`BaptisteFaisy/vision`) porte le produit entier : l'app Android, le backend
> GPU qui segmente, et la page du proche. Chaque affirmation ci-dessous est relue dans le
> code de cet arbre ; chaque chiffre est mesuré le **28/10/2026** (commande donnée) sur
> `master`. Les chiffres tenus dans la durée vivent dans `docs/PERF.md` — c'est le seul
> endroit qui fait foi.

## Le problème

Voir, c'est percevoir, comprendre et agir — trois choses qui demandent la vue :

1. **Percevoir** : quoi autour de moi, où, à quelle distance, ce qui bouge ?
2. **Comprendre** : décrire la scène, répondre à « c'est quoi, ça ? », guider vers une
   destination.
3. **Accompagner** : laisser un proche suivre à distance, sans appeler sans arrêt.

## La solution

Un téléphone ordinaire, une carte GPU distante, et la voix comme seule interface :

1. **Filmer** — la caméra tourne ; tap = démarrer/arrêter la vidéo, appui long = poser
   une question parlée. Aucun menu à voir, aucun balayage.
2. **Segmenter** — chaque image part vers SAM 3.1 sur une GPU Modal (Europe) qui renvoie
   objets, boîtes et masques, en photo comme en direct.
3. **Mesurer** — les distances se calculent **sur le téléphone** (sténopé + profondeur
   embarquée), sans GPU ni requête.
4. **Décrire et répondre** — un LLM vision décrit la scène et répond aux questions, sans
   historique d'une ouverture à l'autre.
5. **Parler et guider** — voix de synthèse, pastilles de distance, consignes de marche,
   navigation vers Google Maps — pendant que le proche suit sur sa page.

```
┌──────────────┐  images   ┌──────────────────┐  objets+masques  ┌──────────────┐
│  Téléphone   │ ────────► │  GPU Modal (EU)  │ ───────────────► │  LLM vision  │
│  caméra+voix  │           │  SAM 3.1 direct  │                  │  description │
└──────────────┘           └──────────────────┘                  └──────────────┘
       │                            │                                  │
       │  distances sur place       │  une frame en vol                │  voix (TTS)
       ▼                            ▼                                  ▼
┌──────────────┐           ┌──────────────────┐                  ┌──────────────┐
│  iSee parle  │           │  Proche (navigateur, suivi temps réel)    │  Guidage Maps  │
└──────────────┘           └──────────────────┘                  └──────────────┘
```

## Ce que sait faire l'app

- **Accueil iSee** — une bulle de verre sur fond blanc, la caméra fondue au-dessus,
  seule la phrase en cours sous la bulle. Tap = vidéo, appui long (300 ms) = dictée
  puis réponse parlée ; ni balayage, ni historique conservé
  (`app/isee/`, `docs/ISEE-DESIGN-PORTRAIT.md`).
- **Direct** — 6 concepts suivis (`person`, `door`, `bicycle`, `car`, `truck`,
  `electric scooter`), une frame en vol sur WebSocket `/live/ws`, pastilles, distances
  et annonces vocales (`docs/LIVE-SPEC.md`).
- **Photo → description → questions** — les écrans d'origine (Capture, Analyse, Chat,
  Direct, Tap) restent atteignables depuis les Réglages (« Other screens »).
- **Mode tap** — filmer court, recevoir un résumé parlé. Client `POST /video` écrit et
  testé ; la route serveur n'est implémentée nulle part ici (`docs/MODE-TAP.md`).
- **Profondeur embarquée (bêta)** — carte de proximité 100 % sur le téléphone (Depth
  Anything V2, repli MiDaS), modèles téléchargés une fois, fusionnée aux masques
  (`docs/DEPTH.md`).
- **Navigation vocale** — « emmène-moi à X » ouvre Google Maps en mode piéton ; routeur
  Valhalla intégré côté app et backend CPU (`docs/NAVIGATION-VOCALE.md`).
- **Guidage à pied** — consignes « tout droit », obstacles et traversée annoncés à la
  voix (`docs/GUIDAGE-TOUT-DROIT.md`).
- **Lunettes Ray-Ban Meta** — caméra et micro des lunettes (SDK DAT 1.0.0), banc d'essai
  intégré à l'app (`docs/META-GLASSES.md`).
- **Suivi proche** — lien de partage vers une page temps réel : phrase en cours, objets
  proches, carte (`suivi-parent/README.md`).

## Contenu du dépôt

| Bloc | Rôle |
|---|---|
| `:core` | Bibliothèque Android (`dev.sam3vision.core`, minSdk 26) : clients réseau (LLM, SAM, WebSocket du direct, tracker, vidéo), cadence, masques, distances, profondeur pure, gestes, voix, réglages chiffrés |
| `:app` | L'APK (`dev.sam3vision.app`, minSdk 29) : Compose + CameraX, accueil iSee, anciens écrans, lunettes + banc, profondeur (ONNX, LiteRT), purge des captures |
| `server/` | Backend Modal (Python) : direct/photo (`live_fast`, app `sam3-eu-a100`), routage Valhalla CPU (`valhalla-idf-cpu`), tracker (`track_app.py`, déploiement manuel), legacy photo (`modal_app.py`), sondes GPU — plus `legacy-expo-app/`, l'ancienne app Expo archivée |
| `suivi-parent/` | Page du proche (statique, zéro dépendance npm) + relais Vercel ; embarquée dans chaque APK (`caregiverAssets`, `app/build.gradle.kts:179`) |
| `tools/` | Gardes de CI (tailles Kotlin, design Portrait, budget de latence, navigation vocale), faux LLM/SAM pour les tests, utilitaires |
| `scripts/` | Build, recettes téléphone/émulateur, vérifications, budget de latence |
| `docs/` | Architecture, contrats, recettes ; `docs/DOC-ETAT.md` dit l'état de chaque doc |

## Stack technique

### App Android (`gradle/libs.versions.toml`, wrapper, builds)

| Couche | Choix |
|---|---|
| Langage / build | Kotlin 2.2.21, Gradle 8.14.1, AGP 8.11.1, KSP 2.2.21-2.0.5, bytecode JVM 17 (JDK 17 par `JAVA_HOME`, jamais épinglé) |
| SDK | compileSdk / targetSdk 36 ; minSdk **29** (`:app`), 26 (`:core`) |
| UI / caméra | Compose (BOM 2026.05.01, Material3), CameraX 1.6.0 (dont `camera-video`), activity-compose 1.13.0, lifecycle 2.10.0 |
| Données | Room 2.8.5 (KSP), WorkManager 2.12.0 (purge horaire), security-crypto 1.1.0, core-ktx 1.18.0 |
| Réseau | OkHttp 4.12.0 (un seul client, un seul pool), `org.json` (fourni par Android, zéro dépendance), coroutines 1.10.2 |
| Voix / audio | ElevenLabs (TTS + repli STT), Meta ASR (dictée), jump3r 1.0.4 (MP3 pur Java) |
| Lunettes / IA embarquée | Meta `mwdat-*` 1.0.0 (la seule version servie par les lunettes), ONNX Runtime 1.18.0, LiteRT 2.17.0, Guava 33.3.1-android |
| Version | `versionName = 1.0.<commits>-<sha>`, `versionCode` = date du commit (un APK périmé ne peut pas écraser le téléphone) |

### Backend `server/` (Python, Modal)

| Élément | Valeur |
|---|---|
| Runtime | Python ≥ 3.11, FastAPI (`fastapi[standard]==0.162.0`), Modal CLI 1.6.2 |
| Modèle | `unsloth/sam3.1` (miroir non gated de `facebook/sam3.1`), `transformers==5.20.2`, `torch==2.14.1`, `torchvision==0.29.1` |
| Déployé | `sam3-eu-a100` (A100-40GB, `eu-west`) depuis `server/backend/live_fast/` — `/health`, `/segment`, `/live`, `/live/ws`, `/release` ; redéployé par la CI à chaque push qui le touche, avec ses leviers (graphe CUDA, snapshot, `scaledown_window=60`) |
| Routage | `valhalla-idf-cpu` (CPU, zéro GPU, tuiles Île-de-France) — `/health`, `/route` ; déployé par la CI |
| Autres | `modal_app.py` (legacy photo `/analyze`, `/ask`), `track_app.py` (tracker `/session`, `/frame` — déploiement manuel), `probe_*.py` (sondes de mesure, jamais déployées en routine) |
| Règle d'or | **une seule GPU à la fois** : la caméra active allume la carte, coupée elle s'éteint (`POST /release`) ; aucune tâche planifiée ne garde la carte allumée |

### Suivi proche (`suivi-parent/`)

Page statique (HTML/CSS/JS, shader WebGL régénéré depuis le shader Android),
relais Vercel en Node sans dépendance, stockage Upstash Redis (repli mémoire en
dev), Google Maps si une clé est fournie (OpenFreeMap sinon).

### Voix et LLM

| Rôle | Fournisseur | Contrat |
|---|---|---|
| Dictée | Meta ASR (`muse-voice-transcribe-1.0`), repli ElevenLabs Scribe | `POST {meta}/asr/transcribe` (WAV 16 kHz mono) |
| Voix | ElevenLabs TTS (`eleven_v4_turbo`, voix Laure ; Jade US proposée) | `POST {elevenlabs}/text-to-speech/{voiceId}` → MP3 |
| Description / questions | Baseten, `meta-models/muse-spark-open-1026` (défauts `Settings.kt:156-157`) | `POST {llm}/chat/completions` (OpenAI-compatible), température 0,2, budgets 1 200 / 200 tokens, 400 caractères (`HttpLlmClient.kt:21-24`) |

## S'en servir

1. **Une clé LLM** exposant `chat/completions` (base URL + modèle) — saisie dans
   **Réglages**, stockée chiffrée (Keystore), ou fournie au build par `local.properties`
   (gitignoré). Sans clé, l'app le dit et n'appelle pas.
2. **Un endpoint de segmentation** SAM 3.1 — champ *Base URL* dans Réglages, vide = pas
   d'envoi (la photo part directement au LLM). Sinon la **région** (Europe, preset
   `sam3-eu-a100`, ou États-Unis) fournit l'URL ; une URL injectée au build passe avant
   le preset (`SamRegion.kt:50-56`). Changer de région n'allume rien.
3. **Facultatif** : clé ElevenLabs (voix + repli de dictée), clé ASR Meta (dictée),
   clé Google Maps (carte du proche). Sans clé de voix, rien n'est annoncé à l'oreille
   et **aucune erreur ne s'affiche** — c'est le piège documenté dans `docs/TESTING.md`.

## Construire et tester

Prérequis : JDK 17 + SDK Android (`JAVA_HOME`, `ANDROID_HOME`), Python 3 (gardes),
Node (page du proche, sans `npm install`).

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest   # tests JVM (2 202 @Test)
./gradlew :app:assembleDebug                               # APK debug
python3 tools/check-kotlin-limits.py                       # fichier < 500 l., fonction < 50 l.
python3 tools/check-isee-design.py                         # design Portrait obligatoire
bash scripts/test_scripts.sh                               # 117 assertions shell
node --test suivi-parent/test/session.test.mjs suivi-parent/test/relay.test.mjs
bash scripts/build.sh                                      # assembleDebug --offline + contrôle de l'APK
```

## Chiffres (mesurés le 28/10/2026 sur `master`)

| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` | **733** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \| wc -l` |
| Lignes Kotlin | **76 990** | même `find`, `-exec cat {} + \| wc -l` |
| Lignes de prod (`*/src/main`) | **43 457** — `:core` 167 f. / 16 654 l. · `:app` 240 f. / 26 803 l. | idem sous `core/src/main`, `app/src/main` |
| `@Test` JVM | **2 202** — `:core` 1 418 · `:app` 784 (dont banc lunettes 95) | `grep -rho '@Test' <dir> --include='*.kt' \| wc -l` |
| Tests instrumentés | **10** (appareil requis) | idem sur `app/src/androidTest*` |
| Backend Python | **31** fichiers, **6 094** lignes | `find server/backend -name '*.py' …` |
| Gardes | toutes vertes (tailles, Portrait, budget, voix, ci-budget, scripts, goldens, relais) | voir `docs/TESTING.md` §2 |

Le détail (constantes, budgets, seuils) est dans `docs/PERF.md` ; les recettes dans
`docs/TESTING.md`.

## CI et file de merge

- **PR** : un seul job `ci` (gardes + tests JVM) + `ci-budget` (≤ 5 min) + `couche0`
  (`## Scope` déclaré, motifs interdits) — les trois **requis**, verdict en minutes, une
  PR de docs en secondes (Gradle sauté).
- **File native** `pr-auto-merge` : rebase, agent de résolution de conflits, merge dès
  que les 3 checks sont verts. `master` est protégé (PR obligatoire).
- **Sur `master`** : `ci-native` (APK debug + release + lint), redéploiements Modal
  (`deploy-live-fast`, `deploy-valhalla-cpu`).
- Runners **auto-hébergés** `[self-hosted, linux, x64, vision]`, cache Gradle chaud.

Détail : `docs/CI-QUEUE.md`.

## Docs

- **Démarrer** : `docs/ARCHITECTURE.md` (modules, flux, contrat HTTP, clés),
  `docs/PERF.md` (chiffres tenus), `docs/TESTING.md` (comptes, recettes),
  `docs/DOC-ETAT.md` (état de chaque doc), `NOTES.md` (versions, décisions),
  `CHANGELOG.md` (journal depuis le 26/10/2026).
- **Direct** : `docs/LIVE-SPEC.md`, `docs/LATENCE-BUDGET.md`, `docs/TRACK-PERF.md`,
  `docs/T8-PREDICTION.md`, `docs/KEYFRAME-SCHEDULER.md`, `docs/L13-FUSION.md`.
- **Voix et navigation** : `docs/ELEVENLABS-TTS.md`, `docs/META-ASR.md`,
  `docs/NAVIGATION-VOCALE.md`, `docs/NAVIGATION.md`, `docs/VOIX-POSITIONS.md`,
  `docs/VOIX-ANGLAIS.md`, `docs/TEST-LONGPRESS-DICTEE.md`.
- **Image** : `docs/MASQUES-DEBUG.md`, `docs/MASK-DECODE.md`, `docs/DISTANCES.md`,
  `docs/DEPTH.md`.
- **Lunettes** : `docs/META-GLASSES.md`, `docs/GLASSES-ETAT.md`,
  `docs/GLASSES-DECISION.md`, `docs/LUNETTES-LANCEMENT.md`.
- **Produit** : `docs/MODE-TAP.md`, `docs/MODE-US.md`, `docs/ISEE-DESIGN-PORTRAIT.md`,
  `docs/GUIDAGE-TOUT-DROIT.md`, `docs/MICRO-VERROUILLE.md`, `docs/NETTOYAGE.md`,
  `docs/OPTIMISATIONS.md`, `docs/CLES-DU-BUILD.md`, `docs/CI-QUEUE.md`.
- **Serveur** : `server/README.md`, `server/backend/README.md`, `server/docs/`
  (contrats, latence, déploiement).
- **Proche** : `suivi-parent/README.md`.

## Licence et sécurité

MIT — voir `LICENSE`. **Jamais d'issue publique pour une faille** (`SECURITY.md`).
Aucune clé n'est versionnée : `local.properties` est gitignoré, les clés vivent dans le
Keystore Android ou les secrets du dépôt (lus par l'étape « Clés du build » de la CI).
