# Relever le rappel du feu vert — quatre tentatives, zéro gain (29/10/2026)

Objectif : faire passer le rappel vert de **1/6** (`docs/MESURES-SAM3-FEUX.md` §6)
à au moins **4/6** sans jamais produire de vert sur un rouge. Résultat : **aucune
des quatre voies testées n'y arrive**. Ce document dit lesquelles, avec quels
chiffres, et pourquoi — pour que personne ne refasse le chemin.

**Provenance.** Relevé du 29/10/2026, RTX 3060 Ti, `unsloth/sam3.1` bf16. La
chaîne de mesure (harnais Kotlin, entraîneur, générateur de crops) vit **hors de
cet arbre** ; seule la porte testée est celle du dépôt, exécutée telle quelle.

## 1. La chaîne de mesure, et pourquoi elle compte

Trois briques, toutes vérifiées avant de servir :

| brique | ce qu'elle fait | preuve |
|---|---|---|
| harnais Kotlin | compile `PedLightGate` / `TrainedLightColor` / `CrossingFeatures` **de cet arbre** hors Gradle, lit des crops PPM, rend le verdict et les compteurs internes | reproduit **9/9** les verdicts attendus de `core/src/test/resources/feuxfr/ATTRIBUTION.md` |
| `DumpKt` | sort les **17 traits de `CrossingFeatures`** en CSV pour chaque crop | mêmes valeurs que l'app, puisque c'est le même code |
| `tools/train_light.py` | refit un arbre (profondeur 6, feuille min 20) et émet les cinq tableaux de `TrainedLightColor` | l'export doit reproduire les prédictions de sklearn sur chaque ligne, sinon rien n'est imprimé — vérifié sur 400 lignes |

Conséquence : un modèle réentraîné se **branche sans toucher une ligne de
Kotlin** — `TrainedLightColor` n'est que des tableaux.

## 2. Les données : ce qui est mort, ce qui est bloqué

| source | état constaté le 29/10 |
|---|---|
| **Ampel-Pilot** (source documentée dans `TrainedLightColor`) | **mort** — `s3://ampelpilot.data` renvoie `NoSuchBucket`, les 4 archives n'existent plus. Le dépôt garde les annotations SQL et un YOLO CoreML 61 Mo, pas les 3 696 images |
| **ImVisible PTL** (5 059 images, MIT) | **bloqué** — quota Google Drive épuisé après deux téléchargements partiels ; le zip de 13,8 Go coupé à 9,7 Go n'a que **4 images lisibles sur 1 363** |
| **LytNet V2** (modèle de référence, MIT) | poids chargés (`0 manquant, 0 inattendu`) mais **inutilisable en l'état** : prétraitement 0-255 non documenté (`to_tensor`/`normalize` commentés dans leur propre `dataset.py`), `AvgPool2d(12,9)` codé en dur, entrée 576×768 plein cadre + ligne de passage |
| Hugging Face (`traffic light`, `ampel`, `pedestrian`) | feux **véhicules** (LISA, Bosch) ou passages piétons — aucun état de feu piéton |
| les 235 lampes Panoramax | **pas dans cet arbre** (9 crops extraits seulement) |

## 3. Les quatre tentatives

Mesurées sur les **mêmes 16 crops** de feux réels, même code Kotlin, seule la
préparation change. « Verts » = verts étiquetés acceptés ; « faux vert » = vert
rendu sur une image dont la vérité est rouge.

| # | variante | verts | faux vert |
|---|---|---|---|
| 0 | boîte SAM brute (référence) | **1/6** | 0 |
| 1 | crop sur la **tache verte** | 2/6 | **1** |
| 2 | crop sur la **lentille allumée** (plus lumineuse et saturée) | 1/6 | **1** |
| 3 | boîte **élargie +25 %** | 1/7 | **1** |

Les trois ajouts de rappel paient en sûreté : le faux vert est toujours un
bonhomme **debout** (vérité rouge) dont le crop isole un bras vertical —
élongation 6,50, très au-dessus du seuil 1,5.

## 4. Le chiffre qui condamne le réglage

`ELONGATION_MIN = 1.5f` exige une tache verte 1,5× plus haute que large.

| population | élongation mesurée |
|---|---|
| vrais verts **rejetés** par la porte | 1,00 · 1,07 · 1,11 · 0,15 |
| disques de voiture que la porte **doit** rejeter | 1,30 · 1,47 |

Le maximum des uns est **sous** le minimum des autres : **l'ordre est inversé
entre les deux domaines**. Aucune valeur de seuil ne sépare les deux
populations. Ce n'est pas un réglage à trouver, c'est un trait qui ne transfère
pas — parce que les crops de calibration sont cadrés sur le pictogramme
(1,92 / 2,20 / 2,33) et que SAM, lui, segmente le **caisson** : à 48 px le
pictogramme est emboîté dans une lentille ronde, donc sa boîte est quasi carrée.

## 5. Ce qu'un élargissement change vraiment

Test complémentaire, plus fin que la ligne 3 du tableau : 280 crops obtenus en
jittant la boîte SAM autour du feu (échelle 0,70 à 1,40) sur les **mêmes**
photos. Taux d'acceptation du vert par échelle de crop :

| échelle du crop | verts acceptés | rouge→vert |
|---|---|---|
| ≤ 0,85 | 14/54 (26 %) | 0 |
| 0,85 – 1,00 | 19/53 (36 %) | 0 |
| 1,00 – 1,20 | 20/70 (29 %) | 0 |
| > 1,20 | 25/63 (**40 %**) | 0 |

Sur ce jeu, élargir aide nettement (26 % → 40 %). **Mais** sur les 9 crops
Panoramax de calibration, l'élargissement ×1,25 laisse les 9 verdicts
**identiques** — et sur les 16 photos réelles il fait **apparaître un faux vert**
(`uk_puffin`, un boîtier d'appel confondu avec un feu). Les 280 crops viennent
de 6 feux seulement : le gain qu'on y voit est de la mémorisation, pas de la
généralisation.

**Conclusion : ne pas élargir en production.**

## 6. Ce qu'il faut pour débloquer

Ni un seuil, ni un cadrage, ni un autre trait géométrique : **des données
labellisées dans le domaine de production**. Par ordre de coût :

1. **les 235 lampes Panoramax étiquetées** (jour, françaises, crops serrés) —
   elles existent, elles ont servi à la calibration du 28/10, elles ne sont pas
   dans l'arbre. C'est le chemin le plus court : un refit sur ces 235 + les 16
   réelles donnerait un modèle validable en une heure ;
2. **200 à 400 crops téléphone** (SAM box, cadrage production, jour **et** nuit),
   étiquetés à la main : 20 minutes de labellisation, refit immédiat ;
3. le corpus ImVisible complet (13,8 Go) si le quota Drive se libère — mais il
   est **américain**, et un bonhomme US n'est pas un bonhomme français.

Tant que l'un des trois n'est pas disponible, **toute modification de la porte
sera du sucre sur un chiffre à n = 7** : le tableau §3 montre que chaque tentative
achète du rappel avec de la sûreté, et ce mode sert à dire « traversez ».

## 7. Reproduire

Hors de cet arbre, avec le paquet de mesure `pedlight` :

```bash
# 1. les traits des 9 crops de calibration, par le vrai code Kotlin
java -cp "out:lib/*" DumpKt core/src/test/resources/feuxfr > feuxfr.csv

# 2. un arbre neuf (refuse de sortir si l'export ne reproduit pas sklearn)
python tools/train_light.py feuxfr.csv --depth 6 --leaf 20

# 3. verdict de la porte sur un dossier de crops, avec le détail des contrôles
CSV=1 java -cp "out:lib/*" MainKt <dossier-de-crops>
```

Le point de contrôle qui rend tout le reste citable reste le **9/9** sur
`feuxfr/` : toute réexécution doit d'abord le retrouver.

## 8. Le corpus existe — et il est shanghaïen (29/10, second passage)

Après le constat du §2 (« aucune donnée joignable »), une source a finalement été
trouvée, et elle est **sérieuse** :

| | |
|---|---|
| jeu | [`ngqtrung/ptl-crossing-check`](https://huggingface.co/datasets/ngqtrung/ptl-crossing-check) sur Hugging Face |
| licence | **MIT** |
| contenu | **5 059 photos**, 876×657, étiquettes **humaines d'origine** du PTL (ImVisible) : rouge, vert, décompte |
| point de vue | **le piéton au bord du trottoir** — exactement la situation d'iSee |
| hébergement | Hugging Face, donc **pas** le quota Google Drive qui bloquait ImVisible |

**Toute la chaîne a tourné pour de vrai** : SAM trouve un feu sur **99 %** de ces
photos (468/474 sur la validation — à comparer aux **0 %** obtenus sur les photos
de rue Panoramax, où le feu fait 16 px). Crops « caisson » 48 px → traits de
`CrossingFeatures` → arbre refit. 1 881 crops d'entraînement, 470 de validation,
410 de test, tous disjoints.

### Ce que donne le modèle réentraîné (profondeur 6, feuille 20)

| jeu | verts acceptés | faux verts |
|---|---|---|
| PTL validation — arbre **actuel** | 16 % (39/238) | 1,3 % (3/232) |
| PTL validation — arbre **refit** | **50 %** (130/238) | 0,9 % (2/232) |
| PTL **test** — arbre actuel | 21 % (38/184) | 3,1 % (7/226) |
| PTL **test** — arbre refit | **52 %** (95/184) | 3,5 % (8/226) |

Sur le domaine PTL, c'est un **gain net : 2,5× plus de verts à taux de faux vert
identique**. C'est la première fois qu'une variante gagne sans payer en sûreté.

### Et c'est précisément pour ça qu'il ne faut pas le livrer

Les 9 crops **français** de `core/src/test/resources/feuxfr/` — la seule référence
tricolore de l'arbre — se dégradent :

| arbre | conformes aux 9 verdicts attendus |
|---|---|
| actuel (calibré FR, 28/10) | **9/9** |
| refit PTL, pureté 0,80–0,85 | **6/9** — `fr_green_fig_243` et `fr_green_fig_302` (deux vrais bonshommes verts) passent en `Unknown` |
| refit PTL, pureté 0,90 | **5/9** — et `fr_cardisc_close_183` (un **disque de voiture**) passe en `Green` |

Un bonhomme shanghaïen n'est pas un bonhomme français : silhouette, proportions,
et surtout le caisson (format, lentille, halo) diffèrent. Le modèle apprend le bon
domaine et le mauvais pays.

**Décision : ne pas remplacer l'arbre.** Le gain est réel mais il est shanghaïen,
et Baptiste traverse en France.

### Ce que ça change pour la suite

Le verrou n'est plus « trouver des données » : il est **« trouver des données
françaises »**. Et la chaîne est maintenant éprouvée de bout en bout — elle a
produit 2 761 crops étiquetés en 15 minutes de GPU. Appliquée à 200-400 crops
téléphone français, elle donnera un modèle dans l'heure.

Reproductible : `tools/build_ptl_crops.py` (PTL → crops caisson),
`DumpKt` (17 traits), `tools/fit_and_eval.py` (refit + contrôle d'export +
matrice de confusion **avec** la règle de pureté de `TrainedLightColor`).

## 9. Portée resserrée : de jour seulement (décision du 29/10)

Décision produit : **le mode Traversée ne traite plus que le jour.** La nuit sort
du périmètre — donc de la liste des preuves à apporter.

Ce que ça change, mesuré et non supposé :

- **le corpus PTL est déjà 100 % de jour** (luminance moyenne des 2 761 crops
  utilisés : minimum 60/255, médiane 119, maximum 153). Filtrer la nuit ne retire
  donc rien et n'ouvre aucun gain : la portée « jour » ne débloque pas le
  transfert shanghaïen → français ;
- **filtrer sur la luminosité ne suffit pas non plus.** En ne gardant que les
  crops lumineux (≥ 110), le refit remonte de 6/9 à **7/9** sur les crops
  français — toujours pas 9/9, et au prix de faux verts sur PTL (3,2 % à
  pureté 0,85) ;
- en revanche la portée resserrée **retire deux lignes de la dette** listée au
  §6 de `docs/MESURES-SAM3-FEUX.md` : plus de nuit, plus de pluie. Il reste les
  poses larges, le corpus téléphone, et le seuil bloquant des faux verts.

Conséquence pratique : le seuil de nuit de `MIN_VALUE`/`MIN_SATURATION` dans
`PedLightColor` et la garde de luminosité de `PedLightGate` n'ont plus à être
calibrés sur des scènes sombres. Ils ne doivent pas non plus être *relâchés* :
un feu à contre-jour de jour est aussi sombre qu'un feu de nuit, et il reste dans
le périmètre.
