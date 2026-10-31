# Profondeur monoculaire embarquée (bêta)

Estimation de profondeur **100 % sur le téléphone** (0 GPU Modal) : une carte
de proximité par image, fusionnée aux masques SAM (médiane par masque, ordre
proche/loin, occlusion), calibrée en mètres par une ancre sténopé.
En direct, les médianes fusionnées par le serveur (`with_depth`, même carte
Modal que SAM, +10-30 ms) gagnent quand la frame les porte — l'embarqué reste
le repli (hors-ligne, ancien backend, tracker).

## Choix du modèle

- **Moteur unique : Depth Anything V2 Small** (24,8 M, Apache-2.0, commercial
  OK), 256×256 → carte 252×252, CPU seul (NNAPI essayé le 2026-10-28 :
  session OK bit-identique mais jamais plus rapide en dos-à-dos, abandonné).
  Distribué en **`.ort` 27 Mo** (pré-optimisé, téléchargé une fois ; le paquet
  `-mobile` est exclu : il refuse le `.onnx` et ne sait pas exécuter le
  `ConvInteger` quantifié — prouvé sur Redmi) :
  `onnx/model_uint8.onnx` (`onnx-community/depth-anything-v2-small`) converti
  par `onnxruntime.tools.convert_onnx_models_to_ort` 1.18.1, sortie vérifiée
  bit-identique (voir `NOTICE-depth.txt`). Verdict Redmi : 174 ms (~5,7 Hz).
- Supprimé le 2026-10-29 : le repli **MiDaS v2.1 small** (TFLite fp32 63 Mo,
  275 ms sur Redmi, hors seuil < 200 ms) — DA2, plus précis et ~1,6x plus
  rapide, reste seul. Le fichier reste sur la release `depth-models-v1`
  (rien ne le télécharge plus).
- Exclus : DA-V2 Base/Large/Giant + UniDepth (CC-BY-NC), ZoeDepth/Marigold/
  DepthFM/Metric3D/DepthPro (poids ou licence). Le modèle retenu est
  **relatif** (grand = proche) : l'échelle métrique vient du sténopé
  (`DepthFusion.calibrateScale`), jamais de la carte seule.
- Attributions : `app/src/main/assets/NOTICE-depth.txt`.

## Code

- `:core` (pur JVM) : `depth/DepthMap.kt` (proximité, bilinéaire),
  `depth/DepthFusion.kt` (médiane par masque en une passe, quickselect O(n),
  ordre, occlusion, calibration), `depth/DepthEstimator.kt` (interface,
  `DepthBackend`, image synthétique), `model/Geometry.kt`
  (rétroprojection, Box3D). Tests JVM à côté.
- `:app` : `depth/OnnxDepthEstimator.kt`, `depth/DepthEstimators.kt`
  (sélecteur + banc), `depth/ModelDownloader.kt` (téléchargement +
  vérification sha256), `DepthSettings.kt` (Réglages → Profondeur). Modèle
  absent ⇒ message + téléchargement au premier test, jamais de crash.
- Réglage `depthBackend` (toujours **DA2**, seuil < 200 ms tenu : 174 ms
  mesurés sur Redmi), persisté dans les deux stores. L'option Coupée est
  supprimée (31/10, ordre user) : réglages anciens (`AUTO`, `MIDAS`, `OFF`)
  migrés vers DA2 par `DepthBackend.fromName`.
- Contrainte d'écriture (parseur UBS, exit 2 sinon) : pas de `while`
  (`for` bornés + `break`), pas de `if`-expression (instructions + retours),
  pas de sélection de propriété sur ressource fermable (lire le modèle en
  `ByteArray`, comme le moteur ONNX).

## Modèles : téléchargement à l'exécution

L'APK ne porte pas les ~27 Mo du modèle : au premier test, le moteur est
téléchargé depuis la release `depth-models-v1`
(`github.com/BaptisteFaisy/vision/releases/tag/depth-models-v1`), vérifié par
sha256 (renommage atomique, `.tmp` nettoyé en cas d'échec), puis réutilisé
depuis `<filesDir>/depth-models`. Hors-ligne ⇒ message « réseau indisponible »,
le banc ne tourne pas dans le vide. Dev hors-ligne : poser les fichiers en
assets (mêmes noms) — le fichier téléchargé gagne, l'asset suit en repli.

## Guidage (direct)

Les mètres profondeur alimentent le direct existant (pastilles, voix,
consignes « va à gauche, 2 mètres », flèches) :
- `:core` `depth/DepthReadings.kt` (pur, testé) : médiane de proximité par
  boîte, échelle calibrée par les ancres (sténopé + plan de sol, médiane
  des mètres × proximité), remplace le sténopé quand elle mesure (même sans
  hauteur connue : trottoirs, murs), l'ajoute quand il est muet, le garde
  sinon. Mètres bornés [0,3 m, 60 m].
- `:app` `depth/LiveDepthMeter.kt` : infère la dernière frame JPEG en fond
  (~5 Hz), moteur paresseux (téléchargement puis ouverture), carte de plus
  d'une seconde = repli sténopé.
- `with_depth` (même carte Modal, `WS /live/ws`) : la trame demande toujours
  les médianes (`LiveProbe`, via `LiveMaskTransport`) ;
  `SegObject.depth` (0..1, grand = proche) ; le mesureur préfère
  `refineWithCloseness` (serveur) à la carte embarquée. Serveur muet ou
  ancien backend : repli embarqué puis sténopé, sans erreur.
- `LiveProbe.publish` : `offer(jpegB64)` puis `readingsFor` avant
  pastilles/voix/consignes (3 lignes) ; voix et flèches inchangées (une
  seule voix, le danger parle d'abord).

## Banc et recette (téléphone réel, jamais d'émulateur)

1. `build-serveur <worktree>` puis installation sur le téléphone du créneau
   (`PHONE_SERIAL` : Redmi `2c6852ac` ou Samsung `RF8T11NBMCV` — créneau via
   le manager tests, cron `vision-phone-latest` gelé pendant le run).
2. Réglages → Profondeur → « Tester la profondeur (5 inférences) »
   (télécharge le modèle au premier appui : ~26 Mo).
3. Verdict : bas de page + `phone-adb -s $PHONE_SERIAL logcat -d | grep DepthBench`
   (`backend=DA2 medianMs=… runs=5 map=252x252`).
4. Seuil < 200 ms tenu (DA2 : 174 ms) : profondeur toujours active depuis
   le 31/10 (option Coupée supprimée) — pastilles, voix et consignes
   reçoivent les mètres affinés.
