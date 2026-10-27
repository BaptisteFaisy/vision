# Backend live — là où va le temps, et comment le garder

Source réellement déployée : `backend/live_fast/` (app Modal `sam3-eu-a100`, une A100 40 Go,
`eu-west`). **Une seule** commande de déploiement :

    bash scripts/deploy-live-fast.sh          # déploie puis vérifie /health

## Les leviers, et pourquoi ils sont dans le script

| levier | effet mesuré (25 octobre, A100 eu-west) |
|---|---|
| `FAST_CUDA_GRAPH=1` | encodeur vision **45 ms → 1,4 ms** |
| `FAST_SCALEDOWN=300` | plus de réveil de 25-50 s entre deux gestes (20 s par défaut) |
| `FAST_BATCH_PROMPTS=1` | les N concepts dans un seul forward |
| `FAST_EDGE=512` | grille 518 côté serveur |

Trois déploiements manuels d'autres agents ont effacé ces leviers dans la soirée (le
`cuda_graph` Repassait à `false`, `vision_accel` à `none`) parce que la seule copie du
backend rapide vivait dans `~/builds/prod-backend`, **hors dépôt**. D'où :

- `sam3-gpu` (l'arbitre « une seule GPU ») déploie maintenant `live_fast` **avec** les leviers ;
- `~/bin/sam3-live-guard auto` vérifie `/health` toutes les 5 min et redéploie si un levier
  a sauté (sans jamais toucher si une autre app GPU tourne) ;
- `.github/workflows/deploy-live-fast.yml` ferait la même chose côté GitHub — mais il faut
  pour cela un dépôt **avec remote** et un jeton Modal dans les secrets : non fait, à décider.

## Où va le temps (télémétrie par étage, `FAST_TIMING=1`)

    decode 1-2 ms · preprocess 6-9 ms · vision 1,4 ms · tokenize 1 ms
    DETR 52-56 ms · post-traitement 6 ms            → serveur 99-106 ms

Le DETR est **fixe** : ni le nombre de concepts (1 ou 8), ni la grille (518 ou 252), ni
`sdpa`, ni un graphe CUDA (capture impossible, `Cannot copy between CPU and CUDA tensors`)
ne le font bouger. Avec ~45-60 ms de réseau, l'app est à 141-185 ms ; **sous 100 ms
demande un autre détecteur**, pas un réglage.
