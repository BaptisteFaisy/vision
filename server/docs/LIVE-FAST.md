# Backend live — là où va le temps, et comment le garder

Source réellement déployée : `backend/live_fast/` (app Modal `sam3-eu-a100`, une A100 40 Go,
`eu-west`). **Une seule** commande de déploiement :

    bash scripts/deploy-live-fast.sh          # déploie puis vérifie /health

## Les leviers, et pourquoi ils sont dans le script

| levier | effet mesuré (relevé antérieur, hors de cet arbre ; A100 eu-west) |
|---|---|
| `FAST_CUDA_GRAPH=1` | encodeur vision **45 ms → 1,4 ms** |
| `FAST_SCALEDOWN=60` | fuite max ~0,04 $ si `/release` raté (60 s au lieu de 300) |
| `FAST_BATCH_PROMPTS=1` | les N concepts dans un seul forward |
| `FAST_EDGE=512` | grille 518 côté serveur |
| `FAST_POST_KEPT_ONLY=1` | post DETR : sigmoid après seuillage (bit-identique au HF, `fast_post.py`) |

Trois déploiements manuels d'autres agents ont effacé ces leviers dans la soirée (le
`cuda_graph` Repassait à `false`, `vision_accel` à `none`) parce que la seule copie du
backend rapide vivait dans `~/builds/prod-backend`, **hors dépôt**. D'où :

- `sam3-gpu` (l'arbitre « une seule GPU ») déploie maintenant `live_fast` **avec** les leviers ;
- aucun contrôle périodique : un ping `/health` réveillerait la carte (règle du
  workflow : déploiement au push uniquement, jamais de `schedule:`) ;
- `.github/workflows/deploy-live-fast.yml` fait la même chose côté GitHub : tout push sur
  `master` qui touche `live_fast/` redéploie (secrets `MODAL_TOKEN_ID`, `MODAL_TOKEN_SECRET`).

## Passage conseillé (`passage`, depuis le 30/10)

Chaque réponse `/live` (HTTP, binaire et WS — un seul chemin,
`service._live_from_image`) porte `passage` : couloir bouché par une personne
(`blocked`), côté conseillé (`side`), écho de l'obstacle (`label`, `box`,
`score`). Calculé sur les boîtes SAM (`backend/passage.py`, miroir vendored
dans `live_fast/`, `VENDORED_PARITY`), sans GPU ni focale : le téléphone y
ajoute ses distances et le mot parlé, et rebascule sur sa décision locale si
le champ est absent (backend antérieur). Personnes seules (FR/EN), bande
centrale 0,34–0,66 (miroir `PositionText.SIDE_EDGE`), naines < 2 % ignorées.

## Où va le temps (télémétrie par étage, `FAST_TIMING=1`)

    decode 1-2 ms · preprocess 6-9 ms · vision 1,4 ms · tokenize 1 ms
    DETR 52-56 ms · post-traitement 6 ms            → serveur 99-106 ms

Le DETR est **fixe** : ni le nombre de concepts (1 ou 8), ni la grille (518 ou 252), ni
`sdpa`, ni un graphe CUDA (capture impossible, `Cannot copy between CPU and CUDA tensors`)
ne le font bouger. Avec ~45-60 ms de réseau, l'app est à 141-185 ms ; **sous 100 ms
demande un autre détecteur**, pas un réglage.
