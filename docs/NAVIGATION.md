# Navigation mains libres (Google Maps)

iSee ne guide pas : sur ordre vocal, il ouvre **Google Maps** avec la
destination. Pas de GPS, pas de carte, pas d'instructions virage par
virage dans l'app — tout le guidage vit chez Google Maps.

Le fonctionnel (parcours, annonces, cas limites, recette, catalogue de
chaînes) vit dans `docs/NAVIGATION-VOCALE.md` ; ce document reste le contrat
technique (URIs, replis, permissions).

## Déclenchement vocal

Chaîne : appui long iSee → dictée (`DictationFeature`, Meta puis repli
ElevenLabs) → `IseeViewModel.handleDictated` → `NavigationHook.onTranscript`,
**avant** le chemin question → Room → LLM. La commande est détectée en
déterministe sur le texte transcrit (insensible casse/accents, zéro appel LLM) :

- « je veux / voudrais / vais aller à _X_ », « j'aimerais aller à _X_ »
- « emmène-moi à _X_ », « conduis-moi à _X_ », « guide-moi jusqu'à _X_ »
- « naviguer vers _X_ », « itinéraire vers _X_ », « aller à _X_ », « va à _X_ »

_X_ = reste de la phrase, transmis tel quel à Maps (pas de géocodage
local). « Où est _X_ » reste une question pour le LLM, pas un ordre.
Destination vide ou incomprise → le texte suit le chemin question normal.

Fichiers : `core/.../nav/` (parseur, normalisation, URIs),
`core/.../usecase/NavigationRouter.kt`, `app/.../isee/vm/NavigationHook.kt`.

## Formats d'URI

Implémentation unique : `core/.../nav/MapsUri.kt` (pur JVM, testé sans
Robolectric). Encodage percent-UTF-8, espace en `%20`.

| Usage | URI réelle |
|---|---|
| Guidage direct piéton (défaut) | `google.navigation:q=la%20gare&mode=w` |
| Guidage direct voiture | `google.navigation:q=Gare%20de%20Lyon&mode=d` |
| Repli universel (sans l'app Maps) | `https://www.google.com/maps/dir/?api=1&destination=...&travelmode=walking` |
| Fiche lieu (guidage éteint) | `https://www.google.com/maps/search/?api=1&query=...` |

Modes `google.navigation` : `d` voiture, `w` piéton, `b` vélo.
Le GPS d'iSee force toujours `w` : `d`, `b` et `l` restent des formats
supportés par `MapsUri`, jamais utilisés par la navigation.
Intent : `ACTION_VIEW`, `setPackage("com.google.android.apps.maps")` en
premier (fichier `app/.../navigation/NavigationLauncher.kt`).

## Replis

1. Maps installé → intent `google.navigation:` ciblé.
2. Maps absent → URL universelle `google.com/maps/dir/?api=1` (navigateur).
3. Aucune app capable → annonce TTS « Je ne peux pas ouvrir le guidage »
   + `Result.failure`, **jamais d'exception** (pas de `resolveActivity`,
   le `startActivity` qui échoue suffit à passer au repli suivant).
4. Réglage « Guidage GPS » éteint → la fiche du lieu s'affiche
   (`openPlace`), sans lancer de guidage.

Aucune permission neuve : ni `ACCESS_FINE_LOCATION` (c'est Maps qui
se localise), ni `QUERY_ALL_PACKAGES` (un bloc `<queries>` déclare Maps).

## Réglage

Même motif que les sections voisines (`SettingsScreen.kt` : brouillon + `onSave`) :

- `Settings.navigationGuidanceEnabled: Boolean = true`
- Section Réglages « Navigation — guidage GPS » (`NavigationToggle.kt`)

## Navigation vocale in-app (Valhalla)

Depuis le 31/10, `app/.../voicenav/` démarre directement : dictée →
`VoiceNavSession.onVoiceTranscript` → chargement (« Finding the best
route », boule iSee en rotation rapide) → guidage moteur, sans question
ni bouton (`autoStartOnVoice = true` câblé par `IseeVoiceNav`). L'écran
de confirmation (`autoStartOnVoice = false`) subsiste comme API
historique pour les tests de session (`VoiceNavSessionTest`) ; aucun
chemin produit ne l'ouvre. Si le moteur ne peut pas démarrer (adresse,
GPS, réseau, itinéraire), repos + annonce « I can't open the guidance » :
aucun repli externe, iSee n'ouvre jamais Google Maps.

Deux voix se partagent une session : les consignes du moteur (virages,
écart, arrivée) passent par `SystemTtsEngine` — le TTS Android, français,
gratuit et hors-ligne, car un virage n'attend pas le réseau ; les annonces de
session (`VoiceNavAnnouncer` : fiche lieu, « Guidage arrêté »,
« Arrivée à destination ») passent par `speakNavigation`, la voix ElevenLabs
de l'app. Aucune annonce n'est doublée : au démarrage vocal, seule la voix du
moteur dit « Navigation vers X ».

Dans ce chemin historique, le résumé est fourni par `ValhallaRouteLoader`
(géocodage GMS puis BAN + `POST /route` piéton, adresse seule sans position ni
URL) et l'état du moteur par `VoiceNavEngineDriver`
(`VoiceNavViewModel.onGuidanceUpdate`, voix système + écran). Ces écrans ne
s'ouvrent que si `Settings.routeBaseUrl` est renseignée (seed du build ou
Réglages) : sinon, `IseeViewModel` garde le handoff direct ci-dessus.

### Contrat vocal virage (deux temps, jamais de distance)

Un virage franc (tourner, serrer — `ManeuverAction.toSteeringCap`) se dit
en deux temps, avec la rue quand Valhalla la donne : de loin (au-delà de
`VoiceNavPolicy.TURN_NOW_M` = 25 m), préavis directionnel
« Continuez dans cette direction, rue X. », dit une seule fois ; de près,
ordre sec « Tournez à gauche, rue X. », sans distance. Aucun virage franc
ne contient plus « dans X mètres ». Inchangés : demi-tour
(« Faites demi-tour dans X »), giratoire (« Au rond-point, suivez la
route dans X »), traversée (« Passage protégé dans X ») et arrivée
(« Vous êtes arrivé à X »).

### Recette appareil — wording virage (protocole, non rejoué)

⚠️ **Protocole consigné sans téléphone** : aucune étape ci-dessous n'a été
rejouée sur appareil. Preuve JVM acquise : `VoiceNavPhrasesTest`,
`VoiceNavPolicyTest`, `VoiceNavSpeakerTest`, `VoiceNavScriptTest`,
`NavigationHookTest` verts (voir `docs/TESTING.md`).

Prérequis : build du HEAD de `origin/master`, `routeBaseUrl` renseignée
(proxy Valhalla), GPS actif, parcours avec un virage franc à plus de
100 m du départ. Noter SHA du build, heure et phrases entendues.

1. Dicter une destination dont l'itinéraire commence par plus de 100 m
   puis un virage franc nommé (rue X). Attendu : une seule fois
   « Continuez dans cette direction, rue X. », puis silence jusqu'au
   carrefour (aucune répétition à distance intermédiaire).
2. Marcher jusqu'au carrefour. Attendu : « Tournez à gauche, rue X. »
   (ou droite), **sans distance**, audible avant d'avoir à tourner.
3. Pendant tout le trajet : vérifier qu'aucun virage franc entendu ne
   contient « dans X mètres ».
4. Demi-tour, giratoire, traversée, arrivée : vérifier les phrases
   inchangées du § contrat ci-dessus (distances conservées).

## Limites

- **Guidage in-app piéton, Île-de-France uniquement** : pas de carte, pas de
  voiture, pas de vélo, pas de transport ; hors tuiles IdF, le moteur échoue
  et Maps piéton prend le relais.
- **Géocodage GMS puis BAN** : le géocodeur Android résout les lieux ; le
  repli BAN est une base d'adresses (pas de POI), donc une destination
  ambiguë peut partir ailleurs. La destination est relue à voix haute.
- **Hors-ligne** : sans réseau, ni Valhalla ni BAN ne répondent ; iSee ne
  pré-cache ni carte ni itinéraire.
- **Vie privée** : la destination et la position de départ partent chez
  Valhalla (Modal) ; la destination part aussi chez Google/BAN pour le
  géocodage. Rien n'est journalisé côté iSee au-delà du texte dicté.
- **Écran verrouillé** : le micro verrouillé (`LockedMicAnswer`) ne route
  pas encore vers la navigation — chantier suivant.
