#!/usr/bin/env python3
"""Vérifie les limites du projet : fichiers < 500 lignes, fonctions/composants < 50 lignes."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECL = re.compile(
    r'^(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s+(\w+)'
    r'|^(?:export\s+)?const\s+(\w+)\s*(?::[^=]+)?=\s*(?:async\s*)?(?:\(|useCallback|useMemo)'
)


def long_functions(path):
    lines = path.read_text().splitlines()
    problems = []
    index = 0
    while index < len(lines):
        match = DECL.match(lines[index])
        if not match:
            index += 1
            continue
        name = match.group(1) or match.group(2)
        depth = 0
        opened = False
        end = index
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
        if length > 50:
            problems.append(f'{path.relative_to(ROOT)}:{index + 1} {name} fait {length} lignes (> 50)')
        index = end + 1
    return problems


def main():
    problems = []
    for path in sorted(ROOT.rglob('*')):
        if path.suffix not in ('.ts', '.tsx') or 'node_modules' in path.parts:
            continue
        count = len(path.read_text().splitlines())
        if count >= 500:
            problems.append(f'{path.relative_to(ROOT)} fait {count} lignes (>= 500)')
        problems.extend(long_functions(path))
    print('\n'.join(problems) if problems else 'OK — fichiers < 500 lignes, fonctions < 50 lignes')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
