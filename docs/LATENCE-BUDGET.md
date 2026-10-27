# LATENCE-BUDGET — budget de latence « capteur → pixels » (mode Direct)

Cible : **latence perçue < 100 ms** en mode Direct (frame caméra → masque affiché). Ce document est
le **budget** : chaque étage du chemin, son plancher physique, ce que les vagues précédentes ont déjà
retiré, ce qui est atteignable, et ce qui ne le sera jamais en cloud.

Règles de lecture :

- chaque chiffre porte son étiquette — **mesuré** (banc ou terrain, source citée), **calculé**
  (dérivé de mesures, formule donnée), **estimé** (ordre de grandeur jamais instrumenté ici),
  **hypothèse** (valeur de travail, à remplacer par une mesure) ;
- la grandeur de référence est la **latence perçue** (§ 1) : un A/R serveur de 41 ms ne dit rien de
  ce que l'utilisateur voit ;
- les **6 blocs chiffrés** de ce document sont **générés** par `scripts/budget_latence.py`, la source
  unique des chiffres. `--check-doc` échoue si ce document et le script divergent, `--selftest`
  vérifie l'arithmétique, `--verdict` imprime le verdict sur les 100 ms.

Provenance : les valeurs marquées `uNN` viennent des notes de la campagne (vague 1), **hors de ce
dépôt** ; aucune n'est présentée comme mesurée **ici**. Ce que cet arbre-ci permet de vérifier est
listé en annexe B, et `docs/PERF.md` reste la référence de ce qui est mesuré dans cet arbre.

## 1. Définitions

- **Latence perçue** = capteur → pixels = `CAPTURE + ENCODE + QUEUE + HTTP + DECODE + COMPOSE +
  DISPLAY`. C'est l'âge de l'image affichée : la seule grandeur comparable à « 100 ms ».
- **A/R** = étage `HTTP` seul : requête émise → réponse complète lue (réseau + inférence + parsing).
  Dans cet arbre, le Direct passe par **WebSocket** (`/live/ws`, `core/net/LiveClientFactory.kt`) :
  l'A/R est celui d'un message sur un tunnel déjà chaud, pas d'un `POST` neuf.
- **Part réseau** = `HTTP − elapsed_ms` : le serveur se chronomètre lui-même, la différence est le
  réseau (TLS, montée, descente, edge).
- **Débit ≠ latence** : à 30 fps, chaque étage doit rendre la main en **33,3 ms**, sinon des frames
  sont sautées ; la latence perçue, elle, peut dépasser un cycle — c'est le cas aujourd'hui
  (612–801 ms calculés, § 4). Le problème à traiter est donc la latence, pas le débit.

## 2. Budget par étage

Chemin Direct, frame 512×206 JPEG q65 = 20,7 Ko (27 Ko en base64), 4 concepts, 12 objets.

<!-- table:étages -->
| étage | ce qu'il couvre | valeur (ms) | provenance | plancher |
|---|---|---:|---|---|
| `CAPTURE` | capteur → callback de l'analyseur CameraX | 25 | u07 **simulation** (25 ms de centre, jamais lu sur un appareil) | période caméra : 33,3 ms à 30 fps, 16,7 ms à 60 fps |
| `ENCODE` | NV21 → JPEG q65 → base64 du corps de requête | 5–8 | u02 **estimé** (17–32 ms avant la refonte NV21, 3 bitmaps/frame) | ≈ 2 ms pour un 512×206 (non mesuré ici) |
| `QUEUE` | attente du créneau d'envoi (`intervalMsAdaptive`) | calculée | u01 + hypothèse de phase uniforme (attente ≈ intervalle ÷ 2) ; cadence prudente de cet arbre : 350 / 500 / 900 ms | 0 ms si la frame est traitée sans tick (chemin local) |
| `HTTP` | requête émise → réponse complète lue par le client (**= A/R**) | 422 (médian mesuré) | notes de campagne (12 appels keep-alive, A100 eu-west, 512×206, 4 concepts) | plancher 123,7 ms = réseau 93 + serveur 30,7 (meilleur cas mesuré des deux côtés) |
| `DECODE` | base64 + PNG des masques → tampons gris | 10–25 | u07 simulation ; u03 a supprimé 3,4 Mo/frame d'allocation (mesuré JVM) | 0 ms quand le masque est déjà en mémoire (propagation) |
| `COMPOSE` | recoloration + composition dans une seule surface | 0,9–3 | u03 **mesuré** 0,93 ms/frame (JVM de CI, 8 objets, 512×206 ; 1,79 ms avant) | ≈ 0,9 ms (le mesuré JVM est déjà proche du plancher) |
| `DISPLAY` | publication de l'état → composition Compose (vsync) | 16,7–17,4 | plancher physique 1 vsync ; u07 sim 17,4 ms (± 1 frame, ± 16 ms) | 16,7 ms à 60 Hz, 11,1 ms à 90 Hz, 8,3 ms à 120 Hz |
<!-- /table:étages -->

Trois remarques qui commandent la suite :

- `CAPTURE` est une **hypothèse de simulation** (25 ms) : l'instrumentation existe (7 étages en
  p50/p95) mais aucune valeur n'a encore été lue sur un téléphone. C'est le premier chiffre à
  mesurer : 36 % du budget cible.
- `QUEUE` n'est pas un coût technique mais un **coût de politique** : la frame attend un créneau
  d'envoi. Le modèle de campagne prend une attente ≈ intervalle ÷ 2 (phase uniforme) alors que la
  cadence prudente de cet arbre est de **350 / 500 / 900 ms** (`core/live/LiveSession.kt:33`) : les
  lignes cloud du § 4 sont donc des **bornes basses**, pas des promesses. C'est le seul étage que le
  passage au local supprime intégralement (0 ms).
- `DECODE` a perdu son coût d'allocation (3,4 Mo/frame → 0 octet, mesuré sur JVM) mais garde un coût
  CPU de décodage PNG. Un format binaire de masque n'a d'intérêt que si le client le décode — c'est
  un autre chantier, pas encore fait.

### 2 bis. Ce que contient `HTTP` côté serveur

<!-- table:serveur -->
| sous-étage serveur (dans `HTTP`) | ms | mesure ou calcul | détail |
|---|---:|---|---|
| base64 + décodage image (PIL `draft`) | 1,2 | mesuré (u05, r630) | campagne vague 1 (hors dépôt) · `bench_live_cpu.py` |
| encodeur vision (1 passe/frame) | ≈ 234 | **calculé par soustraction** (u05) : à confirmer par `breakdown_ms` sur un appel réel | campagne vague 1 (hors dépôt) · u05 |
| détection DETR × 4 concepts | ≈ 85 | calculé (u05 : 328 − 234 − 7) ; 21,2 ms/concept compare deux mesures différentes | u05 · vérification indépendante |
| encodage des masques PNG + base64 | 7,3 | mesuré (u05) — **hors du verrou GPU** après u05/u21 | 17,5 → 6,8 ms en 4 threads (u21) |
| masques au format RLE 256 px (u04) | 1,5 | mesuré sur masques synthétiques (u04) | −84 % d'octets, −6 ms de CPU |
| file d'attente GPU (N frames en vol) | ≈ 300 × (N − 1) | mesuré indirectement : 1 220–1 260 ms affichés avec 6 frames en vol | campagne vague 1 (hors dépôt) |
| total serveur `elapsed_ms` | 294–328 (A100) · 244–247 (H200) · 41 (B200) · 30,7 (tracker) | mesuré | campagne vague 1 (hors dépôt) · `bench_track.py` |
| démarrage à froid | 25–38 s | mesuré (hors chemin critique, facturé au client) | campagne vague 1 (hors dépôt) |
<!-- /table:serveur -->

Nature des chiffres : seuls `1,2 ms` (décodage), `7,3 ms` (masques) et les totaux `elapsed_ms`
sont **mesurés**. Le partage vision ≈ 234 ms / détection ≈ 85 ms est une **attribution par
soustraction** (328 − 234 − 7) : un `breakdown_ms` sur un appel réel doit le trancher.

## 3. Planchers physiques

<!-- table:planchers -->
| grandeur | valeur | comment elle est obtenue |
|---|---:|---|
| Aller-retour HTTP cloud, hôte filaire | 93 ms | 12 appels keep-alive, frame 512×206 (20,7 Ko → 27 Ko en base64) — **le plus petit observé** |
| Aller-retour HTTP cloud, téléphone | 150–250 ms | mesures terrain antérieures (Xiaomi, Wi-Fi 192 Mbps) ; ~200 ms aussi depuis un PC |
| Coût marginal montant | ≈ 0,12 ms/Ko | calculé sur 2 points mesurés : 27 Ko → 93 ms, 2 437 Ko → 381 ms (soit ≈ 68 Mbit/s utiles) |
| Inférence serveur, 1 concept | 41 ms (B200) · 30,7 ms (tracker 720p) | mesuré ; A100 4 concepts 328 ms, H200 8 concepts 245 ms |
| Période caméra | 33,3 ms (30 fps) · 16,7 ms (60 fps) | physique : une frame fraîche ne peut pas exister plus souvent |
| Affichage | 16,7 ms à 60 Hz | physique : 1 vsync, et ± 1 frame d'incertitude u07 |
| A/R cloud complet (plancher structurel) | 123,7 ms | calculé : réseau le plus petit mesuré + serveur le plus rapide mesuré — **> 100 ms avant tout client** |
| Fenêtre de fraîcheur d'un masque propagé | 200–500 ms | choix d'architecture (u12) : coût d'un keyframe SAM, hors chemin perçu |
<!-- /table:planchers -->

- **Le réseau est le seul plancher qui ne se négocie pas** : 93 ms est le plus petit A/R jamais
  mesuré (banc filaire, keep-alive, petite frame) ; depuis un téléphone, 150–250 ms. Aucun GPU,
  aucun codec, aucune compression ne descend sous ce plancher — c'est un aller-retour.
- **Le coût montant est linéaire** : ≈ 0,12 ms/Ko (calculé sur deux points mesurés). Une frame
  2048 px (2,3 Mo montants) ajoute ~288 ms de réseau à elle seule : la définition native est
  incompatible avec la cible.
- **L'affichage coûte une vsync** : 16,7 ms à 60 Hz, 11,1 ms à 90 Hz, 8,3 ms à 120 Hz. C'est le
  seul étage « physique » que l'utilisateur peut changer sans code.
- **Le démarrage à froid (25–38 s) n'est pas de la latence** mais une indisponibilité : il sort du
  budget perçu et se traite par l'infrastructure, pas par une optimisation dans la boucle de frame.

## 4. Budget par architecture (latence perçue, calculée étage par étage)

<!-- table:architectures -->
| architecture | contexte | A/R (ms) | attente (ms) | client hors HTTP (ms) | **perçu (ms)** | < 100 ms ? |
|---|---|---:|---:|---:|---:|---|
| Cloud A100 eu-west, 4 concepts | téléphone | 444–578 | 111–144,5 | 168,6–222,9 | **612,6–800,9** | non (+512,6–700,9 ms) |
| Cloud A100 eu-west, 4 concepts | banc filaire r630 | 387–528 | 96,8–132 | 154,3–210,4 | **541,3–738,4** | non (+441,3–638,4 ms) |
| Cloud H200, 8 concepts | téléphone | 394–497 | 98,5–124,3 | 156,1–202,6 | **550,1–699,6** | non (+450,1–599,6 ms) |
| Cloud B200 /live, 1 concept | téléphone | 191–291 | 47,8–72,8 | 105,3–151,1 | **296,3–442,1** | non (+196,3–342,1 ms) |
| Cloud B200 + tracker vidéo 720p | téléphone | 180,7–280,7 | 45,2–70,2 | 102,7–148,6 | **283,4–429,3** | non (+183,4–329,3 ms) |
| Cloud B200 + tracker vidéo 720p | banc filaire r630 | 123,7 | 0–16,7 | 57,6–95,1 | **181,3–218,8** | non (+81,3–118,8 ms) |
| Boîte GPU dans le LAN (4090/5090) | téléphone ↔ LAN | 26–48 | 0–16,7 | 57,6–95,1 | **83,6–143,1** | à la limite (83,6–143,1 ms, non garanti) |
| Keyframe cloud + propagation locale (30 fps) | téléphone | 0 | 4–10 | 46,6–55,4 | **46,6–55,4** | **oui** (marge 44,6 ms) |
<!-- /table:architectures -->

Comment lire, et ce que le modèle ne prouve pas :

- `client hors HTTP` = les étages client qui s'ajoutent à l'A/R, **attente comprise**
  (`CAPTURE + ENCODE + QUEUE + DECODE + COMPOSE + DISPLAY` ; pour la dernière ligne, `QUEUE` est
  remplacée par la `PROPAGATION` locale). La formule du tableau est donc
  **`perçu = A/R + client hors HTTP`**, et la colonne `attente` est un **sous-étage** de ce client ;
  elle est affichée à part parce que c'est le seul levier que le choix d'architecture déplace.
- **Calibrage** : le modèle suppose les optimisations de la vague 1 appliquées (encode 5–8 ms,
  composition 0,93 ms, cadence adaptative). Les relevés terrain antérieurs restent **plus hauts** :
  1 220–1 260 ms affichés avec 6 frames en vol. L'écart est attribué (file d'attente GPU et créneau
  d'envoi), pas inexpliqué.
- La ligne **banc filaire** est la plus favorable au cloud (plus petit réseau mesuré) : c'est une
  borne basse, pas un scénario produit.
- La ligne **LAN** suppose une carte achetée (décision utilisateur) et 60 fps : elle ne franchit la
  cible qu'en bas de plage, car capture + affichage y pèsent déjà 42 ms.
- **Ce qui n'est pas dans le modèle** : la gigue (p95), les frames jetées, et le surcoût d'un
  décodage PNG à 12 objets. Le budget est donc optimiste par construction ; c'est voulu — un budget
  bâti sur les p50 doit laisser la marge pour les p95.

## 5. Verdict chiffré sur les 100 ms

<!-- table:verdict -->
- plancher d'A/R cloud (banc filaire, serveur le plus rapide mesuré) : 123,7 ms → déjà > 100 ms
- meilleur perçu cloud calculé : 181,3 ms (banc filaire + tracker B200, hypothèse la plus favorable) ; état mesuré aujourd'hui côté téléphone : 612,6–800,9 ms
- propagation locale : 46,6–55,4 ms perçus → **sous 100 ms avec 44,6 ms de marge**
- boîte GPU dans le LAN : 83,6–143,1 ms → franchit la cible en bas de plage, la dépasse en haut
- budget de calcul laissable à un modèle **local sur l'appareil** (u15) : ≤ 55 ms par frame pour tenir 100 ms perçus à 60 Hz
- débit : à 30 fps, tout étage de calcul doit tenir 33,3 ms par frame (sinon des frames sont sautées) — contrainte distincte de la latence
<!-- /table:verdict -->

En une phrase : **la cible de 100 ms n'est atteignable qu'en local** — en propageant sur l'appareil
le masque d'un keyframe SAM (46,6–55,4 ms calculés), ou avec une boîte GPU dans le LAN
(83,6–143,1 ms, non garantie). Toute architecture cloud reste au-dessus, non pas à cause de
l'inférence mais du seul A/R : **123,7 ms au plancher absolu** (93 ms de réseau + 30,7 ms de
serveur, les deux meilleurs chiffres mesurés séparément).

## 6. Ce qui reste impossible (et pourquoi)

1. **Un A/R HTTP cloud sous 93 ms** (banc filaire) ou **150 ms** (téléphone) : plancher mesuré,
   indépendant du serveur. Corollaire : aucun masque rendu par le serveur ne peut s'afficher en
   moins de ~124 ms, même sur le meilleur matériel mesuré.
2. **Un masque SAM exact à 30 fps par HTTP cloud** : il faudrait 123,7 ms par cycle au minimum, soit
   ~8 fps de contenu SAM. La fluidité 30 fps doit venir d'ailleurs (propagation locale).
3. **La définition native (2048 px) sous 100 ms** : +288 ms de réseau mesurés pour 2,3 Mo montants.
4. **Un GPU plus cher** : mesuré — un H200 (serveur 406 → 245 ms) n'a **rien** changé sur le
   téléphone (599 → 754 ms affichés). Le goulot est le lien montant et la file d'attente, pas la
   carte.
5. **Moins d'une vsync** à l'affichage : 16,7 ms à 60 Hz, par construction.
6. **Zéro cold start sans réglage d'infrastructure** : 25–38 s mesurés ; c'est de la disponibilité,
   pas de la latence, et ça ne se code pas dans le chemin d'une frame.
7. **Un masque propagé exact** : la propagation dérive (occlusions, sortie de champ, objet rapide) ;
   la qualité est bornée par la fréquence des keyframes (200–500 ms). Compromis assumé, et le masque
   n'est « SAM 3.1 » qu'à chaque keyframe — à dire dans l'UI, pas à cacher.
8. **< 50 ms depuis un téléphone en cloud** : 150 ms de réseau minimum. Les 50 ms ne sont
   atteignables que par la propagation locale (46,6–55,4 ms), et de justesse.

## 7. Budget cible < 100 ms (allocation pour l'architecture locale)

<!-- table:budget -->
| étage du chemin local | cible | justification |
|---|---:|---|
| CAPTURE | ≤ 25 | hypothèse u07 à valider sur l'appareil — **le premier chiffre à mesurer**, il occupe 36 % du budget |
| PROPAGATION (u10+u11) | ≤ 10 | cible u11 < 8 ms/frame à 512×206 ; 33,3 ms de débit disponible à 30 fps |
| COMPOSE | ≤ 3 | 0,93 ms mesuré (u03) — 3 ms laisse la place aux 12 objets |
| DISPLAY | ≤ 17 | 1 vsync 60 Hz (16,7 ms) ; 90 Hz ou 120 Hz rendrait 5–8 ms |
| Aléas (GC, ordonnancement, reprise d'écran) | ≤ 15 | u03 a retiré 6,8 Mo/frame de pression GC ; le reste est à mesurer |
| **total alloué** | **≤ 70** | **marge 30 ms sur 100** (30 %) |
<!-- /table:budget -->

Règles d'usage :

- un dépassement d'étage **mange la marge** : 70 ms alloués laissent 30 ms, soit 30 % du budget ;
- `CAPTURE` est une hypothèse : si la mesure sur appareil donne 40 ms, l'allocation est à refaire —
  la marge ne l'absorbe pas ;
- la propagation doit tenir **deux** contraintes distinctes : ≤ 10 ms de latence **et** ≤ 33,3 ms de
  débit par frame à 30 fps (cible < 8 ms/frame à 512×206, ce qui laisse de la place) ;
- le keyframe SAM (A/R 444–578 ms depuis un téléphone) est **hors** du chemin perçu : il ne coûte
  que la fraîcheur du masque (200–500 ms) et ~40–100 Ko/s d'octets montants — négligeable.

## 8. Plan de mesure — qui remplit quel chiffre

| grandeur | mesure manquante | unité | attendu |
|---|---|---|---|
| `CAPTURE`, `ENCODE`, `DECODE`, `DISPLAY` réels | valeurs terrain (le banc est une simulation) | instrument | profil 7 étages p50/p95 dans le statut |
| distribution d'A/R (N = 20, keep-alive, p50/p95) | reproductibilité de l'A/R médian | banc réseau | un banc sans campagne payante |
| propagation locale (ms/frame) | cible < 8 ms à 512×206 | banc JVM | ms/frame par étape de warp |
| part vision / détection du serveur | attribution par soustraction à confirmer | backend | un appel `/live` réel avec `breakdown_ms` |
| poids montant ⇄ octets ⇄ ms | modèle qualité ↔ latence | transport | tableau octets ↔ ms |
| dérive du masque ⇄ fréquence de keyframe | erreur de reprojection | simulation | courbe erreur ↔ intervalle |

Tant que ces mesures ne sont pas faites, ce budget est **exact en planchers** (mesurés) et
**provisoire en coûts client** (estimés ou hypothèses) — chaque ligne des tableaux le dit.

## 9. Ce que ce document n'affirme pas

- Aucune mesure n'a été faite sur un téléphone pour ce document. Les coûts client sont des
  simulations ou des estimations reprises de la campagne, pas des relevés d'appareil.
- Les chiffres marqués `uNN` viennent de notes **hors de ce dépôt** : ils décrivent le backend et
  les patchs de la vague 1, pas le code d'ici.
- Le partage vision / détection du serveur est **calculé**, pas mesuré (§ 2 bis).
- Les gains d'allocation des vagues précédentes sont mesurés sur la JVM de CI, pas sur ART.
- La latence « affichée » par l'app (1 220–1 260 ms) mesure une file de 6 frames : elle ne se
  compare pas directement à la latence perçue d'une frame et ne doit pas servir de référence.
- `docs/PERF.md` § 3 dit l'inverse et a raison : **aucune latence n'est mesurée dans cet arbre**.
  Ce document ne change pas ce constat, il chiffre des planchers et des hypothèses.

## Annexe A — où retrouver chaque chiffre

| chiffre | source | unité |
|---|---|---|
| A/R 422 ms, serveur 328 ms, réseau 93 ms, 2,3 Mo → +381 ms | note de campagne « 12 appels keep-alive » (hors dépôt) | vague 1 |
| 294–328 ms A100 eu-west · 244–247 ms H200 · 41 ms B200 · 25–38 s à froid | notes de campagne (hors dépôt) | — |
| 30,7 ms/frame (32,6 fps) tracker B200 720p | banc de campagne `bench_track.py` (hors dépôt) | u21 |
| 1,2 ms décodage · 7,3 ms masques · 8 ms de CPU serveur | banc de campagne `bench_live_cpu.py` (hors dépôt) | u05 |
| 3 608 → 560 octets, 6,7 → 1,5 ms (RLE 256 px) | banc de campagne `bench_mask_payload.py` (hors dépôt) | u04 |
| composition 1,79 → 0,93 ms/frame, 3,4 Mo → 0 octet | banc `MaskOverlayBenchTest` (hors dépôt) | u03 |
| encode 17–32 → 5–8 ms (NV21), 3 bitmaps → 0 | `Nv21JpegEncoder` (hors dépôt) | u02 |
| cadence adaptative, réserve 1 frame | `LiveSession`, `LivePump` (vague 1) ; ici : `Cadence.Direct` 350/500/900 ms | u01 |
| 7 étages, perçu ≠ A/R, coût d'instrumentation borné | `LatencyProfile`, `LatencyTrace` (vague 1) | u07 |
| 1 220–1 260 ms affichés (6 en vol) · 599–775 ms (1 en vol) | notes de campagne, terrain | terrain |

## Annexe B — ce que cet arbre-ci permet de vérifier

| fait vérifiable ici | où | valeur / conséquence |
|---|---|---|
| transport du Direct | `core/net/LiveClientFactory.kt`, `core/net/WsLiveClient.kt` | **WebSocket** vers `/live/ws`, pas de `POST` par frame |
| gain du WebSocket sur le `POST`, mesuré côté serveur | `core/net/WsLiveClient.kt:11` (commentaire daté) | 50,3 ms contre 81,4 ms — ~31 ms gagnés par frame |
| cadence prudente du Direct | `core/live/LiveSession.kt:33` (`Cadence.Direct`) | 350 / 500 / 900 ms → l'attente réelle est **plus haute** que l'hypothèse du § 4 |
| ce que cet arbre refuse d'affirmer | `docs/PERF.md` § 1 et § 3 | latences, fps, réveil de conteneur : « non mesuré ici » |
| instrument côté client | `core/live/LiveStats.kt`, banc JVM `LiveFrameBudgetTest` | coûts CPU mesurés en microsecondes, hors JPEG et hors radio |