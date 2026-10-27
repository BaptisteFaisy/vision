# Suivi caregiver — session iSee partagée

Page mobile utilisée par le lien envoyé au proche et par la WebView Caregiver
Android : fond blanc, boule Portrait grise, phrase courante et carte en bas.

## Ouvrir la bonne session sur un autre téléphone

1. Sur le téléphone accompagné : configurer un identifiant de flux par proche
   dans Réglages → Suivi parent, puis « Partager le suivi ».
2. Le caregiver ouvre le lien reçu (`https://…/?share=<identifiant>`) dans son
   navigateur, ou le colle via **Lien** dans son app en rôle Caregiver.
3. La page lit uniquement ce flux. Sans lien valide, aucune session implicite
   n'est choisie. Aucune position du téléphone caregiver n'est demandée.

Le rôle Caregiver interdit aussi les publications Android : ni l'écran Direct
ni un ancien service de suivi ne peut envoyer sa position à la place du proche.

## Rendu et données

- La boule reprend le shader Portrait Android, désaturé. À la racine,
  `python tools/export-caregiver-orb.py` régénère son port WebGL. Le shader
  Android gelé reste inchangé. Repli en verre gris si WebGL est indisponible.
- Texte Portrait, quatre lignes maximum, même cadence d'éclairage mot à mot
  quand le téléphone indique qu'il parle. La dernière phrase est transmise
  sans historique ; sinon le site décrit les deux objets reçus les plus proches.
- Les déplacements GPS distinguent avance, recul et décalage par rapport au
  cap précédent. Le déplacement doit dépasser la somme des incertitudes GPS et
  être compatible avec une marche. Aucun petit pas en intérieur ni contournement
  n'est affirmé à partir d'une simple consigne. La précision reste limitée par
  le GPS du téléphone accompagné.
- Carte : icônes des objets, halo de précision, zoom et recentrage. Un cap de
  0° place l'objet au nord ; 90° à l'est.
- LIVE avant 12 s ; SIGNAL LOST jusqu'à 2 min (objets et position figés en gris) ;
  OFFLINE ensuite (aucun repère ni phrase active). Aucune simulation en production.

## Flux

Le téléphone accompagné publie toutes les deux secondes pendant iSee ou Direct :

```text
POST /api/share
{shareId, writeKey, fix:{lat,lon,accM,headingDeg,atMs,indoor},
 objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking}}
GET /api/state?share=<identifiant>
```

`scene` est facultatif pour les anciennes versions de l'app ; le texte est borné
à 2 000 caractères. Le service reprend la position seule en arrière-plan, sans
inventer les objets d'une caméra fermée. Le relais utilise l'origine du site,
même si les réglages contiennent un lien complet avec `?share=`.

## Vérification locale

Depuis la racine, sans dépendance npm :

```bash
node --test suivi-parent/test/session.test.mjs suivi-parent/test/relay.test.mjs
node suivi-parent/test/preview-server.cjs
```

Prévisualisation : `http://127.0.0.1:8836/?share=ton-flux`. Le serveur local
utilise les handlers Vercel et un stockage mémoire isolé de la production.
Tests : deux flux indépendants, rétrocompatibilité, cap des objets, déconnexion,
bruit GPS et éclairage des mots.

## Hébergement

Racine Vercel : `suivi-parent/`, framework « Other », build vide. Les modules
`.mjs`, le CSS et le shader généré sont statiques ; `api/` n'a pas de dépendance npm.

- `SHARE_WRITE_KEY` : si définie, même clé dans les réglages de l'émetteur.
- `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN`, ou les noms Vercel KV
  `KV_REST_API_URL` + `KV_REST_API_TOKEN` : stockage partagé entre instances.
  Le repli mémoire est réservé au développement.

Carte OpenFreeMap par défaut ; clé Google Maps facultative dans **Options de la
carte**, conservée uniquement dans le navigateur concerné.
