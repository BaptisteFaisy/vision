#!/usr/bin/env python3
"""Tests de la garde du runbook WS : stdlib, secondes, aucun réseau."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check-ws-runbook.py"
_SPEC = importlib.util.spec_from_file_location("check_ws_runbook", SCRIPT)
garde = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(garde)

PUMP = """\
WS_CLIENT_PING_SECONDS = 30.0
WS_IDLE_SECONDS = 120.0
WS_FRAME_SECONDS = 75.0
WS_MAX_REPLY_BYTES = 4_000_000
WS_MAX_IN_FLIGHT = 2
"""

APP = """\
MIN_CONTAINERS = clamp_int(os.environ.get("FAST_MIN_CONTAINERS"), 0, 0, 1)
    max_containers=1,
"""

DEPLOY = "APP=sam3-eu-a100 FAST_SCALEDOWN=60 modal deploy\n"
CI = "jobs:\n  ci-budget:\n    timeout-minutes: 5\n"
HTTP_LIVE = "const val LIVE_TIMEOUT_SECONDS: Long = 5L\n"
PROBE = "internal const val COLD_TIMEOUT_SECONDS = 60L\n"
KEEPALIVE = "internal const val PING_INTERVAL_MS = 30_000L\n"
WSLINK = "val baseDelayMs: Long = 500L\nval maxDelayMs: Long = 8_000L\n"

DOC_LIGNES = (
    ("WS_IDLE_SECONDS", "120 s"),
    ("WS_FRAME_SECONDS", "75 s"),
    ("WS_MAX_REPLY_BYTES", "4 Mo"),
    ("WS_CLIENT_PING_SECONDS", "30 s"),
    ("WS_MAX_IN_FLIGHT", "2"),
    ("LIVE_TIMEOUT_SECONDS", "5 s"),
    ("COLD_TIMEOUT_SECONDS", "60 s"),
    ("PING_INTERVAL_MS", "30 s"),
    ("baseDelayMs", "500 ms"),
    ("maxDelayMs", "8 s"),
    ("max_containers", "1"),
)


def arbre_conforme(racine):
    fichiers = {
        garde.DOC: "".join("| %s | %s |\n" % paire for paire in DOC_LIGNES),
        garde.WS_PUMP: PUMP,
        garde.LIVE_APP: APP,
        garde.DEPLOY: DEPLOY,
        garde.CI: CI,
        garde.HTTP_LIVE: HTTP_LIVE,
        garde.PROBE_FRAME: PROBE,
        garde.KEEPALIVE: KEEPALIVE,
        garde.WSLINK: WSLINK,
    }
    for chemin, contenu in fichiers.items():
        cible = racine / chemin
        cible.parent.mkdir(parents=True, exist_ok=True)
        cible.write_text(contenu, encoding="utf-8")
    return racine


class WsRunbookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.racine = arbre_conforme(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def ecrire(self, chemin, contenu):
        (self.racine / chemin).write_text(contenu, encoding="utf-8")

    def test_conforme(self):
        self.assertEqual(garde.check_parite(self.racine), [])
        self.assertEqual(garde.check_ordre(self.racine), [])
        self.assertEqual(garde.check_gpu_unique(self.racine), [])
        self.assertEqual(garde.check_ci_budget(self.racine), [])

    def test_derive_code(self):
        self.ecrire(garde.WS_PUMP, PUMP.replace("120.0", "45.0"))
        erreurs = garde.check_parite(self.racine)
        self.assertEqual(len(erreurs), 1)
        self.assertIn("WS_IDLE_SECONDS", erreurs[0])

    def test_jeton_doc_absent(self):
        doc = (self.racine / garde.DOC).read_text(encoding="utf-8")
        self.ecrire(garde.DOC, doc.replace("120 s", "deux minutes"))
        erreurs = garde.check_parite(self.racine)
        self.assertEqual(len(erreurs), 1)
        self.assertIn("120 s", erreurs[0])

    def test_runbook_absent(self):
        (self.racine / garde.DOC).unlink()
        self.assertEqual(len(garde.check_parite(self.racine)), 1)

    def test_invariant_rompu(self):
        self.ecrire(garde.WS_PUMP, PUMP.replace("120.0", "20.0"))
        erreurs = garde.check_ordre(self.racine)
        self.assertTrue(any("ping protocole < idle" in e for e in erreurs))

    def test_deuxieme_gpu(self):
        self.ecrire(garde.LIVE_APP, APP.replace("max_containers=1", "max_containers=2"))
        erreurs = garde.check_gpu_unique(self.racine)
        self.assertTrue(any("max_containers" in e for e in erreurs))

    def test_min_containers_workflow(self):
        flot = self.racine / garde.WORKFLOWS / "deploy-live-fast.yml"
        flot.parent.mkdir(parents=True, exist_ok=True)
        flot.write_text("run: min_containers=1\n", encoding="utf-8")
        erreurs = garde.check_gpu_unique(self.racine)
        self.assertTrue(any("2e GPU" in e for e in erreurs))

    def test_min_containers_en_commentaire_tolere(self):
        flot = self.racine / garde.WORKFLOWS / "ci.yml"
        contenu = flot.read_text(encoding="utf-8")
        flot.write_text(contenu + "      # pas de min_containers=1 ici\n", encoding="utf-8")
        self.assertEqual(garde.check_gpu_unique(self.racine), [])

    def test_deploy_non_epingle(self):
        self.ecrire(garde.DEPLOY, "modal deploy\n")
        erreurs = garde.check_gpu_unique(self.racine)
        self.assertEqual(len(erreurs), 2)

    def test_ci_budget_decroche(self):
        self.ecrire(garde.CI, "jobs:\n  ci:\n    timeout-minutes: 5\n")
        erreurs = garde.check_ci_budget(self.racine)
        self.assertEqual(len(erreurs), 1)
        self.assertIn("ci-budget", erreurs[0])


if __name__ == "__main__":
    unittest.main()
