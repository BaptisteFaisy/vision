# Suivi caregiver — session iSee partagée

> **Site abandonné (2026-10-28).** Le relais que l'app utilise par défaut est
> `../suivi-relais/` (Worker + D1) ; ce déploiement Vercel est resté figé au
> 27/10 21 h 03 et refusait les écritures des installations neuves (403). Ce
> dossier ne sert plus que de source de la page embarquée dans l'APK (tâche
> Gradle `caregiverAssets`) et de référence pour l'ancien contrat.

Page mobile utilisée par le lien envoyé au proche et par la WebView Caregiver
Android : fond blanc, petite boule Portrait bleue et verte, phrase courante
et carte haute en bas.

L'interface Android est embarquée dans chaque APK depuis ce dossier (tâche
Gradle `caregiverAssets`). La WebView intercepte uniquement ces fichiers locaux
sous `/.isee-caregiver/` ; les appels `/api/state` restent sur l'origine du lien
partagé. Une ancienne version du site ne peut donc plus remplacer le design
de l'app installée.

L'écran tient dans la hauteur du téléphone, avec une carte plus haute en bas,
sans défilement, barre de retour ni titre Caregiver. L'état LIVE / SIGNAL LOST /
OFFLINE est dans une pastille arrondie en haut à droite, aux dimensions du
badge Portrait. La roue dentée noire ouvre les paramètres
Android, où se trouve la clé Google Maps préremplie par le build.

La clé vient de `GOOGLE_MAPS_API_KEY` (secret CI, toutes les variantes) ou de
`google.maps.api.key` dans `local.properties` gitignoré. Elle est ajoutée aux
réglages vides à l'installation et conservée dans les deux stores chiffrés.
Une clé personnalisée enregistrée dans les paramètres reste prioritaire.

## Ouvrir la bonne session sur un autre téléphone

1. Sur le téléphone accompagné : configurer un identifiant de flux par proche
   dans Réglages → Suivi parent, puis « Partager le suivi ».
2. Le caregiver ouvre le lien reçu (`https://…/?share=<identifiant>`) dans son
   navigateur, ou le colle dans **Settings → THE USER → Invite link or code**
   dans son app en rôle Caregiver.
3. La page lit uniquement ce flux. Sans lien valide, aucune session implicite
   n'est choisie. Aucune position du téléphone caregiver n'est demandée.

Le rôle Caregiver interdit aussi les publications Android : ni l'écran Direct
ni un ancien service de suivi ne peut envoyer sa position à la place du proche.

## Rendu et données

- La boule reprend le shader Portrait Android, avec un liquide bleu et vert,
  un rayon de 24 % de la largeur et un plafond de 100 px CSS. À la racine,
  `python tools/export-caregiver-orb.py` régénère son port WebGL. Le shader
  Android gelé reste inchangé. Repli en verre bleu et vert si WebGL est indisponible.
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

Google Maps est utilisé dès qu'une clé est fournie par l'app ou les paramètres
web. Sans clé, le navigateur peut utiliser OpenFreeMap. Le formulaire Google
et le lien reçu sont uniquement dans les paramètres, pas sous la carte.
