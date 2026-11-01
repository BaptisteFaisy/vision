# Voix iSee — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026.** Ce document est le contrat vocal actuel : échelle
> dehors 0 à 12, traversée exclusive 100 à 102, chaîne dedans, écrasement,
> table d'antirépétition, clips anglais, ordres dictée, listes de détection.
> Tout ce qui contredit ce contrat dans un autre doc est périmé.
> Historique pré-v4 (§ 1 à 15, guidage SAM/TwinLite, « Attention … » FR,
> stop, TwinLiteNet), puis ordres du 01/11 : `git show 0911017f:docs/VOIX-DANGER.md`.

## 0. Règles globales

- **Clips anglais uniquement.** Toute phrase fixe part en clip pré-enregistré
  (catalogue `tools/isee-clips-en-catalog.json`, miroir
  `core/.../speech/IseeClipsEn.kt`, garde `tools/check-isee-clips-en.py`).
  **Zéro appel réseau au moment de parler, sauf le chat et le GPS**
  (synthèse ElevenLabs, vocabulaire infini).
- **Hors-ligne supprimé** : sans clé ElevenLabs, le chat et le GPS sont
  muets — pas de TTS système en repli.
- **Google Maps supprimé** : ni handoff, ni repli navigateur, ni fiche lieu.
  Le GPS, c'est le guidage in-app (Valhalla) ou rien.
- **Supprimés de la détection** : bus, vélo, camion, moto, clés dehors,
  borne, arbre, portail, bateau. Ils ne sont ni suivis, ni reconnus, ni dits.

## 1. Dehors : échelle 0 à 12

Le plus petit nombre gagne (voir § 4). Phrases en anglais (clips), sauf
chat (9) et GPS (3, 11) en synthèse réseau.

| Niveau | Quoi | Règle | Clip / voix |
|---|---|---|---|
| 0 | Voitures en mouvement < 20 m | Présence seule, **sans cadran ni distance** ; pluriel si plusieurs | `car` « Car. », `cars` « Cars. » |
| 1 | Parole du user | Tait tout ; **seul 0 la coupe** | — (micro) |
| 2 | Humains ≤ 15 m droit devant (plein axe ≤ 2 m) | **Mot d'évitement en continu** « Droite. »/« Gauche. », répété toutes les 300 ms (§ 36) | `guide_left`, `guide_right` |
| 3 | Rappel GPS | Si silence depuis 15 s ou plus : répète la dernière consigne + distance restante | Synthèse réseau (GPS) |
| 4 | Grillages + murs + barrières < 6 m | — | `fence`, `wall`, `barrier` |
| 5 | Bordures < 6 m | Montée / descente | `curb_up`, `curb_down` |
| 6 | Escaliers | **Jalons 4 / 2 / 1 m** + **nombre de marches dit** | `stairs_up/down_4/2/1`, `n_1..12` + `stairs_steps` |
| 7 | Poteau ≤ 15 m droit devant : **mot continu** (§ 36) ; mobilier vertical + divers < 6 m : doux confirmé | `guide_left`, `guide_right` (poteau) ; `pole`, `lamp`, `sign`, `animal`, `bin`, `keepsake` (divers) |
| 8 | Passages piétons < 6 m | Annonce **1 fois par vue** + alignement **au changement** + arrivée **1 fois** | `cross_approach_*`, `crosswalk_aligned`, `cross_arrived` |
| 9 | Réponses du chat | **Attente + reprise depuis la coupure** (tranche interrompue rejouée) ; **seule file restante** | Synthèse réseau (chat) |
| 10 | Trottoirs | Comportement actuel (rail : ne parle que si dérive) | `sidewalk_*` (existants) |
| 11 | GPS manœuvres et événements | Aux événements moteur | Synthèse réseau (GPS) |
| 12 | Repli | **Rien à dire + silence 300 ms** : gauche, droite ou tout droit selon GPS + obstacles | `guide_left`, `guide_right`, `guide_straight` |

## 2. Traversée exclusive : 100 à 102

Détail : `docs/TRAVERSEE.md`.

- **100 verdict** : feu piéton puis voitures — **rouge ou mouvement =
  « Don't cross. », sinon « Cross. »**. Véhicules = **voiture uniquement**
  (bus, vélo, camion, moto ne tranchent jamais).
- **101 humains** du passage, après le verdict.
- **102 bordures** du passage, après les humains.
- **Dictée active** (les 10 ordres restent écoutés, `crossing off` compris).
- **Chat et GPS muets** tant que le mode est actif (le chat reprend à la
  sortie, jamais perdu).

## 3. Dedans : objet cherché d'abord

Chaîne (la plus prioritaire gagne, même règle d'écrasement qu'au § 4) :

1. **Objet cherché** — les 10, dans cet ordre : clés, boîte de médicaments,
   lunettes, boîtier de lunettes, téléphone, chaussures, porte, murs,
   humain, chaussettes. Résultats **en clips** (« Looking for » + objet,
   « Found. » + cadran).
2. **Parole du user** (seul l'objet cherché la coupe, cran 0).
3. **Humain** (même cran que dehors : 2).
4. **Chat** (même cran que dehors : 9, reprise depuis la coupure).
5. **Porte et murs** (cran 13, le plus bas de la chaîne dedans).

## 4. Écrasement

- **Le plus petit nombre gagne.** Au repos, tout niveau parle ; pendant une
  parole, seul un niveau plus petit (ou égal — même source re-détectée,
  dernier gagnant) coupe et parle.
- **L'écrasé n'est jamais redit**, jamais mis en file — sauf **re-détecté
  actuel** (nouvelle détection = nouvelle annonce, pas une redite).
- **Chat (9) : reprise au lieu de perte** — une réponse coupée attend et
  reprend depuis la coupure ; une nouvelle réponse écrase l'attente
  (overwrite, jamais deux files).

## 5. Antirépétition

Une seule table, clés stables par niveau et objet, durées de la spec.
Contrat : `object AntiRepeat` + `fun allow(key: String, nowMs: Long):
Boolean` (vrai = parler, et mémoriser). L'antirépétition vit **en amont**,
côté producteurs, **pour une annonce vraiment parlée** (après écrasement :
une annonce avalée ne consomme pas son tour).

| Clé | Niveau | Durée |
|---|---|---|
| `car-moving` | 0 voitures | 4 s |
| `human-soft` | 2 humains | 2 s |
| `human-imminent` | 2 mot humain continu 0-15 m | **immédiat** (toujours autorisé) |
| `pole-avoid` | 7 mot poteau continu 0-15 m | **immédiat** (toujours autorisé, § 36) |
| `gps-reminder` | 3 rappel | sans antirépétition (le 15 s est le déclencheur, pas une fenêtre) |
| `fence` | 4 grillages/murs/barrières | 4 s |
| `curb` | 5 bordures | 2 s |
| `stairs` | 6 escaliers | 2 s |
| `pole` | 7 poteaux + mobilier vertical | 2 s |
| `misc` | 7 divers (animal, poubelle, souvenirs) | 2 s |
| `crosswalk-announce` | 8 annonce | **1 fois par vue** (oubli explicite à la vue suivante) |
| `crosswalk-align` | 8 alignement | **au changement** (l'appelant n'appelle que si ça change) |
| `crosswalk-arrival` | 8 arrivée | **1 fois** |
| `sidewalk` | 10 trottoirs | 10 s |
| `verdict` / `verdict:état` | 100 verdict | **3 s, changement immédiat** (clé paramétrée : un état nouveau parle aussitôt) |
| (aucune) | 9 chat | **aucune** (attente + reprise, pas de délai) |
| (aux événements) | 11 GPS | aux événements moteur, pas de délai |

Clés inconnues : toujours autorisées (jamais bloquantes — une faute de
frappe ne fait jamais taire une annonce).

## 6. Ordres dictée : les 10, préfixés par « mode »

- `mode outdoor`, `mode indoor`, `mode crossing on`, `mode crossing off`,
  `mode find my X`, `mode find off`, `mode GPS I want to go to …`,
  `mode GPS off`, `mode glasses on`, `mode glasses off`.
- **Tout le reste = question LLM.** Un « mode … » qui n'est aucun des 10
  part au LLM (jamais un ordre deviné) ; les ordres fixes n'acceptent aucun
  mot supplémentaire ; les ordres à libellé exigent un libellé non vide.
- Les 10 ordres sont écoutés **partout, traversée comprise**.
- Confirmations en clips anglais (`outdoor_on`, `indoor_on`, `cross_on`,
  `cross_off`, `find_looking_for` + objet, `find_stopped`, `gps_started`,
  `nav_stopped`, `glasses_on`, `glasses_off`) — jamais en synthèse.

## 7. Listes de détection (SAM 3.1, anglais)

- **Dehors (16)** : `person`, `car`, `pedestrian traffic light`, `curb`,
  `sidewalk`, `crosswalk`, `pole`, `stairs`, `fence`, `wall`, `barrier`,
  `sign`, `street lamp`, `animal`, `trash can`, `souvenir shop`.
- **Dedans (10)** : `keys`, `medicine box`, `glasses`, `glasses case`,
  `phone`, `shoes`, `door`, `wall`, `person`, `socks`.
- `Find my X` ajoute le concept (le dernier cède son slot au plafond).
  Liste unique normal/traversée : la bascule ne change plus les prompts.

## 8. Contrats partagés (l'intégration suppose ces signatures)

- `VoiceBus` : `fun VoiceBus.post(level: Int, text: String, clipId: String?
  = null)`. Niveaux = entiers de la spec (0 à 12 dehors, 100 à 102
  traversée, 13 porte/murs dedans). Le bus arbitre l'instant (plus petit
  gagne, écrasé jeté) ; `userSpeaking` (niveau 1, seul 0 passe) et
  `chatSpeaking` (niveau 9 tenu pendant la lecture) portent les états.
- `AntiRepeat` : `object AntiRepeat` avec `fun allow(key: String, nowMs:
  Long): Boolean`, clés du § 5, durées du § 5. `reset()` en début de
  séance ; `forget(key)` pour le « 1 fois par vue » et le « re-détecté
  actuel ».
- Chaque guetteur pose `level` + `antiRepeatKey` sur son cue : le câblage
  les lit, il ne recalcule jamais le niveau depuis le type.

## 9. État d'implémentation (intégrée et mergée le 01/11/2026)

Travail éclaté en 15 branches `feat/isee-*`, intégrées
(`fix/isee-veto-arret-15m`) puis mergées sur `master` (`0911017f`). Fusion :
personne douce = mot seul d'évitement (ordre du 01/11 18h03), poteaux parlés
niveau 7 (spec v4). Détail et contradictions résiduelles : `docs/REVUE-V4.md`.
Tableau des missions (registre d'origine des pièces) :

| Mission | Branche | État |
|---|---|---|
| Échelle + écrasement + voix | `feat/isee-echelle-overwrite` (`850f008d`) | Committé : `VoiceBus`, `OutdoorLevel`/`CrossingLevel`, `ProbePriority`, `ProximityVoice` sans file |
| Antirépétition | `feat/isee-antirepetition` (`cf30e914`) | Committé : `core.live.AntiRepeat` + cadence branchée |
| Seuils dehors | `feat/isee-seuils-dehors` (`eb1703b2`) | Committé : niveaux 2/4/5/6/7/8/10 + clés sur les cues, jalons 4/2/1 |
| Verdict traversée | `feat/isee-verdict-traversee` (`7c09364a`) | Committé : `CrossingVerdictRule`/`Announcer`/`Engine`, 100-102 |
| Voix GPS | `feat/isee-voix-gps` (`45f64960`) | Committé : rappel 15 s + phrase consigne + distance, voix réseau |
| Clips anglais | `feat/isee-clips-en` (`accecb49`) | Committé : `IseeClipsEn` + catalogue + `IseeClipPlayer` + garde |
| Gyro-arrêt | `feat/isee-gyro-arret` (`350f02a7` + `db0b9547`) | Committé : `EgoMotion`, 20 m, mouvement seul, grille luma |
| Attente chat | `feat/isee-attente-chat` (`25e3922d`) | Committé : `ChatWaitResume` (rang 9, reprise coupure) |
| Prompts + listes | `feat/isee-prompts-listes` (`3ce22146`) | Committé : 16 dehors / 10 dedans |
| Routeur dictée | `feat/isee-routeur-dictee` | **WIP non committé** : 10 ordres + ViewModel (bloc dictée) |
| Find indoor | `feat/isee-find-indoor` | **WIP non committé** : 10 cibles + niveaux dedans + clips propres |
| Morts divers | `feat/isee-morts-divers` | **WIP non committé** (33 fichiers) : Maps partiel, clips cycles/borne/portail, purge `KnownSizes` — mais `LivePrompts.kt` divergent (R-27) |
| Morts voix | `feat/isee-morts-voix` | **WIP non committé** (9 fichiers) : hors-ligne supprimé, repli TTS coupé (collision R-29) |
| Tests contrats | `feat/isee-tests-contrats` | **WIP non committé** : tests Maps supprimés + 4 tests `spec/` auto-porteurs (R-28) |
| Docs + revue | `feat/isee-docs-revue` | Cette branche |

## 35. S'écarter des gens et des poteaux même à 15 m devant (ordre user du 01/11)

Le user marche avec l'app : ni les gens ni les poteaux ne sont annoncés
assez tôt — la personne se tait au-delà de 4 m, le poteau est muet
partout (§ 26). Nouveau contrat : droit devant, les deux parlent
jusqu'à 15 m.

- **palier loin** (`HazardWatcher.PERSON_FAR_METERS` et `POLE_FAR_METERS`,
  15 m, couloir strict `SIDE_EDGE` — hors couloir = silence, au-delà de
  15 m = silence, seuils repris par la v4) : la personne dit le mot
  d'évitement (continu depuis le § 36) ; le poteau dit « Attention au
  poteau, à … heures. » (mot continu, sorte `POLE`, depuis le § 36) ;
- **poteau proche** : reparle (mot d'évitement continu depuis le § 36) ;
- **inchangé alors** : personne imminente ≤ 2 m (mot seul urgent en
  clip), parole du user devant tout, traversée (le repli haut-de-poteau
  garde `isPole`).

Verrouillé alors par `HazardConceptsTest` et `HazardWatchTest`
(contrat § 36 depuis). `check-kotlin-limits.py` vert.

Recette téléphone : personne et poteau à 12-15 m droit devant → mot
d'évitement en continu — **non mesuré**, exige un APK sur appareil réel.

## 36. Position optimale en permanence : mot continu gens + poteaux, 300 ms (ordre user du 01/11)

Le user refuse le dit-une-fois : tant qu'un gens ou un poteau est
devant (0-15 m), iSee dit la position optimale en permanence. Nouveau
contrat, qui amende le § 35 et la table v4 (§ 1, § 5) : proche comme
loin, les deux disent le côté où aller, en boucle.

- **mot continu** (`HazardWatcher.onContinuous`, dès la 1re frame — plus
  de confirmation, plus de cooldown, plus de ré-armement, plus de repli
  `HazardAlt`) : personne (niveau 2, clé `human-imminent`) et poteau
  (niveau 7, clé `pole-avoid`, sorte `POLE`) disent « Droite. » /
  « Gauche. » (`HazardText.avoidWord`, côté opposé, droite par défaut),
  répété toutes les 300 ms (`AVOID_REPEAT_MS`, immédiat au changement
  de mot), urgent, en clip `guide_*` via `hazardWish` (tout mot urgent
  part en clip, barrière et divers exclus car jamais urgents) ;
- **aval v4 inchangé** : `AntiRepeat` laisse passer `human-imminent` et
  `pole-avoid` (toujours autorisés), le bus arbitre par niveau (la
  personne écrase le poteau, la voiture écrase tout) ; en traversée,
  seule la personne parle (niveau 101) ; dedans, seule la personne
  parle ; la parole du user coupe le mot ;
- **inchangé** : seuils (15 m droit devant gens + poteaux, 10 m personne
  hors couloir, 6 m le reste, plein axe ≤ 2 m), douce confirmée des
  autres dangers (2 frames, 4 s grillages/murs/barrières, 2 s divers),
  repli haut-de-poteau (`isPole`).

Verrouillé par `HazardWatchTest` (mot 1re frame gens + poteaux proche
et loin, niveaux 2/7, clés, répétition 300 ms, immédiat au changement,
sans cooldown ni ré-armement, barrière tue puis parlante au départ),
`HazardConceptsTest` (sorte `POLE`), `SeuilsDehorsV4Test` (humain 9 m
et poteau en continu), `EchelleOverwriteTest` (personne et poteau
postés niveau 2/7 en clip dès la 1re frame), `AntiRepeatTest` +
`IseeSpecV4AntiRepeatTest` (clé `pole-avoid` immédiate) et
`ProbeHazardVoiceTest` (ligne sans repli). `check-kotlin-limits.py` et
`check-voicepack.py` verts.

Recette téléphone : gens ou poteau droit devant → « Droite. »/« Gauche. »
répété en continu jusqu'à son départ — **non mesuré**, exige un APK sur
appareil réel.
