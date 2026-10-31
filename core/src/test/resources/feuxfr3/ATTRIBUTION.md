# Échantillon téléphone (tests `PedLightGateTest`) — 11 ancres lotfinal

Ancres issues du lot téléphone (collecte terrain `docs/COLLECTE-FEUX.md`,
crops ≤ 48 px convertis en PPM) : elles verrouillent le rappel vert sur
données du domaine production, comme `feuxfr/` (recalibrage) et `feuxfr2/`
(règle R1b) verrouillent le socle Panoramax.

Source : captures téléphone de l'équipe (caissons seuls, pas de scènes,
pas de visages, pas de plaques) — aucune photo tierce, aucune mention
de licence externe requise. Ligne par crop :

| crop | lot téléphone | verdict attendu |
|---|---|---|
| `tel_g01.ppm` | lotfinal 2026-10-31, `0003.png` (G, bonhomme cyan délavé — raté avant la correction cyan+jour) | Green |
| `tel_g02.ppm` | lotfinal 2026-10-31, `0004.png` (G, bonhomme pâle, bande 18 px) | Green |
| `tel_g03.ppm` | lotfinal 2026-10-31, `0012.png` (G, bonhomme cyan délavé 48×48) | Green |
| `tel_g04.ppm` | lotfinal 2026-10-31, `mz004.png` (G, bonhomme fin, bande 11 px) | Green |
| `tel_g05.ppm` | lotfinal 2026-10-31, `s8_15_20_53_1.png` (G, bonhomme net) | Green |
| `tel_g06.ppm` | lotfinal 2026-10-31, `s8_15_20_55_7.png` (G, LED bleutée) | Green |
| `tel_g07.ppm` | lotfinal 2026-10-31, `s8_15_20_57_0.png` (G, bonhomme net) | Green |
| `tel_r01.ppm` | lotfinal 2026-10-31, `0001.png` (R, bonhomme rouge net centré) | Red |
| `tel_r02.ppm` | lotfinal 2026-10-31, `0008.png` (R, bonhomme rouge net 48×48) | Red |
| `tel_r03.ppm` | lotfinal 2026-10-31, `0000.png` (R, feu rouge net) | Red |
| `tel_r04.ppm` | lotfinal 2026-10-31, `0006.png` (R, bonhomme rouge petit, bande 28 px) | Red |

Conventions : `tel_g*.ppm` verts piétons confirmés, `tel_r*.ppm` rouges,
`tel_c*.ppm` cas limites (disques voiture, LED froides) ; PPM P6 ≤ 48 px
(voir `README.md` à côté pour la conversion et l'embarquement).
