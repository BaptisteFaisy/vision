# Contrat du direct — `LIVE-SPEC`

> **Dépôt** : `BaptisteFaisy/vision` — **date** : 26/10/2026.
> **Nature du document** : ce n'est pas la spécification du serveur, c'est **le contrat
> déduit du client**. Chaque affirmation porte un `chemin:ligne` lu dans cet arbre ; le code
> est l'autorité, `docs/` n'est qu'une piste. Le serveur de référence est dans `server/`
> (`backend/live_fast/`) : voir la section 8.

Le direct « iSee » envoie des images JPEG à un endpoint SAM 3.1 (GPU Modal) et reçoit, pour
chaque image, des objets segmentés (label, score, boîte, masque). Il y a **un seul transport
pour les frames** : un WebSocket. Les autres routes HTTP du service sont listées ici parce
qu'elles entourent le direct (réveil, photo, vidéo, extinction).

## 1. Transport : WebSocket `/live/ws`

| Élément | Valeur | Référence |
|---|---|---|
| Chemin | `/live/ws` | `core/src/main/java/dev/sam3vision/core/net/WsTransport.kt:35` |
| URL construite | base sans `/` final, puis chemin | `WsTransport.kt:38-46` |
| `https://` | devient `wss://` | `WsTransport.kt:41` |
| `http://` | devient `ws://` | `WsTransport.kt:42` |
| User-Agent du handshake | `sam3vision-android/1.0 live` | `HttpLiveClient.kt:24`, posé par `WsTransport.kt:68` |
| Fermeture | code `1000`, raison `fin du direct` | `WsTransport.kt:80`, `:101-103` |
| Socket | **un seul par session**, réutilisé pour toutes les frames | `WsLiveClient.kt:91-102` |

- Le handshake réutilise le pool de connexions du direct (`newBuilder()` sur le client
  partagé) : DNS/TCP/TLS déjà payés par les appels HTTP du même hôte
  (`WsTransport.kt:48-59`, `Http.kt:72-84`).
- `callTimeout` est neutralisé pour le socket (il borne une requête, pas une session) :
  `WsTransport.kt:59`. C'est le client qui borne **chaque frame** (voir §4).
- Un socket apparemment vivant mais silencieux (conteneur arrêté, redéployé) est fermé au
  premier dépassement de délai, et la frame suivante rouvre le handshake :
  `WsLiveClient.kt:158-176`.
- **Aucun repli HTTP pour les frames.** L'UI n'a que deux libellés, « WebSocket » et
  « WebSocket KO » (`app/src/main/java/dev/sam3vision/app/LiveMessages.kt:20-24`), et le
  démarrage du direct ne fait plus que `LiveMaskTransport.clientFor(base, marks = chronology)`
  (`app/src/main/java/dev/sam3vision/app/LiveViewModel.kt:200`, via `LiveMaskTransport.kt:38-39`).

### `POST /live` : écrit, jamais appelé pour une frame

Le corps du message WebSocket est **exactement** le corps de `POST /live` plus l'`id`
(`WsLiveClient.kt:109-122`, `core/src/main/java/dev/sam3vision/core/net/Json.kt:298-318`,
verrouillé par `core/src/test/java/dev/sam3vision/core/live/LiveWireGuardTest.kt:51-70`).
La route HTTP subsiste dans `HttpLiveClient.live()` (`HttpLiveClient.kt:53-61`), mais :

- la fabrique de production ne rend que le client WebSocket, et ne garde le client HTTP que
  pour `/health` (`core/src/main/java/dev/sam3vision/core/net/LiveClientFactory.kt:19-39`) ;
- les appelants de production passent tous par `LiveMaskTransport.clientFor` :
  `LiveViewModel.kt:200` (Direct) et `LiveProbe.kt:64-65,178` (iSee) ;
- `POST /live` n'est plus exercé que par les tests JVM (`LiveContractTest.kt:22`).

Preuve par recherche — `git grep -n "POST /live"` dans cet arbre (27/10/2026) ne renvoie que
des commentaires, de la documentation et des tests ; **aucun site d'appel** :

```
AGENTS.md:99,102        « n'est plus appelé », HTTP gardé pour les tests JVM
NOTES.md:113,116        « n'est plus appelé en production », POST pour les tests JVM
README.md:37,44         « ne passe plus par POST /live », tests JVM uniquement
app/…/isee/IseeCameraPane.kt:35 mesure d'une époque où il servait
core/…/Json.kt:184,204,289        commentaires de forme de corps
core/…/LiveClientFactory.kt:6     « a abandonné POST /live pour les frames »
core/…/WsLiveClient.kt:109,122    commentaires du contrat WS
core/…/WsTransport.kt:34          commentaire du chemin
core/…/predict/ObjectAssociator.kt:7  commentaire
core/src/test/…/LiveContractTest.kt:12      test JVM de la route
core/src/test/…/WsLiveContractTest.kt:93    test JVM du barème d'erreurs
core/src/test/…/LiveWireGuardTest.kt:51     test JVM de la forme du corps
docs/ARCHITECTURE.md:174, docs/ELEVENLABS-TTS.md:167,
docs/OPTIMISATIONS.md:92   documentation
```

Le seul code qui appelle réellement `POST /live` est `HttpLiveClient.live()`, lui-même atteint
par les tests JVM seulement. Le libellé « le POST a été retiré » est explicite dans
`LiveMessages.kt:24`.

## 2. Une frame = un message JSON, corrélé par `id`

**Requête** (un message texte par frame) — `Json.kt:298-318` :

```json
{"id":7,"image_b64":"<JPEG base64>","prompts":["person","door","bicycle","car","truck","electric scooter"],
 "work_edge":512,"max_objects":8,"mask_encoding":"bits"}
```

| Champ | Type | Sens |
|---|---|---|
| `id` | entier | corrélation ; absent de `POST /live`, présent sur chaque trame WS |
| `image_b64` | chaîne | JPEG encodé en base64, sans bourrage de ligne |
| `prompts` | tableau ou `null` | concepts suivis ; `null` accepté (`Json.kt:309`) |
| `work_edge` | entier | côté long de travail (`512` envoyé par le client, `768` côté serveur si absent ; voir §4) |
| `max_objects` | entier | plafond d'objets demandé au serveur |
| `mask_encoding` | chaîne | `"bits"` demandé ; le serveur peut répondre autre chose |

**Réponse** — `Json.kt:321-333`, exemple de forme : `NetContractHarness.kt:106-113` :

```json
{"id":7,"width":512,"height":1138,"elapsed_ms":118,
 "objects":[{"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"}],
 "passage":{"blocked":true,"side":"left","label":"person","box":[1,2,30,40],"score":0.91},
 "mask_encoding":"bits","timings_ms":{"total":104.2}}
```

- `id` doit être **au premier niveau** et de préférence **en tête** : le client le lit sans
  parser le JSON (`leadingId`, `Json.kt:229`), et ignore un message dont l'`id` n'est pas
  en vol. Un `id` absent, illisible ou niché dans un sous-objet vaut `-1` → message ignoré.
- `objects[]` : `label` (défaut `"objet"`), `score`, `box` (4 flottants), `mask_b64`
  (`Json.kt:40-53`).
- `mask_encoding` absent ⇒ `"png"` (ancien décodeur) ; valeurs connues `bits`, `png`, `none`
  (`Json.kt:323`, défaut `MASK_ENCODING_PNG` ; `Models.kt:18`, consts `:59-60`).
- `timings_ms` est facultatif : postes serveur flottants, carte vide si absent
  (`Json.kt:336-346`).
- `passage` est facultatif : couloir bouché par une personne (`blocked`), côté
  conseillé (`side` : `"left"`/`"right"`, `null` sur voie libre) et écho de
  l'obstacle (`label`, `box` en pixels image, `score`). Absent sur un backend
  antérieur ⇒ `null`, la décision gauche/droite reste locale (`Models.kt`,
  `passageOf`, `Json.kt`). Le mot parlé vient de SAM quand le champ est là,
  l'obstacle chiffré (stop, décalage) restant mesuré sur le téléphone
  (`GuidanceAnnouncer.kt`, `ProbeVoice.kt`).
- `elapsed_ms` est la durée serveur annoncée, complétée par `timings_ms` quand le backend
  est instrumenté (`Models.kt:69-82`).

**Erreur dans la trame** — la connexion reste ouverte, l'`id` est celui de la requête
(`Json.kt:196-198`, `NetContractHarness.kt:115-117`) :

```json
{"id":7,"error":{"status":402,"detail":"credits epuises"}}
```

Le `status` suit le **même barème** que les codes HTTP (`Json.kt:208-219`, `Http.kt:190-197`) :
401/403 clé refusée, 402 crédits, 409/429 limite, autres statuts en erreur de réponse.

**Corrélation** : une file **par `id`**, pas une FIFO (`WsLiveClient.kt:51-52`, `:61-68`).
Conséquences pour un serveur : il peut répondre **dans le désordre**
(`core/src/test/java/dev/sam3vision/core/net/WsLiveContractTest.kt:131-147`) ;
un message d'`id` inconnu est ignoré sans effet (`WsLiveClient.kt:131-137`) ; l'attente est
enregistrée **avant** l'ouverture du socket, sinon une connexion refusée laisserait la frame
pendue (`WsLiveClient.kt:59-68`).

## 3. Les routes HTTP autour du direct

`/health`, `/segment`, `/release`, `/live` et `/live/ws` partagent la **même base** — la base
SAM résolue par les réglages (`app/src/main/java/dev/sam3vision/app/SamEndpoint.kt:13-14`,
`app/src/main/java/dev/sam3vision/app/LiveState.kt:186`). `/video` fait exception : il vise un
endpoint distinct injecté au build (`BuildConfig.SAM31_ENDPOINT`,
`app/src/main/java/dev/sam3vision/app/tap/TapConfig.kt:35`,
`app/src/main/java/dev/sam3vision/app/tap/TapCaptureScreen.kt:171`).

| Méthode | Chemin | Corps | Délai client | Réponse |
|---|---|---|---|---|
| `GET` | `/health` | — | 180 s (client de réveil) | `{ok, model, device, gpu}` |
| `POST` | `/segment` | `{"image_b64","prompts"}` | 60 s | `{width, height, objects[]}` |
| `POST` | `/video` | multipart `model`,`video`,`prompt` | 120 s | texte ou `{text\|description\|answer\|caption}` |
| `POST` | `/release` | `{}` + en-tête `x-sam-release: confirm` | 45 s | `{released: bool}` |
| `POST` | `/live` | idem trame WS **sans** `id` | pas de plafond global (read 60 s) | idem réponse WS **sans** `id` |

Le délai de 10 s (`LIVE_TIMEOUT_SECONDS`) ne s'applique **plus** qu'à la frame WebSocket ;
`POST /live` garde le client HTTP du direct, sans `callTimeout`
(`HttpLiveClient.kt:8-13`, `:37`, `Http.kt:56-67`).

### `GET /health` — réveil, pas sonde

- Client : `HttpLiveClient.health()`, `GET /health` sur le client de réveil
  (`HttpLiveClient.kt:69-75`) ; réponse `ok`, `model`, `device`, `gpu`
  (`Models.kt:86-90`).
- **Pourquoi un délai de 180 s** : la requête qui réveille un conteneur Modal froid attend
  son démarrage — image + poids ≈ 30-50 s mesurés, jusqu'à ~150 s si l'image doit être
  reconstruite (`HttpLiveClient.kt:15-21`, `WAKE_TIMEOUT_SECONDS`).
- **Pourquoi ce n'est plus une sonde** : appelée périodiquement, `/health` sortait la carte
  de veille (~20 s facturées) **sans aucune analyse** et tenait une A100 allumée en continu
  à 2,415 $/h. La sonde a été supprimée le 26/10/2026
  (`app/src/main/java/dev/sam3vision/app/live/LiveWakeGuard.kt:10-12`, `CHANGELOG.md:266`) ;
  le réveil passe désormais par la **première frame**, que Modal met en file pendant le
  démarrage (`LiveViewModel.kt:214,319`, fenêtre d'attente 60 s :
  `LiveWakeGuard.kt:41-43`).
- Il reste **un** appel `/health` dans l'app : le bouton « Tester SAM » des Réglages, via
  `HttpSamClient` (`app/src/main/java/dev/sam3vision/app/vm/AppDiagnostics.kt:54-72`). Aucun
  écran n'appelle plus le `/health` du client direct.

### `POST /segment` — la photo

`{"image_b64": …, "prompts": … ou null}` sur `application/json`
(`core/src/main/java/dev/sam3vision/core/net/HttpSamClient.kt:17-26`) ; réponse `width`,
`height`, `objects[]` avec les mêmes champs d'objet que le direct (`HttpSamClient.kt:36-47`).
Délai par défaut de 60 s (`Http.kt:28`).

### `POST /video` — la vidéo (multipart)

`multipart/form-data` : `model=sam3.1-video`, `video` (fichier MP4), `prompt` si non vide
(`core/src/main/java/dev/sam3vision/core/net/HttpSam31VideoClient.kt:57-64`) ; chemin
`/video`, délai 120 s (`HttpSam31VideoClient.kt:66-71`). Le multipart évite les +33 % du
base64 sur une vidéo de plusieurs Mo (`HttpSam31VideoClient.kt:17-22`). La réponse est lue de
façon tolérante : `text`, `description`, `answer`, `caption`, sinon corps brut, bornée à
600 caractères (`HttpSam31VideoClient.kt:74-97`). Si `TapConfig.ON_DEVICE_INFERENCE` est vrai,
le client refuse sans appel réseau (`app/src/main/java/dev/sam3vision/app/tap/TapConfig.kt:48`,
`HttpSam31VideoClient.kt:36`).

### `POST /release` — extinction du GPU

`POST /release` avec un corps `{}` et l'en-tête **`x-sam-release: confirm`** — sans lui, le
backend répond 400 (pas d'extinction par erreur) ; délai 45 s parce que la requête peut
arriver sur un conteneur endormi (`core/src/main/java/dev/sam3vision/core/net/SamLifecycle.kt:10-48`).
Réponse : `{"released": true}` ; 401/402/403/429 remontent une erreur typée au lieu d'un
`false` muet (`SamLifecycle.kt:20`, `:58-67`). Appelé quand l'app quitte le premier plan
(`app/src/main/java/dev/sam3vision/app/MainActivity.kt:135`, `:156` →
`AppDiagnostics.kt:97-114`).

## 4. Cadence et dimensionnement

### Direct (écran « Direct »)

| Règle | Valeur | Référence |
|---|---|---|
| Frames en vol | **1** (les autres sont jetées, jamais mises en attente) | `core/src/main/java/dev/sam3vision/core/live/FrameGate.kt:22`, `LiveSession.kt:52-55` |
| Palier rapide | 350 ms | `core/src/main/java/dev/sam3vision/core/live/LiveSession.kt:34` |
| Palier cible | 500 ms | `LiveSession.kt:34` |
| Palier lent | 900 ms | `LiveSession.kt:34` |
| Intervalle appliqué | `> 900 ms` → 900, `< 350 ms` → 350, sinon 500 | `LiveSession.kt:153-157` |
| Image envoyée | côté long **512 px**, JPEG **q65** | `app/src/main/java/dev/sam3vision/app/LiveScreen.kt:50-51`, `:308`, `:331` |
| `work_edge` | **512** (**768** côté serveur si absent) | `LiveSession.kt:211`, `server/backend/live_fast/service.py:36` |
| `max_objects` | 8 par défaut, borné 1..32 côté client | `LiveSession.kt:199`, `core/src/main/java/dev/sam3vision/core/model/ObjectLimits.kt:16-19` |
| Budget de frame | 10 s (`withTimeoutOrNull`) | `WsLiveClient.kt:27`, `:156` |

### Écran caméra iSee

| Règle | Valeur | Référence |
|---|---|---|
| Créneaux | **1** | `LiveSession.kt:43-48` (`concurrency = 1`) |
| Budget de latence | **150 ms** | `LiveSession.kt:48` |
| Plancher / cible | 0 ms : on repart dès qu'un créneau se libère | `LiveSession.kt:44-45` |
| Palier lent | 900 ms | `LiveSession.kt:46` |
| Image envoyée | côté long **320 px**, JPEG **q55** | `app/src/main/java/dev/sam3vision/app/isee/FramePolicy.kt:17`, `:20` ; `IseeCameraPane.kt:42`, `:176`, `:245` |
| Direct aidant | côté long **480 px**, JPEG **q70**, **15 im/s** | `IseeCameraPane.kt:56`, `:59`, `:257` ; `core/src/main/java/dev/sam3vision/core/share/ShareStream.kt:11` |
| Cadence | `Cadence.Fast` | `app/src/main/java/dev/sam3vision/app/isee/LiveProbe.kt:181` |
| Délai normal / première frame | 10 s / 60 s | `LiveProbe.kt:481`, `:484` |

Régulation de l'écran iSee : au-delà du budget de latence, le régulateur **referme** à un
créneau ; il ne rouvre que sous la moitié du budget (`RELAX_RATIO = 0.5`) et après trois
mesures (`REGULATE_MIN_SAMPLES = 3`), la première frame payant le démarrage du conteneur
(`LiveSession.kt:99-112`, `:168`, `:186`, `:189`). Une frame encodée peut attendre en **réserve**
(une seule, remplacée, jamais empilée) pendant que la précédente est en vol
(`LiveProbe.kt:228-260`, `core/src/main/java/dev/sam3vision/core/live/LatestFrameSlot.kt:45`).

### Délais du client HTTP du direct

`connect` 3 s, `write` 5 s, `read` 60 s, `callTimeout` 0, `retryOnConnectionFailure(false)`,
pool 8 connexions inactives / 5 min (`Http.kt:56-67`). Le corps d'une frame est **one-shot** :
OkHttp ne le rejoue jamais, donc pas de seconde inférence facturée (`Http.kt:98-112`).

## 5. Concepts suivis : 18 (plafond 18)

- Liste par défaut : `["person", "car", "bus", "bicycle", "motorcycle",
  "pedestrian traffic light", "pedestrian signal head", "curb", "sidewalk",
  "crosswalk", "pole", "stairs", "keys", "gate", "souvenir shop", "beanie",
  "socks", "wall", "stroller", "child", "dog"]`
  (**21 concepts extérieurs**, ordres user des 28/10-01/11 : la tête du feu
  piéton suit le caisson depuis le 31/10, la couleur s'y lit en local ; les
  murs depuis le 31/10 ; les souvenirs et les corps de la foule depuis le
  01/11 ; **fence et electric scooter sont sortis le 01/11 ; bus,
  vélo, moto et trottoir sont revenus** — le trottoir sert de rail de maintien
  (`SidewalkKeep`), pas d'obstacle — la Traversée les rétablit quand elle s'arme, via
  `VehicleConcepts.promptsFor` + curb/sidewalk). Plafond client **et** serveur :
  **22** (marge pour « Find the X »), un serveur resté à 18 refuserait la
  frame —
  `core/src/main/java/dev/sam3vision/core/live/LivePrompts.kt`.
- Preset extérieur : `["person", "pole", "street lamp", "bollard", "fence", "barrier",
  "gate", "sign", "tree", "trash can", "sidewalk", "crosswalk", "animal",
  "pedestrian traffic light", "pedestrian signal head", "stroller", "child",
  "electric scooter"]` (18 concepts de cheminement : mobilier +
  trottoir, animaux, feu piéton et sa tête — détecté en permanence hors
  traversée ; pastille « Extérieur » du direct, `LivePrompts.outdoor`).
- Liste d'intérieur : `["person", "door", "chair", "table", "stairs", "wall",
  "window"]` (7 objets d'appartement, `LivePrompts.indoor`) — la sonde iSee
  la suit quand le monde est « dedans » (`Settings.outdoorMode = false`,
  `LivePrompts.forMode`) ; une liste vide ou un défaut passé à
  `configureFrom` bascule sur le monde, une liste explicite (Traversée,
  champ « Suivre ») traverse inchangée
  (`app/src/main/java/dev/sam3vision/app/isee/ProbeWiring.kt`). V1 : seuls
  les prompts basculent, la portée reste 4 m dans les deux mondes.
- Plafond : `LivePrompts.MAX = 18`. Le client n'en envoie jamais plus ;
  le backend suit (`MAX_PROMPTS = 18` sur `/live`, `PROMPT_LIMIT = 18`
  côté tracker).
- Longueur maximale d'un concept : 32 caractères (`LivePrompts.kt:37`).
- `normalize()` retire les espaces, les vides et les doublons, rejette les concepts trop
  longs, borne à `MAX`, et retombe sur la liste par défaut si le résultat est vide
  (`LivePrompts.kt:44-52`). La liste est normalisée **une fois** par session
  (`LiveSession.kt:83`).

## 6. Les identités stables sont reconstruites sur le téléphone

Le direct **ne reçoit aucun identifiant d'objet** : la réponse d'une frame ne porte que
`label`, `score`, `box`, `mask_b64` (`Json.kt:40-53`). Le serveur du direct ne suit donc pas
les objets d'une frame à l'autre.

L'identité « c'est la même personne » est attribuée **localement**, par
`core/src/main/java/dev/sam3vision/core/live/LiveTimeline.kt` : même `label` **et** centre
horizontal proche (`matchRadius = 0.18` de la largeur), dans la fenêtre
`trackTimeoutMs = 2 500 ms` ⇒ même piste ; sinon nouvel `id` (`LiveTimeline.kt:10-20`,
`:85-105`, `:146-158`). Mémoire : 60 s, 600 frames maximum (`LiveTimeline.kt:148`, `:151`).

Le tracker `sam3-track` (`POST /session`, `POST /frame`, `DELETE /session/{id}`,
`GET /health`) renvoie bien des `id` stables
(`core/src/main/java/dev/sam3vision/core/net/TrackClient.kt:23-31`,
`core/src/main/java/dev/sam3vision/core/live/TrackRunner.kt:20-30`). C'est un **second
transport du direct**, à la demande : la pastille « Tracker » du bandeau du haut construit
`HttpTrackClient` + `TrackRunner` (`app/LiveViewModel.kt`), qui remplacent alors la boucle
`/live`. **Le défaut reste `/live`** : c'est donc l'association locale qui fournit les
identités dans le cas courant, et le serveur seulement quand le tracker est actif
(`docs/TRACK-PERF.md`, § 2). L'association locale est décrite dans
`core/src/main/java/dev/sam3vision/core/predict/ObjectAssociator.kt:3-9`.

## 7. Écrire un serveur compatible — la liste minimale

1. Ouvrir un WebSocket sur `/live/ws` (même hôte que le HTTP, `wss` si `https`).
2. Accepter des messages texte JSON : `id`, `image_b64`, `prompts`, `work_edge`,
   `max_objects`, `mask_encoding` (§2). Ignorer les champs inconnus.
3. Répondre **un message par requête**, avec le **même `id`** en tête, `width`, `height`,
   `elapsed_ms`, `objects[]` (`label`, `score`, `box`, `mask_b64`) et, si possible,
   `mask_encoding` et `timings_ms`. L'ordre des réponses est libre.
4. En cas d'erreur : `{"id":…,"error":{"status":…,"detail":…}}` **sans fermer** la connexion.
5. Servir `GET /health` (`ok`, `model`, `device`, `gpu`), `POST /segment`,
   `POST /video` (multipart), `POST /release` (en-tête `x-sam-release: confirm`).
6. Tolérer **une** frame en vol par client pour le Direct, et des envois espacés de 350 ms
   au mieux ; le client jette, il ne met pas en file.

## 8. Ce que ce document ne peut pas prouver

- **Le conteneur réellement déployé.** Le source de référence est dans `server/`
  (`backend/live_fast/` : `service.py`, `live_ws.py`), mais la version qui sert, ses leviers
  (`FAST_*`) et sa région ne se lisent que sur son `/health` (champs `levers`/`applied`) —
  voir `server/docs/LIVE-FAST.md`. Ce document décrit ce que le client **envoie et sait
  lire**, pas ce qui sert au moment d'un test.
- **Les débits.** Aucun débit n'est mesuré ici. Les chiffres cités dans les commentaires du
  code (WebSocket contre POST, DNS/TCP/TLS, réveil) sont des mesures **rapportées par le
  commentaire**, non reproduites pour ce document.
- **Les coûts GPU.** Le 2,415 $/h d'une A100-40GB vient de `CHANGELOG.md:623` et
  `docs/MODE-US.md:116-117` ; ce n'est pas une mesure faite ici, et le tarif peut changer.
- **Le mode tap.** La route qu'il utilise, `POST {endpoint}/video` en multipart
  (`docs/MODE-TAP.md:146-158`), n'a **aucune implémentation connue côté serveur**
  (`git grep -n '"/video"' server/backend` vide) : aucun contrat de réponse n'est écrit,
  seulement la lecture tolérante du client (`HttpSam31VideoClient.kt:89-95`). La question
  « cloud ou on-device ? » reste ouverte (`TapConfig.kt:48`).
- **La troncature des concepts côté serveur.** `PROMPT_LIMIT = 18`, affirmé par le commentaire
  (`LivePrompts.kt`) **et** le code du tracker (`server/backend/tracking.py:54`) — mais
  le plafond du déploiement `/live` ne se lit que sur le serveur.
