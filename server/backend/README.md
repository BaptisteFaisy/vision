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

**Jamais de clé en argument de commande** — la forme `modal secret create … LLM_API_KEY=…`,
même en copiant un exemple, laisse la clé dans l'historique shell et dans la table des
processus (`ps`). Elle n'est plus documentée ici.

Le dépôt passe par `--from-json` avec un fichier temporaire `chmod 600` — implémentation
unique dans `scripts/lib-env.sh` (`sync_secret_from_env`), appelée par :

```bash
# rotation depuis un fichier de clé (recommandé) : pousse le secret puis redéploie
./scripts/rotate_llm_secret.sh /chemin/cle.txt

# ou à l'occasion d'un déploiement : le script pousse le secret depuis l'environnement
export LLM_BASE_URL="https://openrouter.ai/api/v1" LLM_API_KEY="<clé>" LLM_MODEL="<modèle vision>"
./scripts/deploy_backend.sh

# vérifier le processus sans rien pousser sur Modal
DRY_RUN=1 ./scripts/deploy_backend.sh
```

Les trois clés du secret sont `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`. Côté backend,
`llm.py` retombe sur OpenRouter (`DEFAULT_BASE_URL`) et `google/gemini-2.5-flash`
(`DEFAULT_MODEL`) si les deux premières manquent ; `rotate_llm_secret.sh` applique le même défaut
d'URL (`BASE_URL_DEFAULT` dans `scripts/lib-env.sh`). La clé, elle, est obligatoire : les scripts
**refusent de tourner** sans `LLM_API_KEY` plutôt que de créer un secret vide. Aucune clé n'est
écrite dans le dépôt.

## Déploiement

```bash
cd /home/baptiste/Bureau/sam3-vision-app
./scripts/deploy_backend.sh            # secret + deploy + smoke /health (idempotent)
```

Équivalent manuel (sans la partie secret) : `/home/baptiste/.venv-modal/bin/modal deploy backend/modal_app.py`.
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
- `scaledown_window=300` : le premier appel après 5 min de veille recharge les poids — **~36 s
  mesurés une seule fois** (35,8 s pour `/health`), non reproductible depuis : voir `docs/PERF.md`.
- Les masques sont calculés sur une copie ≤ 1024 px puis remontés en nearest à la taille d'origine.