# Mesures — décision temporelle du feu piéton (vote glissant 3/5)

Date : 2026-11-02. Base mesurée : `origin/master` `aacdcd20` (porte
`PedLightGate`, décision de couleur **par frame**, aucune mémoire).
Code livré : `core/src/main/java/dev/sam3vision/core/crossing/LightTemporalVote.kt`
+ `LightTemporalVoteTest` (17 tests). Banc reproductible :
`~/.scratch/feux-impl/w3/bench/` (`simulate_w3.py`, `bascule_w3.py`,
`policies_w3.py`, `resultats_w3_fr.json`, `resultats_w3_ptl.json`,
`bascule_w3.json`) — dérivé du banc A5 (`~/.scratch/feux-improve/a5/`),
mêmes séquences, mêmes graines, seules les politiques changent.

---

## 1. Le problème, chiffré

Aujourd'hui `PedLightGate.verdict(argb, w, h)` rend vert ou rouge **frame par
frame** : une frame bruitée suffit. Sur les crops propres la porte ne se
trompe jamais (0 faux vert / 29 rouges téléphone). Dès qu'on ajoute la
perturbation d'une vraie séquence, la décision par frame s'effondre, et c'est
**l'intrus** qui porte tout le faux vert :

| scénario | faux verts, décision par frame |
|---|---|
| `calme` (bruit seul) | 4 / 116 séquences rouges |
| `moyen` (piéton 15 %) | 4 / 116 |
| `agite` (piéton 30 %) | 4 / 116 |
| **`voisin`** (feu voiture dans le crop, 30 %) | **47 / 116** |
| **`cumul`** (les deux, 40 %) | **48 / 116** |

Un feu voisin qui entre dans le crop bascule donc **40 % des séquences
rouges** en vert. Le piéton qui passe, lui, ne crée pas de faux vert (sa
fraction rouge reste à 0,000 sur les 20 verts occlus mesurés) : il **détruit**
des verts.

---

## 2. Protocole (banc A5, rejoué tel quel en W3)

- **Données** : 49 crops téléphone FR étiquetés (20 verts / 29 rouges,
  11-48 px, la taille d'entrée réelle du classifieur) ; 410 crops PTL
  (autre pays, MIT, occlusion) en contrôle indépendant.
- **Séquences** : le crop réel est monté ×4, perturbé frame par frame
  (marche aléatoire de la main ±2,5 px, rotation ±1,2°, échelle ±5 %, flou de
  bougé 0-3 px, flou optique, exposition 0,75-1,25, bruit capteur), puis la
  boîte est recadrée et **ramenée à la taille d'entrée d'origine**. Intrus
  optionnels : `person` (silhouette sombre traversante), `neighbour` (un
  **vrai crop de classe opposée** collé au bord), `both`.
- **Volume** : 5 scénarios × 49 crops × 4 graines = **980 séquences /
  8 372 frames** en FR (5-12 frames, 300-600 ms) ; 2 050 séquences PTL.
- **Métriques** : premier instant où la règle dit VERT (*latence verte*
  p50/p90), verts trouvés sur 400 séquences vertes, **faux verts** = séquences
  rouges où un vert a été dit (sur 580), bascules V→R (coût de parole).
  Séparément, `bascule_w3.py` fait passer le feu au rouge **au milieu** du
  burst : on mesure combien de temps le vert est encore affirmé — c'est le
  danger, pas le confort.
- **Parité** : le miroir Python du pipeline est vérifié 31/31 ancres de
  `PedLightGateTest` et reproduit les chiffres de référence du dépôt
  (20/20 verts, 0 faux vert FR).

---

## 3. Table Pareto — latence vs faux verts vs verts perdus

980 séquences FR (400 vertes, 580 rouges). « p50 / p90 » = latence du premier
vert, en ms depuis la première frame.

| règle | verts trouvés | perdus | lat. p50 | lat. p90 | faux verts | % séq. rouges | bascules V→R |
|---|---|---|---|---|---|---|---|
| `per-frame (master)` | 393/400 | 7 | 57 ms | 107 ms | 107 | 18,4 % | 486 |
| vote majoritaire k=3 | 385/400 | 15 | 162 ms | 268 ms | 43 | 7,4 % | 107 |
| vote majoritaire k=5 | 371/400 | 29 | 264 ms | 433 ms | 16 | 2,8 % | 31 |
| somme pixels k=3 T=80 px | 358/400 | 42 | 65 ms | 203 ms | 43 | 7,4 % | 75 |
| moyenne fractions k=3 θ=0,020 | 344/400 | 56 | 162 ms | 267 ms | 44 | 7,6 % | 60 |
| hystérésis k=3 θhi=0,020 θlo=0,010 | 345/400 | 55 | 108 ms | 182 ms | 45 | 7,8 % | 29 |
| 3 vertes **consécutives** | 347/400 | 53 | 189 ms | 331 ms | 11 | 1,9 % | 169 |
| 3/5 glissant, sortie 1 frame | 371/400 | 29 | 180 ms | 295 ms | 16 | 2,8 % | 201 |
| 3/5 glissant, sortie 2 frames (A5) | 371/400 | 29 | 180 ms | 295 ms | 16 | 2,8 % | 37 |
| 4/6 glissant, sortie 2 frames (W3) | 355/400 | 45 | 240 ms | 371 ms | 10 | 1,7 % | 22 |
| 2/3 glissant, sortie 2 frames (W3) | 385/400 | 15 | 117 ms | 197 ms | 40 | 6,9 % | 74 |
| **3/5 glissant + rouge prouvé court-circuit, sortie 2 frames (W3)** | **370/400** | **30** | **180 ms** | **295 ms** | **15** | **2,6 %** | **36** |

Latence de la règle livrée : **+123 ms p50** (57 → 180) pour **−85 % de faux
verts** (107 → 15). Le p90 double (107 → 295 ms) : c'est le prix assumé, la
fenêtre doit voir trois accords.

### 3.1 Front de Pareto

Objectif : minimiser (latence p50, faux verts) sous contrainte verts perdus
≤ 30/400.

```
faux verts
  107 |● per-frame                                    (57 ms)
   43 |   ● vote k=3 / somme px / moyenne (117-162 ms)
   16 |        ● vote majoritaire k=5                  (264 ms)
   15 |        ★ 3/5 + rouge prouvé (W3)               (180 ms)  <= LIVRÉ
   11 |           ● 3 consécutives                     (189 ms, 53 verts perdus)
   10 |              ● 4/6 + rouge prouvé              (240 ms, 45 verts perdus)
      +------------------------------------------------> latence p50
```

Le point livré **domine le vote majoritaire k=5** : même preuve (3 accord
dans une fenêtre de 5), **−84 ms**, et il ajoute le rouge réflexe que k=5
n'a pas. Il n'existe aucun réglage mesuré qui fasse mieux sur les deux axes
sans payer plus de 30 verts perdus.

### 3.2 Pourquoi glissant et pas consécutif

À faux verts comparables, l'accord **consécutif** coûte 53 verts sur 400 (un
intrus isolé au milieu d'un vert remet le compteur à zéro) — et 70 de plus que
le glissant en PTL, où les feux sont partiellement occlus. Le glissement à
3 sur 5 absorbe un intrus isolé sans rien retarder : c'est exactement le cas
mesuré sur `voisin`/`cumul`.

### 3.3 Pourquoi un seuil de preuve en pixels est rejeté

Un seuil absolu de fraction verte (θ = 0,02) perd 10 à 12 verts sur 80 **dès
le scénario calme** : 2 % d'un crop de 11 px et 2 % d'un crop de 48 px (facteur
19 en surface) ne veulent pas dire la même chose. La preuve doit être
l'**accord du verdict**, jamais une magnitude de pixels.

---

## 4. Règle retenue et seuils

```
preuve verte = la frame dit vert          (`CrossingState.Green`)
rouge prouvé = fraction rouge ≥ 0,02       (`PedLightGate.RED_MAX_FRACTION`)

frame avec rouge prouvé  -> ROUGE immédiat, fenêtre vidée
sinon, si état == VERT   -> ROUGE après `exitFrames` = 2 frames non vertes
sinon, si 3 vertes parmi les 5 dernières -> VERT
```

| seuil | valeur | pourquoi cette valeur |
|---|---|---|
| `window` | **5** frames | à 12-15 fps, ≈ 300-400 ms de mémoire ; sous 5, un intrus isolé pèse trop (2/3 → 40 faux verts) |
| `needGreen` | **3** | majorité stricte de la fenêtre : absorbe 1 intrusion sur 5, refuse 2 verts sur 5 |
| `exitFrames` | **2** | 1 frame sort plus vite (4/80 « dit encore vert ») mais clignote (201 bascules) ; 2 → 36 bascules, p90 de sortie 53 ms |
| `redProven` | **0,02** de pixels rouges | seuil déjà en place dans la porte : un vert admis par la porte ne peut pas le franchir, et un piéton qui passe n'allume aucun rouge (0,000 mesuré) |

Coût : **O(1)** par frame, un anneau de `window` booléens, aucune horloge,
aucune allocation. Déterministe (testé). Latence exprimée en **frames**, pas
en ms : la règle est identique à 10 comme à 30 fps, seule la latence perçue
change (180 ms à 15 fps, 100-150 ms à 20-30 fps).

### 4.1 Le rouge reste réflexe (80 bascules réelles)

Le feu passe au rouge au milieu de la séquence. Ce que chaque règle affirme
**après** la bascule :

| règle | rouge p50 | rouge p90 | dit encore VERT |
|---|---|---|---|
| per-frame | 0 ms | 0 ms | 5/80 |
| vote majoritaire k=5 | 85 ms | 117 ms | 74/80 |
| 3/5 glissant, sortie 2 frames (A5) | 0 ms | 53 ms | 29/80 |
| **W3 (livré)** | **0 ms** | **53 ms** | **29/80** |
| W3, sortie 1 frame | 0 ms | 0 ms | 4/80 |

Sans le court-circuit du rouge prouvé, une fenêtre glissante garde
mécaniquement les frames d'avant la bascule : 74 séquences sur 80 continuent
d'affirmer « vert » pendant 85 ms après que le feu est rouge. Le booléen
`redProven` rétablit un rouge **instantané** sans rien retirer à la robustesse
du vert. W3 va un cran plus loin que A5 : la fenêtre est vidée à **chaque**
rouge prouvé (A5 ne la vidait qu'en sortant d'un vert), ce qui interdit
définitivement une entrée en vert construite autour d'un rouge — d'où 15 faux
verts au lieu de 16, et un vert perdu de plus.

---

## 5. Contrôle indépendant PTL (410 crops, 2 050 séquences)

| règle | verts | faux verts |
|---|---|---|
| per-frame | 573/920 | 144 (15,7 %) |
| vote majoritaire k=5 | 516/920 | 78 (8,5 %) |
| **W3 (livré)** | **505/920** | **78 (8,5 %)** |
| 4/6 + rouge prouvé | 450/920 | 71 (7,7 %) |

Même classement que sur le corpus FR, autre pays : la règle ne dépend pas du
corpus. Elle coupe les faux verts de **46 %** en PTL.

---

## 6. Limites

1. **Séquences synthétiques** : les frames sont des crops réels perturbés
   (photométrie fidèle, géométrie d'intrus approximative, caméra supposée fixe
   hors gigue). Aucun burst réel n'existe dans les données.
2. **Mesure censurée** : un « vert perdu » est un vert non dit dans les
   300-600 ms du burst ; la sonde tourne en continu, le burst suivant rattrape.
   30/400 est donc un **majorant** du coût réel.
3. **Le crop `lot1/0007.png`** (feu rouge avec beaucoup de ciel à la frontière
   de teinte 195°) produit à lui seul une douzaine de faux verts : c'est un
   défaut de la couche couleur, pas du temps. Hors ce crop, la règle est à
   0,7 % de séquences rouges faussement vertes (contre 15,3 % par frame).
4. **Le clignotant du vert** n'est plus visible dans l'état voté (un vert qui
   clignote reste un vert 3/5). Le câblage devra passer l'état **brut** à
   `CrossingAnnouncer.blinkDetected()` en plus de l'état voté.
5. **Latence en frames**, non horodatée : pas de borne en ms. Si le parent en
   veut une, il faut un anneau de `(preuve, atMs)` — même coût, non fait.
