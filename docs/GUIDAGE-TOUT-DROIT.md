# Guidage « tout droit » — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-29 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé) est dans
`docs/ELEVENLABS-TTS.md` ; l'annonce des positions (l'autre voix de l'app) dans
`docs/VOIX-POSITIONS.md`. Ici : **la consigne de marche** — ce que la page caméra iSee
dit à quelqu'un qui avance.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** (« Continue. », « Va à gauche,
2 mètres. »), calculé **sur le téléphone depuis les seuls objets
SAM** — **aucun appel LLM**, la phrase part directement à ElevenLabs. Un
obstacle, même au contact, se contourne : il n'y a **jamais d'ordre d'arrêt**.

## 2. Le couloir et la portée

- **Couloir** : la bande centrale, `centerX` entre 0,34 et 0,66
  (`PositionText.SIDE_EDGE`, `core/.../live/Guidance.kt`, `inCorridor`) — les seuils
  sont partagés avec les positions, mais la consigne ne dit ni cadran ni label :
  côté + distance suffisent (`key` garde l'heure pour reparler quand l'obstacle
  glisse dans le couloir).
- **Portée** : `RANGE_METERS = 4f` (`Guidance.kt`) — au-delà, un objet au centre est
  trop loin pour un pas et ne bloque pas.
- **Distance inconnue = prudence** : un objet du couloir sans distance mesurée
  (classe inconnue, boîte coupée) bloque quand même, et la consigne ne porte pas de
  chiffre (`inRange`, `nearRank`, `Guidance.kt`).
- **Pas d'ordre d'arrêt** (ordre user du 29/10, remplace le « Stop. ») : un
  obstacle mesuré à moins d'un mètre se contourne comme les autres, en
  centimètres (`Va à gauche, 40 centimètres.`, `DistanceText.spokenShort`).
  Sans mesure (boîte coupée au contact), la consigne ne porte pas de chiffre
  (`Va à gauche.`). Le contournement est urgent : il coupe la phrase en cours,
  chat compris (`LiveProbeState.spokenUrgent`).
- **Côté de sortie** : le moins encombré — moins d'objets à portée, sinon obstacle
  connu le plus loin, sinon l'opposé du décalage de l'obstacle dans le couloir
  (pilier légèrement à gauche ⇒ on passe à droite), pile au centre ⇒ à gauche
  (`freerSide`, `Guidance.kt`).

## 3. Ce que la consigne dit

| Situation | fr | en |
|---|---|---|
| Voie libre | Continue. | Continue. |
| Obstacle mesuré | Va à gauche, 2 mètres. | Go left, 2 meters. |
| Obstacle à moins d'un mètre | Va à gauche, 40 centimètres. | Go left, 40 centimeters. |
| Obstacle non mesuré | Va à gauche. | Go left. |

- Vocabulaire minimal (décisions user des 28-29/10, `Guidance.phrase`) : côté +
  distance courte de l'obstacle (`DistanceText.spokenShort` : mètre entier,
  centimètres sous un mètre), ni nom d'obstacle, ni cadran, ni décalage latéral.
  Remplace la consigne chiffrée « décalez-vous » du 28/10 puis le « Stop. »
  du 29/10 (voir `docs/VOIX-DANGER.md` § 3, § 5 et § 9).
- Les labels SAM n'étant plus dits, leur non-traduction ne touche plus le guidage
  (elle reste vraie des positions, `docs/VOIX-ANGLAIS.md`).

## 4. Quand elle parle : stabilité, nouveauté, danger prioritaire

`core/.../live/GuidanceAnnouncer.kt` — machine à états **pure** (l'horodatage est fourni) :

1. **stabilité** — une voie libre doit être vue **2 frames de suite**
   (`DEFAULT_MIN_FRAMES = 2`) ; le bouché parle **dès la 1re frame** (`firstSight`) :
   à 2 ips, attendre la deuxième, c'est une demi-seconde de marche en plus, et un
   obstacle qui s'approche change de clé à chaque frame donc ne serait jamais stable ;
2. **nouveauté** — une décision déjà dite reste muette. La signature est `free`, ou
   `b|label|côté|mètre arrondi|heure` (`Guidance.key`) : un obstacle qui
   s'approche (3 m → 2 m → 1 m) ou qui glisse dans le couloir (11 h → midi) reparle,
   comme les positions ; dans le même seau, pas de re-annonce ;
3. **délai asymétrique** — jamais deux consignes à moins de **4 s**
   (`DEFAULT_COOLDOWN_MS = 4_000L`), **sauf passage au bouché** : un obstacle qui entre
   dans le couloir parle aussitôt, sans attendre le délai. Le lecteur coupe la phrase
   en cours (`SpeechPlayer` : une seule lecture à la fois), donc le danger coupe la
   réassurance — c'est voulu. Le retour au libre, lui, attend : la réassurance peut
   patienter. Côté voix, l'urgence vient de l'état (`LiveProbeState.spokenUrgent`,
   vrai quand le couloir est bouché) : le contournement coupe la lecture en cours,
   chat compris (`ProbeWiring.observeSpoken`).

Différence assumée avec les positions : **la voie libre se dit** (« Continue. »,
une fois par période libre) là où une scène vide reste muette
(`AnnouncementGate.clearScene`, via `PositionAnnouncer`). Le guidage confirme qu'il
veille ; les positions décrivent ce qu'il y a.

## 5. Câblage : une seule voix, le guidage prime

| Élément | Fichier |
|---|---|
| Décision, phrase, signature | `core/.../live/Guidance.kt` |
| Machine 1re vue danger / 4 s / danger prioritaire | `core/.../live/GuidanceAnnouncer.kt` |
| Sonde iSee : `guide` prime sur `announce` | `app/.../isee/LiveProbe.kt` (`phraseOf`), `ProbeWiring.kt` (`configureFrom`) |
| Voix (inchangée) | `app/.../isee/ProximityVoice.kt` → ElevenLabs, jamais par-dessus le chat |
| Réglage | `Settings.ttsGuidanceStraight` (défaut `true`), effectif `guideStraight = bascule && ttsConfigured` (`Settings.kt`), bascule « Guider tout droit (caméra iSee) » (`app/TtsSettings.kt`) |

Le Direct et l'analyse photo gardent l'annonce des positions : le guidage ne concerne
que la page caméra iSee, la seule où l'utilisateur marche. Couper la bascule rend la
page caméra à l'annonce des positions (si elle est active) ; sans clé ElevenLabs, le
guidage est muet — par construction, pas par bug.

Aucun import réseau dans `Guidance.kt` ni `GuidanceAnnouncer.kt` : le guidage ne peut
pas appeler le LLM, même par accident.

Tests JVM qui verrouillent le contrat : `GuidanceTest` (18), `GuidanceAnnouncerTest`
(12), `SettingsGuidanceTest` (4), `LiveProbeGuidanceTest` (4, sonde branchée sur un faux
client SAM : voie libre → consigne, obstacle → contournement, urgence du
contournement seul, positions seules → silence sur voie libre), `ProbeWiringSpokenTest`
(3 : même phrase ré-émise → une synthèse qui aboutit, phrase fraîche → dernier
gagnant, contournement → parlé chat en lecture).

## 6. Recette

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet) et bascule
   « Guider tout droit (caméra iSee) » **activée** ;
2. page caméra, champ vide → **une** fois « Continue. », puis silence ;
3. une personne entre au centre à ~2 m → « Va à gauche, 2 mètres. »
   (ou à droite si la gauche est encombrée), **sans attendre 4 s** ;
4. la personne s'approche à ~1 m → nouvelle consigne avec la distance à jour ;
5. la personne est à ~0,5 m → « Va à gauche, 50 centimètres. », qui coupe la
   phrase en cours, chat compris ; main devant l'objectif (boîte coupée, non
   mesurée) → « Va à gauche. » (non chiffré) ;
6. le couloir se dégage → « Continue. » après le délai ;
7. bascule **coupée** → retour aux annonces de positions, voie libre muette.

## 7. État des mesures (28/10/2026, appareil Redmi 2312DRA50G)

Relevé du 28/10/2026 sur le build `1.0.179-399a512` (= `master` HEAD du moment,
contenant le guidage) : Redmi 2312DRA50G, Android 15, écran 1220 × 2712, Clé
ElevenLabs + voix renseignées, bascule « Guider tout droit (caméra iSee) »
**activée** (captures d'écran des Réglages à l'appui).

- Lancement à froid → tap central → enregistrement : `iSee` au premier plan,
  aucune erreur fatale, aucun `live KO`.
- SAM en direct : `première analyse : 3 objets, 671 ms`, puis `masques décodés`
  en continu (2–17 ms) ; panneau debug `SAM · 7,1 ips · 138 ms · 3 objets`
  avec pastilles de distance (capture à l'appui).
- Consigne parlée : `cache/isee-play/voice.mp3`, **41 838 octets**, écrit pendant
  l'enregistrement — donc synthétisé par ElevenLabs puis joué (le lecteur est
  unique et l'intro passe par `playFile`, sans écrire ce fichier ; le guidage
  prime sur les positions quand sa bascule est active, donc ce fichier est une
  consigne de marche).
- **Non mesuré** : le texte exact de la consigne prononcée (inaudible à distance ;
  le libellé reste verrouillé par les tests JVM) et le comportement en marche
  réelle (couloir qui se dégage, obstacle qui s'approche).
