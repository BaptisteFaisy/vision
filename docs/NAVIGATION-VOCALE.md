# Navigation vocale mains libres — spécification fonctionnelle

Référence fonctionnelle du chantier « navigation vocale » d'iSee :
parcours utilisateur, annonces vocales, cas limites, recette téléphone et
catalogue de chaînes. Le contrat technique (URIs, replis, permissions) reste
`docs/NAVIGATION.md` — ce document n'y touche pas, il le cite.

**Périmètre MVP** : sur ordre vocal, iSee ouvre **Google Maps** avec la
destination dictée. Pas de GPS, pas de carte, pas de virage-par-virage dans
l'app. Le géocodage local (§7.1) et l'itinéraire Valhalla (§7.2) sont des
chantiers liés, **hors MVP** : ce document les cadre sans les promettre.

> **Mise à jour 28/10** : un guidage in-app Valhalla (piéton, tuiles
> Île-de-France) existe désormais et démarre directement après la dictée.
> Le MVP décrit ci-dessous reste le handoff Maps, utilisé en repli.

**Cohérence outillée** : `tools/check-voice-nav-doc.py` vérifie que les
phrases, le catalogue « en place » et les chiffres cités ici sont ceux du
code (porte du job `ci`, comme `check-budget-derive.py`).

## 1. Parcours utilisateur MVP (malvoyant d'abord, mains libres)

1. **Déclencheur** : appui long (~300 ms) n'importe où sur la surface iSee →
   overlay « Listening / Speak, then release to send. » (motif
   `docs/TEST-LONGPRESS-DICTEE.md`, inchangé).
2. **Dictée** : relâchement → transcription ASR (Meta puis repli ElevenLabs
   Scribe) → overlay « Transcription / One moment. », puis texte transcrit
   affiché + relu.
3. **Extraction** : `NavigationHook` analyse le texte **avant** le LLM
   (insensible casse/accents), avec les 24 motifs GO du §6 : 12 français (« je veux /
   voudrais / vais aller à X », « j'aimerais aller à X », « emmène-moi /
   conduis-moi à X », « guide-moi jusqu'à X », « naviguer vers X », « itinéraire vers X », « aller à X », « va à X ») et 12 anglais (« I want / wanna / would like to go to X », « I'd like to go to X », « take / bring me to X », « navigate to X », « guide me to X », « going to X », « let's go to X », « go to X », « directions to X »), plus les formes orales
   sans préposition (« je veux aller X », « aller X », « va X », « I want to go X », « take me X », « go X ») et
   contractées (« aller au / aux X ») : la STT mange souvent la préposition (« à », « to »), et un
   complément d'action (« aller vite », « go fast ») n'est jamais une destination. X = reste
   de la phrase, transmis tel quel à Maps, aucun géocodage local. Un ordre sans destination comprise (« I want to go », « take me »…) ne part pas au
   LLM : iSee redemande la destination (`NavClarify`).
   La dictée du **direct** suit la même extraction : une destination comprise
   ouvre l'écran de navigation
   (`app/src/main/java/dev/sam3vision/app/live/LiveNavOrders.kt`), une demande
   d'itinéraire incomplète redemande l'adresse — jamais de description de la
   scène à la place.
4. **Confirmation (implicite, one-shot)** : pas de question oui/non (non-but
   MVP : garder le geste unique mains libres). La confirmation = relecture
   vocale immédiate « Going to X » + overlay affichant la destination
   (34 sp). Si la destination entendue est fausse, l'utilisateur dit « stop guidance » (ordre STOP, consommé avant le LLM) puis redicte.
5. **Guidage** : handoff système — intent `google.navigation:q=X&mode=w`
   (piéton par défaut) ciblé sur `com.google.android.apps.maps`. Maps guide
   (virages, arrivée). iSee ne suit rien, ne reprend rien si Maps est tué.
6. **Retour / arrivée** : l'arrivée est annoncée par Maps, pas par iSee (ni
   GPS ni suivi). Retour système → iSee affiche son écran courant ; « c'est
   arrivé ? » reste une question LLM, pas un état.
7. **Réglage** : interrupteur existant « Guide to the confirmed destination » (défaut oui) ; à non → fiche lieu (`geo:` / URL search) sans
   guidage.

## 1 bis. Pas de carte sans destination (retiré le 29/10/2026)

Un « mode navigation » sans destination a existé (carte + position GPS à
l'écran, ordres « navigation », « carte », « où suis-je ») : il affichait la
carte **sans destination rentrée**, contrairement à l'ordre du 29/10. Retiré :
la carte OSM n'apparaît qu'avec un objectif de destination (confirmation,
aperçu, ou guidage, suivi — `docs/CARTE-OSM.md`). « navigation », « carte »,
« où suis-je » ne sont plus des ordres de mode : sans destination reconnue,
ils suivent le chemin normal (question → LLM).

## 2. Politique d'annonces vocales (phrase exacte par événement)

| Événement | Phrase exacte |
|---|---|
| Écoute | « Listening. Speak, then release to send. » |
| Transcription en cours | « Transcription. One moment. » |
| Destination comprise + guidage lancé | « Going to X » |
| Repli navigateur (Maps absent) | « Going to X » (même phrase ; l'overlay dit « Route opened in the browser ») |
| Guidage impossible (aucune app capable) | « No guidance. » |
| Réglage guidage éteint | « Page: X. » |
| Ordre STOP (« stop / cancel the guidance / navigation », « arrête / stop le guidage ») | « Stopped » |

Règles : une seule voix à la fois (la phrase en cours est coupée si un
nouvel ordre arrive) ; erreurs toujours vocales + overlay, jamais overlay
seul ; langue des phrases = langue de la voix (`SpeechLanguage`), pas de
l'interface ; sans clé TTS configurée : aucune annonce, overlays texte seuls
— le lancement Maps reste possible.

Note STOP : Maps n'est pas pilotable par iSee ; « Stopped » confirme
l'oubli de destination + le retour à l'accueil iSee (à dire une fois dans
l'aide vocale).

## 3. Cas limites — UX exacte de chacun

1. **Aucune destination** : un ordre sans X (« go to… » vide, « I want to go ») redemande la destination (« I didn't catch where you want to go… », `NavClarify`), jamais le LLM ; un bruit sans tête d'ordre
   suit le chemin question normal (Room → LLM), zéro annonce navigation.
2. **Phrase ambiguë** (« where is X », « how far is X? ») : reste une question
   LLM, jamais un ordre ; aucune annonce navigation.
3. **Hors-trace** (l'utilisateur s'écarte) : RIEN côté iSee — Maps recalcule
   et annonce seul ; iSee ne surveille pas la position (pas de permission
   localisation, par design). Ne jamais afficher « you are off route ».
4. **GPS perdu** : RIEN côté iSee — Maps affiche/parle son propre message ;
   iSee ne détecte pas l'état GPS.
5. **Backend ASR injoignable** (Meta KO) : repli automatique Scribe ; si les
   deux échouent : overlay Erreur rouge « Error. \<msg Meta\>. \<msg Scribe\>. »
   + lecture vocale des deux messages ; destination jamais inventée.
6. **TTS / ElevenLabs KO au moment de confirmer** : le lancement Maps a quand
   même lieu (le guidage ne dépend pas de la voix) ; overlay « Going to X » seul, sans audio ; erreur TTS journalisée, pas parlée (on ne
   parle pas d'une panne de voix à la voix).
7. **Maps absent** : repli URL universelle navigateur (overlay « Route opened in the browser ») ; si aucun navigateur : « No guidance. » + `Result.failure`, jamais de crash.
8. **Micro occupé** (vidéo tap en cours) : refus en amont « microphone already busy with video » (libellé MicBus), pas d'écoute.
9. **Micro verrouillé** (écran verrouillé) : hors scope MVP — `LockedMicAnswer`
   ne route pas encore vers la navigation (limite documentée
   `docs/NAVIGATION.md`) ; comportement verrouillé actuel, sans annonce.

## 4. Recette téléphone (chaque point : SHA du build, heure, observable)

Préalable : build depuis le HEAD de `origin/master` (`git ls-remote origin
master` comparé au SHA du build — règle ISEE-TEST-MASTER-SEUL), APK installé
sur le Redmi `2c6852ac`, clé ElevenLabs + voix renseignées, bascule « Guide to the confirmed destination » sur oui, annonce prise/libération du
téléphone dans `BOITE-USER.md`.

1. Appui long 1 s au centre → overlay « Listening / Speak, then release to send. » + annonce vocale ; relâcher en silence → overlay Transcription
   puis retour chat sans annonce navigation.
2. Dicter « take me to King's Cross station » → overlay « Going to King's Cross station » + voix « Going to King's Cross station » + bascule vers
   Google Maps en guidage piéton vers cette destination.
3. Dicter « where is the nearest pharmacy » → PAS de bascule Maps ;
   réponse chat normale (question LLM).
4. Dicter « take me to » (sans X) → PAS d'erreur, chemin question normal,
   zéro annonce navigation.
5. Réglage guidage sur non + dicter « go to the Eiffel Tower » → voix « Page: the Eiffel Tower. » + fiche lieu ouverte, PAS de guidage.
6. Dicter « stop guidance » après un lancement → voix « Stopped »
   + retour accueil iSee.
7. Mode avion + dicter une navigation → overlay Erreur rouge affichant les
   deux messages ASR + lecture vocale ; aucune ouverture Maps.
8. S'écarter de l'itinéraire Maps → Maps recalcule seul ; vérifier qu'iSee
   n'affiche et ne dit rien.
9. Tap court sur un bouton (pas d'appui long) → action du bouton, jamais de
   dictée ; swipe → navigation de page, jamais de dictée (non-régression
   gestes).
10. Couper ElevenLabs (clé vide) + dicter une navigation → Maps s'ouvre quand
    même, overlay texte seul, zéro audio.

## 5. Catalogue de chaînes EN (convention : clés stables `nav_*`)

Statut `en place` = la clé existe dans `strings.xml` avec ce texte exact
(vérifié par la porte) ; `à venir` = texte réservé par ce document, à créer
avec le code qui l'utilise (X remplace `%1$s` dans ce tableau).

| Clé | Texte EN | Statut |
|---|---|---|
| `nav_confirm_destination` | « Going to X » | en place |
| `nav_guidance_unavailable` | « No guidance. » | en place |
| `nav_guidance_toggle_label` | « Guide to the confirmed destination » | en place |
| `nav_place_opened` | « Page: X. » | en place |
| `nav_browser_fallback` | « Route opened in the browser » | à venir |
| `nav_stop_done` | « Stopped » | en place |
| `nav_mic_busy_video` | « microphone already busy with video » | à venir |

Aide sous l'interrupteur (en dur dans le composable, comme les autres aides) :
« Off: iSee shows the place page instead of starting guidance. »
Motifs STOP déterministes (insensibles casse/accents) : « arrête / stop / cancel (le/la) guidage / guidance / navigation / route / GPS » (+ « turn / switch GPS off / on », « disable / deactivate / enable ») ; les 24 motifs GO restent valables.

## 6. Ancres code (ce que le MVP réutilise, sans le dupliquer)

| Fait publié | Ancre |
|---|---|
| 24 motifs GO déterministes (12 FR + 12 EN), destination brute | `core/src/main/java/dev/sam3vision/core/nav/NavigationIntent.kt::PATTERNS` — 24 motifs |
| Ordre sans destination comprise → redemande, jamais le LLM | `core/src/main/java/dev/sam3vision/core/voicenav/NavClarify.kt` |
| Normalisation de X dicté (pas de géocodage) | `core/src/main/java/dev/sam3vision/core/nav/DestinationQuery.kt` |
| URIs intent / universelle / fiche (pur JVM) | `core/src/main/java/dev/sam3vision/core/nav/MapsUri.kt` |
| Routage transcript → requête | `core/src/main/java/dev/sam3vision/core/usecase/NavigationRouter.kt` |
| Consommation avant le LLM (cap → navigation → traversée → LLM) | `app/src/main/java/dev/sam3vision/app/isee/vm/NavigationHook.kt` |
| Chaîne de replis (jamais d'exception) | `app/src/main/java/dev/sam3vision/app/navigation/NavigationLauncher.kt` |
| Phrases « Going to X » / indisponible | `app/src/main/java/dev/sam3vision/app/speech/NavigationAnnouncer.kt::NavigationPhrases` |
| Réglage guidage | `core/src/main/java/dev/sam3vision/core/settings/Settings.kt::navigationGuidanceEnabled` — défaut oui |
| Dictée : Meta puis repli Scribe, MicBus un seul AudioRecord | `core/src/main/java/dev/sam3vision/core/net/FallbackSpeechClient.kt` |
| Voix : ElevenLabs-only, silencieuse en échec | `app/src/main/java/dev/sam3vision/app/VoiceAnnouncer.kt` |

## 7. Chantiers liés, hors MVP (cadre, pas promesse)

### 7.1 Géocodage local (adresse dictée → coordonnées)

Constat du 28/10 : le Valhalla du r630 n'a **aucune** action de recherche
(`loki.actions` = locate, route, … — pas de `search`) ; le dépôt ne contient
aucun code de géocodage (ni `Geocoder`, ni Nominatim/Photon/Pelias) ; le
Redmi `2c6852ac` a les services Google (v26.x, localisation haute précision),
donc un backend `Geocoder` réseau. Recommandation retenue : primaire
`android.location.Geocoder.getFromLocationName` (API 33+, gardé par
`Geocoder.isPresent()`), repli BAN `api-adresse.data.gouv.fr` (GET sans clé,
champ `score` pour lever les ambiguïtés). L'extraction « je veux aller au X »
reste en amont (STT puis motifs §6). Monter un index Pelias/placeholder côté
Valhalla est écarté (lourd face aux deux options client).

### 7.2 Ingress `/route` Valhalla (téléphone → r630)

Constat du 28/10 : Valhalla écoute sur `0.0.0.0:8002` au r630 (joignable via
Tailscale `100.124.18.48`, baseline POST `/route` piéton Paris = HTTP 200 en
1,48 s) mais le port est injoignable depuis l'IP publique (Tailscale-only
confirmé). **Décision du 28/10 (ordre user, voir `docs/VOICE_NAV_PROXY.md`)** :
Tailscale Funnel est **abandonné** (jamais activé, v1.104.1) au profit du
**proxy CPU Modal déployé** (app `valhalla-idf-cpu`, `ROUTE_BASE_URL`) —
le téléphone appelle le proxy en HTTPS direct, sans install. Le script
`scripts/valhalla-funnel.sh` a été supprimé le 29/10. Écartés : tunnel
`cloudflared` nommé (repli si le proxy Modal vient à manquer). Côté client :
`ROUTE_BASE_URL` configurable, sur le motif existant
(`BuildConfig.SAM_BASE_URL` + réglage persisté).

## 8. Interdits

- Ne pas mettre « you have arrived » (iSee ne sait pas), ni « recalculating » ni « U-turn » (c'est Maps qui parle).
- Ne pas journaliser la destination au-delà du texte dicté (vie privée,
  `docs/NAVIGATION.md`).
- Ne pas tester depuis une branche en retard sur `master` ni un APK au SHA
  inconnu (règle ISEE-TEST-MASTER-SEUL).
- Ne pas louer une deuxième carte GPU pour tester (règle « une seule GPU » :
  `sam3-gpu status` d'abord, on attend la carte existante).
