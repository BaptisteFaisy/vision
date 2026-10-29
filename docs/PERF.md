# PERF — les chiffres qu'on peut tenir

Règle de ce fichier : **aucun chiffre sans provenance**. Trois catégories, et rien d'autre :

1. **mesuré dans cet arbre** le 26/10 après 19 h (commande reproductible donnée) ;
2. **lu dans le code** (constante nommée, fichier + ligne cités) — c'est une valeur de réglage,
   pas une performance observée ;
3. **non mesuré ici** : tout ce qui demande le téléphone, le réseau ou le backend GPU.

Aucun relevé d'appareil n'est repris dans ce fichier : la seule machine sur laquelle les
chiffres ci-dessous ont été obtenus est **r630** (serveur Debian, JDK 17), pas un téléphone.
Un chiffre de téléphone lu dans un commentaire de code reste un commentaire, pas une mesure de
cet arbre.

## 1. Mesuré dans cet arbre (26/10 après 19 h ; recompté les 27, 28 puis 29/10/2026 au soir —
le relevé se rejoue avec `python3 tools/count-tree.py`, jamais à la main)

### 1.1 Taille de l'arbre

| Grandeur | Valeur | Commande |
|---|---|---|
| Fichiers Kotlin | **913** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \| wc -l` |
| Lignes Kotlin | **98 370** | même `find`, `-exec cat {} + \| wc -l` |
| Fichiers de test JVM | **408** (249 `:core` + 159 `:app`) | `find core/src/test app/src/test -name '*.kt' \| wc -l` |
| `@Test` JVM `:core` | **1 835** | `grep -rho '@Test' core/src/test --include='*.kt' \| wc -l` |
| `@Test` JVM `:app` | **1 049** | idem sur `app/src/test` (dont **95** dans `glasses/bench`) |
| **Total JVM** | **2 884** | somme des deux lignes |
| Tests instrumentés | **9** (8 `androidTest` + 1 `androidTestDebug` banc) | idem sur `src/androidTest` et `src/androidTestDebug` |
| Garde de taille | **1 008** fichiers `.kt`/`.kts`/`.py` inspectés, **0** dépassement | `python3 tools/check-kotlin-limits.py` |
| Gardes de scripts | **101** assertions, 101 ok | `bash scripts/test_scripts.sh` |
| Tests de l'outillage | **118** tests, OK | `python3 -m unittest tools/test_check_kotlin_limits.py tools/test_check_budget_derive.py tools/test_check_voice_nav_doc.py tools/test_check_apk_keys.py tools/test_export_caregiver_orb.py` + `python3 .github/fireworks/test_conflict_agent.py` + `python3 .github/couche0/test_check_couche0.py` + `python3 .github/ci-budget/test_check_timeouts.py` (28 + 18 + 12 + 8 + 6 + 16 + 19 + 11) |
| Tests backend | **254** tests, OK | `python3 server/scripts/test_units.py` (numpy, Pillow, fastapi) |
| Budget CI | 6 workflows, 0 erreur | `python3 .github/ci-budget/check_timeouts.py` |

### 1.2 Tests réellement exécutés par Gradle

```bash
export JAVA_HOME=<un JDK 17 qui a javac>
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline --rerun-tasks
# rapports : <module>/build/test-results/testDebugUnitTest/*.xml
```

Relevé du 29/10, sur r630 (`:core:testDebugUnitTest :app:testDebugUnitTest`) : **`:core`
1 835 / 0 échec / 0 ignoré**, **`:app` 1 049 / 0 / 0** — **2 884 exécutés**, conformes au tableau
ci-dessus. (Historique : le 26/10, `:core` 663, `:app` 305 et le module lunettes 36 — 1 004 au
total, avant la fusion du banc dans `:app`, non comparable.)
Les 9 tests instrumentés ne sont **pas** dans ces chiffres (aucun appareil n'a été utilisé).

### 1.3 Mesures JVM du chemin chaud (imprimées par les tests)

Deux tests de `:core` mesurent et **impriment** leurs chiffres dans le rapport JUnit
(`<system-out>`) ; ils n'échouent que sur des seuils très larges, à plus de dix fois le mesuré :

- `core/src/test/…/mask/MaskColorizePerfTest.kt` — décodage `M1`, coloriage par LUT,
  allocation par pixel (sonde étalonnée sur une allocation volontaire).
- `core/src/test/…/live/LiveFrameBudgetTest.kt` — budget CPU par frame hors réseau et hors
  encodage JPEG (`capture → JPEG → base64 → upload`).

Valeurs relevées le 26/10 après 19 h (JDK 17, JVM de bureau, **pas** un téléphone) :

| Mesure | Valeur | Source |
|---|---|---|
| Décodage masque `M1` 640×360 | **0,345 ms/masque** (344 963 ns), payload base64 304 o (corps compressé) | `MaskColorizePerfTest` |
| Allocation du décodage `M1` 640×360 | **260 072 o/masque** (niveaux 230 400 + corps) | idem |
| Coloriage LUT 640×360 (230 400 px) | **0,203 ms/masque**, **0,88 ns/pixel**, ≈ 4 321 Mo/s | idem |
| Coloriage LUT 512×288 | **0,130 ms/masque**, **0,88 ns/pixel**, ≈ 4 329 Mo/s | idem |
| Allocation du coloriage | **0 octet** pour 100 coloriages de 230 400 px | idem |
| base64 encode (6 Ko, tampon réutilisé) | **17,5 µs/op** | `LiveFrameBudgetTest` |
| base64 aller-retour (6 Ko) | **26,5 µs/op** | idem |
| `ByteArray→String` UTF-8 (8 Ko) | **125,3 µs/op** (contre **3,2 µs** en ISO-8859-1) | idem |
| `LiveStats` record + moyenne + p95 | **4,5 µs/op** | idem |
| `FrameRate` record + cadence | **0,95 µs/op** | idem |
| `TimingsText` ligne compacte | **6,5 µs/op** | idem |
| labels `map` + `distinct` (12) | **9,6 µs/op** (contre **4,9 µs** avec un set réutilisé) | idem |
| distances `associate` + 12 lectures | **14,3 µs/op** (contre **18,0 µs** pour le balayage direct, rejeté) | idem |
| `LatestFrameSlot` offer + take | **1,6 µs/op** | idem |

Ce que ces chiffres disent : le coloriage n'alloue rien par pixel et tourne sous la
milliseconde par masque ; côté frame, tout ce qui n'est pas le JPEG lui-même se compte en
microsecondes. Ce qu'ils **ne** disent pas : le coût de `Bitmap.compress`, de la radio ou du
rendu à l'écran — **non mesuré ici**, cela demande le téléphone.

## 2. Constantes lues dans le code (ce que l'app s'autorise)

| Constante | Valeur | Fichier:ligne |
|---|---|---|
| Délai HTTP générique | **60 s** | `core/net/Http.kt:28` (`DEFAULT_TIMEOUT_SECONDS`) |
| Délai d'une frame (compatibilité) | **10 s** | `core/net/HttpLiveClient.kt:14` (`LIVE_TIMEOUT_SECONDS`) |
| Délai du réveil `/health` | **180 s** | `core/net/HttpLiveClient.kt:22` (`WAKE_TIMEOUT_SECONDS`) |
| Délai d'extinction `/release` | **45 s** | `core/net/SamLifecycle.kt:10` (`RELEASE_TIMEOUT_SECONDS`) |
| Délai d'envoi vidéo (mode tap) | **120 s** | `core/net/HttpSam31VideoClient.kt:71` (`UPLOAD_TIMEOUT_SECONDS`) |
| Délai du tracker | **15 s** | `core/net/TrackClient.kt:11` (`TRACK_TIMEOUT_SECONDS`) |
| Cadence du tracker | plancher **33 ms** (30 fps), plafond **500 ms**, **1** frame en vol | `core/live/TrackRunner.kt:232-238` (`TARGET_FPS`, `MIN_INTERVAL_MS`, `MAX_INTERVAL_MS`), créneau unique (`submit`, `:120`) |
| Client du direct | connexion 3 s, écriture 5 s, lecture 60 s, `callTimeout` 0 | `core/net/Http.kt:85-88,110-115` |
| Pool du direct | 8 connexions inactives, 5 min | `core/net/Http.kt:43` |
| Cadence Direct (plancher / cible / lent) | **350 / 500 / 900 ms** | `core/live/LiveSession.kt:34` (`Cadence.Direct`) |
| Cadence iSee | pas de plancher, **1 créneau**, budget **150 ms**, hystérésis **3** mesures, réouverture sous **0,5 ×** budget | `core/live/LiveSession.kt:43-50,195,198` |
| Fenêtre de réveil du Direct | **60 s** | `app/live/LiveWakeGuard.kt` (`WAKE_TIMEOUT_MS`) |
| Côté long de la frame iSee | **320 px** (qualité 55) | `app/isee/IseeCameraPane.kt:43,46` |
| Côté long de la frame du Direct | **512 px** | `app/LiveScreen.kt:55` (`LIVE_EDGE`) |
| `work_edge` par défaut du direct | **512 px** | `core/live/LiveSession.kt:211` (`DEFAULT_WORK_EDGE`) |
| Objets affichés par défaut | **8** (bornes 1–12) | `core/model/ObjectLimits.kt:16-18` |
| Concepts suivis par défaut | **6** (`person`, `door`, `bicycle`, `car`, `truck`, `electric scooter`), max 6, 32 caractères | `core/live/LivePrompts.kt:27,34,37` |
| Budget de tokens du LLM | **1200** (photo/chat, le défaut raisonne avant de répondre), **200** (narration de frame), réponse de direct tronquée à **400** caractères, température **0,2** | `core/net/HttpLlmClient.kt:21-24` |
| Modèle de narration | **même** modèle Meta que les questions : `meta-models/muse-spark-open-1026` (Baseten, raisonnement coupé), clé dédiée (`liveLlmApiKey`) ; sans clé, le Direct se tait | `core/settings/Settings.kt` (`DEFAULT_LIVE_LLM_MODEL`), `app/LlmFactory.kt` (`liveNarration`) |
| Historique envoyé au LLM | **20** tours | `core/usecase/UseCases.kt:81` (`MAX_HISTORY_TURNS`) |
| Champ de vision de repli | **67°**, bornes **15–130°** | `core/distance/LensSpec.kt:43,46,49` |
| Lissage des distances | **0,35** (suivi), **0,85** (saut) | `core/distance/DistanceSmoother.kt:19-20` |
| Appui long | **300 ms**, tolérance **24 px** | `core/gesture/LongPressTrigger.kt:102,105` |
| Durée minimale de dictée | **400 ms** | `core/usecase/DictationUseCase.kt` (`MIN_DURATION_MS`) |
| Purge des captures | toutes les **1 h** | `app/cleanup/CleanupConfig.kt:25` (`PERIOD_HOURS`) |
| Tronquage d'un résumé vidéo | **600** caractères | `core/net/HttpSam31VideoClient.kt:15` |

Ces valeurs disent **ce que l'app s'autorise**, pas ce qu'elle obtient.

## 3. Non mesuré ici

Tout ce qui suit a pu être vu dans un commentaire, une note ou un autre dépôt ; **aucun n'est
mesurable depuis cet arbre**, donc aucun n'est repris comme chiffre :

- **Débit d'images** (les `X ips` du panneau, tout `fps`) : dépend du réseau, du conteneur GPU
  et de la scène. **Non mesuré ici.**
- **Latences** : aller-retour d'une frame, durée serveur (`elapsed_ms`), p50/p95, et tout
  « X ms » de bout en bout. **Non mesuré ici.**
- **Réveil du conteneur Modal** et délai de la première frame. **Non mesuré ici.**
- **DNS + TCP + TLS** d'un client neuf. **Non mesuré ici.**
- **Coût de la capture et de l'encodage JPEG** sur le téléphone (`Bitmap.compress`), coût
  radio de l'upload, coût de décodage et de rendu Compose à l'écran. **Non mesuré ici.**
- **Gains d'un encodage de masque `bits` + LUT côté serveur** : le client sait lire `bits`
  (`core/model/Models.kt:65`), mais le backend déployé peut servir `png` — le gain réel
  dépend de ce qu'il envoie (`mask_encoding`). **Non mesuré ici.**
- **Comparaison WebSocket / POST** : le code ne garde qu'un transport (WS). Aucune mesure des
  deux dans cet arbre. **Non mesuré ici.**

Les commentaires de code citent parfois des relevés faits sur un téléphone (réveil du
conteneur, durée serveur d'une frame, coût d'un client neuf, latence de deux créneaux en vol).
Ils documentent **pourquoi** une constante a été choisie ; ils ne sont pas des mesures de cet
arbre et ne sont pas recopiés ici.

## 4. Mesures du backend (`server/docs/`, relevés antérieurs)

Le backend est dans cet arbre depuis le 27/10 (`server/`) : ses documents de latence aussi.
Ce sont des **relevés antérieurs, hors de cet arbre** — cités sans date ni sha (règle
`AGENTS.md` §0), jamais rejoués ici :

| Document | Contenu |
|---|---|
| `server/docs/LIVE-FAST.md` | Source déployée (`live_fast/`, `sam3-eu-a100`), leviers et télémétrie par étage |
| `server/docs/LIVE-PERF.md` | Campagnes `/live` : froid, A100/H200, Europe, WebSocket |
| `server/docs/TRACK-PERF.md` | Campagne tracker : sessions, téléphone, budgets 200/100 ms |
| `server/docs/PERF.md` | Latences `/segment` sur A10G |
| `server/docs/LATENCE-PROD.md` | Note de déploiement : leviers, contrat client, vérification |

Rien de tout cela n'est une mesure de cet arbre : pour un chiffre tenu, voir les §1–2
ci-dessus.
