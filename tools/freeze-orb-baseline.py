#!/usr/bin/env python3
"""Régénère tools/orb-frozen-baseline.json depuis l'arbre courant.

À n'utiliser que pour un changement de la boule EXPLICITEMENT demandé par le
user (docs/ISEE-DESIGN-PORTRAIT.md §6) : toute autre dérive du code gelé doit
être annulée, pas refigée. Affiche les entrées qui changent, pour relecture.

Usage : python3 tools/freeze-orb-baseline.py [racine]
"""
import argparse
import importlib.util
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import guard_lib


def _design(root):
    """Charge tools/check-isee-design.py (tirets : importlib, comme load_checker)."""
    path = os.path.join(root, "tools", "check-isee-design.py")
    spec = importlib.util.spec_from_file_location("_isee_design", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main(argv=None):
    parser = argparse.ArgumentParser(description="Refige la boule Portrait gelée.")
    parser.add_argument("root", nargs="?", default=".")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    design = _design(args.root)
    current = design.orb_hashes(args.root, guard_lib.load_checker(args.root))
    base_path = os.path.join(args.root, design.ORB_BASELINE)
    old = {}
    if os.path.isfile(base_path):
        with open(base_path, encoding="utf-8") as handle:
            old = json.load(handle)
    for section in ("files", "regions"):
        before, after = old.get(section, {}), current[section]
        for key in sorted(set(before) | set(after)):
            if before.get(key) != after.get(key):
                print("  %s: %s -> %s"
                      % (key, (before.get(key) or "absent")[:12],
                         (after.get(key) or "absent")[:12]))
    with open(base_path, "w", encoding="utf-8") as handle:
        json.dump(current, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print("baseline écrite : %s" % base_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
