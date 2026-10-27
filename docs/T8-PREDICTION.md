# T8 — prédiction locale des boîtes (latence perçue)

> Relu dans le **code de cet arbre** (`origin/master`, `dfe81f3`) le 26/10 ; chaque
> affirmation porte un `chemin:ligne`. Les chiffres sont datés et attribués ; tout ce qui
> exige le téléphone et n'a pas été mesuré ici est marqué **non mesuré** — jamais deviné.
> § 1, § 4 et § 6 recorrigés le 27/10 : le module est branché derrière
> `Settings.boxPredictionEnabled` (éteint par défaut).
> La fiche de conception d'origine (`sam-vision-doc/20-latence-predict.md`, PR #100 de
> l'ancien dépôt) décrit le même contrat : le code ci-dessous est l'autorité.

## 1. Le problème, en une phrase

Le direct n'affiche une boîte neuve qu'à **chaque réponse serveur** : entre deux réponses,
l'overlay ne bouge pas, l'étiquette traîne derrière l'objet — c'est la **latence perçue**,
pas la latence réseau. `core/predict/` extrapole la dernière boîte connue entre deux
réponses, sans requête supplémentaire et sans GPU.

L'intervalle à couvrir est celui du direct : une frame en vol maximum, cadence déduite de
la latence moyenne (`LiveSession.intervalMsAdaptive()`,
`core/src/main/java/dev/sam3vision/core/live/LiveSession.kt:153`). Page caméra iSee :
`Cadence.Fast` = pas de plancher, budget 150 ms (`LiveSession.kt:43-48`) ; Direct :
350/500/900 ms (`LiveSession.kt:34`).

## 2. Ce qui est prédit, et ce qui ne l'est pas

| | |
|---|---|
| **Prédit** | la **boîte** (rectangle normalisé) de chaque id suivi : `ancre + vitesse × âge`, bornée (`MotionModel.kt:64`), éventuellement recadrée par le flot optique (`BlockShift.kt:38`). La forme dessinée est un rectangle. |
| **Pas prédit** | la **distance** : elle vient de la géométrie de l'image (`distance/DistanceEstimator.kt`, `docs/DISTANCES.md`), jamais de la prédiction — une pastille « ≈ 3,2 m » ne bouge pas parce que la boîte est extrapolée. |
| **Pas prédit** | le **masque PNG** : il reste celui du serveur tant qu'il est frais ; `PredictedObject.useMask` (`BoxPredictor.kt:31`) dit à l'UI de repasser au rectangle quand il vieillit. |
| **Pas prédit** | les **labels/scores** : ils ne changent qu'à une réponse serveur (`ServerObject`, `BoxPredictor.kt:10`). |

## 3. Les seuils, lus dans le code

| Constante | Valeur | Rôle | Source |
|---|---:|---|---|
| `MAX_HORIZON_MS` | 400 ms | âge maximal extrapolé : au-delà, l'ancre ne bouge plus | `predict/MotionModel.kt:137` |
| `CONFIDENCE_ZERO_MS` | 450 ms | sans observation, la boîte prédite ne vaut plus rien | `MotionModel.kt:140` |
| `MASK_FLOOR` | 0,7 | au-dessus, `useMask` = masque serveur ; en dessous, rectangle prédit | `MotionModel.kt:143` |
| `MIN_FOR_VELOCITY` | 2 | moins de deux observations = pas de vitesse (pas d'extrapolation) | `MotionModel.kt:146` |
| `DEFAULT_BLEND` / `DEFAULT_BETA` | 0,2 / 0,3 | ancre neuve = serveur×(1−blend) + prédit×blend | `MotionModel.kt:125,128` |
| `DEFAULT_MIN_IOU` / `JUMP_HINT_IOU` | 0,15 / 0,35 | en dessous : **ré-ancrage brutal**, pas de lissage d'un saut | `MotionModel.kt:131,134` |
| `DEFAULT_MAX_SPEED_RATIO` | 0,9 | vitesse plafonnée par la largeur de l'image (anti-fusée) | `predict/BoxPredictor.kt:199` |
| `DEFAULT_COAST_MS` / `DEFAULT_DROP_MS` | 700 / 1200 ms | id absent : prédit, puis oublié | `BoxPredictor.kt:190,193` |
| `ANCHOR_WINDOW_MS` | 60 ms | datage de l'ancre par rapport à la frame affichée | `BoxPredictor.kt:202` |
| `DEFAULT_MAX_IOU` | 0,3 | association d'une frame à l'autre (ids stables) | `predict/ObjectAssociator.kt:56` |
| `TRUST_MIN` | 0,12 | flot optique appliqué seulement s'il est sûr | `predict/BlockShift.kt:14` |
| `DEFAULT_FLOW_WEIGHT`, `PROBE_STEP_PX`, `DEFAULT_SEARCH_PX` | 0,6 · 4 px · ±6 px | poids du flot, 169 décalages testés | `BoxPredictor.kt:196`, `BlockShift.kt:136,139` |

Contrôle de cohérence : `python3 tools/check-kotlin-limits.py` → OK.

## 4. Branchement : derrière un flag éteint par défaut (depuis le 27/10)

`LivePrediction` (`app/…/live/LivePrediction.kt`) enveloppe `BoxPredictor` +
`ObjectAssociator` : `LiveViewModel.kt:94` le monte éteint, `:235` le rallume avec
`Settings.boxPredictionEnabled` (défaut `false`, `Settings.kt:65`), `:368` absorbe
chaque réponse serveur et `:373` expose les boîtes à l'overlay — qui ne dessine que
celles dont le masque serveur n'est plus frais (`useMask`). Flag éteint : couche
vide, l'écran reste exactement celui d'avant (masques serveur seuls). Les 39 `@Test`
de `core/src/test/…/predict/` verrouillent le calcul.

Le build « après » de la § 5 existe donc : même APK, flag à on.

## 5. Recette sur le téléphone (protocole avant / après)

Téléphone du poste : `2c6852ac` (2312DRA50G), **jamais `adb` nu** — le verrou est partagé.

```bash
~/bin/phone status                                                   # doit dire LIBRE (sinon : attendre)
~/bin/phone run --wait 600 --task "M95 recette T8 avant" -- adb -s 2c6852ac install -r app-avant.apk
~/bin/phone run --wait 600 --task "M95 recette T8 avant" -- adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity
~/bin/phone run --wait 600 --task "M95 recette T8 avant" -- adb -s 2c6852ac shell screenrecord --time-limit 30 /sdcard/t8-avant.mp4
~/bin/phone run --wait 600 --task "M95 recette T8" -- adb -s 2c6852ac pull /sdcard/t8-avant.mp4
# puis la même séquence avec app-apres.apk (flag de prédiction à on)
```

Attendus, à écrire dans le rapport de recette :

| À relever | Avant (prédiction off) | Après (on) |
|---|---|---|
| intervalle entre deux mouvements visibles de l'étiquette | ≈ intervalle de réponse serveur (cadence `Cadence.Fast`/`Direct`, § 1) | ≈ cadence caméra (30 fps visés), l'étiquette ne recule pas à la réponse |
| sauts de l'étiquette par minute | référence | doit **baisser** ; s'il monte, le flag reste **off** |
| `SyncReport.meanIou` (dérive prédit/serveur) | s. o. | > `DEFAULT_MIN_IOU` (0,15) ; effondrement = masque collé au mauvais objet |
| `ageMs` / `useMask` par objet | `useMask` toujours vrai | `useMask` repasse à faux ~450 ms après la dernière réponse |

Métrique de décision : **la mesure perçue décide, pas la théorie** — « la prédiction qui
bouge » peut être pire que l'overlay qui saute. Un objet immobile ne doit pas dériver ; un
objet masqué une seconde doit être prédit puis oublié (`COAST`/`DROP`, § 3).

## 6. État des mesures

| Grandeur | Valeur | Source |
|---|---|---|
| Appareil de recette | 2312DRA50G, Android 15, écran 1220 × 2712, modes 60/90/120 Hz (mode courant 60 Hz, mode par défaut 120 Hz) | mesuré le 26/10 par `~/bin/phone run … dumpsys display` (M95) |
| **T8 perçue avant/après** | **non mesuré** | le flag existe (§ 4) mais la recette avant/après (§ 5) n'a pas été jouée ; backend Modal/GPU requis pour le direct — hors mandat |
| Latence serveur, RTT, encodage JPEG | **non mesuré ici** | relevés antérieurs, hors de cet arbre (fiches `18-latence-200ms.md`, `20-latence-predict.md`) : 0,5–1,1 s serveur, ~213 ms aller-retour, 1,2 ms d'encodage — **valeurs d'un autre jour, pas de cet arbre** |
| 417 ms à deux frames en vol contre 185 ms à un seul | lu dans le commentaire de `Cadence.Fast` | `LiveSession.kt:36-41` (relevé téléphone cité par le code) |
| `@Test` de `core/src/test/…/predict/` | 39 | `grep -rho '@Test' core/src/test/java/dev/sam3vision/core/predict \| wc -l` |