# Guidage — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026.** Le guidage, c'est trois voix : le GPS (manœuvres
> 11 + rappel 3, synthèse réseau), le rail trottoir (10, clips), et le
> repli (12, clips). Les mots SAM/TwinLite (« gauche. », « Tout droit. »,
> « obstacle, arrêtez-vous. »), le contournement chiffré et le stop ont
> vécu : l'échelle 0 à 12 les remplace.
> Historique pré-v4 : `git show 4a3c2bcf:docs/GUIDAGE-TOUT-DROIT.md`.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** — consigne GPS quand il y
a un itinéraire (11), rappel de la consigne après 15 s de silence (3),
correction de dérive trottoir (10), et à défaut (rien à dire + silence
300 ms) un repli gauche/droite/tout droit selon GPS + obstacles (12) —
GPS en synthèse réseau, le reste en clips anglais, **jamais deux voix en
même temps** (bus vocal, le plus petit gagne).

## 2. GPS : manœuvres (11) et rappel (3)

- **Manœuvres et événements (11)** : le moteur Valhalla parle aux
  événements (préavis, ordre de virage, sortie de trace, recalcul,
  arrivée) par la voix réseau ElevenLabs. Une seule voix : GPS et
  obstacles partagent le même tuyau.
- **Rappel (3)** : si **silence depuis 15 s ou plus**, répète la **dernière
  consigne + la distance restante** (« … 50 meters remaining. », synthèse,
  anglais). Un événement moteur recale la cadence (le rappel se tait sur
  un fix qui a parlé). Sans consigne encore parlée : repli ligne droite
  (« Continue straight ahead. … remaining. »).
- Le 15 s est le **déclencheur**, pas une fenêtre d'antirépétition (clé
  `gps-reminder` : sans délai).
- Sans `routeBaseUrl` : pas de guidage (« Guidance unavailable. »), **pas
  de repli Google Maps** (supprimé).
- En traversée : GPS muet (ni 11, ni 3). La parole du user (1) coupe la
  voix route ; seul le niveau 0 parle par-dessus.

## 3. Rail trottoir (10, comportement actuel)

Le trottoir n'est pas un obstacle : c'est un **rail**. L'app ne parle que
si l'utilisateur n'est plus dessus (dérive, contournement) — trottoir
droit devant = aucun mot. Annonces au changement seul (« Sidewalk ahead. »,
« Sidewalk on the left. », « Sidewalk on the right. », « Sidewalk across
the street. »), silence 10 s (`sidewalk`). Coupé en traversée.

## 4. Repli (12) : gauche, droite ou tout droit

- **Déclencheur** : rien à dire (aucun niveau 0 à 11 ne parle) **et silence
  de 300 ms** sur le lecteur partagé.
- **Consigne** : « Left. » / « Right. » / « Straight. » (clips `guide_left`,
  `guide_right`, `guide_straight`) selon **GPS + obstacles** : cap GPS
  quand il y a un itinéraire, côté de sortie (le moins encombré) sinon.
- Une fois par période de silence (pas de boucle : le repli ne se
  re-déclenche que sur un nouveau silence après une parole).
- ⚠️ **Non implémenté** (constante `FALLBACK = 12` seule, aucune logique
  300 ms nulle part — voir `docs/REVUE-V4.md` R-12). À implémenter avant
  la première prise rue (item 11 de `docs/RECETTE-RUE.md`).

## 5. Ce que le guidage n'est plus (supprimé en v4)

- Mots SAM/TwinLite à chaque frame, arbitrage bouché/libre, « obstacle,
  arrêtez-vous. », contournement chiffré, stop : **supprimés** (échelle).
- Filtre tête des panneaux, décalage latéral chiffré, TwinLiteNet,
  urgence externe : **supprimés ou hors spec**.
- Voix FR de guidage : **supprimée** (clips anglais, GPS en anglais).

## 6. Câblage

| Élément | Fichier |
|---|---|
| Niveaux 0-12, décision frame, vœux | `app/.../isee/ProbePriority.kt` (`OutdoorLevel`, `decideFrame`, `offer`) |
| Bus vocal (plus petit gagne, écrasé jeté) | `app/.../isee/VoiceBus.kt` (`post`, `userSpeaking`, `chatSpeaking`) |
| Antirépétition (table centrale) | `core/.../live/AntiRepeat.kt` (`allow`, clés § 5 voix) |
| Clips anglais (catalogue + lecteur) | `core/.../speech/IseeClipsEn.kt`, `tools/isee-clips-en-catalog.json`, `app/.../isee/IseeClipPlayer.kt` |
| Voix de proximité (sans file) | `app/.../isee/ProximityVoice.kt` (`speak`, clips d'abord) |
| Moteur GPS + rappel 15 s | `app/.../voicenav/VoiceNavEngineDriver.kt`, `VoiceNavGpsVoice.kt` (`gpsReminderPhrase`) |
| Chat : attente + reprise | `app/.../isee/ChatWaitResume.kt` (rang 9) |
| Verdict traversée 100-102 | `core/.../crossing/CrossingVerdict.kt`, `app/.../isee/CrossingVerdictWiring.kt` |
| Routeur dictée (10 ordres) | `core/.../speech/DictationOrder.kt`, `app/.../isee/vm/IseeDictationRouter.kt` |
| Listes SAM 16/10 | `core/.../live/LivePrompts.kt` (`default`, `indoor`, `forMode`) |

Contradictions résiduelles entre ces fichiers : `docs/REVUE-V4.md`.
