# Mode tap court — vidéo → SAM 3.1, annonces ElevenLabs

Conçu pour un usage sans regard. Ce mode est l'**écran `Route.Tap`** (`AppNav.kt:37`, `:68`),
atteint depuis les Réglages (« Other screens » → « Classic home (photo + analysis) »,
`SettingsScreen.kt:407-416`). ⚠️ L'accueil par défaut de l'app est **iSee**, qui a son
propre geste de tap (voir la dernière section) : ce ne sont pas les mêmes chemins de code.

**Un tap sur l'aperçu de Capture ouvre le mode vidéo et démarre l'enregistrement**
(`CaptureScreen.kt:138`, `.tapOnce { onOpenTap(true) }`) ; le bouton « Vidéo (tap) » reste
l'entrée explicite, sans démarrage automatique (`CaptureScreen.kt:148`, `onOpenTap(false)`) — on
a le temps de se placer. Sur l'écran vidéo, **un second tap** arrête et envoie.

## Interaction

| Événement | Verbe prononcé (anglais, mot unique) | Écran |
|---|---|---|
| Tap sur l'aperçu de Capture | `Launched` | « Enregistrement » (démarré d'office) |
| Tap court relâché, caméra ouverte | `Launched` | « Enregistrement » |
| Second tap relâché, envoi réussi | `Sent` | « Envoyé » + résumé texte |
| Permission refusée / caméra KO / envoi KO | `Failed` | « Échec » + motif |

Libellés exacts à l'écran : « Touchez l'écran », « Enregistrement », « Envoi… », « Envoyé »,
« Échec » (`TapCaptureScreen.kt:134-140`) — l'écrit complète la voix, il ne la remplace pas.

### Ce qui déclenche vraiment le geste

- **L'action part au relâchement du doigt** (`ACTION_UP`), pas à la confirmation du tap :
  `TapAreaView.kt:10-13` explique pourquoi — `GestureDetector.onSingleTapConfirmed` attend le délai
  du double tap (~370 ms) et, sur deux taps rapides, **ne se déclenche jamais** (le second tap
  était avalé (`CHANGELOG.md`, § « envoi au relâchement »).
- Un tap court = un `UP` ni précédé d'un appui long ni d'un glissement, et de durée inférieure au
  seuil appui long de `ViewConfiguration` : `isShortTapRelease` (`TapZones.kt:28-33`), appelé par
  `TapAreaView.kt:84-88` ; le `GestureDetector` (seuils par défaut) ne sert qu'à **marquer** appui
  long (`onLongPress`) et glissement (`onScroll`), `TapAreaView.kt:41-53`.
- **Barre système jamais déclenchante** : le conteneur est posé sous `WindowInsets.systemBars`
  (`TapCaptureScreen.kt:57`, `:65`) et `TapAreaView` rejette en plus tout point tombant dans les
  bandes haut/bas (`TapZones.kt:11-12`) ou dans la marge latérale de la largeur d'un slop
  (`TapZones.kt:19-20`). Tests JVM : `TapZonesTest.kt`.
- TalkBack : `contentDescription` sur la surface (`TapAreaView.kt:61-62`) et `performClick()` comme
  action unique (`TapAreaView.kt:103-107`) — un double-tap TalkBack fait exactement un tap du doigt.
- **Tap contre appui long, sans double déclenchement** : sur l'aperçu de Capture, le classement se
  fait **au lever** par `core/gesture/TapOrHold` avec les seuils de l'appui long de l'app
  (300 ms, 24 px ≈ 8 dp : `LongPressTrigger.kt:102`, `:105`, réexportés par
  `TapOrHold.kt:25-28`) ; un appui tenu est un appui long, il ne lance donc jamais la vidéo
  (`TapOnce.kt:28`). Les commandes posées au-dessus (Photographier, Direct, Galerie, Réglages)
  gardent leurs taps (`CaptureScreen.kt:232-238`).

## Parler pendant la vidéo

Android n'autorise **qu'un seul `AudioRecord` par application**. Une vidéo qui prend le micro
interdirait la dictée pendant l'enregistrement : `MicBus` refuse le second demandeur
(`MicBus.kt:27-34`) et c'est une panne silencieuse pour l'utilisateur.

Le mode vidéo renonce donc à sa piste audio (`TapConfig.VIDEO_WITH_AUDIO = false`,
`TapConfig.kt:45`) : la demande faite à SAM 3.1 est **visuelle** (`UPLOAD_PROMPT` est fixe,
`TapConfig.kt:51`), le son de la vidéo n'apporte rien, et le micro reste disponible pour la voix.

Conséquence exacte, qui n'était pas écrite avant : **avec le réglage par défaut, le mode tap ne
réserve jamais le micro** — `record()` n'appelle `MicBus.acquire(TAP_VIDEO)` que si
`VIDEO_WITH_AUDIO` est vrai (`TapSession.kt:118-122`). L'annonce « micro occupé par la dictée »
n'est donc atteignable qu'après avoir repassé le drapeau à `true` ; dans ce cas seulement, la
dictée est refusée pendant la prise. À l'arrêt, `MicBus.release(TAP_VIDEO)` est rendu **avant**
l'envoi (`TapSession.kt:157`), donc la dictée peut reprendre pendant que la vidéo monte.

## Voix

`AnnouncementPlayer` : **trois mots, pas un de plus**. `Announcement` est un enum fermé
(`LAUNCHED`/`SENT`/`FAILED`, `TapConfig.kt:13-21`) et `announcementAllowed()` refuse tout autre
texte (`TapMachine.kt:53-54`, revérifié dans `AnnouncementPlayer.kt:57`) : le TTS système n'est
jamais utilisé ici, et le lecteur du chat est coupé à l'entrée de l'écran
(`AppNav.kt:111-113`, `viewModel.stopSpeaking()`).

Latence : `prewarm()` génère les trois clips à l'ouverture de l'écran dans
`filesDir/announcements/{launched,sent,failed}.mp3` (`TapConfig.kt:86`,
`TapCaptureScreen.kt:101`, `AnnouncementPlayer.kt:38-50`) — tailles relevées sur émulateur :
17,5 / 16,3 / 15,1 ko (**constat d'émulateur, date non consignée dans le code, non remesuré ici**).
Ensuite la lecture est **100 % locale**, sans réseau. Si un clip manque et que le réseau dépasse
le budget (`NETWORK_LATENCY_BUDGET_MS = 500 ms`, `TapConfig.kt:54`), le mot est tu **pour ce
coup** — jamais de voix qui arrive en retard sur la piste audio — et la génération continue en
arrière-plan pour le tap suivant (`AnnouncementPlayer.kt:56-66`). Un clip reste valable 30 jours
(`CLIP_TTL_MS`, `TapConfig.kt:67`, contrôle `AnnouncementPlayer.kt:78-82`). Lecture via
`SpeechPlayer.playFile` : **aucune demande de focus audio** et aucun `AudioAttributes`
(`SpeechPlayer.kt:36-48`, mesuré : pas d'`AudioFocus` dans le fichier), donc l'enregistrement en
cours n'est ni coupé ni ducker.

## Rien n'est perdu : durée minimale et file d'attente

`MIN_RECORDING_MS = 350 ms` (`TapConfig.kt:64`). En dessous, CameraX finalise la prise en **erreur
8 avec un fichier de 0 octet** — mesures d'émulateur consignées dans le commentaire du code :
échec à 62 ms, succès à 332 / 521 / 718 / 1022 / 1523 ms, fichiers de 412 à 452 ko
(**constat non daté dans le code, non remesuré ici**).

Un tap trop tôt n'est donc **pas ignoré** : il est mémorisé (`stopRequested`,
`TapSession.kt:57-65`) et le battement (250 ms, `TapSession.kt:189-201`) l'exécute dès que la
durée exploitable est atteinte — imperceptible, et deux taps à 200 ms d'écart partent bien
(`tapCommand`/`tickCommand`, `TapMachine.kt:22-43`, tests `TapMachineTest.kt`). Un tap arrivé
pendant la liaison caméra est traité de la même façon (`starting`, `TapSession.kt:58-61`,
`record()` relance la liaison `:105`).

Avant l'envoi, `TapVideoSession.stopAndAwait` attend l'événement `VideoRecordEvent.Finalize`
(**4 s max**, `TapVideoSession.kt:109-114`, `:134`) : sans cette attente, la taille du fichier
était lue trop tôt et la vidéo était déclarée « aucune vidéo à envoyer » alors qu'elle venait de
s'écrire. L'envoi lui-même est borné deux fois : 120 s côté client HTTP
(`UPLOAD_TIMEOUT_SECONDS`, `HttpSam31VideoClient.kt:69`) et 130 s côté session (`TapSession.kt:201`), l'échec devenant
« délai d'envoi dépassé » + `Failed`.

## Caméra et fichier

CameraX `VideoCapture` + `Recorder` (couche la plus maigre, `Quality.HD`, caméra arrière par
défaut), **sans piste audio** par défaut (voir plus haut) : `TapVideoSession.kt:49-56`. Si
`RECORD_AUDIO` est refusé, l'enregistrement continue **muet** au lieu d'échouer
(`TapVideoSession.kt:86-96`). La vidéo est écrite via `CapturePaths.newVideoFile`
(`TapSession.kt:123`), donc dans le périmètre exact purgé par le module de nettoyage
(`docs/NETTOYAGE.md`).

## Configuration — la source unique

Tout ce qui change d'un déploiement à l'autre est dans `TapConfig.kt` :

| Clé | Provient de | Valeur / défaut (relu dans cet arbre le 2026-10-26) |
|---|---|---|
| `sam31Endpoint` | `BuildConfig.SAM31_ENDPOINT` ← `local.properties` → `sam31.endpoint` | vide → annonce « Failed » (`TapConfig.kt:35`, `:83`) |
| `ELEVENLABS_API_KEY` | Réglages (store chiffré) sinon `local.properties` → `tts.api.key` | vide → muet (`TapConfig.kt:73`) |
| `LAUNCHED` / `SENT` / `FAILED` | `enum Announcement` | mots anglais (`TapConfig.kt:13-17`) |
| `MAX_RECORDING_MS` | constante | **60 s**, arrêt + envoi automatiques (`TapConfig.kt:57`, `TapMachine.kt:40`) |
| `MIN_RECORDING_MS` | constante | **350 ms**, en dessous le tap est **mémorisé**, pas ignoré (`TapConfig.kt:64`) |
| `ON_DEVICE_INFERENCE` | constante | `false` = upload cloud multipart (`TapConfig.kt:48`) |

**Le mode tap n'est pas concerné par le mode US** (`docs/MODE-US.md`) : il lit
`BuildConfig.SAM31_ENDPOINT` en direct (`TapConfig.kt:35`, passé au client
`TapCaptureScreen.kt:171`) et ignore `Settings.samBaseUrl` comme `Settings.samRegion` — aucune
résolution de région ne s'applique ici. Conséquence pratique : sur un build sans
`sam31.endpoint`, le second tap échoue avec « endpoint SAM 3.1 vide »
(`HttpSam31VideoClient.kt:37`) ; c'est exactement ce qui a été observé en recette sur téléphone
réel (`CHANGELOG.md`, entrée « un tap lance la vidéo, un appui long parle » : second tap
→ « Échec — endpoint SAM 3.1 vide »).

Le build debug autorise le HTTP en clair (`app/src/debug/AndroidManifest.xml`,
`usesCleartextTraffic="true"`) : un endpoint local (`http://10.0.2.2:8099` sur émulateur) est
utilisable sans recompiler. En release, le `networkSecurityConfig` ne l'autorise que pour
`10.0.2.2`, `localhost` et `127.0.0.1` (`app/src/main/res/xml/network_security.xml`) ; le reste
reste HTTPS.

## Cloud ou on-device ? — l'upload est écrit, la route n'existe pas

Le contrat **côté app** est l'**upload** : `POST {endpoint}/video` en `multipart/form-data`, champs
`model=sam3.1-video`, `video`, et `prompt` quand il est non vide
(`HttpSam31VideoClient.kt:18-21`, `:55-63` ; `DEFAULT_PATH = "/video"` `:69`, `DEFAULT_MODEL` `:70`) ;
le multipart évite les +33 % du base64. La réponse est lue de façon tolérante (`text`, `description`,
`answer`, `caption`, sinon corps brut — `SUMMARY_KEYS` `:77`), bornée à 600 caractères
(`MAX_SUMMARY_CHARS` `:15`, `summarize` `:89-95`).

⚠️ **La vérité, telle quelle : cette route n'existe dans aucune implémentation backend connue.**
Vérifiable ici : `git grep -n '"/video"' server/backend` est vide, et le client ne trouve
que son test (`HttpSam31VideoClient.kt:69`, `VideoClientTest.kt:21`) ; les routes que l'arbre
adresse à SAM sont `/live/ws` (`WsTransport.kt:35`), `/segment`, `/health` et `/release` —
jamais `/video` en dehors de ce fichier. Côté déploiements connus (`server/` de ce dépôt, les
apps Modal `sam3-eu-a100` / `sam3-us-a100`), aucune n'expose `POST /video`. Donc, sur un build
où `sam31.endpoint` est renseigné, le second tap **part** et revient en erreur — 404 du proxy
Modal, que `httpError` traduit en `BackendStopped` (`Http.kt:248-251`) ou en
`ApiError.Http(404, …)` — puis `TapSession.upload` transforme l'échec en `TapStatus.Failed` et
annonce **« Failed »** (`TapSession.kt:176-191`). Sans endpoint, l'échec est **local** et
immédiat : « endpoint SAM 3.1 vide » (`HttpSam31VideoClient.kt:37`).

Autrement dit : **le mode tap enregistre, annonce et échoue proprement ; il ne produit aujourd'hui
aucun résumé vidéo.** Ce n'est pas une fonction qui marche, c'est une fonction dont le client est
prêt et le serveur absent. Ne pas la présenter autrement.

Si SAM 3.1 doit tourner **sur le téléphone**, on ne change qu'un endroit :
`TapConfig.ON_DEVICE_INFERENCE = true` — le client refuse alors sans appel réseau
(`INFERRED_ON_DEVICE`, `HttpSam31VideoClient.kt:36`, `:72`) et l'écran annonce `Failed`. Il
faudra brancher le runtime local (TFLite/ONNX + modèle vidéo) à la place du client ; le geste, la
file d'annonces et le nettoyage restent identiques.

## Cohabitation avec la dictée par appui long

- **tap court** (relâchement sous le seuil) → mode tap : `TapAreaView` déclenche l'enregistrement ;
- **appui long maintenu** → dictée : c'est la racine posée par `MainActivity` qui capte le geste.
  ⚠️ Cette racine est **`IseeRootLayout`** (`MainActivity.kt:94-95`) et ses gestes ne s'activent
  que si `isee.active` est vrai (`MainActivity.kt:95`, `IseeHome.kt:26-29`), c'est-à-dire
  quand l'accueil iSee est à l'écran. Sur l'écran `Route.Tap`, l'appui long **ne fait rien** :
  `TapCaptureScreen` accepte un `longPressAction` (`TapCaptureScreen.kt:54`, `:64`) mais la
  navigation ne lui en passe pas (`AppNav.kt:72` : `TapCaptureScreen(state.settings, pop,
  route.autoStart)`), donc le paramètre reste `null`. `LongPressRootLayout.kt` existe encore mais
  n'est **instancié nulle part** (mesuré : seule occurrence = sa déclaration, ligne 27).

**Le micro est exclusif** : `MicBus` (`app/MicBus.kt`) est le seul arbitre, et avec
`VIDEO_WITH_AUDIO = false` il n'est même pas sollicité par la vidéo (voir « Parler pendant la
vidéo »). La règle reste : le second demandeur est refusé, l'appelant le dit — annonce `Failed`
côté tap, bandeau côté dictée.

## Le tap d'iSee — un autre chemin, à ne pas confondre

L'accueil par défaut (iSee) a aussi un tap, mais il **n'appelle pas ce mode** : tap simple →
`startVideo()` dans la fenêtre caméra flottante, second tap → `stopVideo()` et archivage du MP4
avec les frames SAM du `LiveProbe` (`IseeViewModel.kt:248-251` → `IseeCameraCoordinator.kt:25-50`). Pas d'annonces
trois-mots, pas d'upload `/video`, pas de `TapConfig` : les messages y sont en anglais
(`IseeStatus.kt:17,22` : « Camera not ready — try again », « Recording lost »). Deux gestes qui se ressemblent, deux
implémentations distinctes.
