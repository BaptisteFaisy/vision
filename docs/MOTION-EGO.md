# Ego-motion — qui bouge, du véhicule ou de la caméra (fusion motion)

> Branche `feat/motion-fusion` (base `02b3bf67`), 4 pistes fusionnées le 31/10.
> Tout ce qui suit est lu dans le code de cet arbre ; les seuils sont des
> **priors à recaler sur téléphone**, jamais des mesures — voir § 7.

## 1. Le problème, en une phrase

Un pan ou un zoom caméra fait gonfler et défiler les boîtes comme un véhicule
qui approche : sans compensation, iSee annonce un danger quand c'est le
porteur qui bouge. Les 4 pistes soustraient l'ego-motion par des voies
indépendantes, chacune conservatrice : **le doute ne tait jamais, le doute ne
tranche jamais** — sans preuve, le comportement V1 est gardé tel quel.

## 2. Piste 1 — vote fond vs objet (`EgoCompensator`)

Principe : deux voies votent — le déplacement de la boîte (translation SAD par
boîte) et le mouvement global du fond (`MotionSample` : translation + échelle).
Le résidu `boîte − ego-motion projetée à la boîte` est le mouvement propre :
franc ⇒ `MOVING`, nul ⇒ `STATIC` (que la caméra panne ou non), le reste ⇒
`UNCERTAIN`. La projection tient compte du zoom (le fond ne défile pas pareil
au centre et au bord) ; la croissance de boîte est décotée séparément du zoom
du fond (`discountGrowth`, `areaGrowing`). Sans fond prouvé : repli boîte
seule (comportement historique).

Branchement :

- `core/predict/EgoCompensator.kt` : `decide` (×2), `projectedEgo`,
  `discountGrowth`, `areaGrowing`, `linearRateFromAreas`
- `core/traverse/VehicleGrowth.kt` : `isVehicleGrowth` (fusion P1+P2) ←
  `VehicleStateWatcher.movingPick` (`core/traverse/VehicleState.kt`)
- `core/traverse/VehicleApproach.kt` : `lastGlobal` / `lastGlobalDtMs` reçus
  via `update(motion)` ; `reset` les oublie
- `core/local/BlockMotionAdapter.kt` : pont `BlockMotionSource` → calque
  (`MotionSource`, plan `LumaPlane` 256×103), chemin live

## 3. Piste 2 — confirmation par la profondeur (`DepthConfirm`)

Principe : une boîte qui grossit n'est une approche que si sa proximité
augmente avec sa taille — croissance à profondeur constante ⇒ pan/zoom, pas
approche. La profondeur a un **droit de veto, pas un pouvoir d'exigence** :
sans mesure, le verdict géométrique est gardé. Source embarquée
(`DepthReadings.boxCloseness`) prioritaire, serveur (`SegObject.depth`) en
repli (`resolve`). En plus : seuils d'expansion par label — un véhicule est
un danger grave (seuil abaissé, couloir élargi, TTC plafonné), les défauts
personnes sont inchangés.

Branchement :

- `core/depth/DepthConfirm.kt` : `resolve`, `rises`, `contradicts`,
  `confirmsGrowth`
- `core/traverse/VehicleApproach.kt` : `Track.firstCloseness/lastCloseness`,
  `update(depthByBox)`, `closenessOf`
- `core/traverse/TraverseVeto.kt` : `update(depthByBox)` →
  `approachVerdict(depthByBox)`
- `core/traverse/TraversePipeline.kt` : `DepthReadings.serverCloseness` vers
  le veto (repli serveur ; sonde embarquée non branchée — voir § 8)
- `core/danger/ApproachEstimator.kt` : `thresholdsForLabel` ;
  `core/traverse/VehicleApproach.kt` : `VEHICLE_APPROACH_THRESHOLDS`,
  `approachThresholdsFor`

## 4. Piste 3 — résidu SAM (`SamResidu`, `MaskShift`)

Principe : on warpe le masque SAM du keyframe par l'ego-motion (convertie en
pixels de masque) puis on mesure l'écart au masque courant (IoU) : fort résidu
⇒ l'objet bouge vraiment (`OBJECT_MOVED`), faible résidu ⇒ c'est la caméra
(`CAMERA_MOVED`, à croiser avec `egoPx` : ego ≈ 0 ⇒ arrêt, ego franc ⇒ caméra
mobile). Masque vide, confiance basse, ego inutilisable, ancre périmée ⇒
`UNCERTAIN`. En amont, `estimateMaskedShift` mesure le décalage SAD sur les
seuls pixels du masque (le fond capturé dans le rectangle ne pollue plus) ;
`BlockShift.kt` expose ses internes (`sad`, `bestIn`, `sampleRect`,
`centeredOffsets`, `confidence`, `MIN_SAMPLES`) sans dupliquer la machinerie.
Hooks `// PISTE3` non invasifs posés, décisionnel reporté (voir § 8).

Branchement :

- `core/predict/SamResidu.kt` : `decide`, `MotionSample.toMaskMotion`
- `core/predict/MaskShift.kt` : `estimateMaskedShift`
- `core/predict/BoxPredictor.kt`, `core/traverse/VehicleApproach.kt` :
  commentaires `// PISTE3` / `PISTE3+FUSION` (point de corroboration futur)

## 5. Piste 4 — compensation gyro (`GyroCompensation`, `GyroSource`)

Principe : le bloc-matching global sature en pan rapide (recherche ±16 px,
modèle sans rotation) ; le vecteur de rotation du téléphone mesure le pan
directement. Modèle sténopé au premier ordre : `dx ≈ f·Δyaw`,
`dy ≈ f·Δpitch` (valide à 4 % près jusqu'à 0,35 rad). Garde-fous : rotation
excessive, delta périmé, focale manquante ⇒ `null` (incertain, jamais un résidu
inventé). Ordre avec la piste 1 : le résidu **visuel** passe d'abord ; le gyro
n'est qu'un fond de repli (rotation pure, `GyroHint.toSample`) quand le fond
mesuré est inutilisable. Côté app, `GyroSource` lit le vecteur de rotation en
`SENSOR_DELAY_GAME` (~20 ms) avec historique horodaté (`deltaBetween`) ;
signes capteur → image **à valider sur téléphone** avant tout usage
décisionnel. Câblage app reporté (voir § 8).

Branchement :

- `core/motion/GyroCompensation.kt` : `expectedShift`, `residual`,
  `shortestDelta` (+ `GyroDelta`, `StampedDelta`, `GyroHint`, `GyroResidual`)
- `app/.../app/GyroSource.kt` : `start`, `stop`, `deltaBetween`, `hasSensor`
- `core/traverse/VehicleBackground.kt` : `decide(gyro = …)`, `visualUsable`
- `app/.../app/traverse/TraverseWiring.kt` : recette du câblage en commentaire
  `FUSION P4` (au point d'appel, avant `pipeline.onFrame`)

## 6. Verdicts unifiés — `traverse.Verdict` seul vocabulaire

`Verdict` est le seul vocabulaire du chemin véhicule ; les verdicts génériques
des pistes s'y convertissent (jamais l'inverse, `predict` n'importe pas
`traverse`). `null` = doute : l'appelant garde son verdict.
Couvert par `VerdictFusionTest` (7 tests).

| Source | Mapping | Code |
|---|---|---|
| `EgoVerdict.MOVING` | `Verdict.VEHICLE_MOVED` | `VehicleBackground.kt` : `EgoVerdict.toVerdict` |
| `EgoVerdict.STATIC` | `Verdict.STATIC` | idem |
| `EgoVerdict.UNCERTAIN` | `null` (garder le verdict) | idem |
| `ResiduDecision(OBJECT_MOVED)` | `Verdict.VEHICLE_MOVED` | `VehicleBackground.kt` : `ResiduDecision.toVerdict` |
| `ResiduDecision(CAMERA_MOVED, ego≈0)` | `Verdict.STATIC` | idem (`egoPx ≤ egoStillPx`) |
| `ResiduDecision(CAMERA_MOVED, ego franc)` | `Verdict.CAMERA_MOVED` | idem |
| `ResiduDecision(UNCERTAIN)` | `null` (garder le verdict V1) | idem |

## 7. Seuils = priors à recaler (valeurs exactes du code)

| Constante | Valeur | Rôle | Source |
|---|---:|---|---|
| `EgoCompensator.STILL_SPEED` | 0,02 /s | dérive max d'un arrêt | `predict/EgoCompensator.kt` |
| `EgoCompensator.MOTION_RATIO` | 0,03 /s | au-delà, résidu franc | idem |
| `EgoCompensator.TRUST_MIN` | 0,12 (`Shift.TRUST_MIN`) | confiance min. d'une voie | idem |
| `EgoCompensator.SCALE_TOLERANCE` | 0,5 | croissance décotée jusqu'à +50 % du zoom | idem |
| `EgoCompensator.GROWTH_EPS` | 0,005 /s | plancher de bruit d'une croissance | idem |
| `DepthConfirm.MIN_RISE_ABS` | 0,005 | hausse absolue min. de proximité | `depth/DepthConfirm.kt` |
| `DepthConfirm.MIN_RISE_REL` | 0,02 | hausse relative min. (2 %) | idem |
| `ApproachThresholds` personnes | 0,03 / 0,25 / pas de plafond | défauts historiques inchangés | `danger/ApproachEstimator.kt` |
| `VEHICLE_APPROACH_THRESHOLDS` | 0,02 / 0,30 / TTC 4 s | véhicules : seuil ↓, couloir ↑, TTC plafonné | `traverse/VehicleApproach.kt` |
| `SamResidu.IOU_MOVED_MAX` | 0,75 | sous ce seuil, objet mobile | `predict/SamResidu.kt` |
| `SamResidu.IOU_STILL_MIN` | 0,85 | au-dessus, caméra ; entre les deux, doute | idem |
| `SamResidu.SAM_SCORE_MIN` | 0,5 | score SAM min. (miroir `TraverseVeto.SCORE_MIN`) | idem |
| `SamResidu.MIN_MASK_PIXELS` | 64 (8×8) | aire objet min. | idem |
| `RESIDU_STILL_EGO_PX` | 1 px | sous ce seuil, résidu nul ⇒ arrêt | `traverse/VehicleBackground.kt` |
| `GyroCompensation.MAX_DELTA_RAD` | 0,35 rad (~20°) | rotation max. compensée | `motion/GyroCompensation.kt` |
| `GyroCompensation.MAX_AGE_MS` | 250 ms | âge max. du delta | idem |
| `GyroCompensation.TRUST_MIN` | 0,12 | confiance min. du hint | idem |
| `GyroSource.YAW_SIGN` / `PITCH_SIGN` | −1 / −1 | signes capteur → image, **à valider** | `app/GyroSource.kt` |
| `GyroSource.MATCH_TOLERANCE_MS` | 100 ms | écart max. borne ↔ échantillon | idem |
| `GyroSource.MAX_SAMPLES` | 128 (~2,5 s) | historique borné | idem |

## 8. Recette téléphone à prévoir

Signes gyro (prérequis à tout usage décisionnel, `GyroSource.YAW_SIGN` /
`PITCH_SIGN`) :

1. **Pan gauche** : la scène doit partir vers la droite (`dYaw > 0`) pendant
   que l'azimut capteur décroît — sinon inverser `YAW_SIGN`.
2. **Tilt haut** : la scène doit partir vers le bas (`dPitch > 0`) pendant que
   `getOrientation[1]` décroît — sinon inverser `PITCH_SIGN`.
3. Comparer aux `dx, dy` mesurés par le bloc-matching sur les mêmes gestes.

Scénarios (téléphone tenu vertical, portrait, caméra arrière) :

- **Pan + voiture garée** : la boîte défile et gonfle, aucune annonce
  « approche » ne doit sortir (vetos P1+P2) ; à l'arrêt du pan, silence.
- **Suivi d'un véhicule qui roule** (caméra qui suit) : l'annonce de mouvement
  doit tenir malgré le fond qui défile (résidu franc).
- **Passage latéral** (caméra fixe, véhicule qui traverse) : verdict
  `VEHICLE_MOVED` franc, puis `STATIC`/silence une fois sorti.
- Recalage : les seuils du § 7 se règlent sur ces scénarios (bande de doute
  IoU 0,75–0,85 et `RESIDU_STILL_EGO_PX` en premier).

## 9. Les 3 câblages reportés (et pourquoi)

1. **Gyro app** (`GyroSource` → `VehicleBackground.decide(gyro = …)`) :
   reporté car les signes `YAW/PITCH` ne sont lus que depuis la doc Android et
   `docs/POSE-IMU.md` — un signe inversé accuserait le monde à chaque pan.
   Prérequis : recette § 8 (pan gauche, tilt haut), puis suivre la recette en
   commentaire `FUSION P4` dans `TraverseWiring.kt` (lifecycle du mode, `start`
   sur thread main, `deltaBetween` → `expectedShift` focale luma → `GyroHint`).
2. **`depthByBox` sonde embarquée** : aujourd'hui seul le repli serveur
   alimente la profondeur (`DepthReadings.serverCloseness` dans
   `TraversePipeline`) ; `boxCloseness` (médiane sur carte embarquée) n'est
   branché nulle part dans le chemin traverse. Prérequis : une `DepthMap`
   embarquée disponible au point d'appel, puis `resolve` la prendra en
   priorité sans autre changement.
3. **`SamResidu.decide` décisionnel** : le résidu masque est pur et testé
   (14 tests `SamResiduTest`, `MaskShiftTest`), mais les pistes
   `VehicleApproach` ne retiennent pas le masque SAM de l'objet. Prérequis :
   décodage du masque + association inter-frames (id stable), puis corroborer
   au point marqué `PISTE3+FUSION` dans `VehicleApproach.evaluateMotion`
   (verdict via `ResiduDecision.toVerdict`, `null` = garder le verdict V1).

## 10. Preuves (tests unitaires, sans téléphone)

`EgoCompensatorTest`, `EgoVoteWatcherTest` (séquences caméra : pan, suivi,
zoom, dilatation), `DepthConfirmTest`, `TraverseVetoDepthTest`,
`VehicleApproachDepthTest`, `VehicleStateDepthTest`,
`TraversePipelineDepthTest`, `ApproachThresholdsTest`, `ApproachLabelsTest`,
`MaskShiftTest`, `SamResiduTest` (14 tests), `GyroCompensationTest`,
`VehicleBackgroundTest` (repli gyro), `VerdictFusionTest`,
`BlockShiftTest` (via `ShiftScenes` partagé).
