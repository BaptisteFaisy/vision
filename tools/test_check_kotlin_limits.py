#!/usr/bin/env python3
"""Tests de la garde `tools/check-kotlin-limits.py`.

Lancer : python3 -m unittest tools/test_check_kotlin_limits.py -v
Aucune dépendance externe : uniquement `unittest` et la bibliothèque standard.

Chaque cas écrit une arborescence jetable, lance la garde en sous-processus
(donc sur son vrai code de retour) et vérifie la sortie.
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-kotlin-limits.py")


def load_guard():
    """Charge le script (nom de fichier à tirets) comme module Python."""
    spec = importlib.util.spec_from_file_location("check_kotlin_limits", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GUARD = load_guard()


def kotlin_fun(name="f", body=40, indent=""):
    """Source Kotlin d'une fonction de `body + 2` lignes."""
    lines = [f"{indent}fun {name}() {{"]
    lines += [f"{indent}    val v{i} = {i}" for i in range(body)]
    lines.append(f"{indent}}}")
    return "\n".join(lines) + "\n"


def python_fun(name="f", body=40):
    """Source Python d'une fonction de `body + 2` lignes."""
    lines = [f"def {name}():"]
    lines += [f"    v{i} = {i}" for i in range(body)]
    lines.append("    return v0")
    return "\n".join(lines) + "\n"


def pad(lines, width=20):
    """Complète une source avec des lignes vides pour atteindre `width` lignes."""
    text = "\n".join(lines)
    missing = width - len(lines)
    return text + ("\n" * max(missing, 0))


class GuardCase(unittest.TestCase):
    """Base : arborescence jetable + exécution de la garde en sous-processus."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, relpath, content):
        path = os.path.join(self.root, relpath)
        os.makedirs(os.path.dirname(path) or self.root, exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        return path

    def run_guard(self, *extra):
        proc = subprocess.run([sys.executable, SCRIPT, self.root, *extra],
                              capture_output=True, text=True, check=False)
        return proc.returncode, proc.stdout + proc.stderr

    def assert_refused(self, message):
        code, out = self.run_guard()
        self.assertNotEqual(code, 0, out)
        self.assertIn(message, out)
        return out

    def assert_accepted(self):
        code, out = self.run_guard()
        self.assertEqual(code, 0, out)
        self.assertIn("OK", out)
        return out


class FileSizeTests(GuardCase):
    def test_fichier_500_lignes_refuse(self):
        self.write("Gros.kt", "// bord\n" * 500)
        out = self.assert_refused("fichier 500 lignes (max 500)")
        self.assertIn("Gros.kt:1 —", out)

    def test_fichier_499_lignes_accepte(self):
        self.write("Juste.kt", "// bord\n" * 499)
        self.assert_accepted()

    def test_fichier_kts_verifie(self):
        self.write("build.gradle.kts", "// bord\n" * 512)
        self.assert_refused("build.gradle.kts:1 — fichier 512 lignes (max 500)")

    def test_fichier_python_trop_long_refuse(self):
        self.write("gros.py", "x = 1\n" * 500)
        self.assert_refused("gros.py:1 — fichier 500 lignes (max 500)")


class FunctionSizeTests(GuardCase):
    def test_fonction_exactement_50_refusee(self):
        self.write("F.kt", kotlin_fun("f", 48))
        out = self.assert_refused("F.kt:1 — fonction 50 lignes (max 50)")
        self.assertIn("f", out)

    def test_fonction_49_acceptee(self):
        self.write("F.kt", kotlin_fun("f", 47))
        self.assert_accepted()

    def test_fonction_63_signalee_a_sa_ligne_de_declaration(self):
        source = "// entete\n// entete\n" + kotlin_fun("longue", 61)
        self.write("F.kt", source)
        self.assert_refused("F.kt:3 — fonction 63 lignes (max 50)")

    def test_backticks_detectes(self):
        self.write("T.kt", kotlin_fun("`un nom avec espaces`", 60))
        self.assert_refused("fonction 62 lignes (max 50) — `un nom avec espaces`")

    def test_backticks_courts_non_signales(self):
        self.write("T.kt", kotlin_fun("`cas court`", 3))
        self.assert_accepted()

    def test_backtick_avec_apostrophe_detecte(self):
        name = "`le codec du fichier prive fait l'aller-retour de l'intro`"
        self.write("A.kt", kotlin_fun(name, 52))
        self.assert_refused("fonction 54 lignes (max 50)")

    def test_backtick_ne_deforme_pas_la_suite(self):
        name = "`marqueur aller-retour codec (format fichier)`"
        source = kotlin_fun(name, 2) + kotlin_fun("suivante", 52)
        self.write("P.kt", source)
        out = self.assert_refused("fonction 54 lignes (max 50) — suivante")
        self.assertNotIn("fonction 4 lignes", out)

    def test_suspend_et_generiques_detectes(self):
        source = "suspend fun <T : Any> longue(x: T): T {\n"
        source += "".join(f"    val v{i} = {i}\n" for i in range(60))
        source += "    return x\n}\n"
        self.write("S.kt", source)
        self.assert_refused("fonction 63 lignes (max 50) — longue")

    def test_corps_expression_detecte(self):
        source = "fun longue() = listOf(\n"
        source += "".join(f"    {i},\n" for i in range(60))
        source += ")\n"
        self.write("E.kt", source)
        self.assert_refused("E.kt:1 — fonction 62 lignes (max 50) — longue")

    def test_corps_expression_court_accepte(self):
        self.write("E.kt", "fun courte() = 42\n")
        self.assert_accepted()

    def test_classe_imbriquee_inspectee(self):
        source = "class A {\n    class B {\n" + kotlin_fun("profonde", 55, "        ")
        source += "    }\n}\n"
        self.write("C.kt", source)
        self.assert_refused("fonction 57 lignes (max 50) — profonde")

    def test_init_et_accesseurs_detectes(self):
        self.write("I.kt", "class A {\n    init {\n"
                           + "".join(f"        val v{i} = {i}\n" for i in range(55))
                           + "    }\n}\n")
        self.assert_refused("fonction 57 lignes (max 50) — init")
        self.write("I.kt", "class A {\n    val p: Int\n        get() {\n"
                           + "".join(f"            val v{i} = {i}\n" for i in range(55))
                           + "            return 0\n        }\n}\n")
        self.assert_refused("fonction 58 lignes (max 50) — get")


class BlindSpotTests(GuardCase):
    def test_chaine_multiligne_ignoree(self):
        source = kotlin_fun("courte", 2)
        source += 'val brut = """\n' + kotlin_fun("fausse", 60) + '"""\n'
        self.write("S.kt", source)
        self.assert_accepted()

    def test_chaine_simple_ignoree(self):
        source = kotlin_fun("courte", 2)
        source += 'val s = "fun fausse() { val a = 1 }"\n'
        self.write("S.kt", source)
        self.assert_accepted()

    def test_commentaire_bloc_ignore(self):
        self.write("K.kt", "/*\n" + kotlin_fun("fausse", 60) + "*/\n" + kotlin_fun("ok", 2))
        self.assert_accepted()

    def test_commentaire_ligne_ignore(self):
        self.write("K.kt", "// fun fausse() {\n" + kotlin_fun("ok", 2))
        self.assert_accepted()

    def test_fun_interface_nest_pas_une_fonction(self):
        self.write("F.kt", "fun interface Rappel { fun appeler() }\n")
        self.assert_accepted()

    def test_fonction_anonyme_ignoree(self):
        self.write("A.kt", "val f = fun(x: Int): Int {\n    var y = x\n"
                           + "".join(f"    y += {i}\n" for i in range(60))
                           + "    return y\n}\n")
        self.assert_accepted()


class ApiTests(GuardCase):
    def test_arbre_vide_accepte(self):
        self.assert_accepted()

    def test_racine_absente_code_2(self):
        proc = subprocess.run([sys.executable, SCRIPT, os.path.join(self.root, "nope")],
                              capture_output=True, text=True, check=False)
        self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)

    def test_strict_refuse_une_violation_connue(self):
        self.write("F.kt", "// bord\n" * 500)
        code, out = self.run_guard("--strict")
        self.assertNotEqual(code, 0, out)

    def test_parseur_compte_les_declarations(self):
        source = (kotlin_fun("a", 2) + kotlin_fun("`b b`", 2)
                  + "class C {\n    init {\n    }\n    val p: Int\n        get() = 0\n}\n")
        names = [name for name, _, _ in GUARD.kotlin_declarations(source)[1]]
        self.assertEqual(names, ["a", "`b b`", "init", "get"])

    def test_neutralisation_preserve_les_lignes(self):
        source = 'val a = 1 // fin\n/* x\ny */\nval b = """\nz\n"""\n'
        stripped = GUARD.strip_strings_comments(source)
        self.assertEqual(stripped.count("\n"), source.count("\n"))
        self.assertNotIn("fin", stripped)
        self.assertNotIn("z", stripped)

    def test_seuils_exposes(self):
        self.assertEqual(GUARD.MAX_FILE_LINES, 500)
        self.assertEqual(GUARD.MAX_FUN_LINES, 50)


if __name__ == "__main__":
    unittest.main(verbosity=2)