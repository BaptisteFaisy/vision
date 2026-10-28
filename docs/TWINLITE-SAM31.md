# TwinLiteNet, complément on-device de SAM 3.1

Segmentation temps réel **sur le téléphone** (0 GPU Modal) : TwinLiteNet
(MIT, chequanghuy, converti litert-community) via LiteRT neuf
(`CompiledModel` 2.2.0, cascade GPU→CPU),
dans un écran démo (Réglages → « Segment demo » ou
`adb … --es route segment`).

## Complémentarité avec SAM 3.1 (la règle du montage)

- **TwinLiteNet** = la couche rapide : zone praticable + voies à chaque frame,
  sans réseau. Il ne remplace rien.
- **SAM 3.1** (GPU `sam3-eu-a100`) garde la segmentation précise et le suivi :
  chaque masque s'exporte en prompt boîte ([SegmentMask.toPrompt], repère
  0..1000) pour désigner une région au serveur — sans nouvel envoi d'image.
- Le direct actuel n'est pas touché : l'intégration Live (masques vers
  `/live`) est l'étape suivante, pas celle-ci.

## Couleurs et guidage (écran démo)

- Overlay : **vert = on marche** (zone praticable), **rouge translucide = on
  ne marche pas** (la scène reste lisible dessous), **voies jaunes** (visibles
  sur le rouge comme sur le vert).
- `WalkGuide` (core, pur) découpe la moitié basse du masque en tiers
  gauche/centre/droite : le tiers le plus praticable donne la flèche (↑ → ←),
  sous 25 % partout = ■ obstacle. La démo affiche la flèche en grand, le
  conseil dans la ligne d'état et `walk=` dans la ligne `twinlite:` logcat.

## Contrat du modèle (vérifié le 2026-10-28, LiteRT sur image route)

- Fichier : `twinlite.tflite` (3 084 552 o, fp32), release
  `segment-models-v1`, sha256 `d4d2397e…289917` (fiche complète :
  `app/…/segment/SegmentModels.kt`).
- Entrée `serving_default_args_0` [1,3,360,640] : RGB, `x/255`, NCHW.
- Sorties : 2× [1,2,360,640] float32 — sortie 0 = zone praticable, sortie 1 =
  voies (ordre vérifié visuellement : route verte, lignes rouges) ; logits
  bruts, argmax direct (égalité ⇒ fond, comme la sample app).
- Tenu en paysage : le modèle est entraîné en 16:9 dashcam.

## Fichiers (tout est réutilisé, rien n'est dupliqué)

- `core/…/segment/` : `Segmenter` (contrat, miroir de `DepthEstimator`),
  `TwinLiteDecode` (argmax, miroir de la sample app du zoo), masques +
  prompts.
- `app/…/segment/` : `SegmentPreprocess` (resize commun + normalisation NCHW),
  `LiteRtTwinLiteSegmenter` (session LiteRT + `SerialRunGuard`, comme MiDaS),
  `SegmentCameraHost` (CameraX aperçu + analyse), `SegmentScreen` (masques +
  cadence via `LiveFpsMeter`), `SegmentModels` + `SegmentModelStore`.
- Réutilisés tels quels : `ModelDownloader.ensureSpec` (avec `dir` paramétré),
  `DepthPreprocess.resizeArgbBilinearRect` (nouveau, partagé), `uprightScaled`,
  `LiveFpsMeter`, `syntheticGradientArgb`.
- Navigation : `Route.Segment` + extra adb générique `--es route <nom>`
  (`RouteRequest`, sur le modèle de `BenchEntryPoint`).

## Recette téléphone

```bash
~/bin/phone take --task "twinlite: démo + mesure" --ttl 20m   # depuis le poste
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac install -r -d builds/sam-wt-litert-twinlite/app/build/outputs/apk/debug/app-debug.apk'
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity --es route segment'
# tenir en paysage ~30 s face à une route, puis :
ssh baptiste@100.124.18.48 '/home/baptiste/bin/phone-adb -s 2c6852ac logcat -d' | grep 'twinlite:'
~/bin/phone release
```

(`-d` : la build tarball a `versionCode = 1`, inférieur à la build git
installée — `INSTALL_FAILED_VERSION_DOWNGRADE` sinon.)

## Mesure (Samsung RF8T11NBMCV, 2026-10-28, runtime LiteRT neuf 2.2.0)

Historique : `Interpreter` CPU 1885 ms / 0,4 ips, puis délégué GPU
classique 455 ms mais masques NCHW corrompus (bandes) — d'où la migration
vers le runtime neuf.

| run | ms inférence (médiane) | ips effectifs | scène |
| --- | --- | --- | --- |
| démo `route segment` | 2-4 | 4,9-5,6 | intérieur, 0 % praticable, overlay propre (screencap) |
| banc `SegmentBenchDeviceTest` (11 runs, 1 warmup) | 2 | — | gradient synthétique 640×360, OK |
| banc `DepthBenchDeviceTest` MiDaS (5 runs) | 2 | — | gradient synthétique 256×256, OK (non-régression migration) |
| banc `DepthBenchDeviceTest` DA2 (5 runs) | 216 | — | gradient synthétique 256×256, OK |

(`ms` = LiteRT seul, `ips` = bout en bout caméra → affichage.)
