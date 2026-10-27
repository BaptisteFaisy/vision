# Navigation mains libres (Google Maps)

iSee ne guide pas : sur ordre vocal, il ouvre **Google Maps** avec la
destination. Pas de GPS, pas de carte, pas d'instructions virage par
virage dans l'app — tout le guidage vit chez Google Maps.

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

## Limites

- **Pas de guidage in-app** : iSee lance, Maps guide. Si Maps est tué
  ou déconnecté, iSee ne reprend rien.
- **Pas de géocodage local** : la résolution « Gare de Lyon » →
  coordonnées est faite par Maps ; une faute de dictée donne une
  mauvaise destination (la destination est relue à voix haute au lancement).
- **Hors-ligne** : sans réseau, Maps peut échouer ; iSee ne pré-cache
  ni carte ni itinéraire.
- **Vie privée** : la destination transcrite part chez Google via
  l'intent ; rien n'est journalisé côté iSee au-delà du texte dicté.
- **Écran verrouillé** : le micro verrouillé (`LockedMicAnswer`) ne route
  pas encore vers la navigation — chantier suivant.
