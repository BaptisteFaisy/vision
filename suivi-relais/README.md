# Relais de suivi — Worker + D1

Le relais que l'app utilise **par défaut** (`Settings.DEFAULT_PARENT_SITE_URL`).
Il remplace l'API du site Vercel `suivi-parent/`, abandonné : ce déploiement était
figé sur un commit du 27/10 et refusait toute écriture sans une clé que les
téléphones n'ont pas (403 « clé invalide »), donc la carte du proche restait vide
sans un mot.

## Contrat (inchangé pour la page embarquée dans l'APK)

```text
POST /api/share   {shareId, writeKey, fix?:{lat,lon,accM,headingDeg,atMs,indoor},
                   objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking},
                   photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}
GET  /api/state?share=<identifiant>   → {ok, live, ageMs, fix?, objects, scene, photo, trip, serverAt}
GET  /health                          → {ok:true}
GET  /ws?share=<id>&role=watch        → WebSocket : frames JPEG binaires du proche
GET  /ws?share=<id>&role=push&key=<k> → WebSocket : le proche pousse ses frames
```

## Direct : `/ws`, une salle par flux (`src/durable.js`)

L'état lent (position, phrase, trajet) passe par D1 ; **l'image du direct ne
passe pas par la base** — un Durable Object sert de salle, et les frames vont du
téléphone aux aidants connectés sans être écrites nulle part. C'est la seule
façon de tenir un vrai direct : à 6 im/s, D1 et son quota d'écritures ne suivraient
pas (100 000 écritures/jour, soit **2,5 h** de direct, et 345 000 requêtes pour la
même durée côté Worker).

```text
téléphone ──frames binaires──▶ RelayRoom (DO, un par identifiant) ──▶ aidants

téléphone ──{"type":"camera","on":…}──▶ RelayRoom ──{"type":"camera","on":…}──▶ aidants
                                      │
                                      └─▶ {"type":"audience","watch":n}  (au téléphone)
```

- **La caméra dit quand elle s'éteint** : le téléphone annonce
  `{"type":"camera","on":…}` à chaque changement et la salle le transmet aux
  aidants ; quand le dernier téléphone s'en va (fermeture, crash, réseau), la
  salle émet elle-même `{"type":"camera","on":false}`. La page efface l'image
  aussitôt — direct comme photo lente — au lieu de garder la dernière image
  jusqu'à 8 s. Un aidant qui arrive sans téléphone apprend tout de suite que
  la caméra est éteinte ; seul un `push` parle au nom de la caméra (un aidant
  ne peut pas éteindre l'image des autres).
- **Le téléphone n'émet que s'il est regardé** : à chaque arrivée ou départ
  (aidant comme téléphone), la salle republie `{"type":"audience","watch":n}`
  aux téléphones connectés ; à `n = 0`, la caméra se tait (forfait, batterie).
  Un téléphone qui rejoint une salle déjà regardée apprend ainsi tout de
  suite qu'on le regarde, au lieu de rester muet.
- **Une frame ne revient jamais à son émetteur** et n'est jamais relue en base ;
  au-delà de 256 Ko (`MAX_FRAME_BYTES`), elle est ignorée.
- **L'écriture reste protégée** : `role=push` exige la clé du flux quand le flux
  en a une (même règle que `/api/share`, liée au premier envoi) — un identifiant
  deviné ne suffit pas à diffuser une fausse caméra chez l'aidant.
- **Hibernation** : la salle utilise l'API d'hibernation (`acceptWebSocket` avec
  étiquette de rôle), donc un aidant immobile ne coûte pas de durée. Sur l'offre
  gratuite (100 000 requêtes/jour, messages WebSocket comptés au 1/20), une heure
  de direct coûte ~1 400 requêtes : très en dessous du plafond.
- **Repli** : la page garde la photo lente (`/api/state`, une frame toutes les 2 s)
  si le WebSocket est indisponible. Un déploiement sans binding `RELAY_ROOM`
  répond `503 {"ok":false,"error":"diffusion non déployée"}` et la page continue
  de fonctionner.

- `fix` (position), `photo` (JPEG base64 ≤ 120 000 caractères) et `trip`
  (progression 0..1) sont facultatifs : un téléphone sans GPS publie quand
  même sa caméra, sa phrase et ses repères, et un appareil d'avant continue
  d'envoyer position, objets et phrase. Une écriture **sans** image (service
  de suivi écran éteint) garde la dernière photo et son `atMs` : la page la
  cache au lieu de la faire passer pour du direct. Un `trip` absent retire
  la barre de progression.

- **Un état par identifiant de flux** : deux proches suivis = deux `?share=`, deux
  lignes distinctes. Plusieurs caregivers peuvent lire le même identifiant, aucun
  ne peut écrire à la place d'un autre (`caregiverRole` le bloque côté app).
- `live` dure **12 s** ; au-delà la page passe en SIGNAL LOST puis OFFLINE en
  gardant la dernière position (`ageMs`, `serverAt`).
- **Aucune clé à saisir** : la clé d'écriture est liée au flux au premier envoi
  (`write_key` en base). Une clé différente ensuite → 403, ce qui empêche un
  identifiant deviné d'injecter une fausse position. L'app génère cette clé à
  l'installation (`defaultShareKey`), elle n'est jamais montrée ni partagée.
- `SHARE_WRITE_KEY` (secret du Worker) reste possible comme verrou global : s'il
  est défini, toutes les écritures doivent porter cette clé.

## Stockage

D1, table `streams (id, doc, updated_at, write_key)`, schéma créé à la première
requête. Un flux est conservé 24 h puis purgé (une écriture sur 200).

## Vérifier

```bash
node --test suivi-relais/test/relais.test.mjs
```

Les tests couvrent : isolement de deux flux, écriture sans clé, clé liée au flux
(403), verrou global optionnel, corps invalides, lecture sans identifiant, âge de
l'état, la publication sans position et le bornage du texte.

## Déployer

Le Worker est publié par le manifeste `deploy.json` (modules ESM + binding `DB`).
L'URL de production est recopiée dans `Settings.DEFAULT_PARENT_SITE_URL` côté
`:core` ; la changer impose un APK neuf.

**Déployer la salle** : `npx wrangler login`, puis `npx wrangler d1 list` (relever
l'id de la base et le coller dans `wrangler.toml`), puis `npx wrangler deploy`
depuis ce dossier. Le token wrangler de cette machine a expiré le 6/10/2026 — aucun
déploiement n'est possible avant une nouvelle connexion.

**URL de production (29/10) : `https://isee-suivi.duello.workers.dev/`** — c'est le
Worker lui-même : `/health` → `{"ok":true}`, `/api/state?share=…` → l'état du flux, et
il sert aussi la page de la WebView (`/`, `live.mjs`, `styles.css`). Le domaine
d'apparence plus jolie retenu plus tôt (`…freebuff.page`) a rendu **502 « Site
unavailable »** le 29/10 au soir alors que ce Worker répondait encore : la page
caregiver restait en NO STREAM et la caméra du proche ne s'affichait plus. Un relais
qui sert des personnes pointe sur l'infrastructure qu'on déploie, pas sur une
redirection. Vérifier après tout déploiement :

```bash
curl -s https://isee-suivi.duello.workers.dev/health      # {"ok":true}
curl -s "https://isee-suivi.duello.workers.dev/api/state?share=verif"   # {"ok":true,"live":false}
```
