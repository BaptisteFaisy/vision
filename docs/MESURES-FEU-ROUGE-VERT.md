# Feux piétons rouge / vert — état des mesures au 02/11/2026

Branche `fix/feux-p1-rouge-vert` (6 commits, `2f5f4f10` → `38758b20`),
rebase de `fix/feux-p0-rouge-vert` (`ba024227` → `27069598`).
Cette page répond à cinq questions et **chaque chiffre porte sa source**
(rapport d'axe `~/.scratch/feux-improve/a<N>/RAPPORT.md`, rapport de lot
`~/.scratch/feux-impl/w<N>/RAPPORT.md`, ou code du dépôt avec `fichier:ligne`).
Aucun chiffre n'est repris sans fichier où le vérifier : ce qui n'était pas
mesuré est écrit « non mesuré ».

Corpus nommés (inventaire complet : `a1/RAPPORT.md` §2) :

| nom | n | contenu | échelle |
|---|---|---|---|
| `phone48` | 49 (20 V / 29 R) | crops téléphone FR (lot1 18 + lot2 3 + ub8 28) | 48 px |
| `ancres33` | 33 (22 V / 11 R) | `core/src/test/resources/feuxfr{,2,3}` | 48 px |
| `lotfr_vert` / `lotfr_rouge` | 183 / 473 | Panoramax FR jour, triés un par un | 48 px |
| `lotfr_neg_voiture` | 1 492 | disques de voiture | 48 px |
| `jitter48` / `jitter96` | 280 (240 V / 40 R) | 9 photos, boîtes jittées, **mêmes boîtes** | 48 / 96 px |
| `ptl_val` | 470 | Shanghaï, labels = noms de fichiers (bruités) | 48 px |
| `ampel` | 3 587 valides | Allemagne (MIT), 96 % nocturne | 48 px natif |

---

## 0. Résumé

1. **La couleur n'est plus le goulot** : sur les 183 verts FR jour triés un par
   un, la couleur ne bloque que **1 crop sur 183** ; **49 des 50 manques sont la
   porte** (géométrie) — `a1/RAPPORT.md` §4.1 et §6(i).
2. Sur le corpus **de production** (49 crops téléphone), couleur *et* porte sont
   au plafond : **20/20 verts, 0 faux vert**, et **aucune règle de la porte ne
   mord** — `a1/RAPPORT.md` §5.1.
3. Mais **tout le corpus étiqueté est à 48 px** alors que la prod découpe à
   96 px : sur les mêmes boîtes, **83 des 280 crops (29,6 %)** changent de
   verdict et **22 rouges sur 40** deviennent verts — `a1/RAPPORT.md` §7.
4. Le code livré attaque donc des causes **hors seuils de couleur** : crop non
   déformé, sélection de boîte par flux de lentille, vote 3/5, preuve d'absence,
   projection chromatique. Effets mesurés en §2.
5. Quatre pistes ont été **mesurées négatives** et sont à ne pas refaire (§4).

---

## 1. Où sont les erreurs (a) couleur / porte / détection / échelle

### 1.1 La répartition, par corpus

`a1/RAPPORT.md` §4 attribue chaque erreur à une cause : **détection** = pas de
boîte ; **porte** = l'arbre dit vert et `acceptGreen` refuse ; **couleur** = pas
assez de pixels verts ; **échelle** = le verdict dépend du rendu.

| corpus | rappel vert | faux vert | cause dominante des manques |
|---|---|---|---|
| `phone48` | 20/20 | 0/29 | aucune (plafond) — `a1` §5.1 |
| `ancres33` | 22/22 | 0/11 | aucune |
| `lotfr_vert` (183) | 133/183 = 72,7 % | — | **49/50 porte**, 1/50 couleur — `a1` §4.1 |
| `lotfr_rouge` (473) | — | 1/473 = 0,21 % | couleur (cyan du fond) — `a1` §4.2 |
| `lotfr_neg_voiture` | — | 514/1 492 = 34,4 % | attendu : le `CarHousingVeto` agit en amont — `a1` §3 |
| `jitter96` | 201/240 = 83,8 % | **22/40 = 55 %** | échelle + largeur de bande — `a1` §7 |
| `ptl_val` | 127/238 = 53,4 % | 12/232 = 5,2 % | labels faux (24 verts nommés contiennent un rouge franc) — `a1` §3 |

Les 34,4 % de faux verts sur disques de voiture **ne sont pas une régression** :
depuis l'ordre « détection au plus large » du 01/11, la sûreté vient de
`CarHousingVeto` (h/w ≥ 3,0) en amont, pas de la couleur (`a1` §3).

### 1.2 Couleur : deux mécanismes distincts, tous deux au bord

- **Verts manqués** : plancher `green_frac < 0,002` (`PedLightGate.kt:60`) —
  1 crop sur 183 en FR jour, 0 sur 49 en téléphone (`a1` §6(i)). Descendre le
  plancher à 0,0005 ne gagne **aucun** vert sur les 183 et coûte 10 faux verts
  sur `jitter96` (`a1` §6(i), table).
- **Faux vert FR** : 1/473 seulement. Signature mesurée : les 47 pixels
  « verts » sont **47/47 à teinte ≥ 180°** et le bonhomme est rouge sombre
  (330-345°, sous le seuil rouge 345°) : c'est le **cyan du fond** qui passe
  (`a1` §4.2). Ce n'est pas la porte, c'est la bande de teinte.

### 1.3 Porte : ce que chaque règle coûte et rapporte

Ablation sur FR jour, arbre forcé à `Green`, base = porte de master
(`a1/RAPPORT.md` §5.2) :

| configuration | verts /183 | faux verts /473 | faux verts voiture /1492 |
|---|---|---|---|
| base | 133 | 1 | 514 |
| −`WIDE_FILL_MAX` | 143 (+10) | 1 | 697 (+183) |
| −`EDGE_MAX` | 141 (+8) | 1 | 518 (+4) |
| −`R1B_BOTTOM_FLOOR` | 140 (+7) | 1 | 515 (+1) |
| −`BBOX_MIN_PX` | 136 (+3) | 1 | 532 (+18) |
| −`TOP3_MAX` | 135 (+2) | 1 | 516 (+2) |
| −`RED_MAX_FRACTION` | 134 (+1) | 2 (+1) | 522 (+8) |
| −`CLOUD_ELONGATION_MIN` | 133 (0) | 1 | 516 (+2) |
| **porte hors circuit** | 182 (+49) | 6 (1,3 %) | 792 (53 %) |

Trois enseignements : `WIDE_FILL_MAX` est le meilleur marché déjà pris
(10 verts pour 183 disques) ; `R1B_BOTTOM_FLOOR` est le levier « presque
gratuit » (+7 pour +1 voiture) ; `CLOUD_ELONGATION_MIN` est une **règle morte**
(0 vert, +2 voiture). Sans porte, 49 verts reviennent mais le faux vert piéton
passe à 1,3 % et le faux vert voiture à 53 % : la porte reste justifiée.

Sur `phone48`, **aucune règle ne mord** : 18 des 20 verts sortent par le chemin
court (`bottom ≥ 0,35`, `GREEN_BOTTOM_MIN`), 2 par la branche R1b, et les 29
rouges sont arrêtés au plancher vert avant la porte (`a1` §5.1).

### 1.4 Détection : le point structurel des photos

Niveau photo, SAM 3.1 réel, boîte au meilleur score (`a1` §6(iii)) :
**6/7 verts = 85,7 %**, 0 faux vert sur les 2 rouges étiquetés. Le vert manquant,
`kr_green_close`, est un **bandeau LED au sol** : aucune boîte sur 7 prompts et
2 seuils — aucun réglage de couleur ne le récupère. Deux erreurs de **sélection**
subsistent hors étiquettes (`fr_vichy_red` → Green, `fr_vichy_green` → Red),
non arbitrées faute de vérité (`a1` §6(iii)).

### 1.5 Échelle : voir §3

---

## 2. Ce qui a changé dans le code, et ce que ça a donné

### 2.1 Les six commits

| commit | contenu | lot source |
|---|---|---|
| `ba024227` | `PedLightColor` décide par projection signée ; `PedLightGate` lit la même projection ; plancher de largeur `GREEN_MIN_WIDTH_FRACTION = 0.05` | W4, W5 |
| `d1c5fa4b` | `CrossingCropScale` (crop non déformé), `LightTemporalVote`, `LightEvidenceGate` | W1, W3, W6 |
| `2fc8497d` | `VoiceBus.post` rend un Boolean, `rearm` rejoue une phrase refusée, veto trafic jusqu'au verdict (le verdict reste au cran **100**, `CrossingVerdict.kt:23`) | W7 |
| `4d467d48` | tuning capteur branché, compteur d'écrêtage, gouverneur EV sur la boîte, AWB/AE (drapeau éteint) | W8 |
| `80786462` | `LampLens` : la boîte du feu se choisit au **flux de lentille**, plus au score SAM | W2, A4 §9.1 |
| `27069598` | câblage : vote 3/5 et preuve d'absence dans `CrossingVerdictEngine` | W3, W6 |

Les SHAs ci-dessus sont ceux de la branche p0. Après rebase sur `ff01d04d`,
les six commits de `fix/feux-p1-rouge-vert` sont `2f5f4f10` (= `ba024227`),
`909cb2f5` (≈ `d1c5fa4b`), `a8118300` (≈ `2fc8497d`), `d2d013b4`
(= `4d467d48`), `ad0bf6eb` (≈ `80786462`) et `38758b20` (≈ `27069598`) ;
trois ont été réappliqués avec résolution de conflit (`909cb2f5`,
`a8118300`, `38758b20`) — les mesures de ce document valent pour les deux
lignes, le contenu de code des fichiers mesurés est identique.

### 2.2 Avant / après, corpus par corpus — même harnais JVM des deux côtés

`w4/RAPPORT.md` §2, harnais compilé une fois sur `aacdcd20`, une fois sur le
patch ; « porte » = `PedLightGate.verdict`, « classifieur » =
`PedLightColor.classify`. Le corpus `ptl` de W4 est le dossier `test`
(410 crops : 184 V / 226 R), **distinct** du `ptl_val` de `a1` (470 crops).

| corpus | règle | avant (bornes de teinte) | après (projection) | Δ |
|---|---|---|---|---|
| `ancres33` | porte | 22/22 V, 0/11 R | 22/22 V, 0/11 R | **0** |
| `ancres33` | classifieur | 16/22 V | 22/22 V | +6 |
| `phone48` | porte | 20/20 V, 0/29 R | 20/20 V, 0/29 R | 0 |
| `phone48` | classifieur | 11/20 V | **20/20 V** | +9 |
| `jitter48` | porte | 140/240 V, 0/40 R | **160/240 V**, 0/40 R | **+20** |
| `ptl` (410 crops) | porte | 103/184 V, 14/226 R | 99/184 V, 14/226 R | −4 V |
| fenêtres hors domaine (39 724) | dominance verte | 190 fenêtres sur les négatifs | **62** | −128 |
| 42 verts, gain 1,45 + écrêtage | porte | **36/42** | **42/42** | +6 |

Le gain dur est la dernière ligne : une lampe qui blanchit sous le soleil garde
une projection mesurable là où sa teinte HSV n'existe plus (`w4` §4 ;
`a8/RAPPORT.md` §TL;DR 7 annonçait 34/40 → 40/40 sur un décompte voisin).

### 2.3 Effet du plancher de largeur (invariance d'échelle)

`w5/RAPPORT.md` §3, miroir Python, règle `GREEN_MIN_WIDTH_FRACTION = 0.05`
ajoutée à la porte de master :

| corpus | échelle | n | verts | faux verts |
|---|---|---|---|---|
| `ancres33` | 48 | 33 | 22 → 22 | 0 → 0 |
| `phone48` | 48 | 49 | 20 → 20 | 0 → 0 |
| `jitter96` | 96 | 280 | 201 → 201 | **22 → 0** |
| `lotfr_vert` | 48 | 183 | 133 → 132 | — |
| `lotfr_rouge` | 48 | 473 | — | 1 → 1 |
| `ptl_val` | 48 | 470 | 127 → 126 | 12 → 11 |
| `jitter48` (hors périmètre) | 48 | 280 | 140 → 130 | 0 → 0 |

La règle **supprime 22 faux verts sur 22 sans perdre un vert** à 96 px. Le seuil
tombe entre 4 et 7 px (faux verts 1-4 px, vrai vert le plus serré 7 px, p10 12,
médiane 30) — `w5` §1. Les pertes assumées sont listées nommément en `w5` §4.
Les 10 verts perdus sur `jitter48` sont un **artefact de rendu** : les mêmes
boîtes à 96 px ont un nuage de 11 à 13 px et passent (`w5` §4).

### 2.4 Vote temporel 3/5

`w3/RAPPORT.md` « Mesures », banc A5 rejoué (980 séquences FR / 8 372 frames) :

| règle | verts | faux verts | latence p50 |
|---|---|---|---|
| par frame (master) | 393/400 | 107/580 = 18,4 % | 57 ms |
| **3 verts sur 5, rouge prouvé immédiat** | **370/400** | **15 = 2,6 %** | **180 ms** |

Soit **−86 % de faux verts** pour **+123 ms p50** ; bascule réelle vert→rouge :
rouge p50 0 ms / p90 53 ms (`w3`). Contrôle indépendant PTL (2 050 séquences) :
**505/920 verts, 78 faux verts (8,5 %)** contre 144 (15,7 %) — `w3`.
Écart avec `a5/RAPPORT.md` §3.2 (371/400, 16 faux verts) : la règle livrée vide
la fenêtre à **chaque** rouge prouvé, ce qui coûte 1 vert et 1 faux vert (`w3`,
écart n°3). Les deux chiffres sont exacts, ils ne mesurent pas la même règle.

### 2.5 Sélection de boîte par flux de lentille

`a4/RAPPORT.md` §9.1 : top-1 = **5/7** verts, **top-2 = 6/7**, tous les
candidats = 6/7, **31/31 ancres** (0 faux vert sur les évaluables :
`~/.scratch/feux-improve/SYNTHESE.md`, ligne A4). Deux boîtes suffisent, aucun
élargissement de `selectLightBoxes`. Coût de `LampLens.flux` : **21-50 µs** par
crop de 32-48 px, allocation unique `4·n + 16` octets (`w2` §2).

### 2.6 Preuve d'absence

`w6/RAPPORT.md` §3 (sorties tracées, pas déduites) : 1 à 3 frames sans boîte →
`Unknown` (donc jamais « Cross. ») ; **4 frames** sans aucune boîte *et* contexte
de passage → `Absent` ; un feu vu puis perdu reste `Unknown` ; vert/rouge
alternés ne donnent **jamais** `Green`. La tenue de 700 ms
(`LightEvidenceGate.GREEN_HOLD_MS`) est **désactivée au câblage** :
`CrossingVerdictWiring.kt:81` construit la porte avec
`minGreenFrames = 1, greenHoldMs = 0L`, la tenue étant déléguée au vote 3/5
(commentaire de la ligne, mesure A5). La valeur 700 ms reste donc **non
mesurée en rue** (`w6` §5).

### 2.7 Caméra

`w8/RAPPORT.md` §2 : seuil « cramé » **2,0 %** du crop, sortie **0,6 %**
(hystérésis ×3), plancher EV **−2 crans**, verrou AWB relâché 3 s après la
dernière boîte. Écrêtage mesuré sur les 13 crops `feuxfr3` : 0 → 4,84 %,
moyenne **1,08 %** (`w8` §2 ; `a6/RAPPORT.md` donnait 1,23 % sur un décompte
légèrement différent). **Aucun chiffre de gain sur appareil** : la recette
`docs/RECETTE-FEU-EXPOSITION.md` (45 min) n'a pas été passée.

---

## 3. Le trou de mesure : 48 px étiqueté contre 96 px en production

`CrossingPixels.MAX_EDGE = 96` (`CrossingCropScale.kt:25`, source unique depuis
`d1c5fa4b`). **Aucun corpus étiqueté n'existe à cette échelle** : `phone48`,
`lotfr-jour`, PTL et les 33 ancres sont tous ≤ 48 px (`a1` §2 et §7).

Mêmes 280 boîtes, même graine, seul le rendu change (`a1` §7) :

| | 48 px | 96 px |
|---|---|---|
| verts | 140/240 (58,3 %) | **201/240 (83,8 %)** |
| faux verts | 0/40 | **22/40 (55 %)** |
| accord des verdicts | — | 197/280 = **70,4 %** |
| bascules | — | rouge→vert **83**, vert→rouge **0** |

**29,6 % des crops ne rendent pas le même verdict selon l'échelle.** Cause : la
porte contient des règles **en pixels** — `BBOX_MIN_PX = 3`
(`PedLightGate.kt:112` sur un profil de lignes), `EDGE_MAX`, `TOP3_MAX`. À 48 px
`BBOX_MIN_PX` attrapait les 22 bandes fautives **par accident** ; à 96 px elle
ne mord plus (`a1` §4.3). Les 22 faux verts sont des bandes de **1 à 4 px** de
large (teinte 150-165°, 22/22, une seule photo : `ru_moscow_03`), les 201 vrais
verts ont une boîte verte de **7 px minimum** (`a1` §7).

Preuve indépendante que le 48 px ne mesure pas le domaine : `at_ampelpaerchen_11`
a une tache verte de **13 px à 96** et de **1 px à 48** — le rééchantillonnage
LANCZOS délave le vert sous le plancher de saturation (`w5` §1). Symétriquement,
`w10/RAPPORT.md` §7 rappelle que les crops FR passés à 96 px par ×2 bilinéaire
sont un **proxy**, pas un échantillon natif : le faux vert `lot1/0007` à 96 px
est à reconfirmer sur un crop natif.

Conséquences pratiques :

1. Toute calibration de seuil faite sur 48 px est faite **hors du domaine de
   déploiement** (`a1` §8 point 4).
2. La seule règle écrite en **fraction** aujourd'hui est la largeur du nuage
   vert (`GREEN_MIN_WIDTH_FRACTION`), précisément pour cette raison (`w5` §1).
3. `BBOX_MIN_PX` reste la dernière règle purement en pixels, **dette nommée et
   non payée** (`w5` §1 et §6) : 11 des manques FR jour viennent d'elle.

---

## 4. Ce qui a été mesuré NÉGATIF — ne pas refaire

| piste | mesure | source |
|---|---|---|
| **Élargir la teinte verte vers 55-70°** | fenêtres « vertes » hors domaine **126 → 451** (+325), dont négatifs 98 → 349 ; dominance verte des négatifs 0,063 → 0,194 ; **0 vert FR gagné**. Le piège est reproduit à l'identique par `p ≥ 0,30` : un pixel à 72° tombe à `p = +0,20` et sort **rouge** | `a8` §5 (table) et §6 ; « 0 vert FR gagné » : `SYNTHESE.md`, « À ne pas refaire » |
| **Réentraîner l'arbre** | un seul trait, `green_frac`, sépare déjà **49/49** crops FR (AUC **1,000**) ; le GBM 60×3 sur les 17 traits donne 20/20, 0/29 — **identique à la baseline** — et perd des ancres (**27 à 30/31** selon la table du rapport) ; 34 traits : 1 faux vert. Gain du modèle appris *dans la porte* : **exactement nul** | `a3` §Résumé et §« Pouvoir séparant univarié » |
| **Veto de forme** (disque/isotropie) | meilleur réglage : **−29 verts PTL pour −3 faux verts retirés** ; le `WIDE_FILL_MAX = 0,35` de master fait déjà le tri à meilleur prix | `a4` §7 (table) |
| **Descendre le plancher `green_frac`** | 0,002 → 0,0005 : **0 vert gagné sur 183**, +5 verts sur `jitter96` mais **+10 faux verts** ; à 0,02 on perd 26 verts FR jour | `a1` §6(i) |
| Recadrer sur la lentille / lire la couleur sur la lentille | casse une ancre (`a4` §3 et §9.3) ; `PedLightGate` teste des positions relatives au cadre | `a4` §9.3 |
| `CONTROL_AE_LOCK` | mesuré nuisible le 01/11, reste exclu | `a6` §2 ; `w8` §2 |
| Se fier à `~/Bureau/.scratch/feux-hack/kit/tools/light_features.py` | **périmé** : `THRESHOLD=0.0048`, `R1B_BOTTOM_FLOOR=0.20`, `EDGE_MAX=0.72`, pas de `WIDE_FILL_MAX` ; prendre `tools/light_features.py` du dépôt | `a1` §2 |

Deux « négatifs » sont en fait des **coûts assumés**, pas des échecs : PTL perd
4 verts avec la projection (hors domaine, `w4` §2), et une lampe sombre sur fond
clair ne vote plus (le cœur brillant est le fond) — aucun crop des quatre corpus
n'est dans ce cas, mais c'est structurel (`w4` §7).

---

## 5. Rejouer les mesures

Interpréteur des bancs Python : **`~/pedlight/.venv/bin/python`**. Les bancs JVM
tournent sans Gradle (`kotlin-compiler-embeddable` 2.2.21 du cache, recette
`~/.scratch/feux-improve/a3/ktcp.sh`).

| mesure | harnais | commande |
|---|---|---|
| matrice de confusion, ablation de la porte, plafonds, bascule 48/96 | `~/.scratch/feux-improve/a1/bench_all.py` → `bench_all.json`, re-rendu `jitter96.py` | `~/pedlight/.venv/bin/python ~/.scratch/feux-improve/a1/bench_all.py` |
| projection signée, avant/après par corpus, exposition | `~/.scratch/feux-impl/w4/build.sh` + `harness/W4Harness.kt` (hors dépôt) | `bash ~/.scratch/feux-impl/w4/build.sh` puis `java -cp out/classes:$STD dev.sam3vision.core.crossing.W4HarnessKt corpus manifest.tsv` |
| plancher de largeur, non-régression | `~/.scratch/feux-impl/w5/measure.py` → `w5_measure.json` | `cd ~/.scratch/feux-impl/w5 && python3 measure.py --json w5_measure.json` |
| vote temporel (séquences synthétiques) | `~/.scratch/feux-impl/w3/bench/` (`simulate_w3.py`, `bascule_w3.py`, `policies_w3.py`) | `~/pedlight/.venv/bin/python simulate_w3.py` (dossier `bench/`) |
| sélection par flux de lentille | `~/.scratch/feux-improve/a4/lens.py`, `eval_select.py` ; parité Kotlin `~/.scratch/feux-impl/w2/ref_lens.py` | `~/pedlight/.venv/bin/python ref_lens.py <crops…>` |
| transfert Ampel DE → FR, borne de Wilson | `~/.scratch/feux-impl/w10/w10_transfer.py`, `w10_qualite.py`, `w10_diag.py` | `~/pedlight/.venv/bin/python w10_transfer.py --out w10-mesures.json` |
| parité miroir Python ↔ Kotlin (33 ancres) | `tools/test_train_light.py` du dépôt | `python3 tools/test_train_light.py` |
| tests JVM du paquet `crossing` | `~/.scratch/feux-impl/w4/test.sh`, `w5/run-tests.sh`, `w3/run-tests.sh`, `w6/verif/` | `bash test.sh` (299 tests en `w4`) |
| taille des fichiers/fonctions | `tools/check-kotlin-limits.py` | `python3 tools/check-kotlin-limits.py .` |

Les scripts A8 (`a8/mirror/`) et A2 ne sont pas le même harnais que W4 : ils
comptent d'autres crops et donnent, pour l'« avant », 124/240 verts jitter
(A8) contre 140/240 (W4, `w4` §2). Comparer **avant/après dans une seule
colonne**, jamais entre colonnes de harnais différents.

---

## 6. Constantes vérifiées dans le code au 02/11/2026

Vérifiées par `grep` dans `core/src/main/java/dev/sam3vision/core/crossing/` ;
toute valeur citée dans cette page existe à la ligne indiquée.

| constante | valeur | fichier:ligne |
|---|---|---|
| `CrossingCropScale.MAX_EDGE` | 96 | `CrossingCropScale.kt:25` |
| `PedLightGate.GREEN_BOTTOM_MIN` | 0,35 | `PedLightGate.kt:48` |
| `PedLightGate.RED_MAX_FRACTION` | 0,02 | `PedLightGate.kt:51` |
| `PedLightGate.GREEN_MIN_FRACTION` | 0,002 | `PedLightGate.kt:60` |
| `PedLightGate.ELONGATION_MIN` | 1,0 | `PedLightGate.kt:68` |
| `PedLightGate.WIDE_FILL_MAX` | 0,35 | `PedLightGate.kt:85` |
| `PedLightGate.GREEN_MIN_WIDTH_FRACTION` | 0,05 | `PedLightGate.kt:109` |
| `PedLightGate.BBOX_MIN_PX` | 3 | `PedLightGate.kt:112` |
| `PedLightGate.TOP3_MAX` | 0,38 | `PedLightGate.kt:115` |
| `PedLightGate.EDGE_MAX` | 0,75 | `PedLightGate.kt:123` |
| `PedLightGate.CLOUD_ELONGATION_MIN` | 1,0 | `PedLightGate.kt:126` |
| `PedLightGate.R1B_BOTTOM_FLOOR` | 0,15 | `PedLightGate.kt:136` |
| `PedLightColor.GREEN_PROJECTION_MIN` | +0,30 | `PedLightColor.kt:58` |
| `PedLightColor.RED_PROJECTION_MAX` | −0,50 | `PedLightColor.kt:61` |
| `PedLightColor.BRIGHT_BAND_PERCENT` | 80 | `PedLightColor.kt:64` |
| `PedLightColor.MIN_VALUE_BYTE` | 77 | `PedLightColor.kt:67` |
| `PedLightColor.MIN_BAND_PX` | 6 | `PedLightColor.kt:70` |
| `PedLightColor.MIN_GREEN_PX` | 3 | `PedLightColor.kt:73` |
| `PedLightColor.MIN_GREEN_FRACTION` | 0,005 | `PedLightColor.kt:76` |
| `PedLightColor.MARGIN_RATIO` | 2,5 | `PedLightColor.kt:79` |
| `PedLightColor.GREEN_HUE_MIN/MAX_DEG` | 80 / 195 (traits seulement) | `PedLightColor.kt:85-86` |

Écarts constatés avec d'autres pages, **signalés et non corrigés ici** :

- `docs/CALIBRAGE-FEU.md:54` et `docs/MESURES-SAM3-FEUX.md:124` citent
  `ELONGATION_MIN = 1.5f` ; le code porte **1,0**. Les deux passages datent du
  29/10 et décrivent la mesure de l'époque — l'historique n'est pas faux, mais
  la valeur n'est plus celle du code.
- `docs/MESURES-SAM3-FEUX.md:91` cite `MAX_EDGE = 48` ; le code porte **96**
  depuis le 31/10 (voir §3 et la note ajoutée au §11 de cette page).

---

## 7. Limites et ce qui reste à mesurer

1. **`phone48` = 20 verts / 29 rouges** : une erreur fait bouger le taux de
   5 points ; 20/20 borne le rappel à **≥ 84 %** (Wilson 95 %, `a1` §9).
2. **Le corpus téléphone est saturé** : quatre calibrations différentes y
   donnent 20/20 + 0 faux vert ; il ne sert plus qu'à la non-régression
   (`w10` §4, point 2).
3. **La borne de faux vert FR reste à 11,7 %** (29 rouges, 0 échec observé) :
   il faut ~+271 rouges FR de jour pour descendre à 1,26 % (`w10` §5).
4. **Aucun chiffre sur appareil** : ni la recette exposition (2,0 %/0,6 %,
   `w8` §5), ni la calibration de `GREEN_HOLD_MS` 700 ms n'ont été mesurées en
   rue ; la latence du vote (+123 ms) vient de séquences synthétiques.
5. **`jitter96` n'a qu'une seule photo rouge** (`ru_moscow_03`) : les 22 faux
   verts sont un signal, pas une population (`a1` §4.3 et §9).
6. **Non mesuré** : nuit, pluie, contre-jour ; décodage JPEG Android (les bancs
   partent de PPM/PNG décodés par PIL) ; le miroir `CaissonEval.reduceEdge`
   garde l'ancien redimensionnement et le doré `RECALL-v4.json` (`w1`, « Reste »).
