# iSee — voir, comprendre, parler

Application Android (Kotlin / Jetpack Compose) qui regarde avec la caméra, **segmente** ce
qu'elle voit avec SAM 3.1 sur une GPU distante, **décrit** la scène avec un LLM vision
(API `chat/completions`) et **répond à la voix**. L'accueil est la **scène iSee** : une grande bulle « verre »
centrée, des satellites autour, un **gros texte paginé** (3 lignes) qui porte le dernier
message, et la **carte caméra** qui se dissout en damier dans la bulle en laissant son
« monde » aux couleurs de la photo. Tout passe par les gestes : tap = enregistrement
vidéo, double tap = photo, appui long = dictée, balayage vertical = changer de bulle,
balayage horizontal = tourner la page.

> Ce dépôt (`BaptisteFaisy/vision`) porte le produit : un seul commit de départ, daté du
> 26/10/2026. Chaque affirmation ici est relue dans le code de cet arbre ; ce qui vient d'une mesure appareil est daté et sourcé
> (`docs/PERF.md`). La scène iSee est **branchée** (l'accueil l'affiche, pas seulement les
> tests) — voir `docs/ARCHITECTURE.md` et `docs/OPTIMISATIONS.md` (ce qui a été refait, et
> mesuré).

## Ce qu'il y a dedans

| Module ou dossier | Rôle |
|---|---|
| `:core` | Bibliothèque Android (`dev.sam3vision.core`) : modèles, clients HTTP (LLM `chat/completions`, SAM `/segment` et `/release`, vidéo `/video`), **WebSocket `/live/ws`** pour le direct, TTS/STT, masques, distances, gestes, cadence du direct, réglages chiffrés, texte, design iSee (`core/…/iseedesign`) |
| `:app` | L'APK (`dev.sam3vision.app`) : Compose + CameraX, accueil iSee (`app/ihear`), Capture, Analyse, Chat, Direct, Réglages, mode tap vidéo (`app/tap`), lunettes (`app/glasses`), purge des captures (`app/cleanup`) |
| `:glasses-spike` | Spike jetable lunettes Meta (Wearables DAT), application séparée `dev.sam3vision.glasses`, hors APK de production |
| `tools/` | Garde passée en CI : fichier `.kt` < 500 lignes, fonction < 50 lignes |
| `docs/` | Architecture, contrats, recettes, et l'état de chaque doc (`docs/DOC-ETAT.md`) |

**Il n'y a pas de `backend/` dans ce dépôt.** Le service qui fait tourner SAM 3.1 sur GPU
(Modal : `/health`, `/segment`, `/live`, `/live/ws`, `/release`, `/video`) vit dans un **autre
dépôt**, privé, et n'est pas publié ici. `:core` et `:app` se contentent de l'appeler par son
URL ; rien de ce qui est serveur n'est vérifiable depuis cet arbre. En particulier
**`POST /video` (mode tap) n'a pas d'implémentation backend connue** : le client est écrit et
testé (`core/net/HttpSam31VideoClient.kt`, `DEFAULT_PATH = "/video"`), la route serveur ne
l'est pas ici.

## Le direct : WebSocket, une frame en vol

Le direct ne passe **plus** par `POST /live` : une seule fabrique `liveClient()`
(`core/net/LiveClientFactory.kt:19-25`) ouvre un **WebSocket `/live/ws`**
(`core/net/WsTransport.kt:35`) et sert les deux écrans qui parlent à SAM (Direct et page
caméra iSee). Chaque frame porte un `id` et se corrèle par file par `id`
(`core/net/WsLiveClient.kt`) ; **une seule frame en vol** à la fois — si le créneau est pris,
la frame est jetée, jamais mise en attente (`core/live/LiveSession.kt`). Seul `/health` reste
HTTP, délégué par `WsLiveClient.health()` (`core/net/WsLiveClient.kt:82`) ; `HttpLiveClient`
garde `POST /live` (`core/net/HttpLiveClient.kt:60`) **pour les tests JVM uniquement**.

Les concepts suivis sont **deux** par défaut — `person` et `door` —, et `LivePrompts.MAX = 2`
borne toute liste saisie dans le champ « Suivre » (`core/live/LivePrompts.kt:20,28`) : le
backend tronque de toute façon à `PROMPT_LIMIT = 2`.

## Taille de l'arbre (mesurée sur ce commit)

| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` versionnés | **364** | `git ls-files '*.kt' \| wc -l` |
| Lignes de production (`*/src/main`) | **22 565** | `git ls-files '*/src/main/*.kt' \| xargs cat \| wc -l` |
| dont `:core` / `:app` / `:glasses-spike` | **74 f. / 7 666 l.** · **130 f. / 13 394 l.** · **12 f. / 1 505 l.** | idem, par module |
| `@Test` | **1 011** = **1 004 JVM** (`:core` 663, `:app` 305, spike 36) + **7 instrumentés** (`:app` 6, spike 1) | `git ls-files '*.kt' \| xargs grep -h '@Test' \| wc -l` |
| `@Preview` Compose | **11** | `git ls-files '*.kt' \| xargs grep -h '@Preview' \| wc -l` |
| Plus gros fichier | `app/src/main/java/dev/sam3vision/app/ihear/IseeScenePane.kt` — **496** lignes | `git ls-files '*/src/main/*.kt' \| xargs wc -l \| sort -rn \| head` |

## Ce qu'il faut pour s'en servir

1. **Une clé LLM** exposant une API `chat/completions` (base URL + modèle) — saisie dans **Réglages**, stockée
   chiffrée (Keystore), ou fournie au build par `local.properties` (gitignoré). Sans clé,
   l'app le dit et n'appelle pas. Les défauts du code, **identiques pour toutes les clés** :
   base `https://inference.baseten.co/v1`, modèle `meta-models/muse-spark-open-1026`
   (`Settings.DEFAULT_LLM_BASE_URL` / `DEFAULT_LLM_MODEL`,
   `core/…/settings/Settings.kt:87-88`).
2. **Un endpoint de segmentation** SAM 3.1 — champ *Base URL* dans Réglages, vide = pas
   d'envoi (la photo part directement au LLM). À défaut de saisie, la **région** choisie aux
   Réglages (Europe ou États-Unis) fournit l'URL de l'app déployée dans cette région ; une
   URL injectée au build (`sam.base.url`, `sam.us.base.url`) passe avant le preset
   (`resolveSamEndpoint`, `core/settings/SamRegion.kt:50-55`). Voir `docs/ARCHITECTURE.md`.
3. **Facultatif** : clé ElevenLabs (voix + repli de dictée), clé ASR Meta (dictée). Sans clé
   de voix, rien n'est annoncé à l'oreille et **aucune erreur ne s'affiche** — c'est le piège
   documenté dans `docs/TESTING.md`.

## Construire

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug      # tests JVM + APK debug
./gradlew :glasses-spike:assembleDebug                    # le spike lunettes, séparément
python3 tools/check-kotlin-limits.py                      # gardes de style
bash scripts/build.sh                                     # assembleDebug --offline + contrôle de l'APK
```

| Chaîne d'outils | Valeur (lue dans le dépôt) |
|---|---|
| Gradle | 8.14.1 (`gradle/wrapper/gradle-wrapper.properties:3`) |
| AGP | 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 (`gradle/libs.versions.toml`) |
| compileSdk / targetSdk | 36 partout |
| minSdk | **29** pour `:app` et `:glasses-spike`, 26 pour `:core` (bibliothèque) |
| Java | bytecode et `-jvm-target` **17** ; le JDK n'est **pas** épinglé dans le dépôt (`JAVA_HOME` du poste, jamais `org.gradle.java.home`) |

Sur cette machine (r630) : `export JAVA_HOME=~/toolchains/jdk17 ANDROID_HOME=$HOME/android-sdk`,
puis `./gradlew --offline`. L'APK cible des téléphones **Android 10 et plus** (`minSdk 29`) :
le SDK lunettes Meta l'exige. Dépendances : coroutines, OkHttp, AndroidX (Compose, CameraX,
Room, WorkManager, security-crypto), JUnit 4, plus Guava, jump3r (MP3 pur Java) et
`mwdat-*` pour les lunettes.

Comptage des tests (fait sur cet arbre par `grep -h '@Test'`, commandes ci-dessus) : **1 011**
annotations, dont **1 004 JVM** (`:core` 663, `:app` 305, spike 36) et **7 instrumentés**
(`app/src/androidTest` 6, `glasses-spike/src/androidTest` 1). Le nombre de tests réellement
**exécutés** n'est pas mesuré ici ; `docs/TESTING.md` donne la recette.

## CI

Quatre workflows (`.github/workflows/`) : `ci.yml` — cinq jobs, un par VM, chacun borné à
**5 minutes** : `ci` (gardes + tests JVM), `apk` (debug), `apk-release`, `lint`, `spike`
(lunettes) ; `ci-budget.yml` (refuse un job qui déclarerait plus de 5 minutes) ; `couche0.yml`
(toute PR déclare son `## Scope` en chemins, et refuse les clés en clair) ;
`stale-base-guard.yml` (retard sur la base, informatif). Tout tourne sur `ubuntu-latest` avec
cache Gradle via `gradle/actions/setup-gradle` : dépôt public, donc minutes gratuites et VM
neuve par job. Une PR qui ne touche que de la doc ou de la config ne lance aucun Gradle
(l'étape de périmètre de `ci` le décide) : son verdict tombe en quelques secondes.

Aucune clé n'est **versionnée**, et un job déclenché par la PR d'un **fork** ne voit aucun
secret. Le job `ci` sait en revanche lire des secrets **facultatifs** du dépôt
(`LLM_API_KEY`, `TTS_API_KEY`, …) pour les injecter dans `local.properties` et embarquer des
clés dans l'APK qu'il publie : absent ces secrets, l'APK sort sans clé et la saisie se fait
dans Réglages. Détail : `docs/CLES-DU-BUILD.md`.

## Docs

- `docs/ARCHITECTURE.md` — les trois modules, le flux capture → segmentation → LLM → voix,
  le contrat HTTP, les chemins des clés, la carte des fichiers d'entrée.
- `docs/PERF.md` — les chiffres tenus : constantes lues dans le code et mesures datées.
- `docs/DOC-ETAT.md` — doc par doc : à jour, périmée ou inexacte.
- `docs/TESTING.md` — comptages, recettes émulateur et téléphone, pièges.
- `NOTES.md` — versions épinglées et décisions techniques.

## Licence

MIT — voir `LICENSE`. Faille de sécurité : `SECURITY.md` (jamais d'issue publique).
