# Mode traversée — bilan du 28/10/2026

Le mode traversée dit à une personne aveugle **quand traverser** : il lit le
feu piéton, surveille les véhicules, et parle (« Vert, traversez. » /
« Rouge, attendez. »). Ce document raconte le travail livré le 28/10/2026
(recherche, paquet `traverse/`, câblage) et ce qui reste à prouver avant un
usage réel. Code : `core/.../traverse/`, `app/.../traverse/`,
`core/.../crossing/`, `app/.../isee/CrossingWiring.kt`.

## 1. Recherche préalable (10 angles)

Dix agents ont passé le mode traversée en revue : détection des feux,
trafic, décision, suivi temporel, UX malvoyants, capteurs, archi temps réel,
données, sécurité, roadmap. Verdict : fail-safe de base sain (doute =
attendre), mais 3 trous critiques — feu vert **voiture** confondu avec le
feu piéton, **grille-feu** non détecté (les boîtes `car` n'étaient pas lues),
voix **muette ou préemptée** (flag `urgent` non câblé, ElevenLabs seul).
Quatre chantiers retenus : gains perf, hystérésis, veto trafic, voix sûre.

## 2. Implémentation (paquet `traverse/`, PR #163–167)

Implémenté en 4 streams parallèles (un worktree par stream, review, fix),
intégré, porté sur `vision/master` car le dépôt de travail était archivé :

- **Perf (#163)** : `mask_encoding` bits, WebSocket `/live/ws` et `/release`
  centralisé déjà présents sur `vision` — la PR ne verrouille que le contrat
  (test requête unique, `LifecycleContractTest` conservé).
- **Hystérésis (#164)** : `TraverseState.kt` — confirmation après 2
  détections, 2 frames manquantes tolérées, seuils 0,5 / 0,6 / 0,4, boîte
  gelée. Fini le clignotement du `/live` sans état.
- **Veto trafic (#165)** : `TraverseVeto.kt` — machine
  ATTENDRE / PRÊT / TRAVERSER / ALERTE ; un véhicule (score > 0,5) qui
  approche (boîte croissante) ou proche (< 15 m sténopé) force « attendez »,
  même sur vert ; le guidage obstacles reste actif devant un objet proche.
- **Voix sûre (#166)** : `TraverseVoice.kt` — l'urgent (vert→rouge) coupe la
  lecture en cours et n'est jamais jeté ; préchauffage hors-ligne des 3
  phrases FR via le TTS système, repli cache automatique si ElevenLabs échoue.
- **Intégration + câblage (#167)** : `TraversePipeline` (hystérésis → veto →
  annonceur → voix), `TraverseWiring`, adapteurs `:app`, branchement dans
  `LiveViewModel.publish`.

## 3. Le direct décide (#174)

Le veto recevait `lightGreen = false` en dur : le mode ne disait jamais
« traversez ». La PR #174 branche le classifieur :

- `crossingStateOf()` (extrait de `crossingCueOf`, sonde inchangée) :
  boîte SAM au meilleur score → crop JPEG → `TrainedLightColor` ;
- `TraverseWiring` relit le vert à chaque frame **active** et demande le
  concept `traffic light` dans les prompts (5 véhicules + feu, plafond 16) ;
- `LiveViewModel` transmet le JPEG (1 ligne, fichier maintenu à 498 lignes).
- Porte anti-confusion (`PedLightGate`) : le vert classifié ne passe que
  s'il est en bas du cadre, élancé comme un bonhomme, sans rouge franc —
  méthode sans données (priors structurels + 12 tests synthétiques JVM),
  direction sûre (tout rejet vaut « attendez »).
- Veto TTC sur boîtes SAM (`VehicleApproach`) : association IoU entre frames
  `/live` (pas d'ids serveur), vitesse lissée (`MotionModel` réutilisé),
  tau = largeur / vitesse < 3 s force le veto — en OU avec le veto
  instantané. Pas de tracking serveur (app `sam3-track` non déployée, 2e
  GPU interdite) : suivi 100 % client, zéro déploiement backend.

## 4. Ce qui tourne où (dépendances extérieures)

- **Dans l'app, hors-ligne** : couleur du feu (`TrainedLightColor`, pur JVM),
  hystérésis, veto, annonceur, voix de repli (clips + TTS système).
- **Processus extérieur** : les **boîtes d'objets** (feu + véhicules) viennent
  de SAM via le backend `/live` (GPU Modal, réseau) — sans lui, pas de boîtes,
  donc doute, donc « attendez » (défaut sûr, jamais de vert inventé). La voix
  ElevenLabs primaire est aussi réseau, avec repli cache automatique.

## 5. Preuves (28/10, `master`)

- `:core:testDebugUnitTest` + `:app:testDebugUnitTest` verts sur r630
  (2 264 tests, 0 échec à la vérification #167 ; +3 tests #174 :
  `TraverseWiringTest` 9/9, `CrossingWiringTest` 3/3 après refactor).
- UBS : 0 finding nouveau (l'exit 2 sur `CrossingWiring.kt` est un faux
  positif d'outil prouvé pré-existant sur master vierge).
- Limites : fichiers < 500 lignes, fonctions < 50 (`check-kotlin-limits` OK).

## 6. Reste à prouver (bloquant pour un usage réel)

1. **Calibration FR** : l'arbre est entraîné sur feux allemands (Ampel),
   jamais validé sur feux français ni sur appareil ; seuils non calibrés
   jour/nuit/pluie. Corpus téléphone + seuil bloquant sur les faux verts.
2. **Terrain** : aucun test téléphone — crop réel, voix sur appareil, veto
   avec vraies distances, à valider sur build du HEAD.
3. **Anti-confusion feu voiture / feu piéton** : porte structurelle en
   place (`PedLightGate`, prouvée sur pixels synthétiques) — validation sur
   feux réels et crops serrés reste due avec le corpus.
4. **Rappel SAM** sur feux piétons FR jamais mesuré (inconnue n° 1).
