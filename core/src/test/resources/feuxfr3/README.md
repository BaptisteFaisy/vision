# `feuxfr3/` — ancres du lot téléphone (patron)

Comment promouvoir ~10 crops du lot téléphone
(`docs/COLLECTE-FEUX.md` : 200–400 crops, étiquetés R/G/BAD) en ancres
versionnées, sans toucher aux 20 ancres Panoramax (`feuxfr/`, `feuxfr2/`)
ni au corpus hors dépôt.

## 1. Choisir les crops

Dans `<OUT>/lotN/` (sortie de `tools/review_light_crops.py`) :
6–8 verts piétons variés (bonhomme plein, petit, LED, nuit) + 2–4 rouges
ou cas limites (rouge sombre, disque voiture). Prendre des crops nets,
centrés, ≤ 48 px de côté — les BAD ne deviennent jamais des ancres.

## 2. Convertir en PPM P6

```bash
# ImageMagick (un crop) ou Pillow (lot) — PPM P6, ≤ 48 px, nom tel_* :
convert lot1/crops/0042.png -resize '48x48>' \
  core/src/test/resources/feuxfr3/tel_g01.ppm
```

## 3. Tracer et brancher

1. Ajouter une ligne par crop dans `ATTRIBUTION.md` (lot + verdict).
2. Étendre `ANCHORS` dans `tools/test_train_light.py` (miroir Python).
3. Étendre `ANCHOR_GREENS` / `ANCHOR_REDS` dans `tools/train_light.py`.
4. Recopier les verdicts dans `PedLightGateTest` (JVM, source de vérité).

## 4. Vérifier

```bash
python3 -m unittest tools/test_train_light.py
```

`tools/train_light.py` résout déjà `feuxfr3/` via `find_anchor` : les
nouvelles ancres entrent dans la barrière GO (20 + N parfaites) sans
autre changement.
