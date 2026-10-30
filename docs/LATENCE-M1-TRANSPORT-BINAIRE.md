# LATENCE-M1-TRANSPORT-BINAIRE — supprimer le base64 à la montée (`/live`)

M1 du chantier latence : la frame JPEG (~20,7 Ko) voyage aujourd'hui en base64
(~27 Ko, +33 %) dans un message JSON texte. Passer au binaire retire
**~7 Ko par frame** à la montée, plus le CPU d'encodage/décodage base64 des deux
côtés. Spécification seule : aucun code ici, déploiement groupé en M2/M3
(`docs/LATENCE-M2M3-SERVEUR-INFRA.md`).

Références vérifiées sur `origin/master` `482c5e18` (2026-10-30). Les numéros de
ligne de l'étude d'origine sont suspects (SHAs mixtes) : ceux ci-dessous ont été
re-localisés sur ce HEAD.

## 1. État des lieux (ce qui existe déjà)

| Transport | Client | Serveur | Statut |
|---|---|---|---|
| WS `/live/ws` texte JSON (`image_b64`) | `WsLiveClient` + `WsTransport.send(text: String)` (`core/.../net/WsTransport.kt:28`) | `_ws_loop` + `receive_text()` (`server/backend/live_fast/service.py:331,350`) | **chemin du Direct** (`LiveClientFactory.kt` : WS uniquement) |
| `POST /live` JSON (`image_b64`) | `HttpLiveClient.live` (`core/.../net/HttpLiveClient.kt`) | `_handle_live` → `_run_live` (`server/backend/live_fast/service.py:405,212`) | secours / bancs |
| `POST /live` binaire (`octet-stream`, knobs en query) | `binaryUpload = true` + `liveBinaryUrl` (`HttpLiveClient.kt:114`, `Http.kt:143,160`) | `_run_live_binary` + `decode_jpeg_bytes` (`service.py:224`, `imaging.py:74`) | **déjà codé des deux côtés**, inactif (flag à `false`) |
| WS `/live/ws` binaire | inexistant | inexistant (`receive_text` seul) | **c'est M1** |

- Message WS actuel : exactement le corps de `POST /live` plus `id`
  (`liveFrameJson`, `core/.../net/Json.kt:327` ; contrat serveur
  `server/backend/live_fast/live_ws.py`, `schemas.py:55` `LiveWsRequest`, `id`
  obligatoire). ~28 Ko assemblés par frame.
- Réponse : forme de `POST /live` + `id`, masques base64 (~5 Ko), gzip côté
  descente déjà actif (`FAST_GZIP_MIN_BYTES`, défaut 1024).
- Le `POST` binaire existant prouve le motif serveur (corps brut + knobs hors
  corps, réponse strictement identique) : M1 le transpose au WebSocket, le seul
  transport du Direct.

## 2. Contrat WS binaire proposé

Un message binaire WS = **en-tête + JPEG brut**, sans base64 :

```
0..3   : longueur N de l'en-tête JSON, entier big-endian non signé (≤ 4 Ko)
4..4+N : en-tête JSON UTF-8 (sans `image_b64`, sans `id` binaire)
4+N..  : JPEG brut (tel que sorti de l'encodeur, inchangé)
```

En-tête JSON : les champs de `LiveWsRequest` **moins** `image_b64`
(`schemas.py:55`) — `id` (obligatoire, même rôle de corrélation), `prompts`,
`work_edge`, `max_objects`, `mask_encoding`, `mask_format`, `mask_edge`,
`batch_prompts`, `edge`. Champs futurs (ex. `roi`, voir M2/M3) : même règle
que le texte, ignorés s'ils sont inconnus, **jamais** de 400.

Réponse : **inchangée** (message texte JSON, contrat `live_ws.py`
`success_message` / `error_message`, `id` repris de l'en-tête). La descente
(~5 Ko de masques) ne justifie pas un second format.

Erreurs : en-tête illisible ou `id` absent → `{"id": -1, "error": {...}}`
(même `UNKNOWN_ID` que `live_ws.py`) ; JPEG indécodable → erreur 400 avec
l'`id` de l'en-tête, connexion maintenue (même règle que le texte).

Pourquoi pas « query + binaire » comme le `POST` : un socket WS n'a pas de
query par message ; l'en-tête dans le message garde un aller simple par frame
et réutilise la validation pydantic existante (en-tête → `LiveWsRequest` +
octets).

## 3. Changements client (Kotlin)

1. `WsTransport.kt` : `send(bytes: ByteString)` à côté de `send(text: String)`
   (`:28`), `OkHttpWsTransport` (`:81`) l'envoie en message binaire OkHttp.
2. `Json.kt` : `frameMessageBinary(...)` — même knobs que `liveFrameJson`
   (`:327`), sans `image_b64`, préfixe 4 octets + JPEG brut. Le base64 de la
   frame n'est plus construit sur ce chemin (plus de passage par `String`).
3. `WsLiveClient.kt` : flag `binaryUpload: Boolean = false` (même motif que
   `HttpLiveClient`), défaut `false` jusqu'au déploiement serveur ; `parseReply`
   et `leadingId` inchangés (réponse toujours texte).
4. `LiveClientFactory.kt` : exposer le flag (défaut `false`), comme `maskFormat`
   / `maskEdge` / `withDepth`.
5. Tests JVM : message byte-exact (longueur, en-tête, JPEG), repli texte quand
   le flag est à `false`, trame orpheline toujours ignorée sans parsing.

## 4. Changements serveur (Python, `server/backend/live_fast/`)

1. `service.py` `_ws_loop` (`:331`) : `websocket.receive()` au lieu de
   `receive_text()` (`:350`), dispatch texte → `parse_frame` (inchangé),
   octets → `parse_frame_bytes` neuf.
2. `live_ws.py` : `parse_frame_bytes(raw: bytes)` — découpe longueur/en-tête/
   JPEG, valide l'en-tête en `LiveWsRequest`, renvoie `{id, request, jpeg,
   error}` sur le modèle de `parse_frame`. Constante : en-tête > 4 Ko = 400.
3. `service.py` : `_run_live_binary_ws(request, jpeg)` — `decode_jpeg_bytes`
   (`imaging.py:74`, réutilisé, pas dupliqué) puis `_live_from_image` (le même
   que `_run_live_binary`, `:224-246`). Réponse via `_ws_answer` (`:291`)
   inchangée.
4. Limites inchangées : `WS_IDLE_SECONDS`, `WS_FRAME_SECONDS`,
   `WS_MAX_REPLY_BYTES` (`service.py:33-35`).
5. Tests : `server/scripts/test_live_ws.py` + `test_units.py` — trame binaire
   byte-exacte, texte toujours accepté sur le même socket (mixte), en-tête
   corrompu → `id: -1`, connexion maintenue.

## 5. Compatibilité (vieux clients, vieux serveurs)

- Serveur neuf + client texte : servi comme avant (dispatch par type de
  message). Aucun client existant ne casse.
- Client binaire + serveur vieux : le serveur vieux ne lit que `receive_text`
  → la trame binaire lève côté serveur. Donc le flag client reste à `false`
  tant que le serveur déployé ne sait pas lire le binaire — **activation
  client après le deploy groupé M2/M3, jamais avant** (même règle que le
  `binaryUpload` du `POST`, `HttpLiveClient.kt`).
- Négociation : aucune version de protocole. Le client binaire ne s'active que
  par flag compilé, après vérification `/health` du déploiement (voir M2/M3).

## 6. Gain attendu

- **Octets montants : 27 Ko → 20,7 Ko, ≈ −7 Ko/frame (−23 %)**
  (`docs/LATENCE-BUDGET.md` § 2 : « frame 512×206 JPEG q65 = 20,7 Ko
  (27 Ko en base64) »).
- Réseau : ≈ 0,12 ms/Ko (`scripts/budget_latence.py:66`, § 3 du budget) →
  **~0,8 ms** gagnés sur l'A/R. Petit, assumé : M1 est un gain d'octets et de
  CPU, pas de latence réseau.
- CPU client : la part base64 de `ENCODE` (5–8 ms estimés, § 2 du budget)
  disparaît ; reste le JPEG seul.
- CPU serveur : 1,2 ms mesurés de « base64 + décodage image » (§ 2 bis) →
  reste le seul décodage JPEG (`decode_jpeg_bytes`).
- Mémoire : plus de `String` base64 de ~28 Ko par frame des deux côtés
  (pression GC, cf. u03 cité au § 7 du budget).

## 7. Vérification (critères d'acceptation)

1. Banc filaire avant/après : octets montants/frame −23 % (±1 point), à frame
   JPEG identique (même q65, même scène).
2. `elapsed_ms` serveur inchangé à ±2 ms (le binaire ne touche pas l'inférence).
3. Mixité : un socket alternant texte/binaire répond `id` pour `id`, sans
   fermeture, sans 400.
4. `scripts/budget_latence.py --check-doc` et `--selftest` verts (ce document
   ne touche pas `LATENCE-BUDGET.md` ; s'il devait en reprendre un chiffre, ce
   serait via `--write-doc`, jamais à la main).

## 8. Hors scope (explicitement non-M1)

- Réponse binaire (descente) : ~5 Ko gzipés, gain négligeable.
- Changement de codec image (q65, 512×206) : voir budget § 4, autre chantier.
- `roi` / crop serveur : knob M2/M3, conçu pour passer dans l'en-tête § 2.
