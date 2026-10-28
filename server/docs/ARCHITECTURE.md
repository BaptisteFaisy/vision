# Architecture — sam3-vision

> Périmètre : ère Expo + `sam3-vision` (legacy). Le backend déployé aujourd'hui est
> `../backend/live_fast/` (voir `LIVE-FAST.md`).

```
 App Expo (Android)                    Modal — app `sam3-vision` (GPU A10G)
 ──────────────────                    ─────────────────────────────────────
 expo-camera  ──photo JPEG──►  POST /analyze {image_b64}
   │                                │
   │                                ├─ 1. Sam3Processor + Sam3Model (unsloth/sam3.1)
   │                                │      prompts=null → liste de concepts générique
   │                                │      → objets {label, score, box}
   │                                │      → masque numpy → PNG 8 bits gris → base64
   │                                ├─ 2. LLM vision (API OpenAI-like, secret `sam3-llm`)
   │                                │      prompt = image + objets segmentés
   │                                │      → description + tags
   │                                ▼
   │           ◄── {description, tags, objects[{label,score,box,mask_b64}]}
   │
   ├─ overlays PNG (opacité 0,45, couleurs tournantes) + description
   │
   └─ chat ──► POST /ask {image_b64, question, history}
                    └─ même LLM vision, historique client rejoué
                    ◄── {answer}

 Persistance : aucune. URL backend stockée localement (AsyncStorage), pas de clé API dans l'app.
```

## Choix techniques

- **`unsloth/sam3.1` plutôt que `facebook/sam3.1`** : miroir **non gated** — pas de formulaire
  d'accès ni de jeton HF à gérer côté serveur, donc déploiement reproductible par n'importe qui.
  Chargé via `transformers` (`Sam3Model` / `Sam3Processor`, à confirmer sur la version installée).
- **Masques PNG base64, pas de RLE** : décodage immédiat par `<Image>` côté React Native, sans
  bibliothèque de décompression ni JS de décodage. Coût : réponse plus lourde (~centaines de Ko) ;
  atténué par le Wi-Fi et un timeout client explicite de 60 s.
- **Secret Modal `sam3-llm`** : les trois variables `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`
  sont injectées au conteneur ; aucune clé dans le dépôt, aucun `.env` commité. Le script de
  déploiement pousse les valeurs via `--from-json` (jamais en argument de commande).
- **FastAPI derrière `@modal.asgi_app()`** : un seul endpoint HTTPS stable, routage et validation
  gérés par FastAPI, chargement du modèle une fois par conteneur (`@modal.enter()`).
- **App sans état** : l'historique du chat reste dans l'app et est renvoyé à chaque `/ask` ;
  le backend peut donc scaler horizontalement sans stockage partagé.

## Routes

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| GET | `/health` | — | `{"ok": true, "model": "sam3.1", "device": "cuda"}` |
| POST | `/segment` | `{"image_b64", "prompts": [str] \| null}` | `{"width", "height", "objects": [{label, score, box[4], mask_b64}]}` |
| POST | `/analyze` | `{"image_b64"}` | `{"description", "tags": [str], "objects": [...]}` |
| POST | `/ask` | `{"image_b64", "question", "history": [{role, content}] \| null}` | `{"answer"}` |

Erreurs : `400 {"detail": "..."}` (image illisible), `500` sinon. Images en base64 brut, sans préfixe `data:`.
`mask_b64` = PNG 8 bits niveaux de gris, dimensions exactes `width`×`height`, 255 = objet.

## Modules

| Chemin | Rôle |
|---|---|
| `legacy-expo-app/src/api.ts` | client HTTP unique (timeout 60 s, erreurs typées) |
| `legacy-expo-app/src/parse.ts` | validation des réponses (échec explicite si le contrat change) |
| `legacy-expo-app/src/utils/mask.ts` | masque PNG gris → calque RGBA colorisé (UPNG, réduit à 512 px) |
| `backend/modal_app.py` | app Modal : image, routes FastAPI, mapping erreurs → codes HTTP |
| `backend/segmentation.py` | chargement SAM 3.1, prompts, concepts FR→EN, tri/découpe des objets |
| `backend/imaging.py` | base64 → PIL, resize, encodage masque PNG, data URL JPEG pour le LLM |
| `backend/llm.py` | client OpenAI-compatible, prompts description/question, extraction JSON |
| `backend/schemas.py` | modèles Pydantic des corps de requêtes (SPEC) |
| `scripts/test_api.py` | test E2E du contrat contre un backend déployé (stdlib) |
| `scripts/test_units.py` | tests unitaires sans GPU : imagerie, masques, prompts, plomberie HTTP |
| `scripts/deploy_backend.sh` | secret + déploiement + smoke test |
| `scripts/dev_qr.sh` | serveur Expo tunnel pour Expo Go |

## Détails d'implémentation vérifiés

- **Modèle** : `unsloth/sam3.1`, miroir **non gated** de `facebook/sam3.1` (pas de jeton HF).
  `config.json` ⇒ `architectures = ["Sam3Model"]` ; il n'existe **aucune** classe `Sam3_1*` —
  « 3.1 » n'est qu'un jeu de poids. Chargé par `transformers` 5.20.2 (`Sam3Model` + `Sam3Processor`).
- **`torchvision` est requis** : `Sam3ImageProcessor` en dépend à l'import — sans lui,
  `from_pretrained` échoue même si `transformers` est présent (`requirements.txt` : 0.29.1).
- **Image Modal** : `.env(...)` **avant** `.add_local_dir(...)` — l'inverse casse au build
  (les étapes sont ordonnées). Les modules sont copiés dans `/root/backend_src` et exposés par
  `PYTHONPATH=/root:/root/backend_src`, d'où les imports plats (`import imaging`).
- **Concurrence = 1** : `@app.cls(max_inputs>1)` est refusé par Modal 1.6.2
  (`InvalidError: Only max_inputs=1 is currently supported`). Un `threading.Lock` sérialise
  l'usage du GPU ; les pipelines bloquants tournent dans `asyncio.to_thread` pour ne pas figer l'ASGI.
- **Pipeline SAM** : l'encodeur image ne passe qu'une fois (`vision_embeds` réutilisé pour les N
  prompts). Processeur = resize fixe 1008×1008 ; la segmentation est lancée sur une copie ≤ 1024 px
  puis les masques sont remontés en `nearest` à la taille d'origine (mémoire + latence).
- **Bornes** : `MAX_PROMPTS` 16, `MAX_PROMPT_CHARS` 64, `MAX_OBJECTS` 24 (tri par score décroissant,
  coupe silencieuse au-delà), `SCORE_THRESHOLD` 0.35, `MASK_THRESHOLD` 0.5.
- **Concepts français** : l'encodeur texte de SAM 3.1 est un CLIP **anglais** — `CONCEPT_ALIASES`
  traduit pour le modèle, le label renvoyé reste celui saisi par le client.
- **Cycle de vie conteneur** : `timeout=600`, `startup_timeout=900` (chargement du checkpoint),
  `scaledown_window=300` (5 min d'inactivité → conteneur froid au prochain appel).
- **Piège FastAPI** : pas de `from __future__ import annotations` dans `modal_app.py` — sinon les
  modèles importés dans `web()` ne sont pas résolus et le corps devient un paramètre de query (400).

## Erreurs et codes réellement renvoyés

| Cas | Code | Origine |
|---|---|---|
| base64 invalide, octets non-image, image vide | 400 | `ImageDecodeError` → `HTTPException` |
| `prompts` vide/non-liste/éléments non-chaînes/trop long/trop nombreux | 400 | `PromptError` ou Pydantic |
| corps JSON malformé, champ manquant, type de corps invalide | 400 | handler `RequestValidationError` (FastAPI renverrait 422 sans lui) |
| LLM absent/injoignable, erreur GPU | 500 | `except Exception` générique, `detail` borné à 300 car. |
| mauvaise méthode / route inconnue | 405 / 404 | comportement FastAPI, hors SPEC |

Le `detail` d'erreur de validation est **résumé** (`_validation_detail` : chemin du champ + message) :
sans ça, un corps malformé contenant l'image renvoyait le base64 entier au client (2,4 Mo mesurés).