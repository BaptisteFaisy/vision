#!/usr/bin/env python3
"""Garde du runbook WebSocket (doc ↔ code, GPU unique, CI câblée).

Vérifie, sans compilation ni réseau (stdlib, secondes) :
  1. chaque budget du §2 de `docs/WS-RUNBOOK.md` est la valeur du code
     (ancre `fichier::SYMBOLE` + jeton attendu sur la même ligne) ;
  2. les invariants d'ordre (ping < idle, froid < frame serveur…) ;
  3. l'unicité GPU (un conteneur, clamp 0..1, déploiement épinglé,
     pas de `min_containers=1` dans les workflows) ;
  4. le job `ci-budget` reste câblé dans `ci.yml` (c'est lui qui
     enforce les 5 min par job — cette garde ne le duplique pas).

Usage : python3 tools/check-ws-runbook.py [--root DIR]
Sortie 0 = conforme, 1 = régression (détail sur stdout).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DOC = Path("docs/WS-RUNBOOK.md")
WS_PUMP = Path("server/backend/live_fast/ws_pump.py")
LIVE_APP = Path("server/backend/live_fast/live_app_fast.py")
DEPLOY = Path("server/scripts/deploy-live-fast.sh")
CI = Path(".github/workflows/ci.yml")
WORKFLOWS = Path(".github/workflows")
NET = Path("core/src/main/java/dev/sam3vision/core/net")
HTTP_LIVE = NET / "HttpLiveClient.kt"
KEEPALIVE = NET / "WsKeepAlive.kt"
WSLINK = NET / "WsLink.kt"
PROBE_FRAME = Path("app/src/main/java/dev/sam3vision/app/isee/ProbeFrame.kt")

# (symbole, chemin du code, motif de lecture, valeur canonique, jeton doc).
# Le motif capture le littéral ; `_norm` rend `4_000_000`, `5L` et `120.0`
# comparables à l'attendu.
REGISTRE = (
    ("WS_IDLE_SECONDS", WS_PUMP, r"WS_IDLE_SECONDS\s*=\s*([\d_.]+)", "120", "120 s"),
    ("WS_FRAME_SECONDS", WS_PUMP, r"WS_FRAME_SECONDS\s*=\s*([\d_.]+)", "75", "75 s"),
    ("WS_MAX_REPLY_BYTES", WS_PUMP, r"WS_MAX_REPLY_BYTES\s*=\s*([\d_]+)", "4000000", "4 Mo"),
    ("WS_CLIENT_PING_SECONDS", WS_PUMP, r"WS_CLIENT_PING_SECONDS\s*=\s*([\d_.]+)", "30", "30 s"),
    ("WS_MAX_IN_FLIGHT", WS_PUMP, r"WS_MAX_IN_FLIGHT\s*=\s*(\d+)", "2", "2"),
    ("LIVE_TIMEOUT_SECONDS", HTTP_LIVE, r"LIVE_TIMEOUT_SECONDS[^\n=]*=\s*([\d_]+)", "5", "5 s"),
    ("COLD_TIMEOUT_SECONDS", PROBE_FRAME, r"COLD_TIMEOUT_SECONDS[^\n=]*=\s*([\d_]+)", "60", "60 s"),
    ("PING_INTERVAL_MS", KEEPALIVE, r"PING_INTERVAL_MS[^\n=]*=\s*([\d_]+)", "30000", "30 s"),
    ("baseDelayMs", WSLINK, r"baseDelayMs[^\n=]*=\s*([\d_]+)", "500", "500 ms"),
    ("maxDelayMs", WSLINK, r"maxDelayMs[^\n=]*=\s*([\d_]+)", "8000", "8 s"),
    ("max_containers", LIVE_APP, r"max_containers\s*=\s*(\d+)", "1", "1"),
)

# Invariants d'ordre sur les valeurs lues : (petit, grand, libellé).
# Unités ramenées en secondes (PING_INTERVAL_MS et budgets WsLink en ms).
ORDRE = (
    ("WS_CLIENT_PING_SECONDS", "WS_IDLE_SECONDS", "ping protocole < idle serveur"),
    ("PING_INTERVAL_MS", "WS_IDLE_SECONDS", "keepalive < idle serveur"),
    ("LIVE_TIMEOUT_SECONDS", "WS_FRAME_SECONDS", "régime client < frame serveur"),
    ("COLD_TIMEOUT_SECONDS", "WS_FRAME_SECONDS", "froid client < frame serveur"),
    ("maxDelayMs", "WS_IDLE_SECONDS", "backoff max < idle serveur"),
)

MS = {"PING_INTERVAL_MS", "baseDelayMs", "maxDelayMs"}


def _norm(litteral):
    """`4_000_000` → 4000000.0, `5L` → 5.0, `120.0` → 120.0."""
    return float(litteral.replace("_", "").rstrip("L").rstrip("f"))


def _lit(racine, chemin, motif):
    src = (racine / chemin).read_text(encoding="utf-8")
    trouve = re.search(motif, src)
    return _lit_valeur(trouve)


def _lit_valeur(trouve):
    if trouve is None:
        return None
    return _norm(trouve.group(1))


def check_parite(racine):
    """§2 du runbook = valeurs du code ; le symbole disparu est une dérive."""
    erreurs = []
    try:
        doc = (racine / DOC).read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        return ["runbook absent : %s" % DOC]
    for symbole, chemin, motif, attendu, jeton in REGISTRE:
        try:
            valeur = _lit(racine, chemin, motif)
        except FileNotFoundError:
            erreurs.append("code absent : %s" % chemin)
            continue
        if valeur is None:
            erreurs.append("%s illisible dans %s" % (symbole, chemin))
            continue
        if valeur != float(attendu):
            erreurs.append("%s vaut %g dans %s, runbook dit %s" % (symbole, valeur, chemin, jeton))
        lignes = [ligne for ligne in doc if symbole in ligne]
        if not lignes:
            erreurs.append("%s sans ancre dans %s" % (symbole, DOC))
        elif not any(jeton in ligne for ligne in lignes):
            erreurs.append("%s : jeton %r absent de %s" % (symbole, jeton, DOC))
    return erreurs


def check_ordre(racine):
    """Les budgets restent rangés (un glissement inverse idle et ping)."""
    erreurs = []
    valeurs = {}
    for symbole, chemin, motif, _attendu, _jeton in REGISTRE:
        try:
            valeurs[symbole] = _lit(racine, chemin, motif)
        except FileNotFoundError:
            valeurs[symbole] = None
    for petit, grand, libelle in ORDRE:
        bas, haut = valeurs.get(petit), valeurs.get(grand)
        if bas is None or haut is None:
            continue  # déjà signalé par check_parite
        bas_s = bas / 1000.0 if petit in MS else bas
        if not bas_s < haut:
            erreurs.append("invariant rompu : %s (%g >= %g s)" % (libelle, bas_s, haut))
    return erreurs


def check_gpu_unique(racine):
    """Un seul conteneur GPU ; le déploiement prod reste épinglé."""
    erreurs = []
    try:
        app = (racine / LIVE_APP).read_text(encoding="utf-8")
    except FileNotFoundError:
        return ["code absent : %s" % LIVE_APP]
    if "max_containers=1" not in app.replace(" ", ""):
        erreurs.append("max_containers != 1 dans %s" % LIVE_APP)
    clamp = re.search(r'FAST_MIN_CONTAINERS"\),\s*0,\s*0,\s*(\d+)\)', app)
    if clamp is None or clamp.group(1) != "1":
        erreurs.append("MIN_CONTAINERS plus clampé 0..1 dans %s" % LIVE_APP)
    for flot in sorted((racine / WORKFLOWS).glob("*.yml")):
        lignes = flot.read_text(encoding="utf-8").splitlines()
        code = "\n".join(ligne.split("#", 1)[0] for ligne in lignes)
        if "min_containers=1" in code.replace(" ", ""):
            erreurs.append("min_containers=1 dans %s (2e GPU interdite)" % flot.name)
    try:
        deploy = (racine / DEPLOY).read_text(encoding="utf-8")
    except FileNotFoundError:
        return erreurs + ["déploiement absent : %s" % DEPLOY]
    deploy_code = "\n".join(ligne.split("#", 1)[0] for ligne in deploy.splitlines())
    if "min_containers=1" in deploy_code.replace(" ", ""):
        erreurs.append("min_containers=1 dans %s (2e GPU interdite)" % DEPLOY)
    if "FAST_SCALEDOWN=60" not in deploy:
        erreurs.append("FAST_SCALEDOWN=60 non épinglé dans %s" % DEPLOY)
    if "sam3-eu-a100" not in deploy:
        erreurs.append("app sam3-eu-a100 non épinglée dans %s" % DEPLOY)
    return erreurs


def check_ci_budget(racine):
    """Le job `ci-budget` (5 min par job) reste câblé dans `ci.yml`."""
    try:
        ci = (racine / CI).read_text(encoding="utf-8")
    except FileNotFoundError:
        return ["workflow absent : %s" % CI]
    if re.search(r"^  ci-budget:\s*(#.*)?$", ci, re.MULTILINE) is None:
        return ["job ci-budget décroché de %s (budget 5 min perdu)" % CI]
    return []


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    racine = Path(parser.parse_args(argv).root)
    erreurs = (
        check_parite(racine)
        + check_ordre(racine)
        + check_gpu_unique(racine)
        + check_ci_budget(racine)
    )
    for erreur in erreurs:
        print("KO %s" % erreur)
    if erreurs:
        return 1
    print("OK runbook WS : doc = code, GPU unique, ci-budget câblé")
    return 0


if __name__ == "__main__":
    sys.exit(main())
