#!/usr/bin/env python3
"""Prépare le dossier Truss Baseten : copie les sources dans ``backend/baseten/data/``.

Baseten ne copie dans l'image que ``config.yaml`` et ``data/`` (monté sur ``/app/data``) :
les sources du service vivent dans ``backend/live_fast/`` et ne sont donc **pas**
dupliquées dans le dépôt — ce script les rassemble au moment du push.

    python3 scripts/baseten_sync.py            # copie (+ supprime les sources périmées)
    python3 scripts/baseten_sync.py --check    # échoue si data/ diverge des sources
"""

from __future__ import annotations

import filecmp
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIVE = ROOT / "backend" / "live_fast"
BASETEN = ROOT / "backend" / "baseten"
DATA = BASETEN / "data"


def sources() -> dict[Path, Path]:
    """Destination -> source de chaque fichier à embarquer."""
    # live_app_fast.py est l'amorçage Modal (importe `modal`) : inutile côté Baseten.
    files = {DATA / path.name: path for path in sorted(LIVE.glob("*.py"))
             if path.name != "live_app_fast.py"}
    files[DATA / "requirements.txt"] = LIVE / "requirements.txt"
    files[DATA / "server.py"] = BASETEN / "src" / "server.py"
    return files


def check(files: dict[Path, Path]) -> int:
    """Retourne 0 si data/ est identique aux sources, 1 sinon (liste les écarts)."""
    stale = [str(dst.relative_to(ROOT)) for dst, src in files.items()
             if not dst.exists() or not filecmp.cmp(dst, src, shallow=False)]
    extra = [str(p.relative_to(ROOT)) for p in DATA.glob("*")
             if p.is_file() and p not in files] if DATA.exists() else []
    for path in stale + extra:
        print(f"écart : {path}")
    return 1 if stale or extra else 0


def sync(files: dict[Path, Path]) -> None:
    """Copie les sources et retire de data/ tout fichier qui n'est plus produit."""
    DATA.mkdir(parents=True, exist_ok=True)
    wanted = {dst.resolve() for dst in files}
    for existing in DATA.glob("*"):
        if existing.is_file() and existing.resolve() not in wanted:
            existing.unlink()
            print(f"supprimé : {existing.relative_to(ROOT)}")
    for dst, src in files.items():
        shutil.copy2(src, dst)
        print(f"copié : {dst.relative_to(ROOT)}")


def main(argv: list[str]) -> int:
    files = sources()
    if "--check" in argv:
        return check(files)
    sync(files)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))