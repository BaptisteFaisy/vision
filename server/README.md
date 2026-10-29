# server/ — backend SAM 3.1 (Modal, Baseten) + Valhalla

> ⚠️ **L'app Android est native Kotlin, à la racine du dépôt (`app/`, `core/`).**
> Ce dossier ne contient que le **serveur** : segmentation SAM 3.1 sur GPU Modal,
> routage piéton Valhalla, tracker, et les sondes de mesure.
> L'ancienne app Expo (`legacy-expo-app/`) a été **retirée de l'arbre le 29/10/2026** —
> elle vit toujours dans l'historique git (`git log -- server/legacy-expo-app`).

## Ce qui est déployé

| App Modal | Dossier | Routes | Déploiement |
|---|---|---|---|
| `sam3-eu-a100` (A100-40GB, `eu-west`) | `backend/live_fast/` | `GET /health`, `POST /segment`, `POST /live`, `WS /live/ws`, `POST /release` | **CI** (`deploy-live-fast.yml`, à chaque push qui touche le dossier) ou `scripts/deploy-live-fast.sh` |
| `valhalla-idf-cpu` (CPU, tiles Île-de-France) | `backend/valhalla_cpu.py` | `GET /health`, `POST /route`, `GET /signals` | **CI** (`deploy-valhalla-cpu.yml`) |
| `sam3-vision` (ère photo, A10G) | `backend/modal_app.py` | `GET /health`, `POST /segment`, `POST /analyze`, `POST /ask` | manuel, `scripts/deploy_backend.sh` |
| tracker | `backend/track_app.py` | `GET /health`, `POST /session`, `POST /frame`, `POST /session/close` | manuel (`modal deploy`) |

Le **même service live** tourne aussi sur **Baseten** (`backend/baseten/`) ; la synchro des poids
passe par `scripts/baseten_sync.py`.

`backend/{concepts,http_errors,imaging,live_ws,schemas,segmentation,timing}.py` existent en double
dans `backend/live_fast/` : Modal ne suit pas les imports hors du dossier déployé. La parité
octet-à-octet est vérifiée par `scripts/test_units.py` (`VENDORED_PARITY`) — toucher l'un sans
l'autre fait échouer la CI.

## Règle d'or : une seule GPU

Le direct réveille la carte, et la coupe quand le flux s'arrête (`POST /release`, en-tête
`x-sam-release: confirm`). Aucune tâche planifiée ne garde la carte allumée. Garde-fou :
`scripts/modal-one-gpu.sh --status`.

## Déployer

```bash
# direct (le seul chemin normal : il attend la nouvelle version et vérifie /health)
bash scripts/deploy-live-fast.sh              # --no-deploy pour vérifier sans déployer

# ère photo (sam3-vision) : secret + déploiement + smoke test
LLM_BASE_URL=… LLM_API_KEY=… LLM_MODEL=… ./scripts/deploy_backend.sh

# rotation de la clé LLM, puis redéploiement
./scripts/rotate_llm_secret.sh /chemin/cle.txt
```

`deploy_backend.sh` et `rotate_llm_secret.sh` sont idempotents : la clé ne passe **jamais** en
argument de commande (fichier temporaire `chmod 600`, secret Modal écrasé depuis un JSON).
`DRY_RUN=1` valide l'environnement **sans appeler Modal**.

## Tester

```bash
# tests unitaires backend, sans GPU ni token (stdlib + PIL + numpy + fastapi) — ce que lance la CI
python3 scripts/test_units.py

# contrat WebSocket sans GPU ni réseau (24 vérifications)
python3 scripts/test_live_ws.py

# E2E contre un backend déployé (stdlib seule ; groupes health, segment, analyze, ask, contract-errors…)
python3 scripts/test_api.py --url https://…modal.run --only contract-errors
```

Détail script par script : [`scripts/README.md`](scripts/README.md). Contrats et mesures :
[`docs/`](docs/) (`LIVE-FAST.md` pour le direct, `LIVE-SPEC.md` pour le protocole,
`SPEC.md`/`ARCHITECTURE.md` pour l'ère photo — documents datés).

## Coûts indicatifs (GPU)

| Poste | Ordre de grandeur |
|---|---|
| A10G sur Modal (ère `sam3-vision`) | ~1,10 $/h de calcul facturé à la seconde |
| Cold start (chargement SAM 3.1) | **~36 s mesurés une seule fois** (35,8 s pour `/health`, puis 5,7 s pour le `/segment` suivant) ; non reproductible au second essai — chiffres détaillés dans [`docs/PERF.md`](docs/PERF.md) |
| Requête `/segment` | ~1,6–2,0 s à chaud (médians sur 3 passes) |
| Requête `/analyze` | segmentation + appel LLM : dépend de la clé côté serveur |
| App au repos | 0 $ si `scaledown_window` est court (60 s) ; sinon le GPU reste facturé |

À retenir : le coût dominant est le **temps de GPU allumé**, pas le nombre de requêtes.
Un `keep_warm` évite les cold starts mais facture en continu.

## Limites connues

- **Cold start** : ~36 s mesurés une fois après 450 s d'inactivité (`scaledown_window=300`) ; le
  second essai après 567 s n'en a produit aucun — à surveiller en console Modal (`docs/PERF.md`).
- **Liste d'objets coupée à 24** : `MAX_OBJECTS = 24` dans `backend/segmentation.py` trie par score
  puis ne garde que 24 objets, **sans champ d'avertissement** (`docs/SPEC.md`).
- **Prompts libres** : la qualité dépend des concepts demandés ; sans prompt, le serveur choisit une
  liste générique et peut manquer de petits objets.
- **Masques PNG base64** : réponse lourde (centaines de Ko à plusieurs Mo) → Wi-Fi conseillé,
  timeout client 60 s.
- **Sans état** : l'historique du chat est renvoyé par le client à chaque `/ask`, rien n'est stocké.
- **Pas d'authentification** : toute personne ayant l'URL peut consommer du GPU — garder l'URL privée.
- SAM 3.1 est un modèle *promptable*, pas un détecteur exhaustif : les scores faibles se filtrent
  côté client.

## Crédit

Backend SAM 3.1 officiel (poids Meta `facebook/sam3.1` + code natif) — **fait par Baptiste Faisy**.
