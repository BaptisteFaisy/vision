# Routeur voix-navigation : téléphone → Valhalla (/route)

Le guidage vocal piéton appelle un backend Valhalla (tuiles Île-de-France)
**sur Modal CPU** (`server/backend/valhalla_cpu.py`, app `valhalla-idf-cpu`) :
HTTPS publique, aucune GPU, extinction à zéro entre les requêtes (~0 €),
**sans Tailscale** (ordre user 2026-10-28 — le montage Funnel précédent est
abandonné, `scripts/valhalla-funnel.sh` a été supprimé le 29/10).

Le r630 garde son Valhalla local (`127.0.0.1:8002`) pour la QA et les tuiles
source ; le téléphone ne l'appelle jamais directement.

## Déploiement

Automatique : tout push sur master qui touche `server/backend/valhalla_cpu.py`
redéploie via `.github/workflows/deploy-valhalla-cpu.yml` (CPU, 10 min max, hors PR).

Manuel (premier déploiement déjà fait le 2026-10-28) :

```bash
python3 -m modal deploy server/backend/valhalla_cpu.py
```

URL publique (stable) :

`https://baptiste-faisy--valhalla-idf-cpu-valhallarouter-web.us-east.modal.run`

## Tuiles (upload unique)

Les tuiles vivent sur le Volume Modal `valhalla-idf-tiles` (`/tiles` +
`/admin.sqlite`, ~330 Mo, uploadées une fois depuis le r630). Le workflow
ne touche jamais au volume. Pour recharger des tuiles :

```bash
modal volume put valhalla-idf-tiles <dir-tiles> /tiles
modal volume put valhalla-idf-tiles <admin.sqlite> /admin.sqlite
```

## Vérification (depuis le poste, sans toucher au téléphone)

```bash
curl -s https://baptiste-faisy--valhalla-idf-cpu-valhallarouter-web.us-east.modal.run/health
curl -s -m 30 -X POST https://baptiste-faisy--valhalla-idf-cpu-valhallarouter-web.us-east.modal.run/route -d \
  '{"locations":[{"lat":48.8566,"lon":2.3522},{"lat":48.86,"lon":2.34}],"costing":"pedestrian"}'
```

Attendus : `/health` → 200 + JSON `status ok` ; `/route` → 200 + `trip`
avec `summary` et `legs[].maneuvers[]` (mesuré : 1,308 km, 12 manoeuvres).

Sécurité traversées (piéton) : `/route` répare les passages piétons sans feu
proche (manœuvres tout-droit "crosswalk" à +40 m d'un feu) en reroutant via
le feu le plus proche (150 m max, 2 réparations, +20 % de temps max, repli
route de base). Mesuré Gare de Lyon → Louvre : 3 → 1 traversées sans feu,
+1 % de temps.

## Rollback

`modal app stop valhalla-idf-cpu` (l'app retombe sur le guidage Maps dès
que l'URL ne répond plus). Redéploiement : relancer le workflow
(`workflow_dispatch`) ou `modal deploy`.

## Secrets

Aucun. L'URL est publique par design et `/route` est un routage en lecture
seule sans données personnelles : rien dans le dépôt, rien dans les logs
au-delà de l'URL. Si un jour l'abus impose un contrôle d'accès, ajouter un
front Cloudflare Worker (motif `duello-api-relay`) — phase 2, pas maintenant.

## Câblage côté app

Défaut embarqué `routeBaseUrlDefault` (`app/build.gradle.kts`, = URL
ci-dessus) → `BuildConfig.ROUTE_BASE_URL` → `seedRouteBaseUrl` (remplit le
champ vide) → `Settings.routeBaseUrl` (persisté, parité des deux stores) →
`resolveRouteEndpoint()` / `routeEndpoint()` → `HttpRouteClient`
(`POST /route`, `GET /status`, socle `Http.kt` partagé). Surcharge possible
via `route.base.url` (`local.properties`, gitignoré) ou Réglages →
« Valhalla route URL ». Valhalla ne géocode pas : l'appelant fournit des
lat/lon (GPS, Geocoder Android, BAN).
