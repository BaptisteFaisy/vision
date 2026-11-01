#!/usr/bin/env python3
"""Reentraine l'arbre couleur feu sur crops telephone labellises.

Entree : labels.csv (name,label R/G/BAD) + dossier de PNG (voir
`tools/review_light_crops.py`), ancres `feuxfr/` + `feuxfr2/` + `feuxfr3/`
(garde-fou, listes ANCHOR_GREENS/ANCHOR_REDS ci-dessous).
L'arbre candidat (sklearn, max_depth=5) remplace les tableaux de
`TrainedLightColor` ; la porte et les planchers sont FIXES (non reentraînes).

Barriere GO (exit 0) : ancres parfaites (verdicts stables par ancre, voir
les listes) + precision du vert >= 0.98 sur hold-out (seuil user du 31/10,
volontairement bas) + rappel vert >= arbre actuel sur le meme hold-out.
Sinon NO-GO (exit 1) avec motifs. N'embarque rien seul : le bloc Kotlin
emis est a relire puis a merger par PR.

Usage : uv run --with-requirements tools/requirements-train.txt \\
    tools/train_light.py --labels OUT/labels.csv --crops OUT/crops \\
    --anchors core/src/test/resources --out OUT/refit/
"""

import argparse
import json
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import light_features as lf  # noqa: E402

MIN_SAMPLES, MIN_PER_CLASS = 50, 10
PRECISION_FLOOR = 0.98
SEED = 42

# Protocole 01/11 : arbre sur les 17 traits, profondeur 5, feuille min 5,
# seed 42 — la souche `green_frac` embarquee en est le produit (lot
# lotfinal + fig_243). Les variantes ecartees (exclusions de traits,
# feuille 2, echantillons epingles) sont narrees en PR, pas conservees ici.

# Socle de surete (verdicts complets, cf. test_train_light.ANCHORS) : 20 verts
# admis (bonhommes + pose large + 2 disques elances), 11 rouges (rouges +
# 2 disques ronds + 4 tel). 01/11 : widepose/close_183/st_c01 passent en
# verts (A2 leve 31/10), 367/st_c02 restent rouges (mesure), 11 tel lotfinal.
ANCHOR_GREENS = ["fr_green_fig_21", "fr_green_fig_302", "fr_green_fig_243",
                 "fr_green_widepose_355", "fr_cardisc_close_183",
                 "r1b_g01", "r1b_g02", "r1b_g03", "r1b_g04", "r1b_g05",
                 "st_g01", "st_g02", "st_c01",
                 "tel_g01", "tel_g02", "tel_g03", "tel_g04", "tel_g05",
                 "tel_g06", "tel_g07"]
ANCHOR_REDS = ["fr_red_fig_548", "fr_red_dim_138", "fr_red_noise_732",
               "fr_cardisc_367", "st_r01", "st_r02", "st_c02",
               "tel_r01", "tel_r02", "tel_r03", "tel_r04"]


def load_png(path):
    from PIL import Image
    img = Image.open(path).convert("RGB")
    w, h = img.size
    return ([0xFF000000 | (r << 16) | (g << 8) | b for r, g, b in img.getdata()], w, h)


def find_anchor(res, stem):
    for d in ("feuxfr", "feuxfr2", "feuxfr3"):
        p = res / d / f"{stem}.ppm"
        if p.exists():
            return p
    raise FileNotFoundError(stem)


def load_items(labels_path, crops_dir):
    """Crops labellises : [(nom, traits, y, pixels, w, h)] (BAD exclus)."""
    labels = lf.load_labels(labels_path)
    items = []
    for name, lab in sorted(labels.items()):
        if lab == "BAD":
            continue
        px, w, h = load_png(Path(crops_dir) / name)
        items.append((name, lf.extract(px, w, h), 1 if lab == "G" else 0, px, w, h))
    return items


def check_volume(items):
    """Assez de crops (verts et rouges) pour entraîner ?"""
    n_green = sum(1 for _, _, y, _, _, _ in items if y == 1)
    if len(items) < MIN_SAMPLES or min(n_green, len(items) - n_green) < MIN_PER_CLASS:
        return [f"pas assez de donnees : {len(items)} crops ({n_green} verts)"]
    return []


def fit_tree(X, y, seed):
    from sklearn.tree import DecisionTreeClassifier
    clf = DecisionTreeClassifier(criterion="gini", max_depth=5, min_samples_leaf=5,
                                 random_state=seed)
    clf.fit(X, y)
    return clf


def tree_fn_of(clf):
    """Verdict d'arbre sklearn + planchers Kotlin (miroir de TrainedLightColor)."""
    def decide(feats, _c=clf):  # noqa: B023
        node = 0
        t = _c.tree_
        while t.children_left[node] >= 0:
            f, th = t.feature[node], t.threshold[node]
            node = t.children_left[node] if feats[f] <= th else t.children_right[node]
        counts = t.value[node][0]
        leaf_green = int(counts[1] >= counts[0])
        floor = lf.GREEN_MIN_PURITY if leaf_green else lf.MIN_PURITY
        if max(counts) / sum(counts) < floor:
            return "Red"
        if leaf_green and feats[lf.GREEN_FRAC_INDEX] < lf.GREEN_MIN_FRACTION:
            return "Red"
        return "Green" if leaf_green else "Red"
    return decide


def check_anchors(new_tree, anchors_dir):
    """Ancres parfaites sous le candidat (verdicts complets, listes stables)."""
    res = Path(anchors_dir)
    fails = []
    for stem in ANCHOR_GREENS:
        px, w, h = lf.load_ppm(find_anchor(res, stem))
        got = lf.verdict(px, w, h, tree=new_tree)
        if got != "Green":
            fails.append(f"{stem}: {got} (vert attendu)")
    for stem in ANCHOR_REDS:
        px, w, h = lf.load_ppm(find_anchor(res, stem))
        got = lf.verdict(px, w, h, tree=new_tree)
        if got != "Red":
            fails.append(f"{stem}: {got} (rouge attendu)")
    return fails


def holdout_metrics(new_tree, hold):
    """(precision vert, rappel nouveau, rappel actuel) sur le hold-out."""
    stats = {}
    for tag, fn in (("new", new_tree), ("old", lf.tree_classify)):
        tp = fp = fn_ = 0
        for _, _, want, px, w, h in hold:
            got = lf.verdict(px, w, h, tree=fn)
            if got == "Green" and want == 1:
                tp += 1
            elif got == "Green":
                fp += 1
            elif want == 1:
                fn_ += 1
        stats[tag] = {"tp": tp, "fp": fp, "fn": fn_}
    new, old = stats["new"], stats["old"]
    prec = new["tp"] / (new["tp"] + new["fp"]) if (new["tp"] + new["fp"]) else -1.0
    rec_new = new["tp"] / (new["tp"] + new["fn"]) if (new["tp"] + new["fn"]) else 0.0
    rec_old = old["tp"] / (old["tp"] + old["fn"]) if (old["tp"] + old["fn"]) else 0.0
    return prec, rec_new, rec_old


def emit_kotlin(clf) -> str:
    t = clf.tree_
    n = t.node_count
    feat, th, left, right, cls, pur = [], [], [], [], [], []
    for i in range(n):
        if t.children_left[i] < 0:
            feat.append(-2)
            th.append(-2.0)
            counts = t.value[i][0]
            cls.append(int(counts[1] >= counts[0]))
            pur.append(float(max(counts) / sum(counts)))
        else:
            feat.append(int(t.feature[i]))
            th.append(float(t.threshold[i]))
            cls.append(0)
            pur.append(0.0)
        left.append(int(t.children_left[i]))
        right.append(int(t.children_right[i]))

    def arr(name, vals, suffix):
        body = ", ".join(f"{v}{suffix}" for v in vals)
        kind = "intArrayOf" if suffix == "" else "floatArrayOf"
        return f"    private val {name} = {kind}({body})"

    return ("// Candidat genere par tools/train_light.py — RELIRE avant PR.\n"
            "// Remplace les 6 tableaux de TrainedLightColor (planchers inchanges).\n" +
            arr("FEATURE", feat, "") + "\n" + arr("THRESHOLD", th, "f") + "\n" +
            arr("LEFT", left, "") + "\n" + arr("RIGHT", right, "") + "\n" +
            arr("LEAF_CLASS", cls, "") + "\n" + arr("LEAF_PURITY", pur, "f") + "\n")


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels", required=True)
    ap.add_argument("--crops", required=True)
    ap.add_argument("--anchors", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=SEED)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    return a, out


def main() -> int:
    a, out = parse_args()

    from sklearn.model_selection import train_test_split

    items = load_items(a.labels, a.crops)
    n_green = sum(1 for _, _, y, _, _, _ in items if y == 1)
    reasons = check_volume(items)
    report = {"crops": len(items), "greens": n_green, "seed": a.seed}

    clf, new_tree, hold = None, None, None
    if not reasons:
        X = [feats for _, feats, _, _, _, _ in items]
        y = [lab for _, _, lab, _, _, _ in items]
        idx_tr, idx_te = train_test_split(range(len(items)), test_size=0.2, random_state=a.seed,
                                          stratify=y)
        clf = fit_tree([X[i] for i in idx_tr], [y[i] for i in idx_tr], a.seed)
        new_tree = tree_fn_of(clf)
        hold = [items[i] for i in idx_te]
        report["holdout"] = len(hold)

    anchor_fails = check_anchors(new_tree, a.anchors) if new_tree else []
    report["anchors"] = {"greens": len(ANCHOR_GREENS), "reds": len(ANCHOR_REDS),
                         "fails": anchor_fails, "skipped": new_tree is None}
    if anchor_fails:
        reasons.append(f"{len(anchor_fails)} ancres en echec")

    if new_tree and hold:
        prec, rec_new, rec_old = holdout_metrics(new_tree, hold)
        report["holdout_metrics"] = {"precision_green": round(prec, 4),
                                     "recall_new": round(rec_new, 4),
                                     "recall_old": round(rec_old, 4)}
        if prec < PRECISION_FLOOR:
            reasons.append(f"precision vert hold-out {prec:.3f} < {PRECISION_FLOOR}")
        if rec_new < rec_old:
            reasons.append(f"rappel vert {rec_new:.3f} < actuel {rec_old:.3f}")
        report["go"] = not reasons
        (out / "candidate.kt").write_text(emit_kotlin(clf))
    else:
        report["go"] = False

    report["reasons"] = reasons
    (out / "report.json").write_text(json.dumps(report, indent=2))
    print(("GO" if report["go"] else "NO-GO") + f" : {len(items)} crops")
    for r in reasons:
        print("  -", r)
    print(f"rapport : {out / 'report.json'}")
    return 0 if report["go"] else 1


if __name__ == "__main__":
    sys.exit(main())
