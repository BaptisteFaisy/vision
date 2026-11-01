# LATENCE-BUDGET — budget de latence « capteur → pixels » (mode Direct)

Cible : **latence perçue < 100 ms** en mode Direct (frame caméra → masque affiché). Ce document est
le **budget** : chaque étage du chemin, son plancher physique, ce que les vagues précédentes ont déjà
retiré, ce qui est atteignable, et ce qui ne le sera jamais en cloud.

Règles de lecture :

- chaque chiffre porte son étiquette — **mesuré** (banc ou terrain, source citée), **calculé**
  (dérivé de mesures, formule donnée), **estimé** (ordre de grandeur jamais instrumenté ici),
  **hypothèse** (valeur de travail, à remplacer par une mesure), **simulation** (modèle, jamais
  lu sur un appareil). L'étiquette n'est pas décorative : `scripts/check-budget-provenance.py`
  échoue si un étage n'en porte aucune, si un « mesuré » n'a ni date ni aveu de date manquante,
  et si une hypothèse survit à une campagne archivée qui mesure le même étage ;
- la grandeur de référence est la **latence perçue** (§ 1) : un A/R serveur de 41 ms ne dit rien de
  ce que l'utilisateur voit ;
- les **7 blocs chiffrés** de ce document sont **générés** par `scripts/budget_latence.py`, la source
  unique des chiffres. `--check-doc` échoue si ce document et le script divergent, `--selftest`
  vérifie l'arithmétique, `--verdict` imprime le verdict sur les 100 ms.

Provenance : les valeurs marquées `uNN` viennent des notes de la campagne (vague 1), **hors de ce
dépôt** ; aucune n'est présentée comme mesurée **ici** — sauf celles datées du 01/11/2026, lues sur
un téléphone via `ISEE_LAT` (§ 10 bis). Ce que cet arbre-ci permet de vérifier est listé en
annexe B, et `docs/PERF.md` reste la référence de ce qui est mesuré dans cet arbre.

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
  (690,6–875,9 ms calculés, § 4). Le problème à traiter est donc la latence, pas le débit.

## 2. Budget par étage

Chemin Direct, frame 512×206 JPEG q65 = 20,7 Ko (27 Ko en base64), 4 concepts, 12 objets.

<!-- table:étages -->
| étage | ce qu'il couvre | valeur (ms) | provenance | plancher |
|---|---|---:|---|---|
| `CAPTURE` | capteur → callback de l'analyseur CameraX | 81 | **mesuré** 81 ms le 01/11/2026 (`ISEE_LAT` capsplit `age`, téléphone ; N non archivé) — la cause n'est pas établie : le poste `int` (cadence obtenue) la tranche | période caméra 33,3 ms à 30 fps, donc exposition <= 33 ms : au moins ~48 ms de ces 81 ms ne sont ni l'exposition ni la cadence (readout, ISP, file — à départager) |
| `ENCODE` | NV21 → JPEG q65 → base64 du corps de requête | 27 | **mesuré** 27 ms le 01/11/2026 (`ISEE_LAT` capsplit `enc` 24 + `b64` 3, téléphone ; N non archivé) | ≈ 2 ms pour un 512×206 (non mesuré ici) ; u02 donnait 5–8 ms, base64 exclu |
| `QUEUE` | attente du créneau d'envoi (`intervalMsAdaptive`) | calculée | u01 + hypothèse de phase uniforme (attente ≈ intervalle ÷ 2) ; cadence prudente de cet arbre : 350 / 500 / 900 ms | 0 ms si la frame est traitée sans tick (chemin local) |
| `HTTP` | requête émise → réponse complète lue par le client (**= A/R**) | 422 (médian mesuré) | **mesuré** (notes de campagne, 12 appels keep-alive, A100 eu-west, 512×206 ; date non consignée) | plancher 123,7 ms = réseau 93 + serveur 30,7 (meilleur cas mesuré des deux côtés) |
| `DECODE` | base64 + PNG des masques → tampons gris | 10–25 | u07 **simulation** (10–25 ms, jamais lu sur un appareil) ; u03 a retiré 3,4 Mo/frame d'allocation (banc JVM, date non consignée) — le décodage PNG, lui, n'est pas instrumenté | 0 ms quand le masque est déjà en mémoire (propagation) |
| `COMPOSE` | recoloration + composition dans une seule surface | 0,9–3 | u03 **mesuré** 0,93 ms/frame (banc JVM `MaskOverlayBenchTest`, 8 objets, 512×206 ; date non consignée) | ≈ 0,9 ms (le mesuré JVM est déjà proche du plancher) |
| `DISPLAY` | publication de l'état → composition Compose (vsync) | 16,7–17,4 | **calculé** (1 vsync à 60 Hz) ; u07 simulait 17,4 ms (± 1 frame, ± 16 ms) | 16,7 ms à 60 Hz, 11,1 ms à 90 Hz, 8,3 ms à 120 Hz |
<!-- /table:étages -->

Trois remarques qui commandent la suite :

- `CAPTURE` **n'est plus une hypothèse** : 81 ms mesurés le 01/11/2026 (`age` de la décomposition
  `capsplit`), contre 25 ms simulés. Ce qui restait invisible — 81 ms répartis entre expo, readout,
  livraison et attente fil, qu'aucune sortie ne sommait — est désormais publié par
  `scripts/parse_isee_lat.py --capsplit`. En revanche **la cause de ces 81 ms n'est pas établie**,
  et ce document ne la suppose plus : à 30 fps l'exposition ne peut pas dépasser 33 ms, donc au
  moins ~48 ms viennent d'ailleurs (readout, ISP, file d'attente de l'analyseur) sans qu'on sache
  dans quelles proportions. Le poste `int` — la cadence réellement obtenue — est là pour trancher :
  `int` ≈ 33 ms avec `age` 81 ms disculpe l'exposition *et* la cadence et désigne l'amont ;
  `int` ≫ 33 ms désigne une cadence perdue. Tant que la campagne n'a pas été relue avec `int`,
  l'étage est *mesuré* mais **pas expliqué**.
- `ENCODE` non plus : **27 ms mesurés** (`enc` 24 + `b64` 3), contre 5–8 ms estimés — l'estimation
  u02 avait été faite avant d'inclure le base64. Avec `CAPTURE`, cela déplace le budget client hors
  attente de 57,6–78,4 ms à 135,6–153,4 ms (§ 4).
- `QUEUE` n'est pas un coût technique mais un **coût de politique** : la frame attend un créneau
  d'envoi. Le modèle de campagne prend une attente ≈ intervalle ÷ 2 (phase uniforme) alors que la
  cadence prudente de cet arbre est de **350 / 500 / 900 ms** (`core/live/LiveSession.kt:34`) : les
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
- **Protocole de réveil (31/10)** : `GET /warm` réveille le conteneur sans inférence (boot
  complet 25–38 s à froid, restauration snapshot 2–7 s ensuite) ; le déploiement CI exige
  le marqueur `[deploy-gpu]` pour ne pas invalider le snapshot à chaque push. Le snapshot
  ne change aucun chiffre de ce budget (disponibilité, pas latence), mais une recette
  qui suit un deploy de peu paie le boot complet — prévoir le réveil hors prise.

## 4. Budget par architecture (latence perçue, calculée étage par étage)

<!-- table:architectures -->
| architecture | contexte | A/R (ms) | attente (ms) | client hors HTTP (ms) | **perçu (ms)** | < 100 ms ? |
|---|---|---:|---:|---:|---:|---|
| Cloud A100 eu-west, 4 concepts | téléphone | 444–578 | 111–144,5 | 246,6–297,9 | **690,6–875,9** | non (+590,6–775,9 ms) |
| Cloud A100 eu-west, 4 concepts | banc filaire r630 | 387–528 | 96,8–132 | 232,4–285,4 | **619,4–813,4** | non (+519,4–713,4 ms) |
| Cloud H200, 8 concepts | téléphone | 394–497 | 98,5–124,3 | 234,1–277,7 | **628,1–774,7** | non (+528,1–674,7 ms) |
| Cloud B200 /live, 1 concept | téléphone | 191–291 | 47,8–72,8 | 183,4–226,2 | **374,4–517,2** | non (+274,4–417,2 ms) |
| Cloud B200 + tracker vidéo 720p | téléphone | 180,7–280,7 | 45,2–70,2 | 180,8–223,6 | **361,5–504,3** | non (+261,5–404,3 ms) |
| Cloud B200 + tracker vidéo 720p | banc filaire r630 | 123,7 | 0–16,7 | 135,6–170,1 | **259,3–293,8** | non (+159,3–193,8 ms) |
| Boîte GPU dans le LAN (4090/5090) | téléphone ↔ LAN | 26–48 | 0–16,7 | 135,6–170,1 | **161,6–218,1** | non (+61,6–118,1 ms) |
| Keyframe cloud + propagation locale (30 fps) | téléphone | 0 | 4–10 | 46,6–55,4 | **46,6–55,4** | **oui** (marge 44,6 ms) |
| Le même, capture et encode mesurés (01/11) | téléphone | 0 | 4–10 | 102,6–111,4 | **102,6–111,4** | non (+2,6–11,4 ms) |
<!-- /table:architectures -->

Comment lire, et ce que le modèle ne prouve pas :

- `client hors HTTP` = les étages client qui s'ajoutent à l'A/R, **attente comprise**
  (`CAPTURE + ENCODE + QUEUE + DECODE + COMPOSE + DISPLAY` ; pour la dernière ligne, `QUEUE` est
  remplacée par la `PROPAGATION` locale). La formule du tableau est donc
  **`perçu = A/R + client hors HTTP`**, et la colonne `attente` est un **sous-étage** de ce client ;
  elle est affichée à part parce que c'est le seul levier que le choix d'architecture déplace.
- **Calibrage** : le modèle suppose les optimisations de la vague 1 appliquées (composition
  0,93 ms, cadence adaptative) mais **plus** l'encode de 5–8 ms ni la capture de 25 ms : ces deux
  étages valent 27 et 81 ms mesurés depuis le 01/11/2026. Les relevés terrain antérieurs restent
  **plus hauts** encore : 1 220–1 260 ms affichés avec 6 frames en vol. L'écart est attribué
  (file d'attente GPU et créneau d'envoi), pas inexpliqué.
- La ligne **banc filaire** est la plus favorable au cloud (plus petit réseau mesuré) : c'est une
  borne basse, pas un scénario produit.
- La ligne **LAN** suppose une carte achetée (décision utilisateur) et 60 fps : elle ne franchit
  plus la cible (161,6–218,1 ms), car capture + affichage y pèsent déjà 98 ms des 100 visés. Un
  réseau local cinq fois plus court ne rattrape pas un coût client mesuré.
- La dernière ligne compte plus que les autres : le chemin local ne tient les 100 ms que **si
  l'encode par frame disparaît**. S'il reste, 81 ms de capture et 17 ms d'affichage mangent la
  cible avant la moindre propagation (102,6–111,4 ms). L'architecture locale n'est donc pas
  gratuite : elle suppose d'arrêter d'encoder du JPEG à chaque frame — ou que les 81 ms de
  `CAPTURE` soient largement réductibles, ce que la campagne n'a pas encore établi (§ 2).
- **Ce qui n'est pas dans le modèle** : la gigue (p95), les frames jetées, et le surcoût d'un
  décodage PNG à 12 objets. Le budget est donc optimiste par construction ; c'est voulu — un budget
  bâti sur les p50 doit laisser la marge pour les p95.

## 5. Verdict chiffré sur les 100 ms

<!-- table:verdict -->
- plancher d'A/R cloud (banc filaire, serveur le plus rapide mesuré) : 123,7 ms → déjà > 100 ms
- meilleur perçu cloud calculé : 259,3 ms (banc filaire + tracker B200, hypothèse la plus favorable) ; état mesuré aujourd'hui côté téléphone : 690,6–875,9 ms
- propagation locale : 46,6–55,4 ms perçus → **sous 100 ms avec 44,6 ms de marge**
- boîte GPU dans le LAN : 161,6–218,1 ms → franchit la cible en bas de plage, la dépasse en haut
- budget de calcul laissable à un modèle **local sur l'appareil** (u15) : ≤ 55 ms par frame pour tenir 100 ms perçus à 60 Hz
- débit : à 30 fps, tout étage de calcul doit tenir 33,3 ms par frame (sinon des frames sont sautées) — contrainte distincte de la latence
<!-- /table:verdict -->

En une phrase : **la cible de 100 ms n'est atteignable qu'en local**, et à une condition — que
l'encode JPEG par frame disparaisse. En propageant sur l'appareil le masque d'un keyframe SAM on
obtient 46,6–55,4 ms calculés ; si l'encode reste, le même chemin coûte 102,6–111,4 ms et rate la
cible. La boîte GPU dans le LAN sort désormais à 161,6–218,1 ms : elle ne la franchit plus. Toute
architecture cloud reste au-dessus, non pas à cause de l'inférence mais du seul A/R : **123,7 ms au
plancher absolu** (93 ms de réseau + 30,7 ms de serveur, les deux meilleurs chiffres mesurés
séparément), et 135,6–153,4 ms de coût client mesuré s'y ajoutent.

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
| CAPTURE | ≤ 25 | cible locale : les 81 ms **mesurés** sur le chemin Direct (§ 2) sont un backlog d'analyseur, supposé disparaître quand le chemin local n'encode plus de JPEG par frame — le pari le plus fragile du document |
| PROPAGATION (u10+u11) | ≤ 10 | cible u11 < 8 ms/frame à 512×206 ; 33,3 ms de débit disponible à 30 fps |
| COMPOSE | ≤ 3 | 0,93 ms mesuré (u03) — 3 ms laisse la place aux 12 objets |
| DISPLAY | ≤ 17 | 1 vsync 60 Hz (16,7 ms) ; 90 Hz ou 120 Hz rendrait 5–8 ms |
| Aléas (GC, ordonnancement, reprise d'écran) | ≤ 15 | u03 a retiré 6,8 Mo/frame de pression GC ; le reste est à mesurer |
| **total alloué** | **≤ 70** | **marge 30 ms sur 100** (30 %) |
<!-- /table:budget -->

Règles d'usage :

- un dépassement d'étage **mange la marge** : 70 ms alloués laissent 30 ms, soit 30 % du budget ;
- `CAPTURE` est mesuré à **81 ms** sur le chemin Direct : la cible de 25 ms du tableau ci-dessus ne
  tient que si le chemin local n'encode plus de JPEG par frame. Si l'encode reste, l'étage consomme
  81 des 100 ms et l'allocation entière est morte — c'est le risque n° 1 du plan local ;
- la propagation doit tenir **deux** contraintes distinctes : ≤ 10 ms de latence **et** ≤ 33,3 ms de
  débit par frame à 30 fps (cible < 8 ms/frame à 512×206, ce qui laisse de la place) ;
- le keyframe SAM (A/R 444–578 ms depuis un téléphone) est **hors** du chemin perçu : il ne coûte
  que la fraîcheur du masque (200–500 ms) et ~40–100 Ko/s d'octets montants — négligeable.

## 8. Plan de mesure — qui remplit quel chiffre

| grandeur | mesure manquante | unité | attendu |
|---|---|---|---|
| `CAPTURE` / `ENCODE` mesurés (81 / 27 ms) : effectif | moyenne seule, sans p50/p95 | instrument | archiver le dump `ISEE_LAT` du 01/11 (N ≥ 100) |
| `DECODE`, `DISPLAY` réels, `CAPTURE` local | valeurs terrain (le banc est une simulation) | instrument | `--capsplit` en campagne longue, p50/p95 |
| distribution d'A/R (N = 20, keep-alive, p50/p95) | reproductibilité de l'A/R médian | banc réseau | un banc sans campagne payante |
| propagation locale (ms/frame) | cible < 8 ms à 512×206 | banc JVM | ms/frame par étape de warp |
| part vision / détection du serveur | attribution par soustraction à confirmer | backend | un appel `/live` réel avec `breakdown_ms` |
| poids montant ⇄ octets ⇄ ms | modèle qualité ↔ latence | transport | tableau octets ↔ ms |
| dérive du masque ⇄ fréquence de keyframe | erreur de reprojection | simulation | courbe erreur ↔ intervalle |

Tant que ces mesures ne sont pas faites, ce budget est **exact en planchers** (mesurés) et
**provisoire en coûts client** (estimés ou hypothèses) — chaque ligne des tableaux le dit.

## 9. Ce que ce document n'affirme pas

- **Hors `CAPTURE` et `ENCODE`** (§ 2, mesurés sur téléphone le 01/11/2026, sans p50/p95) et hors
  § 10, les coûts client sont des simulations ou des estimations reprises de la campagne, pas des
  relevés d'appareil. `DECODE`, `DISPLAY` et la capture du chemin local restent des hypothèses.
- Les chiffres marqués `uNN` viennent de notes **hors de ce dépôt** : ils décrivent le backend et
  les patchs de la vague 1, pas le code d'ici.
- Le partage vision / détection du serveur est **calculé**, pas mesuré (§ 2 bis).
- Les gains d'allocation des vagues précédentes sont mesurés sur la JVM de CI, pas sur ART.
- La latence « affichée » par l'app (1 220–1 260 ms) mesure une file de 6 frames : elle ne se
  compare pas directement à la latence perçue d'une frame et ne doit pas servir de référence.
- `docs/PERF.md` § 3 dit « aucune latence n'est mesurée dans cet arbre » : c'était vrai jusqu'au
  01/11/2026. Depuis, deux étages du § 2 viennent d'un téléphone ; le reste chiffre encore des
  planchers et des hypothèses, et chaque ligne le dit dans sa colonne `provenance`.

## 10. Campagnes ISEE_LAT mesurées

Deux campagnes `ISEE_LAT` (une ligne logcat par frame complète, `LiveLatencyLog`) : la moyenne
et le max portent sur `latency`, les postes sont les moyennes par poste. Les verdicts suivent
la règle de `scripts/parse_isee_lat.py` (`moyenne <= --mean` et `max <= --max`), sous les
seuils par défaut (300 / 500) et sous l'objectif (moyenne < 250 ms, max ≤ 500 ms).

<!-- table:mesures -->
| campagne | N | moyenne (ms) | capt | up | srv | rtt | mask | pixel | max (ms) | 300 / 500 ? | 250 / 500 ? | conditions |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| baseline pré-PR2 | 1257 | 454 | 171 | 38 | 102 | 165 | 5 | 78 | 1677 | non (+154 en moyenne, +1177 en max) | non (+204 en moyenne, +1177 en max) | référence avant PR2 |
| post-PR2 | 1500 | 380 | 119 | 38 | 100 | 164 | 1 | 58 | 713 | non (+80 en moyenne, +213 en max) | non (+130 en moyenne, +213 en max) | scène vide (0 masque), app à 430 % CPU |

Règle des verdicts : celle de `scripts/parse_isee_lat.py` (`moyenne <= --mean` et `max <= --max`, défauts 300 / 500) ; `250 / 500` = objectif (moyenne < 250 ms, max ≤ 500 ms).
<!-- /table:mesures -->

Lecture :

- la campagne post-PR2 divise le max par 2,4 (1 677 → 713 ms) et retire 74 ms à la moyenne
  (454 → 380 ms), mais reste au-dessus des deux seuils — moyenne comme max ;
- les postes ne s'additionnent pas à la moyenne : `rtt` (envoi → réponse) recouvre `srv`
  plus la radio et le parse (`LiveTimings.kt`), et chaque moyenne de poste ignore les frames
  où il vaut `—` (`parse_isee_lat.py`) ;
- la campagne post-PR2 est une scène vide (0 masque) avec l'app à 430 % CPU : un contenu
  réel (masques à décoder, § 2 `DECODE`) coûterait plus cher.

### 10 bis. Campagne `capsplit` du 01/11/2026 — ce qui était mesuré nulle part

Le poste `capt` de la sonde (111 ms en moyenne) n'était décomposé par aucune sortie : on savait
qu'il coûtait cher, pas *en quoi*. `TimingsText.capsplit` (1 frame sur 20, même dump `ISEE_LAT`)
puis `parse_isee_lat.py --capsplit` répondent :

| poste | ms | ce qu'il couvre | étage du § 2 |
|---|---:|---|---|
| `age` | 81 | capteur → entrée de l'analyseur (expo, readout, livraison, file) | `CAPTURE` |
| `enc` | 24 | NV21 → JPEG q65 | `ENCODE` |
| `b64` | 3 | base64 + horodatage | `ENCODE` |
| `cb` | 2 | rappel `onFrame` (profondeur, mouvement, réserve/envoi) | — |
| `total` | 30 | analyse complète du callback | — |

`age + enc + b64 + cb = 110` contre 111 lus par la sonde : les deux mesures se rejoignent à 1 ms.
Lectures, dans l'ordre d'importance :

- **les 81 ms de `age` ne sont pas du matériel** : à 30 fps une frame naît toutes les 33,3 ms ;
  attendre 81 ms, c'est laisser 2,5 frames s'empiler dans la file d'`ImageAnalysis` parce que
  l'analyse est plus lente que la cadence. Le levier est la politique de flux et la charge de
  l'analyseur, pas le capteur ;
- **l'encode pèse 27 ms, pas 5–8** : l'estimation u02 datait d'avant l'inclusion du base64 ;
- **`cb` (2 ms) ne pèse rien** : le rappel n'est pas le goulot, contrairement à ce que laissait
  croire le total ;
- **rien de tout cela n'est un p50/p95** : l'effectif du dump n'a pas été archivé. C'est la dette
  ouverte de cette campagne, et `check-budget-provenance.py` l'affiche en avertissement à chaque
  passage.

### Ce que cette campagne ne tranche pas

Les 81 ms de `age` restent **inexpliqués** : la campagne mesure le total, pas sa répartition. Le
poste `int` (intervalle depuis l'entrée précédente de l'analyseur) a été ajouté pour ça, et la
campagne du 01/11 ne le contient pas — elle précède l'instrument. À la prochaine relecture :

| `int` mesuré | ce que ça veut dire | levier |
|---|---|---|
| ≈ 33 ms (30 fps tenus) | ni l'exposition ni la cadence ne peuvent faire 81 ms : le délai est en amont de l'analyseur (readout, ISP, buffers du HAL) | rien côté app ; voir la configuration des flux |
| ≫ 33 ms | l'application ne tient pas la cadence : l'analyseur est saturé (`total` proche de `int`) | réduire la section critique de l'analyseur |
| ≈ `total` | l'analyseur est le goulot : les frames sont jetées, pas retardées | idem, par le débit |

Tant que ce tableau n'a pas de réponse mesurée, `CAPTURE` est publié comme *mesuré mais pas
expliqué* — et aucune optimisation du chemin caméra ne peut être présentée comme un gain.

C'est aussi la leçon de fond : `CAPTURE` était publié à 25 ms avec l'étiquette *simulation* et
rien ne reliait cette étiquette à une campagne. `scripts/check-budget-provenance.py` existe pour
que ça ne recommence pas — une mesure chasse l'hypothèse, et une hypothèse qui survit à une mesure
fait échouer la CI.

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
| cadence adaptative, réserve 1 frame | `LiveSession`, `LivePump` (vague 1) ; puis `Cadence.Direct` régulée (latence/2, 33-900 ms) | u01, O8 |
| 7 étages, perçu ≠ A/R, coût d'instrumentation borné | `LatencyProfile`, `LatencyTrace` (vague 1) | u07 |
| 1 220–1 260 ms affichés (6 en vol) · 599–775 ms (1 en vol) | notes de campagne, terrain | terrain |

## Annexe B — ce que cet arbre-ci permet de vérifier

| fait vérifiable ici | où | valeur / conséquence |
|---|---|---|
| transport du Direct | `core/net/LiveClientFactory.kt`, `core/net/WsLiveClient.kt` | **WebSocket** vers `/live/ws`, pas de `POST` par frame |
| gain du WebSocket sur le `POST`, mesuré côté serveur | `core/net/WsLiveClient.kt:12` (commentaire daté) | 50,3 ms contre 81,4 ms — ~31 ms gagnés par frame |
| cadence prudente du Direct | `core/live/LiveSession.kt:34` (`Cadence.Direct`) | 350 / 500 / 900 ms → l'attente réelle est **plus haute** que l'hypothèse du § 4 |
| ce que cet arbre refuse d'affirmer | `docs/PERF.md` § 1 et § 3 | latences, fps, réveil de conteneur : « non mesuré ici » |
| instrument côté client | `core/live/LiveStats.kt`, banc JVM `LiveFrameBudgetTest` | coûts CPU mesurés en microsecondes, hors JPEG et hors radio |
| décomposition du poste `capt` | `core/live/TimingsText.kt` (`capsplit`), `app/isee/IseeCameraPane.kt` | `age` / `enc` / `b64` / `cb` / `total`, 1 frame sur 20, même dump `ISEE_LAT` |
| agrégation de cette décomposition | `scripts/parse_isee_lat.py --capsplit` | moyenne/p50/p95/max par poste, `somme` et écart au `capt` de la sonde |
| une hypothèse peut-elle survivre à une mesure ? | `scripts/check-budget-provenance.py` | non : étiquette fermée, « mesuré » daté, contradiction archive ↔ doc = échec |