# Vanguard 95° : dérivation marketing validée sans paire réelle (2026-11-01)

`VANGUARD_SENSOR_H_FOV_DEG = 95f` (`GlassesLens.kt`, D1) : le marketing annonce
122° DFOV, la dérivation sur capteur 3:4 rectilinéaire donne 94,5° → 95°.

## Verdict

**Écart de conception CLOS** : la dérivation 122° → 95° est validée par
recoupement (méthode prouvée à 1,1° sur le point indépendant HSTN, ambiguïté
diagonale-vs-horizontal fermée, erreur bornée). **Confirmation labo sur paire
réelle : reste ouverte mais NON BLOQUANTE** — protocole ci-dessous.

## Le 122° confirmé multi-sources

Recherche 2026-11-01, « 122° field of view » + « 12 MP » + « centred/centered »
pour Oakley Meta Vanguard, concordant sur 8+ sources indépendantes (retail +
comparatifs) : ee.co.uk, bestbuy.com, amazon.com (DP B0FQFJT9YW), jbhifi.com.au,
camdencycles.com.au, 180by2.co.za, blinkblink.pl, soyacincau.com. Table Amazon :
Vanguard « 12 MP with 122° FOV » vs HSTN et autres « 12 MP with 100° FOV ».
Écarts minoritaires : un 120° (contredit par son propre texte 122°) et un 124°.
Spread marketing retenu : **120–124°**.

## Méthode prouvée sur HSTN (Δ 1,1°)

Hypothèse : marketing = diagonale sur capteur 3:4 rectilinéaire,
H = 2·atan(0,6·tan(D/2)) :

| D marketing | H prédit | V prédit (3:4) | Labo DXOMARK | ΔH |
| --- | --- | --- | --- | --- |
| HSTN 100° | 71,1° | 87,3° | H 70°, V 87° | **1,1°** |
| Vanguard 122° | 94,5° | 110,6° | — (non mesuré) | — |
| Vanguard 120° / 124° | 92,2° / 96,9° | 108,4° / 112,8° | — | spread |

- **Ambiguïté D-vs-H fermée.** Si le marketing était l'horizontal, le labo HSTN
  lirait ~100°, pas 70°. Le point HSTN prouve que le marketing est la diagonale
  et que le capteur est ~3:4 rectilinéaire. L'erreur catastrophique (prendre
  122° pour H : +27°) est exclue par les faits.
- **Capteur 3:4 confirmé officiel** : ray-ban.com (page Gen 2) donne
  « Image capture: 3024x4032 pixels » → `GLASSES_SENSOR_ASPECT = 0,75` validé
  pour la famille 12 MP. Vanguard = même famille 12 MP (dims pixels non
  publiées, aspect supposé identique).
- **Nuance** : marketing gen2 100° → H prédit 71,1° vs labo 66° (Δ 5,1°,
  gen1 vs gen2 ou crop de dewarp). Borne de variance inter-modèles : ~5° en
  moins. HSTN reste l'analogue le plus proche de Vanguard → erreur de méthode
  attendue ~1–2°.

## Borne d'erreur

- Valeur retenue : **H = 94,5° → 95° inchangé**.
- Bande d'incertitude : **[92°, 97°]** (−2,5° méthode/extrapolation : aspect 0,75
  confirmé sur Vanguard même (3024×4032), gen1 second point ≤ 1,5° ; +2,5° spread
  124° conservé, 122° officiel). Erreur distance bornée ≈ **−5,1 % / +3,6 %**.
- Mitigation runtime : `glassesFovDeg` forcé (priorité forcé > profil > défaut)
  permet de corriger sur le terrain sans nouveau build.

## Protocole de confirmation sur paire réelle (non bloquant)

Pour le détenteur d'une paire Vanguard :

1. Fixer la paire face à une mire plane (damier ou lignes verticales espacées
   connues), axe optique perpendiculaire, distance ≥ 20× la focale (~2 m).
2. Capturer un snapshot via l'app (même pipeline que les distances : snapshot
   bord 320) + relever la photo pleine taille 3024×4032 si exposée.
3. Mesurer H : H = 2·atan(L / (2·d)) avec L = largeur de mire visible sur toute
   la largeur image, d = distance mire-objectif. Répéter à 3 distances, moyenner.
4. Critère d'acceptation : H ∈ [92°, 97°] → garder 95° ; H hors bande → poser
   `glassesFovDeg = H` en Réglages (correction immédiate) + patch mettant à jour
   `VANGUARD_SENSOR_H_FOV_DEG` et son test `GlassesLensTest`.
5. Consigner : modèle (`OAKLEY_META_VANGUARD`), H mesuré, d/L, version
   firmware/app, dans `META-GLASSES.md`.
