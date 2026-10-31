# Recette — feux piétons FR (matrice A1-A5)

Fiche relue dans le **code de cet arbre** (base `origin/master` `4b8d89d9`,
31/10/2026).
⚠️ **Recette non rejouée sur appareil** : aucun téléphone n'était piloté
d'ici, aucune commande `adb` n'a été lancée. Ce qui suit décrit ce que le
**code** fait ; le comportement face à un vrai feu FR reste **non mesuré**.

Périmètre : feux piétons **français**, **de jour uniquement** (nuit et pluie
sorties le 29/10, `docs/CALIBRAGE-FEU.md` §9). État **binaire** rouge/vert
depuis le 31/10 (`docs/TRAVERSEE.md` §9) : tout non-vert attend rouge.

## Matrice d'acceptation A1-A5

| # | Axe | Observable promis par le code | Source |
|---|---|---|---|
| A1 | Détection | Feu piéton vu par SAM (`pedestrian traffic light`, rappel 87,5 % sur 16 photos) ; face au feu, le cadre + l'étiquette (`VERT`/`ROUGE`) tombent sur le caisson ; sans boîtier vu, repli haut-de-poteau (`docs/TRAVERSEE.md` §15) | `docs/MESURES-SAM3-FEUX.md` §2, `docs/TRAVERSEE.md` §7 |
| A2 | Sûreté — **0 vert sur rouge/éteint, bloquant** | Aucun feu rouge ni éteint n'est annoncé vert — ni à la voix, ni en vibration, ni à l'overlay. Un seul vert sur rouge = recette **refusée**. Disques voiture et boîtiers : admis depuis le 31/10 (ordre user, détection au plus large, A2b levé — seule la non-demande des prompts voiture garde) | `PedLightGate`, `docs/TRAVERSEE.md` §9 (fuzz anti-inversion) |
| A3 | Rappel vert ≥ 50 % — **provisoire** | Au moins un feu vert réel sur deux est annoncé « Vert, traversez. ». Seuil provisoire en attendant la collecte (état actuel : 1/6 sur crops SAM) ; il monte sur données (`docs/COLLECTE-FEUX.md`), pas par réglage de la porte — exceptions 31/10, ordre user : plancher 0,004 → 0,002 (lointains), élancement 1,5 → 1,0 (rappel max), fuzz anti-inversion exigé vert | `docs/MESURES-SAM3-FEUX.md` §6 |
| A4 | Sorties confirmées | Annonce dès la 1re frame verte ; voix, vibration et overlay disent la même chose ; un véhicule qui approche force « attendez » même sur vert (veto) | `docs/TRAVERSEE.md` §2 (veto), §9 (sorties confirmées) |
| A5 | Domaine jour | Recette de jour, face au feu à distance de traversée. Nuit et pluie hors verdict ; contre-jour noté au procès-verbal, non bloquant (un feu à contre-jour de jour est aussi sombre qu'un feu de nuit) | `docs/CALIBRAGE-FEU.md` §9, `docs/MESURES-SAM3-FEUX.md` §9 |

A2 est le seul axe bloquant à zéro : un vert sur rouge ne se négocie pas. A3 est
provisoire par construction — le fail-safe intact vaut mieux qu'un vert
inventé, mais un mode qui ne dit jamais « traversez » ne sert à rien.

## Prérequis

- Build du HEAD `origin/master` (`git ls-remote origin master` comparé au
  SHA du build — règle ISEE-TEST-MASTER-SEUL), APK installé sur le Redmi
  `2c6852ac` **via le verrou** `phone` (libre = utilise, occupé = attends ;
  prise/libération annoncée dans `BOITE-USER.md`).
- Réglages iSee : voix renseignée, mode Traversée armable (auto sur passage
  proche ou manuel).
- Terrain : **France**, **de jour**, 3+ carrefours à feux piétons variés
  (centre dense, résidentiel, LED + lampes), à pied face aux feux.
- Backend `/live` joignable (GPU) : sans boîtes SAM, pas de lecture — rouge
  par défaut, la recette ne peut pas se jouer.

## Recette (une prise par carrefour, ~10 min chacun)

1. **Viser un feu rouge.** Face au feu, mode Traversée actif. Attendu :
   « Rouge, attendez. », pastille et cadre rouges sur le caisson. Noter
   la phrase exacte + l'heure. Capture écran.
2. **Attendre le vert.** Sans bouger. Attendu : « Vert, traversez. »
   dès la 1re frame qui suit le basculement réel (A4), cadre vert.
   Si le vert réel reste annoncé rouge : noter (A3), pas d'échec
   sauf sous le seuil de 50 % en fin de prise.
3. **Feu voiture à côté.** Quand un disque voiture vert est dans le champ
   près du feu piéton : noter s'il fait annoncer vert à la place du
   piéton (A2b, admis depuis le 31/10 — compter, pas refuser).
   Phrase exacte + heure.
4. **Veto.** Sur vert piéton, quand une voiture approche ou passe devant :
   attendu « attendez » (le veto coupe le vert). Phrase exacte + heure.
5. **Contre-jour (noté, non bloquant).** Si un feu est à contre-jour :
   noter l'annonce vs la vérité terrain, sans verdict bloquant (A5).

Chaque point : noter le SHA du build, l'heure, et l'observable (phrase
exacte entendue + texte écran + vérité terrain vue). Tout écart au tableau
A1-A5 part en **issue GitHub**, pas en commentaire. **Un vert sur rouge
(A2) arrête la recette : refusée.**

## Checklist vierge (une ligne par item : observable + SHA + verdict)

| # | Observable | SHA build | Heure | Verdict |
|---|---|---|---|---|
| A1 | Cadre + étiquette sur le caisson du feu visé | | | |
| A1b | Carrefours couverts (≥ 3, variés) : … | | | |
| A2 | 0 faux vert voix/vibration/overlay (**bloquant**) | | | |
| A2b | Disque voiture vert : admis depuis le 31/10 (A2 levé, noter les occurrences ; veto par recouvrement prêt mais inactif, wording à mesurer) | | | |
| A3 | Verts réels annoncés verts : …/… (≥ 50 %) | | | |
| A4 | Annonce dès la 1re frame, une seule voix (voix = vibration = overlay) | | | |
| A4b | Veto : voiture qui approche force « attendez » sur vert | | | |
| A5 | De jour, face aux feux ; contre-jour noté : … | | | |

## Première prise

**Non rejouée** : pas d'appareil piloté d'ici. La checklist ci-dessus
est prête ; la première prise l'archive (procès-verbal + captures) et
remplit chaque verdict — aucun n'est pré-rempli ici.

## Ce qui est vérifié sans appareil (tests JVM)

Comptés sur cet arbre (`@Test` par fichier) :

- `PedLightGate` + calibration : `PedLightGateTest`, `PedLightGateFuzzTest`
  (~230 assertions anti-inversion, graines fixées), `TrainedLightColorTest`,
  corpus `core/src/test/resources/feuxfr/` (9 crops, verdicts `ATTRIBUTION.md`).
- Chaîne traversée : `TraverseStateTest` (hystérésis), `TraverseVetoTest`,
  `TraverseVoiceTest`, `TraversePipelineTest`, `TraverseWiringTest`,
  `CrossingWiringTest`, `CrossingOverlayLayoutTest` (placement du cadre).
- Binaire + voix : `CrossingTextTest` (phrases exactes), `CrossingAnnouncerTest`
  (1 frame), `CrossingPeopleVoiceTest`, `VehicleStateWatcherTest`.

## Non mesuré (à ne pas vendre comme vérifié)

- **La recette n'a pas été rejouée sur appareil** : non mesuré.
- Rappel vert réel sur crops téléphone FR (1/6 sur crops SAM web,
  `docs/MESURES-SAM3-FEUX.md` §6) : la collecte (`docs/COLLECTE-FEUX.md`)
  n'a pas commencé, 0 crop terrain.
- Cadre tombant juste sur un téléphone réel (testé en JVM sur cadres
  simulés, `docs/TRAVERSEE.md` §7).
- Veto avec vraies distances et voix sur appareil : non rejoué.
- Contre-jour, poses larges, feux FR LED vs lampes : non couverts.
