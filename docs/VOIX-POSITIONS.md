# Annonce vocale des positions — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-26 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé, cache) est dans
`docs/ELEVENLABS-TTS.md` ; les mots par langue dans `docs/VOIX-ANGLAIS.md`. Ici : **où est
l'objet**, **quand la voix le dit**, et **comment le vérifier sur un appareil**.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où est** l'objet détecté (« Personne à gauche, à 1,4 mètre »), **une
seule fois** par scène, sans se répéter et sans couper une réponse en cours.

## 2. Trois secteurs, deux seuils

| `centerX` normalisé | Mot (fr) | Mot (en) |
|---|---|---|
| `< 0,34` | à gauche | on the left |
| `0,34 … 0,66` | au centre | in the middle |
| `> 0,66` | à droite | on the right |

- Seuil bas : `SIDE_EDGE = 0.34f` (`core/.../live/PositionText.kt:41`) ; seuil haut
  `1 - SIDE_EDGE` = **0,66** (`PositionText.kt:178`). Les deux bornes sont **exclusives** :
  une boîte centrée à 0,34 pile est « au centre ».
- Même découpe en vertical (`< 0,34` en haut, `> 0,66` en bas, sinon rien,
  `PositionText.kt:183-186`) : c'est un **repère** (« en haut à gauche »), pas un secteur
  annoncé séparément.
- La géométrie est **la même pour toutes les langues** ; la langue ne change que les mots
  (`PositionText.kt:52-88`).

## 3. Ce que la phrase dit

- **Forme** : label + secteur, **sans article** — donc aucune faute de genre sur des
  labels libres (`Personne à gauche, chaise en bas à droite.`, `PositionText.kt:99-121`).
- **Ordre** : gauche → droite (`centerX` croissant, puis label) — `PositionText.kt:107`.
- **Doublons** : même label à la même position cité **une fois** (`PositionText.kt:110`).
- **Plafond** : `MAX_OBJECTS = 3` (`PositionText.kt:44`) puis « et N autres objets »
  (`PositionText.kt:119-121`).
- **Distance** : quand elle est connue, `DistanceText.spoken` la met en mots et les objets
  sont séparés par ` ; ` (la virgule sert déjà à séparer position et distance) —
  `PositionText.kt:113-118`.

## 4. Quand elle parle : trois garde-fous

`core/.../live/PositionAnnouncer.kt` — machine à états **pure** (l'horodatage est fourni) :

1. **stabilité** — la même scène doit être vue **2 frames de suite**
   (`DEFAULT_MIN_FRAMES = 2`, `PositionAnnouncer.kt:88`) : un masque qui clignote ne
   déclenche rien ;
2. **nouveauté** — une scène déjà annoncée reste muette, même si les objets sont toujours
   là. La signature de scène est `label|secteur|distance arrondie au mètre`
   (`PositionText.key`, `PositionText.kt:140-146`) : c'est ce qui fait reparler un objet
   **qui s'approche** (3 m → 2 m → 1 m) sans le faire bavarder à chaque frame ;
3. **délai** — jamais deux annonces à moins de **4 s** (`DEFAULT_COOLDOWN_MS = 4_000L`,
   `PositionAnnouncer.kt:91`).

Plus : **scène vide ⇒ la mémoire de scène retombe** (`onEmptyScene`,
`PositionAnnouncer.kt:73-79`), donc le retour des objets est de nouveau annoncé ; et
`reset()` (`:66-71`) à chaque nouvelle séance, sinon la première scène resterait muette.

## 5. Un seul moteur de voix

L'annonce passe par le moteur **unique** de l'app, jamais par un second lecteur.

| Chemin | Câblage | Garde « ne coupe rien » |
|---|---|---|
| Direct (écran vidéo) | `LivePositionVoice` (`app/.../live/LivePositionVoice.kt`) construit dans `LiveViewModel.kt:95`, voix `VoiceAnnouncer(app, "positions")` (`LiveViewModel.kt:93`, dossier `POSITION_VOICE_DIR = "positions"`, `:410`) | `Mutex speaking` en `tryLock` (`LivePositionVoice.kt:37,62-69`) : une phrase en cours n'est **ni coupée ni doublée** ; rien n'est dit pendant une question mémoire (`asking`, `:50`) |
| iSee (fenêtre caméra) | `LiveProbe.phraseOf` (`app/.../ihear/LiveProbe.kt:394-397`) → `ProbeWiring.observeSpoken` → `ProximityVoice.onPhrase` | `playerIdle()` : si une bulle du chat est en cours, l'annonce **n'est pas jouée** (`ProximityVoice.kt:29-38`) |
| Analyse photo | `AppViewModel.announcePositions` (`AppViewModel.kt:187-198`) | même voix, un seul `say` à la fois |

Réglage : **`Settings.ttsAnnouncePositions`** (défaut `true`, `Settings.kt:28`), bascule
« Annoncer les objets et leur distance » (`app/TtsSettings.kt:74-80`). La bascule effective
est `announcePositions = ttsAnnouncePositions && ttsConfigured` (`Settings.kt:131`) :
**sans clé ElevenLabs ni voix, l'annonce est muette** — par construction, pas par bug.

## 6. Ce qui n'est jamais annoncé

- **Label vide** ou géométrie inutilisable (taille d'image inconnue, boîte < 4 valeurs) :
  l'objet est omis (`PositionText.fromDetection`, `PositionText.kt:80-86`).
- **Objet coupé par le bord haut ou bas** : sa hauteur ne mesure plus l'objet, donc sa
  **distance est écartée** (`Pinhole.isVerticallyCut`, marge **2 px**,
  `core/.../distance/DistanceEstimator.kt:47,114`). Il reste **annoncé sans distance** :
  l'annonce de position ne filtre pas les bords. Le contrat écrit de la lane
  (« aucun objet coupé par le bord n'est annoncé ») n'est donc **pas** tenu tel quel —
  écart documenté, non corrigé ici (hors du périmètre de cette fiche).
- **« Hors zone »** : il n'existe pas de zone « hors champ ». Tout objet dont la boîte est
  lisible reçoit un secteur ; la seule exclusion est celle des lignes ci-dessus.

## 7. Où ça vit

| Élément | Fichier |
|---|---|
| Zones, phrase, signature de scène | `core/.../live/PositionText.kt` (195 l.) |
| Machine 2 frames / 4 s / non-répétition | `core/.../live/PositionAnnouncer.kt` (92 l.) |
| Frame → objets placés + distance | `core/.../live/FrameRoster.kt` (`placedLabels`, `:62-77`) |
| Direct (VM + annonce) | `app/.../LiveViewModel.kt`, `app/.../live/LivePositionVoice.kt` |
| iSee (sonde + voix) | `app/.../ihear/LiveProbe.kt`, `ProbeWiring.kt`, `ProximityVoice.kt` |
| Photo | `app/.../AppViewModel.kt` |
| Réglages | `core/.../settings/Settings.kt`, `app/.../TtsSettings.kt` |

Tests JVM qui verrouillent le contrat : `PositionTextTest` (10), `PositionTextEnglishTest`
(7), `PositionAnnouncerTest` (8), `LivePositionVoiceTest` (5), `SettingsAnnounceTest` (4).

## 8. Recette

### 8.1 Sans GPU — le chemin photo (recommandé)

L'analyse d'une photo suffit à prouver détection → phrase → voix, et le faux serveur SAM ne
sert que `/health` et `/segment` (`tools/mock-sam.py`, docstring) : le **Direct** (WebSocket
`/live/ws`, `core/.../net/WsTransport.kt:35`) n'a **pas** de faux serveur ici et exige le
vrai backend.

```bash
python3 tools/mock-sam.py 8099                    # /health + /segment, aucune carte
adb -s <serial> reverse tcp:8099 tcp:8099         # le téléphone atteint le port du poste
# local.properties : sam.base.url=http://127.0.0.1:8099   puis  ./gradlew :app:assembleDebug
```

Attendus, dans l'ordre :

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet, cf. § 5) et
   bascule « Annoncer les objets et leur distance » **activée** ;
2. analyse d'une photo contenant **trois objets séparés** (gauche, centre, droite) →
   **une** phrase, p. ex. `Personne à gauche, chaise au centre, bouteille à droite.` ;
3. la même photo réanalysée → **aucune** nouvelle annonce (scène déjà dite) ;
4. bascule **coupée** + Enregistrer → l'analyse tourne, **plus aucune** annonce.

### 8.2 Téléphone réel

Jamais d'`adb` nu : tout passe par le verrou partagé.

```bash
~/bin/phone run --wait 600 --task "M86 recette positions" -- adb -s 2c6852ac <commande>
# sortie 2 = occupé par un autre agent : attendre, ne pas forcer (jamais PHONE_FORCE)
```

`install -r` échoue si la signature a changé (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) : ne pas
désinstaller l'app d'un autre agent sans accord — l'app installée peut être le build qu'il
est en train de tester.

Attendu de la porte de sortie : **3 objets → 3 annonces distinctes, aucune répétition
< 4 s**, et silence pendant une question au chat.

### 8.3 État des mesures (26/10/2026, poste `baptiste-MS-7D18`)

| Relevé | Résultat |
|---|---|
| Appareil joignable, libre au moment du relevé | 2312DRA50G, Android 15, écran 1220 × 2712 (`getprop`, `wm size`) |
| App installée | `dev.sam3vision.app` `versionName=1.0.150-bf47723` |
| L'app se lance, `MainActivity` au premier plan | **oui** (`am start` + `dumpsys window`) |
| Bascule « Annoncer les objets et leur distance » visible | **non mesuré** — l'écran capturé par `uiautomator` était celui de l'app des lunettes (appareil partagé) ; **aucun tap à l'aveugle** n'a été fait |
| 3 objets → 3 annonces distinctes, silence < 4 s | **non mesuré** — exige un APK de cet arbre (compilation) **et** une clé ElevenLabs (secret, appel payant) ; le téléphone portait le build d'un autre agent |
| Verrou téléphone | pris puis relâché normalement ; aucun `PHONE_FORCE`, aucune désinstallation |

Le comportement annoncé ci-dessus est donc **lu dans le code et verrouillé par les tests
JVM**, pas mesuré sur appareil — c'est l'état réel au 26/10.