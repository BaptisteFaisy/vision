#!/usr/bin/env python3
"""Tests du verdict de check lu par le balayeur (`pr-auto-merge.yml`).

Le balayeur juge une PR sur `checks_pr` : un contexte peut avoir plusieurs
runs (relance, annulation) et il n'en garde qu'un. **Lequel** décide de tout :
GitHub attache un check requis à la suite la plus récente, donc au run le plus
récent. Départager par `started_at` au lieu du run a rendu le balayeur aveugle
le 31/10 (#702) : `couche0` vert dans le run 41239172991, `couche0` annulé
2 s plus tard dans le run 41239350675 (lancé par un `edited` du corps) — le
balayeur voyait « pass », GitHub et Mergify voyaient « cancelled », et la PR
est restée BLOCKED 20 min sans un seul rouge.

Le programme `jq` testé est **extrait du workflow**, pas recopié : une
divergence entre le test et le balayeur ferait échouer le test, pas la
production. Bibliothèque standard + `jq`, aucun réseau.
"""

import json
import os
import shutil
import subprocess
import unittest

ICI = os.path.dirname(os.path.abspath(__file__))
RACINE = os.path.dirname(ICI)
WORKFLOW = os.path.join(RACINE, ".github", "workflows", "pr-auto-merge.yml")


def trouver_jq() -> str | None:
    """`jq` du PATH, sinon un emplacement connu.

    Les runners `r630-vision-*` n'ont pas `/usr/bin` complet dans le PATH du
    job : sans cette recherche, les tests de comportement étaient tous
    **sautés** en silence (`OK (skipped=4)`), et la garde ne gardait rien.
    """
    trouve = shutil.which("jq")
    if trouve:
        return trouve
    for candidat in ("/usr/bin/jq", "/bin/jq", "/usr/local/bin/jq",
                     os.path.expanduser("~/bin/jq"), "/snap/bin/jq"):
        if os.access(candidat, os.X_OK):
            return candidat
    return None


JQ = trouver_jq()


def extraire_jq(texte: str) -> str:
    """Le programme jq de `checks_pr()`, tel qu'il est dans le workflow."""
    debut = texte.index("checks_pr() {")
    jq_debut = texte.index("--jq '", debut) + len("--jq '")
    jq_fin = texte.index("' 2>/dev/null", jq_debut)
    return texte[jq_debut:jq_fin]


def contexte(nom: str, conclusion: str, run: int, job: int, debut: str) -> dict:
    """Un check-run tel que l'API le rend (seuls les champs lus comptent)."""
    return {
        "name": nom,
        "conclusion": conclusion,
        "started_at": debut,
        "html_url": f"https://github.com/BaptisteFaisy/vision/actions/runs/{run}/job/{job}",
    }


@unittest.skipIf(JQ is None, "jq introuvable sur ce runner")
class VerdictChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        with open(WORKFLOW, encoding="utf-8") as f:
            cls.jq = extraire_jq(f.read())

    def verdict(self, check_runs: list[dict]) -> dict[str, dict]:
        sortie = subprocess.run(
            [JQ, "-c", self.jq],
            input=json.dumps({"check_runs": check_runs}),
            capture_output=True, text=True, check=True,
        ).stdout
        return {c["name"]: c for c in json.loads(sortie)}

    def test_annule_plus_recent_que_le_vert_est_le_verdict(self) -> None:
        # #702, à l'identique : le run annulé (41239350675) est plus récent
        # que le run vert (41239172991), même si son essai a démarré 9 s avant.
        runs = [
            contexte("couche0", "cancelled", 41239350675, 124649773726, "2026-10-31T20:32:11Z"),
            contexte("couche0", "success", 41239172991, 124649807835, "2026-10-31T20:32:20Z"),
            contexte("ci", "success", 41239172931, 124649730598, "2026-10-31T20:32:00Z"),
            contexte("ci-budget", "success", 41239172931, 124649254869, "2026-10-31T20:30:02Z"),
        ]
        c = self.verdict(runs)
        self.assertEqual(c["couche0"]["bucket"], "cancel", c["couche0"])
        self.assertEqual(c["ci"]["bucket"], "pass")
        self.assertEqual(c["ci-budget"]["bucket"], "pass")

    def test_deux_essais_du_meme_run_garde_le_dernier(self) -> None:
        # Relance dans le MÊME run : le départage revient à `started_at`.
        runs = [
            contexte("couche0", "failure", 41239172991, 1, "2026-10-31T20:30:02Z"),
            contexte("couche0", "success", 41239172991, 2, "2026-10-31T20:32:20Z"),
        ]
        self.assertEqual(self.verdict(runs)["couche0"]["bucket"], "pass")

    def test_verdict_normal_reste_vert(self) -> None:
        runs = [
            contexte("ci", "success", 10, 100, "2026-10-31T20:30:02Z"),
            contexte("ci-app", "success", 10, 101, "2026-10-31T20:30:02Z"),
            contexte("ci-core", "success", 10, 102, "2026-10-31T20:30:02Z"),
            contexte("couche0", "success", 11, 110, "2026-10-31T20:30:01Z"),
        ]
        c = self.verdict(runs)
        self.assertTrue(all(v["bucket"] == "pass" for v in c.values()), c)

    def test_url_sans_run_ne_casse_pas(self) -> None:
        # Un check d'une autre app (Mergify) n'a pas d'URL de run Actions.
        runs = [{"name": "Mergify Merge Queue", "conclusion": "cancelled",
                 "started_at": "2026-10-31T20:40:28Z", "html_url": None},
                contexte("couche0", "success", 11, 110, "2026-10-31T20:30:01Z")]
        c = self.verdict(runs)
        self.assertEqual(c["Mergify Merge Queue"]["bucket"], "cancel")
        self.assertEqual(c["couche0"]["bucket"], "pass")


def corps_fonction(texte: str, nom: str) -> str:
    """Le corps bash de `<nom>() {` jusqu'à sa ligne de fermeture (10 espaces)."""
    debut = texte.index(f"\n          {nom}() {{") + 1
    lignes = texte[debut:].split("\n")
    garde = [lignes[0]]
    for ligne in lignes[1:]:
        if ligne == "          }":
            garde.append(ligne)
            break
        garde.append(ligne)
    return "\n".join(garde)


class StructureBalayeur(unittest.TestCase):
    """Garde sans `jq` : les invariants de lecture du workflow lui-même.

    Les tests de comportement sautent si `jq` manque sur le runner — ils
    étaient donc TOUS sautés en CI (runners `r630-vision-*`, PATH réduit) et la
    garde ne gardait rien. Ces assertions-ci tournent partout.
    """

    @classmethod
    def setUpClass(cls) -> None:
        with open(WORKFLOW, encoding="utf-8") as f:
            cls.texte = f.read()

    def test_checks_pr_departage_par_run(self) -> None:
        corps = corps_fonction(self.texte, "checks_pr")
        self.assertIn("sort_by(.name, .run, .debut)", corps)
        self.assertNotIn("sort_by(.name, .debut)", corps.replace(
            "sort_by(.name, .run, .debut)", ""))
        self.assertIn('capture("/runs/(?<id>[0-9]+)")', corps,
                      "l'identifiant de run doit être extrait de l'URL du check")

    def test_suite_en_cours_est_utilisee_par_les_trois_relances(self) -> None:
        self.assertIn("suite_en_cours() {", self.texte)
        for fonction in ("relancer", "relancer_annules", "relancer_couche0"):
            corps = corps_fonction(self.texte, fonction)
            self.assertIn("suite_en_cours \"$wf_run\" \"$sha_run\"", corps,
                          f"{fonction} doit refuser de relancer pendant une suite")
            # La garde passe AVANT la relance, sinon elle ne sert à rien.
            self.assertLess(corps.index("suite_en_cours"),
                            corps.index("run rerun"),
                            f"{fonction} : la garde doit précéder le rerun")


if __name__ == "__main__":
    unittest.main()
