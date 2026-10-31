# Échantillon téléphone (tests `PedLightGateTest`) — PATRON, 0 crop

Réservé aux ancres issues du lot téléphone (collecte terrain
`docs/COLLECTE-FEUX.md`, crops ≤ 48 px convertis en PPM) : elles
verrouilleront le rappel vert sur données du domaine production, comme
`feuxfr/` (recalibrage) et `feuxfr2/` (règle R1b) verrouillent le socle
Panoramax. **Aucun crop pour l'instant** : ce patron fixe le format avant
la première collecte, aucun téléchargement n'est attendu.

Source : captures téléphone de l'équipe (caissons seuls, pas de scènes,
pas de visages, pas de plaques) — aucune photo tierce, aucune mention
de licence externe requise. Ligne par crop :

| crop | lot téléphone | verdict attendu |
|---|---|---|
| _ex. `tel_gNN.ppm`_ | _ex. lot1 2026-11-0X, carrefour Y_ | _Green / Red_ |

Conventions : `tel_g*.ppm` verts piétons confirmés, `tel_r*.ppm` rouges,
`tel_c*.ppm` cas limites (disques voiture, LED froides) ; PPM P6 ≤ 48 px
(voir `README.md` à côté pour la conversion et l'embarquement).
