# LIVE-PERF — latences réelles de `/live` (app `sam3-live`, A100)

Mesures 2026-10-24 · URL `https://baptiste-faisy--sam3-live-sam3live-web.us-east.modal.run` · GPU réellement alloué (`/health`) : **NVIDIA A100-SXM4-40GB**, `device=cuda`, `model=sam3.1`.
Classe `Sam3Live` : `gpu="A100"`, `timeout=300`, `startup_timeout=900`, `scaledown_window=30`, **sans secret** ; démarrage à froid (poids 1,68 Go) : 38 s.
Protocole : 10 appels `/live` consécutifs par résolution, `prompts=["person","car","dog","chair"]`, `max_objects=12` ; serveur = `elapsed_ms` (`time.monotonic`), client = aller-retour (`scripts/bench_live.py`).

## /tmp/street.png — 2048×824 (2,38 Mo de base64)

| work_edge | min | médiane | p95 | max | A/R client méd. | objets |
|---:|---:|---:|---:|---:|---:|---:|
| 512 | 493 | 510 | 525 | 525 | 1779 | 8 |
| 640 | 478 | **499** | 523 | 523 | 1820 | 8 |
| 768 | 493 | 526 | 567 | 567 | 1776 | 8 |

## Frame téléphone 640×258 (même scène, redimensionnée)

| work_edge | min | médiane | p95 | max | A/R client méd. | objets |
|---:|---:|---:|---:|---:|---:|---:|
| 512 | 329 | 340 | 348 | 348 | 1115 | 8 |
| 640 | 322 | **339** | 356 | 356 | 1107 | 8 |
| 768 | 323 | 347 | 363 | 363 | 1113 | 8 |

Masques : PNG gris 8 bits à la taille exacte de l'image envoyée, `True` sur les 6 séries. `scripts/test_api.py --only health,segment` : **2 OK, 0 KO** — `/health` 200, `/segment` 200 en 2,3 s (2048×824, 17 objets).
Erreurs `/live` : 400 borné (52–78 car., 61–91 octets, aucun echo du payload) ; `work_edge=99999` borné à 1024 → 200, `max_objects=99` borné à 24.

## Conclusion — recommandation téléphone

**`work_edge = 768`** (défaut de la SPEC) : le knob ne change quasi rien — le processeur resize de toute façon en 1008×1008, l'encodeur vision domine, et 512/640/768 tiennent dans 3 % (bruit de mesure) ; inutile de dégrader la résolution.
Objectif **< 600 ms/frame tenu côté serveur** : médiane 526 ms en 2048×824 (p95 567 ms), 347 ms en 640 px (p95 363 ms).
Le vrai levier est la **taille de frame envoyée** : 640 px ⇒ ~1,1 s d'aller-retour, 2048 px ⇒ ~1,8 s (transfert base64 + masques encodés à taille pleine). Le client Kotlin redimensionnant déjà à 640 px, un intervalle de **350–500 ms** est réaliste et le régulateur adaptatif (> 900 ms) ne se déclenchera pas.
Descendre sous 340 ms exigerait de toucher `processor.image_processor.size` (hors périmètre de cette tâche).

## Relancer / arrêter

```bash
cd ~/Bureau/sam3-vision-app && /home/baptiste/.venv-modal/bin/modal --profile baptiste-faisy deploy backend/live_app.py
/home/baptiste/.venv-modal/bin/modal --profile baptiste-faisy app stop sam3-live -y   # coupe le GPU
```
## A100 40 Go vs H200 — même code, même contrat (2026-10-24 19:0x)

Variante H200 déployée sans dupliquer le code : `LIVE_APP_NAME=sam3-live-h200 LIVE_GPU=H200
modal deploy backend/live_app.py` (constantes surchargées par l'environnement).
GPU réellement alloué (`/health`) : **NVIDIA H200**. Démarrage à froid : **25 s** (A100 : 27 s).

Protocole identique aux mesures ci-dessus, **8 concepts** (`person,chair,table,laptop,bottle,cup,bag,door`),
`max_objects=16`, 12 passes par résolution.

### Frame téléphone (512×206, JPEG q65, 21 Ko de base64) — le cas réel de l'app

| Carte | work_edge | serveur méd. | p95 serveur | A/R méd. | p95 A/R | objets |
|---|---|---:|---:|---:|---:|---:|
| A100-40GB | 512 | 413 ms | 418 ms | 1269 ms | 1289 ms | 8 |
| A100-40GB | 640 | 406 ms | 430 ms | 1259 ms | 1345 ms | 8 |
| A100-40GB | 768 | 402 ms | 406 ms | 1254 ms | 1260 ms | 8 |
| **H200** | 512 | **244 ms** | 249 ms | **706 ms** | 756 ms | 8 |
| **H200** | 640 | **245 ms** | 256 ms | **686 ms** | 706 ms | 8 |
| **H200** | 768 | **247 ms** | 252 ms | **696 ms** | 710 ms | 8 |

### Image de référence (2048×824, 2,38 Mo de base64)

| Carte | serveur méd. (edge 640) | A/R méd. | objets |
|---|---:|---:|---:|
| A100-40GB | 551 ms | 1844 ms | 8 |
| H200 | 446 ms | 1659 ms | 8 |

### Lecture

- **H200 = 1,7× plus rapide côté serveur** (245 vs 406 ms) et **1,8× sur l'aller-retour** (0,69 vs 1,26 s).
  Plafond théorique du direct (concurrence 1, une frame à la fois) : **4,1 ips** (H200) vs **2,5 ips** (A100).
- `work_edge` ne change presque rien (240→247 ms de 512 à 768) : le coût est dominé par les **8 passes DETR**
  (une par concept) après un seul encodage vision. Baisser la résolution ne rend pas le direct plus rapide ;
  **réduire le nombre de concepts, si.**
- Coût par frame : **0,237 $/1000 frames en A100** vs **0,309 $/1000 en H200** — H200 est 2,16× plus cher
  à l'heure pour 1,7× de vitesse, donc **plus cher par frame**. Il n'est intéressant que si l'on veut
  ~4 ips au lieu de ~2,5 (fluidité) ou plus de concepts par frame.

### Recommandation

- Rester sur **A100** pour l'usage actuel (1,5–2 ips réels sur le téléphone, ~0,24 $/1000 frames).
- Passer à **H200** si le direct doit être plus fluide : `local.properties` → `sam.base.url` =
  `https://baptiste-faisy--sam3-live-h200-sam3live-web.us-east.modal.run`, rebuild, réinstaller.
- Pour franchir un vrai cap (>10 ips, suivi d'objets persistant), ce n'est pas une question de carte :
  c'est le **tracker vidéo** `sam3.1_multiplex.pt` (mesuré précédemment : 21,6 ips en A100, 35,8 en H100,
  40,0 en H200) qu'il faut brancher à la place de la boucle image-par-image.

### Mesure sur le téléphone (Xiaomi 2312DRA50G, Android 15, Wi‑Fi 192 Mbps), même build

| Carte | régime établi | cadence affichée | latence affichée |
|---|---|---|---|
| A100-40GB | ~40 s après réveil | 1,4–1,7 ips | 599–710 ms |
| H200 | ~40 s après réveil | 1,3 ips (après 1,1–1,6 en montée) | 754–775 ms |

**Verdict terrain : aucun gain visible.** Le goulot du direct sur le téléphone est le **lien montant**
(base64 de la frame + masques redescendus) et la cadence d'une frame à la fois, pas le GPU. Le gain
serveur de H200 (406 → 245 ms) est avalé par le réseau. L'app reste donc sur **A100** (2,10 $/h au lieu
de 4,54 $/h). H200 ne redevient utile qu'avec le tracker vidéo (40 ips) ou plusieurs flux simultanés.
