# LIVE-PERF — latences réelles de `/live` (app `sam3-live`, A100)

> Relevés antérieurs, hors de cet arbre (règle §0 : cités sans date ni sha).
> La commande de déploiement qui fait foi est `scripts/deploy-live-fast.sh`.

## Démarrage à froid : poids dans l'image + snapshot mémoire (relevé antérieur, hors de cet arbre)

Deux leviers, dans cet ordre, mesurés sur `sam3-eu-a100` (A100-40GB, eu-west) :

1. **Poids embarqués dans l'image** (`.run_commands` → `snapshot_download` au build,
   `FAST_MODEL_DIR=/opt/models/sam3.1`, `local_files_only=True` à la charge) : le
   conteneur ne retélécharge plus 1,7 Go au démarrage. `load_seconds` passe de
   **32 s à 12-13 s** ; le Hub HF ne renvoie plus son avertissement de débit.
2. **Snapshot mémoire** (`enable_memory_snapshot=True` + `@modal.enter(snap=True)` pour
   les poids chargés **sur CPU**, puis un `@modal.enter()` pour le passage GPU) : quand
   elle a lieu, la restauration évite de recharger (journaux Modal :
   `Restoring Function from memory snapshot`, puis `GET /health -> 200` en **2,3 à 7,3 s**).
   Le tout premier démarrage après un déploiement crée le snapshot (≈ 24 s, incompressible).

Restauration **freeze/thaw** (mesuré le 29/10/2026, 2 réveils) : après une extinction
idle (scaledown 60 s), Modal **gèle** le conteneur au lieu de le tuer — le réveil est
un **thaw à 5,5-10,9 s** (thaw réseau + phase GPU 1,4-4,6 s), pas un boot complet.
L'âge `/proc` inclut le gel (thaw à 185,8 s pour un snap de 20,4 s).

En revanche **POST /release tue** (`os._exit`, pas de gel) : le réveil suivant est
un **boot complet** — 27,6 s mesuré (hôte froid : ~20 s CPU dont 12 s
`import transformers`, + GPU + overhead Modal ; ~11 s sur hôte chaud). Quitter
l'app coûte donc le boot complet au retour ; la laisser idler 60 s donne le thaw.

Contrainte à connaître : un snapshot mémoire ne contient pas d'état CUDA, d'où la
coupure CPU/GPU entre les deux `enter`. `/health` expose `restored` (vrai ssi thaw :
`age_conteneur_s - poids_cpu_1er_demarrage_s > 30`), `load_breakdown`
(`age_conteneur_s`, `poids_cpu_1er_demarrage_s`, `gpu_s`) et `memory_snapshot`.
(`load_seconds` ne mesure que la phase GPU ; le VRAI temps d'ouverture est
`load_breakdown.age_conteneur_s`.)

## ⚠️ Ne pas déployer `live_app.py` (relevé antérieur, hors de cet arbre)

`backend/live_app.py` est la version **LENTE** (1008 px, 1 forward DETR par concept) : **205-241 ms serveur**.
La production du téléphone, c'est le backend rapide (`backend/live_fast/`) avec `FAST_BATCH_PROMPTS=1` et
`FAST_EDGE=512` : **83-125 ms serveur**, **213 ms d'aller-retour côté téléphone**. Un déploiement
fait depuis le dépôt avec `live_app.py` double la latence, sans aucune erreur visible.

Seule commande de déploiement du direct (réglages en dur + vérification de `/health`) :

```bash
bash scripts/deploy-live-fast.sh               # déploie backend/live_fast/live_app_fast.py puis vérifie
bash scripts/deploy-live-fast.sh --no-deploy   # vérifie seulement, n'allume rien
```

Les sections ci-dessous (mesures sur `live_app.py`, relevé antérieur, hors de cet arbre) documentent l'état **lent** :
elles restent la référence de non-régression, pas la cible de production.

Mesures (relevé antérieur, hors de cet arbre) · URL `https://baptiste-faisy--sam3-live-sam3live-web.us-east.modal.run` · GPU réellement alloué (`/health`) : **NVIDIA A100-SXM4-40GB**, `device=cuda`, `model=sam3.1`.
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
bash server/scripts/deploy-live-fast.sh  # depuis la racine du dépôt vision (déploie backend/live_fast/live_app_fast.py, jamais backend/live_app.py)
/home/baptiste/.venv-modal/bin/modal --profile baptiste-faisy app stop sam3-eu-a100 -y   # coupe le GPU
```
## A100 40 Go vs H200 — même code, même contrat (relevé antérieur, hors de cet arbre)

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

## A100-40GB en Europe — region + routing (relevé antérieur, hors de cet arbre)

Demande : « tester une A100 40 Go en Europe pour le moins de latence possible ».
Modal permet deux réglages distincts (docs/guide/region-selection) :

- `region="eu"` : le **conteneur** (donc le GPU) est placé en Europe. Multiplicateur tarifaire 1,15×
  (large) ou 1,75× (étroit, ex. eu-west). Choisi : large `eu` → 2,415 $/h.
- `routing_region="eu-west"` : le **trafic web** sort de Virginia (défaut Modal) et passe par Dublin.
  Sans ça, même GPU européen = aller-retour Paris→Virginie→Europe. Fixable qu'au premier déploiement.

Déploiement : `LIVE_APP_NAME=sam3-eu-a100 LIVE_GPU=A100-40GB LIVE_REGION=eu LIVE_ROUTING_REGION=eu-west
modal deploy backend/live_app.py` → URL `https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run`.
`/health` confirme : `gpu=NVIDIA A100-SXM4-40GB, region=eu-west, cloud=GCP` (40 Go réelles, pas d'upgrade 80 Go).

### PC (France) vs mêmes protocoles us-east — street 640×258, 4 concepts, max 12

| Backend | serveur méd. | A/R méd. | part réseau |
|---|---:|---:|---:|
| A100 us-east (table plus haut) | 339–340 ms | 1107–1115 ms | ~775 ms |
| **A100-40GB eu-west** | **294–298 ms** | **472–503 ms** | **~200 ms** |

8 concepts, frame 512×206 (scène sans objets, à titre indicatif) : serveur 481–485 ms, A/R 627–633 ms.
Le serveur n'est pas plus lent en Europe (GCP SXM4 40 Go, même ordre que AWS us-east).

### Téléphone (Xiaomi, live30 multi-frames, 6 en vol)

| Backend | cadence établie | latence affichée |
|---|---|---|
| A100 us-east, 1 frame à la fois (ancien build) | 1,4–1,7 ips | 599–710 ms |
| B200 us-east, 6 en vol | 20–28 ips | 517–627 ms |
| **A100-40GB eu-west, 6 en vol** | **4,4–4,8 ips** | **~1220–1260 ms** |

Lecture : la latence affichée inclut la **file d'attente** derrière le verrou GPU (1 conteneur,
inférence sérialisée ~250–300 ms × 6 en vol). L'A100-EU triple la cadence de l'A100-US simple frame
et descend l'A/R réseau à ~200 ms, mais ne rattrape pas le B200 (41 ms/frame côté serveur).
Le goulot A100 n'est plus le réseau : c'est le GPU lui-même.

### Coût & garde-fous

max_containers=1 + scaledown 20 s : jamais deux cartes, la carte chauffe 20 s après la dernière frame.
Session de test ≈ 9–10 min de GPU ≈ **0,36 $** (2,415 $/h). Garde : `KEEP=sam3-eu-a100 bash scripts/modal-one-gpu.sh`.

## WebSocket `/live/ws` — supprimer l'overhead HTTP par frame (relevé antérieur, hors de cet arbre)

Ajout d'une route **WebSocket** sur la même app et le même conteneur (`live_app_fast.py` d'alors) :
le pipeline GPU est **le même** (`Sam3Live._live_sync`, un seul verrou GPU), seul le transport change.

- URL : `wss://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run/live/ws`
  (en local : `ws://<host>/live/ws`).
- Entrée, un message **texte** JSON par frame — mêmes champs que `POST /live`, plus `id` :
  `{"id": 7, "image_b64": "...", "prompts": ["person", "door"], "max_objects": 4, "work_edge": 640}`
  (`prompts`, `max_objects`, `work_edge`, `batch_prompts`, `edge` restent optionnels et gardent les
  mêmes défauts/bornes que `POST /live` ; `id` est **obligatoire**, il apparie la réponse à la frame).
- Sortie, un message **texte** JSON par frame traitée, **même forme que `POST /live`** plus `id` :
  `{"id": 7, "width": 512, "height": 1138, "elapsed_ms": 118, "objects": [{"label": "person", "score": 0.91, "box": [x1,y1,x2,y2], "mask_b64": "..."}]}`
  (le champ `fast` de `/live` est conservé tel quel).
- Erreur : `{"id": 7, "error": {"status": 400|500, "detail": "..."}}` — `id` à `-1` s'il est illisible
  (JSON cassé, `id` absent ou non entier, message binaire). **Une erreur de frame ne ferme jamais la
  connexion** ; les codes sont ceux de `POST /live` (400 décodage/prompt, 500 interne), détail borné à 300 car.
- Une frame à la fois **par connexion** (la boucle attend la réponse avant de lire la suivante) ; le
  verrou GPU global reste la seule file entre connexions.

### Mesure

Même région (backend eu-west, client en France), même frame, mêmes prompts :

| Transport | Aller-retour mesuré |
|---|---:|
| `POST /live` (HTTP) | **81,4 ms** |
| `WS /live/ws` | **50,3 ms** |

**~31 ms gagnés par frame** (handshake, en-têtes et ouverture de connexion supprimés) ; le temps serveur
(`elapsed_ms`, 83–125 ms avec `FAST_BATCH_PROMPTS=1` + `FAST_EDGE=512`) est inchangé — le WebSocket
n'accélère pas le GPU, il retire la couche HTTP.

### Banc

```bash
python3 scripts/bench_live_ws.py --url wss://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run \
    --image /tmp/frame.jpg --runs 6 --prompts person,door
```

Une seule connexion, `--runs` frames enchaînées ; affiche par frame l'`id` reçu, l'`elapsed_ms` serveur,
l'aller-retour mesuré, puis **moyenne et p95**. `--image` accepte un fichier binaire ou un JSON portant
`image_b64` ; `--image-b64` passe la chaîne directement. `--url` est **obligatoire** : le banc n'a aucune
URL par défaut et n'est jamais lancé automatiquement (il réveille un GPU).

Contrôle du contrat sans GPU ni réseau : `python3 scripts/test_live_ws.py` (24 vérifications — forme de
réponse identique à `POST /live`, `id` corrélé, erreurs sans fermeture de connexion).

### Hors périmètre (fait ailleurs)

Déploiement de la route (`bash scripts/deploy-live-fast.sh`) et mesure réelle contre la production :
non joués ici — aucune GPU allumée, aucun `modal deploy`, aucun appel réseau depuis ce dépôt.
