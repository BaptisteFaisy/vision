# Mode traversée — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026** (niveaux revus le 02/11/2026 : verdict **100**,
> puis 101/102). Le mode traversée dit
> à une personne aveugle **quand traverser** : verdict exclusif (feu piéton
> puis voitures), niveaux **100** (verdict) puis 101/102, chat et GPS muets,
> dictée active.
> Historique pré-v4 (recherche 10 angles, `TraverseVeto`, hystérésis,
> voix « Vert, traversez. » / « Rouge, attendez. ») :
> `git show 4a3c2bcf:docs/TRAVERSEE.md`.

## 1. Le verdict (niveau 100)

Règle, dans cet ordre :

1. **Feu piéton rouge = « Don't cross. »**, quoi que fassent les voitures.
2. **Feu vert confirmé = « Cross. »**, même avec des voitures en mouvement
   (ordre user du 02/11/2026) ; **sans feu** : une voiture en mouvement ?
   **« Don't cross. »** : **« Cross. »** (le premier sans feu exige 3 s de
   calme, voir plus bas).
3. **Véhicules = voiture uniquement** (`car`, `cars`, `voiture`,
   `voitures`) : bus, vélo, camion, moto ne tranchent jamais.

- Clips : `verdict-dont-cross` « Don't cross. », `verdict-cross` « Cross. »
  (verdict : tirets, textes courts — tranche la contradiction du § 6).
- Répétition : **2 s, changement immédiat** (ordre user du 02/11/2026) —
  le premier verdict parle aussitôt, un changement parle aussitôt, un
  verdict maintenu se répète toutes les 2 s (clé `verdict` / `verdict:état`).
  `CrossingVerdictAnnouncer.REPEAT_MS = 2_000L` (`CrossingVerdict.kt`).
- **Phrase refusée = rejouée** : le verdict est posté au cran **100**, donc
  un mot d'obstacle dehors (2 à 12) le précède. `VoiceBus.post` rend un
  `Boolean` ; sur `false`, `CrossingVerdictAnnouncer.rearm` remet
  l'échéance de répétition à zéro et la phrase repart à la frame suivante
  (≤ 7 fois/s, `post` est `@Synchronized` ; A9 § 4.4). C'est cette protection — le
  ré-armement — qui remplace l'escalade de priorité : le cran 0 reste
  **sans producteur** (ordre user du 02/11/2026, voitures muettes dehors).
- Le verdict vibre (`IseeHaptics.crossing`) et s'affiche (cadre VERT /
  ROUGE sur le feu, étiquette `CROSS` / `DON'T CROSS`) — même vocabulaire
  sur les deux surfaces, pas de cadre quand le mode est coupé ou sans feu.

## 2. Ordre exclusif : verdict > humains > bordures

| Niveau | Quoi | Règle |
|---|---|---|
| **100** | Verdict | § 1, devant les humains (101) et les bordures (102), jamais coupé par eux. Il passe **après** les mots d'obstacle dehors (2 à 12) et après la parole du user (1) : la protection contre l'écrasement est le **ré-armement** (§ 1, `VoiceBus.post` rend `false`, `rearm` rejoue), pas une escalade de priorité — décision du 02/11/2026, voitures muettes dehors et cran 0 sans producteur (A9 § 3 D8, § 4.4) |
| 101 | Humains du passage + alignement | Après le verdict (humains : même phrase qu'au niveau 2 : phrase + cadran + distance, imminent immédiat ; alignement passage : mot + annonce + arrivée, niveau 8 dehors, 101 en Traversée) |
| 102 | Bordures du passage | Après les humains (montée / descente) |

Chaque place `null` se tait (pas de remplissage : un verdict seul ne fait
pas parler les bordures). Les niveaux dehors (0 à 12) ne parlent pas en
traversée et leurs producteurs s'y taisent — sauf la parole du user (1),
qui **précède** le verdict (plus petit nombre = plus forte priorité, donc
1 passe avant 100) : aucun cran de la Traversée ne la coupe.

## 3. Exclusivité : chat et GPS muets, dictée active

- **Chat muet** : les réponses attendent (file du niveau 9) et reprennent à
  la sortie du mode, jamais perdues.
- **GPS muet** : ni manœuvres (11), ni rappels (3) tant que le mode est
  actif. La progression route se tait ; au rappel proche, le mode s'arme
  une seule fois (« Mode traversée activé. » reste en synthèse ? Non :
  clip `cross_on` « Crossing mode on. »).
- **Dictée active** : les 11 ordres « mode … » restent écoutés, `mode
  crossing off` compris.

## 4. Détection en traversée

- Liste unique normal/traversée (16 dehors, `docs/VOIX-DANGER.md` § 7) :
  la bascule ne change plus les prompts. Le verdict lit le feu
  (`pedestrian traffic light`) et les voitures (`car`) ; les niveaux 101
  et 102 lisent `person` et `curb`.
- Couleur du feu : relue localement sur **chaque** frame caméra (le moteur
  du verdict est nourri par frame en Traversée, `LiveProbePublish.computeRest`) ;
  position : cadence SAM. Hystérésis gardée côté voix (le verdict ne bégaye pas).
- Mouvement voiture : suivi client (association IoU, vitesse lissée,
  ego-motion fond + gyro) — une boîte qui suit la caméra (pan sur une
  voiture garée) n'est pas un mouvement.

## 5. Proposition du mode (niveau 8)

Le passage piéton < 6 m s'annonce (« Crosswalk. » devant — clip < 500 ms
redit toutes les 2 s hors Traversée — « Left. »/« Right. » sur les côtés,
1 fois par vue), l'alignement se dit **au changement** (« Aligned with the
crosswalk. »), l'arrivée **1 fois** (« On the crosswalk. »). La pastille
de carte propose le mode (« Passage piéton — traversée ? », ambre, un tap
= `crossing on`) ; un refus se tait une minute. Entrer en traversée ne se
fait jamais seul : seuls la puce et l'ordre vocal arment le mode.

## 6. Tranché par la revue v4 (à appliquer à l'intégration)

- **Texte et clips du verdict** : « Cross. » / « Don't cross. », ids
  `verdict-cross` / `verdict-dont-cross` (branche verdict). Le catalogue
  clips-en (`verdict_cross` / « Cross now. », `verdict_wait`) est aligné
  dessus à l'intégration — pas l'inverse (le verdict est la source).
- **Niveaux** (appliqué) : `CrossingLevels` (`:core`, 100/101/102) est
  l'unique source ; il n'y a pas de `CrossingLevel` côté `:app`, rien à
  replier.
- **Antirépétition du verdict** : clé `verdict:état` de la table centrale
  (2 s, changement immédiat) ; l'annonceur maison (`CrossingVerdictAnnouncer`)
  est replié dessus ou supprimé — pas deux mécanismes.
- **Ancien pipeline** (`TraverseVeto`, `TraversePipeline`, voix « Vert,
  traversez. ») : **décâblé et supprimé** (missions morts-* vides :
  l'intégrateur le fait). Tant qu'il vit, double voix FR + EN en traversée.
- **Veto vélos** (`CycleWatcher`, « Attention, vélo en mouvement ») :
  **supprimé** (vélo sorti de la spec) — il parle encore sur toutes les
  branches via `CrossingVehicleVoice` et `LiveVehicleVoice`.

## 18. Sans feu : « Traversez. » après 3 s sans voiture en mouvement (01/11)

Ordre user : en Traversée sans feu, « Traversez. » ne se dit que si
aucune voiture en mouvement n'a été détectée durant les 3 dernières
secondes. Avant, 2 frames calmes (~0,4 s) suffisaient — une voiture qui
venait de passer libérait le verdict aussitôt.

- **noyau** : `NoLightAnnouncer.CALM_MS = 3_000L` — le premier
  « Traversez. » exige 3 s de calme continu (mouvement brut seul, une
  garée ne rouvre jamais la fenêtre) ; tout danger la rouvre, et une
  phrase véhicules sur calme (« à l'arrêt ») recule le délai sans la
  rouvrir. « Attendez. » inchangé (2 frames, coupe le délai après un
  « Traversez. », redit toutes les 2 s comme le verdict) ;
- **avec feu** : le vert confirmé décide seul (« Cross. » même avec des
  voitures en mouvement) ; hors vert, le veto trafic s'applique comme
  avant — cette règle ne couvre que la branche sans-feu ;
- **preuves** : `NoLightCrossingTest` :core (silence avant 3 s, fenêtre
  rouverte par un danger, phrase calme qui ne rouvre pas). Le câblage
  :app (`NoLightCrossingVoice`, supprimé par la v4) n'est plus testé.
