#!/usr/bin/env python3
"""Contrôles du garde-fou iSee : limites 500/50, tests JVM, tests backend, cadence.

Chaque contrôle rend un `Control` (nom, statut, détail). Aucun ne compile : la
compilation appartient à la CI, qui laisse des rapports JUnit XML que ce module
relit. Un contrôle dont la preuve manque est `ABSENT`, jamais vert par défaut —
un `skipped` ne vaut pas un succès.

Statuts : OK (vert), WARN (échec d'environnement, pas une régression),
ABSENT (preuve absente de l'arbre, exigeable par `--require-…`), KO (régression).
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
from datetime import datetime
from xml.etree import ElementTree

OK, WARN, ABSENT, KO = "OK", "WARN", "ABSENT", "KO"
REFERENCE = "tools/guard-reference.json"
IPS_TOLERANCE = 0.05            # dérive maximale admise sur un débit (ips)
MS_TOLERANCE = 0.2              # dérive maximale admise sur une latence (ms)
# Une ligne du tableau de cadence, telle que `LiveCadenceSimTest.printTable` l'écrit :
#   « 41 ms | avant 2.89 ips / 180 ms | après 20.33 ips / 70 ms | 0 frames écrasées »
# Le séparateur décimal est celui de la locale de la JVM (`.` en CI, `,` ailleurs).
CADENCE_LINE = re.compile(
    r"(?P<latence_ms>[\d.,]+)\s*ms\s*\|\s*avant\s+(?P<ancien_ips>[\d.,]+)\s*ips"
    r"\s*/\s*(?P<percue_avant>[\d.,]+)\s*ms\s*\|\s*après\s+(?P<nouveau_ips>[\d.,]+)\s*ips"
    r"\s*/\s*(?P<percue_apres>[\d.,]+)\s*ms\s*\|\s*(?P<ecrasees>\d+)")
# Paliers absolus du banc de cadence : sous ces valeurs la cadence perçue est
# perdue, quel que soit l'écart avec la référence figée.
CADENCE_PALIERS = ({"latence_ms": 41.0, "nouveau_ips_min": 10.0},
                   {"latence_ms": 422.0, "nouveau_ips_min": 2.3})
# Bancs backend : stdlib seule, zéro GPU, zéro réseau, zéro token.
BANCS = ({"nom": "unittest-limites",
          "cmd": ["python3", "-m", "unittest", "tools/test_check_kotlin_limits.py"],
          "tests_min": 28},
         {"nom": "ci-budget",
          "cmd": ["python3", ".github/ci-budget/check_timeouts.py"],
          "tests_min": 0})
TEST_ANNOTATION = re.compile(r"@Test\b")
RAN_RE = re.compile(r"Ran (\d+) tests?")


class Control:
    """Un contrôle et son verdict binaire.

    `failed` est la seule chose que lit le verdict final : WARN et ABSENT ne
    font pas échouer la passe, KO oui.
    """

    def __init__(self, name, status, detail):
        self.name, self.status, self.detail = name, status, detail

    @property
    def failed(self):
        return self.status == KO


# --- outils communs -------------------------------------------------------

def load_py_module(name, path):
    """Charge un outil `tools/*.py` à tirets (importlib : un seul chargeur)."""
    if not os.path.isfile(path):
        raise FileNotFoundError(f"outil introuvable : {path}")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_checker(root):
    """Charge `tools/check-kotlin-limits.py` au lieu d'en recopier la logique."""
    return load_py_module("_guard_limits",
                         os.path.join(root, "tools", "check-kotlin-limits.py"))


def count_sources(root, checker):
    """(Kotlin, Python) — même parcours que la garde de limites, sans doublon."""
    kotlin = python = 0
    for path in checker._iter_sources(root):        # noqa: SLF001 — parcours partagé
        if path.endswith(checker.KOTLIN_SUFFIXES):
            kotlin += 1
        elif path.endswith(checker.PY_SUFFIXES):
            python += 1
    return kotlin, python


def _source_files(root, checker, suffixes):
    """Les fichiers de l'arbre portant une des extensions demandées."""
    return [p for p in checker._iter_sources(root) if p.endswith(suffixes)]


def junit_reports(root):
    """Les rapports JUnit des modules (`<module>/build/test-results/testDebugUnitTest`)."""
    reports = []
    for entry in sorted(os.scandir(root), key=lambda e: e.name):
        if not entry.is_dir() or entry.name.startswith("."):
            continue
        dossier = os.path.join(entry.path, "build", "test-results", "testDebugUnitTest")
        if os.path.isdir(dossier):
            reports += [os.path.join(dossier, nom) for nom in sorted(os.listdir(dossier))
                        if nom.endswith(".xml")]
    return reports


def _junit_counts(path):
    """(tests, échecs, ignorés, system-out) d'un rapport JUnit."""
    racine = ElementTree.parse(path).getroot()
    suites = [racine] if racine.tag == "testsuite" else list(racine.iter("testsuite"))
    tests = echecs = ignores = 0
    sorties = []
    for suite in suites:
        tests += int(suite.get("tests") or 0)
        echecs += int(suite.get("failures") or 0) + int(suite.get("errors") or 0)
        ignores += int(suite.get("skipped") or 0)
        sorties += [noeud.text or "" for noeud in suite.iter("system-out")]
    return tests, echecs, ignores, "\n".join(sorties)


def summarize_junit(root):
    """Agrège les XML : ({module: (tests, échecs, ignorés)}, texte des system-out)."""
    par_module, textes = {}, []
    for path in junit_reports(root):
        module = os.path.relpath(path, root).split(os.sep)[0]
        tests, echecs, ignores, sortie = _junit_counts(path)
        avant = par_module.get(module, (0, 0, 0))
        par_module[module] = (avant[0] + tests, avant[1] + echecs, avant[2] + ignores)
        if sortie:
            textes.append(sortie)
    return par_module, "\n".join(textes)


# --- contrôle 1 : limites 500/50 ------------------------------------------

def _violation_keys(findings):
    """Clés stables « chemin:genre:nom », comparables entre deux passes."""
    keys = set()
    for relpath, problems in findings:
        for problem in problems:
            keys.add(f"{relpath}:{problem.kind}:{problem.name}")
    return keys


def _tolerated(reference):
    """Dépassements préexistants figés : tolérés tant qu'ils ne bougent pas."""
    return set(reference.get("limites-500/50", {}).get("toleres", []))


def run_limits(root, reference):
    """Fichier < 500 lignes, fonction < 50 : nouveau dépassement = KO."""
    try:
        checker = load_checker(root)
    except FileNotFoundError:
        return Control("limites-500/50", ABSENT,
                       "garde de limites absente de cet arbre (tools/check-kotlin-limits.py)")
    _, findings = checker.scan(root)
    kotlin, python = count_sources(root, checker)
    vus = _violation_keys(findings)
    toleres = _tolerated(reference)
    nouveaux = sorted(vus - toleres)
    if nouveaux:
        detail = f"{len(nouveaux)} dépassement(s) nouveau(x) : " + ", ".join(nouveaux[:4])
        return Control("limites-500/50", KO, detail)
    detail = (f"{kotlin + python} fichiers ({kotlin} Kotlin, {python} Python), "
              f"{len(toleres)} préexistant(s) toléré(s)")
    disparus = sorted(toleres - vus)
    if disparus:
        return Control("limites-500/50", WARN,
                       detail + f" — {len(disparus)} toléré(s) corrigé(s), refiger (--freeze)")
    return Control("limites-500/50", OK, detail)


# --- contrôle 2 : tests JVM -----------------------------------------------

def run_tests_jvm(root, reference):
    """Tests JVM relus dans les XML : échec, ou moins de tests que la référence."""
    par_module, _ = summarize_junit(root)
    if not par_module:
        return Control("tests-jvm", ABSENT,
                       "aucun XML JUnit : Gradle n'a pas tourné ici (la CI est le juge)")
    tests = sum(valeur[0] for valeur in par_module.values())
    echecs = sum(valeur[1] for valeur in par_module.values())
    ignores = sum(valeur[2] for valeur in par_module.values())
    detail = (f"{tests} tests, {echecs} échec(s), {ignores} ignoré(s) — "
              + ", ".join(f"{module}={valeur[0]}" for module, valeur in sorted(par_module.items())))
    if echecs:
        return Control("tests-jvm", KO, f"{echecs} échec(s) — {detail}")
    plancher = reference.get("tests-jvm", {}).get("plancher", {})
    perdus = [module for module, mini in plancher.items()
              if par_module.get(module, (0,))[0] < mini]
    if perdus:
        return Control("tests-jvm", KO,
                       "tests perdus sous la référence : " + ", ".join(sorted(perdus)))
    return Control("tests-jvm", OK, detail)


# --- contrôle 3 : tests backend -------------------------------------------

def py_compile_tree(root, checker):
    """Compile chaque `.py` en mémoire (aucun `.pyc`) ; rend (nombre, erreurs)."""
    erreurs, fichiers = [], _source_files(root, checker, checker.PY_SUFFIXES)
    for path in fichiers:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                compile(handle.read(), path, "exec")
        except SyntaxError as exc:
            erreurs.append(f"{os.path.relpath(path, root)}:{exc.lineno} {exc.msg}")
    return len(fichiers), erreurs


def run_banc(root, banc):
    """Exécute un banc déclaré ; rend (tests exécutés, environnemental, échec)."""
    commande = [sys.executable] + banc["cmd"][1:]
    done = subprocess.run(commande, cwd=root, capture_output=True, text=True)
    sortie = (done.stdout or "") + (done.stderr or "")
    match = RAN_RE.search(sortie)
    tests = int(match.group(1)) if match else 0
    if "ModuleNotFoundError" in sortie:
        return tests, f"{banc['nom']} : environnement Python incomplet (ModuleNotFoundError)"
    if done.returncode != 0:
        ligne = next((ligne.strip() for ligne in sortie.splitlines() if ligne.strip()), "")
        return tests, f"{banc['nom']} rc={done.returncode} — {ligne}"
    return tests, ""


def run_tests_backend(root, reference):
    """Syntaxe de tous les `.py`, puis les bancs déclarés dans la référence."""
    checker = load_checker(root)
    nombre, erreurs = py_compile_tree(root, checker)
    if erreurs:
        return Control("tests-backend", KO,
                       f"{len(erreurs)} erreur(s) de syntaxe : " + " ; ".join(erreurs[:3]))
    section = reference.get("tests-backend", {})
    echecs, avertissements, tests = [], [], 0
    bancs = section.get("bancs", [])
    for banc in bancs:
        executes, probleme = run_banc(root, banc)
        tests += executes
        if not probleme:
            if executes < banc.get("tests_min", 0):
                echecs.append(f"{banc['nom']} : {executes} tests < {banc['tests_min']} attendus")
        elif "environnement" in probleme:
            avertissements.append(probleme)
        else:
            echecs.append(probleme)
    if echecs:
        return Control("tests-backend", KO, " ; ".join(echecs[:3]))
    detail = f"{nombre} fichier(s) .py, {tests} test(s), {len(bancs)} banc(s)"
    if avertissements:
        return Control("tests-backend", WARN,
                       detail + " — " + " ; ".join(avertissements[:2]))
    mini = section.get("fichiers_py_min")
    if mini and nombre < mini:
        return Control("tests-backend", KO,
                       f"{nombre} fichier(s) .py < {mini} figés (fichier perdu, ou refiger)")
    return Control("tests-backend", OK, detail)


# --- contrôle 4 : banc de cadence -----------------------------------------

def parse_cadence(texte):
    """Relit le tableau de `LiveCadenceSimTest` dans le `system-out` JUnit (1 ligne = 1 mesure)."""
    lignes = []
    for ligne in texte.splitlines():
        trouve = CADENCE_LINE.search(ligne)
        if trouve:
            lignes.append({cle: _nombre(valeur) for cle, valeur in trouve.groupdict().items()})
    return lignes


def _nombre(texte):
    """Nombre du tableau, quel que soit le séparateur décimal de la JVM."""
    return float(texte.replace(",", "."))


def _paliers(reference):
    """Seuils absolus : la référence peut les préciser, sinon ceux du contrat."""
    return reference.get("banc-cadence", {}).get("paliers") or list(CADENCE_PALIERS)


def _mesures(reference):
    """Mesures figées, indexées par latence (ms)."""
    return {float(m["latence_ms"]): m
            for m in reference.get("banc-cadence", {}).get("mesures", [])}


def cadence_violations(lignes, reference):
    """Paliers absolus, non-régression locale et dérive contre la référence figée."""
    paliers = _paliers(reference)
    mesures = _mesures(reference)
    problemes = []
    for ligne in lignes:
        latence = ligne["latence_ms"]
        for palier in paliers:
            if abs(latence - float(palier["latence_ms"])) <= MS_TOLERANCE \
                    and ligne["nouveau_ips"] < float(palier["nouveau_ips_min"]):
                problemes.append(f"{latence:g} ms : {ligne['nouveau_ips']:g} ips "
                                 f"< palier {float(palier['nouveau_ips_min']):g}")
        problemes += _derive(ligne, mesures.get(latence))
    return problemes


def _derive(ligne, mesure):
    """Dérives d'une mesure : cadence perdue, latence perçue qui monte."""
    latence = ligne["latence_ms"]
    problemes = []
    if ligne["nouveau_ips"] < ligne["ancien_ips"] - IPS_TOLERANCE:
        problemes.append(f"{latence:g} ms : cadence {ligne['nouveau_ips']:g} ips "
                         f"< avant {ligne['ancien_ips']:g}")
    if ligne["percue_apres"] > ligne["percue_avant"] + MS_TOLERANCE:
        problemes.append(f"{latence:g} ms : latence perçue {ligne['percue_apres']:g} ms "
                         f"> avant {ligne['percue_avant']:g}")
    if mesure:
        if ligne["nouveau_ips"] < float(mesure["nouveau_ips"]) - IPS_TOLERANCE:
            problemes.append(f"{latence:g} ms : {ligne['nouveau_ips']:g} ips, dérive "
                             f"> {IPS_TOLERANCE} sous {float(mesure['nouveau_ips']):g} figés")
        if ligne["percue_apres"] > float(mesure["percue_apres"]) + MS_TOLERANCE:
            problemes.append(f"{latence:g} ms : latence perçue {ligne['percue_apres']:g} ms, "
                             f"dérive > {MS_TOLERANCE} au-dessus de "
                             f"{float(mesure['percue_apres']):g} figés")
    return problemes


def run_cadence(root, reference, require=False):
    """Relit la table de cadence des XML JUnit et applique les seuils."""
    _, textes = summarize_junit(root)
    lignes = parse_cadence(textes)
    if not lignes:
        statut = KO if require else ABSENT
        return Control("banc-cadence", statut,
                       "aucun rapport JUnit ici : le banc LiveCadenceSimTest existe dans "
                       "l'arbre, c'est la CI qui produit le rapport que ce contrôle relit")
    problemes = cadence_violations(lignes, reference)
    if problemes:
        return Control("banc-cadence", KO,
                       f"{len(problemes)} dérive(s) : " + " ; ".join(problemes[:3]))
    detail = ", ".join(f"{ligne['latence_ms']:g} ms → {ligne['nouveau_ips']:g} ips"
                       for ligne in lignes[:4])
    return Control("banc-cadence", OK, f"{len(lignes)} latence(s) mesurée(s) : {detail}")


# --- orchestration --------------------------------------------------------

def _escalate(control, exige):
    """Une preuve absente devient un KO quand la passe l'exige explicitement."""
    if exige and control.status == ABSENT:
        return Control(control.name, KO, control.detail + " — exigé par la passe")
    return control


def limites_text(root, reference):
    """Le contrôle de limites en une ligne « STATUT détail » (copies jetables)."""
    control = run_limits(root, reference)
    return f"{control.status} {control.detail}"


def run_all(root, reference, quick=False, require_jvm=False, require_cadence=False):
    """Les quatre contrôles, dans l'ordre du plan ; `--quick` ne garde que le gratuit."""
    if quick:
        sautes = [Control(nom, ABSENT, "sauté (--quick) : la passe complète le joue")
                  for nom in ("tests-jvm", "tests-backend", "banc-cadence")]
        return [run_limits(root, reference)] + sautes
    return [run_limits(root, reference),
            _escalate(run_tests_jvm(root, reference), require_jvm),
            run_tests_backend(root, reference),
            _escalate(run_cadence(root, reference), require_cadence)]


# --- référence figée ------------------------------------------------------

def reference_path(root):
    """Chemin de la référence figée, relative à l'arbre contrôlé."""
    return os.path.join(root, REFERENCE)


def load_reference(root):
    """Référence figée ; un fichier absent rend une référence vide, jamais inventée."""
    try:
        with open(reference_path(root), encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return {}


def save_reference(root, reference):
    """Écrit la référence — la seule sortie de `--freeze`."""
    with open(reference_path(root), "w", encoding="utf-8") as handle:
        json.dump(reference, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def annotation_counts(root):
    """Plancher de tests par module : annotations `@Test` de `<module>/src/test`."""
    comptes = {}
    for module in sorted(os.listdir(root)):
        source = os.path.join(root, module, "src", "test")
        if not os.path.isdir(source):
            continue
        total = 0
        for dirpath, _, filenames in os.walk(source):
            for nom in sorted(filenames):
                if nom.endswith(".kt"):
                    with open(os.path.join(dirpath, nom), encoding="utf-8",
                              errors="replace") as handle:
                        total += len(TEST_ANNOTATION.findall(handle.read()))
        if total:
            comptes[module] = total
    return comptes


def _commit(root):
    """SHA court de l'arbre figé, ou « inconnu » (jamais un faux SHA)."""
    try:
        done = subprocess.run(["git", "-C", root, "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True)
        return done.stdout.strip() or "inconnu"
    except OSError:
        return "inconnu"


def measure(root):
    """État de l'arbre tel que `--freeze` le figera : rien d'inventé, tout relevé."""
    checker = load_checker(root)
    kotlin, python = count_sources(root, checker)
    _, findings = checker.scan(root)
    par_module, textes = summarize_junit(root)
    plancher = annotation_counts(root)
    for module, valeurs in par_module.items():
        plancher[module] = max(plancher.get(module, 0), valeurs[0])
    return {
        "version": 1,
        "gele": {"le": datetime.now().astimezone().isoformat(timespec="seconds"),
                 "commit": _commit(root), "arbre": "BaptisteFaisy/vision"},
        "limites-500/50": {"kotlin": kotlin, "python": python,
                           "toleres": sorted(_violation_keys(findings))},
        "tests-jvm": {
            "plancher": {module: nombre for module, nombre in sorted(plancher.items()) if nombre},
            "execute": {module: valeurs[0] for module, valeurs in sorted(par_module.items())},
            "note": "plancher = annotations @Test de <module>/src/test, comptées sans "
                    "compiler ; `execute` se remplit au premier passage avec XML JUnit.",
        },
        "tests-backend": {"bancs": [dict(banc) for banc in BANCS],
                          "fichiers_py_min": python},
        "banc-cadence": {"paliers": [dict(palier) for palier in CADENCE_PALIERS],
                         "tolerance": {"ips": IPS_TOLERANCE, "ms": MS_TOLERANCE},
                         "mesures": parse_cadence(textes),
                         "note": "mesures = tableau de LiveCadenceSimTest relu dans le "
                                 "system-out JUnit ; vide tant qu'aucun rapport n'a été "
                                 "produit ici (la CI le produit)."},
    }


