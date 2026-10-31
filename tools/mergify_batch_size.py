#!/usr/bin/env python3
"""Combien de PR porte un lot de la file Mergify ?

Lit le corps de la PR brouillon que Mergify ouvre sur `mergify/merge-queue/**`
(ou le fichier passé en argument, ou stdin) et rend le nombre de PR du lot.

Pourquoi ce script existe
-------------------------
La file de merge ouvre une PR de lot et fait tourner la CI dessus. Or, pour un
lot d'UNE PR, l'arbre testé est exactement celui de la PR déjà verte : Mergify
la rebase sur `master` (`update_method: rebase`) et reconstruit la même chose.
Rejouer Gradle dans ce cas ne teste rien de neuf — c'est un deuxième passage
complet pour un verdict déjà connu, et il coûte un créneau de runner.

Un lot de PLUSIEURS PR, en revanche, teste une combinaison que personne n'a
vue : chaque PR a été vérifiée seule sur `master`, jamais les unes sur les
autres. Là, il faut vraiment lancer les tests.

La CI a donc besoin de la taille du lot. Elle est écrite en clair par Mergify
dans un bloc YAML « Metadata for CI tooling » en fin de corps de la PR :

    pull_requests:
      - number: 702
        scopes: []

Sortie : le nombre de PR du lot ; 0 si le bloc est absent ou illisible
(défaut sûr : l'appelant lance les tests quand il ne sait pas).

Stdlib uniquement, aucun réseau, quelques microsecondes.
"""

import re
import sys

# `pull_requests:` en colonne 0 (clé racine du bloc YAML de Mergify).
OUVERTURE = re.compile(r"^pull_requests:\s*$")
# Une entrée de la liste : `  - number: 702` (indentation libre, la liste est
# celle du bloc ouvert juste au-dessus).
ENTREE = re.compile(r"^\s+-\s+number:\s*(\d+)\s*$")


def taille_lot(corps):
    """Nombre de PR déclarées dans le bloc `pull_requests:` de Mergify.

    0 si le bloc est absent, vide ou illisible.
    """
    if not corps:
        return 0
    dedans = False
    nombres = []
    for ligne in corps.splitlines():
        if OUVERTURE.match(ligne):
            dedans = True
            nombres = []
            continue
        if not dedans:
            continue
        # Une clé racine ferme le bloc (`scopes: []`, `...`).
        if ligne and not ligne[0].isspace() and not ligne.startswith("-"):
            dedans = False
            continue
        trouve = ENTREE.match(ligne)
        if trouve:
            nombres.append(int(trouve.group(1)))
    # Une liste vide (`pull_requests: []`) rend 0 : on ne sait pas, pas « sûrement
    # une seule PR » — l'appelant doit alors lancer les tests.
    return len(nombres)


def main(argv):
    if len(argv) > 1 and argv[1] not in ("-", ""):
        with open(argv[1], encoding="utf-8") as poignee:
            corps = poignee.read()
    else:
        corps = sys.stdin.read()
    print(taille_lot(corps))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
