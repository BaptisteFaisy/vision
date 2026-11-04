# feuxrank — cas de rang feu piéton contre tricolore (04/11/2026)

Crops réels qui ont fait dire « Don't cross » à tort : un feu piéton vert
plus un bout de tricolore rouge dans le même cadre. Le score SAM seul
prend le rouge (mieux noté) ; le rang ([PedLampRank]) doit prendre le
vert. Test : `PedLampRankTest` (« samsung 20261104 »).

Source : vidéo Samsung `20261104_101521.mp4` (4K, 7,6 s), frame t=0,
recadrée portrait 1215×2160 (x=300) puis 480×640, détectée par
`sam3-eu-a100` (`/live`, prompts `LivePrompts.default`, 04/11/2026) :

| fichier | boîte SAM (px, 480×640) | score | verdict porte |
|---|---|---|---|
| `samsung-20261104-red.ppm` (58×96) | [7,0 ; 103,1 ; 80,6 ; 225,0] | 0,9414 | Red |
| `samsung-20261104-green.ppm` (96×75) | [258,8 ; 256,2 ; 315,0 ; 301,2] | 0,8906 | Green |

Mesuré sur ces pixels (miroir `tools/light_features.py` + portages
`pedScore`/`flux` à l'identique) : ped 0,799 (rouge) contre 0,695
(vert) — écart 0,104 sous `PED_EPS` (0,30), le flux tranche : 963
(vert) contre 138 (rouge). Aucune boîte ne touche le bord.

## Densité réelle : `samsung-101521-*.ppm` (04/11/2026)

Les deux crops ci-dessus viennent d'un recadrage 2160 px de haut, soit
**1,8× plus de pixels** par feu que la caméra : la scène entière (rotation
−90°, 2160×3840) tient en 640 px sur le grand côté, d'où un feu de 20×25 px
et non 56×45. À cette densité le rang s'inversait : le test ci-dessus passait
et la rue alternait. Ces quatre crops sont à la densité réelle (vidéo réduite
à 360×640, boîtes SAM de `cap521`) :

| fichier | instant | contenu | verdict porte |
|---|---|---|---|
| `samsung-101521-tricolore-cote.ppm` (30×65) | t=0,5 s | tricolore vu de côté, lueur rouge | Red |
| `samsung-101521-vert.ppm` (21×25) | t=0,5 s | bonhomme vert | Green |
| `samsung-101521-noir.ppm` (21×24) | t=1,0 s | même feu, LED éteinte (battement 100 Hz) | Red |
| `samsung-101521-rouge.ppm` (20×25) | t=7,0 s | même feu, vrai rouge | Red |

## Faux feux sur panneau (`SignLightVetoTest`)

Boîtes « pedestrian traffic light » faibles, inscrites dans un `sign` mieux
noté, sur deux passages sans feu (frames 180×320, q55, `/live`, 04/11/2026) :

| fichier | vidéo, frame | boîte SAM (px) | score feu | score panneau |
|---|---|---|---|---|
| `samsung-20261104-panneau-rond.ppm` | `20261104_101016`, t=4,0 s | [4,9 ; 50 ; 12,9 ; 63,8] | 0,47 | 0,66 |
| `samsung-20261104-barre.ppm` | `20261104_100943`, t=6,9 s | [0 ; 42 ; 34 ; 49] | 0,53 | 0,59 |

Aucune lampe dans ces crops (`LampLens.flux` sous `MIN_FLUX`) : c'est ce qui
les sépare d'un vrai feu rouge, dont la lampe passe au-dessus de la géométrie.
