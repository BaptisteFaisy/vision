"""Tests du passage conseillé par SAM (``suggest_passage``) : décision et tolérance.

Personnes, barrières et poteaux au centre bouchent, le côté le plus libre
est conseillé ; le reste (voitures, nains, rebuts) passe en voie libre.
``scripts/test_units.py`` fournit la plomberie (``check``).
"""

from __future__ import annotations


def _passage_obj(label, box, score=0.9):
    """Objet SAM factice : les champs lus par ``suggest_passage``."""
    return {"label": label, "score": score, "box": box}


def test_passage_decision(passage) -> None:
    """Passage SAM : bouché au centre, côté le plus libre conseillé."""
    from test_units import check

    free = passage.suggest_passage([], 512, 512)
    check("passage: vide = voie libre",
          free == {"blocked": False, "side": None, "label": None,
                   "box": None, "score": None}, str(free))
    center = passage.suggest_passage(
        [_passage_obj("person", [200.0, 100.0, 312.0, 400.0], 0.77)], 512, 512)
    check("passage: personne au centre = bouché à gauche (égalité)",
          center["blocked"] is True and center["side"] == "left", str(center))
    check("passage: écho de l'obstacle",
          center["label"] == "person" and center["score"] == 0.77
          and center["box"] == [200.0, 100.0, 312.0, 400.0], str(center))
    left = passage.suggest_passage(
        [_passage_obj("person", [10.0, 50.0, 100.0, 400.0])], 512, 512)
    check("passage: personne hors bande = voie libre",
          left["blocked"] is False, str(left))
    crowd = passage.suggest_passage(
        [_passage_obj("person", [200.0, 100.0, 312.0, 400.0]),
         _passage_obj("person", [50.0, 50.0, 150.0, 400.0]),
         _passage_obj("person", [360.0, 50.0, 460.0, 400.0]),
         _passage_obj("person", [362.0, 60.0, 462.0, 400.0])], 512, 512)
    check("passage: 1 à gauche, 2 à droite = passer à gauche",
          crowd["blocked"] is True and crowd["side"] == "left", str(crowd))
    depth = passage.suggest_passage(
        [_passage_obj("person", [206.0, 100.0, 306.0, 400.0]),
         _passage_obj("person", [50.0, 0.0, 150.0, 500.0]),
         _passage_obj("person", [362.0, 200.0, 462.0, 300.0])], 512, 512)
    check("passage: égalité = côté le plus loin (boîte petite)",
          depth["side"] == "right", str(depth))
    offset = passage.suggest_passage(
        [_passage_obj("person", [180.0, 100.0, 230.0, 400.0])], 512, 512)
    check("passage: obstacle décalé à gauche = passer à droite",
          offset["blocked"] is True and offset["side"] == "right", str(offset))
    barrier = passage.suggest_passage(
        [_passage_obj("barrier", [200.0, 100.0, 312.0, 400.0])], 512, 512)
    check("passage: barrière au centre = bouché",
          barrier["blocked"] is True and barrier["side"] == "left", str(barrier))
    pole = passage.suggest_passage(
        [_passage_obj("pole", [250.0, 100.0, 262.0, 400.0])], 512, 512)
    check("passage: poteau au centre = bouché",
          pole["blocked"] is True and pole["side"] == "left", str(pole))


def test_passage_tolerance(passage) -> None:
    """Passage SAM : bloqueurs seuls, FR, nains et rebuts tolérés."""
    from test_units import check

    car = passage.suggest_passage(
        [_passage_obj("car", [200.0, 100.0, 312.0, 400.0])], 512, 512)
    check("passage: voiture au centre = voie libre (ni personne ni statique)",
          car["blocked"] is False, str(car))
    french = passage.suggest_passage(
        [_passage_obj("Une Personne", [200.0, 100.0, 312.0, 400.0])], 512, 512)
    check("passage: libellé français reconnu",
          french["blocked"] is True and french["label"] == "Une Personne", str(french))
    french_barrier = passage.suggest_passage(
        [_passage_obj("Une Barrière", [200.0, 100.0, 312.0, 400.0])], 512, 512)
    check("passage: barrière française reconnue",
          french_barrier["blocked"] is True
          and french_barrier["label"] == "Une Barrière", str(french_barrier))
    tiny = passage.suggest_passage(
        [_passage_obj("person", [250.0, 100.0, 262.0, 108.0])], 512, 512)
    check("passage: naine de 8 px ignorée (seuil 2 %)",
          tiny["blocked"] is False, str(tiny))
    tiny_pole = passage.suggest_passage(
        [_passage_obj("pole", [250.0, 100.0, 262.0, 108.0])], 512, 512)
    check("passage: poteau nain de 8 px ignoré (seuil 2 %)",
          tiny_pole["blocked"] is False, str(tiny_pole))
    garbage = passage.suggest_passage(
        [None, {"label": "person"}, {"label": "person", "box": [1, 2]},
         {"label": "person", "box": ["x", 0, 1, 2]}, {"box": [1, 2, 30, 40]}], 512, 512)
    check("passage: objets inexploitables ignorés sans lever",
          garbage["blocked"] is False, str(garbage))
    broken = passage.suggest_passage(None, 0, 0)
    check("passage: entrée invalide = voie libre sans lever",
          broken["blocked"] is False, str(broken))
