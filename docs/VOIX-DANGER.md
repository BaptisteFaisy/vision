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
| 8 | Passages piétons < 6 m | Annonce (**« devant » toutes les 2 s** hors Traversée, 1 fois par vue sinon) + alignement **au changement** + arrivée **1 fois** | `cross_approach_*`, `crosswalk_aligned`, `cross_arrived` |
| 9 | Réponses du chat | **Attente + reprise depuis la coupure** (tranche interrompue rejouée) ; **seule file restante** | Synthèse réseau (chat) |
| 10 | Trottoirs | **Absolu (§ 45)** : coupe tout, va au bout, seul le micro user passe devant ; **< 500 ms (§ 46)** : formes courtes lues en pastille 500 ms | `sidewalk_*_short` (nouveaux, `sidewalk_*` gardés pour le voicepack) |
| 11 | GPS manœuvres et événements | Aux événements moteur | Synthèse réseau (GPS) |
| 12 | Repli | **Rien à dire + silence 300 ms** : gauche, droite ou tout droit selon GPS + obstacles | `guide_left`, `guide_right`, `guide_straight` |

## 2. Traversée exclusive : 100 à 102

Détail : `docs/TRAVERSEE.md`.

- **100 verdict** : vert confirmé = **« Cross. »** même avec des voitures
  en mouvement ; sinon (rouge / sans feu), mouvement = **« Don't cross. »**,
  sinon **« Cross. »**. Véhicules = **voiture uniquement** (bus, vélo,
  camion, moto ne tranchent jamais).
- **101 humains** du passage + alignement passage (mot + annonce +
  arrivée : niveau 8 dehors, 101 en Traversée), après le verdict.
- **102 bordures** du passage, après les humains.
- **Dictée active** (les 11 ordres restent écoutés, `crossing off` compris).
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
| `stairs` | 6 escaliers (annonce, jalons, montée) | 2 s |
| `stairs-count` | 6 compte des marches | 1 s (§ 42, amende § 37) |
| `pole` | 7 poteaux + mobilier vertical | 2 s |
| `misc` | 7 divers (animal, poubelle, souvenirs) | 2 s |
| `crosswalk-announce` | 8 annonce | **2 s** (le « devant » se répète hors Traversée ; oubli explicite à la vue perdue) |
| `crosswalk-align` | 8 alignement | **au changement** (l'appelant n'appelle que si ça change) |
| `crosswalk-arrival` | 8 arrivée | **1 fois** |
| `sidewalk` | 10 trottoirs | 10 s |
| `verdict` / `verdict:état` | 100 verdict | **2 s, changement immédiat** (clé paramétrée : un état nouveau parle aussitôt) |
| (aucune) | 9 chat | **aucune** (attente + reprise, pas de délai) |
| (aux événements) | 11 GPS | aux événements moteur, pas de délai |

Clés inconnues : toujours autorisées (jamais bloquantes — une faute de
frappe ne fait jamais taire une annonce).

## 6. Ordres dictée : les 11, préfixés par « mode »

- `mode outdoor`, `mode indoor`, `mode crossing on`, `mode crossing off`,
  `mode find my X`, `mode find off`, `mode GPS … X`, `mode GPS` (seul :
  redemande), `mode GPS off`, `mode glasses on`, `mode glasses off`.
- GPS : formes FR/EN comprises (« je veux aller à X », « I want to go to
  X », « aller à X », « take me to X »…) ou nom de lieu nu (« mode GPS
  gare de Lyon ») ; « mode GPS » seul ou sans destination comprise →
  « Où aller ? » / « Where to? » (`NavClarifyText.ask`, voix réseau
  `speakNetwork` — le FR n'a pas de clip, le clip EN `where_to` reste un
  repli hors-ligne), jamais le LLM ; questions (« où est X ») → LLM.
- **Tout le reste = question LLM.** Un « mode … » qui n'est aucun des 11
  part au LLM (jamais un ordre deviné) ; les ordres fixes n'acceptent aucun
  mot supplémentaire ; « mode find my » sans objet part au LLM.
- Les 11 ordres sont écoutés **partout, traversée comprise**.
- Confirmations en clips anglais (`outdoor_on`, `indoor_on`, `cross_on`,
  `cross_off`, `find_stopped`, `gps_started`,
  `nav_stopped`, `glasses_on`, `glasses_off`) ; la recherche (`mode find my X`,
  départ, refus, trouvé) parle en synthèse vocale — aucun clip ne couvre un
  objet quelconque.

## 7. Listes de détection (SAM 3.1, anglais)

- **Dehors (16)** : `person`, `car`, `pedestrian traffic light`, `curb`,
  `sidewalk`, `crosswalk`, `pole`, `stairs`, `fence`, `wall`, `barrier`,
  `sign`, `street lamp`, `animal`, `trash can`, `souvenir shop`.
- **Dedans (10)** : `keys`, `medicine box`, `glasses`, `glasses case`,
  `phone`, `shoes`, `door`, `wall`, `person`, `socks`.
- `mode find my X` ajoute le concept (le dernier cède son slot au plafond).
  Liste unique normal/traversée : la bascule ne change plus les prompts.

## 8. Contrats partagés (l'intégration suppose ces signatures)

- `VoiceBus` : `fun VoiceBus.post(level: Int, text: String, clipId: String?
  = null)`. Niveaux = entiers de la spec (0 à 12 dehors, 100 à 102
  traversée, 13 porte/murs dedans). Le bus arbitre l'instant (plus petit
  gagne, écrasé jeté) ; `userSpeaking` (niveau 1, seul 0 passe) et
  `chatSpeaking` (niveau 9 tenu pendant la lecture) portent les états.
- `AntiRepeat` : `object AntiRepeat` avec `fun allow(key: String, nowMs:
  Long): Boolean`, clés du § 5, durées du § 5. `reset()` en début de
  séance ; `forget(key)` pour le « 1 fois » (arrivée) et le « re-détecté
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

## 37. Compteur de marches : répétition toutes les 0,5 s (ordre user du 01/11)

Le user, dans l'escalier avec iSee, veut le compte deux fois plus
souvent qu'aux 2 s de la v4. Nouveau contrat, qui amende le § 5
(`stairs-count`, pas `stairs`) : « N marches. » se répète toutes les
0,5 s tant que l'escalier reste vu.

- **deux portes à 0,5 s** (`StairCountVoice.REPEAT_MS` et `AntiRepeat`
  clé `stairs-count`, 500 ms — le changement parle aussitôt, clé
  paramétrée `stairs-count:<compte>`, battement 300 ms inchangé) : la
  synthèse (~1 s) est coupée à chaque répétition (le bus écrase,
  dernier gagnant) au lieu de chevaucher ;
- **inchangé** : annonce « Escalier… », jalons 4/2/1 m, « Plus que N
  marches. » et « Dernière marche. » (clé `stairs`, 2 s), niveaux du
  bus, traversée et esquive (coupent le compteur comme avant).

Verrouillé par `StairCountVoiceTest` (répétition après 0,5 s, pas
avant), `AntiRepeatTest` + `IseeSpecV4AntiRepeatTest` (clé
`stairs-count` 500 ms), `ProbeStairsCountVoiceTest` (2 répétitions
par fenêtre), `ProbeHazardsTest` (silence à 499 ms) et
`SeuilsDehorsV4Test` (compte 0,5 s, annonce 2 s).
`check-kotlin-limits.py` vert.

Recette téléphone : escalier nommé → « N marches. » puis répétition
toutes les 0,5 s — **non mesuré**, exige un APK sur appareil réel.

> Amendé par le § 42 (ordre user du 02/11) : le même compte se répète
> toutes les 1 s, et le compte est prioritaire sur tout (l'esquive
> parle entre deux comptes).

## 38. Battement voix 700 ms : la priorité du moment, toutes les 700 ms (ordre user du 01/11)

Le user veut une voix qui parle toutes les 700 ms pour dire la chose
la plus prioritaire du moment. Nouveau contrat : les producteurs
continus rapportent leur vœu courant, et un battement toutes les 700 ms
propose le plus prioritaire au bus — l'écrasé n'est plus perdu dans sa
fenêtre de fraîcheur, il est re-proposé au battement suivant.

- **registre** (`VoiceTick.report`, par source : escaliers, compte des
  marches, grillage, trottoir) : le vœu courant de chaque source, frais
  2 s (`FRESH_MS`, prior à valider rue) ; le post immédiat est gardé
  (la première détection parle sans attendre le battement) ;
- **battement** (`observeVoiceTick`, démarré par `observeHazards`,
  coupé en Traversée — le verdict y est propriétaire du son) : toutes
  les 700 ms (`TICK_MS`), le plus petit niveau frais est proposé via
  `offer` (antirépétition puis bus) — ex æquo au plus récent ;
- **anti-bégaiement** : le même (niveau, texte) ne repart pas avant 1 s
  (`SAME_MS` — les clés sans antirépétition n'ont pas d'autre borne) ;
  un refus de l'antirépétition n'est pas mémorisé (retenté au
  battement suivant) ;
- **hors battement, par construction** : les obstacles (re-postés à
  chaque frame SAM, ~200 ms), la Traversée (verdict nourri à chaque
  frame), les événements (GPS, chat, passage, trouvaille — ponctuels)
  et le repli 12 (son chemin émission + silence 300 ms existe déjà —
  un double chemin bégayerait le mot).

Verrouillé par `VoiceTickTest` (8 : ordre, ex æquo, fraîcheur, offre
au bus, anti-bégaiement 1 s, retentative après refus, boucle 700 ms
au temps virtuel + silence en Traversée). `check-kotlin-limits.py`
vert, suite `:app` isee 775 tests verts.

Recette téléphone : alerte écrasée par une parole plus prioritaire →
re-dite au battement suivant (≤ 700 ms après le silence) — **non
mesuré**, exige un APK sur appareil réel.

## 39. Tranche 700 ms : chaque phrase tient dans un tick (ordre user du 01/11)

Un tick, une phrase : toute parole du bus tient dans 700 ms, dehors comme
dedans (même battement, chaîne du mode courant — §3 dedans, §1 dehors).
Deux leviers :

- **vitesse adaptative** (`sliceRateFor`, pitch conservé) : chaque clip et
  chaque synthèse GPS/confirmation joue à `max(réglage, durée/700)`
  plafonné à ×3 (`MAX_SLICE_RATE`) — au-delà, la phrase dépasse ;
  un refus plateforme rejoue à ×1,9 (prior à valider oreille et appareil) ;
- **formes minimales** (~15 caractères) : manœuvres GPS (`Turn left.`,
  `Straight on.`, `Off route.`, `Arrived.`…), confirmations et ordres
  (`Crossing on.`, `Dedans.`, `Création picto.`, `Where to?`…),
  miroirs `strings.xml` et `voicepack-catalog.json` à jour (entrées
  `stale_audio` : l'audio dit encore l'ancien texte, régénération
  pendante) ;
- **verdicts Traversée exemptés** : `Cross.` / `Don't cross.` dits
  entiers à vitesse du réglage (421/632 ms à ×1,9) — la sécurité ne
  s'accélère ni ne se coupe jamais ; chat LLM hors tranche (imprévisible).

Phrases qui dépassent même à ×3 (résiduelles, pas de coupe horloge —
seule une parole plus prioritaire coupe, règle du bus inchangée, et le
battement §38 re-propose) : créneaux non bornés (destinations, rues,
noms d'objets), littérales moteur Valhalla, rappel GPS (consigne +
distance), FR marginales (`Gauche, 5 mètres.` ~370 ms).

Verrouillé par `VoiceSliceTest` (tranche, plafond, verdict entier) et
`VoiceTickTest` (10, modes exclusifs) ; `check-voicepack` (miroir),
`check-voice-nav-doc` et `check-kotlin-limits` verts.

Recette téléphone : oreille — mots ×2-3 intelligibles, verdicts entiers,
aucune coupure mid-mot — **non mesuré**, exige un APK sur appareil réel.

## 40. Escaliers incoupables : le niveau 0 et la Traversée attendent (ordre user du 02/11)

Le user marche : l'annonce des marches (« Escalier montant. »,
« 5 marches. », « Plus que N marches. », « Dernière marche. ») est coupée
en pleine phrase par « Car. » — le niveau 0, re-posté à chaque frame,
écrase le niveau 6 (écrasement pur du bus). Incompréhensible. Nouveau
contrat : des escaliers en cours vont au bout.

- **bus** : `VoiceBus.post` jette le niveau 0 pendant un niveau 6
  (`app/.../isee/VoiceBus.kt`) — seule exception à l'écrasement pur ;
  la Traversée (100+, déjà non préemptive sur 6 : `100 > 6`) attend
  aussi (chevauchement d'armement seul — en Traversée, les escaliers
  ne postent plus) ;
- **amont** : le vœu véhicule se tait avant l'antirépétition
  (`obstacleFrame`, `VoiceBus.stairsSpeaking`) — tour `car-moving`
  non consommé, la voiture reparle dès la fin des marches, côté bus
  (le cooldown du guetteur, 4 s, peut retarder d'un cycle : le suivi
  ne s'interrompt pas, seule la parole attend) ;
- **inchangé** : humains (2), GPS (3, 11), grillages (4), bordures (5)
  et escaliers re-détectés (6, dernier gagnant — le compte reste
  frais) coupent toujours ; parole du user (1), chat (9) et sécurité
  inchangés.

Verrouillé par `StairsUncuttableTest` (+5, nouveau : 0 attend, verdict
attend, 2/5 coupent, 6 dernier gagnant, voiture tue sans tour mangé).

Recette téléphone : escalier + voiture qui passe — la phrase des marches
entière, « Car. » juste après — **non mesuré**, exige un APK sur appareil
réel (preuves par `logcat`, jamais à l'oreille côté agent).

## 41. Consignes atomiques : mot seul « Left. »/« Right. », incoupable (ordre user du 02/11)

Le user marche : les consignes de guidage sont coupées en pleine phrase
(« Tournez à… » mangé par « Car. »), et les préavis lointains avec distances
arrivent trop tard pour servir. Nouveau contrat : **mot anglais seul** —
« Left. » / « Right. » dits au moment de tourner, silence partout ailleurs,
et le mot en cours va toujours au bout.

- **phrases** : `VoiceNavPhrases.maneuverWord` (mot seul si virage franc
  proche, `TURN_NOW_M`, sinon blanc) ; `ContinueStraight` et caps droits =
  silence, écran inchangé (`ahead()`, « dans X », reste) ;
- **porte** : `VoiceNavAnnouncer.isSilentManeuver` tait les manœuvres
  silencieuses avant la porte (rien consommé : un proche <3 s après un
  loin parle) ; même clé dite = muette (nouveauté), la redite passe par
  le rappel 15 s du moteur, hors porte ;
- **moteur** : `speakManeuver` ignore départ/suite, `tickPeriodic` ne
  rappelle que de près (`nearM ≤ TURN_NOW_M`), `lastInstruction` = mot
  seul (`gpsReminderPhrase` et distances supprimés) ;
- **bus** : `VoiceBus.post` jette tout pendant un mot de guidage (11, 3 —
  `guidanceSpeaking`), sauf la parole user (1) ; les producteurs frame
  se taisent en amont (`guidanceHolds`, tours d'antirépétition intacts) ;
- **clips** : « Left. »/« Right. » = `guide_left`/`guide_right`
  (`GuidanceWord.clipIdFor`, <100 ms, jamais en synthèse).

Verrouillé par `GpsUncuttableTest` (+6, nouveau : voiture/humain/verdict
attendent, rappel et manœuvre ne se coupent pas, re-post GPS attend,
parole user coupe, frame tue sans tour mangé) + mots seuls dans
`VoiceNavPolicyTest`, `VoiceNavSpeakerTest`, `VoiceNavAnnouncerTest`,
`PeriodicVoiceTest`, `VoiceNavGpsVoiceTest`.

Recette téléphone : guidage vers un virage — « Left. » sec au moment de
tourner, rien avant, jamais coupé — **non mesuré**, exige un APK sur
appareil réel (preuves par `logcat`, jamais à l'oreille côté agent).

> Amendé par le § 43 (ordre user du 02/11, même jour) : plus seul le
> niveau 0 attend — tout le monde attend, et les escaliers coupent
> (respect mutuel avec les mots de guidage, § 41).

## 42. Évitement des humains à 1 m minimum (ordre user du 02/11)

« Quand il doit dire gauche et droite, qu'il s'éloigne pour être minimum à
1 m de lui ou plus. »

- **constante** : `PeoplePath.MIN_CLEARANCE_M` 0,8 → 1,0 m — bandes
  interdites (corps + 1 m), `clearsSide`/`clearsAhead`, veto trou de
  grillage, veto passage SAM et retour trottoir suivent ;
- **repli inchangé** : aucun côté à 1 m (foule dense) → côté le moins
  encombré, on se faufile (ordre du 31/10) — l'app ne se tait ni ne
  s'arrête devant des humains ;
- **à 1 m, un proche couvre le champ** : une personne à ≤ 1,5 m près de
  l'axe bouche toute la largeur d'image — le plan avoue (`fits=false`)
  et le couloir prend le repli ;
- **priorité du compte** : le compte des marches passe avant (§ 43) — il
  ne se tait pas pour l'évitement, l'esquive « gauche » / « droite »
  parle au milieu de chaque seconde.

Verrouillé par `PeoplePathTest` (caps, écarts vrais et discriminants
recalibrés au mètre, créneau fantôme conservé) + renommages 80cm → 1m
(`GuidanceSteeringTest`, `GuidanceAnnouncerTest`, `ProbeVoiceFenceHoleTest`).

## 43. Compte des marches prioritaire : toutes les 1 s, esquive au milieu (ordre user du 02/11)


Le user, dans l'escalier avec iSee : quand un escalier est reconnu, le
compte (« N marches. ») se dit toutes les 1 s, prioritaire sur tout ce
que la voix doit dire — et au milieu de chaque seconde, l'esquive dit
« gauche » / « droite » pour passer les gens. Deux écarts au contrat
du matin (§ 37, § 40) : le compte se taisait dès qu'une esquive
commençait (`stairsYieldToAvoidance`), et humains, grillages et
bordures coupaient le niveau 6 en pleine phrase.

- **cadence 1 s** : les deux portes du compte passent de 0,5 s à 1 s
  (`StairCountVoice.REPEAT_MS`, `AntiRepeat` clé `stairs-count` —
  le changement parle toujours aussitôt, clé paramétrée
  `stairs-count:<compte>`, battement 300 ms inchangé) : le compte
  suit chaque pas, l'esquive tient au milieu ;
- **bus** : le niveau 6 coupe toute parole en cours (voitures,
  humains, bordures, chat compris) et va au bout, personne ne
  le coupe (`VoiceBus.post` — parole du user et mot de guidage
  en cours exceptés, § 41) ; garde anti-blocage (`stairsBusStuck`,
  4 s : un niveau 6 orphelin ne tait plus jamais toute la voix) ;
- **boucle** : le compteur ne cède plus à l'esquive
  (`observeStairsCount` sans `stairsYieldToAvoidance`, gardé pour
  l'annonce seule) — l'esquive ne perd rien : mots continus
  humains (niveau 2, sans antirépétition) et repli 12, re-postés
  en continu, parlent dans le silence entre deux comptes, jamais
  par-dessus ;
- **audible** : synthèse rouverte pour tous sans clip (porte D-04
  tombée le matin même — le compte parle, cache voix : une synthèse
  par compte et par session au plus), verrouillée pour le niveau 6
  par `MortsVoixV4Test` ;
- **GPS mutuel** : une parole GPS en cours (rappel 3, manœuvre 11,
  jamais re-proposée) va au bout, le compte reprenant après — en
  retour une manœuvre sous un compte est reportée au silence (un
  cycle au plus), jamais perdue ;
- **chat** : les tranches attendent la fin des marches (ni coupure
  du compte, ni bégaiement dans l'intervalle — reprise depuis la
  coupure, comme après tout niveau prioritaire) ;
- **bouclier antirépétition** : sous un compte, les vœux non
  escaliers attendent sans consommer leur tour (`offer`, stop,
  grillage, trottoir — le garde véhicule seul du § 40 est
  fondu dedans) et parlent dans l'intervalle.

Verrouillé par `StairCountVoiceTest` (répétition après 1 s),
`IseeSpecV4AntiRepeatTest` + `SeuilsDehorsV4Test` (compte 1 s,
annonce 2 s), `StairsUncuttableTest` (tous attendent, 6 coupe,
esquive au milieu, parole user devant, GPS mutuel, garde 4 s),
`ProbeStairsCountVoiceTest` (cadence 1 s, compte dans l'esquive),
`MortsVoixV4Test` (compte synthétisé), `EchelleOverwriteTest`
(vœux en attente sous un compte), `ProbeWiringSpokenTest` (stop
attendu), `IseePlaybackTest` (chat attendu) et `IseeVoiceSpineTest`
(reprise et GPS reportés).

Recette téléphone : escalier + gens qui passent — « N marches. »
toutes les 1 s, « Gauche. »/« Droite. » entre deux — **non
mesuré**, exige un APK sur appareil réel (preuves par `logcat`,
jamais à l'oreille côté agent).

## 43. GPS : rappel 15 s, jamais de coupe, phrases < 1 s (ordre user du 02/11)

Le GPS dit l'info du guidage toutes les 15 s (rappel, comme avant), ne
coupe jamais rien, et n'est coupé par rien une fois lancé (sauf la
parole du user) ; chaque phrase tient la tranche ×3 (< 1 s jouée) —
sauf les noms (destination, rues), gardés entiers (exception admise).

- **rappel 15 s** : `GPS_REMINDER_MS` + `PeriodicGuidance` (mot seul de
  près, cadence recalée par toute annonce moteur) — verrouillé par
  `VoiceNavGpsVoiceTest`, inchangé ;
- **jamais de coupe** : côté bus, les crans 11/3 perdent contre tout cran
  plus petit (le GPS attend au lieu de couper) et le garde §41 le rend
  incoupable une fois lancé ; côté repli système, les urgences partent
  en file comme le doux (`VoiceNavSpeaker`, voix grave gardée) et le
  user passe toujours devant, urgences comprises (miroir du bus) ;
  `SpeechArbitration.interrupts` (mort en prod) est supprimé ;
- **phrases < 1 s** : toutes les phrases GPS bornées tiennent 24 car /
  4 mots (pire ancre mesurée ≈ 110 ms/car → ≲ 2,7 s d'audio → ≤ 0,9 s
  à ×3) — rues et distances jamais parlées (écran seul) ; « Left. » /
  « Right. » épinglés en clips offline ; destinations (« Vers X. »,
  « Going to X », « Go to X? … ») gardées entières (exception user) ;
- **gardes gardées** : le danger obstacle (`onHazard`, file TTS
  système) reste le seul coupeur du repli (zéro appelant prod) ;
- **escaliers (§ 42)** : respect mutuel — un mot GPS en cours va au
  bout, une manœuvre sous un compte est reportée au silence (un cycle
  au plus), jamais perdue.

Verrouillé par `GpsUncuttableTest` (+1 : manœuvre et rappel attendent,
le GPS ne coupe jamais), `GpsPhraseBudgetTest` (nouveau : budget,
clips, rues/distances absentes, noms entiers), `VoiceNavSpeakerTest`
(danger en file grave sans flush, user tait tout) et
`SpeechArbitrationTest` (mapping seul, coupe supprimée).

Recette téléphone : guidage avec rappel — un mot toutes les 15 s de
près, jamais par-dessus une autre voix, jamais coupé — **non mesuré**,
exige un APK sur appareil réel (preuves par `logcat`).

## 44. Le compte des marches ne se coupe plus lui-même (ordre user du 03/11)

Le user, dans l'escalier : « quand il détecte un escalier et qu'il doit dire
le nombre de marches, il se coupe en permanence et donc n'arrive pas à dire
le nombre de marches. » Le § 43 protégeait le niveau 6 de **tous les autres**
niveaux, mais pas de lui-même : trois producteurs postent au niveau 6
(annonce `Escalier montant.` à 2 s, compte `N marches.` à 300 ms, battement
700 ms), et chaque post refaisait `player.stop()` (`BusSpeaker.say`) —
le compte changeait donc de phrase avant d'avoir fini la précédente.

- **règle** : une phrase de niveau 6 en cours **n'est jamais interrompue par
  une autre phrase de niveau 6**. La nouvelle est retenue dans une **place
  unique** (la plus récente écrase les précédentes), jouée à la fin de la
  phrase courante (`VoiceBus.onSpeechIdle`), et abandonnée si elle a plus de
  [1,5 s] (`STAIRS_PENDING_TTL_MS`) — un compte de l'image d'avant ne se dit
  pas, le tick suivant repostera le compte courant. Les autres niveaux sont
  inchangés : ils attendent tous la fin des escaliers (§ 40, § 43) ;
- **compte lissé** : le compte dit est la **médiane des 3 derniers ticks**
  (`StairCountVoice.WINDOW_TICKS`, 900 ms) — mesuré le 03/11, le compteur
  Canny est volatil pour des raisons photométriques (grain ±10 → 8 à 12
  marches fantômes, exposition ×1,4 → 8 à 11, contraste bas → 8 à 4) et
  suivre le brut faisait redémarrer la phrase toutes les 300 ms. Un pic
  d'un tick ne parle jamais, deux ticks d'accord (une vraie marche) si. La
  première détection parle tout de suite ; la répétition d'un même compte
  reste à 1 s (§ 43), et deux comptes différents restent espacés d'au moins
  1 s ;
- **coupes** : la parole du user, une sortie d'écran, l'intro ou l'arrêt du
  lecteur de clips (`VoiceBus.onSpeechIdle(cut = true)`) abandonnent la
  phrase retenue — son tour d'antirépétition est rendu, le tick suivant
  repostera le compte — au lieu de la dire par-dessus la raison de la
  coupe ;
- **garde anti-blocage inchangée** : `stairsBusStuck` (4 s) rend le bus si un
  niveau 6 orphelin ne parle plus.

Verrouillé par `StairsUncuttableTest` (amendé : « escaliers re-détectés
dernier gagnant **sans couper** » ; +4 : place unique dernier gagnant,
compte périmé, abandon sur parole user) et `StairCountVoiceTest` (+3 :
médiane de 3 ticks, oscillation du compteur, montée réelle ; 2 amendés).

Recette téléphone : monter un escalier avec iSee — chaque compte dit en
entier, jamais de syllabe répétée, jamais de silence prolongé — **non
mesuré**, exige un APK sur appareil réel (preuves par `logcat` : `VoiceMute`
`bus-escaliers-en-attente` / `bus-escaliers-enchaine`).

## 45. Trottoir absolu : coupe tout, va au bout (ordre user du 02/11)

Le user veut l'annonce du trottoir (« Trottoir à gauche. »…) incoupable
et la plus prioritaire : elle coupe toute parole en cours — escaliers
et mot de guidage compris — et va au bout, personne ne la coupe.

- **bus** : `VoiceBus.post` parle un niveau 10 aussitôt (`postSidewalk` :
  coupe tout) et jette tout pendant un niveau 10 (`trottoir-en-cours`,
  tour rendu) — escaliers (6) et Traversée (100+) attendent ; seule la
  parole du user (1, micro tenu) passe devant ;
- **pas de place retenue** : l'antirépétition trottoir (10 s) et les clips
  courts rendent l'auto-coupe impossible (deux annonces ne se chevauchent
  jamais) — pas de pendant du `pendingStairs` (§ 44) ;
- **amont** : le guetteur trottoir ne se tait plus sous un compte
  (`observeSidewalk`) ; `offer` tient les vœux non trottoirs sous un
  trottoir et laisse passer le trottoir sous un compte (tours intacts) ;
  stop, grillage et escaliers (annonce + compteur) attendent sa fin sans
  manger leur tour (le compteur attend devant `onTick`, pour reparler au
  battement suivant) ;
- **GPS** : une manœuvre sous un trottoir est reportée au silence (un
  cycle au plus), jamais perdue (`speakGps`, `deferGpsToGap`) ;
- **garde anti-blocage** : `sidewalkBusStuck` (4 s, miroir de
  `stairsBusStuck`) rend le bus si un niveau 10 orphelin ne parle plus —
  gardes sorties dans `VoiceBusGuards.kt` (plafond 500 lignes) ;
- **inchangé** : niveau 10, clé `sidewalk` (10 s), Traversée (le guetteur
  s'y tait, le verdict attend un trottoir en cours), chat (reprise depuis
  la coupure après un trottoir comme après des escaliers).

Verrouillé par `SidewalkUncuttableTest` (nouveau : coupe tout, tous
attendent, micro user, dernier gagnant, garde 4 s), `EchelleOverwriteTest`
(+2 : offres sous trottoir/compte ; écrasé trottoir→repli),
`StairsUncuttableTest` (amendé : le trottoir coupe le compte),
`IseeVoiceSpineTest` (+1 : GPS reporté), `ProbeWiringSpokenTest` (+1 : stop
attendu) et `ProbeStairsCountVoiceTest` (+1 : compte attendu, tour intact).

Recette téléphone : trottoir nommé pendant un compte — l'annonce entière
aussitôt, le compte juste après — **non mesuré**, exige un APK sur appareil
réel (preuves par `logcat`, jamais à l'oreille côté agent).

## 46. Trottoir en moins de 500 ms : formes courtes + pastille 500 ms (ordre user du 02/11)

L'annonce absolue (§ 45) bloquait la voix 1,5 à 2 s (clips mesurés :
`ahead` 1,49 s, `left` 1,57 s, `right` 1,65 s, `across` 2,04 s — même à
×3, plafond d'intelligibilité, trois sur quatre dépassaient 500 ms).
Nouveau contrat : l'annonce tient en moins de 500 ms.

- **formes courtes** : « Sidewalk. », « Sidewalk left. », « Sidewalk
  right. », « Sidewalk across. » (`SidewalkText`, EN) — le FR est
  inchangé (muet en v4 : pas de clip FR, seuls les niveaux GPS
  synthétisent) ;
- **nouveaux clips** : `sidewalk_*_short` (catalogue `clips`, miroir
  `IseeClipsEn` + `IseeClipsSidewalk`, garde `check-isee-clips-en.py`) —
  les `sidewalk_*` existants sont gardés (partagés avec le voicepack,
  section `reuse`) ;
- **pauses trimmées** : la synthèse mettait jusqu'à 1 s de pauses dans
  1,8 s d'audio — les MP3 commis sont trimmés (silences tête/queue
  retirés, pauses internes plafonnées à 0,1 s) : `across` 0,91 s, `left`
  0,81 s, `right` 0,76 s, `ahead` 0,50 s (mesurés `ffprobe`, pics
  −9/−11 dB). Recette de reproduction (un `--regenerate` perd le trim) :
  `ffmpeg -i IN -af silenceremove=start_periods=1:start_duration=0:
  start_threshold=-30dB:stop_periods=-1:stop_duration=0.1:
  stop_threshold=-30dB -codec:a libmp3lame -b:a 128k OUT` ;
- **pastille 500 ms** : le niveau 10 se lit en tranche 500 ms
  (`sliceMsForLevel`, verdict entier et 700 ms inchangés) — à vitesse de
  base ×1,9 les quatre annonces tiennent (481/426/399/261 ms jouées,
  aucune accélération au-delà du réglage) ;
- **traversée** : le but suit la forme courte (« Sidewalk across. ») lue
  dans son clip court (`TraversePhrases.clipIdFor`).

Verrouillé par `VoiceSliceTest` (+2 : pastille par niveau, bus 500 ms),
`IseeClipsEnTest` (registre 127, miroir, 4 clefs inverses),
`SidewalkWatcherTest` + `SeuilsDehorsV4Test` (phrases courtes),
`TraverseVoiceTest` + `TraverseVetoPriorityTest` (but court + clip court)
et `GUIDAGE-TOUT-DROIT.md` (phrases citées).

Recette téléphone : trottoir nommé — annonce brève (< 500 ms), voix
libérée aussitôt — intelligibilité à valider à l'oreille (vitesse de
base, pas d'accélération forcée).
