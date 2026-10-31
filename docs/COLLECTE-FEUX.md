# Collecte et réentraînement des feux piétons (31/10/2026)

Pourquoi : le rappel vert est à 1/6 sur crops SAM réels
(`docs/MESURES-SAM3-FEUX.md` §6) et aucun seuil ne le répare — il faut des
données du domaine production (crops téléphone). Cette page décrit la boucle
complète : collecte → étiquetage → réentraînement → barrière GO → PR.

## 1. Collecte (terrain, ~30 min)

1. Sur le téléphone : Réglages → TECHNICAL SETTINGS → **Collect light crops**
   (coupé par défaut, `Settings.collectLightCrops`).
2. Marcher 5+ carrefours variés (centre dense, résidentiel, LED + lampes,
   jour + tombée de nuit si possible), mode Traversée actif face aux feux.
3. Chaque crop classifié (≤ 48 px, PNG exact vu par le classifieur) part dans
   `lightcrops-<session>.jsonl` (fichiers privés, 2000/session max, avec boîte
   + verdict + version). **Crops seuls, jamais la scène : pas de visages,
   pas de plaques.**
4. Recouper l'interrupteur, rapatrier :
   `phone-adb -s <tel> pull /data/data/<pkg>/files/lightcrops <OUT>/brut/`
   (chemin exact dans le logcat `LightCrop`).

Cible : **200–400 crops étiquetables** (le harnais accepte dès 50,
10/classe ; viser 200+ pour un hold-out qui veut dire quelque chose,
400 si le terrain s'y prête — au-delà, le gain est marginal).

## 2. Étiquetage (PC, ~20 min)

```
python3 tools/review_light_crops.py <OUT>/brut/lightcrops-X.jsonl <OUT>/lot1/
```

Ouvrir `<OUT>/lot1/review.html` (Chrome) : chaque crop, **R**ouge, **G** vert,
**B**AD (flou, coupé, pas un feu, voiture). Exporter `labels.csv` dans
`<OUT>/lot1/`. Le verdict machine affiché est indicatif : seule compte la
vérité terrain vue sur le crop.

## 3. Réentraînement (PC, ~2 min)

```
uv run --with-requirements tools/requirements-train.txt \
  tools/train_light.py --labels <OUT>/lot1/labels.csv --crops <OUT>/lot1/crops \
  --anchors core/src/test/resources --out <OUT>/lot1/refit/
```

- Traits : port fidèle de `CrossingFeatures` (`tools/light_features.py`,
  parité verrouillée en CI sur les 20 ancres).
- Arbre : `DecisionTreeClassifier(gini, max_depth=5, min_samples_leaf=5,
  seed=42)` — hyperparamètres choisis (l'original est hors dépôt), porte et
  planchers FIXES (non réentraînés).
- Sorties : `candidate.kt` (6 tableaux prêts à relire) + `report.json`.

## 4. Barrière GO (décision user du 31/10 : précision basse)

GO (exit 0) ssi les trois tiennent, sinon NO-GO motivé (exit 1) :

1. **20 ancres parfaites** : 10 verts restent verts, 10 rouges/disques/froids
   restent rouges — le socle de sûreté ne bouge jamais ;
2. **précision du vert ≥ 0,98 sur hold-out** (20 %, stratifié, seed 42) ;
3. **rappel vert ≥ arbre actuel** sur le même hold-out.

Un candidat GO se relit (`candidate.kt` + `report.json`) puis s'embarque par
PR normale (tests JVM + CI) : le trainer n'écrit jamais dans `core/`.

## 4b. Ancres `feuxfr3/` (embarquement optionnel)

Pour verrouiller le rappel sur données téléphone, promouvoir ~10 crops
du lot en ancres versionnées : `core/src/test/resources/feuxfr3/README.md`
décrit la conversion PPM, la traçabilité (`ATTRIBUTION.md`) et le
branchement (`test_train_light.py`, `train_light.py`, `PedLightGateTest`).
Les 20 ancres Panoramax restent le socle et ne bougent jamais.

## 5. Vie privée

Crops de caissons ≤ 48 px, pas de scènes ; fichiers privés de l'app,
rapatriés par câble, étiquetés en local, jamais envoyés nulle part. Les
corpus labellisés restent hors dépôt (comme les 235 lampes Panoramax).
