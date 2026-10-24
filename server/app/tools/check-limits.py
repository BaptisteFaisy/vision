#!/usr/bin/env python3
"""Garde de dépôt : fichiers < 500 lignes et fonctions < 50 lignes (TS/TSX et Python).

    python3 app/tools/check-limits.py          # depuis n'importe où
    python3 tools/check-limits.py              # depuis app/

Le TypeScript est mesuré par accolades (regex + comptage), le Python par `ast`
(fonctions et méthodes, décorateurs exclus). Sortie ≠ 0 dès un dépassement.
"""
import ast
import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]  # racine du dépôt
MAX_LINES = 500
MAX_FUNC_LINES = 50
TS_SUFFIXES = ('.ts', '.tsx')
SUFFIXES = TS_SUFFIXES + ('.py',)
SKIP_DIRS = {
    '.git', 'node_modules', '.expo', '.gradle', '.cxx', '.test-build', '__pycache__',
    'build', 'dist', 'web-build', 'android', 'venv', '.venv', '.idea', '.vscode',
}

TS_DECL = re.compile(
    r'^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)'
    r'|^(?:export\s+)?const\s+(\w+)\s*(?::[^=]+)?=\s*(?:async\s*)?(?:\(|useCallback|useMemo)'
)


def iter_sources():
    """Parcourt le dépôt en élaguant les répertoires générés."""
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = sorted(name for name in dirnames if name not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.endswith(SUFFIXES):
                yield pathlib.Path(dirpath) / name


def ts_functions(path):
    """Fonctions TS/TSX : repère la déclaration puis compte les accolades."""
    lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
    problems = []
    index = 0
    while index < len(lines):
        match = TS_DECL.match(lines[index])
        if not match:
            index += 1
            continue
        name = match.group(1) or match.group(2)
        depth, opened, end = 0, False, index
        while end < len(lines):
            for char in lines[end]:
                if char == '{':
                    depth += 1
                    opened = True
                elif char == '}':
                    depth -= 1
            if opened and depth <= 0:
                break
            end += 1
        length = end - index + 1
        if length > MAX_FUNC_LINES:
            problems.append(f'{path.relative_to(ROOT)}:{index + 1} {name} fait {length} lignes (> {MAX_FUNC_LINES})')
        index = end + 1
    return problems


def py_functions(path):
    """Fonctions et méthodes Python mesurées par `ast` (lignes du `def` à la fin)."""
    source = path.read_text(encoding='utf-8', errors='replace')
    try:
        tree = ast.parse(source)
    except SyntaxError as error:
        return [f'{path.relative_to(ROOT)}:{error.lineno} syntaxe invalide : {error.msg}']
    problems = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            length = (node.end_lineno or node.lineno) - node.lineno + 1
            if length > MAX_FUNC_LINES:
                problems.append(
                    f'{path.relative_to(ROOT)}:{node.lineno} {node.name} fait {length} lignes (> {MAX_FUNC_LINES})'
                )
    return problems


def check_file(path):
    count = len(path.read_text(encoding='utf-8', errors='replace').splitlines())
    problems = []
    if count >= MAX_LINES:
        problems.append(f'{path.relative_to(ROOT)} fait {count} lignes (>= {MAX_LINES})')
    if path.suffix in TS_SUFFIXES:
        problems.extend(ts_functions(path))
    else:
        problems.extend(py_functions(path))
    return problems


def main():
    problems = []
    checked = 0
    for path in iter_sources():
        checked += 1
        problems.extend(check_file(path))
    if problems:
        print('\n'.join(problems))
        print(f'\n{len(problems)} dépassement(s) sur {checked} fichiers.')
        return 1
    print(f'OK — {checked} fichiers (TS/TSX + Python) : < {MAX_LINES} lignes, fonctions < {MAX_FUNC_LINES} lignes')
    return 0


if __name__ == '__main__':
    sys.exit(main())