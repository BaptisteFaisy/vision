# Recette — navigation vocale mains libres (une prise)

Fiche relue dans le **code de cet arbre** (`origin/master`, HEAD `97575a3`
au moment de l'écriture).
⚠️ **Recette non rejouée sur appareil** : aucun téléphone n'était piloté
d'ici, aucune commande `adb` n'a été lancée. Ce qui suit décrit ce que le
**code** fait ; le comportement à l'écran reste **non mesuré**.

> **Mise à jour 28/10** : le guidage in-app Valhalla démarre désormais
> directement après la dictée, sans bouton. Cette fiche décrit le **repli
> Google Maps**, utilisé quand le moteur ne peut pas démarrer (URL absente,
> hors Île-de-France, GPS ou réseau indisponible). Le chemin in-app est décrit
> dans `docs/NAVIGATION.md`.
>
> **Mise à jour 29/10** : le départ direct sans bouton est abandonné —
> la dictée in-app n'ouvre que l'écran de confirmation (question vocale
> « Do you really want to go to X? Travel time: Y. Answer yes or no. »,
> boutons « Démarrer »/« Annuler » en secours). Le repli Maps décrit ici
> est inchangé.
>
> **Mise à jour 31/10** : le départ direct est rétabli (décision user) et
> le repli Maps est supprimé — la dictée affiche « Finding the best
> route » (boule en rotation) puis guide, sans question ni boutons ; le
> moteur en panne rend le repos + « I can't open the guidance ». La
> recette du repli Maps ci-dessous est obsolète.

Le script `scripts/verify-voice-nav.sh` déroule cette fiche en une prise :
l'intégrateur parle (phrases § Recette, recopiées à l'identique), le script
capture l'observable et rend la checklist remplie.

## Ce qui est vrai dans le code aujourd'hui

Chaîne : appui long iSee → dictée (Meta puis repli ElevenLabs Scribe) →
`IseeViewModel.handleDictated` → routage **dans cet ordre** (`IseeViewModel.kt`) :
cap vocal (`SteeringCommand`) → navigation in-app (`VoiceNavSession`, si une
URL Valhalla est configurée) → navigation Maps (`NavigationHook`) → traversée
(`CrossingVoiceCommand`) → question LLM. Dans le repli Maps décrit ici, iSee ne
suit rien : Maps guide et recalcule ; l'arrivée est parlée par Maps, jamais par
iSee. Le chemin in-app, lui, suit le GPS et annonce l'arrivée.

| Étape | Observable promis par le code | Source |
|---|---|---|
| Commande | « je veux aller à X » consommé avant le LLM, X transmis tel quel à Maps | `NavigationIntentParser.kt`, `NavigationRouter.kt` |
| Confirmation | voix « Navigation vers X » + statut effacé (succès) | `NavigationAnnouncer.kt`, `NavigationHook.kt:56-60`, `strings.xml:nav_confirm_destination` |
| Guidage | intent `google.navigation:q=X&mode=w` ciblant `com.google.android.apps.maps`, replis URL universelle piétonne puis navigateur, sinon « Je ne peux pas ouvrir le guidage » + `Result.failure`, jamais d'exception | `NavigationLauncher.kt:111-160`, `strings.xml:nav_guidance_unavailable` |
| Hors-trace | rien côté iSee (Maps recalcule seul) | aucun code de suivi dans le chemin navigation |
| Arrivée | rien côté iSee (Maps annonce seul) | idem |

Écart corrigé le 28/10 : `NavigationHook` et `VoiceNavSession` passent
désormais `TravelMode.WALK` ; le lanceur et `MapsUri` ont aussi `w` par défaut.
L'intent réel porte `mode=w`, verrouillé par `NavigationLauncherTest` et
`VoiceNavScriptTest`.

Phrases verrouillées par `VoiceNavScriptTest` (recopies exactes, garde § 7 de
`scripts/test_scripts.sh`) : « je veux aller à la gare de Lyon »,
« Emmène-moi à la tour Eiffel », « Où est la pharmacie la plus proche ? »,
« Emmène-moi à » (vide → chemin question), « va à gauche » (piège cap :
`SteeringCommand` prioritaire sur l'intention Maps).

## Prérequis

- Build du HEAD de `origin/master` (`git ls-remote origin master` comparé au
  SHA du build — règle ISEE-TEST-MASTER-SEUL ; le script le fait), APK installé
  sur le Redmi `2c6852ac`, prise/libération annoncée dans `BOITE-USER.md`.
- Réglages iSee : bascule « Guider vers la destination confirmée » sur oui,
  clé ElevenLabs + voix renseignées. Google Maps installé et connecté.

## Recette (une prise, ~10 min)

1. **Commande.** Appui long ≥ 300 ms sur iSee, dicter
   « je veux aller à la gare de Lyon », relâcher. Attendu : transcription
   affichée, pas de crash, pas de question LLM.
2. **Confirmation.** Attendu : question vocale
   « Do you really want to go to la gare de Lyon? Travel time: … » ;
   répondre « yes » (ou « Démarrer »). Attendu ensuite : voix
   « Navigation vers la gare de Lyon » (clé `nav_confirm_destination`).
3. **Guidage.** Attendu : Google Maps au premier plan, guidage actif vers
   la gare de Lyon, **mode piéton** (`mode=w`). Capture écran.
4. **Hors-trace.** S'écarter d'un pâté de maisons. Attendu : Maps recalcule
   seul ; iSee ne dit et n'affiche rien (surtout pas « écarté »).
5. **Arrivée.** Aller au bout (ou finir la démo Maps). Attendu : Maps annonce
   l'arrivée ; iSee reste silencieux. Retour iSee : écran d'accueil, pas de crash.
6. **Contre-épreuve** (optionnelle, même prise). Redicter
   « Où est la pharmacie la plus proche ? » → pas de bascule Maps, réponse
   chat normale. Puis « Emmène-moi à » (sans X) → pas d'erreur, chemin
   question, zéro annonce navigation.

Chaque point : noter le SHA du build, l'heure, et l'observable (texte écran +
phrase entendue + app au premier plan). Le script range tout dans le procès-verbal.

## Ce qui est vérifié sans appareil (tests JVM)

- `core/.../nav/VoiceNavScriptTest` (**6**) : les 5 phrases E2E routées par la
  vraie chaîne (cap → navigation → traversée), destination exacte, intent
  navigation `mode=w` pour « la gare de Lyon », piège « va à gauche » documenté.
- `core/.../nav/NavigationFlowTest` (**4**), `NavigationIntentTest`,
  `DestinationQueryTest`, `MapsUriTest`, `NavigationRouterTest`,
  `app/.../NavigationHookTest`, `NavigationLauncherTest` : parseur, requêtes,
  URIs, replis — existants, inchangés.
- `bash scripts/test_scripts.sh` : hygiène du script (usage, gardes, phrases
  présentes) sans appareil ni Gradle.
- Exécution locale du 28/10 : `:app` ciblé **46/46** (`NavigationLauncherTest`
  12, `NavigationHookTest` 6, `VoiceNavSessionTest` 20,
  `VoiceNavSessionWiringTest` 8), `:core` `VoiceNavScriptTest` **6/6** ;
  `check-kotlin-limits.py` OK.

## Non mesuré (à ne pas vendre comme vérifié)

- **La recette n'a pas été rejouée sur appareil** : non mesuré.
- Voix « Navigation vers X » réellement entendue, overlay 34 sp, bascule Maps :
  non mesuré (exige le téléphone + la voix).
- Recalcul et annonce d'arrivée par Maps, silence d'iSee pendant ces phases :
  non mesuré.
- Mode réel de l'intent (attendu `w`) : à observer étape 3.
- Comportement Maps absent (repli navigateur) et guidage éteint (fiche lieu) :
  couverts par les tests JVM du lanceur, non rejoués sur appareil.
