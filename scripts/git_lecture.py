#!/usr/bin/env python3
"""git_lecture.py — lectures git best-effort pour les portes CI.

Six lectures, zéro écriture : une commande générique (`commande`), la base de
comparaison (`base`), la branche courante (`branche_courante`), le contenu d'un
fichier sur la base (`fichier_sur_base`), les branches non fusionnées
(`branches_non_fusionnees`) et les fichiers changés (`fichiers_diff`).

Tout échec (git absent, hors dépôt, checkout superficiel, délai) rend None ou
[] — jamais d'exception : l'appelant dégrade (avertissement, contrôle sauté),
il n'échoue pas sur l'outil.

Bibliothèque standard seule ; que des lectures locales, aucun réseau déclenché.
"""

import os
import subprocess


def commande(root, *args):
    """`git -C root …` best-effort : stdout dépouillé, ou None."""
    try:
        # ubs:ignore — exécutable fixe, argv sans shell, code inspecté à la main.
        lance = subprocess.run(["git", "-C", root] + list(args), capture_output=True,
                               text=True, timeout=15, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    return lance.stdout.strip() if lance.returncode == 0 else None


def base(root):
    """Base de comparaison (`origin/master`, repli `master`), ou None."""
    for candidat in ("origin/master", "master"):
        if commande(root, "rev-parse", "--verify", "--quiet", candidat) is not None:
            return candidat
    return None


def branche_courante(root):
    """Nom de la branche courante ("" si détachée ou git absent)."""
    nom = commande(root, "rev-parse", "--abbrev-ref", "HEAD")
    return "" if nom in (None, "HEAD") else nom


def fichier_sur_base(root, chemin, base):
    """Contenu de `chemin` sur `base`, ou None (superficiel, absent, hors dépôt)."""
    try:
        relatif = os.path.relpath(chemin, root)
    except ValueError:
        return None
    if relatif.startswith(".."):
        return None
    return commande(root, "show", "%s:%s" % (base, relatif))


def branches_non_fusionnees(root, base, locaux, distants, exclusion=""):
    """Branches `locaux` + `distants` non fusionnées dans `base`, `exclusion` retirée."""
    if base is None:
        return []
    sortie = []
    requetes = [(False, m) for m in locaux] + [(True, m) for m in distants]
    for distant, motif in requetes:
        appel = ["branch"] + (["-r"] if distant else []) + ["--list", motif,
                                                            "--no-merged", base]
        for ligne in (commande(root, *appel) or "").splitlines():
            # Marqueur git en tête (`*` courante, `+` sortie dans un worktree).
            nom = ligne[1:].strip() if ligne[:1] in ("*", "+") else ligne.strip()
            if nom and nom != exclusion and nom not in sortie:
                sortie.append(nom)
    return sortie


def fichiers_diff(root, base, tete):
    """Fichiers changés entre la base et `tete` (`...`), ou None."""
    if base is None:
        return None
    sortie = commande(root, "diff", "--name-only", "%s...%s" % (base, tete))
    return None if sortie is None else set(sortie.split())
