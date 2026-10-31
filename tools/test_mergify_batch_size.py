#!/usr/bin/env python3
"""Tests de `tools/mergify_batch_size.py` (stdlib, aucun réseau)."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mergify_batch_size import taille_lot  # noqa: E402

# Extrait réel du corps de la PR de lot #715 (31/10, lot d'une seule PR).
CORPS_UNE_PR = """\
> Mergify opened it for batch **avid-pruneau-p8hn1n** of the `default` queue, to test [#702](/BaptisteFaisy/vision/pull/702) before it merges into `master`.

<details>
<summary>Metadata for CI tooling, read by <code>mergify ci</code></summary>

```yaml
---
all_scopes: false
checking_base_sha: c3243d045768010551a258f6d2f92a983a5b7db0
previous_check_retries:
  - attempt: 1
    batch_pr_head_sha: e88d49a849f0d85eee285ca67ed5951a62fa17fa
    batch_pr_number: 713
previous_failed_batches: []
pull_requests:
  - number: 702
    scopes: []
scopes: []
...
```
</details>
"""

# Même bloc avec trois PR : c'est le cas qui DOIT faire tourner les tests.
CORPS_TROIS_PR = """\
```yaml
---
all_scopes: false
pull_requests:
  - number: 702
    scopes: []
  - number: 703
    scopes: []
  - number: 704
    scopes: []
scopes: []
...
```
"""

# Corps sans bloc machine (PR ordinaire, brouillon d'une autre file).
CORPS_SANS_BLOC = """\
## Scope

- `tools/mergify_batch_size.py` — nouveau compteur.
"""


class TailleLotTest(unittest.TestCase):
    def test_lot_dune_pr(self):
        self.assertEqual(taille_lot(CORPS_UNE_PR), 1)

    def test_lot_de_trois_pr(self):
        self.assertEqual(taille_lot(CORPS_TROIS_PR), 3)

    def test_sans_bloc_machine(self):
        # 0 = « je ne sais pas » → l'appelant lance les tests (défaut sûr).
        self.assertEqual(taille_lot(CORPS_SANS_BLOC), 0)

    def test_corps_vide(self):
        self.assertEqual(taille_lot(""), 0)
        self.assertEqual(taille_lot(None), 0)

    def test_liste_vide(self):
        self.assertEqual(taille_lot("pull_requests: []\n"), 0)

    def test_ne_compte_pas_les_autres_nombres(self):
        # `batch_pr_number` des tentatives précédentes n'est pas une entrée de liste.
        corps = (
            "previous_check_retries:\n"
            "  - attempt: 1\n"
            "    batch_pr_number: 713\n"
            "pull_requests:\n"
            "  - number: 702\n"
            "    scopes: []\n"
            "scopes: []\n"
        )
        self.assertEqual(taille_lot(corps), 1)

    def test_dernier_bloc_gagne(self):
        # Le bloc est réécrit à chaque tentative : on lit la dernière version.
        corps = (
            "pull_requests:\n"
            "  - number: 1\n"
            "  - number: 2\n"
            "scopes: []\n"
            "pull_requests:\n"
            "  - number: 3\n"
            "scopes: []\n"
        )
        self.assertEqual(taille_lot(corps), 1)


if __name__ == "__main__":
    unittest.main()
