# SPEC — sam3-vision-app

App Android React Native (Expo) : photo → SAM 3.1 sur Modal (segmentation) → LLM vision (description) → questions/réponses sur la photo.

## Répertoires (racine = ~/Bureau/sam3-vision-app)

- `backend/` — app Modal (Python)
- `app/` — app Expo React Native (TypeScript)
- `scripts/` — tests, déploiement, build APK
- `docs/` — spec et notes

## Contrat HTTP (source de vérité, aucun écart toléré)

Serveur : FastAPI ASGI servi par `@modal.asgi_app()` (app Modal `sam3-vision`).
Toutes les routes sont `POST` sauf `/health` (`GET`). Content-Type JSON. Images en base64 (PNG ou JPEG, sans préfixe data:).

1. `GET /health` → `{"ok": true, "model": "sam3.1", "device": "cuda"}`
2. `POST /segment`
   - corps : `{"image_b64": str, "prompts": [str] | null}`
   - `prompts == null` ⇒ le serveur choisit lui-même la liste de concepts (détection large + labels génériques).
   - réponse : `{"width": int, "height": int, "objects": [{"label": str, "score": float, "box": [x1,y1,x2,y2], "mask_b64": str}]}`
   - `mask_b64` = PNG 8 bits niveaux de gris, taille exacte `width`×`height`, 255 = objet.
   - `objects` est trié par `score` décroissant puis **coupé silencieusement à 24 entrées**
     (`MAX_OBJECTS = 24`, `backend/segmentation.py`). Au-delà, les objets de score plus faible
     ne figurent pas dans la réponse : **aucun champ d'avertissement, aucun total, aucun
     `truncated`** n'est renvoyé (choix assumé — le champ n'est pas ajouté). Le client ne peut
     donc pas distinguer « 24 objets dans l'image » de « 24 objets retenus sur N » ; un usage
     qui exige l'exhaustivité doit demander des `prompts` ciblés pour faire remonter les objets
     voulus dans le top 24.
3. `POST /analyze`
   - corps : `{"image_b64": str}`
   - réponse : `{"description": str, "tags": [str], "objects": [...]}` (mêmes objets que `/segment`, plus la description LLM qui cite explicitement les objets segmentés).
4. `POST /ask`
   - corps : `{"image_b64": str, "question": str, "history": [{"role": "user"|"assistant", "content": str}] | null}`
   - réponse : `{"answer": str}`

Erreurs : HTTP 400 `{"detail": "..."}` pour image illisible, HTTP 500 sinon.

## Backend Modal

- Modèle : `unsloth/sam3.1` (miroir NON gated de `facebook/sam3.1`), chargé via `transformers` (`Sam3Model` / `Sam3Processor` — vérifier la classe réellement exposée par la version de transformers installée et le champ `architectures` du config.json).
- GPU : `A10G` par défaut ; chargement en `@modal.enter()` (image build : `transformers`, `torch`, `accelerate`, `Pillow`, `numpy`, `fastapi[standard]`).
- Masques encodés en PNG base64 (numpy → PIL), pas de RLE.
- LLM : API compatible OpenAI (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`). Vision obligatoire. Défaut : OpenRouter + modèle vision rapide.
- Secrets Modal : `sam3-llm` (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`). Aucune clé en dur, aucun fichier `.env` commité. Jamais de clé en argument de commande : passage par `--from-json` (`scripts/lib-env.sh` → `sync_secret_from_env`).
- Bornes : `MAX_PROMPTS` 16, `MAX_PROMPT_CHARS` 64, `MAX_OBJECTS` 24 (tri par score décroissant puis **coupe silencieuse**, sans champ d'avertissement — voir le détail au § Contrat HTTP, `/segment`).
- Fichiers < 500 lignes, fonctions < 50 lignes, pas de duplication (helpers partagés : décodage image, encodage masque, appel LLM).

## App Expo

- Expo SDK stable, TypeScript, Android.
- Écrans : `Camera` (expo-camera, bouton capture) → `Result` (photo + overlays + description + chat).
- `expo-image-picker` en secours (galerie).
- URL du backend modifiable dans un écran Réglages, persistée (`@react-native-async-storage/async-storage`), sinon valeur par défaut lue dans `app.json` → `expo.extra.apiUrl`.
- Aucune clé API dans l'app : tout passe par le backend.
- Overlays de masques : images PNG base64 superposées, opacité ~0.45, couleurs tournantes ; toggle « masques on/off ».
- États UI : chargement, erreur réseau explicite (timeout 60 s), bouton réessayer.
- Fichiers < 500 lignes, composants découpés, pas de duplication (client API unique dans `app/src/api.ts`).

## Vérifications attendues

- `python3 -m py_compile` sur le backend, import du module sans token Modal.
- `npx tsc --noEmit` sans erreur côté app.
- Test E2E `scripts/test_api.py` : `/health`, `/segment`, `/analyze`, `/ask` sur une image d'exemple (`app/assets/sample.jpg` ou l'image de test des docs SAM3).
- APK debug/release produit dans `app/android/app/build/outputs/apk/**`, taille et chemin affichés.