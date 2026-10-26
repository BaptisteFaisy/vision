#!/usr/bin/env python3
"""Rejeu des patchs d'une campagne : `regress` (copie jetable) et `matrix` (couples).

Une campagne de reconstruction laisse des `PATCH-u<NN>.patch` à la racine de
l'arbre. Ce module les inventorie, **détecte** leur convention d'application
(niveau `-p` et répertoire de base : trois préfixes coexistent selon les vagues)
puis les rejoue sur une copie jetable — jamais sur l'arbre de travail.

`matrix` répond à une seule question, par couple ordonné (A, B) : B s'applique-t-il
encore après A ? Un couple perdu est un conflit de fusion à venir.

Stdlib seule + `patch` (binôme système). Aucun `git`, aucune compilation, aucun
réseau. Les fichiers de build et `.git` sont exclus de la copie : elle doit rester
jetable en quelques centaines de millisecondes.
"""
import glob
import os
import shutil
import subprocess
import tempfile

LEVELS = (1, 2, 3)                 # conventions de préfixe rencontrées dans les campagnes
PATCH_GLOB = "PATCH-*.patch"
BASES = ("", "app", "core", "glasses-spike", "backend", "src")
IGNORES = (".git", ".gradle", "build", "__pycache__", "*.lock")
BIN = "patch"


class Patch:
    """Un patch de campagne : son nom d'unité et sa convention détectée."""

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)[len("PATCH-"):-len(".patch")]
        self.level = None
        self.base = None

    def text(self):
        """Ligne lisible du patch, avec sa convention une fois détectée."""
        if self.level is None:
            return f"{self.name} — convention introuvable"
        base = self.base or "."
        return f"{self.name} — -p{self.level} depuis {base}"


def inventory(root, units=None):
    """Les patchs de la racine, éventuellement filtrés par unité (`--units u01,u03`)."""
    patches = []
    for path in sorted(glob.glob(os.path.join(root, PATCH_GLOB))):
        patch = Patch(path)
        if not units or patch.name in set(units):
            patches.append(patch)
    return patches


def _run(args, cwd, patch):
    """Joue `patch` sur l'entrée standard ; rend le `subprocess.CompletedProcess`."""
    with open(patch.path, "rb") as handle:
        return subprocess.run(args, cwd=cwd, stdin=handle, capture_output=True)


def _dry_run(patch, cwd, level):
    """`patch --dry-run -pN` dans `cwd` : vrai si le patch s'appliquerait."""
    args = [BIN, f"-p{level}", "--batch", "--forward", "--dry-run"]
    return _run(args, cwd, patch).returncode == 0


def detect(patch, workdir):
    """Détecte (niveau, base) du patch, en cherchant le premier `--dry-run` qui passe."""
    for base in BASES:
        cwd = os.path.join(workdir, base) if base else workdir
        if not os.path.isdir(cwd):
            continue
        for level in LEVELS:
            if _dry_run(patch, cwd, level):
                return level, base
    return None, None


def apply_patch(patch, workdir):
    """Figent la convention du patch puis l'applique ; rend (succès, détail)."""
    level, base = detect(patch, workdir)
    patch.level, patch.base = level, base
    if level is None:
        return False, f"{patch.name} — sec : -p1..-p3 refusés (mauvaise base ou contexte périmé)"
    cwd = os.path.join(workdir, base) if base else workdir
    args = [BIN, f"-p{level}", "--batch", "--forward"]
    done = _run(args, cwd, patch)
    if done.returncode != 0:
        sortie = (done.stderr or done.stdout or b"").decode("utf-8", "replace")
        ligne = next((l.strip() for l in sortie.splitlines() if l.strip()), "échec inconnu")
        return False, f"{patch.text()} — KO : {ligne}"
    return True, f"{patch.text()} — appliqué"


def throwaway_copy(root):
    """Copie jetable de l'arbre dans un `mkdtemp` ; rend (dossier, chemin de l'arbre)."""
    tmp = tempfile.mkdtemp(prefix="guard-copie-")
    dest = os.path.join(tmp, "arbre")
    shutil.copytree(root, dest, ignore=shutil.ignore_patterns(*IGNORES))
    return tmp, dest


def _with_copy(root, fonction):
    """Applique `fonction(arbre)` sur une copie jetable et détruit la copie."""
    tmp, dest = throwaway_copy(root)
    try:
        return fonction(dest)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _joue_un(root, patch, controle):
    """Une copie jetable par patch : applique, puis relance le contrôle demandé."""

    def jouer(arbre):
        ok, detail = apply_patch(patch, arbre)
        if ok and controle:
            detail += " · " + controle(arbre)
        return ok, detail

    return _with_copy(root, jouer)


def regress(root, units=None, controle=None):
    """Chaque patch sur sa propre copie jetable ; rend [(nom, succès, détail)]."""
    return [(patch.name,) + _joue_un(root, patch, controle)
            for patch in inventory(root, units)]


def _couples(patches):
    """Tous les couples ordonnés (A, B), A ≠ B : c'est l'espace de la matrice."""
    return [(a, b) for a in patches for b in patches if a is not b]


def conflits_de(lignes):
    """Les couples en conflit, extraits des lignes de la matrice (source unique)."""
    return [ligne.strip().split()[1] for ligne in lignes
            if ligne.strip().startswith("CONFLIT")]


def _colonne(root, premier, patches):
    """Une copie jetable par A : on y applique A, puis on sèche chaque B contre A."""

    def jouer(arbre):
        ok, detail = apply_patch(premier, arbre)
        if not ok:
            return [f"  {premier.name} : A refusé — {detail}"]
        lignes = [f"  {detail}"]
        for second in patches:
            if second is premier:
                continue
            couple = f"{premier.name}→{second.name}"
            level, _ = detect(second, arbre)
            if level is None:
                lignes.append(f"  CONFLIT {couple} : {second.name} ne s'applique "
                              f"plus après {premier.name}")
            else:
                lignes.append(f"  OK      {couple}")
        return lignes

    return _with_copy(root, jouer)


def matrix(root, units=None):
    """Rejoue la matrice ; rend (conflits, nb_couples, lignes de détail)."""
    patches = inventory(root, units)
    lignes = []
    for premier in patches:
        lignes += _colonne(root, premier, patches)
    return conflits_de(lignes), len(_couples(patches)), lignes
