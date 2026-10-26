#!/usr/bin/env python3
"""Faux LLM compatible OpenAI (`POST /v1/chat/completions`), pour la recette lunettes
sans dépendre du réseau du téléphone.

Pourquoi il existe : sur le téléphone 2c6852ac, l'app ne résout pas `api.inferhub.dev`
(`UnknownHostException` / `getaddrinfo EAI_NODATA`) alors que le shell du téléphone la
résout — le mock SAM local, lui, est joint sans problème. Ce faux LLM complète donc la
chaîne sur `127.0.0.1`, à poser avec `adb reverse`, pour prouver `frames → SAM → LLM → voix`
sans dépendre du DNS ni d'une clé.

Réponse : une phrase française construite sur les libellés reçus dans le prompt
(`Objets détectés : …` de `LlmPrompts`), donc la voix annonce quelque chose de cohérent.

Usage : python3 tools/mock-llm.py [--port 8098]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LABELS = re.compile(r"«\s*([^»]{2,40}?)\s*»")


def texte_des_messages(messages: list) -> str:
    """Concatène le texte des messages (contenu simple ou liste de parties)."""
    morceaux = []
    for message in messages:
        contenu = message.get("content") if isinstance(message, dict) else None
        if isinstance(contenu, str):
            morceaux.append(contenu)
        elif isinstance(contenu, list):
            morceaux += [p.get("text", "") for p in contenu if isinstance(p, dict)]
    return "\n".join(morceaux)


def description(question: str) -> str:
    """Phrase française plausible, reprenant les libellés entre guillemets français."""
    labels = [m.group(1) for m in LABELS.finditer(question)][:3]
    if not labels:
        return "Je vois une scène devant moi, sans objet identifiable avec certitude."
    if len(labels) == 1:
        return f"Je vois {labels[0]}."
    return "Je vois " + ", ".join(labels[:-1]) + " et " + labels[-1] + "."


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self) -> None:  # noqa: N802 (nom imposé par BaseHTTPRequestHandler)
        taille = int(self.headers.get("Content-Length") or 0)
        corps = self.rfile.read(taille).decode("utf-8", "replace")
        try:
            demande = json.loads(corps)
            question = texte_des_messages(demande.get("messages", []))
        except json.JSONDecodeError:
            question = corps
        reponse = {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": "mock-llm",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": description(question)},
                         "finish_reason": "stop"}],
        }
        charge = json.dumps(reponse, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(charge)))
        self.end_headers()
        self.wfile.write(charge)
        print(f"POST /v1/chat/completions {taille} octets → {description(question)[:70]}", flush=True)

    def do_GET(self) -> None:  # noqa: N802
        charge = json.dumps({"ok": True, "model": "mock-llm"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(charge)))
        self.end_headers()
        self.wfile.write(charge)

    def log_message(self, *_: object) -> None:  # journal silencieux (le nôtre suffit)
        return


def main() -> int:
    parseur = argparse.ArgumentParser()
    parseur.add_argument("--host", default="127.0.0.1")
    parseur.add_argument("--port", type=int, default=8098)
    args = parseur.parse_args()
    serveur = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"mock-llm: écoute sur http://{args.host}:{args.port}/v1 (OpenAI-compatible)", flush=True)
    try:
        serveur.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())