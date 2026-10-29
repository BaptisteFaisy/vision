#!/usr/bin/env python3
"""Tests de la garde `tools/check-apk-keys.py`.

Lancer : python3 -m unittest tools/test_check_apk_keys.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.

Chaque cas monte un **faux dépôt** (`local.properties` + faux APK) et un **faux
LLM** (serveur HTTP local qui répond le statut voulu sur `POST
/chat/completions`), puis lance la garde en sous-processus — donc sur son vrai
code de retour. Aucun appel réseau sortant, aucun fichier du dépôt touché, et
la fausse clé n'apparaît jamais dans les sorties vérifiées.
"""
import http.server
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-apk-keys.py")

FAUSSE_CLE = "cle-fausse-de-test-0123456789abcdef"
FAUX_MODELE = "faux/modele-test"


def port_libre():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class FauxLlm:
    """Serveur `POST /chat/completions` qui répond un statut fixe."""

    def __init__(self, statut):
        port = port_libre()
        statut_voulu = statut

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.path == "/v1/chat/completions":
                    corps = b'{"choices":[]}'
                    self.send_response(statut_voulu)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(corps)))
                    self.end_headers()
                    self.wfile.write(corps)
                else:
                    self.send_response(404)
                    self.end_headers()

            def log_message(self, *args):
                pass

        self.serveur = http.server.HTTPServer(("127.0.0.1", port), Handler)
        self.base = "http://127.0.0.1:%d/v1" % port
        self.fil = threading.Thread(target=self.serveur.serve_forever,
                                    daemon=True)
        self.fil.start()

    def fermer(self):
        self.serveur.shutdown()
        self.serveur.server_close()


def faux_depot(racine, avec_clau=True, cle_dans_dex=True):
    """`local.properties` + faux APK (`classes.dex` avec ou sans la clé)."""
    if avec_clau:
        with open(os.path.join(racine, "local.properties"), "w",
                  encoding="utf-8") as f:
            f.write("llm.base.url=https://faux.invalid/v1\n")
            f.write("llm.api.key=%s\n" % FAUSSE_CLE)
            f.write("llm.model=%s\n" % FAUX_MODELE)
    dossier = os.path.join(racine, "app/build/outputs/apk/debug")
    os.makedirs(dossier, exist_ok=True)
    dex = b"\x00dex" + (FAUSSE_CLE.encode() if cle_dans_dex else b"sans-cle")
    with zipfile.ZipFile(os.path.join(dossier, "app-debug.apk"), "w") as zf:
        zf.writestr("classes.dex", dex)


def lancer(racine, base=None, cle=None, modele=None):
    env = dict(os.environ)
    env["LLM_BASE_URL"] = base or ""
    env["LLM_API_KEY"] = cle or ""
    env["LLM_MODEL"] = modele or ""
    return subprocess.run([sys.executable, SCRIPT, "--root", racine],
                          capture_output=True, text=True, timeout=120,
                          env=env, cwd=ROOT)


class GardeClesTest(unittest.TestCase):
    def test_sans_secret_garde_inactive(self):
        with tempfile.TemporaryDirectory() as racine:
            res = lancer(racine)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("inactive", res.stdout)

    def test_secret_incomplet_refuse(self):
        with tempfile.TemporaryDirectory() as racine:
            res = lancer(racine, cle=FAUSSE_CLE)
        self.assertEqual(res.returncode, 1)
        self.assertIn("incomplets", res.stdout)
        self.assertNotIn(FAUSSE_CLE, res.stdout + res.stderr)

    def test_local_properties_absent_refuse(self):
        with tempfile.TemporaryDirectory() as racine:
            os.makedirs(os.path.join(racine, "app/build/outputs/apk/debug"))
            res = lancer(racine, base="http://127.0.0.1:9/v1",
                         cle=FAUSSE_CLE, modele=FAUX_MODELE)
        self.assertEqual(res.returncode, 1)
        self.assertIn("local.properties absent", res.stdout)

    def test_local_properties_sans_cle_refuse(self):
        with tempfile.TemporaryDirectory() as racine:
            with open(os.path.join(racine, "local.properties"), "w",
                      encoding="utf-8") as f:
                f.write("llm.api.key=une-autre-cle\n")
            res = lancer(racine, base="http://127.0.0.1:9/v1",
                         cle=FAUSSE_CLE, modele=FAUX_MODELE)
        self.assertEqual(res.returncode, 1)
        self.assertIn("sans la clé du secret", res.stdout)
        self.assertNotIn(FAUSSE_CLE, res.stdout + res.stderr)

    def test_apk_absent_refuse(self):
        with tempfile.TemporaryDirectory() as racine:
            with open(os.path.join(racine, "local.properties"), "w",
                      encoding="utf-8") as f:
                f.write("llm.api.key=%s\n" % FAUSSE_CLE)
            res = lancer(racine, base="http://127.0.0.1:9/v1",
                         cle=FAUSSE_CLE, modele=FAUX_MODELE)
        self.assertEqual(res.returncode, 1)
        self.assertIn("aucun APK", res.stdout)

    def test_cle_absente_du_dex_refuse(self):
        with tempfile.TemporaryDirectory() as racine:
            faux_depot(racine, cle_dans_dex=False)
            res = lancer(racine, base="http://127.0.0.1:9/v1",
                         cle=FAUSSE_CLE, modele=FAUX_MODELE)
        self.assertEqual(res.returncode, 1)
        self.assertIn("absente du dex", res.stdout)

    def test_ping_refuse_par_le_llm(self):
        faux = FauxLlm(401)
        try:
            with tempfile.TemporaryDirectory() as racine:
                faux_depot(racine)
                res = lancer(racine, base=faux.base, cle=FAUSSE_CLE,
                             modele=FAUX_MODELE)
        finally:
            faux.fermer()
        self.assertEqual(res.returncode, 1)
        self.assertIn("HTTP 401", res.stdout)
        self.assertNotIn(FAUSSE_CLE, res.stdout + res.stderr)

    def test_tout_bon_accepte(self):
        faux = FauxLlm(200)
        try:
            with tempfile.TemporaryDirectory() as racine:
                faux_depot(racine)
                res = lancer(racine, base=faux.base, cle=FAUSSE_CLE,
                             modele=FAUX_MODELE)
        finally:
            faux.fermer()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("HTTP 200", res.stdout)
        self.assertNotIn(FAUSSE_CLE, res.stdout + res.stderr)


if __name__ == "__main__":
    unittest.main()
