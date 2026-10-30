# LATENCE-M2M3-SERVEUR-INFRA — plan de déploiement groupé UNIQUE

M2/M3 du chantier latence : tous les changements serveur partent en **un seul**
`modal deploy` (chaque deploy = ~1 boot facturé + invalidation du snapshot +
fenêtre 404/500 pendant la bascule). Spécification seule : **aucun code,
aucun deploy, aucun ordre GPU** dans ce document — l'exécution est pour le
coordinateur, hors recette téléphone annoncée.

Références vérifiées sur `origin/master` `c3cc4fe2` (2026-10-30). Fichier
déployé : `server/backend/live_fast/live_app_fast.py` via
`server/scripts/deploy-live-fast.sh` — **jamais** `backend/live_app.py`
(version lente, 205-241 ms) ni `backend/live_app_fast.py` (ancien chemin).

## 1. Règles du deploy groupé (non négociables)

1. **Un seul deploy** pour les § 2–6 : le script est idempotent, les leviers y
   sont posés (`FAST_*` dans `deploy_once`), tout part ensemble.
2. **Jamais pendant une recette téléphone annoncée** (règle workspace
   2026-10-29) : vérifier la mailbox
   (`~/Bureau/.scratch/broadcast/BOITE-USER.md`) avant, annoncer le deploy
   (prise + libération, signé) en append.
3. **Jamais depuis un arbre stale ou sale** : le script refuse (garde
   anti-stale 27/10, `DEPLOY_ALLOW_STALE=1` interdit sans raison écrite).
4. **Coût : ~0,02 $ par boot** (A100-40GB EU ≈ 2,415 $/h → ~30 s de boot ;
   repère code : sortie ratée ≤ 0,04 $ pour 60 s,
   `live_fast/live_app_fast.py`, commentaire `SCALEDOWN`).
5. **Fenêtre 404/500** : entre l'arrêt de l'ancien conteneur et la prise de
   trafic du neuf (boot 18–32 s, « fenêtre de mort » dans le script) :
   `/health` 500/injoignable, retry transparent du script ; un `app stop`
   franc expose un 404 jusqu'au redeploy. Téléphone prévenu via la mailbox.
6. Après tout deploy : `sam3-gpu guard` (une seule app GPU, § 7).

## 2. `WORK_EDGE` 768 → 512 (+ vérification vieux clients)

- Cible : `WORK_EDGE_DEFAULT = 768` → `512`, aux **trois** sites (un seul sert
  le déploiement, les trois doivent dire la même chose) :
  `server/backend/live_fast/service.py:37` (déployé),
  `server/backend/live_app_fast.py:112` et `server/backend/live_app.py:45`.
  Bornes `256..1024` et clamp inchangés (jamais de 400).
- Pourquoi c'est sûr : le défaut ne s'applique que quand le knob est **absent**.
  Tous les clients actuels l'envoient toujours à 512 (`liveFrameJson` l'ajoute
  sans condition, `core/.../net/Json.kt:327` ;
  `LiveSession.kt:211` `DEFAULT_WORK_EDGE = 512`,
  `LivePipeline.kt:122` `WORK_EDGE = 512` ; contrat `docs/LIVE-SPEC.md:90,219`).
  Le changement n'affecte donc que les appelants sans knob.
- **Vérification vieux clients (avant le deploy)** : lister les APK distribués
  et les sondes qui omettent `work_edge` (`server/scripts/probe_*.py`,
  `bench_live*.py`, appels curl) ; pour chacun, confirmer qualité acceptable à
  512 (même valeur que les clients actuels : le risque est nul par
  construction, la vérification est une formalité tracée, pas une supposition).
  Si un vieil APK omet le knob, il passe de 768 à 512 : plus rapide, qualité
  égale à celle des clients actuels — acceptable, à noter dans l'annonce.

## 3. Knob `crop` ROI (neuf) + spec `mask_edge` (figé)

Aucun `crop` côté serveur aujourd'hui (grep vide sur `server/backend/`) : le
knob est neuf, conçu pour l'en-tête binaire M1 autant que pour le JSON.

- **Contrat `roi`** : `[x, y, w, h]` normalisés 0..1 (coin haut-gauche +
  taille, relatifs à la frame décodée), appliqué **avant** le redimensionnement
  `work_edge`. Règles : absent = pas de crop (comportement actuel) ; valeurs
  hors bornes ou zone dégénérée = clampées, **jamais** de 400 (même motif que
  `work_edge`/`mask_edge`, `schemas.py:19`) ; aire < 1 % = ignoré (bruit).
- **Réponse** : `box` exprimées dans la frame d'origine (le serveur re-projette,
  le client ne change pas de repère) ; `width`/`height` = frame d'origine.
- **Client** : envoi quand l'app connaît une zone d'intérêt (suivi, tap) ;
  sinon absent. Activation par flag, après deploy (même règle que M1 § 5).
- **`mask_edge` spec (figé, déjà implémenté)** : `mask_format: "rle"` +
  `mask_edge` (défaut serveur 256, borné 64..1024,
  `server/backend/live_fast/imaging.py:174-176,184-195`,
  `segmentation.py:149-168,174-187`) ; grille réduite + `mask_scale` par objet
  (ratio grille/frame) ; client déjà à `rle`/256
  (`app/.../LiveMaskTransport.kt:24,31`, contrat `docs/MASK-DECODE.md`).
  Le deploy fige ce contrat : toute valeur reçue est clampée, toute valeur
  inconnue de `mask_format` suit le chemin historique (jamais de 400).

## 4. Restauration poids-dans-l'image + snapshot

Le réveil rapide tient à deux mécanismes déjà en place, à **vérifier** (pas à
recoder) dans le deploy groupé :

- **Poids au build** : `WEIGHTS_BUILD` (`snapshot_download` de
  `unsloth/sam3.1` vers `/opt/models/sam3.1`) dans l'image Modal
  (`live_fast/live_app_fast.py`), lus avec `local_files_only`
  (`weights.py`, `MODEL_DIR`/`local_only`) — fini le re-téléchargement des
  ~1,7 Go à chaque réveil.
- **Snapshot mémoire** : `FAST_SNAPSHOT=1` → `enable_memory_snapshot`
  (`fast_core.py:69-70`) ; phase CPU snapshottée (`load_cpu`, aucun état CUDA)
  puis phase GPU après restauration (`load_container`). Réveil 2–7 s au lieu
  de 25–38 s.
- **Vérification post-deploy** (dans `check_once` du script) : `/health`
  montre `model` contenant `fast`, `restored: bool`, `load_breakdown`
  (`age_conteneur_s`, `poids_cpu_1er_demarrage_s`, `gpu_s`), leviers
  `batch_prompts/cuda_graph/edge:512/snapshot/attn:sdpa`, `detr_graph: false`
  (ÉTEINT : crash prod, voir en-tête du script), `vision_accel: ok`.

## 5. Scaledown (extinction rapide, éteinte = 0 €)

- `FAST_SCALEDOWN=60` (posé par le script ; clamp 5..60 dans le shim déployé) :
  le conteneur s'éteint 60 s après la dernière frame. Voie normale
  d'extinction : `POST /release` depuis l'app (~1 s).
- `FAST_MIN_CONTAINERS=0` (défaut, clamp 0..1) : pas de warm pool (~500 $/mois
  A100 pour `1`). `1` uniquement sur demande explicite du user ; **jamais
  plus de 1** (`max_containers=1` : un seul GPU, jamais deux).
- `scaledown_window=SCALEDOWN` + `timeout=3600` + `startup_timeout=1200`
  (torch.compile + warm-up) : inchangés, rappelés pour que le deploy groupé
  ne les perde pas.

## 6. Guard / watchdog `--dry-run` + décision KEEP

- **Avant le deploy** (lecture seule, zéro risque) :
  `sam3-gpu-watchdog.sh --dry-run` (+ `WATCHDOG_FAKE_GPU=<min>` /
  `WATCHDOG_FAKE_PHONE=fullscreen|idle|absent|busy|error` pour simuler) et
  `bash server/scripts/modal-one-gpu.sh --status`. Référence des commandes :
  `~/Bureau/ref/sam3-gpu.md` (hors dépôt).
- **Décision KEEP** : le guard ne garde que `sam3-eu-a100` (défaut
  `SAM3_KEEP`, `modal-one-gpu.sh` : `KEEP=sam3-live-eu` pour garder autre
  chose). Tout second conteneur/app GPU actif au moment du deploy est arrêté
  par `sam3-gpu guard` **après** vérification du trafic neuf — jamais avant
  (ne pas tuer l'ancien qui sert encore).
- **Bail watchdog** : si le deploy a besoin de la carte chaude sans téléphone
  (bancs `bench_live*.py`), `touch /tmp/sam3-gpu.keep` (120 min, plafond
  10 min inclus), `rm` après. Le guard anti-2e-GPU reste actif sous bail.
- **Après le deploy** : `bash server/scripts/modal-one-gpu.sh` (guard réel),
  puis `--status` pour tracer l'état final dans l'annonce mailbox.

## 7. Commandes exactes

Pré-vol (depuis la machine de deploy, arbre sur `origin/master` sain) :

```bash
git fetch origin && git status --porcelain   # vide exigé, sinon commit d'abord
bash server/scripts/deploy-live-fast.sh --no-deploy   # vérifie sans réveiller
bash server/scripts/modal-one-gpu.sh --status         # état GPU avant
```

Deploy groupé UNIQUE (les § 2–6 partent ensemble) :

```bash
bash server/scripts/deploy-live-fast.sh
```

Le script : garde anti-stale → `modal deploy backend/live_fast/live_app_fast.py`
(leviers `FAST_BATCH_PROMPTS=1 FAST_EDGE=512 FAST_CUDA_GRAPH=1 FAST_ATTN=sdpa
FAST_SCALEDOWN=60 FAST_SNAPSHOT=1 FAST_MODEL_DIR=/opt/models/sam3.1
FAST_TIMING=1`, app `sam3-eu-a100`, A100-40GB, eu/eu-west) → attente de la
nouvelle version (6×10 s) → à défaut `modal app stop` + redeploy (12×10 s).

Post-deploy :

```bash
bash server/scripts/modal-one-gpu.sh          # guard : une seule app GPU
bash server/scripts/modal-one-gpu.sh --status # état final, à copier en mailbox
```

Rollback : le script est idempotent — un redeploy depuis un `master` sain
remet l'état précédent (même fenêtre 404/500, même coût ~0,02 $). Dernier
recours (trafic bloqué sur l'ancien conteneur) : `modal app stop sam3-eu-a100`
puis redeploy — c'est déjà ce que fait le script tout seul, ne le faire à la
main qu'en dehors du script. `off --hard` (404 durable) uniquement sur demande
dure explicite du user.

## 8. Interdits (rappel, tous déjà en vigueur)

- Aucun deploy pendant une recette téléphone annoncée (§ 1.2).
- Aucun second conteneur GPU : `min_containers ≤ 1`, `max_containers = 1`,
  guard après chaque deploy (§ 1.6).
- Aucun deploy d'un code plus vieux que le déploiement actif (règle
  test-master-seul : le deploy groupé part du HEAD frais).
- Aucune exécution depuis ce document : les commandes § 7 sont pour le
  coordinateur, annoncées en mailbox, hors recette.
