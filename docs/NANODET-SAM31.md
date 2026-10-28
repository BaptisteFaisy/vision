# NanoDet-Plus 320, complément on-device de SAM 3.1

Détection d'objets temps réel **sur le téléphone** (0 GPU Modal) : NanoDet-Plus
320 (Apache-2.0, RangiLyu) via ONNX Runtime, dans un écran démo
(Réglages → « NanoDet demo » ou `adb … --es route nanodet`).

## Complémentarité avec SAM 3.1 (la règle du montage)

- **NanoDet** = la couche rapide : ~12 ms annoncés sur ARM, boîtes immédiates
  à chaque frame, sans réseau. Il ne remplace rien.
- **SAM 3.1** (GPU `sam3-eu-a100`) garde la segmentation précise et le suivi :
  chaque boîte NanoDet s'exporte en prompt ([DetectBox.toSamPrompt], repère
  0..1000) pour désigner une région au serveur — sans nouvel envoi d'image.
- Le direct actuel n'est pas touché : l'intégration Live (ROI auto vers
  `/live`) est l'étape suivante, pas celle-ci.

## Contrat du modèle (vérifié le 2026-10-28, ORT sur `bus.jpg`)

- Fichier : `nanodet-plus-m_320.onnx` (4 793 615 o, fp32), release
  `detect-models-v1`, sha256 `4f12723c…2c610` (fiche complète :
  `app/…/detect/DetectModels.kt`).
- Entrée `data` [1,3,320,320] : BGR, `(px − moyenne) / écart` sur 0-255,
  moyennes `[103.53, 116.28, 123.675]`, écarts `[57.375, 57.12, 58.395]`.
- Sortie `output` [1,2125,112] : 80 scores **déjà sigmoïde** (dans le graphe,
  ne pas re-sigmoïder) + 4 côtés × 8 paliers DFL ; pas 8/16/32/64,
  `reg_max = 7`, centre `(x·pas, y·pas)` sans +0,5.
- Seuils démo amont : score 0,35, NMS 0,6 (par classe, aires +1 px).

## Fichiers (tout est réutilisé, rien n'est dupliqué)

- `core/…/detect/` : `Detector` (contrat, miroir de `DepthEstimator`),
  `NanoDetDecode` (miroir exact de `NanoDet.cpp` amont), 80 labels COCO.
- `app/…/detect/` : `NanoDetPreprocess` (resize commun + normalisation BGR),
  `OnnxNanoDetDetector` (session ORT + `SerialRunGuard`, comme la profondeur),
  `NanoDetCameraHost` (CameraX aperçu + analyse), `NanoDetScreen` (boîtes +
  cadence via `LiveFpsMeter`), `DetectModels` + `DetectModelStore`.
- Réutilisés tels quels : `ModelDownloader.ensureSpec` (avec `dir` paramétré),
  `DepthPreprocess.resizeArgbBilinear`, `uprightScaled`, `LiveFpsMeter`,
  `syntheticGradientArgb`.
- Navigation : `Route.NanoDet` + extra adb générique `--es route <nom>`
  (`RouteRequest`, sur le modèle de `BenchEntryPoint`).

## Recette téléphone

```bash
~/bin/phone take --task "nanodet: démo + ips" --ttl 20m   # depuis le poste
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac install -r -d builds/sam-wt-nanodet/app/build/outputs/apk/debug/app-debug.apk'
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity --es route nanodet'
# laisser tourner ~30 s face à une scène, puis :
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac logcat -d' | grep 'nanodet:'
~/bin/phone release
```

(`-d` : la build tarball a `versionCode = 1`, inférieur à la build git
installée — `INSTALL_FAILED_VERSION_DOWNGRADE` sinon.)

## Mesure (Redmi 2c6852ac, CPU 4 threads)

| run | ms inférence (médiane) | ips effectifs | scène |
| --- | --- | --- | --- |
| 1 | … | … | … |

(`ms` = ORT seul, `ips` = bout en bout caméra → affichage.)
