# Navigation vocale mains libres — spécification fonctionnelle

Référence fonctionnelle (français) du chantier « navigation vocale » d'iSee :
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
   overlay « J'écoute / Parle, puis relâche pour envoyer. » (motif
   `docs/TEST-LONGPRESS-DICTEE.md`, inchangé).
2. **Dictée** : relâchement → transcription ASR (Meta puis repli ElevenLabs
   Scribe) → overlay « Transcription / Un instant… », puis texte transcrit
   affiché + relu.
3. **Extraction** : `NavigationHook` analyse le texte **avant** le LLM
   (insensible casse/accents), avec les 9 motifs GO du §6 : « je veux /
   voudrais / vais aller … X », « j'aimerais aller … X », « emmène/amène-moi
   … X », « conduis-moi … X », « guide-moi jusqu'à/au/aux / vers X »,
   « naviguer vers X », « itinéraire vers X », « aller … X », « va … X » —
   « … » vaut à, au, aux, en, dans, vers, chez, jusqu'à. X = reste de la
   phrase, transmis tel quel à Maps, aucun géocodage local. Un ordre sans
   destination comprise (« je veux aller », « emmène-moi »…) ne part pas
   au LLM : iSee redemande la destination (`NavClarify`).
4. **Confirmation (implicite, one-shot)** : pas de question oui/non (non-but
   MVP : garder le geste unique mains libres). La confirmation = relecture
   vocale immédiate « Navigation vers X » + overlay affichant la destination
   (34 sp). Si la destination entendue est fausse, l'utilisateur dit « arrête
   le guidage » (ordre STOP, consommé avant le LLM) puis redicte.
5. **Guidage** : handoff système — intent `google.navigation:q=X&mode=w`
   (piéton par défaut) ciblé sur `com.google.android.apps.maps`. Maps guide
   (virages, arrivée). iSee ne suit rien, ne reprend rien si Maps est tué.
6. **Retour / arrivée** : l'arrivée est annoncée par Maps, pas par iSee (ni
   GPS ni suivi). Retour système → iSee affiche son écran courant ; « c'est
   arrivé ? » reste une question LLM, pas un état.
7. **Réglage** : interrupteur existant « Guider vers la destination
   confirmée » (défaut oui) ; à non → fiche lieu (`geo:` / URL search) sans
   guidage.

## 1 bis. Pas de carte sans destination (retiré le 29/10/2026)

Un « mode navigation » sans destination a existé (carte + position GPS à
l'écran, ordres « navigation », « carte », « où suis-je ») : il affichait la
carte **sans destination rentrée**, contrairement à l'ordre du 29/10. Retiré :
la carte OSM n'apparaît que pendant le guidage, avec un tracé
(`docs/CARTE-OSM.md`). « navigation », « carte », « où suis-je » ne sont plus
des ordres de mode : sans destination reconnue, ils suivent le chemin normal
(question → LLM).

## 2. Politique d'annonces vocales (phrase exacte par événement)

| Événement | Phrase exacte |
|---|---|
| Écoute | « J'écoute. Parle, puis relâche pour envoyer. » |
| Transcription en cours | « Transcription. Un instant… » |
| Destination comprise + guidage lancé | « Navigation vers X » |
| Repli navigateur (Maps absent) | « Navigation vers X » (même phrase ; l'overlay dit « Itinéraire ouvert dans le navigateur ») |
| Guidage impossible (aucune app capable) | « Je ne peux pas ouvrir le guidage » |
| Réglage guidage éteint | « Voici la fiche de X » |
| Ordre STOP (« arrête / stop le guidage », « annule la navigation ») | « Guidage arrêté » |

Règles : une seule voix à la fois (la phrase en cours est coupée si un
nouvel ordre arrive) ; erreurs toujours vocales + overlay, jamais overlay
seul ; langue des phrases = langue de la voix (`SpeechLanguage`), pas de
l'interface ; sans clé TTS configurée : aucune annonce, overlays texte seuls
— le lancement Maps reste possible.

Note STOP : Maps n'est pas pilotable par iSee ; « Guidage arrêté » confirme
l'oubli de destination + le retour à l'accueil iSee (à dire une fois dans
l'aide vocale).

## 3. Cas limites — UX exacte de chacun

1. **Aucune destination** : un ordre sans X (« va à… » vide, « je veux
   aller ») redemande la destination (« Je n'ai pas compris où tu veux
   aller… », `NavClarify`), jamais le LLM ; un bruit sans tête d'ordre
   suit le chemin question normal (Room → LLM), zéro annonce navigation.
2. **Phrase ambiguë** (« où est X », « c'est loin X ? ») : reste une question
   LLM, jamais un ordre ; aucune annonce navigation.
3. **Hors-trace** (l'utilisateur s'écarte) : RIEN côté iSee — Maps recalcule
   et annonce seul ; iSee ne surveille pas la position (pas de permission
   localisation, par design). Ne jamais afficher « vous êtes écarté ».
4. **GPS perdu** : RIEN côté iSee — Maps affiche/parle son propre message ;
   iSee ne détecte pas l'état GPS.
5. **Backend ASR injoignable** (Meta KO) : repli automatique Scribe ; si les
   deux échouent : overlay Erreur rouge « Erreur. \<msg Meta\>. \<msg Scribe\>. »
   + lecture vocale des deux messages ; destination jamais inventée.
6. **TTS / ElevenLabs KO au moment de confirmer** : le lancement Maps a quand
   même lieu (le guidage ne dépend pas de la voix) ; overlay « Navigation
   vers X » seul, sans audio ; erreur TTS journalisée, pas parlée (on ne
   parle pas d'une panne de voix à la voix).
7. **Maps absent** : repli URL universelle navigateur (overlay « Itinéraire
   ouvert dans le navigateur ») ; si aucun navigateur : « Je ne peux pas
   ouvrir le guidage » + `Result.failure`, jamais de crash.
8. **Micro occupé** (vidéo tap en cours) : refus en amont « Micro déjà occupé
   par la vidéo » (libellé MicBus), pas d'écoute.
9. **Micro verrouillé** (écran verrouillé) : hors scope MVP — `LockedMicAnswer`
   ne route pas encore vers la navigation (limite documentée
   `docs/NAVIGATION.md`) ; comportement verrouillé actuel, sans annonce.

## 4. Recette téléphone (chaque point : SHA du build, heure, observable)

Préalable : build depuis le HEAD de `origin/master` (`git ls-remote origin
master` comparé au SHA du build — règle ISEE-TEST-MASTER-SEUL), APK installé
sur le Redmi `2c6852ac`, clé ElevenLabs + voix renseignées, bascule « Guider
vers la destination confirmée » sur oui, annonce prise/libération du
téléphone dans `BOITE-USER.md`.

1. Appui long 1 s au centre → overlay « J'écoute / Parle, puis relâche pour
   envoyer. » + annonce vocale ; relâcher en silence → overlay Transcription
   puis retour chat sans annonce navigation.
2. Dicter « je veux aller à la gare de Lyon » → overlay « Navigation vers la
   gare de Lyon » + voix « Navigation vers la gare de Lyon » + bascule vers
   Google Maps en guidage piéton vers cette destination.
3. Dicter « où est la pharmacie la plus proche » → PAS de bascule Maps ;
   réponse chat normale (question LLM).
4. Dicter « emmène-moi à » (sans X) → PAS d'erreur, chemin question normal,
   zéro annonce navigation.
5. Réglage guidage sur non + dicter « va à la tour Eiffel » → voix « Voici la
   fiche de la tour Eiffel » + fiche lieu ouverte, PAS de guidage.
6. Dicter « arrête le guidage » après un lancement → voix « Guidage arrêté »
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

## 5. Catalogue de chaînes FR (convention : clés stables `nav_*`)

Statut `en place` = la clé existe dans `strings.xml` avec ce texte exact
(vérifié par la porte) ; `à venir` = texte réservé par ce document, à créer
avec le code qui l'utilise (X remplace `%1$s` dans ce tableau).

| Clé | Texte FR | Statut |
|---|---|---|
| `nav_confirm_destination` | « Navigation vers X » | en place |
| `nav_guidance_unavailable` | « Je ne peux pas ouvrir le guidage » | en place |
| `nav_guidance_toggle_label` | « Guider vers la destination confirmée » | en place |
| `nav_place_opened` | « Voici la fiche de X » | à venir |
| `nav_browser_fallback` | « Itinéraire ouvert dans le navigateur » | à venir |
| `nav_stop_done` | « Guidage arrêté » | à venir |
| `nav_mic_busy_video` | « Micro déjà occupé par la vidéo » | à venir |

Aide sous l'interrupteur (en dur dans le composable, comme les autres aides) :
« Éteint : iSee montre la fiche du lieu au lieu de lancer le guidage. »
Motifs STOP déterministes (insensibles casse/accents) : « arrête / stop /
annule (le/la) guidage / navigation » ; les 9 motifs GO restent inchangés.

## 6. Ancres code (ce que le MVP réutilise, sans le dupliquer)

| Fait publié | Ancre |
|---|---|
| 9 motifs GO déterministes, destination brute | `core/src/main/java/dev/sam3vision/core/nav/NavigationIntent.kt::PATTERNS` — 9 motifs |
| Ordre sans destination comprise → redemande, jamais le LLM | `core/src/main/java/dev/sam3vision/core/voicenav/NavClarify.kt` |
| Normalisation de X dicté (pas de géocodage) | `core/src/main/java/dev/sam3vision/core/nav/DestinationQuery.kt` |
| URIs intent / universelle / fiche (pur JVM) | `core/src/main/java/dev/sam3vision/core/nav/MapsUri.kt` |
| Routage transcript → requête | `core/src/main/java/dev/sam3vision/core/usecase/NavigationRouter.kt` |
| Consommation avant le LLM (cap → navigation → traversée → LLM) | `app/src/main/java/dev/sam3vision/app/isee/vm/NavigationHook.kt` |
| Chaîne de replis (jamais d'exception) | `app/src/main/java/dev/sam3vision/app/navigation/NavigationLauncher.kt` |
| Phrases « Navigation vers X » / indisponible | `app/src/main/java/dev/sam3vision/app/speech/NavigationAnnouncer.kt::NavigationPhrases` |
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

- Ne pas mettre « vous êtes arrivé » (iSee ne sait pas), ni « recalcul » ni
  « demi-tour » (c'est Maps qui parle).
- Ne pas journaliser la destination au-delà du texte dicté (vie privée,
  `docs/NAVIGATION.md`).
- Ne pas tester depuis une branche en retard sur `master` ni un APK au SHA
  inconnu (règle ISEE-TEST-MASTER-SEUL).
- Ne pas louer une deuxième carte GPU pour tester (règle « une seule GPU » :
  `sam3-gpu status` d'abord, on attend la carte existante).
