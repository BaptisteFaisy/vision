# Modal fermé (relevé antérieur, hors de cet arbre)

## Nouvelle app : Kotlin natif
L'app Android est désormais **native Kotlin** (à la racine du dépôt vision : LLM → InferHub
`https://api.inferhub.dev/v1`, SAM 3.1 en endpoint optionnel). `server/` garde le backend Modal
et l'ancienne app Expo, déplacée en `legacy-expo-app/`.

## Relancer SAM 3.1 (une commande)
```bash
LLM_BASE_URL=https://api.inferhub.dev/v1 LLM_API_KEY=unused LLM_MODEL=unused \
  bash scripts/deploy_backend.sh --skip-smoke
```
- `scripts/deploy_backend.sh` **recrée toujours** le secret `sam3-llm` (imposé par
  `modal_app.py`, `secrets=[modal.Secret.from_name(...)]`), d'où les valeurs placeholder ci-dessus.
- Variante « sans secret » : ce sont des placeholders, aucune clé réelle ne monte sur le serveur ;
  le backend n'appelle plus de LLM (l'app parle à InferHub), seules `/health` et `/segment` servent.
- Le secret `sam3-llm` a été supprimé (relevé antérieur, hors de cet arbre ; vérifié absent de
  `modal secret list` à l'époque) ; il ne réapparaît qu'au prochain déploiement, avec ces
  placeholders ou une vraie clé si besoin.

## URL à coller dans les Réglages de l'app Kotlin
`https://baptiste-faisy--sam3-vision-sam3service-web.us-east.modal.run` (redonnée par le déploiement).
Contrat HTTP inchangé : `docs/SPEC.md` (GET `/health`, POST `/segment`, `/analyze`, `/ask`).

## État au moment de la fermeture (après les stops)
```text
$ modal container list
(vide — aucun conteneur actif)
$ modal app list --json   (16 apps, toutes state=stopped, tasks=0 ; reformaté)
sam3-vision    ×4 : ap-tbuyWnY40qa2wnFCTWkcbQ  ap-bijkPkwXAFdwooKAQM1ROf  ap-w4svuyXqowsvujMHIk8t5q  ap-84tNlJvSReVbyn6EBUvRQf
sam3-probe-*  ×12 : ap-uDZY191fRtLtgUoCwolQWe  ap-wncM8q37zhATeGRO1mV0GD  ap-YeY4AdYQBwouMHXSnf45wn  ap-yvG8PNSmFLFwEnhLjNj44R  ap-x9SdVwrbOXu9juaDdpo8yd
                   ap-Hr9qMDBhXt9EGcwLVV2aF3  ap-R5s4vLhEPvicE0CN0VRSqH  ap-a9HO6omvwhn1dcQwGuXqZa  ap-3ob2KeMLTRpXc5kY6jAHPv  ap-j4CLIGren9aPAR4Du6Wc7y
                   ap-KKuhL81h0KhXnR0vhU9pkW  ap-30v4cYRgwtuS3KZkNpj9zn
total : 16 apps, 0 avec tasks ≠ 0
```
`modal app stop sam3-vision -y` → « App is already stopped » (idempotent).

## Coûts (modal billing summary, relevé antérieur)
Metered 0,97 $ (deployed 0,89 / ephemeral 0,05 / egress 0,03), crédits −0,94 $, **facturé 0,00 $**.
Aucun GPU allumé depuis l'arrêt : coût futur nul tant que l'app n'est pas redéployée.
