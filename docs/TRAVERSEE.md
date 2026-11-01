# Mode traversée — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026.** Le mode traversée dit à une personne aveugle
> **quand traverser** : verdict exclusif (feu piéton puis voitures), niveaux
> 100 à 102, chat et GPS muets, dictée active.
> Historique pré-v4 (recherche 10 angles, `TraverseVeto`, hystérésis,
> voix « Vert, traversez. » / « Rouge, attendez. ») :
> `git show 4a3c2bcf:docs/TRAVERSEE.md`.

## 1. Le verdict (niveau 100)

Règle, dans cet ordre :

1. **Feu piéton rouge = « Don't cross. »**, quoi que fassent les voitures.
2. **Feu vert ou absent** : une voiture en mouvement ? **« Don't cross. »**
   : **« Cross. »**.
3. **Véhicules = voiture uniquement** (`car`, `cars`, `voiture`,
   `voitures`) : bus, vélo, camion, moto ne tranchent jamais.

- Clips : `verdict-dont-cross` « Don't cross. », `verdict-cross` « Cross. »
  (verdict : tirets, textes courts — tranche la contradiction du § 6).
- Répétition : **3 s, changement immédiat** — le premier verdict parle
  aussitôt, un changement parle aussitôt, un verdict maintenu se répète
  toutes les 3 s (clé `verdict` / `verdict:état`).
- Le verdict vibre (`IseeHaptics.crossing`) et s'affiche (cadre VERT /
  ROUGE sur le feu, étiquette `CROSS` / `DON'T CROSS`) — même vocabulaire
  sur les deux surfaces, pas de cadre quand le mode est coupé ou sans feu.

## 2. Ordre exclusif : verdict > humains > bordures

| Niveau | Quoi | Règle |
|---|---|---|
| 100 | Verdict | § 1, prioritaire sur tout le reste |
| 101 | Humains du passage | Après le verdict (même phrase qu'au niveau 2 : phrase + cadran + distance, imminent immédiat) |
| 102 | Bordures du passage | Après les humains (montée / descente) |

Chaque place `null` se tait (pas de remplissage : un verdict seul ne fait
pas parler les bordures). Les niveaux dehors (0 à 12) ne parlent pas en
traversée — sauf la parole du user (1), que seul le cran 0 coupe, et les
producteurs dehors se taisent en mode traversée.

## 3. Exclusivité : chat et GPS muets, dictée active

- **Chat muet** : les réponses attendent (file du niveau 9) et reprennent à
  la sortie du mode, jamais perdues.
- **GPS muet** : ni manœuvres (11), ni rappels (3) tant que le mode est
  actif. La progression route se tait ; au rappel proche, le mode s'arme
  une seule fois (« Mode traversée activé. » reste en synthèse ? Non :
  clip `cross_on` « Crossing mode on. »).
- **Dictée active** : les 10 ordres « mode … » restent écoutés, `mode
  crossing off` compris.

## 4. Détection en traversée

- Liste unique normal/traversée (16 dehors, `docs/VOIX-DANGER.md` § 7) :
  la bascule ne change plus les prompts. Le verdict lit le feu
  (`pedestrian traffic light`) et les voitures (`car`) ; les niveaux 101
  et 102 lisent `person` et `curb`.
- Couleur du feu : relue localement sur chaque frame caméra (au plus tard
  toutes les 150 ms) ; position : cadence SAM. Hystérésis gardée côté voix
  (le verdict ne bégaye pas).
- Mouvement voiture : suivi client (association IoU, vitesse lissée,
  ego-motion fond + gyro) — une boîte qui suit la caméra (pan sur une
  voiture garée) n'est pas un mouvement.

## 5. Proposition du mode (niveau 8)

Le passage piéton < 6 m s'annonce **1 fois par vue** (« Crosswalk ahead. »
+ côté), l'alignement se dit **au changement** (« Aligned with the
crosswalk. »), l'arrivée **1 fois** (« On the crosswalk. »). La pastille
de carte propose le mode (« Passage piéton — traversée ? », ambre, un tap
= `crossing on`) ; un refus se tait une minute. Entrer en traversée ne se
fait jamais seul : seuls la puce et l'ordre vocal arment le mode.

## 6. Tranché par la revue v4 (à appliquer à l'intégration)

- **Texte et clips du verdict** : « Cross. » / « Don't cross. », ids
  `verdict-cross` / `verdict-dont-cross` (branche verdict). Le catalogue
  clips-en (`verdict_cross` / « Cross now. », `verdict_wait`) est aligné
  dessus à l'intégration — pas l'inverse (le verdict est la source).
- **Niveaux** : `CrossingLevel` (100/101/102) est l'unique source côté app ;
  `CrossingLevels` (core, branche verdict) est replié dessus.
- **Antirépétition du verdict** : clé `verdict:état` de la table centrale
  (3 s, changement immédiat) ; l'annonceur maison (`CrossingVerdictAnnouncer`)
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
- **avec feu** : inchangé — le vert confirmé + veto trafic décide, cette
  règle ne couvre que la branche sans-feu ;
- **preuves** : `NoLightCrossingTest` :core (silence avant 3 s, fenêtre
  rouverte par un danger, phrase calme qui ne rouvre pas). Le câblage
  :app (`NoLightCrossingVoice`, supprimé par la v4) n'est plus testé.
