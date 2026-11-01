# Profondeur monoculaire (serveur GPU)

La distance des objets vient d'une carte de profondeur **calculée sur le GPU
Modal**, pas sur le téléphone. Chaque frame du direct demande `with_depth` ; le
serveur joint la proximité de chaque objet (`SegObject.depth`, 0..1, grand =
proche) et le téléphone s'en sert pour affiner ses distances sténopé.

## Ce qui a changé le 01/11 (ordre user)

**L'inférence embarquée est supprimée** : moteur ONNX Runtime, modèle `.ort` 27
Mo, téléchargement à l'exécution, banc « Tester la profondeur », réglage
`depthBackend` et dépendance `onnxruntime-android` ont disparu (PR à venir).
Raison : le téléphone ne pouvait pas gagner. La carte embarquée était déjà
court-circuitée dès que le serveur envoyait ses médianes (`depthInferenceNeeded`,
seuil `SERVER_FRESH_MS`), et sans serveur il n'y a **aucun objet** à mesurer —
la segmentation SAM est serveur, le `pinhole` dérive des boîtes reçues. Le seul
cas où l'embarqué travaillait vraiment était « serveur joignable qui répond sans
`depth` » (backend sans modèle, ancien backend) : une fenêtre trop étroite pour
27 Mo, ~1 800 lignes de prod et une dépendance native de plus.

Conséquence assumée : dans cette fenêtre, iSee perd la mesure des murs,
trottoirs et bordures et retombe sur le sténopé seul (labels à hauteur connue).

## Modèle (serveur)

- **Depth Anything V2 Small** (`depth-anything/Depth-Anything-V2-Small-hf`,
  24,8 M params, Apache-2.0) via `transformers`, poids embarqués dans l'image
  (`FAST_DEPTH_MODEL_DIR`, téléchargés au build comme sam3.1 — boot hermétique
  `HF_HUB_OFFLINE=1`). Modèle absent ⇒ `with_depth` sans effet, jamais d'erreur.
- Médiane sur le **masque** de l'objet (érosion 25 % par côté), repli boîte
  érodée ; la boîte entière lirait le fond sur un objet fin (poteau).
- Le modèle est **relatif** (grand = proche) : l'échelle métrique vient des
  ancres sténopé de la frame (`DepthReadings.scaleFromAnchors`), jamais de la
  carte seule.

## Débit et coût

- `FAST_DEPTH_EVERY` (défaut 8) : le client demande la profondeur à chaque
  frame, le serveur ne calcule qu'une carte sur N et **répète** ses médianes
  entre deux (`new_depth_throttle` + `cached_medians` + `attach_cached_medians`).
  Répéter plutôt qu'omettre : une réponse sans clé retomberait en sténopé pur.
- Poste `depth_ms` émis si `FAST_TIMING` est vrai (même carte Modal que SAM).
- Client muet (pas de `with_depth`) : aucune clé, aucun coût.

## Code

- `server/backend/live_fast/depth.py` : carte + médianes par objet, throttle,
  cache, repli gracieux.
- `:core` (pur JVM) : `depth/DepthReadings.kt` (médiane par boîte, calibration
  par ancres, `refine` / `refineWithCloseness` / `serverCloseness`),
  `depth/DepthMap.kt`, `depth/DepthFusion.kt`, `depth/AffineScale.kt`,
  `depth/DepthConfirm.kt`, `model/Geometry.kt` (rétroprojection, Box3D).
  Ces types servent aussi au pipeline sol (`core/ground`) et au mode traversée.
- `:app` : `depth/DepthMeter.kt` — l'interface et son unique implémentation
  `ServerDepthMeter` (médianes serveur, sténopé en repli). Branchée par
  `isee/ProbeMeters` ; `LiveProbe` demande `with_depth = true` à la connexion.
- Les tests branchent un faux `DepthMeter` : aucune référence réseau ni Android.

## Guidage

Les mètres profondeur alimentent le direct (pastilles, voix, consignes « va à
gauche, 2 mètres », flèches) :
- `DepthReadings.refineWithCloseness` remplace le sténopé quand la profondeur
  mesure (murs, trottoirs, sans hauteur connue), l'ajoute quand il est muet, le
  garde sinon. Mètres bornés [0,3 m, 60 m].
- Mode traversée (`TraversePipeline`) : proximités serveur seulement.
- Voix et flèches inchangées (une seule voix, le danger parle d'abord).

## Recette

Plus de banc sur téléphone : la profondeur se vérifie côté serveur (`depth_ms`
dans les timings, clé `depth` dans la réponse `/live` ou `/live/ws`). Voir
`docs/TESTING.md`.

## Historique

La release `depth-models-v1` conserve les anciens poids (DA2 `.ort` 27 Mo,
MiDaS v2.1 small 63 Mo) : plus rien ne les télécharge, et ils ne sont pas
nécessaires au serveur (qui charge ses poids depuis `FAST_DEPTH_MODEL_DIR`).
