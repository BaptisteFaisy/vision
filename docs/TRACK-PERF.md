# TRACK-PERF — latences réelles du tracker vidéo `/session` + `/frame`

Mesures 2026-10-25 · app `sam3-track-eu` · URL
`https://baptiste-faisy--sam3-track-eu-sam3track-web.eu-west.modal.run` ·
GPU réellement alloué (`/health`) : **NVIDIA A100-SXM4-40GB**, `region=eu-west`, `model=sam3.1-tracker`.
Classe `Sam3Track` : `max_containers=1`, `scaledown_window=60`, démarrage à froid **51–63 s**
(poids 21 s + predictor 22 s).
Protocole : `scripts/bench_track.py` (clip 720p, JPEG q80, prompt `person`, keep-alive HTTP —
sans keep-alive chaque POST repaie un handshake TLS de ~100–150 ms vers eu-west).
`elapsed_ms` = serveur, A/R = client.

## Résultats — 239 frames d'affilée, 640×360 (le cas de l'app)

| Mesure | min | médiane | p95 | max |
|---|---:|---:|---:|---:|
| **Serveur** (`elapsed_ms`) | 156 | **169** | **181** | 204 |
| **Aller-retour** | 246 | **260** | **287** | 360 |

5,9 frames/s côté serveur, **ids stables sur les 239 frames**, VRAM 5,2 → 8,7 Go (plate), zéro OOM.
Décomposition serveur par frame : décodage JPEG 2 ms, normalisation 2 ms, **propagation 151 ms**,
masques PNG 2–4 ms.

## Résultats — 59 frames, 1280×720 (upload 109 Ko)

| Mesure | min | médiane | p95 | max |
|---|---:|---:|---:|---:|
| Serveur | 169 | **175** | **186** | 192 |
| Aller-retour | 266 | **289** | **351** | 534 |

Le serveur ne bouge pas (l'entrée est ramenée à 1008² de toute façon) : c'est l'**upload** qui
coûte. À 720p le p95 d'aller-retour dépasse 300 ms avec un client Python ; sur le téléphone
(OkHttp, keep-alive, ~40 ms d'overhead client en moins) c'est à re-mesurer.

## Verdict

- **Objectif < 300 ms tenu côté serveur** (p95 181 ms) et **tenu en aller-retour à 640×360**
  (p95 287 ms). Marge faible sur l'A/R : le réseau pèse 90–115 ms.
- Pour de la marge à 720p, prendre **H200** (serveur 1,7× plus rapide sur ce même modèle,
  cf. LIVE-PERF) : p95 A/R attendu ≈ 250 ms, au prix de 4,54 $/h au lieu de 2,415 $/h.
- Les 25–46 ms/frame de `probe_video_tracker.py` **ne s'appliquent pas** au direct : c'était un
  run batch in-container (60 frames d'un seul générateur), pas une frame par requête.

## Les quatre causes réelles (toutes corrigées)

1. **`cached_frame_outputs` jamais évincé** — le paquet garde les masques pleine résolution de
   chaque frame calculée. ~970 Mo retenus par frame → OOM vers la frame 11 sur 40 Go.
   Corrigé par `evict_cached_frame_outputs: True` dans la requête de propagation.
2. **Chunks de grounding désalignés** — le détecteur calcule la grounding par chunks mémorisés
   sous la clé `(chunk_start, chunk_end)` et ne nettoie que la clé précédente, calculée sur la
   même grille. Le builder impose `batched_grounding_batch_size=16` alors que le direct demande
   `max_frame_num_to_track=1` : la clé sortante valait `(frame-16, frame)`, ne correspondait
   jamais, et le cache grossissait de **~200 Mo par frame** (5,6 → 18 Go en 7 frames).
   Corrigé par `predictor.model.batched_grounding_batch_size = 1` (`GROUNDING_BATCH`) : la clé
   devient `(frame, frame+1)` et le nettoyage tombe juste → mémoire plate.
3. **Générateur ré-ouvert à chaque frame** — ~15 ms de setup par appel, et un générateur
   abandonné en vol retient ses tenseurs. Corrigé : **un générateur par session**
   (`max_frame_num_to_track = capacity - 1`), tiré une frame à la fois ; il est fermé au
   `DELETE /session/{id}`.
4. **Normalisation sur CPU** — `load_resource_as_video_frames` fait un PIL bicubique 640×360 →
   1008² puis la normalisation en fp16 numpy : **28–45 ms par frame**. Refait sur GPU
   (`F.interpolate` + moyenne/écart du modèle) : **1–3 ms**.

## Réglages utiles

- `capacity` (corps de `POST /session`, borné 30–900) : le tampon GPU est pré-alloué à cette
  taille, donc c'est **la** variable de VRAM par session. 300 frames ≈ 1,8 Go de tampon.
  Au-delà, le client doit rouvrir une session (sinon 400 `capacity reached`).
- `debug: true` dans `POST /session` : ajoute à chaque `/frame` un relevé
  `mem` (caches vivants, Mo par poste) et `ms` (décodage / normalisation / propagation /
  masques). Coûteux : réservé au diagnostic.
- `TRACK_APP_NAME`, `TRACK_GPU`, `TRACK_REGION`, `TRACK_ROUTING_REGION` : lus **au déploiement**
  (côté client). Attention : une variable lue au niveau module est **réévaluée avec sa valeur
  par défaut dans le conteneur** — d'où le passage de `capacity` et `debug` **par requête**.

## Relancer / arrêter

```bash
cd ~/Bureau/sam3-vision-app
TRACK_APP_NAME=sam3-track-eu TRACK_GPU=A100-40GB TRACK_REGION=eu TRACK_ROUTING_REGION=eu-west \
  /home/baptiste/.venv-modal/bin/modal --profile baptiste-faisy deploy backend/track_app.py
/home/baptiste/.venv-modal/bin/modal --profile baptiste-faisy app stop sam3-track-eu -y   # coupe le GPU
```

Banc : `python3 scripts/bench_track.py --url <url> --frames 240 --sizes 640x360 --capacity 300`
(garde-fou : `KEEP=sam3-track-eu bash scripts/modal-one-gpu.sh`).

## Mesure sur le téléphone (2026-10-25 12:03) — le vrai juge

Xiaomi **2312DRA50G** (Android 15), Wi-Fi 5 GHz *Wifirst*, RSSI −39 dBm, 173 Mbps.
Protocole `scripts/phone_bench.py` : payloads poussés en **un tar**, puis **une seule
invocation `curl` native** sur le téléphone (`--next`, donc **une seule connexion TLS
réutilisée**, comme OkHttp). 59 frames **640×360 JPEG q65**, session ouverte depuis le PC.
Serveur : `sam3-track-eu`, **A100-SXM4-40GB eu-west** (`/health`).

| Mesure (téléphone) | min | médiane | p95 | max |
|---|---:|---:|---:|---:|
| **Aller-retour** | 198 | **205** | **224** | 448 |
| 1re frame (TLS à ouvrir) | — | — | — | 431 |

`time_appconnect = 0` sur les 58 frames suivantes → la connexion est bien réutilisée.
Ouverture de session (`POST /session`) : **3,1 s** côté client, 2,97 s côté serveur.

**Verdict : l'objectif tient sur le téléphone, mieux que depuis le PC** — p95 **224 ms**
contre 287 ms au PC, et médiane 205 ms. Sur les 59 frames, **une seule dépasse 300 ms**
(448 ms) en plus de la première (431 ms, handshake TLS inclus).

Ce qu'il faut retenir pour l'app : le p95 est à 224 ms, mais **la toute première frame après
réveil et la première requête d'une connexion neuve dépassent 300 ms** (~430 ms). Si l'app
doit garantir < 300 ms « tout le temps », elle doit garder sa connexion ouverte et préchauffer
le conteneur (`/health`) avant d'afficher la mesure.

## Budget de 200 ms — ce qui coûte quoi (2026-10-25 13:20)

Le levier n'est **ni le réseau ni la carte** : c'est le **nombre de concepts**. Chaque
concept ajoute une passe de détection *par frame* (le multiplex fait une grounding par
concept), sur une seule frame en vol.

| Carte | Concepts | Serveur méd. | Serveur p95 | A/R (PC) |
|---|---:|---:|---:|---:|
| A100-40GB eu-west | 1 | 171 ms | 181 ms | 261 ms |
| A100-40GB eu-west | 8 (défaut historique de l'app) | ~370 ms | — | ~522 ms (vu à l'écran) |
| **H200 eu-west** | **1** | **87–92 ms** | **98–121 ms** | 198–217 ms |
| H200 eu-west | 8 | 158 ms | — | 205 ms (vu à l'écran) |

Sur le téléphone l'aller-retour ajoute **~36 ms** au temps serveur (mesuré : 205 ms d'A/R
pour 169 ms de serveur). Donc, budget 200 ms tenu avec :

- **H200** (4,54 $/h) au lieu d'A100-40GB (2,415 $/h) — le GPU reste éteint 60 s après la
  dernière frame, le surcoût ne porte que sur les sessions de direct ;
- **1 à 2 concepts** : `TrackRunner.MAX_PROMPTS = 2` le garantit côté client ;
- `capacity = 300` par session au lieu de 900 (tampon GPU pré-alloué).

À 1 concept sur H200, l'aller-retour attendu sur le téléphone est **~130 ms médian,
~160 ms p95** — soit 40 ms de marge sur l'objectif.

**Instance dédiée** : `sam3-direct-h200`
(`TRACK_APP_NAME=sam3-direct-h200 TRACK_GPU=H200 TRACK_REGION=eu TRACK_ROUTING_REGION=eu-west`),
URL `https://baptiste-faisy--sam3-direct-h200-sam3track-web.eu-west.modal.run`. C'est elle que
`sam.base.url` doit viser pour le mode Direct.

## Où passent les millisecondes — et ce qui ne dépend pas du GPU (13:45)

Décomposition instrumentée (`ms` de chaque `/frame`), H200 eu-west, 512×288 q65, 1 concept :

| Poste | Coût médian | Nature |
|---|---:|---|
| Décodage JPEG | 1 ms | CPU |
| Normalisation (GPU depuis 11:xx) | 1 ms | CPU→GPU |
| Masques PNG + base64 | 1–2 ms | CPU |
| **Total non-GPU serveur** | **3–4 ms** | rien à gagner |
| **Propagation (modèle)** | **116 ms** | GPU + dispatch Python |
| Écart A/R − serveur (téléphone) | **36 ms** | TLS + gateway + upload |
| Écart A/R − serveur (PC de dev) | 140–150 ms | **réseau du poste** : `/health` sans calcul met déjà 140 ms, et `modal.com` 237 ms |

Autrement dit : le non-GPU côté serveur est déjà quasi nul (3–4 ms), et le trajet réseau
depuis le téléphone est de 36 ms. **Les 116 ms restants sont le calcul du modèle.**

### Leviers testés

| Levier | Résultat |
|---|---|
| `torch.compile` (`TRACK_COMPILE=1`) | conteneur tué par signal au premier `/frame` — abandonné |
| `multiplex_count`/`max_num_objects` à 4 | **impossible** : le checkpoint est figé à 16 (`size mismatch` sur `no_obj_embed_spatial`, `mask_tokens`, …) |
| `batched_grounding_batch_size` = 4 (`TRACK_GROUNDING_BATCH`) | médiane **84 ms** (p95 164) contre 116 ms (p95 120) en chunk=1 : le 1er frame de chaque lot paie tout (série mesurée : 158, 21, 20, 87, 161, 23, 24, 99…) |
| Baisser la résolution d'envoi (320×180 au lieu de 512×288) | aucun effet : le processeur rameute à 1008² de toute façon |

**Conclusion** : sous 200 ms, c'est le *chunk de 1* qui donne la latence la plus **régulière**
(116 ms constants, p95 120). Le chunk de 4 abaisse la moyenne (84 ms, ≈14 fps) au prix d'un
p95 à 164 ms. Le seul vrai levier restant pour descendre sous ~100 ms serait de **pipelines
plusieurs frames en vol** (le GPU est inactif ~25 % du temps entre deux réponses) — cela
demande de changer la porte côté app et l'ordre d'arrivée des frames côté serveur.

## Cible 100 ms — verdict mesuré (2026-10-25 14:30)

Toutes les mesures : 512×288 q65, **1 concept**, une frame en vol, chunk de grounding = 1.

| Carte (région réelle) | Serveur méd. | Serveur p95 | **A/R téléphone** |
|---|---:|---:|---:|
| A100-40GB (eu-west) | 171 ms | 181 ms | **207 ms** |
| A100-40GB, chunk de 4 | 119 ms | **326 ms** | ~300 ms |
| H200 (eu-west) | 87 ms | 121 ms | **~123 ms** |
| B200 (eu-north — pas de capacité en EU) | **53–70 ms** | 72–156 ms | **235 ms** |

Le B200 calcule le plus vite (53–70 ms) mais n'obtient **pas de capacité en Europe** : il tourne
en `eu-north`, et le trajet téléphone → `eu-west` → Stockholm coûte **165 ms** au lieu de 36 ms
en eu-west (`/health` mesuré depuis le téléphone : 160–205 ms contre ~36 ms sur une app eu-west).
Le gain GPU est entièrement mangé par le réseau.

### Prédictions (chantier backend vs état actuel)

- **Pipeline multi-frames en vol** (client) : augmente le **débit**, pas la latence. Le serveur
  sérialise sous un verrou ; chaque frame attend les précédentes. Sur A100 : 171 ms fixes par
  frame, la latence affichée *augmente* avec la profondeur de file.
- **Chunk de grounding > 1** (backend) : baisse la médiane (119 vs 171 sur A100) mais fait payer
  tout le lot à la première frame → **p95 326 ms**. Incompatible avec une cible de latence.
- **Plancher A100** = 171 ms/frame : aucun chantier backend ne descend en dessous, le seul
  levier restant (résolution d'entrée 1008²) **n'est pas exposé** par `build_sam3_multiplex_video_predictor`.

**Conclusion : 100 ms n'est atteignable sur aucune combinaison disponible.**
Meilleur compromis fiable : **H200 en eu-west, 1 concept → ~123 ms**. 100 ms deviendrait
possible si B200 obtenait de la capacité en `eu-west` (prédiction : 53 + 36 ≈ **89 ms**).
