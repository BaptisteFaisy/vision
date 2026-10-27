# Guidage « tout droit » — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-27 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé) est dans
`docs/ELEVENLABS-TTS.md` ; l'annonce des positions (l'autre voix de l'app) dans
`docs/VOIX-POSITIONS.md`. Ici : **la consigne de marche** — ce que la page caméra iSee
dit à quelqu'un qui avance.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** (« Continue tout droit. », « Personne à
2 mètres à midi, va à gauche. »), calculé **sur le téléphone depuis les seuls objets
SAM** — **aucun appel LLM**, la phrase part directement à ElevenLabs.

## 2. Le couloir et la portée

- **Couloir** : la bande centrale, `centerX` entre 0,34 et 0,66
  (`PositionText.SIDE_EDGE`, `core/.../live/Guidance.kt`, `inCorridor`) — les seuils
  sont partagés avec les positions, mais le parlé est au cadran : un obstacle du
  couloir se dit « à 11 heures », « à midi » ou « à 1 heure ».
- **Portée** : `RANGE_METERS = 4f` (`Guidance.kt`) — au-delà, un objet au centre est
  trop loin pour un pas et ne bloque pas.
- **Distance inconnue = prudence** : un objet du couloir sans distance mesurée
  (classe inconnue, boîte coupée) bloque quand même, et la consigne ne porte pas de
  chiffre (`inRange`, `nearRank`, `Guidance.kt`).
- **Côté de sortie** : le moins encombré — moins d'objets à portée, sinon obstacle
  connu le plus loin, sinon l'opposé du décalage de l'obstacle dans le couloir
  (pilier légèrement à gauche ⇒ on passe à droite), pile au centre ⇒ à gauche
  (`freerSide`, `Guidance.kt`).

## 3. Ce que la consigne dit

| Situation | fr | en |
|---|---|---|
| Voie libre | Continue tout droit. | Continue straight ahead. |
| Obstacle mesuré | Personne à 2 mètres à midi, va à gauche. | Person 2 meters at 12 o'clock, go left. |
| Obstacle non mesuré | Personne à midi, va à gauche. | Person at 12 o'clock, go left. |

- Forme produite par `Guidance.phrase` (`Guidance.kt`) : sans article, comme les
  positions — donc aucune faute de genre sur des labels libres. La direction de
  l'obstacle est le cadran (`PositionText.clockLabel`), pas « devant ».
- La distance est `DistanceText.spoken` dans la langue de la voix, comme les positions.
- Les labels SAM ne sont pas traduits (concepts anglais `person`, `door`) : la phrase
  française dit « person à 2 mètres à midi » quand SAM dit `person` — même limite que
  les positions (`docs/VOIX-ANGLAIS.md`), non corrigée ici.

## 4. Quand elle parle : stabilité, nouveauté, danger prioritaire

`core/.../live/GuidanceAnnouncer.kt` — machine à états **pure** (l'horodatage est fourni) :

1. **stabilité** — la même décision doit être vue **2 frames de suite**
   (`DEFAULT_MIN_FRAMES = 2`) : un masque qui clignote ne fait pas changer de trottoir ;
2. **nouveauté** — une décision déjà dite reste muette. La signature est `free`, ou
   `b|label|côté|mètre arrondi|heure` (`Guidance.key`) : un obstacle qui s'approche
   (3 m → 2 m → 1 m) ou qui glisse dans le couloir (11 h → midi) reparle, comme
   les positions ;
3. **délai asymétrique** — jamais deux consignes à moins de **4 s**
   (`DEFAULT_COOLDOWN_MS = 4_000L`), **sauf passage au bouché** : un obstacle qui entre
   dans le couloir parle aussitôt, sans attendre le délai. Le lecteur coupe la phrase
   en cours (`SpeechPlayer` : une seule lecture à la fois), donc le danger coupe la
   réassurance — c'est voulu. Le retour au libre, lui, attend : la réassurance peut
   patienter (`GuidanceAnnouncer.kt:40`, `urgent = decision.blocked`).

Différence assumée avec les positions : **la voie libre se dit** (« Continue tout
droit. », une fois par période libre) là où une scène vide reste muette
(`AnnouncementGate.clearScene`, via `PositionAnnouncer`). Le guidage confirme qu'il
veille ; les positions décrivent ce qu'il y a.

## 5. Câblage : une seule voix, le guidage prime

| Élément | Fichier |
|---|---|
| Décision, phrase, signature | `core/.../live/Guidance.kt` |
| Machine 2 frames / 4 s / danger prioritaire | `core/.../live/GuidanceAnnouncer.kt` |
| Sonde iSee : `guide` prime sur `announce` | `app/.../isee/LiveProbe.kt` (`phraseOf`), `ProbeWiring.kt` (`configureFrom`) |
| Voix (inchangée) | `app/.../isee/ProximityVoice.kt` → ElevenLabs, jamais par-dessus le chat |
| Réglage | `Settings.ttsGuidanceStraight` (défaut `true`), effectif `guideStraight = bascule && ttsConfigured` (`Settings.kt`), bascule « Guider tout droit (caméra iSee) » (`app/TtsSettings.kt`) |

Le Direct et l'analyse photo gardent l'annonce des positions : le guidage ne concerne
que la page caméra iSee, la seule où l'utilisateur marche. Couper la bascule rend la
page caméra à l'annonce des positions (si elle est active) ; sans clé ElevenLabs, le
guidage est muet — par construction, pas par bug.

Aucun import réseau dans `Guidance.kt` ni `GuidanceAnnouncer.kt` : le guidage ne peut
pas appeler le LLM, même par accident.

Tests JVM qui verrouillent le contrat : `GuidanceTest` (11), `GuidanceAnnouncerTest`
(7), `SettingsGuidanceTest` (4), `LiveProbeGuidanceTest` (3, sonde branchée sur un faux
client SAM : voie libre → consigne, obstacle → contournement, positions seules →
silence sur voie libre).

## 6. Recette

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet) et bascule
   « Guider tout droit (caméra iSee) » **activée** ;
2. page caméra, champ vide → **une** fois « Continue tout droit. » (ou « Continue
   straight ahead. » selon la langue de la voix), puis silence ;
3. une personne entre au centre à ~2 m → « Personne à 2 mètres à midi, va à gauche. »
   (ou à droite si la gauche est encombrée), **sans attendre 4 s** ;
4. la personne s'approche à ~1 m → nouvelle consigne avec la distance à jour ;
5. le couloir se dégage → « Continue tout droit. » après le délai ;
6. bascule **coupée** → retour aux annonces de positions, voie libre muette.

## 7. État des mesures (27/10/2026)

Comportement **lu dans le code et verrouillé par les tests JVM** (`:core` 1098 tests,
`:app` 551 tests, 0 échec le 27/10 ; APK debug construit) : **non mesuré** sur
appareil — exige un téléphone, un GPU SAM joignable et une clé ElevenLabs.
