#!/usr/bin/env python3
"""Transport A2A vers l'agent du daemon zeroclaw des runners (agent `b`).

Le resolver de conflits n'interroge plus une API de modèle : il demande la
résolution à l'agent `b`, publié en A2A par le daemon local (provider
InferHub). Le jeton de la passerelle est lu dans un fichier 600 et n'est
jamais journalisé. Module séparé pour que `conflict_agent.py` reste sous le
plafond de 500 lignes (`tools/check-kotlin-limits.py`).
"""

import json
import os
import urllib.request

URL_DEFAULT = "http://127.0.0.1:42617"
TOKEN_DEFAULT = "~/.config/zeroclaw-serveur/token"
TOKEN_FALLBACK = "/opt/zeroclaw/ephem/data/gateway-admin.token"


def token():
    """Jeton de la passerelle ; None s'il est introuvable."""
    candidats = (os.environ.get("CONFLICT_ZC_TOKEN_FILE") or TOKEN_DEFAULT,
                 TOKEN_FALLBACK)
    for candidat in candidats:
        try:
            with open(os.path.expanduser(candidat), encoding="utf-8") as fh:
                jeton = fh.read().strip()
        except OSError:
            continue
        if jeton:
            return jeton
    return None


def env_or(name, default):
    """Variable d'env ou défaut (GitHub injecte une var absente comme "")."""
    return os.environ.get(name) or default


def send(url, agent, jeton, prompt, timeout_s):
    """Envoie `prompt` à l'agent (JSON-RPC `SendMessage`) ; rend son texte.

    Lève RuntimeError avec un message publiable (sans secret) en cas d'échec.
    """
    corps = json.dumps({
        "jsonrpc": "2.0", "id": "conflit", "method": "SendMessage",
        "params": {"message": {
            "role": "ROLE_USER", "messageId": "conflit-vision",
            "parts": [{"kind": "text", "text": prompt}]}},
    }).encode("utf-8")
    requete = urllib.request.Request(
        "%s/a2a/%s" % (url.rstrip("/"), agent), data=corps, method="POST",
        headers={"Authorization": "Bearer " + jeton,
                 "Content-Type": "application/json",
                 "A2A-Version": "1.0"})
    try:
        with urllib.request.urlopen(requete, timeout=timeout_s) as reponse:
            data = json.loads(reponse.read().decode("utf-8", "replace") or "{}")
    except Exception as exc:                                   # noqa: BLE001
        raise RuntimeError("passerelle %s : %s" % (url, str(exc)[:200]))
    if "error" in data:
        raise RuntimeError("erreur A2A (%s) : %s"
                           % (agent, json.dumps(data["error"])[:200]))
    tache = (data.get("result") or {}).get("task") or {}
    texte = "\n".join(partie.get("text", "")
                      for artefact in tache.get("artifacts") or []
                      for partie in artefact.get("parts") or []
                      if isinstance(partie, dict))
    if not texte.strip():
        raise RuntimeError("réponse vide de l'agent `%s`" % agent)
    return texte
