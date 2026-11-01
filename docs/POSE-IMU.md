# Pose IMU temps réel (distances)

`DistanceMeter.pose` est alimentée en temps réel depuis le vecteur de rotation :
la branche plan de sol (`GroundPlane`) travaille enfin en production, au lieu du
sténopé seul. La géométrie des distances reste décrite dans `DISTANCES.md`.

## Fichiers

- `app/.../pose/PitchMath.kt` — maths pures (orientation → tangage, passe-bas,
  construction de la pose), testées en JVM (`pose/PitchMathTest`).
- `app/.../pose/PitchMeter.kt` — écoute `TYPE_ROTATION_VECTOR`, rafraîchit
  `DistanceMeter.pose` à chaque échantillon (~60 ms, `SENSOR_DELAY_UI`).
- `app/.../pose/CameraHeightStore.kt` — hauteur caméra (m), préférence d'app.
- `app/.../DistanceSettings.kt` — champ « Camera height » (+ `heightText` /
  `parseHeight`, même motif que le champ de vision).
- `LiveViewModel` (Direct), `IseeViewModel` + `LiveProbe` (REC iSee) — câblage :
  `PitchMeter.start/stop` suit le cycle de vie, `pose = null` sans capteur.

## Convention d'axes

`SensorManager.getOrientation` donne `values[1] = asin(−R[7])`, où `R[7]` est la
composante « vers le ciel » de l'axe Y du téléphone (haut de l'écran). Téléphone
tenu vertical (portrait, écran face à l'utilisateur) : Y pointe vers le ciel,
`values[1] = −π/2`, axe optique horizontal → tangage `0`. Téléphone à plat,
écran vers le ciel : `values[1] = 0`, caméra plein vers le sol → tangage `+π/2`.
D'où `pitchRad = values[1] + π/2`, borné à ±π/2.

Exact pour le hochement autour de l'axe X de l'écran, téléphone tenu vertical
(roulis ≈ 0). Hors de ce cas (paysage, téléphone tordu), c'est une approximation :
la fusion reste prudente (divergence → le sténopé garde la main), donc sans
chiffre faux. Aucun remappage de rotation d'écran : l'axe optique est fixe dans
le repère du téléphone.

## Hauteur caméra : écart documenté

Demandée dans `Settings`, la hauteur vit finalement dans une préférence d'app
(`CameraHeightStore`, défaut 1,5 m, bornée 0,2–5 m) : `Settings` est dans `:core`,
gelé sur cette branche, et le motif brouillon/`onSave` n'y était pas threadable
sans dépasser la garde des 500 lignes (`SettingsScreen`). Le champ Réglages
sauvegarde donc immédiatement (pas de bouton Save), et la hauteur est relue à
chaque échantillon IMU : un changement s'applique au tangage suivant. Reste à
faire : `cameraHeightM` dans `Settings` + stores quand `:core` rouvre.

## Repli

Capteur absent, tangage manquant ou hauteur inexploitable → `pose = null` :
seule la branche sténopé travaille, comme avant le plan de sol. `stop()` remet
explicitement la pose à `null` (jamais de tangage périmé).

## Session lunettes : pas d'IMU téléphone

Téléphone en poche, son vecteur de rotation ne dit plus où regarde la tête :
en session lunettes, l'écoute IMU est coupée et la pose tombe sur la constante
`CameraPose.Glasses` (1,6 m, pitch 0 — hauteur des yeux, tête droite). Câblage :
`observeGlassesLive` (bascule à chaud lens + pose, `GlassesCalibrationWiring.kt`)
et `PitchMeter.startUnlessGlassesLive` (réactivations caméra sans bascule
`live`). Au retour téléphone, l'IMU reprend (`pitch.start()`).

## Validation sur appareil

1. Réglages → Distances : hauteur à sa valeur tenue (≈ 1,5 m).
2. Direct ou REC iSee, téléphone vertical : les pastilles des objets posés
   doivent rester stables quand on hoche doucement (le plan de sol compense).
3. Téléphone à plat, caméra vers le sol : distances au sol courtes et cohérentes.
4. Sans capteur (émulateur) : pastilles identiques à avant (sténopé seul).
