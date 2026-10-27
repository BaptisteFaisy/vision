#!/usr/bin/env python3
"""Couche 0 anti-régression — périmètre déclaré + motifs interdits.

Deux contrôles seulement, bibliothèque standard uniquement.

  scope      tout fichier modifié doit être déclaré dans la section `## Scope`
             du corps de la PR — et toute déclaration doit servir.
  forbidden  motifs refusés dans les lignes AJOUTÉES du diff, quel que soit le
             périmètre déclaré ; les fichiers de garde sont gelés.

Usage : check_couche0.py check --pr N [--repo owner/name] [--control scope|forbidden]
Codes de sortie : 0 = OK, 1 = contrôle(s) en échec, 2 = erreur interne.
"""

import argparse
import fnmatch
import json
import os
import re
import sys
import urllib.request

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
MAX_PAGES = 20

# Fichiers de garde : les modifier exige une mention explicite dans le corps.
GUARD_FILES = (".github/ci-budget/", ".github/couche0/",
               ".github/workflows/couche0.yml", ".github/workflows/ci-budget.yml",
               # Fermé le 26/10 : sans ça une PR pouvait retirer les tests du job
               # `ci` sans le déclarer — la porte ouverte derrière la garde.
               ".github/workflows/ci.yml")
GUARD_FLAG = "SAMVISION_ALLOW_GUARD_BUMP"

# Exemptés de déclaration de périmètre : régénérable ou non signé.
EXEMPT_SUBSTR = ("/build/", "gradle/wrapper/", ".gradle/")
EXEMPT_SUFFIX = (".lock", ".png", ".webp", ".jpg", ".xml.bak")

# Troisième élément : sous-chaîne qui exempte la ligne (faux positif documenté).
FORBIDDEN_PATTERNS = [
    (r"-X\s+(ours|theirs)\b", "prise de côté forcée (-X ours/theirs)", None),
    (r"--force(?!-with-lease)\b", "push forcé sans --force-with-lease",
     "modal secret create"),  # faux positif : écrasement idempotent du secret Modal
    (r"pull_request_target", "déclencheur à privilèges pull_request_target", None),
    (r"sk-[A-Za-z0-9_\-]{24,}", "clé API sk-… en clair", None),
    (r"sk_[a-f0-9]{32}", "clé API sk_… en clair", None),
    (r"ghp_[A-Za-z0-9]{36}", "jeton GitHub ghp_… en clair", None),
    (r"^sdk\.dir=", "sdk.dir en dur (local.properties ne se versionne pas)", None),
    (r"2026-10-2[0-5]\b|\b2[0-5]/10\b",
     "date antérieure au 26/10 19 h (citer « relevé antérieur, hors de cet arbre »)", None),
]


def api_get(path, token):
    """GET sur l'API GitHub, retourne du JSON décodé."""
    req = urllib.request.Request(API + path)
    req.add_header("Authorization", "bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "couche0")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def pr_context(repo, number, token):
    """Récupère (corps de la PR, liste des fichiers modifiés)."""
    pr = api_get("/repos/%s/pulls/%s" % (repo, number), token)
    files, page = [], 1
    while page <= MAX_PAGES:
        chunk = api_get("/repos/%s/pulls/%s/files?per_page=100&page=%d"
                        % (repo, number, page), token)
        files.extend(chunk)
        if len(chunk) < 100:
            break
        page += 1
    return (pr.get("body") or ""), files


def parse_scope(body):
    """Motifs déclarés dans `## Scope` (puces `- \`motif\` — raison`)."""
    patterns, inside = [], False
    for line in body.splitlines():
        if line.strip().lower().startswith("## scope"):
            inside = True
            continue
        if inside and line.strip().startswith("## "):
            break
        if not inside:
            continue
        match = re.match(r"^\s*[-*]\s+`?([^`\s]+)`?", line)
        if match:
            patterns.append(match.group(1).strip().strip("`"))
    return patterns


def matches(pattern, path):
    """Un motif désigne un fichier : exact, dossier (préfixe), ou glob."""
    if pattern == path:
        return True
    if pattern.endswith("/"):
        return path.startswith(pattern) or ("/" + pattern) in ("/" + path)
    if not re.search(r"[*?\[]", pattern):
        return path.startswith(pattern + "/")
    return fnmatch.fnmatch(path, pattern.replace("**", "*"))


def is_exempt(path):
    """Fichiers régénérables : pas besoin de les déclarer."""
    return any(s in path for s in EXEMPT_SUBSTR) or path.endswith(EXEMPT_SUFFIX)


def check_scope(files, body, errors):
    """Tout fichier modifié déclaré, toute déclaration servie."""
    declared = parse_scope(body)
    paths = [f["filename"] for f in files if not is_exempt(f["filename"])]
    if not declared:
        if paths:
            errors.append("scope : aucune section `## Scope` dans le corps de la "
                          "PR, alors que %d fichier(s) sont modifiés." % len(paths))
        return
    used = set()
    for path in paths:
        hit = [p for p in declared if matches(p, path)]
        if hit:
            used.update(hit)
        else:
            errors.append("scope : `%s` modifié mais non déclaré dans `## Scope`." % path)
    unused = [p for p in declared if p not in used]
    if unused:
        errors.append("scope : déclaration(s) inutilisée(s) — périmètre surdimensionné : %s"
                      % ", ".join("`%s`" % p for p in unused))


def added_lines(files):
    """Lignes ajoutées du diff, par fichier (en-têtes de fichier exclus)."""
    for f in files:
        for line in (f.get("patch") or "").splitlines():
            if line.startswith("+") and not line.startswith("+++"):
                yield f["filename"], line[1:]


def check_forbidden(files, body, errors):
    """Motifs interdits dans les lignes ajoutées + fichiers de garde gelés."""
    compiled = [(re.compile(p, re.M), m, x) for p, m, x in FORBIDDEN_PATTERNS]
    for path, line in added_lines(files):
        # Le garde se cite lui-même (motifs, exemples) : il est déjà gelé
        # plus bas (GUARD_FLAG), donc hors du scan de motifs.
        if path.startswith(".github/couche0/"):
            continue
        for rx, message, exempt in compiled:
            if exempt and exempt in line:
                continue
            if rx.search(line):
                errors.append("forbidden : %s — `%s` dans %s" % (message, line.strip()[:90], path))
    frozen = [f["filename"] for f in files if f["filename"].startswith(GUARD_FILES)]
    if frozen and GUARD_FLAG not in body:
        errors.append("forbidden : fichier(s) de garde modifié(s) sans la mention `%s` "
                      "dans le corps : %s" % (GUARD_FLAG, ", ".join(frozen)))


def main(argv=None):
    ap = argparse.ArgumentParser(prog="check_couche0")
    ap.add_argument("cmd", choices=["check"])
    ap.add_argument("--pr", required=True, type=int)
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--control", choices=["scope", "forbidden"], default=None)
    args = ap.parse_args(argv)
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not args.repo or not token:
        print("::error::GITHUB_REPOSITORY et GITHUB_TOKEN sont requis")
        return 2
    try:
        body, files = pr_context(args.repo, args.pr, token)
    except Exception as exc:                         # noqa: BLE001
        print("::error::API GitHub : %s" % exc)
        return 2
    errors = []
    if args.control in (None, "scope"):
        check_scope(files, body, errors)
    if args.control in (None, "forbidden"):
        check_forbidden(files, body, errors)
    for message in errors:
        print("::error::%s" % message)
    print("%s — %d fichier(s) controlé(s), %d erreur(s)"
          % ("OK" if not errors else "REFUSÉ", len(files), len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
