# AUDIT — sam3-vision-app (2026-10-24)

Périmètre : backend Modal SAM 3.1 + app Expo. Audit statique, tests unitaires, tests E2E contre le
backend déployé (`…sam3service-web.us-east.modal.run`), cas d'erreur du contrat, latence.
Aucun déploiement, aucune modification de secret, `app/app.json` et `app/android/` non touchés.

## 1. Limites (fichiers < 500 lignes, fonctions < 50 lignes)

**Aucun dépassement.** Vérifié par `wc -l` sur tout le dépôt (hors `node_modules`, `.expo`, `android`)
et par analyse `ast` des fonctions Python. Plus gros fichiers :

| Fichier | Lignes | Verdict |
|---|---|---|
| `scripts/test_api.py` | 464 | OK — **au-delà de 460, découper** (PNG/masques → `scripts/png_utils.py`) |
| `backend/segmentation.py` | 206 | OK |
| `backend/llm.py` | 152 | OK |
| `backend/modal_app.py` | 157 (après correctif) | OK |
| `scripts/test_units.py` | 260 (ajouté) | OK |
| `app/src/api.ts` | 126 | OK |

Plus longue fonction mesurée : `test_http_contract` (≈ 35 lignes), toutes les autres < 30.
`app/tools/check-limits.py` passe (`OK`), mais **il ne couvre que `.ts/.tsx`** — les fonctions Python
n'étaient vérifiées par personne ; le contrôle Python a été fait à la main pendant l'audit (ast).

## 2. Tests exécutés

| Commande | Verdict |
|---|---|
| `python3 -m py_compile backend/*.py` | OK |
| `python3 -m py_compile scripts/*.py` | OK |
| `bash -n scripts/*.sh` (5 scripts) | OK |
| `npx tsc --noEmit` (dans `app/`) | OK, 0 erreur |
| `python3 app/tools/check-limits.py` | OK |
| `python3 -c "import modal_app"` (venv-modal) | OK, sans token |
| `python3 scripts/test_units.py` (ajouté) | **57 OK, 0 KO** |
| `test_api.py --only health` | PASS (0,3 s) `model=sam3.1 device=cuda` |
| `test_api.py --only segment,segment-prompts,bad-image --image /tmp/street.png` | PASS 3/3 (2048×824, 17 objets sans prompts, 6 avec ; masques validés PNG 8 bits gris à la taille exacte) |
| `test_api.py --only contract-errors` | **1 KO** — voir §3 (corrigé dans le code, pas encore déployé) |
| `analyze`, `ask` | non tentés comme des succès : **500 attendu** (clé LLM placeholder) |

`/analyze` et `/ask` renvoient `500 {"detail":"internal error: LLM call failed: Error code: 401 -
{'error': {'message': 'Missing Authentication header', 'code': 401}}"}` : la clé du secret `sam3-llm`
est une valeur placeholder refusée par le fournisseur. **Conforme à l'état attendu, non corrigé.**
Intéressant : `settings_from_env()` passe (clé non vide), l'échec vient du fournisseur — le 500 est
bien le chemin « erreur LLM » et pas un 400 déguisé.

## 3. Écarts du contrat (sondés sur le backend réel, 23 cas)

Tous les cas invalides attendus en 400 l'obtiennent : base64 invalide, base64 correct mais non-image,
`image_b64` absent/trop court/non-string, `prompts = []`, `prompts = ['']`, `prompts` non-liste
(string, dict), `prompts` non-chaînes (`[1,2]`), > 16 prompts, prompt > 64 car., `question` vide,
`history` avec rôle invalide, JSON malformé, corps vide, corps = liste. `/analyze` et `/ask` avec une
image invalide renvoient bien **400** (validation avant LLM), pas 500.

Un seul écart, côté serveur :

- **Réémission du payload dans `detail`.** Corps JSON valide-but-malformé contenant l'image
  (`POST /segment` avec `["<base64>"]`) → `400` dont le `detail` contient **tout le base64** :
  **2 380 585 octets** mesurés. Le contrat est respecté sur le code, mais la réponse est inutilisable
  côté client (elle sature le log et le lien) et fuit l'image envoyée dans les journaux intermédiaires.
  **Corrigé** : `_validation_detail()` dans `backend/modal_app.py` ne garde que `chemin-du-champ: message`,
  borné à 300 caractères ; le `detail` des 500 est borné pareil. Test de non-régression ajouté
  (`contract-errors`, et le cas « echo borné < 1 Ko » dans `test_units.py`).
  ⚠️ **À redéployer** (`scripts/deploy_backend.sh`) — non fait ici. Avant redéploiement,
  `test_api.py --only contract-errors` échoue sur cette seule assertion ; après, il doit passer.

Hors SPEC (comportement par défaut acceptable, documenté dans ARCHITECTURE.md) : `405` sur mauvaise
méthode, `404` sur route inconnue, et **400** (et non 422) pour les erreurs de validation de corps —
choix délibéré du handler, cohérent avec « 400 pour requête invalide, 500 sinon ».

## 4. Duplications

Corrigées : aucune correction n'était nécessaire côté `app/src` (client API unique, helpers déjà
partagés : `base64`, `color`, `mask`, `url`, `errors`, `format`, `config` ; `fetch` et le timer
n'existent que dans `api.ts`).

Relevées, **non corrigées** (hors périmètre d'autorisation : `scripts/*.sh`, `app/tools`, `app/app.json`) :

1. `scripts/rotate_llm_secret.sh` recopie `scripts/deploy_backend.sh` : en-tête `MODAL_BIN` /
   `MODAL_PROFILE` / `SECRET_NAME` / `BASE_URL_DEFAULT`, les helpers `log()` et `die()`, et surtout le
   bloc « mktemp chmod 600 → heredoc python JSON → `modal secret create --from-json --force` → rm ».
   Deux implémentations d'un chemin sensible aux secrets : à remonter dans `scripts/lib-env.sh`
   (`sync_secret` + `log`/`die` + par défauts Modal), les deux scripts ne gardant que l'appel.
2. `app/src/theme.ts` : `MASK_COLORS` réécrit les littéraux déjà dans `colors`
   (`#4F8EF7` = `colors.primary`, `#F2555A` = `colors.danger`, `#3ED598` = `colors.success`).
   Sûr mais cosmétique — non fait pour ne pas toucher un fichier lu par le pair en parallèle sur l'app.
3. La valeur `1024` apparaît trois fois avec trois sens distincts : `app/src/utils/image.ts`
   (`MAX_IMAGE_EDGE`, envoi client), `backend/segmentation.py` (`SEG_MAX_EDGE`, résolution de travail),
   `backend/imaging.py` (`LLM_MAX_EDGE`, JPEG du LLM). Ce n'est pas une duplication de code, mais une
   **couple contractuel implicite** : si l'envoi dépasse la borne serveur, les masques grossissent.
   À documenter plutôt qu'à factoriser (fait : ARCHITECTURE.md § bornes).
4. `backend/schemas.py` vs `app/src/types.ts` + `parse.ts` : le même contrat écrit deux fois — inhérent
   à une frontière HTTP, aucune dérive constatée (champs, types et formes vérifiés par `test_api.py`).
5. `scripts/test_api.py` est à 464 lignes : la prochaine fonction ajoutée doit déclencher un découpage.

## 5. Documentations : écarts relevés (non corrigés, hors périmètre d'écriture)

- **`backend/README.md` contredit la règle secrets** : il montre
  `modal secret create sam3-llm LLM_API_KEY=<clé>` — la clé en **argument de commande** (historique
  shell + `ps`), alors que SPEC.md et ARCHITECTURE.md imposent `--from-json` « jamais en argument ».
  Le code, lui, est bon : `deploy_backend.sh` passe bien par un fichier `chmod 600` + `--from-json`.
  → réécrire le bloc « Secret Modal » de `backend/README.md` (ou le remplacer par un renvoi vers
  `scripts/rotate_llm_secret.sh`).
- **README racine** : parle de `container_idle_timeout`, paramètre Modal aujourd'hui nommé
  `scaledown_window` (celui utilisé dans `modal_app.py`) ; annonce un cold start « 60-120 s » qui n'a
  pas pu être confirmé (§7) ; la section « Test » ne cite ni `contract-errors` ni `test_units.py`.
- **`app/NOTES.md`** : les tests du client (parsing des réponses, `api.ts` contre un serveur local,
  colorisation de masque vérifiée pixel à pixel) sont « dans `/tmp`, **non versionnés** » — exactement
  le trou que comble `scripts/test_units.py` côté backend. À versionner (Node + un runner léger) pour
  qu'un CI puisse les rejouer.
- **`scripts/README.md`** ne couvre que le build Android : ni `test_api.py`, ni `test_units.py`,
  ni `deploy_backend.sh` n'y sont documentés.

## 6. Corrections faites / restantes

Faites (code) :

- `backend/modal_app.py` : `_validation_detail()` (plus de réémission du payload), `detail` des 500
  borné à 300 caractères.
- `scripts/test_api.py` : groupe `contract-errors` (5 corps invalides + 2 corps bruts, contrôle de la
  taille de la réponse) et helper `http_raw`.
- `scripts/test_units.py` : **créé** — 57 assertions sans GPU (décodage/resize, masques PNG 0/255 à la
  taille exacte, `normalize_prompts`, `model_concept`, bornage des `box`, `extract_json`, historique
  filtré/tronqué, et la vraie app FastAPI pilotée en ASGI : 400/405/404/500 + payload SPEC).
  NOTES.md décrit ces tests depuis longtemps ; **aucun n'était commité**, c'était le principal trou.
- `docs/ARCHITECTURE.md` : tableau des modules complété (imaging, schemas, segmentation, llm, parse,
  mask, test_units), section « détails vérifiés » (modèle non gated `unsloth/sam3.1` et l'absence de
  classe `Sam3_1`, `torchvision` requis par `Sam3ImageProcessor`, ordre `.env()` puis `add_local_dir()`,
  concurrence 1 car `max_inputs>1` interdit, `vision_embeds` réutilisé, 1008/1024/nearest, bornes,
  cycle de vie conteneur, piège `from __future__ import annotations`) et table des codes d'erreur.

Restent à faire (hors périmètre ou nécessitant une action extérieure) :

1. **Redéployer le backend** pour activer `_validation_detail` — `bash scripts/deploy_backend.sh`.
2. **Secret LLM** : mettre une vraie clé (`rotate_llm_secret.sh`) puis relancer
   `test_api.py --only analyze,ask` ; vérifier au passage que `max_tokens` est accepté par le modèle
   choisi (`llm.py` envoie `max_tokens`, certains modèles récents n'acceptent que `max_completion_tokens`).
3. **Dédupliquer les scripts shell** du secret (§4.1) — owner : scripts, pas backend/app.
4. **Étendre `app/tools/check-limits.py` au Python** (ast), ou remplacer par un unique garde de dépôt.
5. Choix à trancher : `MAX_OBJECTS = 24` coupe silencieusement la liste — soit renvoyer un champ
   d'avertissement, soit le documenter dans SPEC.md (fait dans ARCHITECTURE.md, pas dans SPEC.md).
6. Le `detail` des 500 répercute le message de la SDK (`llm.py` → message du fournisseur). Nécessaire
   pour le diagnostic, mais à garder à l'œil si un fournisseur fait remonter des en-têtes dans ses erreurs.

## 7. Risques et points à surveiller

- **Cold start non reproductible** : mesuré une fois (35,8 s pour `/health`, 5,7 s pour le
  `/segment` suivant) et pas du tout la fois d'après, après 567 s d'inactivité — le conteneur n'est
  donc pas descendu. Conséquence : GPU facturé en continu, et `/analyze` à froid peut dépasser les
  60 s du timeout client imposé par la SPEC (l'app répond par l'écran « Réessayer », ce qui est
  correct, mais le ressenti dépend du réglage `scaledown_window` / `keep_warm`).
- `Sam3Model.from_pretrained` réel, forwards bf16 et empreinte mémoire sur A10G : observés uniquement
  dans le conteneur déployé (et il répond : `/segment` renvoie 200 avec 17 objets sur 2048×824).
- APK / `app/app.json` : hors périmètre (pair en parallèle).
- Charge concurrente : non testée — concurrence forcée à 1 par Modal, un second appel pendant un
  premier attend derrière le verrou GPU.

## 8. Latence (chiffres détaillés dans `docs/PERF.md`)

À chaud, sur `/tmp/street.png` (2048×824) : `/segment` sans prompts **1,95 s** médian sur 3 mesures
(17 objets, 61,9 Ko de réponse), avec 2 prompts **1,60 s** (6 objets, 10,1 Ko), `/health` **0,30 s**,
400 de validation **0,30 s**. Le décodage d'entrée coûte ~1 s, l'encodeur vision une seule fois pour
tous les prompts : c'est ce qui explique que 8 concepts et 2 concepts soient à ~0,3 s d'écart.
