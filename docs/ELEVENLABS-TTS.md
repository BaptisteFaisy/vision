# Voix ElevenLabs — contrat réel, annonce des positions, silence

> La **langue** de cette voix (annonces de position, réponses du chat, consignes du LLM) se
> règle dans Réglages → « Langue de la voix » : voir `docs/VOIX-ANGLAIS.md`. La voix reste un
> choix (`ID de voix ElevenLabs`) indépendant de la langue.

Fiche relue dans le **code de cet arbre** le 2026-10-26 (commit unique de ce dépôt).
Chaque affirmation ci-dessous est adossée au code (fichier + ligne) ; les mesures de recette
sont conservées telles quelles mais **non rejouées ici**, et ce qui est périmé est **marqué**,
jamais effacé.

Deux sujets distincts, souvent mélangés :

1. le **contrat HTTP** du client TTS (stable) ;
2. l'**annonce vocale des positions** dans iSee (le chemin réel de l'app aujourd'hui).

## Contrat réel : endpoint, modèle, clé, voix

| Élément | Valeur en vigueur | Preuve |
|---|---|---|
| Endpoint | `POST {baseUrl}/text-to-speech/{voiceId}?output_format=mp3_44100_128` | `core/net/ElevenLabsTtsClient.kt` (`ttsUrl()`) |
| `baseUrl` | `https://api.elevenlabs.io/v1` | `DEFAULT_TTS_BASE_URL` |
| **Modèle runtime** | **`eleven_v4_turbo`** | `DEFAULT_TTS_MODEL`, `ElevenLabsTtsClient.kt:80` + test `ElevenLabsTtsClientTest` qui asserte `model_id` |
| Clé | en-tête **`xi-api-key`** (pas `Authorization: Bearer`) | `buildRequest()` |
| Sortie | MP3 `mp3_44100_128` | `OUTPUT_FORMAT` |
| **Voix par défaut** | **Laure `3b8fXc91YHS1i2DYAlBQ`** (voix du projet) | `Settings.DEFAULT_TTS_VOICE_ID` |
| Sarah (ancien défaut) | `EXAVITQu4vr4xnSDxMaL` (premade, marche en compte free) : un réglage resté dessus migre vers la voix du build, sinon Laure | `Settings.SARAH_VOICE_ID`, `app/SettingsSeed.kt` (`migrateLegacyVoice`) |
| Voix « Jade US » | `TOXPlnYC7z5SQSV2Zgc5`, optionnelle (bibliothèque → 402 sans crédits) | `Settings.JADE_US_VOICE_ID` |
| **Vitesse** | **`ttsSpeed`, ×1,9 par défaut, ×0,5..×2** — boutons des Réglages ou ordre vocal (« vitesse 1,5 », « parle plus vite », « ralentis ») | `Settings.ttsSpeed`, `core/speech/TtsSpeedCommand.kt`, `app/TtsSettings.kt` |
| Origine de la clé | `Settings.ttsApiKey`, complétée par `local.properties` (`tts.api.key`) via `seededFromBuild()` | `app/SettingsSeed.kt:34`, `:66-72` |

⚠️ **Correction** — l'ancienne note « modèle `eleven_v3`, voix par défaut » ne décrit **pas**
ce que l'app envoie. `git grep eleven_v3` ne donne plus **aucune** occurrence dans le
Kotlin (l'unique mention était un préfixe de clé de cache dans l'ancien
`SpeechUseCaseTest`, supprimé le 01/11/2026 avec le use case jamais branché) :
`eleven_v3` est le modèle qui a servi à **fabriquer** le MP3 de l'intro
embarquée, pas le modèle des appels. Tout appel runtime part en
`eleven_v4_turbo`, avec Laure si l'utilisateur n'a rien choisi.

⚠️ **Vitesse = lecture locale, jamais cloud.** `eleven_v4_turbo` ignore le réglage
`speed` (un non-défaut peut même être rejeté) : le client n'envoie **aucun** `speed`
(test `ElevenLabsTtsClientTest` qui asserte son absence) et la vitesse s'applique en
lecture (`MediaPlayer.playbackParams`). Tout l'audio parle donc à la vitesse choisie,
frais ou en cache, même hors ligne.

## Réponses vérifiées au curl (relevé de recette, conservé, non rejoué ici)

Compte ElevenLabs (clé dans `local.properties`, gitignorée et **non recopiée ici**) :

| Requête | Résultat |
|---|---|
| `POST /v1/text-to-speech/TOXPlnYC7z5SQSV2Zgc5` `eleven_v3` | **200 `audio/mpeg`**, 30 554 octets |
| idem `eleven_multilingual_v2` | **200 `audio/mpeg`**, 27 628 octets |
| idem avec `language_code: en`, `fr`, ou sans | 200, audio identique ou équivalent |

MP3 44,1 kHz mono 128 kb/s dans tous les cas. **Le modèle de ces essais n'est pas
celui du client** (voir correction ci-dessus) : ce sont des essais de timbre.

## Corps envoyé, erreurs, refus locaux

```
POST {baseUrl}/text-to-speech/{voiceId}?output_format=mp3_44100_128
xi-api-key: <clé>
User-Agent: sam3-vision-android/1.0
Content-Type: application/json

{"text":"…","model_id":"eleven_v4_turbo",
 "voice_settings":{"stability":0.5,"similarity_boost":0.75,"style":0.0,"use_speaker_boost":true}}
```

- **URL construite par OkHttp** (`HttpUrl.newBuilder()`), jamais par concaténation :
  le `voice_id` est échappé correctement.
- **Erreurs typées** (`net/Http.kt` → `httpError`) : 401/403 → `ApiError.InvalidKey`,
  402 → `PaymentRequired`, 429 → `RateLimited`, 404 « invalid function call » →
  `BackendStopped`, autre code → `Http(code, body.take(160))` ; 2xx non audio (corps
  vide ou commençant par `{`/`[`/`<`) → `BadResponse("audio vide reçu d'ElevenLabs")`.
  Un JSON d'erreur n'est jamais joué comme un MP3.
- **Refus locaux, sans réseau** : texte vide, clé absente, voix absente.
- **Limite dure du service** : 5 000 caractères par requête (`400 text_too_long`). Cette limite
  n'apparaît nulle part dans le code de cet arbre : c'est le contrat d'ElevenLabs, pas une garde locale.
  `core/audio/SentenceChunker` (`MAX_CHARS = 1_200`, `SentenceChunker.kt:10`) découpe aux
  phrases (branché : `app/isee/IseePlayback.kt`). L'ancien `core/usecase/SpeechUseCase`
  (cache LRU de 6 réponses) a été **supprimé le 01/11/2026** : jamais branché
  (`git grep SpeechUseCase app/` ne trouvait aucun appel dans l'app).

## Déclenchement : une bascule **ET** une clé

```kotlin
// core/settings/Settings.kt
val ttsConfigured     get() = ttsApiKey.isNotBlank() && ttsVoiceId.isNotBlank()
val announcePositions get() = ttsAnnouncePositions && ttsConfigured
```

Donc la voix des positions parle seulement si **les deux** sont vraies :

- la bascule Réglages **« Annoncer les objets et leur distance »** (`ttsAnnouncePositions`,
  vrai par défaut) ;
- une **clé ElevenLabs** non vide (et un ID de voix non vide) — saisie à l'écran
  Réglages ou venue du build (`tts.api.key`).

**Si l'une manque : rien n'est dit, aucun appel réseau.** L'erreur typée remonte à
l'appelant (`VoiceAnnouncer.say()` rend `Result<Unit>` : succès si le texte est vide,
`ApiError.InvalidKey` si la clé manque, sinon l'erreur de synthèse ou de lecture) —
les bandeaux l'ignorent (l'info y est déjà affichée), le micro verrouillé la publie
dans sa notification au lieu d'un succès muet. C'est le piège de diagnostic :

- `VoiceAnnouncer.say()` ne dit rien sans clé, mais rend l'échec au lieu de sortir
  en silence (`app/VoiceAnnouncer.kt`) ;
- `ProximityVoice.onPhrase()` sort si `!current.ttsConfigured` (`app/isee/ProximityVoice.kt:29-38`, garde `:33`)
  et, sur échec de synthèse, fait `getOrElse { return }` : l'erreur n'est pas remontée
  à l'écran.

Conséquence recette : pour distinguer « bascule coupée » de « pas de clé », il faut
lire les Réglages (ou `Settings`), **pas** l'écran — les deux cas sont également muets.

## Chemin réel dans iSee (page d'accueil)

```
frame /live/ws  →  LiveProbe.phraseOf()            (null si bascule coupée)
                →  PositionAnnouncer.onFrame()      (stabilité, nouveauté, 4 s)
                →  LiveProbeState.spoken
                →  LiveProbe.observeSpoken()        (ProbeWiring.kt)
                →  ProximityVoice.onPhrase(phrase, playerIdle())
                →  ElevenLabsTtsClient.synthesize()  (eleven_v4_turbo, voix des Réglages)
                →  SpeechPlayer(cacheDir/isee-play).play()
```

Aucune de ces étapes ne passe par un POST de frames : le direct est en **WebSocket
`/live/ws`** (voir plus bas).

## Ce que la voix dit, et comment les distances entrent

- **Forme** : labels + heure, sans article (donc sans faute de genre sur des labels
  libres) : `Personne à 9 heures, chien à 3 heures.`
- **Ordre** : gauche → droite (`centerX` croissant) ; un label répété **à la même
  heure** n'est cité qu'une fois ; **3 objets au plus** puis « et N autres objets ».
- **Cadran** : sept bandes égales (`PositionText.clockHour`), 9 h tout à gauche,
  midi au centre, 3 h tout à droite ; plus de repère vertical.
- **Ordre** : le plus proche d'abord, puis le plus en face (distance, puis écart
  à l'axe) ; à égalité, l'ordre alphabétique tranche.
- **Distance** : `PlacedLabel.meters` est rempli depuis `DistanceReading`
  (`readings.associate { it.box to it.meters }`), puis mis en mots au mètre entier
  par **`DistanceText.spokenShort`** (`core/distance/DistanceText.kt`) :
  `50 centimètres`, `1 mètre`, `2 mètres`, `distance inconnue`. Le cadran tombe
  quand la distance est connue :
  `Personne à 1 mètre, chien à 3 mètres.`
- **Signature de scène** : `PositionText.key` = `label|heure|distance arrondie au
  mètre`. C'est ce qui fait reparler un objet **qui s'approche** (3 m → 2 m → 1 m)
  ou **qui glisse** (10 h → 11 h) sans le faire bavarder à chaque frame.

## Anti-bavardage et « cache » de l'énoncé

Trois mémoires différentes, à ne pas confondre :

1. **`PositionAnnouncer`** (`core/live/PositionAnnouncer.kt`) : la scène doit être vue
   **2 frames de suite** (`DEFAULT_MIN_FRAMES = 2`) ; une scène **déjà annoncée** reste
   muette ; **4 s** minimum entre deux annonces (`DEFAULT_COOLDOWN_MS = 4_000`) ; scène
   vide ⇒ mémoire qui retombe (le retour des objets reparle).
2. **`ProximityVoice.lastPhrase`** : la même phrase n'est jamais répétée dans une
   session ; `reset()` à chaque nouvelle session de direct. Point important : si le
   chat est en train de parler, la phrase est **mémorisée comme déjà traitée puis
   jetée** (commentaire du fichier : « pour ne pas la dire en retard une fois le chat
   terminé »).
3. **MP3 déjà synthétisés** : la réponse du chat est écrite sur disque et référencée
   dans Room (`MessageMultimedia.cheminAudioElevenLabs`, `media/MediaCacheStore.kt`) ;
   la rejouer ne consomme **aucun** caractère. Le cache d'énoncés `SpeechCache`
   (LRU 6, clé voix + modèle) est prêt et testé mais **non branché** sur la lecture.

## « Sans couper le chat » : ce que ça veut dire exactement

- **iSee (chemin réel)** : chat et positions partagent **le même** `SpeechPlayer`
  (`cacheDir/isee-play`, `IseeViewModel.kt`) et `ProximityVoice` reçoit
  `playerIdle()` = `speakingEntryId == null` (`IseeViewModel.playerIdle()`). Si la
  réponse du chat est en cours, l'annonce **n'est pas jouée du tout** : elle ne coupe
  rien, elle est abandonnée. C'est la seule garantie « une source sonore à la fois ».
- **Écran Direct historique (`LiveViewModel`)** : `VoiceAnnouncer(app, "positions")`
  possède son **propre** `SpeechPlayer` (dossier `positions`). Une annonce n'y coupe
  pas la réponse du chat (lecteur différent) mais rien ne l'empêche de parler **en
  même temps** ; le `Mutex speak` ne protège que les annonces entre elles.

⚠️ **Correction** : la formule « la voix parle sans couper le chat via POST /live » est fausse
sur les deux points. Les frames du direct arrivent par le **WebSocket `/live/ws`**
(`core/net/WsTransport.kt:35` : `LIVE_WS_PATH = "/live/ws"`, `WsLiveClient`), sans repli ni
envoi HTTP ; et la
non-interruption vient du garde `playerIdle`, pas du transport.

## Transport et latences de référence (citées, non re-mesurées ici)

Les chiffres du direct viennent du dépôt **backend**, `docs/LATENCE.md` (absent de ce dépôt) :
serveur p50 91,0 ms (plage 82–95), aller-retour PC p50 240,1 ms, téléphone 600–710 ms (dont ~80 ms
non comptés), TLS + DNS 67,4 ms, réveil d'un conteneur 23,6 s — relevés côté backend.
**Non re-mesurés ici** : ne pas les réécrire, les citer.

⚠️ Le conteneur déployé `sam3-eu-a100` est un build `sam3.1-fast` **hors des dépôts** :
**ne pas redéployer** (perte d'environ 235 ms côté serveur et du WebSocket). Une
annonce muette n'est donc jamais une raison de redéployer.

## Où ça vit

| Élément | Où |
|---|---|
| Client HTTP TTS | `core/net/ElevenLabsTtsClient.kt` (pur JVM, testé : URL, erreurs, audio) |
| Réglages (clé, voix, bascule) | `core/settings/Settings.kt` (`ttsApiKey`, `ttsVoiceId`, `ttsAnnouncePositions`, `announcePositions`) |
| Clé de build | `app/SettingsSeed.kt` (`seededFromBuild`) |
| Découpage d'énoncés | `core/audio/SentenceChunker.kt` (branché : `app/isee/IseePlayback.kt`) ; l'ancien cache LRU (`SpeechUseCase`) est supprimé depuis le 01/11/2026 (jamais branché) |
| Lecture MP3 | `app/SpeechPlayer.kt` (`MediaPlayer`) ; `voice` (bandeaux), `isee-play` (iSee), `positions` (Direct) |
| Bandeaux de dictée | `app/VoiceAnnouncer.kt` |
| Positions (mots) | `core/live/PositionText.kt`, `core/live/PositionAnnouncer.kt` |
| Positions (câblage iSee) | `app/isee/LiveProbe.kt` (`phraseOf`), `app/isee/ProbeWiring.kt` (`observeSpoken`), `app/isee/ProximityVoice.kt`, `app/isee/IseeViewModel.kt` |
| Positions (écran Direct) | `app/LiveViewModel.kt` (`announcePositions`, `sayPositions`) |
| Boutons « 🔊 / ⏹ » | `app/TtsState.kt`, `app/TtsSettings.kt`, `app/SharedUi.kt` |

## Autres usages de la voix

- **Chat / analyse** : bouton « 🔊 Lire » sur la description et chaque réponse ;
  bascule « Lire les réponses automatiquement » (`ttsAutoplay`, vrai par défaut, mais
  effective seulement si `ttsConfigured`).
- **iSee, bulles** : chaque bulle garde son MP3 dans Room ; la réponse la plus récente
  est lue dès son arrivée (`speakLatestAnswer`). Une seule lecture à la fois.
- **Intro embarquée** : `app/src/main/res/raw/intro_isee.mp3` — voix Laure
  (`3b8fXc91YHS1i2DYAlBQ`), rendue le 2026-10-28 en `eleven_v3` (stability 0,5,
  similarity 0,75, style 0, speaker boost : les réglages du runtime), MP3 44,1 kHz
  mono 128 kb/s, **401 701 octets, 25,08 s** (`ffprobe`), voix réelle
  (mean −19,3 dB, max −0,4 dB, `volumedetect`). Coût : **135 caractères**
  (compte creator, 28 078 → 28 213 / 130 005). Aucun réseau pour la jouer.
  Le texte (`ISEE_INTRO_TEXT`, `IntroLogic.kt`, **307 caractères**) dit « Welcome to iSee… »,
  puis la phrase de coupe « Disable iSee intro. » — dans cet ordre, et `app.UiLogicTest`
  le vérifie. Voix et texte **réalignés** : la phrase « To listen to previous messages,
  swipe down » n'est plus ni dans le texte ni dans le MP3 (balayage supprimé le 27/10).
  **Non écouté ici** (pas de sortie audio sur le poste de
  travail) : contrôle par `ffprobe`/`volumedetect` et par le texte seulement, à confirmer
  à l'oreille sur le téléphone.
  ⚠️ **Périmé** : l'ancienne note « lue **une seule fois**, `introPlayed` posé avant lecture »
  ne décrit plus le comportement. Aujourd'hui l'intro est **rejouée à chaque ouverture
  complète** tant qu'elle est autorisée : `Settings.introPending` vaut `introEnabled`
  (`Settings.kt:110`) et `introPlayed` n'est conservé que pour le format de fichier
  (`Settings.kt:29-33` ; `IseeIntro.playIfPending` : « aucun marqueur persisté »). Une mise en arrière-plan
  puis retour ne la rejoue pas (le ViewModel survit), une fermeture complète si.
- **Mode tap** : trois mots anglais pré-générés via ElevenLabs (`app/tap/AnnouncementPlayer.kt`).

## Coût (relevé de recette, conservé, non rejoué ici)

Compteur du compte : **321 / 130 005 caractères** consommés (tier `creator`) au premier
relevé, plus les essais de vérification (~200 caractères) et les rendus de l'intro
(309 puis 351 caractères, un par version). Une lecture coûte ~1 caractère par
caractère lu.
Un énoncé déjà en cache disque (bulle du chat) coûte **0** caractère.

## Reste ouvert

- Aucun test instrumenté de la voix des positions (pas de vérification automatique du
  silence « bascule coupée » / « clé absente »).
- Plus de cache LRU de réponses (`SpeechUseCase` supprimé le 01/11/2026, jamais
  branché) : une réponse de plus de 5 000 caractères échouerait toujours en
  `400 text_too_long`.
- La clé du compte n'a pas `voices_read` : le champ voix est un ID libre, pas une
  liste réelle du compte.
- Sur l'écran Direct, l'annonce des positions n'est pas exclusive avec la voix du
  chat (lecteurs distincts) — seul iSee garantit « jamais par-dessus ».