# Agents Fireworks

Deux agents, deux problèmes distincts, jamais une porte de merge : seule la CI
verte fait merger. Tous deux sont lus sur `origin/master` par le sweep (la
branche d'une PR peut être antérieure), et n'écrivent que du texte ou des puces
vérifiées.

## Agent de conflit (`conflict_agent.py`)

Quand le sweep de `pr-auto-merge` échoue à rebaser une PR, il appelle
`conflict_agent.py` dans son clone, rebase en cours. L'agent demande au modèle
Fireworks la résolution de chaque fichier en conflit, termine le rebase, pousse
sur la branche de la PR avec bail et rend compte en commentaire. La file reprend
au passage suivant ; seule la CI verte fait merger.

## Contrat d'appel

```bash
FIREWORKS_API_KEY=... python3 conflict_agent.py resolve \
  --repo BaptisteFaisy/vision --pr 118 --branch feat/x --sha <tête>
```

Le script est lu sur `origin/master` (pas sur la branche de la PR, qui peut
être antérieure). Sorties : `0` poussé, `1` échec avec compte rendu,
`2` rien à faire (l'appelant saute sans bruit).

## Réglages

| Variable / secret | Défaut | Effet |
|---|---|---|
| secret `FIREWORKS_API_KEY` | — | sans lui, l'agent ne fait rien |
| var `FIREWORKS_MODEL` | `accounts/fireworks/models/minimax-m3p1-flash` | modèle interrogé |
| var `FIREWORKS_BASE_URL` | `https://api.fireworks.ai/inference/v1/chat/completions` | point d'API |
| var `FIREWORKS_AGENT_RESOLVE` | `1` | `0` coupe l'agent sans toucher au code |

## Garde-fous

Empreinte par tête de PR + cooldown 20 min, plafond 10 saisines/heure (`SPAWN_CAP`),
8 fichiers max, refus des conflits sous `.github/`, des binaires et des
fichiers de plus de 200 Ko, budget 240 s, push avec bail uniquement.
Labels `hold` / `intervention-humaine` : main humaine, aucune saisine.
Succès : label `agent-conflit`. Le modèle ne voit que les bords du conflit,
jamais aucun secret.

Recette : `python3 conflict_agent.py resolve --dry-run --repo BaptisteFaisy/vision
--pr N` affiche les prompts sans rien appeler (dans un clone en conflit).

## Agent de périmètre (`scope_agent.py`)

Une PR dont le corps n'a pas de `## Scope` (ou dont la section ne couvre pas tous
les fichiers modifiés) est refusée par `couche0`, donc vue `BLOCKED` par le
balayeur. L'agent lit le diff, rédige une puce ``- `chemin` — raison`` par
fichier en français, puis **rejoue `check_scope` du garde de `master`** sur le
corps recomposé avant d'écrire : fichier non déclaré et déclaration inutile
restent refusés. Ce n'est pas un contournement du garde, c'est la déclaration
manquante qui est écrite.

```bash
FIREWORKS_API_KEY=... python3 scope_agent.py fix \
  --repo BaptisteFaisy/vision --pr 215 --sha <tête> --guard /tmp/check_couche0.py
```

Sorties : `0` corps complété (un commentaire porte le marqueur `automerge-scope`
avec la tête, donc une seule tentative par commit), `1` échec avec compte rendu,
`2` rien à faire. Réglages : `FIREWORKS_MODEL` / `FIREWORKS_BASE_URL` comme
l'agent de conflit, `FIREWORKS_AGENT_SCOPE=0` pour couper. Plafond 25 fichiers ;
au-delà, label `intervention-humaine` et main humaine.

## Validation

```bash
python3 -m unittest test_conflict_agent test_scope_agent -v
```
