# Suivi caregiver — session iSee partagée

> **Site abandonné (2026-10-28).** Le relais que l'app utilise par défaut est
> `../suivi-relais/` (Worker + D1) ; ce déploiement Vercel est resté figé au
> 27/10 21 h 03 et refusait les écritures des installations neuves (403). Ce
> dossier ne sert plus que de source de la page embarquée dans l'APK (tâche
> Gradle `caregiverAssets`) et de référence pour l'ancien contrat.

Page mobile utilisée par le lien envoyé au proche et par la WebView Caregiver
Android : fond blanc, petite boule Portrait cacao et caramel, phrase courante,
**image du téléphone accompagné** (dernière frame caméra, 320 px), **dessins des
objets reconnus** et, pendant un trajet guidé, une **barre de progression
liquide** du départ à l'arrivée.

L'interface Android est embarquée dans chaque APK depuis ce dossier (tâche
Gradle `caregiverAssets`). La WebView intercepte uniquement ces fichiers locaux
sous `/.isee-caregiver/` ; les appels `/api/state` restent sur l'origine du lien
partagé. Une ancienne version du site ne peut donc plus remplacer le design
de l'app installée.

L'écran tient dans la hauteur du téléphone, avec l'image du proche plus haute
que la boule, sans défilement, barre de retour ni titre Caregiver. L'état LIVE /
SIGNAL LOST / OFFLINE est dans une pastille arrondie en haut à droite, aux
dimensions du badge Portrait. La roue dentée noire ouvre les paramètres Android
(lien reçu), sans aucun réglage de fond de carte : la carte a été retirée.

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

- La boule reprend le shader Portrait Android, avec un liquide cacao et caramel
  (proposition 32 de la planche de couleurs), un rayon de 24 % de la largeur et
  un plafond de 100 px CSS. À la racine, `python tools/export-caregiver-orb.py`
  régénère son port WebGL depuis la source AGSL, qui reste la seule vérité :
  le haut du verre y répond en deux teintes du liquide (le bleu profond de la
  palette et son or ; ici deux cacao), sans rose ni vert, et sans laver la boule
  de couleur. Repli en verre cacao et caramel si WebGL est indisponible (mêmes
  variables `--orb-color-*`).
- Texte Portrait, quatre lignes maximum, même cadence d'éclairage mot à mot
  quand le téléphone indique qu'il parle. La dernière phrase est transmise
  sans historique ; sinon le site décrit les deux objets reçus les plus proches.
- Les déplacements GPS distinguent avance, recul et décalage par rapport au
  cap précédent. Le déplacement doit dépasser la somme des incertitudes GPS et
  être compatible avec une marche. Aucun petit pas en intérieur ni contournement
  n'est affirmé à partir d'une simple consigne. Sans position (paquet sans
  GPS), aucun déplacement n'est affirmé. La précision reste limitée par
  le GPS du téléphone accompagné.
- Image : la caméra du proche s'affiche **seulement pendant qu'il la fait
  tourner** — flux LIVE et frame de moins de 8 s, en `object-fit: cover`. Hors de
  là, aucun cadre, aucune image ni texte : la section se replie et l'image la
  rouvre dès que la caméra tourne. Seul un lien absent s'écrit
  (« Ouvre le lien de suivi partagé par ton proche. »).
- **Direct** : quand le relais expose la salle WebSocket (`/ws`), la page s'y
  connecte (`live-stream.mjs`) et affiche les frames **au fil de l'eau** —
  6 im/s, une image de moins de 1,5 s vaut « maintenant ». La photo de
  `/api/state` (une toutes les 2 s) reste le repli si le WebSocket échoue ou
  n'existe pas : la page ne dépend d'aucun déploiement pour fonctionner.
- Repères : les objets reconnus par le téléphone (pictogrammes créés à la voix)
  s'affichent en bandeau, **en dessins** avec leur nom — flux LIVE et détection
  de moins de 8 s, comme l'image. Sans détection, pas de bandeau.
- Trajet : pendant un guidage iSee, une barre du départ à l'arrivée. Le liquide
  (dégradé bleu → vert de la boule) et la bille avancent à `progress` ; le reste
  s'écrit « Encore 300 m · 4 min ». La barre disparaît dès que le guidage
  s'arrête et se fige (atténuée) quand le flux n'est plus LIVE.
- LIVE avant 12 s ; SIGNAL LOST jusqu'à 2 min (objets et position figés en gris) ;
  OFFLINE ensuite (aucun repère ni phrase active). Aucune simulation en production.
- NO STREAM quand le relais ne connaît pas l'identifiant (`{ok:true, live:false}`
  sans `serverAt`, ou flux purgé après 24 h) : la page le dit et propose de
  redemander le lien, au lieu d'attendre indéfiniment un téléphone qui n'existe
  plus — un lien périmé (app réinstallée, nouvel identifiant) ne ressemble plus
  trait pour trait à un proche pas encore en direct.

## Flux

Le téléphone accompagné publie toutes les deux secondes pendant iSee ou Direct :

```text
POST /api/share
{shareId, writeKey, fix?:{lat,lon,accM,headingDeg,atMs,indoor},
 objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking},
 photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}
GET /api/state?share=<identifiant>
```

`fix`, `scene`, `photo` et `trip` sont facultatifs : un téléphone sans GPS
(intérieur, démarrage, permission refusée) publie quand même sa caméra, sa
phrase et ses repères — sinon la page restait OFFLINE alors que la caméra
tournait ; un paquet sans position n'invente aucun déplacement. Le texte est
borné à 2 000 caractères et l'image base64 à 120 000. Le
service reprend la position seule en arrière-plan, sans inventer les objets
d'une caméra fermée ; le relais iSee conserve alors la dernière image avec son
âge, et la page ne l'affiche jamais : une caméra s'affiche parce qu'elle tourne,
pas parce qu'une image traîne. Le relais
utilise l'origine du site, même si les réglages contiennent un lien complet avec
`?share=`.

## Vérification locale

Depuis la racine, sans dépendance npm :

```bash
node --test suivi-parent/test/session.test.mjs suivi-parent/test/relay.test.mjs suivi-parent/test/live-view.test.mjs
node suivi-parent/test/preview-server.cjs
```

Prévisualisation : `http://127.0.0.1:8836/?share=ton-flux`. Le serveur local
utilise les handlers Vercel et un stockage mémoire isolé de la production.
Tests : deux flux indépendants, rétrocompatibilité, cap des objets, déconnexion,
bruit GPS, image et trajet reçus, éclairage des mots.

## Hébergement

Racine Vercel : `suivi-parent/`, framework « Other », build vide. Les modules
`.mjs`, le CSS et le shader généré sont statiques ; `api/` n'a pas de dépendance npm.

- `SHARE_WRITE_KEY` : si définie, même clé dans les réglages de l'émetteur.
- `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN`, ou les noms Vercel KV
  `KV_REST_API_URL` + `KV_REST_API_TOKEN` : stockage partagé entre instances.
  Le repli mémoire est réservé au développement.

La page ne dépend plus d'aucun fournisseur de carte : ni MapLibre, ni clé
Google, ni appel réseau tiers. L'image affichée est celle que le téléphone
publié dans `photo` (frame JPEG base64) et la barre lit `trip` ; les deux
champs sont facultatifs, donc un appareil d'avant continue de partager
position, objets et phrase.
