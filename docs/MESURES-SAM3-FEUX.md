# SAM 3.1 sur les feux piétons — rappel mesuré, porte de production à l'épreuve (29/10/2026)

Ce document rapporte deux **mesures**, pas une fonctionnalité de cet arbre.

1. **Rappel de SAM 3.1** sur des feux piétons : une des inconnues listées par
   `docs/TRAVERSEE.md` §6 (« rappel SAM sur feux piétons jamais mesuré »).
2. **`PedLightGate` exécutée sur de vrais crops SAM** : c'est le point §6.3 du même
   document (« reste la preuve sur crops SAM réels, cadrage exact inconnu »). La preuve est
   faite, et elle est **négative**.

**Provenance.** Relevé du 29/10/2026, GPU RTX 3060 Ti (8 Go), poids `unsloth/sam3.1` en
`bfloat16` (1,59 Go de VRAM), `transformers` 5.21.1. Le code de mesure (détection + balayage)
vit **hors de cet arbre** (paquet `pedlight`, numpy pur). En revanche le §6 exécute le **vrai
code Kotlin de cet arbre**, compilé hors Gradle : `PedLightGate`, `TrainedLightColor`,
`PedLightColor`, copiés tels quels, `scan` passé public et un `debug()` ajouté — aucune ligne de
logique modifiée. Les images sont des photographies publiques Wikimedia Commons, listées et
attribuées au §8 ; aucune n'est versionnée ici (CC BY-SA : l'attribution suffit, l'octet n'a
rien à faire dans le dépôt). Les chiffres ci-dessous sont des **relevés datés**, pas des
affirmations lisibles dans cet arbre.

## 1. Protocole

16 photos de feux piétons réels, 7 pays (Allemagne, Autriche, France, Pays-Bas, Russie, Corée,
Royaume-Uni), cadrages variés : bonhomme plein cadre, rue entière en 1920×3413, bandeau LED au
sol, boîtier de bouton d'appel. Quatre prompts par image, seuil de score 0,5, déduplication à
IoU 0,6 sur les recouvrements inter-prompts.

L'image est encodée **une seule fois** (`get_vision_features`) et son embedding est réutilisé
par les quatre prompts : la tour vision domine le coût et ne dépend pas du texte.

## 2. Rappel par prompt

| Prompt | détections | images trouvées | rappel | rouge | vert | éteint |
|---|---|---|---|---|---|---|
| `pedestrian traffic light` | 41 | 14/16 | **87,5 %** | 10 | 13 | 17 |
| `pedestrian signal` | 10 | 7/16 | 43,8 % | 3 | 6 | 1 |
| `red pedestrian traffic light` | 2 | 2/16 | 12,5 % | 2 | 0 | 0 |
| `green pedestrian traffic light` | 13 | 7/16 | 43,8 % | 1 | 10 | 2 |

`pedestrian traffic light` est le seul prompt utilisable comme détecteur. Les synonymes courts
perdent la moitié du rappel ; **un prompt d'état n'est pas un détecteur** (12,5 %).

Les deux images à zéro détection sont les bandeaux LED au sol de Séoul : ce ne sont pas des
feux sur mât, SAM ne les voit pas et c'est cohérent. *Le détecteur définit ce que le système
peut voir.*

## 3. Le presence token discrimine bien rouge / vert

`Sam3DETRDecoderConfig.use_presence_token = true` (lu dans `config.json` des poids). Test :
les prompts d'état ne tirent que 3 fois sur 16 images, mais **3 fois juste**.

| Image | prompt | état lu | confiance |
|---|---|---|---|
| `de_ampelmann_green` | `green …` | vert | 1,00 |
| `kr_green_full` | `green …` | vert | 1,00 |
| `kr_red_full` | `red …` | rouge | 1,00 |

Conclusion utilisable : le prompt générique **trouve**, le prompt d'état **vérifie**. Trois
points de mesure ne prouvent pas la discrimination — c'est une piste, pas un résultat.

## 4. Exactitude d'état (classifieur `pedlight`, hors arbre)

Sur les 9 images dont l'état est certain (vérifié à la loupe ×4) :

```
exactitude scène : 7/9 = 77,8 %
rouge <-> vert   : 0 erreur
temps            : 0,71 s/image pour 4 prompts (≈ 60-70 ms par prompt après l'encodage)
```

Les deux échecs sont les bandeaux LED au sol (aucune détection), pas des inversions de
couleur.

## 5. Deux défauts trouvés en exécutant, et ce qu'ils coûtent

**(a) Un éclat de 384 pixels renversait la scène.** Sur une photo 1920×3413, SAM sort le vrai
feu (516×1056 px, vert, score 0,49) **plus** trois éclats de 8 à 48 px sur le mât, dont deux
lus « rouge ». Le verdict de scène sortait `red`. Correctif : restreindre les votes aux
détections à moins de 10× de la plus grande de l'image. Un éclat de 384 px ne doit jamais
battre un signal de 545 000 px.

**(b) Un pictogramme vert à LED pointillées lu « éteint ».** Il n'occupait que 0,65 % de son
crop ; le plancher « quelque chose est allumé » du classifieur de mesure était à 1,2 %.
Abaissé à 0,6 % : photos réelles 6/9 → 7/9, et le corpus synthétique s'améliore aussi (masque
95,5 % → 95,6 %, crop entier 80,3 % → 81,9 %). Ce plancher appartient au paquet de mesure, pas
à cet arbre.

## 6. `PedLightGate` sur de vrais crops SAM : 1 vert sur 6

**Dispositif.** Pour chaque image : boîte SAM au meilleur score, crop, plus grand côté ramené
à **48 px** — exactement ce que fait `CrossingPixels.frame` (`MAX_EDGE = 48`,
`app/.../isee/CrossingPixels.kt:23`). Puis `PedLightGate.verdict(argb, w, h)`, le vrai.

**Contrôle du harnais d'abord** : sur les 9 crops `core/src/test/resources/feuxfr/`, le harnais
reproduit **9/9** les verdicts attendus d'`ATTRIBUTION.md`, y compris les trois rejets par la
porte (2 disques voiture, 1 pose large). Le harnais exécute donc bien le code, pas une copie
divergente.

**Résultat sur les 16 crops SAM** (boîte au meilleur score) :

| crop | px | brut | porte | vérité |
|---|---|---|---|---|
| `at_ampelpaerchen` | 20×48 | Green | **Unknown** | vert |
| `at_vienna_light` | 21×48 | Green | **Unknown** | vert |
| `de_ampelmann_green` | 48×24 | Green | **Unknown** | vert |
| `kr_green_full` | 20×48 | Green | **Unknown** | vert |
| `nl_panda_10` | 23×48 | Unknown | Unknown | vert |
| `ru_moscow_04` | 22×48 | Green | **Green** | vert |
| `ru_moscow_03` | 6×48 | Unknown | Unknown | rouge |
| `fr_vichy_green` | 12×48 | Red | Red | (non étiquetée) |
| `fr_vichy_red` | 13×48 | Green | Green | (non étiquetée) |
| `kr_red_full` | 19×48 | Red | Red | (non étiquetée) |
| `nl_panda_13` | 27×48 | Red | Red | (non étiquetée) |
| `nl_panda_16` | 18×48 | Unknown | Unknown | (non étiquetée) |
| `uk_man_green` | 22×48 | Red | Red | (feu voiture) |
| `uk_puffin` | 27×48 | Green | **Unknown** | (boîtier d'appel) |

**Le vert passe 1 fois sur 6 signaux verts étiquetés.** Aucun faux vert sur les images
étiquetées — le sens de l'erreur est sûr (on dit « attendez »), mais **un mode traversée qui
n'annonce jamais « vert » ne sert à rien** : le fail-safe est intact et la fonction est morte.

### Pourquoi, et pourquoi aucun seuil ne le corrige

Le contrôle qui rejette est `ELONGATION_MIN = 1.5f` : la tache verte doit être 1,5× plus haute
que large. Diagnostic relevé par le `debug()` du harnais :

| crop vert réel rejeté | green | bottom | **elongation** |
|---|---|---|---|
| `at_vienna_light` | 0,0198 | 1,00 | **1,11** |
| `kr_green_full` | 0,0656 | 1,00 | **1,07** |
| `at_ampelpaerchen` | 0,0010 | 1,00 | 1,00 (tache de 1 px) |
| `de_ampelmann_green` | 0,0017 | 1,00 | 0,15 (tache de 1 px de haut) |

Et les deux disques de voiture que la porte doit rejeter dans le corpus de calibration :

| crop disque voiture | green | bottom | **elongation** |
|---|---|---|---|
| `fr_cardisc_367` | 0,0460 | 0,53 | **1,30** |
| `fr_cardisc_close_183` | 0,2402 | 0,76 | **1,47** |

Le maximum d'élancement des vrais verts rejetés (**1,11**) est **sous** le minimum des disques
à rejeter (**1,30**). Les deux domaines sont **inversés** : aucun seuil sur l'élancement ne
sépare les deux populations. Ce n'est pas un réglage à trouver, c'est une hypothèse qui ne
tient plus.

L'hypothèse en cause est écrite dans `PedLightGate` : *« Hypothèse cadrage : la boîte SAM serre
la lampe »*. Avec un prompt texte `pedestrian traffic light`, SAM 3.1 segmente le **caisson**,
pas la lampe : après réduction à 48 px le pictogramme fait une dizaine de pixels, et sa forme
n'est plus mesurable. Le corpus de calibration, lui, est cadré sur la lampe (les bonshommes y
sortent à 1,92, 2,20, 2,33 d'élancement), donc la borne 1,5 y fonctionne parfaitement — et ne
transfère pas.

Second facteur, plus discret : les crops SAM sont **très allongés** (20×48, 12×48) parce que le
caisson l'est. Une tache ronde dans un cadre aussi étroit n'a plus de place pour être allongée.

### Ce que ça implique

Le correctif n'est pas dans `ELONGATION_MIN`, il est **en amont** :

1. soit **cadrer la lampe** avant la porte (localiser la tache allumée dans le crop SAM, puis
   recadrer dessus — essayé, voir ci-dessous, insuffisant seul) ;
2. soit **recalibrer la porte sur le domaine SAM** (caisson 48 px), en acceptant de perdre
   l'élancement comme trait — et il faut alors un autre trait pour rejeter les disques voiture,
   ce que ces données ne fournissent pas encore.

Le recadrage sur la tache allumée a été testé : rappel vert **2/6** (contre 1/6) mais un
**faux vert** apparaît (`ru_moscow_03`, rouge debout lu vert, élongation 6,50 — un bras de
bonhomme debout forme une bande verticale). Gain de rappel payé en sûreté : à ne pas retenir en
l'état.


### Trois tentatives pour relever le rappel vert, trois échecs (29/10)

Après le constat du §6 (1 vert sur 6), trois variantes ont été mesurées sur les
**mêmes 16 crops**, avec le même code Kotlin, seule la préparation changeant :

| variante | verts acceptés | faux verts |
|---|---|---|
| boîte SAM brute (référence) | **1/6** | 0 |
| crop sur la **tache verte** | 2/6 | **1** |
| crop sur la **lentille allumée** (plus lumineuse et saturée) | 1/6 | **1** |

Le faux vert est toujours le même : `ru_moscow_03`, un bonhomme **debout** dont
l'image de vérité est rouge. Le crop isole un bras vertical — élongation 6,50,
6,50 > 1,5 — et la porte l'accepte. **Gagner du rappel en perdant la sûreté n'est
pas un gain** : ce mode sert à dire « traversez ».

La cause est structurelle, pas un réglage : `ELONGATION_MIN = 1.5f` suppose un
pictogramme allongé dans un cadre qui l'épouse. Avec un prompt texte, SAM segmente
le **caisson**, et une fois ramené à 48 px le pictogramme est emboîté dans une
lentille **ronde** — sa boîte englobante est donc quasi carrée (élongation mesurée
1,03 à 1,11 pour les vrais verts). Les crops du corpus de calibration, eux, sont
cadrés sur le pictogramme (1,92 / 2,20 / 2,33). Le trait ne transfère pas.

**Ce qu'il faudrait pour conclure** : refitter un autre trait sur les 235 lampes
Panoramax — qui ne sont pas dans cet arbre (9 crops extraits seulement, §9) — ou
sur quelques centaines de crops téléphone. Avec 6 verts étiquetés et 2 disques de
voiture, aucun trait ne peut être validé : les densités se recouvrent (taux de
remplissage de la boîte du vert : 0,16 à 0,65 pour les bonshommes contre 0,46 à
0,54 pour les disques).

**Recommandation** : ne pas toucher `ELONGATION_MIN`. Le prochain pas est la
collecte, pas le réglage.

## 7. Reproductibilité

Le harnais (copie du 29/10 des quatre fichiers `crossing/`, compiler et exécuter) vit hors de
cet arbre, comme le reste du code de mesure. Le point de contrôle qui rend le résultat citable
est le 9/9 sur `feuxfr/` : **toute** réexécution future doit d'abord retrouver ces neuf
verdicts avant d'interpréter les crops SAM.

## 8. Attribution des images (Wikimedia Commons)

Aucune image n'est versionnée. Licences telles que déclarées par Commons ; CC BY-SA impose
l'attribution et le partage à l'identique des dérivés.

| Fichier Commons | Licence | Auteur |
|---|---|---|
| Korean smartphone zombie pedestrian traffic light red.jpg | CC BY-SA 4.0 | Aaaatu |
| Korean smartphone zombie pedestrian traffic light red close up.jpg | CC BY-SA 4.0 | Aaaatu |
| Korean smartphone zombie pedestrian traffic light green.jpg | CC BY-SA 4.0 | Aaaatu |
| Korean smartphone zombie pedestrian traffic light green close up.jpg | CC BY-SA 4.0 | Aaaatu |
| Feu rouge piéton et cycliste au PP et sas vélo rue de la Source-de-l'Hôpital (Vichy) 2024-09-01.JPG | CC BY-SA 4.0 | Tabl-trai |
| Feu vert piéton et cycliste au PP et sas vélo rue de la Source-de-l'Hôpital (Vichy) 2024-09-01.JPG | CC BY-SA 4.0 | Tabl-trai |
| Moscow, pedestrian-cycling traffic signals Sept 2025 03.jpg | CC0 | Retired electrician |
| Moscow, pedestrian-cycling traffic signals Sept 2025 04.jpg | CC0 | Retired electrician |
| Ampelmann-Green.jpg | CC BY-SA 4.0 | Illustratedjc |
| Ampelpaerchen traffic light Vienna 2024.jpeg | CC BY-SA 4.0 | Furkan Akkurt |
| Traffic Light, Vienna.jpg | CC BY-SA 4.0 | John Samuel |
| Red Panda themed traffic light, Blijdorpse Polder, Rotterdam (2023) 10.jpg | CC BY-SA 4.0 | Donald Trung Quoc Don |
| Red Panda themed traffic light, Blijdorpse Polder, Rotterdam (2023) 13.jpg | CC BY-SA 4.0 | Donald Trung Quoc Don |
| Red Panda themed traffic light, Blijdorpse Polder, Rotterdam (2023) 16.jpg | CC BY-SA 4.0 | Donald Trung Quoc Don |
| Man waiting for green traffic light.jpg | CC BY-SA 3.0 | Mateusz Giełczyński |
| Puffin crossing in Belfast (35021074385).jpg | CC BY-SA 2.0 | William Murphy |

## 9. Limites

- **n = 9 images étiquetées** pour le rappel, **6 verts** pour la porte. Ce n'est pas un banc
  d'essai, c'est un déballage. Le 1/6 dit « la preuve demandée au §6.3 est négative », pas
  « la porte est cassée sur le terrain » : le cadrage réel de production (crop JPEG, décodeur
  Android) n'a pas été reproduit à l'octet près.
- **Portée : de jour uniquement** (décision du 29/10, voir `docs/CALIBRAGE-FEU.md` §9).
  La nuit sort du périmètre ; le contre-jour, non — un feu à contre-jour de jour est
  aussi sombre qu'un feu de nuit.
- `TAU` et le plancher d'allumage du paquet de mesure n'ont **pas** été recalibrés sur les
  **9** crops `feuxfr/` versionnés ici (le corpus de 235 lampes qui a servi à calibrer
  `PedLightGate` n'est pas dans cet arbre : seuls 9 crops extraits le sont).
- Le second chemin d'API (`facebookresearch/sam3`, poids officiels gated) n'a **pas** été
  exécuté. Tout ce qui précède utilise le miroir `unsloth/sam3.1` via `transformers`.

## 10. Relevé du 01/11 : rappel par prompt, veto par prompt mort, bout-en-bout

Mêmes 16 photos, poids `unsloth/sam3.1` `bfloat16`, RTX 3060 Ti, seuil de
score 0,35 (seuil serveur `SCORE_THRESHOLD`), une passe par prompt, sans
dédup inter-prompts. Le §6 (1 vert/6) est caduc : crop 48 → 96 px,
recentrage lampe et porte 1,0 ont suivi (doré `RECALL-v4.json` : 6/6).

### Rappel détection (image trouvée si ≥ 1 boîte)

| Prompt | rappel |
|---|---|
| `pedestrian traffic light` | 14/16 |
| `pedestrian signal head` | 11/16 |
| `pedestrian light` | 13/16 |
| `pedestrian signal` | 7/16 |
| union des quatre (prod) | **14/16** |
| `walk signal` (candidat, mesure seule) | 14/16 |
| `pedestrian crossing signal` (candidat, mesure seule) | 12/16 |

Les 2 manquées sont les bandeaux LED au sol de Séoul (hors domaine, §2).
Les candidats n'ajoutent rien à l'union et captent aussi les tricolores :
non retenus.

### Le veto par prompt est mort

`vehicle traffic light`, `three-lamp traffic light` et `car traffic light`
tirent 14/16, 12/16 et 14/16 — **sur les mêmes boîtes que le prompt piéton**
(mêmes coordonnées, scores 0,7-0,93, y compris gros plans piétons sans
voiture) : aucun wording ne sépare voiture et piéton, activer
`CarLightVeto` éteindrait les vrais verts. Le veto reste inactif, mesure à
l'appui. `feu tricolore` brut au modèle : 0/16 (CLIP anglais).

### Bout-en-bout local : 5/7

Chaîne rejouée hors appareil (miroir Python, seuil prod, crop 96 px PIL,
sans recentrage) sur les 9 scènes étiquetées : 2 sans boîte (bandeaux
Séoul), 5/7 justes, 2 verts manqués, 0 inversion :

- `at_ampelpaerchen` (bonhomme net, fraction verte 0,0028) : rejeté par le
  seuil arbre 0,0035. Balayage 0,002-0,0035 **identique** sur 933 crops
  (883 FR téléphone + 17 lot1 + 33 ancres, précision 1,0) : le seuil passe
  à 0,002, plancher partagé avec la porte.
- `nl_panda_10` : la tête (0,48) cadre la lentille **éteinte** quand le
  caisson (0,89) couvre le boîtier allumé — la préférence tête absolue
  lisait le noir. Correctif : tête sans lampe vue → le caisson est relu
  (`selectLightBoxes` + `crossingLightOf`, tests JVM).

Après correctifs (rejoué, mêmes approximations) : **7/7** sur les scènes à
boîte, 0 inversion. Restent hors portée : bandeaux LED au sol, nuit/pluie
(non calibrés), feux multi-lentilles ambigus.

### Corpus couleur : le bruit est dans les labels, pas dans la porte

- 2 771 crops PTL (noms de fichiers) : 872 « verts » à pixels rouges et 44
  « rouges » à pixels verts — les noms ne valent pas vérité, exclus des
  chiffres (vus à la loupe : feux inversés).
- 883 crops FR téléphone : précision 1,0, rappel 0,78 — les 116 verts
  manqués relus sont des cadrages sans lampe visible (feuillage, poteau :
  labels de scène, pas de crop), que la porte a raison de refuser.
- Lot1 : 17/17. Ancres : 33/33.
