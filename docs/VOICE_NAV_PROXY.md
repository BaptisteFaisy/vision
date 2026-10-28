# Proxy voix-navigation : téléphone → Valhalla (/route)

Le routeur Valhalla tourne sur le r630 (`127.0.0.1:8002`, tuiles Île-de-France),
joignable uniquement via Tailscale. Le téléphone n'est pas sur le tailnet : il
passe par l'URL publique d'un **Tailscale Funnel** servi par le r630. Aucun
proxy à écrire, aucun déploiement Modal, aucune GPU touchée, rien à installer
sur le téléphone (HTTPS ordinaire).

Recommandation du spike reach du 2026-10-28 (appliquée telle quelle) :
Funnel direct, avec `cloudflared` en repli si l'admin tailnet refuse Funnel.

## Déploiement

1. **User, une fois** : activer Funnel pour le tailnet (console admin
   Tailscale → Funnel). Les agents ne peuvent pas le faire.
2. **Exposer** (depuis le poste) :
   `bash scripts/valhalla-funnel.sh up`
   (= `ssh baptiste@100.124.18.48 'tailscale funnel --bg 8002'`).
   Noter l'URL publique affichée, ex.
   `https://r630-debian.<tailnet>.ts.net`.
3. **Brancher l'app** : mettre cette URL dans `local.properties`
   (gitignoré) : `route.base.url=https://…`, ou la saisir dans
   Réglages → « Valhalla route URL ». Priorité : Réglages > build > vide
   (vide = guidage Maps seul, comportement inchangé).
4. **Rebuild** sur le serveur (`build-serveur`), puis test sur master mergé
   (règle ISEE-TEST-MASTER-SEUL).

## Vérification (depuis le poste, sans toucher au téléphone)

```bash
bash scripts/valhalla-funnel.sh check https://r630-debian.<tailnet>.ts.net
curl -s -m 10 https://r630-debian.<tailnet>.ts.net/route -d \
  '{"locations":[{"lat":48.8566,"lon":2.3522},{"lat":48.8606,"lon":2.3376}],"costing":"pedestrian"}'
```

Attendus : `/status` → 200 + JSON `version 3.6.0` ; `/route` → 200 + `trip`
avec `summary` et `legs[].maneuvers[]` (mesuré en local : ~1,5 s, ~8 Ko).

## Keepalive

`funnel --bg` survit à la session SSH, pas à un reboot du r630. Le script
fournit `ensure` (revérifie puis ré-expose si mort) :

```bash
bash scripts/valhalla-funnel.sh ensure https://r630-debian.<tailnet>.ts.net
```

Cron suggéré sur le poste (non installé — à poser explicitement) :

```cron
*/5 * * * * <chemin-du-depot>/scripts/valhalla-funnel.sh ensure https://r630-debian.<tailnet>.ts.net >>/tmp/valhalla-funnel.log 2>&1
```

## Rollback

Couper l'exposition publique (Valhalla local intact, l'app retombe sur
le guidage Maps dès que l'URL ne répond plus) :

```bash
bash scripts/valhalla-funnel.sh down
```

(= `ssh baptiste@100.124.18.48 'tailscale funnel reset'`).
Côté app : vider le champ Réglages (ou `route.base.url`) = routage
in-app désactivé, zéro changement de comportement du guidage Maps.

## Secrets

Aucun. L'URL Funnel est publique par design et `/route` est un routage
en lecture seule sans données personnelles : rien dans le dépôt, rien en
`local.properties` d'autre que l'URL, rien dans les logs au-delà de
l'URL. Si un jour l'abus impose un contrôle d'accès, ajouter un front
Cloudflare Worker (motif `duello-api-relay` : adresse fixe + `ORIGIN` =
URL Funnel + Bearer) — phase 2, pas maintenant.

## Câblage côté app

`route.base.url` → `BuildConfig.ROUTE_BASE_URL` → `seedRouteBaseUrl`
(remplit le champ vide) → `Settings.routeBaseUrl` (persisté, parité des
deux stores) → `resolveRouteEndpoint()` / `routeEndpoint()` →
`HttpRouteClient` (`POST /route`, `GET /status`, socle `Http.kt` partagé).
Valhalla ne géocode pas : l'appelant fournit des lat/lon (GPS, Geocoder
Android, BAN — voir le spike géocodage du 2026-10-28).
