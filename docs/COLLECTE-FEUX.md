# Collecte et réentraînement des feux piétons (31/10/2026, revu le 02/11/2026)

État au 02/11/2026 (voir `docs/MESURES-FEU-ROUGE-VERT.md`) : sur les crops
téléphone FR le classifieur est à **20/20 verts et 0 faux vert sur 29 rouges**,
et **6/7** sur les photos réelles. Le besoin a donc changé de nature : il n'y a
plus de rappel à gagner *sur ce corpus* (quatre calibrations différentes y
donnent le même 20/20, il ne discrimine plus rien), mais deux choses restent :

1. **certifier le taux de faux vert** — c'est la seule métrique qui compte pour
   une personne malvoyante, et 0 échec sur 29 rouges ne borne que « ≤ 10,3 % » ;
2. **couvrir le domaine réel** — jour surexposé, nuit, pluie, LED cyan,
   pictogrammes ; le seul faux vert observé à l'échelle de production (96 px)
   est un rouge **de jour surexposé**.

Cette page décrit la boucle complète : collecte → étiquetage → réentraînement →
barrière GO → PR.

## 1. Collecte (terrain, ~30 min)

1. Sur le téléphone : Réglages → TECHNICAL SETTINGS → **Collect light crops**
   (coupé par défaut, `Settings.collectLightCrops`).
2. Marcher 5+ carrefours variés (centre dense, résidentiel, LED + lampes,
   jour + tombée de nuit si possible), mode Traversée actif face aux feux.
3. Chaque crop classifié (96 px, PNG exact vu par le classifieur — `MAX_EDGE`
   depuis le 31/10) part dans
   `lightcrops-<session>.jsonl` (fichiers privés, 2000/session max, avec boîte
   + verdict + version). **Crops seuls, jamais la scène : pas de visages,
   pas de plaques.**
4. Recouper l'interrupteur, rapatrier :
   `phone-adb -s <tel> pull /data/data/<pkg>/files/lightcrops <OUT>/brut/`
   (chemin exact dans le logcat `LightCrop`).

Cible : **200–400 crops étiquetables** (le harnais accepte dès 50,
10/classe ; viser 200+ pour un hold-out qui veut dire quelque chose,
400 si le terrain s'y prête — au-delà, le gain est marginal).

### 1bis. Ce qu'il faut collecter MAINTENANT : des ROUGES de jour

Le vert est appris (transfert Ampel-Pilot → FR : un arbre entraîné uniquement
en allemand passe 20/20 sur les crops FR et 31/31 ancres, `w10`). Ce qui manque
est de la **preuve**, et elle ne peut venir que de rouges français :

| rouges FR cumulés | borne supérieure du taux de faux vert (Wilson 95 %, 0 échec) |
|---|---|
| 29 (aujourd'hui) | 10,3 % |
| 150 | **2,50 %** |
| 300 | **1,26 %** |
| 600 | 0,64 % |

Le jeu actuel ne contient que **4 rouges de jour sur 29** — et c'est en jour que
la mesure de couleur se dégrade. Une session de rue utile = **beaucoup de feux
rouges piétons en plein jour**, pas de nouveaux verts.

Pendant la session, noter pour chaque carrefour (3 informations suffisent) :
l'heure, ce que le feu faisait vraiment, et ce que l'app a dit (ou n'a pas dit).
Le crop exact et le verdict machine sont déjà dans le `jsonl` : le croisement des
deux donne l'étiquette sans revoir l'image.

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
  parité verrouillée en CI sur les 31 ancres).
- Arbre : `DecisionTreeClassifier(gini, max_depth=5, min_samples_leaf=5,
  seed=42)` — hyperparamètres choisis (l'original est hors dépôt), porte et
  planchers FIXES (non réentraînés).
- Sorties : `candidate.kt` (6 tableaux prêts à relire) + `report.json`.

## 4. Barrière GO (décision user du 31/10 : précision basse)

GO (exit 0) ssi les trois tiennent, sinon NO-GO motivé (exit 1) :

1. **ancres parfaites** (20 Panoramax + 11 `feuxfr3/` téléphone) : chaque
   ancre garde son verdict mesuré (listes `ANCHOR_GREENS`/`ANCHOR_REDS` de
   `tools/train_light.py`, disques mixtes documentés par ancre) — le socle
   de sûreté ne bouge jamais sans mesure ;
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
