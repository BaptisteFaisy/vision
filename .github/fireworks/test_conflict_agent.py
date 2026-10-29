#!/usr/bin/env python3
"""Tests ciblés de conflict_agent.py — bibliothèque standard uniquement.

Lance : python3 -m unittest test_conflict_agent -v
(depuis .github/fireworks/).
"""
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from unittest import mock

import conflict_agent


class EnvOrTest(unittest.TestCase):
    """Résolution des réglages : une var GitHub absente arrive comme ""."""

    def test_var_absente_rend_defaut(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("FIREWORKS_MODEL", None)
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "DEFAUT")

    def test_var_vide_rend_defaut(self):
        # Régression 28/10 : modèle "" → 400 Fireworks sur un vrai conflit.
        with mock.patch.dict(os.environ, {"FIREWORKS_MODEL": ""}):
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "DEFAUT")

    def test_var_renseignee_gagne(self):
        with mock.patch.dict(os.environ, {"FIREWORKS_MODEL": "M"}):
            self.assertEqual(
                conflict_agent.env_or("FIREWORKS_MODEL", "DEFAUT"), "M")


class PushUrlTest(unittest.TestCase):
    """URL push : PAT du CI, repli "origin" en local."""

    def test_avec_token_rend_url_authentifiee(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN": "T"}):
            url = conflict_agent.push_url("O/R")
            self.assertEqual(
                url, "https://x-access-token:T@github.com/O/R.git")

    def test_sans_token_rend_origin(self):
        with mock.patch.dict(os.environ):
            os.environ.pop("GH_TOKEN", None)
            self.assertEqual(conflict_agent.push_url("O/R"), "origin")


class SplitHunksTest(unittest.TestCase):
    """Découpage des marqueurs : le propre recopié, les bords nommés."""

    def test_sans_marqueur_tout_propre(self):
        segs = conflict_agent.split_hunks("a\nb\n")
        self.assertEqual(segs, [("clean", "a\nb\n")])

    def test_diff_simple(self):
        text = ("A\n<<<<<<< HEAD\nm1\n=======\nb1\n>>>>>>> br\nZ\n")
        segs = conflict_agent.split_hunks(text)
        self.assertEqual(len(segs), 3)
        self.assertEqual(segs[0], ("clean", "A"))
        kind, hunk = segs[1]
        self.assertEqual(kind, "hunk")
        self.assertEqual(hunk["head"], ["m1"])
        self.assertIsNone(hunk["base"])
        self.assertEqual(hunk["saved"], ["b1"])
        self.assertEqual(segs[2], ("clean", "Z\n"))

    def test_diff3_avec_base(self):
        text = ("<<<<<<< HEAD\nm\n||||||| base\nb\n=======\ns\n>>>>>>> br\n")
        segs = conflict_agent.split_hunks(text)
        self.assertEqual(len(segs), 2)
        kind, hunk = segs[0]
        self.assertEqual(hunk["head"], ["m"])
        self.assertEqual(hunk["base"], ["b"])
        self.assertEqual(hunk["saved"], ["s"])

    def test_rebase_reel_second_bloc_est_branche(self):
        # Non-régression 28/10 : en rebase, "saved" = branche rejouée.
        repo = tempfile.mkdtemp(prefix="fw-sides-")
        self.addCleanup(shutil.rmtree, repo, True)
        old = os.getcwd()
        self.addCleanup(os.chdir, old)
        os.chdir(repo)
        run = lambda *a: subprocess.run(  # noqa: E731
            ["git", *a], capture_output=True, text=True)
        run("init", "-q")
        run("config", "user.email", "t@t")
        run("config", "user.name", "t")
        with open("f.txt", "w") as fh:
            fh.write("base\n")
        run("add", ".")
        run("commit", "-qm", "base")
        main = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        with open("f.txt", "w") as fh:
            fh.write("BRANCHE\n")
        run("commit", "-qam", "br")
        run("checkout", "-q", main)
        with open("f.txt", "w") as fh:
            fh.write("MASTER\n")
        run("commit", "-qam", "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", main],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)  # conflit attendu
        with open("f.txt") as fh:
            segs = conflict_agent.split_hunks(fh.read())
        hunks = [s for k, s in segs if k == "hunk"]
        self.assertEqual(len(hunks), 1)
        self.assertEqual(hunks[0]["saved"], ["BRANCHE"])
        self.assertEqual(hunks[0]["head"], ["MASTER"])


def _scratch_repo(test):
    """Dépôt git réel en temp, cwd dedans, nettoyé après. Rend run()."""
    repo = tempfile.mkdtemp(prefix="fw-loop-")
    test.addCleanup(shutil.rmtree, repo, True)
    old = os.getcwd()
    test.addCleanup(os.chdir, old)
    os.chdir(repo)
    def run(*args):
        return subprocess.run(["git", *args], capture_output=True, text=True)
    run("init", "-q")
    run("config", "user.email", "t@t")
    run("config", "user.name", "t")
    run("config", "commit.gpgsign", "false")
    return run


def _commit(run, files, message):
    for path, content in files.items():
        with open(path, "w") as fh:
            fh.write(content)
    run("add", ".")
    assert run("commit", "-qm", message).returncode == 0


class RebaseLoopTest(unittest.TestCase):
    """Boucle multi-passes : chaque arrêt est résolu, puis poussé."""

    def test_rebase_in_progress_suit_le_rebase(self):
        run = _scratch_repo(self)
        _commit(run, {"f.txt": "base\n"}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        self.assertFalse(conflict_agent.rebase_in_progress())
        run("checkout", "-qb", "br")
        _commit(run, {"f.txt": "BR\n"}, "br")
        run("checkout", "-q", base_branch)
        _commit(run, {"f.txt": "M\n"}, "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        self.assertTrue(conflict_agent.rebase_in_progress())
        run("rebase", "--abort")
        self.assertFalse(conflict_agent.rebase_in_progress())

    def test_boucle_deux_arrets(self):
        # Non-régression 28/10 : #125 a deux commits en conflit, l'ancien
        # code échouait au premier --continue ("Resolve all conflicts").
        run = _scratch_repo(self)
        _commit(run, {"f.txt": "base\n", "g.txt": "base\n"}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        _commit(run, {"f.txt": "BR-F\n"}, "br1")
        _commit(run, {"g.txt": "BR-G\n"}, "br2")
        run("checkout", "-q", base_branch)
        _commit(run, {"f.txt": "M-F\n"}, "m1")
        _commit(run, {"g.txt": "M-G\n"}, "m2")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        with mock.patch.object(conflict_agent, "fw_complete",
                               return_value="```\nRESOLU\n```"):
            resolved = conflict_agent.finish_loop(
                "http://x", "K", "M", time.time() + 60)
        self.assertEqual(sorted(resolved), ["f.txt", "g.txt"])
        self.assertFalse(conflict_agent.rebase_in_progress())
        log = run("log", "--format=%s").stdout
        self.assertIn("br1", log)
        self.assertIn("br2", log)

    def test_commit_vide_skip(self):
        # La résolution reprend le bord master : le commit rejoué est vide,
        # --continue échoue ("empty") et la boucle le saute.
        run = _scratch_repo(self)
        _commit(run, {"f.txt": "X\n"}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        _commit(run, {"f.txt": "Y\n"}, "br")
        run("checkout", "-q", base_branch)
        _commit(run, {"f.txt": "Z\n"}, "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        with mock.patch.object(conflict_agent, "fw_complete",
                               return_value="```\nZ\n```"):
            conflict_agent.finish_loop("http://x", "K", "M",
                                       time.time() + 60)
        self.assertFalse(conflict_agent.rebase_in_progress())
        log = run("log", "--format=%s").stdout
        self.assertNotIn("br", log.splitlines())
        with open("f.txt") as fh:
            self.assertEqual(fh.read().strip(), "Z")

    def test_borne_max_rounds(self):
        # Un conflit qui ne part pas (résolution sans effet) abandonne
        # après MAX_ROUNDS au lieu de boucler.
        run = _scratch_repo(self)
        _commit(run, {"f.txt": "base\n"}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        _commit(run, {"f.txt": "BR\n"}, "br")
        run("checkout", "-q", base_branch)
        _commit(run, {"f.txt": "M\n"}, "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        with mock.patch.object(conflict_agent, "resolve_one",
                               return_value=None):
            with self.assertRaises(conflict_agent.AgentError) as ctx:
                conflict_agent.finish_loop("http://x", "K", "M",
                                           time.time() + 60)
        self.assertIn("arrêts", str(ctx.exception))


class FinishPushTest(unittest.TestCase):
    """Fin de rebase : un seul push avec bail, puis compte rendu."""

    def test_un_seul_push(self):
        # Non-régression 29/10 : le push était exécuté deux fois avec le même
        # bail — le second échouait toujours après un premier succès, et la PR
        # repartait en « conflit » sans commentaire ni label.
        appels = []

        def faux_run(argv, **kw):
            appels.append(argv)
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(conflict_agent.subprocess, "run",
                               side_effect=faux_run):
            rc = conflict_agent.finish("O/R", "1", "br", "sha", ["f.txt"])
        self.assertEqual(rc, 0)
        pushes = [a for a in appels if a[:2] == ["git", "push"]]
        self.assertEqual(len(pushes), 1)
        self.assertIn("--force-with-lease=br:sha", pushes[0])

    def test_push_refuse_sans_compte_rendu_succes(self):
        def faux_run(argv, **kw):
            if argv[:2] == ["git", "push"]:
                return mock.Mock(returncode=1, stdout="", stderr="bail")
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(conflict_agent.subprocess, "run",
                               side_effect=faux_run):
            rc = conflict_agent.finish("O/R", "1", "br", "sha", ["f.txt"])
        self.assertEqual(rc, 1)


class DryRunStampTest(unittest.TestCase):
    """Le dry-run n'écrit jamais d'empreinte (ni cooldown ni plafond)."""

    def test_dry_run_sans_stamp(self):
        # Non-régression 29/10 : une simulation touchait l'empreinte avant la
        # branche dry-run, et bloquait un vrai run pendant 20 min.
        run = _scratch_repo(self)
        _commit(run, {"f.txt": "base\n"}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        _commit(run, {"f.txt": "BR\n"}, "br")
        run("checkout", "-q", base_branch)
        _commit(run, {"f.txt": "M\n"}, "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        home = tempfile.mkdtemp(prefix="fw-home-")
        self.addCleanup(shutil.rmtree, home, True)
        args = mock.Mock(repo="O/R", pr="1", branch="", sha="abc",
                         dry_run=True)
        with mock.patch.dict(os.environ, {"HOME": home}), \
                mock.patch.object(conflict_agent, "pr_labels", return_value=[]):
            rc = conflict_agent.cmd_resolve(args)
        self.assertEqual(rc, 0)
        cache = os.path.join(home, ".cache", "fireworks-agent")
        stamps = os.listdir(cache) if os.path.isdir(cache) else []
        self.assertEqual(stamps, [])


class UnionAjoutsTest(unittest.TestCase):
    """Journaux : deux entrées écrites en tête se réunissent sans modèle."""

    def test_deux_ajouts_en_tete(self):
        texte = ("# Journal\n\n"
                 "<<<<<<< HEAD\n## Entrée master\n- fait A\n"
                 "||||||| base\n"
                 "=======\n## Entrée branche\n- fait B\n"
                 ">>>>>>> 3e0e33b\n\n## Plus ancien\n")
        self.assertEqual(conflict_agent.union_sans_perte(texte),
                         "# Journal\n\n## Entrée master\n- fait A\n"
                         "## Entrée branche\n- fait B\n\n## Plus ancien\n")

    def test_ligne_commune_modifiee_refusee(self):
        # Une ligne de base remplacée des deux côtés : vrai désaccord.
        texte = ("<<<<<<< HEAD\nversion master\n"
                 "||||||| base\nversion commune\n"
                 "=======\nversion branche\n>>>>>>> x\n")
        self.assertIsNone(conflict_agent.union_sans_perte(texte))

    def test_marqueur_incomplet_refuse(self):
        self.assertIsNone(conflict_agent.union_sans_perte("<<<<<<< HEAD\na\n"))

    def test_chemin_de_journal(self):
        self.assertTrue(conflict_agent.journal_ajout_pur("CHANGELOG.md"))
        self.assertTrue(conflict_agent.journal_ajout_pur("docs/CHANGELOG.md"))
        self.assertFalse(conflict_agent.journal_ajout_pur("CHANGELOG-OLD.md"))
        self.assertFalse(conflict_agent.journal_ajout_pur("app/A.kt"))


class ResoudreAjoutsTest(unittest.TestCase):
    """Bout en bout sur un vrai rebase : CHANGELOG.md réuni, code au modèle."""

    def _conflit(self, chemin, base, nous, eux):
        run = _scratch_repo(self)
        _commit(run, {chemin: base}, "base")
        base_branch = run("branch", "--show-current").stdout.strip()
        run("checkout", "-qb", "br")
        _commit(run, {chemin: nous}, "br")
        run("checkout", "-q", base_branch)
        _commit(run, {chemin: eux}, "m")
        run("checkout", "-q", "br")
        failed = subprocess.run(["git", "rebase", base_branch],
                                capture_output=True)
        self.assertNotEqual(failed.returncode, 0)
        return run

    def test_changelog_reuni_sans_modele(self):
        # Non-régression 29/10 : #215 rebasée six fois sur CHANGELOG.md, une
        # saisine LLM (et son quota) par rebase.
        self._conflit("CHANGELOG.md", "# Journal\n\n## Ancien\n- vieux\n",
                      "# Journal\n\n## Branche\n- B\n\n## Ancien\n- vieux\n",
                      "# Journal\n\n## Master\n- M\n\n## Ancien\n- vieux\n")
        self.assertTrue(conflict_agent.resoudre_ajouts("CHANGELOG.md"))
        self.assertEqual(conflict_agent.unmerged_files(), [])
        with open("CHANGELOG.md") as fh:
            contenu = fh.read()
        self.assertIn("## Master", contenu)
        self.assertIn("## Branche", contenu)
        self.assertIn("- vieux", contenu)
        self.assertNotIn("<<<<<<<", contenu)

    def test_fichier_hors_journal_rend_la_main(self):
        self._conflit("app.txt", "base\n", "branche\n", "master\n")
        self.assertFalse(conflict_agent.resoudre_ajouts("app.txt"))
        self.assertEqual(conflict_agent.unmerged_files(), ["app.txt"])

    def test_boucle_union_seule_sans_saisine(self):
        # Le modèle n'est pas appelé du tout : aucun saisine consommée.
        self._conflit("CHANGELOG.md", "# J\n\n## Ancien\n",
                      "# J\n\n## B\n\n## Ancien\n",
                      "# J\n\n## M\n\n## Ancien\n")
        with mock.patch.object(conflict_agent, "fw_complete",
                               side_effect=AssertionError("modèle appelé")):
            resolved = conflict_agent.finish_loop(
                "http://x", "K", "M", time.time() + 60)
        self.assertEqual(resolved, ["CHANGELOG.md"])
        self.assertFalse(conflict_agent.rebase_in_progress())


class SaisinesParPrTest(unittest.TestCase):
    """Une PR ne peut pas consommer tout le plafond horaire à elle seule."""

    def test_compte_par_pr_et_par_fenetre(self):
        home = tempfile.mkdtemp(prefix="fw-home-")
        self.addCleanup(shutil.rmtree, home, True)
        with mock.patch.dict(os.environ, {"HOME": home}):
            cache = conflict_agent.cache_dir()
            os.makedirs(cache, exist_ok=True)
            for pr, sha in (("215", "a" * 12), ("215", "b" * 12),
                            ("215", "c" * 12), ("250", "d" * 12)):
                with open(os.path.join(cache, "pr-%s-%s.stamp" % (pr, sha)),
                          "w") as fh:
                    fh.write("0")
            vieux = os.path.join(cache, "pr-215-vieux.stamp")
            with open(vieux, "w") as fh:
                fh.write("0")
            os.utime(vieux, (time.time() - 7_200,) * 2)
            maintenant = time.time()
            self.assertEqual(conflict_agent.saisines_pr("215", maintenant), 3)
            self.assertEqual(conflict_agent.saisines_pr("250", maintenant), 1)

    def test_plafond_par_pr_leve_skip(self):
        home = tempfile.mkdtemp(prefix="fw-home-")
        self.addCleanup(shutil.rmtree, home, True)
        with mock.patch.dict(os.environ, {"HOME": home}):
            cache = conflict_agent.cache_dir()
            os.makedirs(cache, exist_ok=True)
            for i in range(conflict_agent.SAISINES_PR_MAX):
                with open(os.path.join(cache, "pr-215-%012d.stamp" % i),
                          "w") as fh:
                    fh.write("0")
            with self.assertRaises(conflict_agent.Skip):
                conflict_agent.autorisation_saisine("215", "e" * 40)


if __name__ == "__main__":
    unittest.main()
