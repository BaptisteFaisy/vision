# TRACK-PERF — le débit du tracker vidéo (`sam3-track`)

Règle de ce fichier, comme `docs/PERF.md` : **aucun chiffre sans provenance**, et un chiffre
qui n'a pas été mesuré s'écrit « non mesuré ».

Le tracker est le transport par **sessions** du direct (`POST /session` + `POST /frame`,
`DELETE /session/{id}`) : les `id` d'objets survivent d'une frame à l'autre, contrairement à
`/live` (voir `docs/LIVE-SPEC.md` § 6). Le client de l'app est
`core/net/TrackClient.kt` + `core/live/TrackRunner.kt` ; il est branché à l'écran Direct
(pastille « Live » / « Tracker » du bandeau du haut).

## 1. Les relevés qui existent (relevés antérieurs, backend `sam3-vision-app`)

Origine : campagne du tracker côté backend (fiche `15-tracker-http-sessions.md`, dépôt
`~/Bureau/sam3-vision-app`), frames **640×360 JPEG q65**, instance **A100-40GB eu-west**.

| Relevé | min | médiane | p95 | max |
|---|---:|---:|---:|---:|
| **Serveur**, 239 frames d'affilée | 156 | **169** | **181** | 204 ms |
| **Aller-retour depuis le PC** (client Python) | 246 | **260** | **287** | 360 ms |
| **Aller-retour depuis le téléphone** (`scripts/phone_bench.py`, relevé antérieur, 59 frames) | 198 | **205** | **224** | 448 ms |

Précisions mesurées au même moment : `POST /session` = **3,1 s** (chargement de la session) ;
la **première** frame d'une connexion neuve coûte **431 ms** ; la connexion est bien réutilisée
(`time_appconnect = 0` sur 58 des 59 frames, une seule invocation `curl`).

## 2. Dans l'app, tracker branché sur l'écran Direct (relevé antérieur)

Client `TrackClient` + `TrackRunner`, une frame en vol (`FrameGate`) : bandeau relevé
**« Direct (tracker) · 1,8 ips · 522 ms · 2 objets »**, contre **~1,6 ips** sur `/live` au même
moment et sur le même appareil.

Conclusion inscrite dans la fiche, et qui vaut d'être gardée : **brancher le tracker ne triple
pas la cadence**. Le facteur limitant est le serveur (préparation de frame sous le verrou GPU),
pas le client — le p95 de 224 ms du banc devient 522 ms en usage réel.

## 3. Cadence côté client (constantes de cet arbre)

| Grandeur | Valeur | Fichier:ligne |
|---|---|---|
| Objectif de cadence du direct | **30 fps** (plancher **33 ms**, plafond **500 ms**) | `core/live/TrackRunner.kt` (`TARGET_FPS`, `MIN_INTERVAL_MS`, `MAX_INTERVAL_MS`) |
| Délai d'une frame du tracker | **15 s** | `core/net/TrackClient.kt:11` (`TRACK_TIMEOUT_SECONDS`) |
| Frames en vol | **1** (`FrameGate`) | `core/live/TrackRunner.kt` (`submit`) |

## 4. Non mesuré (26/10, cet arbre)

- **Le débit du tracker depuis ce téléphone, avec ce client, n'a pas été re-mesuré le
  26/10** : le backend `sam3-track` n'est déployé nulle part (`~/bin/sam3-gpu status` → aucune
  app, aucun conteneur actif) et allumer une GPU Modal est interdit sans accord explicite.
  Les chiffres des § 1 et 2 sont ceux d'un relevé **antérieur**, avec la source ci-dessus — pas une mesure
  de cet arbre.
- Ce que cet arbre vérifie sans GPU : les tests JVM du client et du runner
  (`:core:testDebugUnitTest`, `TrackClientTest` / `TrackRunnerTest`), le budget de lignes et
  les gardes de CI. **Rien de tout cela ne mesure un débit.**