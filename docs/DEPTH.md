# Profondeur monoculaire embarquée (bêta)

Estimation de profondeur **100 % sur le téléphone** (0 GPU Modal) : une carte
de proximité par image, fusionnée aux masques SAM (médiane par masque, ordre
proche/loin, occlusion), calibrée en mètres par une ancre sténopé.
En direct, les médianes fusionnées par le serveur (`with_depth`, même carte
Modal que SAM, +10-30 ms) gagnent quand la frame les porte — l'embarqué reste
le repli (hors-ligne, ancien backend, tracker).

## Choix du modèle

- **Primaire : Depth Anything V2 Small** (24,8 M, Apache-2.0, commercial OK),
  256×256 → carte 252×252, CPU seul (NNAPI essayé le 2026-10-28 : session OK
  bit-identique mais jamais plus rapide en dos-à-dos, abandonné).
  Distribué en **`.ort` 27 Mo** (pré-optimisé, téléchargé une fois ; le paquet
  `-mobile` est exclu : il refuse le `.onnx` et ne sait pas exécuter le
  `ConvInteger` quantifié — prouvé sur Redmi) :
  `onnx/model_uint8.onnx` (`onnx-community/depth-anything-v2-small`) converti
  par `onnxruntime.tools.convert_onnx_models_to_ort` 1.18.1, sortie vérifiée
  bit-identique (voir `NOTICE-depth.txt`).
- **Repli : MiDaS v2.1 small** (21 M, MIT Intel ISL), TFLite fp32 63 Mo
  (téléchargé une fois), 256×256 → 256×256, via LiteRT `Interpreter`
  CPU 4 fils (NNAPI essayé le 2026-10-28 : 578/574 ms contre 490/568 ms CPU
  en dos-à-dos — aucun gain ; pas de délégué GPU : l'artefact 2.17
  ne livre pas ses classes Java — `NoClassDefFoundError` prouvé).
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
  `depth/DepthEstimators.kt` (sélecteur + banc), `depth/ModelDownloader.kt`
  (téléchargement + vérification sha256), `DepthSettings.kt`
  (Réglages → Profondeur). Modèle absent ⇒ message + téléchargement au
  premier test, jamais de crash.
- Réglage `depthBackend` (défaut **AUTO** = DA2 puis MiDaS, seuil < 200 ms
  tenu : 174 ms mesurés sur Redmi), persisté dans les deux stores.
- Contrainte d'écriture (parseur UBS, exit 2 sinon) : pas de `while`
  (`for` bornés + `break`), pas de `if`-expression (instructions + retours),
  pas de sélection de propriété sur ressource fermable (lire le modèle en
  `ByteArray`, comme le moteur ONNX).

## Modèles : téléchargement à l'exécution

L'APK ne porte plus les ~92 Mo : au premier test, le moteur choisi est
téléchargé depuis la release `depth-models-v1`
(`github.com/BaptisteFaisy/vision/releases/tag/depth-models-v1`), vérifié par
sha256 (renommage atomique, `.tmp` nettoyé en cas d'échec), puis réutilisé
depuis `<filesDir>/depth-models`. Hors-ligne ⇒ message « réseau indisponible »,
le banc ne tourne pas dans le vide. Dev hors-ligne : poser les fichiers en
assets (mêmes noms) — le fichier téléchargé gagne, l'asset suit en repli.

## Guidage (direct)

Les mètres profondeur alimentent le direct existant (pastilles, voix,
consignes « personne à 2 mètres à midi, va à gauche », flèches) :
- `:core` `depth/DepthReadings.kt` (pur, testé) : médiane de proximité par
  boîte, échelle calibrée par les ancres sténopé (médiane des
  mètres × proximité), remplace le sténopé quand elle mesure (même sans
  hauteur connue : trottoirs, murs), l'ajoute quand il est muet, le garde
  sinon. Mètres bornés [0,3 m, 60 m].
- `:app` `depth/LiveDepthMeter.kt` : infère la dernière frame JPEG en fond
  (~5 Hz), moteur paresseux (téléchargement puis ouverture), carte de plus
  d'une seconde = repli sténopé. OFF = zéro coût.
- `with_depth` (même carte Modal, `WS /live/ws`) : la trame demande les
  médianes quand le moteur n'est pas OFF (`LiveProbe`, via
  `LiveMaskTransport`) ; `SegObject.depth` (0..1, grand = proche) ; le
  mesureur préfère `refineWithCloseness` (serveur) à la carte embarquée.
  Serveur muet ou ancien backend : repli embarqué puis sténopé, sans erreur.
- `LiveProbe.publish` : `offer(jpegB64)` puis `readingsFor` avant
  pastilles/voix/consignes (3 lignes) ; voix et flèches inchangées (une
  seule voix, le danger parle d'abord).

## Banc et recette (téléphone réel, jamais d'émulateur)

1. `build-serveur <worktree>` puis installation sur le téléphone du créneau
   (`PHONE_SERIAL` : Redmi `2c6852ac` ou Samsung `RF8T11NBMCV` — créneau via
   le manager tests, cron `vision-phone-latest` gelé pendant le run).
2. Réglages → Profondeur → moteur → « Tester la profondeur (5 inférences) »
   (télécharge le modèle au premier appui : ~26 Mo DA2, ~63 Mo MiDaS).
3. Verdict : bas de page + `phone-adb -s $PHONE_SERIAL logcat -d | grep DepthBench`
   (`backend=DA2 medianMs=… runs=5 map=252x252`).
4. Seuil < 200 ms tenu (DA2 : 174 ms) : actif par défaut (AUTO) depuis le
   câblage guidage — pastilles, voix et consignes reçoivent les mètres
   affinés, OFF reste disponible dans les Réglages.
