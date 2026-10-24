# Architecture — sam3-vision

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
| `app/src/api.ts` | client HTTP unique (timeout 60 s, erreurs typées) |
| `backend/modal_app.py` | app Modal : routes, inférence SAM 3.1, appel LLM |
| `scripts/test_api.py` | test E2E du contrat (stdlib) |
| `scripts/deploy_backend.sh` | secret + déploiement + smoke test |
| `scripts/dev_qr.sh` | serveur Expo tunnel pour Expo Go |