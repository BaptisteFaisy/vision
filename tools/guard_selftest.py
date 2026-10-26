#!/usr/bin/env python3
"""Preuves négatives du garde-fou : chaque contrôle doit rougir sur une entrée dégradée.

Une garde qui n'a jamais dit non n'est pas une garde. Ce module construit des
entrées **dégradées** connues (fonction de 57 lignes, table de cadence à 3 ips,
rapport JUnit avec un échec) et vérifie que le contrôle correspondant sort en KO ;
il vérifie symétriquement que l'entrée saine sort en OK — sinon la garde refuserait
du bon code.

Les cas passent par les contrôles réels, pas par des copies de leur logique : les
rapports JUnit sont écrits dans un `mkdtemp` que l'on relit par `guard_lib`. Aucun
fichier de l'arbre n'est touché, aucune compilation n'est lancée.
"""
import os
import shutil
import tempfile

import guard_lib as gl

# Table du banc de cadence, au format exact de `LiveCadenceSimTest.printTable`.
CADENCE_SAINE = """CADENCE — horloge virtuelle, caméra 30 fps, une frame en vol
latence | avant (350/500/900 ms, sans réserve) | après (latence/2, réserve 1)
41 ms | avant 2.89 ips / 180 ms | après 20.33 ips / 70 ms | 0 frames écrasées en réserve
422 ms | avant 1.02 ips / 980 ms | après 4.17 ips / 240 ms | 0 frames écrasées en réserve
"""
CADENCE_DEGRADEE = """41 ms | avant 2.89 ips / 180 ms | après 3.00 ips / 600 ms | 0 frames écrasées en réserve
422 ms | avant 1.02 ips / 980 ms | après 4.17 ips / 240 ms | 0 frames écrasées en réserve
"""
# Banc backend déclaré dont la commande échoue : il doit rendre le contrôle KO.
BANC_CASSE = {"nom": "banc-cassé",
              "cmd": ["python3", "-c", "print('Ran 1 test'); raise SystemExit(1)"],
              "tests_min": 1}
REF_BANC_CASSE = {"tests-backend": {"bancs": [BANC_CASSE], "fichiers_py_min": 1}}
REF_SANS_BANC = {"tests-backend": {"bancs": [], "fichiers_py_min": 1}}
JUNIT_VIDE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="x" tests="3" failures="0" errors="0" skipped="0">
  <testcase name="a"/><testcase name="b"/><testcase name="c"/>
</testsuite>
"""
JUNIT_ECHEC = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="x" tests="3" failures="1" errors="0" skipped="0">
  <testcase name="a"/><testcase name="b"><failure>boum</failure></testcase>
  <testcase name="c"/>
</testsuite>
"""
RAPPORT_CADENCE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="LiveCadenceSimTest" tests="1" failures="0" errors="0" skipped="0">
  <testcase name="table"/>
  <system-out>latence_ms|ancien_ips|nouveau_ips|attente_supprimee_ms|percue_avant|percue_apres|gagne_ms
{table}</system-out>
</testsuite>
"""
PLANCHER_CORE = 3


def _arbre_junit(rapport):
    """Arbre jetable portant un seul rapport JUnit sous `core/`."""
    racine = tempfile.mkdtemp(prefix="guard-selftest-")
    dossier = os.path.join(racine, "core", "build", "test-results", "testDebugUnitTest")
    os.makedirs(dossier)
    with open(os.path.join(dossier, "TEST-x.xml"), "w", encoding="utf-8") as handle:
        handle.write(rapport)
    return racine


def _controle(rapport, fonction, reference=None):
    """Joue un contrôle de `guard_lib` sur un arbre jetable, puis jette l'arbre."""
    racine = _arbre_junit(rapport)
    try:
        return fonction(racine, reference or {})
    finally:
        shutil.rmtree(racine, ignore_errors=True)


def _cas_cadence(degrade=False):
    """Table de cadence saine ⇒ OK, table dégradée (3 ips à 41 ms) ⇒ KO."""
    table = CADENCE_DEGRADEE if degrade else CADENCE_SAINE
    control = _controle(RAPPORT_CADENCE.format(table=table), gl.run_cadence)
    attendu = gl.KO if degrade else gl.OK
    nom = "banc-cadence " + ("dégradé (3 ips à 41 ms)" if degrade else "conforme")
    return nom, attendu, control.status, control.status == attendu


def _cas_junit(echec=False):
    """Trois tests verts ⇒ OK, un échec ⇒ KO : lecture réelle du rapport XML."""
    reference = {"tests-jvm": {"plancher": {"core": PLANCHER_CORE}}}
    rapport = JUNIT_ECHEC if echec else JUNIT_VIDE
    control = _controle(rapport, gl.run_tests_jvm, reference)
    attendu = gl.KO if echec else gl.OK
    nom = "tests-jvm " + ("avec 1 échec" if echec else "3 verts")
    return nom, attendu, control.status, control.status == attendu


def _cas_limites(root):
    """Une fonction de 57 lignes doit être refusée par la garde de limites."""
    checker = gl.load_checker(root)
    corps = "\n".join(f"    x = {index}" for index in range(55))
    problems = checker.check_python("def trop_longue():\n" + corps + "\n    return x\n")
    obtenu = gl.KO if problems else gl.OK
    return "limites-500/50 (fonction 57 l.)", gl.KO, obtenu, obtenu == gl.KO


def _arbre_tools(root):
    """Arbre jetable portant une copie de `tools/` : les bancs backend en ont besoin."""
    racine = tempfile.mkdtemp(prefix="guard-selftest-")
    shutil.copytree(os.path.join(root, "tools"), os.path.join(racine, "tools"),
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    return racine


def _cas_backend(root, casse=False):
    """Banc déclaré qui échoue ⇒ KO, aucun banc déclaré ⇒ OK : bancs réels rejoués."""
    racine = _arbre_tools(root)
    try:
        reference = REF_BANC_CASSE if casse else REF_SANS_BANC
        control = gl.run_tests_backend(racine, reference)
    finally:
        shutil.rmtree(racine, ignore_errors=True)
    attendu = gl.KO if casse else gl.OK
    nom = "tests-backend " + ("banc cassé (rc=1)" if casse else "sans banc déclaré")
    return nom, attendu, control.status, control.status == attendu


def selftest(root):
    """Les sept preuves, dans l'ordre des contrôles : (nom, attendu, obtenu, conforme)."""
    return [_cas_limites(root),
            _cas_cadence(),
            _cas_cadence(degrade=True),
            _cas_junit(),
            _cas_junit(echec=True),
            _cas_backend(root),
            _cas_backend(root, casse=True)]