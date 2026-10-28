# Profondeur monoculaire embarquée (bêta)

Estimation de profondeur **100 % sur le téléphone** (0 GPU Modal) : une carte
de proximité par image, fusionnée aux masques SAM (médiane par masque, ordre
proche/loin, occlusion), calibrée en mètres par une ancre sténopé.

## Choix du modèle

- **Primaire : Depth Anything V2 Small** (24,8 M, Apache-2.0, commercial OK),
  256×256 → carte 252×252. Embarqué en **`.ort` 27 Mo** (pré-optimisé ;
  le paquet `-mobile` est exclu : il refuse le `.onnx` et ne sait pas
  exécuter le `ConvInteger` quantifié — prouvé sur Redmi) : `onnx/model_uint8.onnx`
  (`onnx-community/depth-anything-v2-small`) converti par
  `onnxruntime.tools.convert_onnx_models_to_ort` 1.18.1, sortie vérifiée
  bit-identique (voir `NOTICE-depth.txt`).
- **Repli : MiDaS v2.1 small** (21 M, MIT Intel ISL), TFLite fp32 64 Mo,
  256×256 → 256×256, via LiteRT `Interpreter` CPU (4 fils).
- Exclus : DA-V2 Base/Large/Giant + UniDepth (CC-BY-NC), ZoeDepth/Marigold/
  DepthFM/Metric3D/DepthPro (poids ou licence). Les deux retenus sont
  **relatifs** (grand = proche) : l'échelle métrique vient du sténopé
  (`DepthFusion.calibrateScale`), jamais de la carte seule.
- Attributions : `app/src/main/assets/NOTICE-depth.txt`.

## Code

- `:core` (pur JVM) : `depth/DepthMap.kt` (proximité, bilinéaire),
  `depth/DepthFusion.kt` (médiane par masque en une passe, quickselect O(n),
  ordre, occlusion, calibration), `depth/DepthEstimator.kt` (interface,
  `DepthBackend`, `NoOp`, image synthétique), `model/Geometry.kt`
  (rétroprojection, Box3D). Tests JVM à côté.
- `:app` : `depth/OnnxDepthEstimator.kt`, `depth/LiteRtDepthEstimator.kt`,
  `depth/DepthEstimators.kt` (sélecteur + banc), `DepthSettings.kt`
  (Réglages → Profondeur). Asset absent ⇒ échec explicite, jamais de crash.
- Réglage `depthBackend` (défaut **coupé**), persisté dans les deux stores.
- Contrainte d'écriture (parseur UBS, exit 2 sinon) : pas de `while`
  (`for` bornés + `break`), pas de `if`-expression (instructions + retours),
  pas de sélection de propriété sur ressource fermable (lire l'asset en
  `ByteArray`, comme le moteur ONNX).

## Banc et recette (téléphone réel, jamais d'émulateur)

1. `build-serveur <worktree>` puis installation sur le téléphone du créneau
   (`PHONE_SERIAL` : Redmi `2c6852ac` ou Samsung `RF8T11NBMCV` — créneau via
   le manager tests, cron `vision-phone-latest` gelé pendant le run).
2. Réglages → Profondeur → moteur → « Tester la profondeur (5 inférences) ».
3. Verdict : bas de page + `phone-adb -s $PHONE_SERIAL logcat -d | grep DepthBench`
   (`backend=DA2 medianMs=… runs=5 map=252x252`).
4. Seuil : médiane < 200 ms pour activer par défaut ; sinon le repli MiDaS
   est mesuré, puis câblage aux pastilles (phase 2, hors périmètre ici).
