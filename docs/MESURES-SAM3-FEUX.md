# SAM 3.1 sur les feux piétons — rappel mesuré (29/10/2026)

Ce document rapporte une **mesure**, pas une fonctionnalité de cet arbre. Il répond à une
question que le mode traversée pose sans y répondre : *est-ce que SAM 3.1 trouve réellement un
feu piéton dans une photo, et lequel ?* (`docs/TRAVERSEE.md` traite du côté application ; ici
c'est le détecteur qui est mis à l'épreuve.)

**Provenance.** Relevé du 29/10/2026, GPU RTX 3060 Ti (8 Go), poids `unsloth/sam3.1` en
`bfloat16` (1,59 Go de VRAM), `transformers` 5.21.1. Le code de mesure vit **hors de cet
arbre** (paquet `pedlight`, numpy pur + un balayage). Les images sont des photographies
publiques Wikimedia Commons, listées et attribuées au §7 — aucune n'est versionnée ici
(licences CC BY-SA pour la plupart, l'attribution suffit mais l'octet n'a rien à faire dans le
dépôt). Les chiffres ci-dessous sont donc des **relevés datés**, pas des affirmations
lisibles dans cet arbre.

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
peut voir* — un mode traversée qui compte sur eux ne verra jamais rien.

## 3. Le presence token discrimine bien rouge / vert

`Sam3DETRDecoderConfig.use_presence_token = true` (lu dans `config.json` des poids). Test :
les prompts d'état ne tirent que 3 fois sur 16 images, mais **3 fois juste**.

| Image | prompt | état lu | confiance |
|---|---|---|---|
| `de_ampelmann_green` | `green …` | vert | 1,00 |
| `kr_green_full` | `green …` | vert | 1,00 |
| `kr_red_full` | `red …` | rouge | 1,00 |

Conclusion utilisable : le prompt générique **trouve**, le prompt d'état **vérifie**. En
revanche 3 points de mesure ne prouvent pas la discrimination — c'est une piste, pas un
résultat.

## 4. Exactitude d'état

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
crop ; le seuil « quelque chose est allumé » était à 1,2 %. Abaissé à 0,6 % :

| seuil | photos réelles (n=9) | corpus synthétique (3 060 crops) |
|---|---|---|
| 1,2 % | 6/9 | masque 95,5 % · crop entier 80,3 % |
| **0,6 %** | **7/9** | masque **95,6 %** · crop entier **81,9 %** |
| 0,4 % | 7/9 | idem |

Le gain se voit aussi sur le corpus synthétique, donc ce n'est pas un sur-ajustement aux neuf
photos.

## 6. Ce que ça dit du code de cet arbre

`PedLightColor.MIN_FRACTION = 0.06f` (`core/src/main/java/dev/sam3vision/core/crossing/PedLightColor.kt`)
exige 6 % de pixels colorés dans la boîte. Le défaut (b) ci-dessus est exactement le cas d'un
bonhomme qui n'en occupe pas 6 %.

**Mais les deux statistiques ne sont pas comparables** : ici c'est une fraction pondérée de
pixels « allumés » (luminance × saturation × chroma) sur le crop ; là c'est la part de pixels
dont la teinte HSV tombe dans une plage. Un chiffre ne se transpose pas à l'autre. Il faudrait
rejouer `PedLightColor.classify` sur les 16 crops pour trancher — c'est la première chose à
faire si le mode traversée peine sur des bonshommes sombres.

Le point transférable est la **forme** du correctif, pas son seuil : abaisser un plancher
d'allumage coûte peu en faux positifs *si* la décision rouge/vert reste portée par une marge,
et il se règle sur un corpus qui contient des cas sombres.

## 7. Attribution des images (Wikimedia Commons)

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

## 8. Limites

- **n = 9 images labellisées.** Ce n'est pas un banc d'essai, c'est un déballage. Les 77,8 %
  disent « le branchement tient », pas « le système est bon ».
- Aucune condition **nocturne**, de **pluie** ni de contre-jour dans l'échantillon — les cas
  qui cassent réellement un classifieur de couleur.
- Aucun pictogramme **français** en conditions réelles : les images FR de l'échantillon sont
  des plans larges où le feu est petit.
- `TAU` (marge rouge/vert) et le plancher d'allumage n'ont pas été recalibrés sur les 235
  lampes Panoramax déjà présentes dans `core/src/test/resources/feuxfr/` — c'est le prochain
  gain évident, et il ne demande aucun GPU.
- Le second chemin d'API (`facebookresearch/sam3`, poids officiels gated) n'a **pas** été
  exécuté. Tout ce qui précède utilise le miroir `unsloth/sam3.1` via `transformers`.
