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
  (678,6–863,9 ms calculés, § 4). Le problème à traiter est donc la latence, pas le débit.

## 2. Budget par étage

Chemin Direct, frame 512×206 JPEG q65 = 20,7 Ko (27 Ko en base64), 4 concepts, 12 objets.

<!-- table:étages -->
| étage | ce qu'il couvre | valeur (ms) | provenance | plancher |
|---|---|---:|---|---|
| `CAPTURE` | capteur → callback de l'analyseur CameraX | 86 | **mesuré** 86 ms le 01/11/2026 (`ISEE_LAT` capsplit `age` p50, téléphone SM-G780G, build `1.0.1139-da6531a`, n = 38) — cause établie : délai du **pipeline capteur** (exposition + readout + ISP + buffers du HAL), indépendant de la durée d'analyse et du nombre de flux liés | `int` p50 33 ms (30 fps tenus, l'app n'affame pas la caméra) et corrélation `age` ↔ `total` +0,18 : la durée d'analyse n'explique pas l'âge, le délai est en amont de l'analyseur |
| `ENCODE` | NV21 → JPEG q65 → base64 du corps de requête | 10 | **mesuré** 10 ms le 01/11/2026 (`ISEE_LAT` capsplit `enc` p50 + `b64` p50 0, 30 fps tenus, n = 343 sur 3 sessions ; le 27 venait d'un micro-échantillon n = 2, tranché) | ≈ 2 ms pour un 512×206 (non mesuré ici) ; u02 donnait 5–8 ms, base64 exclu |
| `QUEUE` | attente du créneau d'envoi (`intervalMsAdaptive`) | calculée | u01 + hypothèse de phase uniforme (attente ≈ intervalle ÷ 2), confirmée le 01/11/2026 (`up` p50 19 ms = phase caméra, file serveur 0 ms, n = 1081) ; cadence prudente de cet arbre : 350 / 500 / 900 ms | 0 ms si la frame est traitée sans tick (chemin local) |
| `HTTP` | requête émise → réponse complète lue par le client (**= A/R**) | 422 (médian mesuré) | **mesuré** (notes de campagne, 12 appels keep-alive, A100 eu-west, 512×206 ; date non consignée) | plancher 123,7 ms = réseau 93 + serveur 30,7 (meilleur cas mesuré des deux côtés) |
| `DECODE` | base64 + PNG des masques → tampons gris | 10–25 | u07 **simulation** (10–25 ms, jamais lu sur un appareil) ; u03 a retiré 3,4 Mo/frame d'allocation (banc JVM, date non consignée) — le décodage PNG, lui, n'est pas instrumenté | 0 ms quand le masque est déjà en mémoire (propagation) |
| `COMPOSE` | recoloration + composition dans une seule surface | 0,9–3 | u03 **mesuré** 0,93 ms/frame (banc JVM `MaskOverlayBenchTest`, 8 objets, 512×206 ; date non consignée) | ≈ 0,9 ms (le mesuré JVM est déjà proche du plancher) |
| `DISPLAY` | publication de l'état → composition Compose (vsync) | 16,7–17,4 | **calculé** (1 vsync à 60 Hz) ; u07 simulait 17,4 ms (± 1 frame, ± 16 ms) | 16,7 ms à 60 Hz, 11,1 ms à 90 Hz, 8,3 ms à 120 Hz |
<!-- /table:étages -->

Trois remarques qui commandent la suite :

- `CAPTURE` **n'est plus une hypothèse, ni une question ouverte** : 86 ms mesurés le 01/11/2026
  (`age` p50 de la décomposition `capsplit`, n = 38), contre 25 ms simulés. Ce qui restait
  invisible — 86 ms répartis entre expo, readout, livraison et attente fil, qu'aucune sortie ne
  sommait — est publié par `scripts/parse_isee_lat.py --capsplit`. **La cause est établie** : c'est
  le **pipeline du capteur** — exposition (≤ 33 ms à 30 fps), readout, ISP, buffers du HAL — et non
  l'application. Quatre faits de la même campagne la fixent : `int` p50 = 33 ms (la caméra tient
  30 fps, l'app ne l'affame pas), donc 86 ms = **2,61 périodes de frame** ; la corrélation
  `age` ↔ `total` vaut **+0,18** (la durée d'analyse n'explique pas l'âge) ; les frames à analyse
  rapide (`total` ≤ 35 ms, n = 32) ont `age` = 84,8 ms quand les rares frames lentes
  (`total` ≥ 100 ms, n = 3) n'atteignent que 96,7 ms ; et une variante liant seulement
  `Preview + ImageAnalysis` (sans `ImageCapture` ni `VideoCapture(Quality.HD)`) a été compilée et
  mesurée au même protocole — `age` p50 95 ms, `int` p50 34 ms, **aucun gain** : ce n'est pas
  l'application qui retarde la frame. Ces 86 ms ne sont donc pas réductibles depuis elle ; détail
  et protocole au § 10 bis.
- `ENCODE` non plus : **10 ms mesurés** (`enc` p50 + `b64` p50 0, n = 343 sur 3 sessions
  à 30 fps tenus le 01/11/2026), contre 5–8 ms estimés — l'estimation u02 avait été faite
  avant d'inclure le base64. **Écart tranché** : le 27 venait d'un micro-échantillon
  (n = 2, valeurs de fixture des tests unitaires) et le 32 d'une session CPU-saturée
  (`total` p50 74 ms) qui mesurait la contention, pas le JPEG ; ni l'un ni l'autre ne
  survivent aux ~480 lignes réelles (`b64` p50 0, max 1). L'étage reste sensible à la
  charge (p95 11–26 ms selon la session). Avec `CAPTURE`, cela déplace le budget client
  hors attente de 57,6–78,4 ms à 123,6–141,4 ms (§ 4).
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
| Cloud A100 eu-west, 4 concepts | téléphone | 444–578 | 111–144,5 | 234,6–285,9 | **678,6–863,9** | non (+578,6–763,9 ms) |
| Cloud A100 eu-west, 4 concepts | banc filaire r630 | 387–528 | 96,8–132 | 220,4–273,4 | **607,4–801,4** | non (+507,4–701,4 ms) |
| Cloud H200, 8 concepts | téléphone | 394–497 | 98,5–124,3 | 222,1–265,7 | **616,1–762,7** | non (+516,1–662,7 ms) |
| Cloud B200 /live, 1 concept | téléphone | 191–291 | 47,8–72,8 | 171,4–214,2 | **362,4–505,2** | non (+262,4–405,2 ms) |
| Cloud B200 + tracker vidéo 720p | téléphone | 180,7–280,7 | 45,2–70,2 | 168,8–211,6 | **349,5–492,3** | non (+249,5–392,3 ms) |
| Cloud B200 + tracker vidéo 720p | banc filaire r630 | 123,7 | 0–16,7 | 123,6–158,1 | **247,3–281,8** | non (+147,3–181,8 ms) |
| Boîte GPU dans le LAN (4090/5090) | téléphone ↔ LAN | 26–48 | 0–16,7 | 123,6–158,1 | **149,6–206,1** | non (+49,6–106,1 ms) |
| Keyframe cloud + propagation locale (30 fps) | téléphone | 0 | 4–10 | 46,6–55,4 | **46,6–55,4** | **oui** (marge 44,6 ms) |
| Le même, capture mesurée sur appareil (01/11) | téléphone | 0 | 4–10 | 107,6–116,4 | **107,6–116,4** | non (+7,6–16,4 ms) |
<!-- /table:architectures -->

Comment lire, et ce que le modèle ne prouve pas :

- `client hors HTTP` = les étages client qui s'ajoutent à l'A/R, **attente comprise**
  (`CAPTURE + ENCODE + QUEUE + DECODE + COMPOSE + DISPLAY` ; pour les lignes locales, `QUEUE` est
  remplacée par la `PROPAGATION` locale et l'encode par frame disparaît). La formule du tableau est
  donc **`perçu = A/R + client hors HTTP`**, et la colonne `attente` est un **sous-étage** de ce
  client ; elle est affichée à part parce que c'est le seul levier que le choix d'architecture
  déplace.
- **Calibrage** : le modèle suppose les optimisations de la vague 1 appliquées (composition
  0,93 ms, cadence adaptative) mais **plus** l'encode de 5–8 ms ni la capture de 25 ms : ces deux
  étages valent 10 et 86 ms mesurés depuis le 01/11/2026. Les relevés terrain antérieurs restent
  **plus hauts** encore : 1 220–1 260 ms affichés avec 6 frames en vol. L'écart est attribué
  (file d'attente GPU et créneau d'envoi), pas inexpliqué.
- La ligne **banc filaire** est la plus favorable au cloud (plus petit réseau mesuré) : c'est une
  borne basse, pas un scénario produit.
- La ligne **LAN** suppose une carte achetée (décision utilisateur) et 60 fps : elle ne franchit
  plus la cible (149,6–206,1 ms), car capture et affichage y pèsent à eux seuls 102,7 ms
  (86 + 16,7), soit plus que la cible entière. Un réseau local cinq fois plus court ne rattrape pas
  un coût client mesuré.
- La dernière ligne compte plus que les autres : elle remplace l'hypothèse de 25 ms par la capture
  **mesurée**, 86 ms. Le plancher local est donc **86 + 17 ms avant toute propagation** — 103,6 ms
  une fois la composition ajoutée — et la ligne du tableau, propagation comprise, sort à
  107,6–116,4 ms : la cible est ratée de 7,6–16,4 ms. L'architecture locale n'est donc pas
  gratuite, et elle ne tient les 100 ms que parce que la ligne précédente garde, elle, une capture
  de 25 ms **que rien ne mesure**. Faire disparaître l'encode par frame ne suffira pas si le
  pipeline capteur reste à 86 ms — c'est la conclusion du § 2, et c'est elle qui déplace le
  chantier du client vers le capteur.
- **Ce qui n'est pas dans le modèle** : les frames jetées, et le surcoût d'un décodage PNG
  à 12 objets. La gigue est relevée au § 10 (R1 : 75,1 ms) mais le budget reste bâti sur les
  p50 — un budget bâti sur les p50 doit laisser la marge pour les p95.

## 5. Verdict chiffré sur les 100 ms

<!-- table:verdict -->
- plancher d'A/R cloud (banc filaire, serveur le plus rapide mesuré) : 123,7 ms → déjà > 100 ms
- meilleur perçu cloud calculé : 247,3 ms (banc filaire + tracker B200, hypothèse la plus favorable) ; état mesuré aujourd'hui côté téléphone : 678,6–863,9 ms
- propagation locale : 46,6–55,4 ms perçus → **sous 100 ms avec 44,6 ms de marge**
- boîte GPU dans le LAN : 149,6–206,1 ms → franchit la cible en bas de plage, la dépasse en haut
- budget de calcul laissable à un modèle **local sur l'appareil** (u15) : ≤ 55 ms par frame pour tenir 100 ms perçus à 60 Hz
- débit : à 30 fps, tout étage de calcul doit tenir 33,3 ms par frame (sinon des frames sont sautées) — contrainte distincte de la latence
<!-- /table:verdict -->

En une phrase : **la cible de 100 ms n'est atteignable qu'en local**, et à deux conditions — que
l'encode JPEG par frame disparaisse, et que la capture descende sous les 86 ms mesurés. En
propageant sur l'appareil le masque d'un keyframe SAM on obtient 46,6–55,4 ms calculés, mais avec
les 25 ms de capture **supposés** ; dès qu'on met la capture mesurée à la place, le même chemin
coûte 107,6–116,4 ms et rate la cible. La boîte GPU dans le LAN sort désormais à 149,6–206,1 ms :
elle ne la franchit plus. Toute architecture cloud reste au-dessus, non pas à cause de l'inférence
mais du seul A/R : **123,7 ms au plancher absolu** (93 ms de réseau + 30,7 ms de serveur, les deux
meilleurs chiffres mesurés séparément), et 123,6–141,4 ms de coût client mesuré s'y ajoutent.

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
| CAPTURE | ≤ 25 | cible locale **non mesurée** : la campagne du 01/11 mesure 86 ms de pipeline capteur, indépendant de l'application (§ 2) ; la ligne mesurée du § 4 sort à 107,6–116,4 ms, propagation comprise — pari le plus fragile du document |
| PROPAGATION (u10+u11) | ≤ 10 | cible u11 < 8 ms/frame à 512×206 ; 33,3 ms de débit disponible à 30 fps |
| COMPOSE | ≤ 3 | 0,93 ms mesuré (u03) — 3 ms laisse la place aux 12 objets |
| DISPLAY | ≤ 17 | 1 vsync 60 Hz (16,7 ms) ; 90 Hz ou 120 Hz rendrait 5–8 ms |
| Aléas (GC, ordonnancement, reprise d'écran) | ≤ 15 | u03 a retiré 6,8 Mo/frame de pression GC ; le reste est à mesurer |
| **total alloué** | **≤ 70** | **marge 30 ms sur 100** (30 %) |
<!-- /table:budget -->

Règles d'usage :

- un dépassement d'étage **mange la marge** : 70 ms alloués laissent 30 ms, soit 30 % du budget ;
- `CAPTURE` est mesuré à **86 ms** sur le chemin Direct, et la campagne du 01/11 a établi que ce
  délai vient du **pipeline capteur**, pas de l'application (§ 2) : la cible de 25 ms du tableau
  ci-dessus reste une **hypothèse non mesurée**. Le plancher local réel est donc 86 + 17 ms =
  103,6 ms avant propagation, et la ligne mesurée du § 4 sort à 107,6–116,4 ms : la cible est ratée
  de 7,6–16,4 ms. C'est le risque n° 1 du plan local, et ce n'est plus un pari sur l'analyseur ;
- la propagation doit tenir **deux** contraintes distinctes : ≤ 10 ms de latence **et** ≤ 33,3 ms de
  débit par frame à 30 fps (cible < 8 ms/frame à 512×206, ce qui laisse de la place) ;
- le keyframe SAM (A/R 444–578 ms depuis un téléphone) est **hors** du chemin perçu : il ne coûte
  que la fraîcheur du masque (200–500 ms) et ~40–100 Ko/s d'octets montants — négligeable.

## 8. Plan de mesure — qui remplit quel chiffre

| grandeur | mesure manquante | unité | attendu |
|---|---|---|---|
| `ENCODE` (10 ms) : tranché | écart 27/32 résolu (n = 343 nominal, p95 11–26 selon charge) | § 2 | clos le 01/11/2026 |
| `CAPTURE` (86 ms) : p95 et max | percentiles hauts sur n = 38 seulement ; R1 donne p95 477 / p99 573 / gigue 75,1 (N = 323, build combi) | instrument | campagne longue sur master HEAD : n ≥ 1000, p95/p99/jetées (bloquée le 01/11 : téléphone + bail GPU ; protocole et analyseur `lat_stats` prêts) |
| `DECODE`, `DISPLAY` réels, `CAPTURE` local | valeurs terrain (le banc est une simulation) ; `DECODE` instrumenté par la PR #907 (ligne `decsplit`) | instrument | `--capsplit` + `--decsplit` en campagne longue, p50/p95 |
| distribution d'A/R (N = 20, keep-alive, p50/p95) | reproductibilité de l'A/R médian | banc réseau | un banc sans campagne payante |
| propagation locale (ms/frame) | cible < 8 ms à 512×206 | banc JVM | ms/frame par étape de warp |
| part vision / détection du serveur | attribution par soustraction à confirmer ; champ `breakdown_ms` livré par la PR #904 (banc `bench_breakdown`, repli `timings_ms`) | backend | un appel `/live` réel (~1 min GPU après libération du bail) |
| froid / snapshot : temps muraux | boot complet et restauration non remesurés depuis la vague 1 (25–38 s / 2–7 s) | backend | `GET /warm` chronométré après gel 60 s (restore) puis `POST /release` (boot), 2–3 cycles |
| transport M1 binaire : gain mesuré | contrat figé et prouvé byte-exact (16 vecteurs, PR #900 ; harnais A/B PR #912) | instrument | baseline base64 puis jambe binaire, N ≥ 500 chacune, sur `isee-sim` |
| régulateur : réouverture (PR #905) | 83 % → 7,1 % de trames bridées en simulation sur dump N = 1081 | terrain | confirmation en campagne (ips ×~2 attendus) |
| poids montant ⇄ octets ⇄ ms | modèle qualité ↔ latence | transport | tableau octets ↔ ms |
| dérive du masque ⇄ fréquence de keyframe | erreur de reprojection | simulation | courbe erreur ↔ intervalle |

Tant que ces mesures ne sont pas faites, ce budget est **exact en planchers** (mesurés) et
**provisoire en coûts client** (estimés ou hypothèses) — chaque ligne des tableaux le dit.

## 9. Ce que ce document n'affirme pas

- **Hors `CAPTURE`** (§ 2, mesuré sur téléphone le 01/11/2026, n = 38), **`ENCODE`**
  (§ 2, n = 343) et hors § 10, les coûts client sont des simulations ou des estimations
  reprises de la campagne, pas des relevés d'appareil. `DECODE`, `DISPLAY` et la capture
  du chemin local restent des hypothèses.
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

Trois campagnes `ISEE_LAT` (une ligne logcat par frame complète, `LiveLatencyLog`) : la moyenne
et le max portent sur `latency`, les postes sont les moyennes par poste. Les verdicts suivent
la règle de `scripts/parse_isee_lat.py` (`moyenne <= --mean` et `max <= --max`), sous les
seuils par défaut (300 / 500) et sous l'objectif (moyenne < 250 ms, max ≤ 500 ms).
La troisième (R1 Redmi combi, N = 323) est la première à publier p95, p99 et gigue.

<!-- table:mesures -->
| campagne | N | moyenne (ms) | capt | up | srv | rtt | mask | pixel | max (ms) | p95 | p99 | gigue | jetées | 300 / 500 ? | 250 / 500 ? | conditions |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| baseline pré-PR2 | 1257 | 454 | 171 | 38 | 102 | 165 | 5 | 78 | 1677 | — | — | — | — | non (+154 en moyenne, +1177 en max) | non (+204 en moyenne, +1177 en max) | référence avant PR2 |
| post-PR2 | 1500 | 380 | 119 | 38 | 100 | 164 | 1 | 58 | 713 | — | — | — | — | non (+80 en moyenne, +213 en max) | non (+130 en moyenne, +213 en max) | scène vide (0 masque), app à 430 % CPU |
| R1 Redmi combi | 323 | 343 | 151 | 30 | 96 | 107 | 20 | 45 | 722 | 477 | 573 | 75.1 | — | non (+43 en moyenne, +222 en max) | non (+93 en moyenne, +222 en max) | Redmi, combi HAL+wifilock+cb, 01/11/2026 15:47–15:49, serveur sam3-eu-a100 ; 323 chaudes (328 brutes, skip 5) |

Règle des verdicts : celle de `scripts/parse_isee_lat.py` (`moyenne <= --mean` et `max <= --max`, défauts 300 / 500) ; `250 / 500` = objectif (moyenne < 250 ms, max ≤ 500 ms). Les colonnes `p95`, `p99`, `gigue`, `jetées` portent les mesures brutes (« — » = non relevé) ; les seuils `--p95`, `--p99`, `--jitter`, `--drop` du parseur les contrôlent quand ils sont posés.
<!-- /table:mesures -->

Lecture :

- la campagne post-PR2 divise le max par 2,4 (1 677 → 713 ms) et retire 74 ms à la moyenne
  (454 → 380 ms), mais reste au-dessus des deux seuils — moyenne comme max ;
- les postes ne s'additionnent pas à la moyenne : `rtt` (envoi → réponse) recouvre `srv`
  plus la radio et le parse (`LiveTimings.kt`), et chaque moyenne de poste ignore les frames
  où il vaut `—` (`parse_isee_lat.py`) ;
- la campagne post-PR2 est une scène vide (0 masque) avec l'app à 430 % CPU : un contenu
  réel (masques à décoder, § 2 `DECODE`) coûterait plus cher ;
- la campagne R1 (Redmi, combi HAL + verrou Wi-Fi + rappel allégé, 01/11/2026 15:47–15:49)
  ne tient ni 300/500 ni 250/500 : moyenne 343 ms (+93 sur l'objectif), max 722 ms (+222),
  p95 477, p99 573, gigue 75,1 — avec deux réserves : session courte (~109 s à ~13 fps,
  N = 323 < 1000) et SHA exact du build non prouvé (combi-équivalent) ;
- R1 confirme le modèle de file côté client : `up` p50 19 ms = phase caméra (attente ≈
  intervalle ÷ 2), file serveur 0 ms sur tout le dump — et motive la PR #905 (réouverture
  après 5 moyennes sous budget : 83 % → 7,1 % de trames bridées en simulation).

### 10 bis. Campagne `capsplit` du 01/11/2026 — ce qui était mesuré nulle part, et ce que `int` tranche

Le poste `capt` de la sonde (130,9 ms en moyenne) n'était décomposé par aucune sortie : on savait
qu'il coûtait cher, pas *en quoi*. `TimingsText.capsplit` (1 frame sur 20, même dump `ISEE_LAT`)
puis `parse_isee_lat.py --capsplit` répondent. Cette campagne est **archivée** — téléphone
SM-G780G, build `1.0.1139-da6531a`, écran iSee, n = 38 lignes — et le poste `int` livré par la
PR #801 y figure pour la première fois :

| poste | p50 | moyenne | ce qu'il couvre | étage du § 2 |
|---|---:|---:|---|---|
| `age` | 86 | 86,9 | capteur → entrée de l'analyseur (expo, readout, ISP, HAL, file) | `CAPTURE` |
| `enc` | 9 | 11,4 | NV21 → JPEG q65 | `ENCODE` |
| `b64` | 0 | — | base64 + horodatage (sous la milliseconde en p50) | `ENCODE` |
| `cb` | 18 | 22,3 | rappel `onFrame` (profondeur, mouvement, réserve/envoi) | — |
| `total` | 32 | 42,4 | analyse complète du callback | — |
| `int` | 33 | 39,4 | intervalle depuis l'entrée précédente de l'analyseur (**cadence obtenue**) | — |

`age + enc + b64 + cb = 120,8` en moyenne contre 130,9 lus par la sonde : les deux mesures se
rejoignent à 10 ms près ; l'écart tient à l'échantillonnage (1 frame sur 20) et reste non expliqué.

**Le tableau de lecture de `int` n'est plus une spéculation, c'est un résultat : `int` p50 = 33 ms,
première branche.** La caméra livre bien 30 fps et l'application ne l'affame pas. Un `age` de
86 ms vaut donc **2,61 périodes de frame** : ni l'exposition (≤ 33 ms à 30 fps) ni la cadence ne
peuvent l'expliquer. Le délai est en amont de l'analyseur — readout du capteur, ISP, buffers du
HAL. Deux autres faits du même dump ferment la porte à l'hypothèse inverse :

- corrélation `age` ↔ `total` = **+0,18** : la durée de l'analyse n'explique pas l'âge ;
- les frames à analyse **rapide** (`total` ≤ 35 ms, n = 32) ont `age` = **84,8 ms** ; les rares
  frames à analyse lente (`total` ≥ 100 ms, n = 3) n'atteignent que **96,7 ms** — 12 ms de plus
  pour une analyse trois fois plus longue. L'analyseur n'est pas dans la boucle de l'âge.

**Expérience de contrôle — hypothèse réfutée.** Restait la piste « plusieurs flux liés coûtent des
buffers » : une variante ne liant que `Preview + ImageAnalysis` (sans `ImageCapture` ni
`VideoCapture(Quality.HD)`) a été compilée, installée et mesurée avec le même protocole —
`age` p50 **95 ms**, `int` p50 34 ms : **aucun gain**, à peine pire. Ce n'est pas une configuration
livrée, c'est une hypothèse testée puis écartée. La phrase qui compte : **ce n'est pas
l'application qui retarde la frame**, et aucune optimisation du chemin caméra côté client ne peut
être présentée comme un gain sur `CAPTURE`.

Lectures restantes, dans l'ordre d'importance :

- **l'encode pèse 10 ms publiés, pas 5–8 ni 27** : le 27 venait d'un micro-échantillon
  (n = 2, valeurs de fixture des tests unitaires) et le 32 d'une session CPU-saturée ;
  trois sessions nominales à 30 fps tenus (n = 343) donnent `enc` p50 9–10 et `b64` p50 0.
  Le § 2 publie 10 ms, avec un p95 de 11–26 ms selon la charge ;
- **`cb` est résolu, pas seulement mesuré** : les 18 ms en p50 et 67 en p95 venaient de
  l'estimation du mouvement synchrone sur le fil de l'analyseur, sortie depuis (`be6ca9ac`,
  dans master) — sous plein trafic, `cb` tombe à p50 0 / p95 1 (n = 37), et le reste sur
  master est en O(1) sans allocation. Le p95 de 67 reste au tableau comme coût pré-split,
  pas comme coût courant ;
- **le `p95` de `cb` (67 ms) dépassait la période** : c'était du débit perdu, pas de la
  latence ajoutée — la distinction du § 1, et la raison pour laquelle `age` restait stable
  quand l'analyse ralentissait.

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
| cadence adaptative, réserve 1 frame | `LiveSession`, `LivePump` (vague 1) ; puis `Cadence.Direct` régulée (latence/2, 33-900 ms ; plancher 350 ms abandonné) | u01, O8 |
| 7 étages, perçu ≠ A/R, coût d'instrumentation borné | `LatencyProfile`, `LatencyTrace` (vague 1) | u07 |
| 1 220–1 260 ms affichés (6 en vol) · 599–775 ms (1 en vol) | notes de campagne, terrain | terrain |
| R1 Redmi combi : N = 323, moyenne 343 ms, p95 477, p99 573, gigue 75,1, max 722 ms | dump `ISEE_LAT` 01/11/2026 15:47–15:49 (hors dépôt) | R1 |

## Annexe B — ce que cet arbre-ci permet de vérifier

| fait vérifiable ici | où | valeur / conséquence |
|---|---|---|
| transport du Direct | `core/net/LiveClientFactory.kt`, `core/net/WsLiveClient.kt` | **WebSocket** vers `/live/ws`, pas de `POST` par frame |
| gain du WebSocket sur le `POST`, mesuré côté serveur | `core/net/WsLiveClient.kt:13` (commentaire daté) | 50,3 ms contre 81,4 ms — ~31 ms gagnés par frame |
| cadence prudente du Direct | `core/live/LiveSession.kt:35` (`Cadence.Direct`) | 350 / 500 / 900 ms → l'attente réelle est **plus haute** que l'hypothèse du § 4 |
| ce que cet arbre refuse d'affirmer | `docs/PERF.md` § 1 et § 3 | latences, fps, réveil de conteneur : « non mesuré ici » |
| instrument côté client | `core/live/LiveStats.kt`, banc JVM `LiveFrameBudgetTest` | coûts CPU mesurés en microsecondes, hors JPEG et hors radio |
| décomposition du poste `capt` | `core/live/TimingsText.kt` (`capsplit`), `app/isee/IseeCameraPane.kt` | `age` / `enc` / `b64` / `cb` / `total`, 1 frame sur 20, même dump `ISEE_LAT` |
| agrégation de cette décomposition | `scripts/parse_isee_lat.py --capsplit` | moyenne/p50/p95/p99/max par poste, `somme` et écart au `capt` de la sonde |
| contrat de transport binaire M1 | `server/scripts/tests_units/m1_ws_vectors.json` (16 vecteurs) + `docs/LATENCE-M1-VECTEURS.md` | byte-exact des deux côtés (58 checks serveur, 3 JVM) |
| colonnes p95/p99/gigue/jetées du § 10 | `scripts/parse_isee_lat.py`, `scripts/budget_latence.py` | seuils optionnels `--p95/--p99/--jitter/--drop` ; « — » = non relevé |
| analyseur de campagne longue | `scripts/lat_stats.py` | p99/gigue/jetées/DECODE-vs-N + CSV, 14 asserts `--selftest` |
| une hypothèse peut-elle survivre à une mesure ? | `scripts/check-budget-provenance.py` | non : étiquette fermée, « mesuré » daté, contradiction archive ↔ doc = échec |