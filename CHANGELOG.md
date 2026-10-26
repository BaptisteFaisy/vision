# CHANGELOG — vision (iSee)

Le journal part du 2026-10-26 après 19 h : le commit de départ, puis une entrée par fait
livré et ses preuves (tâche, fichiers, chiffres) dans l'arbre.

## 2026-10-26 (23) — docs : ce qui est vrai aujourd'hui

- **README corrigé** contre cet arbre. Trois affirmations fausses retirées : le dossier
  `backend/` (il n'existe pas ici — le backend SAM 3.1 est dans un dépôt séparé), `:core`
  présenté comme « Kotlin/JVM pur » (c'est une **bibliothèque Android** : 4 de ses 71 fichiers
  touchent `android.*`), et `minSdk 26` appliqué à toute l'app (**29** pour `:app` et
  `:glasses-spike`, 26 seulement pour `:core`). La CI n'est plus décrite comme « sans aucun
  secret » : le job `ci` lit des secrets **facultatifs** (`LLM_API_KEY`, …) pour embarquer des
  clés dans l'APK qu'il publie ; sans eux, l'APK sort sans clé.
- **`docs/ARCHITECTURE.md`** (197 lignes) : les trois modules, le flux capture → segmentation →
  LLM → voix, le **contrat HTTP réel** lu dans le code (`/health`, `/segment`, `/live`,
  `/live/ws`, `/release`, `/video`, `/chat/completions`, ElevenLabs, Meta ASR — **pas de
  `/ask`**, qui n'existe nulle part ici), les **chemins** des clés (aucune valeur), et la carte
  des fichiers d'entrée.
- **`docs/PERF.md`** : trois catégories séparées — mesuré dans cet arbre (282 fichiers `.kt`,
  29 803 lignes, 627 `@Test` JVM + 10 spike), constantes lues dans le code (délais, cadences,
  plafonds), relevés sur appareil **datés et sourcés**. Les latences du backend restent hors
  dépôt ; les chiffres de vision serveur ne sont pas repris faute de source ici.
- **`docs/DOC-ETAT.md`** : une ligne par document — à jour, périmée ou inexacte. Corrigés dans
  la même passe : `docs/TESTING.md` (comptages de tests **359/109 → 468/159**, JDK non épinglé,
  `/health` n'est plus une sonde, budget iSee **150 ms et un créneau**), `docs/MASQUES-DEBUG.md`
  (même cadence, frame **320 px** et non 448, ligne `gpu :` plus alimentée), `docs/CI-QUEUE.md`
  (cinq jobs, runners `ubuntu-latest`, budget 5 min par job),
  `docs/DISTANCES.md` (« les huit concepts par défaut » → **deux**), `NOTES.md` (comptages,
  `DistanceMeter` n'est pas un fichier).
- **Constat, pas corrigé (hors périmètre docs)** : `LivePrompts.kt` annonce « huit » concepts en
  commentaire alors que `Default` en contient **deux** et que `MAX = 2` ; `LiveProbeState.gpu`
  n'est plus renseigné depuis la suppression des sondes `/health` ; `SECURITY.md` et `AGENTS.md`
  portent encore des phrases de l'ancien dépôt (détaillé dans `docs/DOC-ETAT.md`).

## 2026-10-26 (22) — la carte caméra se dissout et son monde reste sur la bulle

- **Demande user** : « l'animation des pixels qui remplissent la bulle tu dois énormément la
  travailler » — feindre la vidéo de design : la carte caméra se dissout pendant que ses
  pixels plongent dans la grande bulle qui se reconstitue en monde.
- **Plan pur testé** `core/.../Dissolve.kt` + `DissolveTest.kt` : morph de la bulle (grandit
  de son anneau jusqu'au centre), damier de désagrégation de la carte, cubique bézier du vol
  de chaque pixel, spots du « monde » posés sur le disque (la carte se remappe sur la bulle).
- **App** : `IseeDissolve.kt` (carte gelée, overlay damier + gerbe, `WorldMemory` persistant
  lié à la réponse), `IseeVisionCard.kt` (carte caméra extraite de la fenêtre), satellites
  et ticks réavailés, découpages pour la garde de tailles.

## 2026-10-26 (21) — plus aucune sonde `/health` : la première frame réveille la carte

- **Demande user** : « ok supprime les /health » (après le constat : `/health` toutes les ~20 s
  tenait une A100 allumée en continu, 2,415 $/h, sans une seule analyse).
- **Supprimé côté client** (il ne reste que le bouton « Tester SAM » des Réglages) :
  - `AppViewModel.prewarmGpu()` — le réveil **préventif** à l'ouverture de l'app et à l'affichage
    de l'écran caméra (`MainActivity.kt:63,127`, `AppNav.kt:116`) : c'était la source principale,
    une sonde toutes les 120 s tant que l'app était au premier plan ;
  - `LiveViewModel.wakeGpu()` — une sonde au démarrage du direct ;
  - `ihear/LiveProbe.wake()` — une sonde à la saisie de l'endpoint et au début d'enregistrement ;
  - `core/live/PrewarmGate.kt` (`shouldPrewarm`) et son test, devenus inutiles.
- **Le réveil passe par la première frame** : Modal met la requête en file pendant le démarrage
  (mesuré 8-35 s), donc aucune sonde n'est nécessaire. `LiveViewModel` n'attend plus un « warm »
  pour envoyer les frames ; l'état `starting` (« Réveil du GPU A100 40 Go sur Modal… ») se lève à
  la **première frame publiée**, et une frame en échec reste une ligne « réveil » pendant 60 s
  (`WAKE_TIMEOUT_MS`) avant de devenir une erreur. `LiveProbe` garde son état `warming` (même
  message dans la pastille) mais il est posé localement, sans requête.
- **Conséquence d'affichage** : le nom du GPU venait de `/health` → `formatLiveStatus` n'affiche
  plus « GPU ? » quand il est inconnu (`LiveState.kt`, test mis à jour) ; `shortGpu()` retiré.
- `releaseGpu()` (POST `/release` à la fermeture) est **conservé** : c'est l'inverse d'une sonde,
  il éteint la carte.

## 2026-10-26 (20) — distances sur la vidéo : la pastille devient du verre, reliée à sa boîte

- **Demande user** : « je trouve que c'est assez moche la manière dont les mètres distances sont
  affichées sur la vidéo sur la caméra, et je me demandais s'il serait possible de les afficher
  autrement ? », puis « teste ce que tu proposes ».
- **Plus de rectangle plein sur l'image** (`app/DistanceOverlay.kt`, `app/DistanceLayout.kt`) : la
  pastille devient du **verre sombre** — le texte blanc reste lisible sur n'importe quelle image — avec
  une **puce** et un **liseré** de la couleur du masque. Elle se pose au-dessus du **coin haut-gauche**
  de sa boîte, et un **fil de rappel** de la même couleur la relie à un point posé sur ce coin. La
  couleur ne remplit plus une vignette : elle **désigne** l'objet. C'est le fil qui lève l'ambiguïté que
  les pastilles centrées laissaient quand deux objets se touchent — et qui garde attachée à sa boîte une
  pastille que l'empilement a poussée vers le bas.
- **Placement réécrit, pur et testé** : `placePills` remplace le couple « centré + `spreadLabels` ».
  Alignement sur le coin de la boîte, bornage dans le cadre, jamais sous le bandeau du haut (inset
  230 dp), empilement vers le bas par pas de `hauteur + PILL_STACK_GAP` quand deux pastilles se
  recouvrent ; `PillPlacement.leadY()` choisit le bord que le fil touche (le bas quand la pastille est
  au-dessus, le haut quand elle est dessous). Le texte est mesuré (`TextMeasurer`) avant le placement :
  la pastille a exactement la largeur de son contenu, et tout part dans un seul `Canvas` — fils dessous,
  pastilles dessus. `pillTranslationX`, `pillTranslationY` et `spreadLabels` disparaissent avec leurs
  tests.
- **Ce qui ne change pas** : la mesure elle-même (sténopé, voir `docs/DISTANCES.md`), les couleurs de
  masque, la ligne d'état, les pastilles de concepts. Seule la mise en forme de l'étiquette change.
- **Vérifié** (poste Windows, JDK 17, `--offline`) : `:app:testDebugUnitTest` (**0 échec**, dont
  `DistanceLayoutTest` — 15 tests) et `assembleDebug` OK, `check-kotlin-limits.py` OK. **Non vérifié** :
  le rendu sur un téléphone (aucun appareil sollicité) ; l'aspect a été contrôlé sur une maquette canvas
  qui rejoue les mêmes formules (`tmp/distances-etiquettes.html`, capture `shots/distances-etiquettes.png`,
  tous deux hors dépôt).
- **Précision de la mesure** : toujours **non mesurée** (voir `docs/DISTANCES.md`) — ce changement ne
  touche que l'affichage.
## 2026-10-26 (22) — les clés sont dans l'APK, personne n'a rien à saisir

- **Demande user** : « mets de base les clés dans l'app, qu'une autre personne n'ait pas
  besoin de rentrer ses clés si elle télécharge l'app ».
- **Ce qui manquait vraiment** : le build local embarquait déjà le LLM et ElevenLabs
  (`local.properties` → `BuildConfig` → `seededFromBuild`), mais l'APK publié par la CI
  sortait **sans aucune clé** : le runner fait un checkout neuf, sans `local.properties`.
  Deux personnes sur la même version avaient donc l'une une app prête, l'autre une app qui
  réclamait des clés.
- **Clé de dictée Meta ajoutée au build** : `asr.api.key` → `BuildConfig.ASR_API_KEY`,
  remplie par `seedAsr` quand le champ est vide. Sans elle, la dictée retombait sur
  ElevenLabs Scribe.
- **CI** : nouvelle étape « Clés du build » qui écrit `local.properties` depuis les
  secrets du dépôt (`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `TTS_API_KEY`,
  `TTS_VOICE_ID`, `ASR_API_KEY`) ; sans secret, elle ne fait rien et le comportement
  d'avant est conservé. Aucune valeur n'est affichée.
- **Testable** : `BuildDefaults` isole les valeurs du build, `seeded()` devient une
  fonction pure ; `SettingsSeedTest` (4) prouve qu'une installation neuve hérite de tout,
  qu'une clé saisie gagne, et qu'un build sans clé laisse les champs vides.
- **Réglages** : le bloc « build » annonce aussi la dictée et rappelle que les clés du
  build remplissent tout champ vide.
- Docs : `docs/CLES-DU-BUILD.md` (origine, priorité, secrets à créer, avertissement : une
  clé dans un APK est extractible, donc distribution restreinte).

## 2026-10-26 (21) — iSee : la réponse arrive, l'intro reste relisible

- **Réponse IA invisible et muette — cause trouvée** : `MediaCacheStore` insérait la ligne Room
  mais **rendait l'objet d'id 0** (`dao.insert(row)` sans récupérer la clé auto-générée).
  `setAnswerText` et `saveReplyAudio` faisaient donc un `UPDATE … WHERE id = 0` : zéro ligne
  touchée, texte jamais écrit, aucun MP3 attaché, donc rien à afficher ni à prononcer. Les trois
  insertions rendent maintenant `row.copy(id = dao.insert(row))`.
- **Plus de « … » qui reste** : `ask()` remet `thinking = false` dans un `finally`, garanti même
  si une étape inattendue échoue. Une seule question à la fois : une tenue pendant qu'une réponse
  est en cours est refusée avec un motif au lieu d'envoyer un second appel concurrent.
- **409 « limite atteinte »** : le proxy LLM a refusé la requête (quota ou requête concurrente —
  le corps brut ne dit pas laquelle). `httpError` mappe 409 sur `RateLimited` avec « limite
  atteinte côté IA (409) : réessaie dans un instant » au lieu du « erreur HTTP 409 : … » brut, et
  l'app n'envoie plus deux questions simultanées.
- **Fermeture complète = plus rien** : en plus de la purge d'`onDestroy`, le ViewModel purge au
  démarrage — un force-stop ou un balayage des apps récentes ne passe pas par `onDestroy` et
  laissait les messages sur le téléphone. À la réouverture, seule la bulle d'intro existe.
- **L'intro est sélectionnable avec les autres bulles** : la sélection est un index sur **toutes**
  les bulles affichées (`0` = intro), plus sur les seuls échanges. On peut remonter jusqu'à elle
  pour la relire (`swipeStep`, `selectedEntryIndex`, `IhearPlayback`).
- **Animation d'évaporation supprimée** : `PixelFade`/`PixelDissolve` disparaissent ; le bandeau
  « iSee » est un aplat noir opaque sous lequel le fil passe et disparaît d'un coup.
- **Icônes de message** : hauteur d'une bulle d'une ligne (20 sp + 24 dp, fontScale compris) au
  lieu de 28 dp fixes, avec glyphes ❚❚/▶ en 14 sp ; teinte tirée de l'identifiant parmi huit
  bulles de savon métal/verre (`BubblePalette.kt`), stable d'un rendu à l'autre.
- **Bulles plus étroites** : 230 dp au lieu de 250 dp ; les icônes ajoutent leur largeur.
- **Bulle sélectionnée toujours entière** : position calculée par `offset − viewportStartOffset`
  et complément borné (`revealShift`), et le fil ne se raccroche plus à la fin quand l'utilisateur
  relit une bulle ancienne.
- **Tests** : `MediaCacheStoreTest` (3, l'id rendu et la réponse rattachée sur un faux DAO),
  `SwipeStepTest` (9, intro comprise), `RevealShiftTest` (4), `BubblePaletteTest` (3),
  `HttpErrorTest` (4, dont 409) ; `check-kotlin-limits` OK.
