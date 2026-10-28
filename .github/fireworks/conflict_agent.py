#!/usr/bin/env python3
"""Agent de conflit Fireworks pour la file pr-auto-merge.

Quand le sweep de pr-auto-merge échoue à rebaser une PR (conflit), il appelle
ce script DANS son clone, rebase en cours (fichiers non fusionnés présents).
Le script demande à un modèle Fireworks la résolution de chaque fichier,
termine le rebase, pousse sur la branche de la PR et rend compte.

Bibliothèque standard uniquement. Usage :

  FIREWORKS_API_KEY=... python3 conflict_agent.py resolve \\
      --repo BaptisteFaisy/vision --pr 118 --branch feat/x --sha <sha>

  python3 conflict_agent.py resolve --dry-run --repo ... --pr ...  # prompt seul

Codes de sortie : 0 = rebase poussé (file reprendra) ; 1 = échec avec compte
rendu sur la PR ; 2 = rien à faire (désactivé, déjà tenté, labels) — l'appelant
saute la PR sans bruit. Jamais de secret dans les messages ni les commentaires.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

MODEL_DEFAULT = "accounts/fireworks/models/minimax-m3p1-flash"
API_DEFAULT = "https://api.fireworks.ai/inference/v1/chat/completions"
MAX_FILES = 8
MAX_FILE_BYTES = 200_000
MAX_SIDE_CHARS = 12_000
COOLDOWN_S = 20 * 60
SPAWN_WINDOW_S = 3_600
SPAWN_CAP = 3
BUDGET_S = 240
CALL_TIMEOUT_S = 90
MAX_TOKENS = 8_000
TEMPERATURE = 0.2
LABEL_OK = "agent-conflit"
LABEL_HUMAN = "intervention-humaine"
COMMENT_PREFIX = "_fireworks · agent de conflit (MiniMax M3.1 Flash)_"
REFUSED_PREFIXES = (".github/", ".mergify.yml")


class AgentError(Exception):
    """Échec avec message publiable (sans secret)."""


def sh(args, **kw):
    """Exécute une commande, rend sa sortie texte dépouillée."""
    proc = subprocess.run(args, capture_output=True, text=True, **kw)
    if proc.returncode != 0:
        raise AgentError("%s : %s" % (" ".join(args[:3]),
                                      (proc.stderr or "").strip()[:200]))
    return (proc.stdout or "").strip()


def log(message):
    print("fireworks-agent: %s" % message, flush=True)


def cache_dir():
    path = os.path.join(os.path.expanduser("~"), ".cache", "fireworks-agent")
    os.makedirs(path, exist_ok=True)
    return path


def gc_stamps(now):
    """Purge les empreintes de plus d'un jour."""
    day_ago = now - 86_400
    for name in os.listdir(cache_dir()):
        if not name.endswith(".stamp"):
            continue
        full = os.path.join(cache_dir(), name)
        try:
            if os.path.getmtime(full) < day_ago:
                os.remove(full)
        except OSError:
            pass


def recent_runs(now):
    """Nombre de saisines dans la fenêtre glissante."""
    cutoff = now - SPAWN_WINDOW_S
    count = 0
    for name in os.listdir(cache_dir()):
        if not name.endswith(".stamp"):
            continue
        try:
            if os.path.getmtime(os.path.join(cache_dir(), name)) >= cutoff:
                count += 1
        except OSError:
            pass
    return count


def stamp_path(pr, sha):
    return os.path.join(cache_dir(), "pr-%s-%s.stamp" % (pr, sha[:12]))


def has_fresh_stamp(pr, sha, now):
    try:
        return os.path.getmtime(stamp_path(pr, sha)) >= now - COOLDOWN_S
    except OSError:
        return False


def touch_stamp(pr, sha):
    with open(stamp_path(pr, sha), "w") as fh:
        fh.write(str(int(time.time())))


def pr_labels(repo, pr):
    """Noms des labels de la PR (liste vide si illisible)."""
    try:
        out = sh(["gh", "pr", "view", str(pr), "--repo", repo,
                  "--json", "labels", "-q", ".labels[].name"])
        return [line for line in out.splitlines() if line]
    except AgentError:
        return []


def comment(repo, pr, body):
    """Commente la PR ; best-effort, ne lève jamais."""
    try:
        proc = subprocess.run(["gh", "pr", "comment", str(pr), "--repo", repo,
                               "--body", COMMENT_PREFIX + "\n\n" + body],
                              capture_output=True, text=True, timeout=60)
        if proc.returncode != 0:
            log("commentaire refusé : %s" % proc.stderr.strip()[:150])
    except Exception as exc:                                   # noqa: BLE001
        log("commentaire impossible : %s" % exc)


def add_label(repo, pr, label):
    """Pose un label ; best-effort, ne lève jamais."""
    try:
        subprocess.run(["gh", "pr", "edit", str(pr), "--repo", repo,
                        "--add-label", label],
                       capture_output=True, text=True, timeout=60)
    except Exception as exc:                                   # noqa: BLE001
        log("label %s impossible : %s" % (label, exc))


def abort_rebase():
    try:
        subprocess.run(["git", "rebase", "--abort"], capture_output=True,
                       timeout=60)
    except Exception:                                          # noqa: BLE001
        pass


def unmerged_files():
    out = sh(["git", "diff", "--name-only", "--diff-filter=U"])
    return [line for line in out.splitlines() if line]


def file_version(path, stage):
    """Contenu d'un bord (:1: base, :2: branche, :3: master) ou None."""
    proc = subprocess.run(["git", "show", ":%d:%s" % (stage, path)],
                          capture_output=True, timeout=60)
    if proc.returncode != 0:
        return None
    return proc.stdout


def is_text(data):
    return b"\x00" not in data[:8_000]


def clip(text):
    if len(text) <= MAX_SIDE_CHARS:
        return text
    return text[:MAX_SIDE_CHARS] + "\n[... tronqué ...]"


def build_messages(path, base, ours, theirs):
    """Prompt de résolution pour un fichier."""
    system = ("Tu es un agent qui résout des conflits de fusion git. "
              "Tu rends le fichier ENTIER résolu, sans aucun marqueur de "
              "conflit, en gardant les deux intentions (jamais un bord "
              "choisi à l'aveugle). Tu ne réponds que par le fichier, "
              "dans un seul bloc de code.")
    parts = ["Fichier : %s" % path,
             "--- Version de la branche (à garder en priorité en cas de doute) ---",
             clip(ours)]
    if base is not None:
        parts += ["--- Version ancêtre commun ---", clip(base)]
    parts += ["--- Version de master ---", clip(theirs),
              "Rends le fichier complet résolu, dans un seul bloc de code."]
    return [{"role": "system", "content": system},
            {"role": "user", "content": "\n".join(parts)}]


def fw_complete(api_url, key, model, messages):
    """Un appel chat/completions Fireworks, rend le texte réponse."""
    payload = json.dumps({"model": model, "messages": messages,
                          "temperature": TEMPERATURE,
                          "max_tokens": MAX_TOKENS}).encode("utf-8")
    req = urllib.request.Request(api_url, data=payload, method="POST")
    req.add_header("Authorization", "Bearer " + key)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT_S) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:                                   # noqa: BLE001
        raise AgentError("appel Fireworks : %s" % str(exc)[:200])
    try:
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        raise AgentError("réponse Fireworks illisible (pas de choices[0])")


def extract_file(text):
    """Extrait le fichier résolu : bloc de code, sinon texte brut."""
    cleaned = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    match = re.search(r"```[^\n]*\n(.*?)```", cleaned, flags=re.S)
    body = match.group(1) if match else cleaned
    return body.strip("\n") + "\n"


def still_marked(content):
    """Vrai s'il reste des marqueurs de conflit git."""
    for line in content.splitlines():
        if line.startswith("<<<<<<< ") or line == "<<<<<<<":
            return True
        if line.startswith(">>>>>>> ") or line == ">>>>>>>":
            return True
        if line == "=======":
            return True
    return False


def resolve_one(api_url, key, model, path, deadline, dry_run):
    """Résout un fichier en conflit et l'ajoute à l'index."""
    if time.time() > deadline:
        raise AgentError("budget de %d s dépassé" % BUDGET_S)
    try:
        size = os.path.getsize(path)
    except OSError:
        raise AgentError("fichier illisible : %s" % path)
    if size > MAX_FILE_BYTES:
        raise AgentError("fichier trop gros : %s (%d o)" % (path, size))
    ours, theirs = file_version(path, 2), file_version(path, 3)
    if ours is None or theirs is None or not is_text(ours + theirs):
        raise AgentError("binaire ou bord manquant : %s" % path)
    base = file_version(path, 1)
    if base is not None and not is_text(base):
        base = None
    decode = lambda b: b.decode("utf-8", errors="replace")   # noqa: E731
    messages = build_messages(path,
                              decode(base) if base is not None else None,
                              decode(ours), decode(theirs))
    if dry_run:
        print("===== %s (%d messages) =====" % (path, len(messages)))
        print(messages[1]["content"][:4_000])
        return
    log("résolution de %s (%d o)" % (path, size))
    resolved = extract_file(fw_complete(api_url, key, model, messages))
    if still_marked(resolved):
        raise AgentError("le modèle a rendu des marqueurs : %s" % path)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(resolved)
    sh(["git", "add", "--", path])


def fail(repo, pr, reason):
    """Compte rendu d'échec : rebase annulé, commentaire + label humain."""
    abort_rebase()
    comment(repo, pr, "Conflit **non résolu** : %s\n\nÀ l'auteur de rebaser." % reason)
    add_label(repo, pr, LABEL_HUMAN)
    log("échec : %s" % reason)
    return 1


class Skip(Exception):
    """Rien à faire : l'appelant saute la PR sans bruit (exit 2)."""


def env_or(name, default):
    """Variable d'env ou défaut. GitHub injecte une var absente comme ""
    (et non unset) : `os.environ.get(n, d)` rendrait "" → 400 Fireworks."""
    return os.environ.get(name) or default


def preflight(repo, pr, sha, dry_run, now):
    """Garde-fous et lecture des fichiers en conflit. Rend (clé, modèle, url, fichiers)."""
    if os.environ.get("FIREWORKS_AGENT_RESOLVE", "1") == "0":
        raise Skip("désactivé (FIREWORKS_AGENT_RESOLVE=0)")
    key = os.environ.get("FIREWORKS_API_KEY", "")
    if not key and not dry_run:
        raise Skip("pas de clé FIREWORKS_API_KEY")
    gc_stamps(now)
    labels = pr_labels(repo, pr)
    if "hold" in labels or LABEL_HUMAN in labels:
        raise Skip("labels %s : main humaine" % ",".join(labels))
    if has_fresh_stamp(pr, sha, now):
        raise Skip("saisine récente pour %s, cooldown" % sha[:12])
    if recent_runs(now) >= SPAWN_CAP:
        raise Skip("plafond de %d saisines/h atteint" % SPAWN_CAP)
    try:
        files = unmerged_files()
    except AgentError as exc:
        raise Skip("git illisible : %s" % exc)
    if not files:
        raise Skip("aucun fichier en conflit")
    refused = [p for p in files if p.startswith(REFUSED_PREFIXES)]
    if refused:
        raise AgentError("conflit dans %s (main humaine requise)"
                         % ", ".join(refused))
    if len(files) > MAX_FILES:
        raise AgentError("%d fichiers en conflit (plafond %d)"
                         % (len(files), MAX_FILES))
    model = env_or("FIREWORKS_MODEL", MODEL_DEFAULT)
    api_url = env_or("FIREWORKS_BASE_URL", API_DEFAULT)
    return key, model, api_url, files


def push_url(repo):
    """URL push authentifiée (GH_TOKEN) ; "origin" en repli local."""
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        return "origin"
    return "https://x-access-token:%s@github.com/%s.git" % (token, repo)


def finish(repo, pr, branch, sha, files):
    """Termine le rebase, pousse avec bail, rend compte. Rend 0 ou 1."""
    env = dict(os.environ, GIT_EDITOR="true")
    cont = subprocess.run(["git", "rebase", "--continue"], env=env,
                          capture_output=True, text=True, timeout=120)
    if cont.returncode != 0:
        return fail(repo, pr, "rebase --continue refusé : %s"
                    % ((cont.stderr or "").strip()[:200]))
    push = subprocess.run(["git", "push",
                           "--force-with-lease=%s:%s" % (branch, sha),
                           push_url(repo), "HEAD:%s" % branch],
                          capture_output=True, text=True, timeout=120)
    if push.returncode != 0:
        log("push refusé (bail, prochain passage) : %s"
            % push.stderr.strip()[:150])
        return 1
    comment(repo, pr, "Conflit réconcilié par l'agent : %d fichier(s) "
            "(%s), rebase poussé sur `%s`. La file reprend."
            % (len(files), ", ".join(files), branch))
    add_label(repo, pr, LABEL_OK)
    log("PR #%s rebasée et poussée (%d fichiers)" % (pr, len(files)))
    return 0


def cmd_resolve(args):
    repo, pr = args.repo, args.pr
    now = time.time()
    try:
        key, model, api_url, files = preflight(repo, pr, args.sha,
                                               args.dry_run, now)
    except Skip as exc:
        log(str(exc))
        return 2
    except AgentError as exc:
        return fail(repo, pr, str(exc))
    touch_stamp(pr, args.sha)
    deadline = now + BUDGET_S
    try:
        for path in files:
            resolve_one(api_url, key, model, path, deadline, args.dry_run)
    except AgentError as exc:
        return fail(repo, pr, str(exc))
    if args.dry_run:
        return 0
    return finish(repo, pr, args.branch, args.sha, files)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="conflict_agent")
    ap.add_argument("cmd", choices=["resolve"])
    ap.add_argument("--repo", required=True)
    ap.add_argument("--pr", required=True)
    ap.add_argument("--branch", default="")
    ap.add_argument("--sha", default="")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    if args.cmd == "resolve" and not args.dry_run and not (args.branch and args.sha):
        ap.error("--branch et --sha sont requis hors --dry-run")
    return cmd_resolve(args)


if __name__ == "__main__":
    sys.exit(main())
