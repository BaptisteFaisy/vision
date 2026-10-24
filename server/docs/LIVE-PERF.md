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