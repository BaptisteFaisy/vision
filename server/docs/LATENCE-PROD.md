# Latence du direct — note de déploiement (app `sam3-eu-a100`, backend `live_fast/`)

Ce backend est celui **réellement en production** (modèle `sam3.1-fast`, `batch_prompts`,
grille vision 518, 82-125 ms serveur). `server/backend/live_fast/` du dépôt vision
est la copie de référence. **Ne jamais déployer
`backend/live_app.py`** (205-241 ms, +235 ms de régression).

## La commande, avec les leviers

Seule commande : `server/scripts/deploy-live-fast.sh` (déploie `backend/live_fast/live_app_fast.py`,
attend la nouvelle version, vérifie `/health`). Les leviers posés :

`FAST_BATCH_PROMPTS=1 FAST_EDGE=512 FAST_CUDA_GRAPH=1 FAST_ATTN=sdpa
FAST_SCALEDOWN=60 FAST_SNAPSHOT=1 FAST_MODEL_DIR=/opt/models/sam3.1 FAST_TIMING=1`
(`FAST_DETR_GRAPH` **éteint** : voir ligne graphe DETR ci-dessous)
(les leviers sont **recuits dans l'image** au déploiement — changer un levier =
redéployer). `max_containers=1`, `min_containers=0`, la région et le GPU ne changent pas.

## Ce que chaque levier apporte (mesuré, première salve + cette salve)

| Levier | Gain | Source |
|---|---|---|
| `FAST_TIMING=1` | `timings_ms` par étage + en-tête `Server-Timing` : le chiffre seul devient diagnosticable | `timing.py`, `fast_core._decorate` |
| `FAST_SCALEDOWN=60` | évite **23,6 s** de rechargement après une pause > 20 s (une fois par session) | rapport `modal-runtime` |
| `mask_encoding: "bits"` (sur demande client) | −16,5 ms de CPU d'encodage et −53 % d'octets de masques par frame (12 masques, 512×288) | `bench23/masks_bench.py` |
| cap `max_objects` **avant** encodage | n'encode plus que les objets renvoyés (240 → 12 masques sur une frame à 8 concepts) | même banc |
| préprocess + masques hors verrou GPU | rend le créneau GPU au frame suivant (~20 ms en PNG, ~4 ms en bits) | `fast_core.run_frame`, rapport `cpu-serveur` |
| `@modal.concurrent(max_inputs=2)` | supprime l'attente de la 2ᵉ frame en vol au proxy (~90 ms mesurés) | `live_app_fast.py` |
| dédup alias FR/EN | −14 ms et 5 masques inutiles sur « chaise » + « chair » | rapport `concepts` |
| `FAST_ATTN=sdpa` | attention fusionnée ; **aucun gain mesuré sur `detr_ms` en prod** (45,7 vs 41-45, le coût est aux lancements, pas au calcul) — conservé (standard upstream, inoffensif, encodeur vision 1,2 → 0,7 ms) | `weights._set_attn`, `applied.attn_impl` |
| `FAST_DETR_GRAPH` (éteint) | graphe CUDA de la passe DETR : 2 obstacles capture levés (mapping typé, `arange` RPB), 1er replay à **0,3 ms**, puis **crash conteneur** (mort brutale sans traceback, cause inconnue) — levier coupé le 29/10, code + tests conservés, à reprendre sur carte dédiée | `detr_graph.DetrGraphs`, `applied.detr_graph` |
| cache tokenize | entrées texte mémoïsées par concepts — **validé en prod** : `tokenize_ms` 23,4 → 0,0, médiane serveur 74 → 52 ms | `token_cache.py` |

Coût du `scaledown_window=60` : au pire une minute de A100 en plus par session ≈ **0,04 $**
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