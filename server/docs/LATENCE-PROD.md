# Latence du direct — note de déploiement (app `sam3-eu-a100`, backend `live_app_fast.py`)

Ce backend est celui **réellement en production** (modèle `sam3.1-fast`, `batch_prompts`,
grille vision 518, 82-125 ms serveur). Il n'est pas dans un dépôt : `backend/` de
`~/Bureau/sam3-vision-app` sur ms7d18 est la copie de référence. **Ne jamais déployer
`backend/live_app.py`** (205-241 ms, +235 ms de régression).

## La commande, avec les leviers de cette salve

```bash
LIVE_APP_NAME=sam3-eu-a100 FAST_GPU=A100-40GB FAST_REGION=eu FAST_ROUTING_REGION=eu-west \
  FAST_BATCH_PROMPTS=1 FAST_EDGE=512 FAST_TIMING=1 FAST_SCALEDOWN=120 \
  ~/.venv-modal/bin/modal deploy backend/live_app_fast.py --profile baptiste-faisy
```

`scripts/deploy-live-fast.sh` ne pose aujourd'hui que `FAST_BATCH_PROMPTS` et `FAST_EDGE` :
il faut lui ajouter `FAST_TIMING=1 FAST_SCALEDOWN=120` (les leviers sont **recuits dans
l'image** au déploiement — changer un levier = redéployer). `max_containers=1`,
`min_containers=0`, la région et le GPU ne changent pas.

## Ce que chaque levier apporte (mesuré, première salve + cette salve)

| Levier | Gain | Source |
|---|---|---|
| `FAST_TIMING=1` | `timings_ms` par étage + en-tête `Server-Timing` : le chiffre seul devient diagnosticable | `timing.py`, `fast_core._decorate` |
| `FAST_SCALEDOWN=120` | évite **23,6 s** de rechargement après une pause > 20 s (une fois par session) | rapport `modal-runtime` |
| `mask_encoding: "bits"` (sur demande client) | −16,5 ms de CPU d'encodage et −53 % d'octets de masques par frame (12 masques, 512×288) | `bench23/masks_bench.py` |
| cap `max_objects` **avant** encodage | n'encode plus que les objets renvoyés (240 → 12 masques sur une frame à 8 concepts) | même banc |
| préprocess + masques hors verrou GPU | rend le créneau GPU au frame suivant (~20 ms en PNG, ~4 ms en bits) | `fast_core.run_frame`, rapport `cpu-serveur` |
| `@modal.concurrent(max_inputs=2)` | supprime l'attente de la 2ᵉ frame en vol au proxy (~90 ms mesurés) | `live_app_fast.py` |
| dédup alias FR/EN | −14 ms et 5 masques inutiles sur « chaise » + « chair » | rapport `concepts` |

Coût du `scaledown_window=120` : au pire 100 s de A100 en plus par session = **0,067 $**
(2,415 $/h), et le bail de `~/bin/sam3-gpu` éteint de toute façon l'app.

## Contrat client (rétro-compatible dans les deux sens)

- Un client **muet** (`mask_encoding` absent) est servi exactement comme avant : PNG,
  `mask_b64` identique octet pour octet (test de non-régression `test_finalize_cap`).
- Un client moderne envoie `"mask_encoding":"bits"` (POST `/live` **et** message WS) ; la
  réponse porte toujours `"mask_encoding"` (la représentation appliquée), et `"timings_ms"`
  seulement si `FAST_TIMING=1`.
- `mask_encoding` inconnu ⇒ 400 (validation `Literal`). `none` ⇒ `mask_b64` vide, le client
  dessine la boîte.

## Vérification après déploiement

```bash
curl -s $URL/health | python3 -m json.tool   # levers.timing == true, edge_applied 518
curl -si -X POST $URL/live -d '{"image_b64":"...","prompts":["person"],"mask_encoding":"bits"}'
# attendu : "mask_encoding":"bits", "timings_ms":{...}, en-tête Server-Timing
```

Rejouer ensuite la baseline de `tmp/latency/impl2/measure/COMMANDES.sh` (mission 24) à
l'identique : c'est l'écart avant/après qui vaut, pas la valeur absolue.