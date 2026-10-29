# Échantillon R1b (tests `PedLightGateTest`)

11 crops PPM ≤ 48 px (52 Ko) extraits du lot FR jour (29/10/2026, 6 573
détections SAM 3.1 triées 1 par 1) : ils verrouillent la règle R1b —
bonhommes verts cadrés haut acceptés, rouges et disques voiture refusés.
`r1b_g*` : verts que l'ancienne porte refusait (le test échoue sans R1b) ;
`st_*` : filets anti-régression (verts acceptés avant comme après, rouges
restés rouges, disques restés doute).

Source : photographies contributeurs Panoramax, instance IGN
(https://panoramax.ign.fr), licence **etalab-2.0** (Licence Ouverte) :
réutilisation autorisée avec mention de la source. Photo d'origine par
crop (instance IGN, `GET /api/search?ids=<id>`) :

| crop | photo Panoramax | verdict attendu |
|---|---|---|
| r1b_g01.ppm | 073e963b-2a77-492d-b6e3-50e62ccbcd20 | Green (biarritz) |
| r1b_g02.ppm | 46016145-0bf2-459f-a35c-e92b0295a2cc | Green (nancy) |
| r1b_g03.ppm | 4ca92d5e-6cf3-4947-a44e-5a1f46d0fdf3 | Green (nice) |
| r1b_g04.ppm | 396a83e4-d72b-4970-9bb2-47166e9e1e28 | Green (bayonne) |
| r1b_g05.ppm | 7070003b-afe3-4092-af0d-66f4b34dfd2e | Green (bayonne) |
| st_g01.ppm | 817c269a-1713-4a8c-883e-2a31e782fea9 | Green (cherbourg) |
| st_g02.ppm | 09e5b82b-b7b3-4956-b2f1-d99b320f0e93 | Green (nancy) |
| st_r01.ppm | dd8ee209-fd21-4ca8-a3a8-e362a60aca4d | Red (cherbourg) |
| st_r02.ppm | dd8ee209-fd21-4ca8-a3a8-e362a60aca4d | Red (cherbourg) |
| st_c01.ppm | 00854864-41e9-48aa-bdc3-35c2f9409253 | Unknown (clermont, disque) |
| st_c02.ppm | 00854864-41e9-48aa-bdc3-35c2f9409253 | Unknown (clermont, disque) |

Mention : « © contributeurs Panoramax (ign.fr), Licence Ouverte 2.0 ».
