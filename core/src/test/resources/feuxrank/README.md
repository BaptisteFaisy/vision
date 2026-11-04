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
