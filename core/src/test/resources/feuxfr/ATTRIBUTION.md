# Échantillon feux français (tests `PedLightGateTest`)

9 crops ≤ 48 px (44 Ko) extraits du corpus de validation FR (235 lampes
labellisées, Panoramax IGN, 10/2026) : ils verrouillent le recalibrage de
`PedLightGate` — jamais de vert sur rouge, bonshommes acceptés, disques
voiture refusés. Usages : `verdict`/`acceptGreen` uniquement.

Source : photographies contributors Panoramax, instance IGN
(https://panoramax.ign.fr), licence **etalab-2.0** (Licence Ouverte) :
réutilisation autorisée avec mention de la source. Photo d'origine par
crop (instance IGN, `GET /api/search?ids=<id>`) :

| crop | photo Panoramax | verdict attendu |
|---|---|---|
| fr_red_dim_138.png | 924467b4-2bc8-4f83-8281-fba2a209415a | Unknown (rouge sombre, doute sûr) |
| fr_red_noise_732.png | e231fbcb-7bdd-4920-ba57-a341f443b6de | Unknown (bruit vert, doute sûr) |
| fr_red_fig_548.png | 54a34aea-8151-4196-945c-96d34c0c1027 | Red |
| fr_green_fig_21.png | 170ec8e6-bdf9-4428-bb43-f00aa5d3da3d | Green |
| fr_green_fig_302.png | b747580e-f305-4d6b-84ef-b0f852d7b6d0 | Green |
| fr_green_fig_243.png | 99bea708-ada1-427a-855d-47ad534b30ac | Green (petit bonhomme) |
| fr_cardisc_close_183.png | 70cb6fcf-ea12-447d-83ec-e6bf28daf031 | Unknown (disque voiture) |
| fr_cardisc_367.png | d8f6d839-14f7-4dd1-8ebe-765e29cfedb2 | Unknown (disque voiture) |
| fr_green_widepose_355.png | 2a559136-4e37-417c-a4c1-ce0ace24f120 | Unknown (pose large, limite connue) |

Mention : « © contributeurs Panoramax (ign.fr), Licence Ouverte 2.0 ».
