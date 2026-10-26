# MASQUES SAM + STATS DE DEBUG (accueil iSee)

Ce que l'utilisateur voit sur la page caméra de l'app, et pourquoi.
Relu dans le **code de cet arbre** le 2026-10-26 (commit unique de ce dépôt) : les affirmations
ci-dessous sont vérifiées fichier par fichier. Les mesures citées restent **datées et attribuées** ;
ce qui ne vient pas d'une mesure est marqué « non mesuré ».

## Ce qui s'affiche

Pendant l'enregistrement vidéo — dans la fenêtre caméra iSee, **un tap démarre le REC, un second
l'arrête** (`IhearViewModel.kt:248-251`, gestualité dans `IseeVisionCard.kt`) :

- les **masques SAM** de la dernière frame analysée, colorisés et posés sur l'aperçu au cadrage exact de la
  `PreviewView` (`CENTER_CROP` dans la vue native, `app/…/CameraSurface.kt:26-28`) ;
- les **pastilles de distance** par objet (`≈ 1,1 m`, composées **après** l'`AndroidView`, voir z-order) ;
- la **ligne d'état**, texte exact produit par `liveProbeHeadline` (`app/…/ihear/LiveProbeText.kt:18-25`) :
  `SAM · X ips · Y ms · N objets · plus proche ≈ Z m` — le suffixe « plus proche » n'apparaît que si une
  distance est mesurée. **Le nom du GPU n'est plus dans cette ligne** : l'exemple historique
  `SAM · 1,4 ips · 640 ms · 8 objets · A100-40GB` est **périmé** — le GPU, quand il est connu, est sur
  sa propre ligne de détail `gpu : …` (`LiveProbeText.kt:55`). Le commentaire de tête de
  `liveProbeHeadline` (`LiveProbeText.kt:15-16`) a d'ailleurs gardé « · A100-40GB » : **commentaire
  périmé**, la fonction ne l'ajoute plus (`:24`).
- le **détail** (`liveProbeDetails`, `LiveProbeText.kt:39-59`) : `endpoint :` (raccourci à 46 caractères,
  `:9` et `:73-79`), `frame : L×h`, `latence : Y ms · p95 P ms · serveur S ms`, `F frames · M masques`,
  `transport : WebSocket` (ou `WebSocket KO`), `suivis (N) :`, `reconnus :`, `gpu :`, `distances :`
  (3 max, du plus proche au plus loin — `DISTANCE_LINES_MAX`, `:12`), `dit : <phrase prononcée>`,
  `erreur : <dernière>`. En mode dense (petite fenêtre flottante), le détail est réduit aux erreurs et
  `endpoint`/`frame`/`gpu` sont retirés (`LiveDebugPanel.kt:43-47`, `:92-99`) — l'ancienne note
  « panneau inchangé pendant le REC » est donc fausse : le panneau **s'allège** en dense.

Avant la première frame analysée, un message central s'affiche (`liveProbeNotice`,
`LiveProbeText.kt:89-94`) : `réveil du GPU SAM… (≈ 30 s au premier lancement)` pendant le warming,
puis `en attente de la première analyse…` ; dès la première frame, il disparaît (les masques parlent
d'eux-mêmes).

Après l'arrêt, les derniers masques **restent affichés** jusqu'au prochain enregistrement ou à la sortie
d'écran ; une erreur sur une frame ne remet **pas** le compte d'objets à zéro (`LiveProbe.kt:302-309` :
seul `error` change, `objects` et `masks` sont conservés).

## Coût GPU

Rien ne part à SAM hors enregistrement : l'analyseur caméra livre du JPEG base64 en continu, mais la frame
n'est **même pas convertie** hors REC (`IseeVisionCard.kt:259-266` : `wantsFrame` et `onFrame` sont
conditionnés à `recordingVideo`) et `LiveProbe.onFrame` jette tout si aucune session n'existe. Le GPU ne
se réveille donc pas parce qu'on regarde la page caméra.

Pendant l'enregistrement, la session tourne en `Cadence.Fast` (`core/live/LiveSession.kt:42-48`) :
**pas de plancher artificiel**, **un seul créneau** (`concurrency = 1`) et un **budget de latence de
150 ms** (`latencyBudgetMs = 150L`). Le régulateur ne se prononce qu'après **3 mesures**
(`REGULATE_MIN_SAMPLES`, `:171`) et ne rouvre un créneau que sous **la moitié** du budget
(`RELAX_RATIO = 0.5`, `:168`) — mais avec `concurrency = 1` il n'y a rien à rouvrir : le budget ne sert
qu'à documenter la cible. Motif de ce choix, relevé sur appareil (relevé non rejoué ici) : deux frames
en vol donnaient **417 ms** par frame contre **185 ms** à un seul, serveur inchangé à ~120 ms
(commentaire de `Cadence.Fast`) — le backend sérialise de toute façon sur un verrou GPU.

⚠️ Ce paragraphe a dit « budget 200 ms, jusqu'à 2 requêtes en vol » : **inexact** pour cet arbre (le code
dit 1 créneau / 150 ms). L'ancienne formule « une seule requête en vol (`FrameGate`) et cadence
350–900 ms » décrivait le direct (`Cadence.Direct`), pas la sonde iSee. L'estimation « un direct de 10 s
≈ 10–25 frames » n'a pas été remesurée : **non mesuré**.

Une frame de réserve est convertie pendant que la requête court (emplacement d'une seule image,
`LatestFrameSlot` — `LiveProbe.kt:201-210`) ; au-delà, les frames sont jetées — la scène a changé.

## Branchement

| Élément | Fichier |
|---|---|
| Analyseur de frames + `bind` caméra | `app/…/ihear/IhearCameraPane.kt` (`IhearCameraHost`, côté long **320 px** : `IHEAR_FRAME_EDGE`, `:39`) |
| Envoi `/live/ws`, masques, stats | `app/…/ihear/LiveProbe.kt` (l'appel passe par `liveClient()`, **WS uniquement**) |
| Coloriage des masques (tampons réutilisés) | `app/…/ihear/MaskPainter.kt:37-50` |
| Formatage (pur, testé JVM) | `app/…/ihear/LiveProbeText.kt` (21 tests `LiveProbeTextTest`) |
| Panneau à l'écran | `app/…/ihear/LiveDebugPanel.kt` |
| Rendu des masques sur l'aperçu | `app/…/CameraSurface.kt` (partagé avec le Direct, `LiveScreen.kt:194-198`) |
| Pastilles de distance | `app/…/DistanceOverlay.kt` (`LiveDistanceLayer`, `:38`) + câblage `IseeVisionCard.kt:144-149` |
| Câblage Réglages → sonde | `app/…/ihear/ProbeWiring.kt` (`configureFrom`, `:22-31`) |

> **Piège de z-order (incident « les masques étaient dessinés sous l'aperçu caméra », corrigé depuis)** :
> un `AndroidView` est une vraie `View`, dessinée par le `ViewGroup` **après** le canevas Compose. Un
> masque ou une pastille composés en Compose **avant** l'`AndroidView` passe donc *derrière* l'aperçu —
> invisible, alors que le backend annonce des objets. D'où deux protections dans le code : la
> `PreviewView` et la couche de masques sont deux enfants d'un même `FrameLayout`
> (`CameraSurface.kt:12-23` pour l'explication, `:32-35` pour l'ordre des enfants), et
> `LiveDistanceLayer` est composé **après** l'`AndroidView` dans les deux écrans
> (`IseeVisionCard.kt:144-149`, `LiveScreen.kt:194-198`).

**Endpoint** : plus simplement `Settings.samBaseUrl` — c'est `resolveSamEndpoint` : **saisie manuelle
(hors preset) > URL injectée au build pour la région > preset de la région EU/US choisie**
(`core/…/settings/SamRegion.kt:46-55`, câblée `ProbeWiring.kt:22-31` ; `sam.base.url` /
`sam.us.base.url` de `local.properties`). Piège corrélé : une URL qui n'est **que** le preset d'une
région est effacée au chargement (`SettingsSeed.dropPresetUrl`, `app/…/SettingsSeed.kt:46-47`) — sans
ça la région ne s'appliquerait jamais. Vide ⇒ le panneau affiche `SAM · aucun endpoint (Réglages)`
(`LiveProbe.kt:156` + `liveProbeHeadline` sur erreur sans frame) et rien ne part.

## Décodage et colorisation des masques

La chaîne réelle, vérifiée dans `app/…/ihear/MaskPainter.kt`, `core/…/mask/Masks.kt` et `MaskCore.kt` :

1. **base64 tolérant** (préfixe `data:…;base64,` et espaces acceptés) → octets (`MaskCore.base64Bytes`,
   `MaskCore.kt:51-66`) ;
2. **décodage selon l'encodage annoncé par le backend** (`decodeMaskAny`, `Masks.kt:64-68`), trois cas :
   - `"bits"` → `decodeBitsMask` (`MaskCore.kt:157-174`) : format `M1` du backend (magic, largeur et
     hauteur uint16, puis zlib de lignes 1 bit/pixel), pur Kotlin, **aucun décodeur d'image** ;
   - `"png"` → `decodeGrayMask` (`Masks.kt:46-55`) : PNG gris 8 bits décodé en `ALPHA_8`
     (`GRAY_OPTIONS`, `Masks.kt:16-19`) pour éviter l'expansion ARGB_8888 (420 Ko au lieu de 105 Ko par
     masque), avec repli ARGB + luminance si le décodeur ne rend pas un bitmap `ALPHA_8` ;
   - `"none"` → `null` (le serveur n'envoie pas de masque : l'app dessine la boîte).
   Encodage absent ou inconnu ⇒ PNG. Un masque illisible est **silencieusement absent** de la liste
   (`mapIndexedNotNull`, `MaskPainter.kt:39-40`) — la ligne `F frames · M masques` du détail trahit
   l'écart ;
3. **colorisation par table** : couleur d'objet = `maskColorAt(index)` (cycle de la palette
   `Theme.MASK_COLORS`, `app/…/Theme.kt:36`), opacité de base **`MASK_ALPHA = 115`** (`Theme.kt:34`),
   et une table de 256 niveaux par couleur (`alphaLut`, `MaskCore.kt:94-100`) module l'alpha de chaque
   pixel par son niveau (niveau 0 = transparent). Plus aucune division par pixel — l'ancien chemin, qui
   repassait par une luminance, décolorait un masque déjà gris (`Masks.kt:70-75`) ;
4. **sortie `ARGB_8888`**, tous les masques à la taille de la frame, fusionnés en un seul `Bitmap` par
   `CameraSurface.merge` (`CameraSurface.kt:71-79`) et affichés en `CENTER_CROP` — cadrage identique à la
   `PreviewView` (`FILL_CENTER`). Le direct colorise par la même classe (`LiveProbe.kt:99`, `:415-422`).

Couverture JVM : `core.mask.MaskCoreTest` **20**, `core.mask.MaskRobustnessTest` **20** (base64 à
préfixe `data:`, IHDR, luminance, colorisation, masque 255, formats hostiles) et
`core.mask.MaskGoldenTest` **2** (fixtures dorées : 6/6 au pixel près, IoU 1) ; formatage du panneau
`app.ihear.LiveProbeTextTest` **21**. Le rendu bitmap lui-même (décodage Android) n'est pas testé en JVM.

### Une seule variante de codec (arbitrage)

Le client ne lit **qu'une** variante compacte : `bits` (format `M1`). `png` reste le repli historique
— encodage absent ou inconnu ⇒ `png` — et `none` veut dire « pas de masque ». Rien d'autre n'est
retenu : ni RLE LEB128, ni polygones, ni conteneur auto-décrit (« bis »). Aucun décodeur ne les lit
ici, et en ajouter un ferait échouer `MaskGoldenTest` : c'est la porte anti-dérive du format.

Pourquoi trancher ainsi : `bits` est **sans perte** — IoU mesurée **1,000** sur six formes (disque,
rectangle, anneau à trou, bruit, ellipse, deux disques), bruit compris — et plus léger que le PNG
gris 8 bits à fidélité égale. Poids, méthode et « non mesuré » : `docs/MESURES-u11c.txt`, régénéré
par `python3 scripts/gen_mask_golden.py --write`.

Les fixtures dorées (`core/…/mask/MaskGoldenData.kt`, fichier généré par ce script) viennent de
l'encodeur **Python** du format `M1`, pas de l'encodeur Kotlin des tests : les deux lectures ne
peuvent plus se tromper ensemble (`--selftest` réencode à l'octet près six vecteurs réels du dépôt).
La fidélité de `bits` côté client n'est donc pas supposée, elle est mesurée.

## Recette

1. Réglages : région EU ou US, ou Base URL SAM manuelle (« Tester SAM » interroge `GET /health` — c'est le
   seul endroit d'où part encore cette requête ; les frames partent en WS). Quitter les Réglages.
2. Accueil iSee : un tap sur la fenêtre caméra → « ● REC ». **Le premier appel paie le réveil du
   conteneur : 23,6–26,8 s relevés (30–50 s dans le code) ; il ne doit PAS afficher d'erreur de
   timeout** — le délai du handshake suit celui de la frame (`LiveClientFactory.kt:15-17`), et le premier
   appel dispose d'une **session froide à 60 s** (`LiveProbe.COLD_TIMEOUT_SECONDS`, `LiveProbe.kt:458`).
   Il n'y a **plus de boucle de réveil `/health`** (elle tenait la carte allumée en continu) : c'est la
   première frame qui réveille le conteneur (`LiveProbe.kt:130-138`).
   Un timeout à la première frame afficherait `réveil du GPU en cours… (nouvel essai à la frame
   suivante)` : signe que le froid n'a pas tenu.
3. Vérifier : un masque tombe **sur** l'objet ; `transport : WebSocket` dans le détail ; la ligne `gpu :`
   est seule sur sa ligne ; le nombre d'objets bouge ; le p95 — l'ancienne cible « sous ~1 000 ms »
   (avant WS) n'a pas d'équivalent mesuré en WS : à re-caler sur place, **non mesuré** en REC WS.
4. Second tap : « ● REC » part, les masques restent, la vidéo est archivée.

## Distances et voix pendant l'enregistrement

La page caméra porte les trois choses au même endroit, pendant le REC :

| Élément | Où | Source |
|---|---|---|
| masques SAM | sur l'aperçu (`CameraSurface`, z-order ci-dessus) | `/live/ws` |
| pastilles « ≈ 1,1 m » | par objet, `LiveDistanceLayer` composé **après** l'`AndroidView` (`IseeVisionCard.kt:144-149`) ; **sans nom de concept** en REC (les masques disent déjà de quel objet il s'agit) ; avec concept sur la photo du double tap (`withLabel = true`, `:104`) | `DistanceMeter` (100 % téléphone) |
| distance du plus proche | ligne d'état `… · plus proche ≈ 1,1 m` | idem |
| latences + transport | détail du panneau (`latence : … · p95 … · serveur … ms`, `transport :`) | `/live/ws` |
| ~~GPU~~ | la ligne `gpu :` du détail **n'est pas alimentée** ici : `LiveProbeState.gpu` n'est que recopié d'un état à l'autre (`LiveProbe.kt:152`, `:178`, `:192`) — à rebrancher ou à retirer | — |
| voix « Personne à gauche, à 1,4 mètre » | ElevenLabs, une annonce par scène (`dit :` garde la dernière en mémoire) | `PositionAnnouncer` + `DistanceText.spoken` |

Exemple relevé sur téléphone : `SAM · 1,5 ips · 622 ms · 7 objets · plus proche ≈ 1,1 m · A100`
— **le tronçon `· A100` de la ligne d'état n'existe plus** (le GPU est passé dans le détail) ; garde
pour l'ordre des valeurs, pas pour la forme.

- Bascule des Réglages : « Annoncer les objets et leur distance » (`ttsAnnouncePositions` **ET** clé ElevenLabs,
  `Settings.announcePositions` `Settings.kt:131`). Coupée **ou** clé absente ⇒ **aucune phrase, aucun message
  d'erreur** (piège (b) de `docs/TESTING.md`) ; les distances restent affichées.
- Bascule « Distances » (`distanceOverlayEnabled`) ⇒ plus de mesure du tout (`DistanceMeter.enabled`).
- Focale en REC : `LensSpec.fromSettings(settings.cameraFovDeg)` (`ProbeWiring.kt:26`) — champ forcé (borné
  15–130°), sinon repli 67° ; **la lecture du capteur n'alimente ici que les pastilles de la photo** du double
  tap (`IhearPhotoDistances.chosenLens`, `:53-56`), pas la sonde.
- La voix ne coupe jamais une réponse du chat (`playerIdle()`, `ProbeWiring.kt:37-48`), et une scène déjà dite
  n'est pas répétée (`PositionAnnouncer` + `LiveProbeState.spoken`).
- **Mesure non lissée** pendant le REC : pas de journal de pistes sur cette page (`LiveProbe.readingsOf`) —
  la pastille bouge un peu plus qu'en Direct.