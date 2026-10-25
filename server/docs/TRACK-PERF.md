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
