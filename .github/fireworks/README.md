# Agent de conflit Fireworks

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

## Validation

```bash
python3 conflict_agent.py resolve --dry-run --repo BaptisteFaisy/vision --pr N
```

Dans un clone en conflit : affiche les prompts, n'appelle rien.
Recette bout en bout : voir le plan de test de la PR (serveur Fireworks
simulé + dépôt local).
