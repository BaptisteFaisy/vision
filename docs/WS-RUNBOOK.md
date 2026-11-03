# Runbook du direct WebSocket — `WS-RUNBOOK`

> **Dépôt** : `BaptisteFaisy/vision` — **publié le** : 03/11/2026 sur `master`
> (`d5501122a`). Conduite à tenir quand le direct iSee coupe, rame ou reste KO.
> Chaque chiffre porte une ancre `fichier::SYMBOLE` relue dans cet arbre ; la
> garde `tools/check-ws-runbook.py` (job `ci-core`) refuse toute dérive doc ↔ code.
> Contrat complet : `docs/LIVE-SPEC.md`. Une seule GPU : `sam3-eu-a100`.

## 1. Chemin d'une frame

Téléphone → `wss://…/live/ws` (`WsTransport.kt::LIVE_WS_PATH`, handshake
`101`, un seul socket par session) → accept inconditionnel puis boucle
(`service.py:466-471`) → inférence bornée → réponse texte JSON avec l'`id`
(`live_ws.py`). Rien d'autre ne transite : pas d'auth, pas de sous-protocole.

## 2. Budgets (garde : §2 ↔ code)

| Budget | Valeur | Ancre code |
|---|---|---|
| Idle serveur : sans message applicatif → fermeture `1001` « idle » | 120 s | `ws_pump.py::WS_IDLE_SECONDS` |
| Inférence max par frame → erreur `503`, connexion gardée | 75 s | `ws_pump.py::WS_FRAME_SECONDS` |
| Réponse max → dégradée (moitié des objets, masques retirés), `1009` en dernier recours | 4 Mo | `ws_pump.py::WS_MAX_REPLY_BYTES` |
| Ping protocole client (ne réarme PAS l'idle serveur) | 30 s | `ws_pump.py::WS_CLIENT_PING_SECONDS` |
| Keepalive applicatif `{"type":"ping"}` → `{"pong":true}`, réarme l'idle | 30 s | `WsKeepAlive.kt::PING_INTERVAL_MS` |
| Budget client en régime (1re réponse reçue) | 5 s | `HttpLiveClient.kt::LIVE_TIMEOUT_SECONDS` |
| Budget client à froid (réveil, thaw GPU) → retombe à 5 s au 1er succès | 60 s | `ProbeFrame.kt::COLD_TIMEOUT_SECONDS` |
| Reprise auto : 1re immédiate, puis backoff ×2 plafonné, jitter ±25 % | 500 ms → 8 s | `WsLink.kt::baseDelayMs`, `WsLink.kt::maxDelayMs` |
| Vol borné : 1 inférence + 1 message tenu, pas de file | 2 | `ws_pump.py::WS_MAX_IN_FLIGHT` |
| GPU : un seul conteneur, jamais deux | 1 | `live_app_fast.py::max_containers` |

Invariants (vérifiés par la garde) : ping 30 s < idle 120 s ;
froid 60 s < frame serveur 75 s ; régime 5 s < 75 s ; backoff max 8 s < 120 s.

## 3. Symptômes → causes → actions

| Ce qu'on voit | Cause probable | Action |
|---|---|---|
| « WebSocket KO » fixe dès l'ouverture | GPU éteinte (scaledown), route absente, réseau | `/warm` (réveil), `/health` (bit `ws_binary`), `sam3-gpu status` ; handshake refusé = `WsHandshakeRefused` (code HTTP gardé) |
| « reconnexion » puis ça repart | Coupure transitoire (1011, redeploy, radio) : reprise auto avec backoff | Rien : 1re reprise immédiate, puis 500 ms → 8 s. Si ça boucle, passer au §4 |
| Timeout à froid (60 s) puis OK | Thaw GPU 5–11 s, voire boot ~28 s après `/release` | Normal au réveil : `/warm` avant chaque recette, ne pas toucher au régime 5 s |
| Timeout en régime (5 s) en charge | File GPU : 1 conteneur × 4 slots, une seule inférence à la fois | Réduire la pression (cadence), pas monter le budget — 5 s révèle la file |
| Fermeture `1001` « idle » | 120 s sans frame ni ping applicatif (dictée, réglages) | Normal : la frame suivante rouvre. Le ping protocole 30 s ne suffit pas, seul le keepalive applicatif réarme |
| Erreur `503` « inference timeout, retry » | Frame > 75 s côté serveur | Retry une fois ; connexion gardée, pas de re-handshake |
| Fermeture `1009` | Réponse > 4 Mo même dégradée | Réduire masques/concepts ; instrumenter la taille avant de toucher la borne |
| `BackendStopped`, « pas de nouvelle tentative » | 404 Modal « invalid function call » après redeploy | Redéployer master via la CI, jamais un code plus vieux (règle test-master-seul) |
| Trou d'une frame après coupure | Aucune frame rejouée : l'échec est immédiat, la suivante rouvre | Normal par contrat ; seule exception : repli binaire→JSON sur `id:-1`, collant |

## 4. Exploitation (zéro code)

- **Une seule GPU.** `MIN_CONTAINERS` est clampé 0..1 (`live_app_fast.py:71`),
  le déploiement prod met `FAST_SCALEDOWN=60` (`deploy-live-fast.sh:89`) :
  jamais `min_containers=1` dans un workflow ni une deuxième app GPU pour
  tester — attendre `sam3-eu-a100` (`sam3-gpu status`/`guard`).
- **`/warm` avant chaque recette**, jamais de `modal deploy` pendant une
  recette ni d'un code plus vieux que le déploiement actif.
- **Extinction douce par défaut** : `sam3-gpu off` (réveil < 10 s) ; `off --hard`
  (404, redeploy obligatoire) uniquement sur demande dure explicite.
- **Timeouts CI** : chaque job ≤ 5 min, enforced par le job `ci-budget`
  (`ci.yml`, `check_timeouts.py`) — ce runbook n'y change rien.
