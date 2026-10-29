# Annonce vocale des positions — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-27 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé, cache) est dans
`docs/ELEVENLABS-TTS.md` ; les mots par langue dans `docs/VOIX-ANGLAIS.md`. Ici : **où est
l'objet**, **quand la voix le dit**, et **comment le vérifier sur un appareil**.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où est** l'objet détecté (« Personne à 9 heures, à 1,4 mètre »), **une
seule fois** par scène, sans se répéter et sans couper une réponse en cours.

## 2. Le cadran horaire : sept bandes égales

| Bande (`centerX × 7`, entier) | Heure | Mot (fr) | Mot (en) |
|---|---|---|---|
| 0 | 9 | à 9 heures | at 9 o'clock |
| 1 | 10 | à 10 heures | at 10 o'clock |
| 2 | 11 | à 11 heures | at 11 o'clock |
| 3 | 12 | à midi | at 12 o'clock |
| 4 | 1 | à 1 heure | at 1 o'clock |
| 5 | 2 | à 2 heures | at 2 o'clock |
| 6 | 3 | à 3 heures | at 3 o'clock |

- Heures (`clockHours`, `core/.../live/PositionText.kt:52`) ; `clockHour` (`:59-62`) :
  sept bandes égales, un bord tombe dans la bande du dessus, 1.0 reste à 3 h.
- `SIDE_EDGE = 0.34f` (`:46`) ne sert plus qu'au couloir du guidage
  (`docs/GUIDAGE-TOUT-DROIT.md`) : le parlé n'a plus de repère vertical — la
  hauteur dans l'image dépend du tangage de la caméra, elle n'oriente pas
  quelqu'un qui marche.
- La géométrie est **la même pour toutes les langues** ; la langue ne change que les mots
  (`clockLabel`, `PositionText.kt:69-76`).

## 3. Ce que la phrase dit

- **Forme** : label + heure, **sans article** — donc aucune faute de genre sur des
  labels libres (`Personne à 9 heures, chien à 3 heures.`, `PositionText.kt`).
- **Ordre** : le plus proche d'abord, puis le plus en face (distance, puis écart
  à l'axe, puis label) — `PositionText.kt`.
- **Doublons** : même label à la même heure cité **une fois** (`PositionText.kt`).
- **Plafond** : `MAX_OBJECTS = 3` (`PositionText.kt`) puis « et N autres objets ».
- **Distance** : quand elle est connue, `DistanceText.spokenShort` la met en mots
  au mètre entier et **remplace** l'heure (`Personne à 1 mètre.`) — `PositionText.kt`.

## 4. Quand elle parle : trois garde-fous

`core/.../live/AnnouncementGate.kt` — machine à états **pure** (l'horodatage est fourni),
partagée avec les consignes de guidage (`GuidanceAnnouncer.kt`) :

1. **stabilité** — la même scène doit être vue **2 frames de suite**
   (`DEFAULT_MIN_FRAMES = 2`, `AnnouncementGate.kt:83`) : un masque qui clignote ne
   déclenche rien ;
2. **nouveauté** — une scène déjà annoncée reste muette, même si les objets sont toujours
   là. La signature de scène est `label|heure|distance arrondie au mètre`
   (`PositionText.key`, `PositionText.kt:138-143`) : c'est ce qui fait reparler un objet
   **qui s'approche** (3 m → 2 m → 1 m) ou **qui glisse** (10 h → 11 h) sans le faire
   bavarder à chaque frame ;
3. **délai** — jamais deux annonces à moins de **4 s** (`DEFAULT_COOLDOWN_MS = 4_000L`,
   `AnnouncementGate.kt:86`).

Plus : **scène vide ⇒ la mémoire de scène retombe** (`clearScene`,
`AnnouncementGate.kt:65-69`), donc le retour des objets est de nouveau annoncé ; et
`reset()` (`PositionAnnouncer.kt:48`, → `AnnouncementGate.reset`) à chaque nouvelle
séance, sinon la première scène resterait muette.

## 5. Un seul moteur de voix

L'annonce passe par le moteur **unique** de l'app, jamais par un second lecteur.

| Chemin | Câblage | Garde « ne coupe rien » |
|---|---|---|
| Direct (écran vidéo) | `LivePositionVoice` (`app/.../live/LivePositionVoice.kt`) construit dans `LiveViewModel.kt:126`, voix `VoiceAnnouncer(app, "positions")` (`LiveViewModel.kt:124`, dossier `POSITION_VOICE_DIR = "positions"`, `:497`) | `Mutex speaking` en `tryLock` (`LivePositionVoice.kt:37,62-69`) : une phrase en cours n'est **ni coupée ni doublée** ; rien n'est dit pendant une question mémoire (`asking`, `:50`) |
| iSee (fenêtre caméra) | `LiveProbe.phraseOf` (`app/.../isee/LiveProbe.kt:418-423`) → `ProbeWiring.observeSpoken` → `ProximityVoice.onPhrase` | `playerIdle()` : si une bulle du chat est en cours, l'annonce **n'est pas jouée** (`ProximityVoice.kt:29-38`) |
| Analyse photo | `AppViewModel.announcePositions` (`AppViewModel.kt:187-195`) | même voix, un seul `say` à la fois |

Réglage : **`Settings.ttsAnnouncePositions`** (défaut `true`, `Settings.kt:36`), bascule
« Annoncer les objets et leur distance » (`app/TtsSettings.kt:73-76`). La bascule effective
est `announcePositions = ttsAnnouncePositions && ttsConfigured` (`Settings.kt:184`) :
**sans clé ElevenLabs ni voix, l'annonce est muette** — par construction, pas par bug.

## 6. Ce qui n'est jamais annoncé

- **Label vide** ou géométrie inutilisable (taille d'image inconnue, boîte < 4 valeurs) :
  l'objet est omis (`PositionText.fromDetection`, `PositionText.kt:79-84`).
- **Objet coupé par le bord haut ou bas** : sa hauteur ne mesure plus l'objet, donc sa
  **distance est écartée** (`Pinhole.isVerticallyCut`, marge **2 px**,
  `core/.../distance/DistanceEstimator.kt:47,156`). Il reste **annoncé sans distance** :
  l'annonce de position ne filtre pas les bords. Le contrat écrit de la lane
  (« aucun objet coupé par le bord n'est annoncé ») n'est donc **pas** tenu tel quel —
  écart documenté, non corrigé ici (hors du périmètre de cette fiche).
- **« Hors zone »** : il n'existe pas de zone « hors champ ». Tout objet dont la boîte est
  lisible reçoit une heure ; la seule exclusion est celle des lignes ci-dessus.

## 7. Où ça vit

| Élément | Fichier |
|---|---|
| Heures, phrase, signature de scène | `core/.../live/PositionText.kt` (163 l.) |
| Machine 2 frames / 4 s / non-répétition | `core/.../live/AnnouncementGate.kt` (88 l.), `PositionAnnouncer.kt` (49 l.) |
| Frame → objets placés + distance | `core/.../live/FrameRoster.kt` (`placedLabels`, `:62-77`) |
| Direct (VM + annonce) | `app/.../LiveViewModel.kt`, `app/.../live/LivePositionVoice.kt` |
| iSee (sonde + voix) | `app/.../isee/LiveProbe.kt`, `ProbeWiring.kt`, `ProximityVoice.kt` |
| Photo | `app/.../AppViewModel.kt` |
| Réglages | `core/.../settings/Settings.kt`, `app/.../TtsSettings.kt` |

Tests JVM qui verrouillent le contrat : `PositionTextTest` (10), `PositionTextEnglishTest`
(5), `PositionAnnouncerTest` (8), `LivePositionVoiceTest` (5), `SettingsAnnounceTest` (4).

## 8. Recette

### 8.1 Sans GPU — le chemin photo (recommandé)

L'analyse d'une photo suffit à prouver détection → phrase → voix. Le contrat du client SAM
est `GET /health` et `POST /segment {"image_b64","prompts"}`
(`core/.../net/HttpSamClient.kt:9,21,28`) : un faux serveur qui ne sert que ces deux routes
suffit, **aucune carte GPU** (le script utilisé en 2025 était un `/tmp/mock_sam.py` hors
dépôt — il n'est **pas** dans cet arbre). Le **Direct**, lui, parle en WebSocket
`/live/ws` (`core/.../net/WsTransport.kt:35`) : pas de faux serveur ici, il exige le vrai
backend.

```bash
python3 <un faux serveur SAM sur /health + /segment> 8099   # aucune carte
adb -s <serial> reverse tcp:8099 tcp:8099         # le téléphone atteint le port du poste
# local.properties : sam.base.url=http://127.0.0.1:8099   puis  ./gradlew :app:assembleDebug
```

Attendus, dans l'ordre :

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet, cf. § 5) et
   bascule « Annoncer les objets et leur distance » **activée** ;
2. analyse d'une photo contenant **trois objets séparés** (9 h, midi, 3 h) →
   **une** phrase, le plus en face d'abord, p. ex.
   `Chaise à midi, bouteille à 3 heures, personne à 9 heures.` ;
3. la même photo réanalysée → **aucune** nouvelle annonce (scène déjà dite) ;
4. bascule **coupée** + Enregistrer → l'analyse tourne, **plus aucune** annonce.

### 8.2 Téléphone réel

Jamais d'`adb` nu : tout passe par le verrou partagé.

```bash
~/bin/phone run --wait 600 --task "M86 recette positions" -- adb -s 2c6852ac <commande>
# sortie 2 = occupé par un autre agent : attendre, ne pas forcer (jamais PHONE_FORCE)
```

`install -r` échoue si un build antérieur a changé de signature (`docs/TESTING.md` § 3) : ne
pas désinstaller l'app d'un autre agent sans accord — l'app installée peut être le build
qu'il est en train de tester.

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
