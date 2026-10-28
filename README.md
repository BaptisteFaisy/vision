# iSee — voir, comprendre, parler

Application Android (Kotlin / Jetpack Compose) qui regarde avec la caméra, **segmente** ce
qu'elle voit avec SAM 3.1 sur une GPU distante, **décrit** la scène avec un LLM vision
(API `chat/completions`) et **répond à la voix**. L'accueil est la **scène iSee** : une grande bulle « verre »
sur fond blanc, avec son ombre douce. La **caméra se fond dans le blanc au-dessus de
la boule** : plus haute au repos, plus compacte pendant la parole. Les **sous-titres
courts** suivent la lecture audio et disparaissent au silence ; pendant l'appui long,
le moteur de dictée fournit des aperçus périodiques de la voix du user. Tout passe
par les gestes : tap = enregistrement
vidéo, double tap = photo, appui long = dictée. **Plus de balayage** : ni changement
de bulle, ni retour aux messages passés — seuls les mots en cours de parole sont affichés et
chaque question part **sans aucun historique** ; rien d'une conversation précédente
n'est mémorisé d'une ouverture à l'autre (table Room et cache vidés à l'ouverture).

> Ce dépôt (`BaptisteFaisy/vision`) porte le produit : un seul commit de départ, daté du
> 26/10/2026. Chaque affirmation ici est relue dans le code de cet arbre ; ce qui vient d'une mesure appareil est daté et sourcé
> (`docs/PERF.md`). La scène iSee est **branchée** (l'accueil l'affiche, pas seulement les
> tests) — voir `docs/ARCHITECTURE.md` et `docs/OPTIMISATIONS.md` (ce qui a été refait, et
> mesuré).

## Ce qu'il y a dedans

| Module ou dossier | Rôle |
|---|---|
| `:core` | Bibliothèque Android (`dev.sam3vision.core`) : modèles, clients HTTP (LLM `chat/completions`, SAM `/segment` et `/release`, vidéo `/video`), **WebSocket `/live/ws`** pour le direct, TTS/STT, masques, distances, gestes, cadence du direct, réglages chiffrés, texte, design iSee (`core/…/iseedesign`) |
| `:app` | L'APK (`dev.sam3vision.app`) : Compose + CameraX, accueil iSee (`app/isee`), Capture, Analyse, Chat, Direct, Réglages, mode tap vidéo (`app/tap`), lunettes (`app/glasses`), banc d'essai lunettes (`app/glasses/bench`, ouvert depuis l'écran Lunettes), purge des captures (`app/cleanup`) |
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

Les concepts suivis sont **six** par défaut — `person`, `door`, `bicycle`, `car`, `truck`,
`electric scooter` —, et `LivePrompts.MAX = 6` borne toute liste saisie dans le champ
« Suivre » (`core/live/LivePrompts.kt:27,34`) : le backend tronque de toute façon à
`PROMPT_LIMIT = 6`.

## Taille de l'arbre (mesurée le 2026-10-27)

Le module `:glasses-spike` a été supprimé ce jour-là ; les chiffres ci-dessous l'**excluent**.
Le banc lunettes est un paquet de `:app` (`app/…/glasses/bench/`) et compte donc dans `:app`.

| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` | **472** | `find core app -name '*.kt' -not -path '*/build/*' \| wc -l` |
| Lignes de production (`*/src/main`) | **28 706** | `find core app -path '*/src/main/*' -name '*.kt' \| xargs cat \| wc -l` |
| dont `:core` / `:app` | **105 f. / 11 121 l.** · **164 f. / 17 585 l.** (dont le banc : **17 f. / 1 824 l.**) | idem, par module |
| `@Test` | **1 380** = **1 373 JVM** (`:core` 907, `:app` 466, dont banc 86) + **7 instrumentés** (`:app` 6 + 1 pour le banc) | `find core app -name '*.kt' -not -path '*/build/*' \| xargs grep -h '@Test' \| wc -l` |
| `@Preview` Compose | **11** | idem avec `@Preview` |
| Plus gros fichier | `app/src/main/java/dev/sam3vision/app/LiveViewModel.kt` — **492** lignes | `find core app -path '*/src/main/*' -name '*.kt' \| xargs wc -l \| sort -rn \| head` |

Les fichiers du banc étaient encore **non suivis par git** à l'heure de ce relevé (copie en
cours d'intégration) : c'est pourquoi le comptage se fait sur le disque et non par
`git ls-files`. Le relevé date de 11 h 10 et l'arbre reçoit du code en parallèle pendant la
fusion : ces totaux bougent de quelques dizaines de lignes d'une heure à l'autre, les
proportions non.

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
./gradlew :core:testDebugUnitTest :app:assembleDebug      # tests JVM + APK debug (banc compris)
                                                          # le banc lunettes n'a plus de commande à lui
python3 tools/check-kotlin-limits.py                      # gardes de style
bash scripts/build.sh                                     # assembleDebug --offline + contrôle de l'APK
```

| Chaîne d'outils | Valeur (lue dans le dépôt) |
|---|---|
| Gradle | 8.14.1 (`gradle/wrapper/gradle-wrapper.properties:3`) |
| AGP | 8.11.1 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 (`gradle/libs.versions.toml`) |
| compileSdk / targetSdk | 36 partout |
| minSdk | **29** pour `:app` (banc lunettes compris), 26 pour `:core` (bibliothèque) |
| Java | bytecode et `-jvm-target` **17** ; le JDK n'est **pas** épinglé dans le dépôt (`JAVA_HOME` du poste, jamais `org.gradle.java.home`) |

Sur cette machine (r630) : `export JAVA_HOME=~/toolchains/jdk17 ANDROID_HOME=$HOME/android-sdk`,
puis `./gradlew --offline`. L'APK cible des téléphones **Android 10 et plus** (`minSdk 29`) :
le SDK lunettes Meta l'exige. Dépendances : coroutines, OkHttp, AndroidX (Compose, CameraX,
Room, WorkManager, security-crypto), JUnit 4, plus Guava, jump3r (MP3 pur Java) et
`mwdat-*` pour les lunettes.

Comptage des tests (fait sur cet arbre le 2026-10-27 par `grep -h '@Test'`, commandes
ci-dessus) : **1 380** annotations, dont **1 373 JVM** (`:core` 907, `:app` 466) et **7
instrumentés** (`app/src/androidTest` 6, `app/src/androidTestDebug` 1 — `MockGlassesTest`, qui
appartient au banc). Le nombre de tests réellement **exécutés** n'est pas mesuré ici ;
`docs/TESTING.md` donne la recette.

## CI

Quatre workflows (`.github/workflows/`) : `ci.yml` — **quatre** jobs, un par VM (le job
`spike` a disparu avec le module le 2026-10-27), chacun borné à **5 minutes** : `ci` (gardes +
tests JVM de `:core` et `:app`), `apk` (debug), `apk-release`, `lint` ; `ci-budget.yml` (refuse
un job qui déclarerait plus de 5 minutes) ; `couche0.yml` (toute PR déclare son `## Scope` en
chemins, et refuse les clés en clair) ; `stale-base-guard.yml` (retard sur la base,
informatif). Tout tourne sur un runner **auto-hébergé**
(`runs-on: [self-hosted, linux, x64, vision]`, `ci.yml:28`) avec cache Gradle via
`gradle/actions/setup-gradle`, un checkout en `fetch-depth: 1` et une annulation des runs
précédents de la même branche (`concurrency`, `ci.yml:16-17`). Une PR qui ne touche que de la
doc ou de la config ne lance aucun Gradle (l'étape de périmètre de `ci` le décide, `ci.yml:70`) :
son verdict tombe en quelques secondes.

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
