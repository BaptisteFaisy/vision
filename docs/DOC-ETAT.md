# État de la documentation — vérifié le 26/10, repris le 27/10, recompté le 28/10

Une ligne par document de ce dépôt. **Verdict** : `à jour` (ce qu'il dit se lit dans le code
de cet arbre), `périmée` (décrivait juste, le code a bougé — corrigé), `inexacte` (contient
une affirmation fausse pour cet arbre — corrigée ou encadrée), `relevé antérieur` (mesures
d'avant le 26/10 19 h, citées sans date ni sha), `historique` (trace de conception, ne décrit
plus l'arbre — bandeau ajouté).

| Document | Verdict | Ce qui a été vérifié / corrigé ici |
|---|---|---|
| `docs/MESURES-SAM3-FEUX.md` | **nouveau** (29/10), **relevé hors arbre** | Deux mesures : rappel SAM 3.1 (87,5 % avec `pedestrian traffic light`, 12,5 % avec un prompt d'état) et **`PedLightGate` exécutée sur de vrais crops SAM** — 1 vert sur 6 signaux verts, rejet par `ELONGATION_MIN`, et l'ordre des élongations est inversé entre vert réel (≤ 1,11) et disque voiture (≥ 1,30) : le seuil ne peut pas être réglé, l'hypothèse « la boîte SAM serre la lampe » tombe. Harnais contrôlé 9/9 sur `feuxfr/`. Images non versionnées, attribution au §8 |
| `docs/TRAVERSEE.md` | **nouveau** (28/10) | Bilan traversée : recherche 10 angles, PR #163-167 + #174, dépendances in-app vs `/live`, preuves JVM, 4 restes bloquants listés |
| `README.md` | **réécrit** (28/10) | Problème, solution, 4 blocs, stack complète ; chiffres remesurés (733 `.kt`, 43 457 lignes de prod, 2 202 `@Test` JVM + 10 instrumentés) ; backend dans `server/`, gestes sans double tap |
| `docs/ARCHITECTURE.md` | **recompté** (28/10, §1 + §8) | Comptes §1 refaits, §8 réécrit (backend dans `server/`, `LIVE-SPEC` existe, tracker et `/video` câblés) ; §2-7 : valeurs vraies, lignes non recalées |
| `docs/PERF.md` | **recompté** (28/10) | §1.1 refait (733 / 76 990 / 2 202 / 10), §2 ancres recalées (dont `work_edge` 512), §4 réécrit (mesures backend dans `server/docs/`) |
| `docs/DOC-ETAT.md` | **nouveau** (26/10) | Ce tableau |
| `docs/TESTING.md` | **recompté** (28/10) | §1 (1 418 + 784 = 2 202, 9 + 1 instrumentés), §2 (117 assertions, 58 tests, 8 workflows), §5 (4 fichiers), §6 régénéré par paquet |
| `docs/MASQUES-DEBUG.md` | **inexacte → corrigée** | Cadence iSee : « 2 requêtes en vol, budget 200 ms » alors que `Cadence.Fast` est à **1 créneau / 150 ms** ; « cadre 448 px » alors que `ISEE_FRAME_EDGE = 320` |
| `docs/CI-QUEUE.md` | **périmée → recorrigée** (28/10, découpe v9) | **Un** job dans `ci.yml` (`ci`) + natif (`apk`, `apk-release`, `lint`) dans `ci-native.yml` hors PR, runners **auto-hébergés** `[self-hosted, linux, x64, vision]` ; `couche0` (scope + forbidden, juge pris sur `master`), `stale-base-guard` informatif, budget 5 min par job, `master` protégé (PR obligatoire, 3 checks requis, file de merge native) |
| `docs/NETTOYAGE.md` | **à jour** | 12 fichiers, 599 lignes, purge horaire (`PERIOD_HOURS = 1`), Room + WorkManager — vérifié à l'identique le 26/10 |
| `docs/DISTANCES.md` | **à jour**, une correction | Formule et constantes justes (lissage 0,35 / 0,85 ; `KnownSizes` ; `LensSpec` 67° borné 15–130°). Concepts par défaut du direct : **6** depuis le 28/10 (le doc reste générique, sans chiffre en dur) |
| `docs/ELEVENLABS-TTS.md` | **à jour** | En-tête `xi-api-key`, base `https://api.elevenlabs.io/v1`, modèle `eleven_v4_turbo`, voix premade vs bibliothèque (402) |
| `docs/META-ASR.md` | **à jour** | Chemin `{base}/asr/transcribe`, `Authorization: Bearer`, modèle `muse-voice-transcribe-1.0` |
| `docs/MODE-TAP.md` | **recalé** (28/10) | Chemin Réglages (« Other screens » → « Classic home », `SettingsScreen.kt:407-416`), `Route.Tap` (`AppNav.kt:37,68`), cites `/video` recalées (`:69-72`, `:77`, `:89-95`, `Http.kt:248-251`, `TapSession.kt:176-191`) |
| `docs/MODE-US.md` | **à jour**, chiffres à lire comme historique | Le choix de région n'allume rien et n'élit que l'URL (`SamRegion.kt`, `SamEndpoint.kt`). Les durées eu/us (472–503 ms / 1107–1115 ms) sont des relevés du 26/10 **hors de ce dépôt**, antérieurs au WebSocket |
| `docs/META-GLASSES.md` | **à jour** | Placeholders `mwdat_*` injectés par Gradle (pas des « 0 » en dur), minSdk 29, SDK en developer preview |
| `docs/glasses-dat-spike.md` | **à jour** (relevé d'API, daté) | Reste vrai comme relevé d'API, mais **le module n'existe plus** : `settings.gradle.kts` n'inclut que `:core` et `:app`, et le dossier de l'ancien module a disparu du disque. Le doc dit lui-même qu'il ne décrit pas `:app` |
| la page CI du banc sous `docs/` (`*-ci.md`, nom d'origine conservé pour les liens) | **périmée → recorrigée** (28/10) | Banc dans `:app`, tests JVM au job `ci` (`ci.yml:167-171`), PR = 1 job (découpe v9) ; runners auto-hébergés `[self-hosted, linux, x64, vision]` + `gradle/actions/setup-gradle@v4`, durée de l'ancien job citée comme relevé daté |
| `docs/VOIX-ANGLAIS.md` | **à jour** | `SpeechLanguage.DEFAULT = En` ; l'interface reste en français |
| `docs/GUIDAGE-TOUT-DROIT.md` | **nouveau** (27/10) | Consigne de marche iSee : couloir 0,34–0,66, portée 4 m, danger prioritaire sur les 4 s, zéro appel LLM — ancres `Guidance.kt`, `GuidanceAnnouncer.kt`, `LiveProbe.phraseOf` |
| `docs/TEST-LONGPRESS-DICTEE.md` | **à jour** (recette datée) | Seuil d'appui long 300 ms et tolérance 24 px lus dans `LongPressTrigger.kt` ; la recette elle-même n'a pas été rejouée (le doc le dit) |
| `docs/L13-FUSION.md` | **périmée → corrigée** (27/10) | Contrat de fusion de la lane L13. Tableau d'état recalé : les six artefacts **existent** (A et B non branchés, D inerte) — ce qui manque, c'est le **câblage** A→B→C→D, pas le code. La couture D (`setMotion`) consomme encore `core.local.MotionSource` |
| `docs/CLES-DU-BUILD.md` | **à jour** (relu 28/10) | Clés `local.properties` → `BuildConfig` relues dans `app/build.gradle.kts`, `SettingsSeed.kt` présent, étape « Clés du build » en `ci.yml:114` |
| `docs/GLASSES-DECISION.md` | **périmée → corrigée** (27/10) | « Deux arbres côte à côte » passé au passé (module supprimé du disque), `frame-64.jpg` marqué résolu (JPEG embarqué en base64), ancres `AppNav`/manifeste/`build.gradle.kts` recalées |
| `docs/GLASSES-ETAT.md` | **périmée → recorrigée** (27/10, chiffres 28/10 dans TESTING) | Banc : 15 fichiers / 1 640 lignes, 10 fichiers / **95** `@Test` (28/10, voir `docs/TESTING.md` §1/§6) ; garde de taille OK |
| `docs/KEYFRAME-SCHEDULER.md` | **à jour** (relu 27/10) | 5 fichiers `keyframe/`, constantes (0,12 / 0,35 / 12 s / 3 req/s), `FrameGate` réutilisé, simulateur `KeyframeScene`/`KeyframeSim` — relus à l'identique |
| `docs/LATENCE-BUDGET.md` | **périmée → corrigée** (27/10) | `scripts/budget_latence.py --check-doc` OK (6 tableaux) et `--selftest` 14/14 ; deux ancres `LiveSession.kt:33` → `:34` |
| `docs/LIVE-SPEC.md` | **recorrigée** (28/10, §5 le 29/10) | En-tête + §8 (serveur dans `server/`), `work_edge` 512 (client) / 768 (serveur), `PROMPT_LIMIT = 6`, `CHANGELOG.md:623` ; §5 juste (12 concepts : + `door`, `barrier`) ; §1-4, §6 : valeurs vraies, lignes non recalées |
| `docs/LUNETTES-LANCEMENT.md` | **périmée → recorrigée** (28/10) | `test-mock-sam.sh` rejoué le 28/10 (26 ok / 0 KO) ; lien `ref/` mort retiré le 27/10 (dossier absent du dépôt) |
| `docs/MASK-DECODE.md` | **à jour** (relu 27/10) | Codecs `rle`/`poly`, knobs `MASK_FORMAT`/`MASK_EDGE`, `MASK_MAX_PIXELS`, 6 suites de tests citées — relus à l'identique |
| `docs/MICRO-VERROUILLE.md` | **périmée → corrigée** (27/10) | `LlmContractTest` 13 → **15** ; tout le reste (service, `MAX_RECORD_MS`, receiver debug, recette) relu à l'identique |
| `docs/OPTIMISATIONS.md` | **inexacte → corrigée** (27/10) | Jobs CI 5 → **4** (contredisait le §11), note de datation ajoutée : les chiffres « Après » sont ceux de la fin de campagne (26/10), pas de cet arbre |
| `docs/T8-PREDICTION.md` | **périmée → corrigée** (27/10) | §4 réécrit : le module **est** branché derrière `Settings.boxPredictionEnabled` (`LiveViewModel.kt:94,235,368,373`), §1/§6 recalés |
| `docs/TRACK-PERF.md` | **à jour** (relu 27/10) | `TrackClient`/`TrackRunner`, constantes de cadence, `FrameGate` — relus à l'identique ; chiffres serveur marqués « relevé antérieur » |
| `docs/VOIX-POSITIONS.md` | **périmée → corrigée** (27/10) | `LiveProbe.phraseOf` → `:418-423`, `Settings.kt:28` → `:36`, `:148` → `:184` ; seuils, machine d'annonce et 5 suites de tests relus à l'identique |
| `docs/DEPTH.md` | **cohérent avec l'arbre** (28/10) | Paquets `core/depth` + `app/depth`, deps ONNX/LiteRT au toml, release `depth-models-v1` ; chiffres Redmi repris du doc (non rejoués ici) |
| `docs/VOICE_NAV_PROXY.md` | **à jour** (28/10) | URL = fumée du workflow, volume et rollback vérifiés ; câblage app relu (`routeBaseUrlDefault`, `ROUTE_BASE_URL`, `routeBaseUrl`, `HttpRouteClient`) |
| `docs/RECETTE-NAVIGATION-VOCALE.md` | **cohérent** (28/10) | Cites vérifiées (`handleDictated`, `NavigationHook`, `nav_confirm_destination`, sha ancêtre de `master`) ; non rejoué sur appareil (le doc le dit) |
| `docs/MESURES-u11c.txt` | **généré** (28/10) | Fixtures dorées : `gen_mask_golden.py --check` OK (ne pas éditer à la main) |
| `docs/chat-voix.png` | ressource | Capture d'écran, pas une affirmation |
| `server/README.md` | **à jour** (28/10) | Inventaire des scripts vérifié ; périmètre legacy + pointeur vers le backend déployé (`live_fast/`) |
| `server/backend/README.md` | **complété** (28/10) | Exact pour `modal_app.py` (4 routes, secret `sam3-llm`) ; pointeur live/track/valhalla ajouté |
| `server/backend/NOTES.md` | **relevé antérieur** (28/10) | Vérifications API `transformers` ; datation purgée (§0) |
| `server/docs/SPEC.md` | **à jour comme contrat legacy** (28/10) | 4 routes = `modal_app.py`, bornes 16/64/24 et seuils relus dans `segmentation.py` |
| `server/docs/ARCHITECTURE.md` | **à jour comme archi legacy** (28/10) | Expo + `sam3-vision` ; pointeur vers le déployé (`live_fast/`) ajouté |
| `server/docs/AUDIT.md` | **relevé antérieur** (28/10) | Audit `sam3-vision-app` ; datation purgée (§0) |
| `server/docs/KOTLIN-SPEC.md` | **historique** (28/10) | Spec de transition Expo → Kotlin : ne décrit plus l'arbre — bandeau ajouté |
| `server/docs/LATENCE-PROD.md` | **recâblé** (28/10) | Commande qui fait foi = `scripts/deploy-live-fast.sh` ; table des leviers conservée |
| `server/docs/LIVE-FAST.md` | **à jour** (28/10) | Leviers = `deploy-live-fast.sh`, télémétrie ; datation purgée, § workflow corrigé (déploiement CI actif) |
| `server/docs/LIVE-PERF.md` | **relevé antérieur** (28/10) | Campagnes `/live` (froid, A100/H200, Europe, WebSocket) ; datation purgée (§0) |
| `server/docs/LIVE-SPEC.md` | **historique** (28/10) | Phase `sam3-live` : app et concepts d'alors — bandeau ajouté |
| `server/docs/MODAL-CLOSED.md` | **relevé antérieur** (28/10) | État de fermeture Modal ; datation purgée (§0) |
| `server/docs/PERF.md` | **relevé antérieur** (28/10) | Latences `/segment` sur A10G ; datation purgée (§0) |
| `server/docs/TRACK-PERF.md` | **relevé antérieur** (28/10) | Campagne tracker (sessions, téléphone, budgets) ; datation purgée (§0) |
| `server/scripts/README.md` | **complété** (28/10) | Chemin `test-client.mjs` corrigé, section direct/mesures ajoutée |
| `server/scripts/NOTES.md` | **archivé** (ère legacy) | Notes de build Expo ; périmètre inchangé |
| `server/legacy-expo-app/NOTES.md` | **archivé** (28/10) | Notes de l'app Expo ; datation purgée (§0) |
| `suivi-parent/README.md` | **à jour** (28/10) | `caregiverAssets`, `_store` (Upstash/mémoire, zéro dép), tests node 10/10 vérifiés |
| `scripts/README.md` | **recompté** (28/10) | 117 assertions `test_scripts.sh` |
| `app/DEPS.txt` | **à jour** (28/10) | Entrées recoupées au build (`room`, `work`, `camera-video`) |
| `.github/fireworks/README.md` | **à jour** (28/10) | Contrat = usage `pr-auto-merge`, défaut modèle relu dans `conflict_agent.py` |
| `NOTES.md` | **recompté** (28/10) | Versions (lignes toml/build recalées, + guava/ONNX/LiteRT), taille, ancres du direct, `LivePrompts` 6 concepts, `LivePipeline` non branchée |
| `CHANGELOG.md` | **à jour** | Le journal des faits livrés depuis le 26/10 ; `docs/PERF.md` reste le seul endroit où chercher un chiffre tenu |
| `docs/NAVIGATION.md` | **à jour** (relu 28/10) | Contrat technique du handoff Maps : 9 motifs GO, 4 formats d'URI, 4 replis, `navigationGuidanceEnabled = true` — relus à l'identique ; pointe vers `docs/NAVIGATION-VOCALE.md` pour le fonctionnel |
| `docs/NAVIGATION-VOCALE.md` | **nouveau** (28/10) | Spécification fonctionnelle navigation vocale : parcours MVP, 7 annonces, 9 cas limites, recette 10 points, catalogue 7 chaînes (3 en place, 4 à venir), géocodage + ingress Valhalla cadrés hors MVP ; porte `tools/check-voice-nav-doc.py` |

## Trois affirmations qui circulaient, et ce qu'il en est dans cet arbre

- **« pas de backend ici »** — **faux depuis le 27/10**. Il n'y a pas de `backend/` à la
  racine, mais le backend est dans **`server/backend/`** (+ `server/docs/`) : direct déployé
  `live_fast/` (app `sam3-eu-a100`), Valhalla CPU, tracker, legacy photo. Seul `POST /video`
  reste sans implémentation serveur (`docs/MODE-TAP.md`).
- **« la carte caméra qui se dissout en damier dans la bulle »** — **vrai depuis la fusion
  `opt/isee-branche`**. La dissolution est écrite et testée (`core/…/iseedesign/Dissolve.kt`,
  `app/…/isee/IseeDissolve.kt`, `app/…/isee/IseeScenePane.kt`), et l'accueil est
  désormais `IseeHome` → `IseeScenePane` : `IseeChatPane` et `IseeCameraWindow` sont
  supprimés. Reste à confirmer visuellement sur appareil (jamais regardé sur écran).
- **« 8 objets par défaut »** — **vrai pour les objets affichés** (`ObjectLimits.DEFAULT_ON_SCREEN = 8`,
  borné 1–12), **faux pour les concepts suivis** : `LivePrompts.Default` en contient **6**
  (`person`, `door`, `bicycle`, `car`, `truck`, `electric scooter`) et `LivePrompts.MAX = 6`.

## Hors périmètre de cette passe (à reprendre)

- `SECURITY.md` : corrigé le 27/10 (« aucune clé **versionnée** », lecture des secrets par
  l'étape « Clés du build » de `ci.yml`).
- Ancres `fichier:ligne` des §2-7 de `docs/ARCHITECTURE.md` et des §1-4, §6 de
  `docs/LIVE-SPEC.md` : valeurs vraies, lignes non recalées le 28/10 (passe de recalibrage
  à prévoir, comme celle du 27/10).
- Commentaires de code périmés : `LivePrompts.kt` et `LiveProbeText.kt` corrigés le 27/10 ;
  reste le cas `LiveState.kt` signalé sans localisation précise — non retrouvé.
