#!/usr/bin/env python3
"""Chiffres de l'arbre — la source unique du tableau « Figures » du README.

    python3 tools/count-tree.py            # tableau markdown
    python3 tools/count-tree.py --json     # même chose, lisible par un script

Pourquoi ce fichier : les chiffres du README ont dérivé trois fois en trois jours
(876 → 919 fichiers `.kt` entre le 29/10 au matin et le 29/10 au soir). Un relevé
recopié à la main ne survit pas à une journée de commits ; celui-ci se rejoue.

Aucune dépendance, aucun réseau, aucune compilation : il lit l'arbre et compte.
Les répertoires `build/`, `.git/` et `.gradle/` sont exclus partout.
"""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {"build", ".git", ".gradle", ".kotlin", "node_modules"}


def walk(*subdirs):
    """Fichiers sous `subdirs` (récursif), sans les répertoires de build."""
    seen = []
    for sub in subdirs:
        base = ROOT / sub
        if not base.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in SKIP]
            seen.extend(Path(dirpath) / f for f in filenames)
    return seen


def n_lines(paths):
    """Nombre de sauts de ligne : exactement ce que compte `wc -l`."""
    total = 0
    for path in paths:
        try:
            total += path.open("rb").read().count(b"\n")
        except OSError:
            pass
    return total


def count_annotation(paths, needle):
    total = 0
    for path in paths:
        try:
            total += path.read_text(errors="ignore").count(needle)
        except OSError:
            pass
    return total


def _under(path, *segments):
    """Vrai si `segments` se suivent dans le chemin (ex. src/main)."""
    parts = path.parts
    return any(parts[i:i + len(segments)] == segments for i in range(len(parts) - len(segments) + 1))


def largest(paths):
    best, best_lines = "", 0
    for path in paths:
        try:
            lines = path.open("rb").read().count(b"\n")
        except OSError:
            continue
        if lines > best_lines:
            best, best_lines = str(path.relative_to(ROOT)), lines
    return best, best_lines


def figures():
    kt = walk("core", "app")
    kt = [p for p in kt if p.suffix == ".kt"]
    prod = [p for p in kt if _under(p, "src", "main")]
    core_prod = [p for p in prod if p.relative_to(ROOT).parts[0] == "core"]
    app_prod = [p for p in prod if p.relative_to(ROOT).parts[0] == "app"]
    instr = [p for p in kt if any(part.startswith("androidTest") for part in p.parts)]
    jvm_tests = [p for p in kt if _under(p, "src", "test")]
    python = [p for p in walk("server/backend") if p.suffix == ".py"]
    docs = [p for p in walk("docs") if p.suffix == ".md"]
    workflows = [p for p in walk(".github/workflows") if p.suffix in (".yml", ".yaml")]
    biggest, biggest_lines = largest(prod)

    def module(subset):
        return {"files": len(subset), "lines": n_lines(subset)}

    return {
        "kt_files": len(kt),
        "kt_lines": n_lines(kt),
        "prod_files": len(prod),
        "prod_lines": n_lines(prod),
        "core": module(core_prod),
        "app": module(app_prod),
        "jvm_tests": count_annotation(jvm_tests, "@Test"),
        "core_jvm_tests": count_annotation([p for p in jvm_tests if p.relative_to(ROOT).parts[0] == "core"], "@Test"),
        "app_jvm_tests": count_annotation([p for p in jvm_tests if p.relative_to(ROOT).parts[0] == "app"], "@Test"),
        "instrumented_tests": count_annotation(instr, "@Test"),
        "previews": count_annotation(kt, "@Preview"),
        "python_files": len(python),
        "python_lines": n_lines(python),
        "docs": len(docs),
        "workflows": len(workflows),
        "biggest_file": biggest,
        "biggest_lines": biggest_lines,
    }


def markdown(f):
    return f"""| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` | **{f['kt_files']}** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \\| wc -l` |
| Lignes Kotlin | **{f['kt_lines']:,}** | idem, `-exec cat {{}} + \\| wc -l` |
| Lignes de prod (`*/src/main`) | **{f['prod_lines']:,}** — `:core` {f['core']['files']} f. / {f['core']['lines']:,} l. · `:app` {f['app']['files']} f. / {f['app']['lines']:,} l. | idem sous `core/src/main`, `app/src/main` |
| `@Test` JVM | **{f['jvm_tests']:,}** — `:core` {f['core_jvm_tests']:,} · `:app` {f['app_jvm_tests']:,} | `grep -rho '@Test' core/src/test app/src/test --include='*.kt' \\| wc -l` |
| Tests instrumentés | **{f['instrumented_tests']}** (appareil requis) | idem sous `app/src/androidTest*` |
| `@Preview` Compose | **{f['previews']}** | idem, `@Preview` |
| Backend Python (`server/backend`) | **{f['python_files']}** fichiers, **{f['python_lines']:,}** lignes | `find server/backend -name '*.py' \\| wc -l` |
| Docs | **{f['docs']}** fichiers dans `docs/` | `ls docs/*.md \\| wc -l` |
| Workflows CI | **{f['workflows']}** | `ls .github/workflows/*.yml \\| wc -l` |
| Plus gros fichier de prod | `{f['biggest_file']}` — **{f['biggest_lines']}** lignes | `find core app -path '*/src/main/*' -name '*.kt' \\| xargs wc -l \\| sort -rn \\| head` |"""


def main():
    data = figures()
    if "--json" in sys.argv:
        print(json.dumps(data, indent=2, ensure_ascii=False))
    else:
        print(markdown(data))


if __name__ == "__main__":
    main()
