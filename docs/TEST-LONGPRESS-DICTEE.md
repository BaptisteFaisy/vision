# Recette — dicter : appui long iSee et bouton « Dicter »

Fiche relue dans le **code de cet arbre** le 2026-10-26 (commit unique de ce dépôt).
⚠️ **Aucune des deux recettes ci-dessous n'a été rejouée sur un appareil** : il n'y a ni émulateur ni
téléphone sur cette machine, et aucune commande `adb` n'a été lancée. Ce qui suit décrit ce que le
**code** fait ; le comportement à l'écran reste **non mesuré**.

## Ce qui est vrai dans le code aujourd'hui : deux chemins, un seul insère dans le champ

| Chemin | Geste | Ce qui se passe | Où va le texte |
|---|---|---|---|
| **iSee** (page d'accueil) | appui long **n'importe où** sur la surface ≥ **300 ms** | `IseeRootLayout` → `LongPressTrigger` → `IseeViewModel.onHoldStart/onHoldEnd` (`:177`, `:191`) → `transcribeAndAnswer()` (`:262`) | devient une **question du chat** (iSee n'a pas de champ de saisie) |
| **Chat** (route `Route.Chat`, écran d'origine) | **tap** sur « Dicter » → le libellé passe à « Stop » → **tap** à nouveau | `AppViewModel.startDictation/stopDictation` (`hold = false`) → `DictationFeature` | **inséré dans le champ de question** (`LaunchedEffect(state.dictation.seq)` + `mergeDraft`) |

### ⚠️ Pas d'appui long sur le bouton « Dicter »

Preuves, dans cet arbre :

- `ChatScreen.kt:168` — le bouton est un `TextButton(onClick = onClick, enabled = !dictation.busy)` ;
  **aucun** gestionnaire de clic long n'est attaché ;
- `git grep -n "onLongClick\|combinedClickable\|performLongClick" app/src core/src`
  → **aucun résultat** ; il n'existe donc ni handler, ni test instrumenté de ce geste ;
- la racine d'appui long, `IseeRootLayout`, ne pilote les gestes que quand iSee est
  à l'écran : `gesturesEnabled = { isee.active }` (`MainActivity.kt:95`), et
  `active` n'est vrai que pendant que `IseeHome` est composé
  (`IseeHome.kt:26-29` : `active = true` au `DisposableEffect`, `false` au `onDispose`).
  Sur la route Chat, la racine **n'intercepte rien**.

Conséquence pour la recette : `performLongClick()` (ou `performTouchInput { longClick() }`)
sur le nœud « Dicter » ne fait **rien** — ça ne dit pas « Stop » et n'ouvre pas le
micro. Un test écrit comme ça serait vert par vacuité. Le « devient Stop » du libellé
vient d'un **tap**, pas d'un appui long.

## Recette A — bouton « Dicter » (écran Chat), insertion dans le champ

1. Lancer l'app puis atteindre l'écran Chat : route `Route.Chat` (`AppNav.kt:71`),
   poussée par « Poser une question » depuis l'écran d'analyse
   (`AnalysisScreen.kt:91` → `AppNav.kt:129`) ou par une demande venue des lunettes
   (`AppNav.kt:96`).
2. Permission micro : au premier lancement `MainActivity.requestMicIfNeeded()` la
   demande ; sinon Réglages → refuser/accord est rejouable via
   `adb shell pm revoke dev.sam3vision.app android.permission.RECORD_AUDIO`.
3. **Tap** sur le bouton d'état « Dicter » (`ChatScreen.kt`, `DictationButton`) :
   `toggleMic` appelle `onStartDictation()` → `DictationFeature.start(hold = false)`.
   Attendu : libellé **« Stop »** (couleur danger), ligne d'état
   *« Enregistrement… parle, puis appuie sur Stop. »*, **pas de bandeau plein écran**
   (le bandeau `DictationOverlay` n'est posé que pour `hold = true`, `DictationFeature.kt:84`, `:86`).
4. Parler ≥ **400 ms** (minimum refusé localement en dessous — `DictationUseCase.MIN_DURATION_MS`,
   `DictationUseCase.kt:37`).
5. **Tap** sur « Stop » → `stopDictation()` : libellé **« … »** pendant la
   transcription, bouton désactivé (`enabled = !dictation.busy`).
6. Moteur : `speechClient(settings)` = Meta Muse d'abord, repli ElevenLabs Scribe
   (`app/SpeechClients.kt:14-26`, voir `docs/META-ASR.md`).
7. Succès : `dictation.text` = transcription, **`seq` incrémenté** ; le
   `LaunchedEffect(state.dictation.seq)` de `ChatScreen.kt:54-55` insère le texte dans
   le brouillon via `mergeDraft(draft, dictated)` — jointure par une seule espace,
   blancs ignorés (`UiLogicTest`, 4 cas d'insertion). Le bouton revient à « Dicter ».
8. Échec : le champ **ne bouge pas**, `statusLine = « Dictée KO — <message> »`
   (ex. `… paiement requis (402) …`, `… dictée impossible : Meta : … — ElevenLabs : …`).

**Résultat : non rejoué sur appareil** (aucun écran piloté ici).

## Recette B — appui long vocal (iSee, page d'accueil)

1. Ouvrir l'app (iSee est la page d'accueil, `AppNav.kt:53`) ; la fenêtre caméra peut
   être en bulle, l'appui long marche sur **toute** la surface.
2. Poser le doigt et tenir ≥ **300 ms** (`LongPressTrigger.DEFAULT_LONG_PRESS_MS`, `:102`),
   sans glisser de plus de **24 px** (`DEFAULT_SLOP_PX`, `:105`) et sans second doigt : un
   glissement ou un second doigt avant le seuil **annulent** sans rien déclencher.
3. Seuil atteint → `onHoldStart` : vibration (`IseeHaptics.holdStart`),
   `holding = true`, micro ouvert (`HoldTranscript` → `AudioRecord` 16 kHz mono).
   Micro indisponible ⇒ ligne d'état `Microphone unavailable` (`IseeStatus.kt:16`).
4. Relâcher → `onHoldEnd` → `transcribeAndAnswer()` :
   - si une bulle du chat est en cours de lecture : la capture est **jetée** et
     l'état affiche `Busy speaking — try again` (`IseeViewModel.kt:263-267`,
     `IseeStatus.kt:18`) ;
   - sinon : `DictationUseCase` (min 400 ms) → Meta puis Scribe ; `transcribing` vrai
     pendant l'appel ;
   - la phrase « Disable iSee intro » (`isIntroDisablePhrase`) coupe l'intro **sans**
     être envoyée comme question ;
   - texte transcrit vide ⇒ `Nothing heard` (`IseeStatus.kt:19`) ;
   - succès ⇒ ligne Room (`saveUserMessage(pcm, text)`, le PCM est gardé en MP3 ici,
     contrairement au trajet chat) puis **question posée au LLM**, réponse lue par
     ElevenLabs ;
   - erreur ⇒ `Dictation: <message>`.
5. Clavier/adb : `adb shell input swipe X Y X Y 1700` (sur MIUI, prévoir un léger
   déplacement, sinon la durée est ignorée).

**Résultat : non rejoué sur appareil.**

## Ce qui est vérifié sans appareil (tests JVM, comptés par `grep -c '@Test'` le 2026-10-26)

- `core/src/test/.../gesture/LongPressTriggerTest` (**10**) : seuil 300 ms, glissement toléré
  ou annulant, multi-touch avant/après seuil, clôture unique.
- `app/src/test/.../UiLogicTest` (**13**) : `mergeDraft` (les 4 cas d'insertion) et le reste de
  la logique d'écran pure.
- `core/src/test/.../usecase/DictationUseCaseTest` (**4**) et `MetaAsrClientTest` (**8**) : durée
  minimale, texte vide, multipart `request` + `audio` **sans `Content-Length` par part**.
- Aucun `gradlew` n'a été lancé ici : les compteurs ci-dessus sont des `@Test` **déclarés**, pas
  des tests exécutés. Le total « X verts » n'est donc **pas mesuré** sur cette machine.

## Brancher le vrai moteur

Réglages → section « Dictée Meta Muse (repli automatique sur ElevenLabs Scribe) »
(`SettingsScreen.kt:175`) : base URL `https://api.meta.ai/v1`, clé Meta, modèle
`muse-voice-transcribe-1.0` (`Settings.DEFAULT_ASR_*`, `Settings.kt:81-82`). Sans clé
Meta, la dictée utilise la **clé ElevenLabs** déjà saisie (Scribe `scribe_v1`).
« Tester la dictée Meta » (`SettingsScreen.kt:141` → `AppViewModel.testAsr`, `:260`) envoie 1 s de
silence et juge sur le code HTTP.

Pour valider le texte transcrit sans le service réel : mock local (port 51987) puis base URL
`http://10.0.2.2:51987/v1` — le debug APK autorise le clair pour 10.0.2.2/localhost
(`res/xml/network_security.xml`), jamais pour les autres hôtes.

## Non mesuré (à ne pas vendre comme vérifié)

- **Aucune des deux recettes n'a été rejouée sur appareil** : non mesuré.
- Comptage des tests JVM exécutés : **non mesuré** (aucun `gradlew` lancé ici).
- Durée maximale réelle d'une tenue (le plafond de 60 s a été retiré,
  `DictationRecorder.MAX_DURATION_MS = 0L`, `:86`) : **non mesuré** ; taille d'un WAV au-delà
  d'une minute, et réaction du service Meta : **non mesuré**.
- `performLongClick` sur « Dicter » : **aucun test instrumenté n'existe** ; d'après le
  code, le geste attendu est un tap, pas un appui long.
- Refus de permission micro sur l'app actuelle : **non mesuré** (iSee affiche
  `Microphone unavailable`).