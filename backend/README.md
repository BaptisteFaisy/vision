# backend — sam3-vision (Modal)

App Modal `sam3-vision` : SAM 3.1 (segmentation promptée) + LLM vision, exposée en FastAPI.
Contrat HTTP exact : `../docs/SPEC.md`.

## Structure

| Fichier | Rôle |
| --- | --- |
| `modal_app.py` | app Modal, classe GPU A10G, `@modal.asgi_app()` FastAPI (4 routes) |
| `segmentation.py` | chargement `Sam3Model`/`Sam3Processor`, prompts par défaut + fallback, segmentation |
| `llm.py` | client OpenAI-compatible (vision), prompts `/analyze` et `/ask` |
| `imaging.py` | décodage base64 → PIL, masque → PNG 8 bits base64, data URL JPEG pour le LLM |
| `schemas.py` | modèles Pydantic des corps de requête |

## Secret Modal

```bash
/home/baptiste/.venv-modal/bin/modal secret create sam3-llm \
  LLM_BASE_URL=https://openrouter.ai/api/v1 \
  LLM_API_KEY=<clé> \
  LLM_MODEL=google/gemini-2.5-flash
```

`LLM_BASE_URL` et `LLM_MODEL` sont optionnels (défauts OpenRouter + `google/gemini-2.5-flash`) ;
`LLM_API_KEY` est obligatoire. Aucune clé n'est écrite dans le dépôt.

## Déploiement

```bash
cd /home/baptiste/Bureau/sam3-vision-app
/home/baptiste/.venv-modal/bin/modal deploy backend/modal_app.py
```

L'URL se termine par `...modal.run` ; elle sert de `expo.extra.apiUrl` côté app.

## Smoke test

```bash
URL=https://<workspace>--sam3-vision-web.modal.run
curl -s $URL/health
curl -s -X POST $URL/segment -H 'Content-Type: application/json' \
  -d "{\"image_b64\": \"$(base64 -w0 photo.jpg)\", \"prompts\": null}"
```

`prompts: null` ⇒ concepts génériques du serveur (`person`, `car`, `animal`, `plant`,
`furniture`, `building`, `food`, `clothing`) puis repli `object`/`thing` si rien n'est trouvé.
`prompts` fourni : 1 à 16 concepts, 64 caractères max chacun (sinon 400).
L'encodeur de texte de SAM 3.1 étant un CLIP anglais, les concepts français courants
(`personne`, `voiture`, `objet`…) sont traduits pour le modèle — **le label renvoyé reste
celui tapé par l'utilisateur**.

## Limites connues

- 1 requête à la fois par conteneur (Modal 1.6.2 n'accepte que `max_inputs=1` pour `@app.cls`).
- `scaledown_window=300` : le premier appel après 5 min de veille recharge les poids (~30-60 s).
- Les masques sont calculés sur une copie ≤ 1024 px puis remontés en nearest à la taille d'origine.