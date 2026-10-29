# Recette — rue : guidage in-app + obstacles + traversée (une prise)

Fiche relue dans le **code de cet arbre** (branche
`integration-isee-outdoor-p1p6`, base `origin/master` `6aaf881`,
intégration P1–P6).
⚠️ **Recette non rejouée sur appareil** : aucun téléphone n'était piloté
d'ici, aucune commande `adb` n'a été lancée. Ce qui suit décrit ce que le
**code** fait ; le comportement dans la rue reste **non mesuré**.

Le script `scripts/verify-street.sh` déroule cette fiche en une prise
(~15 min) : l'intégrateur dicte une destination à ~200 m, marche, passe
les obstacles imposés, et le script capture l'observable horodaté
(phrases exactes entendues, écran, crash) puis rend la checklist remplie.
Tout le téléphone passe par le verrou `~/bin/phone` (`PHONE_WAIT=0`,
jamais de force : occupé = on s'arrête, on relance plus tard).

## Ce qui est vrai dans le code aujourd'hui

Chaîne : appui long iSee → dictée → `NavigationHook` (avant le LLM) →
écran de confirmation (question vocale EN + « Démarrer »/« Annuler ») →
moteur Valhalla (`VoiceNavEngine` + `VoiceNavEngineDriver`, si
`routeBaseUrl` est renseignée) → consignes route par la voix Laure
(`speakRoute`, **même tuyau que les obstacles : jamais deux voix en même
temps**) ; sinon repli Google Maps piéton (`TravelMode.WALK`) décrit dans
`docs/RECETTE-NAVIGATION-VOCALE.md`.

| Étape | Observable promis par le code | Source |
|---|---|---|
| Dictée | « je veux aller à X » consommé avant le LLM, X transmis tel quel | `NavigationIntentParser.kt`, `NavigationRouter.kt` |
| Confirmation | question « Do you really want to go to X? Travel time: Y. Answer yes or no. », puis « Navigation vers X » au « yes »/« Démarrer » | `VoiceNavPolicy.kt:293-297`, `NavigationAnnouncer.kt:18` |
| Consigne loin/près | préavis à 100 m (« Continuez dans cette direction[, rue X]. »), ordre sec à 25 m (« Tournez à gauche[, rue X]. », sans distance), une fois chacun (front moteur + signature `ahead`/`now`) | `VoiceNavEngine.kt:7-17,228-241`, `VoiceNavPolicy.kt:113-129,342-350,392-398` |
| Poteau | « Attention au poteau, à … heure. » après 2 frames confirmées, silence 10 s, en douceur (ne coupe rien) | `HazardConcepts.kt:29-30`, `HazardWatcher.kt:92-95,132-135`, `HazardText.phrase` |
| Poubelle | position chiffrée (« … à N mètres », 0,90 m sténopé, « poubelle »/« trash can ») et/ou mot de côté « gauche. »/« droite. » (couloir bloqué) | `KnownSizes.kt:70-71`, `Guidance.kt`, `TwinLiteWord` |
| Benne | position chiffrée (« … à N mètres », 1,50 m sténopé, « benne »/« dumpster ») et/ou mot de côté (couloir bloqué) | `KnownSizes.kt:71`, `Guidance.kt`, `TwinLiteWord` |
| Panneau bas | position (« … à N mètres », 2,20 m sténopé) et/ou mot de côté ; panneau haut ignoré (filtre tête : centre < 0,22 et mesuré < 4 m) | `KnownSizes.kt:69`, `SignConcepts.kt:30-36,61-66` |
| Animal | position chiffrée (« chien à N mètres », 0,55 m ; « chat … », 0,25 m ; « animal » générique, 0,60 m) | `KnownSizes.kt:55-57`, `PositionText` |
| Virage + obstacle | 0 chevauchement : une seule voix, la consigne route se met en file (`Instruction`), le danger coupe (`Danger`), deux phrases de même niveau s'enchaînent ; obstacle non urgent différé tant que la route parle, urgent coupe toujours | `VoiceNavEngineDriver.kt:49-57,200-201`, `SpeechArbitration.kt:18-26,48-49`, `ProximityVoice.kt:42,67`, `IseeVoiceNav.kt:146` |
| Traversée, préavis | « Passage protégé dans X » (loin), jamais de « tout droit » au bord du trottoir | `VoiceNavPhrases.crossingAhead` (`VoiceNavPolicy.kt:272-275`), `VoiceNavEngine.kt:225-226` |
| Traversée, rappel proche | le mode s'arme **une seule fois**, sa voix prend le relais (« Mode traversée activé. »), la progression se tait | `VoiceNavEngineDriver.kt:253-263`, `IseeViewModel.kt:231-235` |
| Traversée, feu | « Vert, traversez. » / « Rouge, attendez. » / « Doute, attendez. » | `CrossingText.kt:26-28` |
| Traversée passée | « Traversée terminée. », le mode auto se coupe, le guidage reprend | `VoiceNavPolicy.kt:277-278`, `VoiceNavEngineDriver.kt:266-272` |
| Arrivée | « Vous êtes arrivé à X » (rayon 20 m), une seule fois, moteur arrêté | `VoiceNavPolicy.kt:261-265`, `VoiceNavEngine.kt:11`, `VoiceNavEngineDriver.kt:223-226` |

Seuils moteur (`VoiceNavEngine.kt:7-17`, `NavTuning`) : annonce loin
100 m, rappel proche 25 m, arrivée 20 m, validation de manœuvre 12 m. La
destination à ~200 m fait passer les trois : annonce loin, rappel proche,
arrivée.

Le « délai annonce < 1 s après confirmation » reprend le seuil déjà
pratiqué pour le trottoir (`docs/VOIX-DANGER.md` § 4) : la confirmation,
c'est la 2ᵉ frame (`HazardWatcher`), le chronomètre part là.

## Prérequis

- Build du HEAD attendu sur origin (`EXPECT_REF`, défaut `master` : `git
  ls-remote origin $EXPECT_REF` comparé au SHA du build — règle
  ISEE-TEST-MASTER-SEUL, « HEAD de la ref recettée » ; le script le fait),
  APK installé
  sur le Redmi `2c6852ac` **via le verrou** `~/bin/phone`, prise/libération
  annoncée dans `BOITE-USER.md`.
- Réglages iSee : clé ElevenLabs + voix renseignées, bascule « Guider vers
  la destination confirmée » sur oui, bascule « Guider tout droit (caméra
  iSee) » activée, **URL Valhalla (`routeBaseUrl`) renseignée** (sans elle,
  pas de guidage in-app : le repli Maps prend le relais).
- Terrain : **Île-de-France** (tuiles Valhalla), GPS fixé, réseau OK, **de
  jour** (la traversée ne traite que le jour, `docs/CALIBRAGE-FEU.md` § 9).
- Parcours repéré à l'avance (~200 m) : un **poteau** dans le couloir, une
  **poubelle** (ou benne), un **panneau bas**, un **passage piéton sur
  l'itinéraire** (pour la traversée auto), un animal si possible, et au
  moins un **virage** avant l'arrivée.

## Recette (une prise, ~15 min)

1. **Dictée.** Appui long ≥ 300 ms sur iSee, dicter
   « je veux aller à X » (X = adresse à ~200 m, notée au procès-verbal),
   relâcher. Attendu : transcription affichée, pas de crash, pas de
   question LLM.
2. **Confirmation.** Attendu : question vocale
   « Do you really want to go to X? Travel time: … » ; répondre « yes »
   (ou « Démarrer »). Attendu ensuite : voix « Navigation vers X ».
3. **Poteau.** Marcher vers X, approcher un poteau dans le couloir.
   Attendu : « Attention au poteau, à … heure. » **< 1 s après
   confirmation**, éventuellement doublé d'un mot de côté (« gauche. » /
   « droite. »). Noter la phrase exacte + l'heure. Capture écran.
4. **Poubelle/benne.** Attendu : position (« … à N mètres », poubelle
   0,90 m, benne 1,50 m) et/ou mot de côté. Noter la phrase exacte +
   l'heure + le délai (< 1 s).
5. **Panneau bas.** Attendu : mot de côté (« gauche. » / « droite. »),
   éventuellement position (« … à N mètres », 2,20 m) ; un panneau
   haut (enseigne) ne dit rien (filtre tête). Phrase exacte +
   heure + délai (< 1 s).
6. **Animal (facultatif).** Si un chien/chat passe dans le champ :
   attendu position (« chien à N mètres »). Sinon noter « non
   rencontré » — l'item reste vide, pas d'échec.
7. **Virage.** Attendu : préavis (« Continuez dans cette direction[,
   rue X]. ») puis ordre sec (« Tournez à …[, rue X]. », sans
   distance) ; **0 chevauchement** : virage et obstacle ne parlent
   jamais l'un sur l'autre (file ou coupe franche, jamais deux voix ;
   obstacle non urgent différé pendant la consigne). Capture écran.
8. **Traversée.** Attendu : préavis « Passage protégé dans … » ; au
   rappel proche, « Mode traversée activé. » et la progression se tait ;
   feu (« Vert, traversez. » / « Rouge, attendez. » / « Doute,
   attendez. ») ; une fois passé, « Traversée terminée. » puis **retour
   au libre** (consigne route ou « Tout droit. »). Capture écran.
9. **Arrivée.** Marcher jusqu'à X. Attendu : « Vous êtes arrivé à X »,
   une seule fois ; retour iSee : écran d'accueil, pas de crash.

Chaque point : noter le SHA du build, l'heure, et l'observable (phrase
exacte entendue + texte écran). Le script horodate et range tout dans le
procès-verbal. Tout écart au tableau ci-dessus part en **issue GitHub**,
pas en commentaire.

## Checklist vierge (une ligne par item : observable + SHA + verdict)

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| 1 | Transcription de « je veux aller à X » affichée, pas de crash | | | |
| 2 | « Navigation vers X » entendue après « yes »/« Démarrer » | | | |
| 3 | « Attention au poteau, à … heure. », phrase exacte : … | | | |
| 3b | Délai poteau < 1 s après confirmation | | | |
| 4 | Poubelle/benne, phrase exacte : … | | | |
| 4b | Délai poubelle/benne < 1 s après confirmation | | | |
| 5 | Panneau bas, phrase exacte : … | | | |
| 5b | Délai panneau < 1 s après confirmation | | | |
| 6 | Animal, phrase exacte (ou « non rencontré ») : … | | | |
| 7 | Consigne + rappel de virage, phrases exactes : … | | | |
| 7b | 0 chevauchement virage/obstacle (jamais deux voix) | | | |
| 8 | « Passage protégé dans … » au préavis | | | |
| 8b | « Mode traversée activé. » au rappel proche, progression tue | | | |
| 8c | Feu, phrase exacte : … | | | |
| 8d | « Traversée terminée. » puis retour au libre | | | |
| 9 | « Vous êtes arrivé à X », une seule fois, pas de crash | | | |

## Première prise

**Non rejouée** : pas d'appareil piloté d'ici (`adb devices` vide,
verrou `~/bin/phone` absent de cette machine). La checklist ci-dessus
est prête ; la première prise l'archive (procès-verbal +
`shot-*.png` du script) et remplit chaque verdict — aucun n'est
pré-rempli ici.

## Ce qui est vérifié sans appareil (tests JVM)

Comptés sur cet arbre (`@Test` par fichier) :

- `core/.../nav/VoiceNavEngineTest` (**6**) : fronts loin/près,
  traversée, écart, arrivée ; `VoiceNavCrossingTest` (**3**),
  `CrossingManeuverTest` (**4**).
- `core/.../hazard/HazardConceptsTest` (**5** : « au poteau » reconnu),
  `HazardWatchTest` (**9** : 2 frames, cooldown 10 s, douceur).
- `core/.../live/GuidanceWordTest` (**6**), `TwinLiteWordTest` (**9**),
  `GuidanceAnnouncerTest` (**18**, arbitrage une seule voix),
  `PositionTextTest` (**14**, cadran + distances).
- `app/.../speech/VoiceNavPhrasesTest` (**10**, phrases exactes),
  `VoiceNavPolicyTest` (**29**, seuil 25 m + signatures `ahead`/`now`),
  `SpeechArbitrationTest` (**6**, file/coupe), `VoiceNavSpeakerTest`
  (**15**) ; `app/.../voicenav/VoiceNavEngineDriverTest` (**4**,
  relais traversée), `VoiceNavSessionTest` (**26**, confirmation).
- `core/.../live/SignConceptsTest` (**10**, filtre tête + composés
  backend), `LivePromptsTest` (**13**, presets + mondes),
  `core/.../settings/OutdoorModeTest` (**4**, bascule persistée).
- `bash scripts/test_scripts.sh` : hygiène du script (usage, gardes,
  verrou, phrases présentes aux § 4 et § 8) sans appareil ni Gradle.

## Non mesuré (à ne pas vendre comme vérifié)

- **La recette n'a pas été rejouée sur appareil** : non mesuré.
- Phrases « Attention … », mots de côté, consignes de virage réellement
  entendus dans la rue, dans le bon ordre, sans chevauchement : non mesuré
  (exige le téléphone + la marche).
- Délai annonce < 1 s après confirmation : non mesuré (chronomètre de
  l'intégrateur, § Recette).
- Relais traversée au rappel proche sur un vrai passage piéton
  (« Mode traversée activé. », feu lu, « Traversée terminée. », retour au
  libre) : couvert en JVM (`VoiceNavEngineDriverTest`,
  `VoiceNavCrossingTest`), non rejoué sur appareil.
- Comportement benne (position chiffrée 1,50 m + prudence) et animal :
  non mesuré.
- Filtre tête des panneaux (enseignes tues, panneaux bas gardés) :
  couvert en JVM (`SignConceptsTest`), seuils non calibrés au
  téléphone (corpus `VOIX-DANGER.md` § 14 vides).
- Repli Maps quand Valhalla est injoignable pendant la marche : couvert
  par la fiche navigation vocale, pas rejoué ici.
