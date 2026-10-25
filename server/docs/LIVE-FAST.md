# Backend live (`live_fast`) — le direct rapide, ses leviers et ses chiffres

Le téléphone appelle `POST /live` et `WS /live/ws` d'une app Modal : **`sam3-eu-a100`**,
une seule A100 40 Go, région `eu`, routing `eu-west`. Ce dossier (`backend/live_fast/`)
est la source **réellement déployée**, autonome (il n'écrase pas `backend/imaging.py`
et consorts utilisés par le tracker).

## Déployer — une seule commande

```bash
bash scripts/deploy-live-fast.sh             # déploie puis vérifie /health
bash scripts/deploy-live-fast.sh --no-deploy # vérifie seulement
```

Les leviers sont **dans ce script**, et nulle part ailleurs :

| levier | effet | mesuré le 25/10 |
|---|---|---|
| `FAST_CUDA_GRAPH=1` | graphe CUDA de l'encodeur vision | 45 ms → **1,4 ms** |
| `FAST_SCALEDOWN=300` | 5 min avant extinction du conteneur | plus de réveil de 25-50 s entre deux gestes |
| `FAST_EDGE=512` | grille du modèle (518 côté serveur) | — |
| `FAST_BATCH_PROMPTS=1` | N concepts dans un seul forward | 8 concepts ≈ 1 concept (6 ms d'écart) |

`sam3-gpu` (l'outil qui arbitre « une seule GPU ») déploie désormais **ce** fichier avec
**ces** leviers : un `sam3-gpu on` ne peut plus revenir à la version lente.

`~/bin/sam3-live-guard` surveille en plus l'app déployée : `check`, `status`, `repair`,
`auto` (répare dès qu'un levier a sauté, sans jamais toucher si une autre app GPU tourne).

## Où va le temps (décomposition mesurée, `FAST_TIMING=1`)

| poste | temps | commentaire |
|---|---|---|
| `decode_ms` | 1-2 ms | décodage JPEG |
| `preprocess_ms` | 6-9 ms | resize + normalisation, CPU, sous le verrou GPU |
| `vision_ms` | **1,4 ms** | encodeur vision (graphe CUDA) |
| `tokenize_ms` | 1 ms | concepts |
| `detr_ms` | **52-56 ms** | têtes de détection — **fixe** : ni le nombre de concepts (1 ou 8), ni la grille (518 ou 252), ni `sdpa`, ni un graphe CUDA n'y touchent |
| `post_ms` | 6 ms | post-traitement des masques |
| **total serveur** | **99-106 ms** | + ~45-60 ms de réseau → 141-185 ms dans l'app |

Conséquence : **sous 100 ms dans l'app est hors de portée** avec ce détecteur. Il
faudrait un autre modèle (le paquet `transformers` embarque un variant `sam3_lite_text`),
donc d'autres capacités de détection.

## Ce qui a déjà été essayé, et écarté

| piste | résultat |
|---|---|
| `FAST_COMPILE=1` | 125-128 ms et 106 s de démarrage → pire |
| graphe CUDA du DETR (`detr_graph.py`) | capture impossible : `Cannot copy between CPU and CUDA tensors during CUDA graph capture` (tenseur CPU créé dans le forward) |
| grille 252 | vision 45 → 42 ms seulement, masques plus grossiers |
| `sdpa` au lieu d'`eager` (balayage des sous-configs) | aucun effet sur `detr_ms` |

`detr_graph.py` reste dans le dossier, **éteint** : il documente la piste et retombe
proprement en eager (vérification eager/graphé + budget de formes).