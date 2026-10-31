# Datasets publics de feux — matrice et recommandation (31/10/2026)

Besoin : reconnaître les **feux piétons français** (traversée iSee). Le rappel
vert est à 1/6 sur crops SAM réels (`docs/MESURES-SAM3-FEUX.md` §6) et aucun
seuil ne le répare : il faut des données du domaine. Cette page répond à une
seule question — *un dataset public peut-il servir ?* — puis renvoie à la
boucle de collecte (`docs/COLLECTE-FEUX.md`), qui reste la voie normale et
n'est pas dupliquée ici.

## 1. Matrice (accès et licences vérifiés le 31/10/2026, sans téléchargement)

| Dataset | Contenu / pays | Accès | Licence | Verdict |
|---|---|---|---|---|
| **PTL** (ImVisible, Yu et al. 2019) | 5 059 images feux **piétons** + passages piétons ; classes rouge / vert / compte à rebours / rien | Annotations dans le dépôt GitHub, images sur Google Drive (3 résolutions) | Code MIT, **dataset sans licence explicite** (citation demandée) | Le plus proche du besoin, mais domaine non-FR (compte à rebours, caissons non-FR) et flou juridique → comparaison recherche seulement |
| **PTLR** (Cheng, Chine/Italie) | 1 135 images feux piétons | **Page de téléchargement en 404** le 31/10 | Inconnue | Écarté (inaccessible) |
| **DTLD** (DriveU, Ulm, DE) | 40 000+ images, 230 000 feux annotés, 11 villes allemandes, attributs (phase, pictogramme, pertinence…) | Inscription + lien envoyé par e-mail | **Recherche/enseignement uniquement**, commercial et redistribution interdits, citation ICRA 2018 exigée | Écarté (licence incompatible prod + domaine DE véhiculaire) |
| **LaRa** (La Route Automatisée, FR) | ~11 000 images, ~9 000 annotations, 640×480, un seul trajet à **Paris** (2009-2010) | **Site officiel mort** (DNS en échec le 31/10), liens historiques uniquement | Non spécifiée | Écarté (inaccessible, véhiculaire, obsolète) — le seul FR, inutilisable |
| **BSTLD** (Bosch) | Feux **véhiculaires**, plusieurs milliers d'images (~6 Go) | Formulaire + lien e-mail (Heidelberg) | **Non commercial uniquement** (licence propre) | Écarté (licence + véhiculaire) |
| **LISA** (UCSD, San Diego) | Séquences vidéo de conduite, feux **véhiculaires** | Site historique + miroir Kaggle | **Académique**, citation requise | Écarté (US véhiculaire + académique) |
| **S2TLD** (SJTU, CN) | 5 786 images, 14 130 instances, 5 catégories (rouge, jaune, vert, éteint, attente) | GitHub public + Hugging Face | **MIT** | Seule licence prod-compatible, mais feux véhiculaires chinois → pas un corpus FR (appoint dev éventuel, §3) |

## 2. Sources

- PTL : [samuelyu2002/ImVisible](https://github.com/samuelyu2002/ImVisible)
  (README : 5 059 images, classes, liens Drive ; `LICENSE` MIT ne couvre que
  le code, rien sur les images).
- PTLR : [wangkaiwei.org/downloadeg.html](http://wangkaiwei.org/downloadeg.html)
  (404 le 31/10 ; cité par
  [PTL-Crosswalk](https://github.com/ronaldosm/PedestrianTrafficLightsAndCrosswalkDetection)).
- DTLD : [page du dataset](https://www.uni-ulm.de/en/in/institute-of-measurement-control-and-microtechnology/research/data-sets/driveu-traffic-light-dataset/)
  et [formulaire d'inscription](https://www.uni-ulm.de/en/in/institute-of-measurement-control-and-microtechnology/research/data-sets/driveu-traffic-light-dataset/registration-form-dtld/)
  (Terms of Use cités en matrice).
- LaRa : [lara.prd.fr](http://www.lara.prd.fr/benchmarks/trafficlightsrecognition)
  (DNS mort le 31/10) ; description reprise dans
  [l'état de l'art TLR](https://arxiv.org/abs/2309.02158).
- BSTLD : [hci.iwr.uni-heidelberg.de](https://hci.iwr.uni-heidelberg.de/content/bosch-small-traffic-lights-dataset)
  (« released explicitly for non-commercial use only »).
- LISA : [défi VIVA](http://cvrr.ucsd.edu/vivachallenge/index.php/traffic-light/traffic-light-detection/)
  et [miroir Kaggle](https://www.kaggle.com/datasets/mbornoe/lisa-traffic-light-dataset).
- S2TLD : [Thinklab-SJTU/S2TLD](https://github.com/Thinklab-SJTU/S2TLD)
  (MIT, confirmé par [Dataset Ninja](https://datasetninja.com/s2tld)).

## 3. Recommandation : collecte FR propriétaire

**Aucun dataset public ne couvre les feux piétons FR avec une licence
compatible prod.** Les deux pièges sont disjoints : les corpus piétons
(PTL, PTLR) sont hors-FR ou inaccessibles, et les corpus à licence claire
(S2TLD MIT) sont véhiculaires et chinois. D'où :

1. **Corpus d'entraînement = collecte propriétaire** via
   `docs/COLLECTE-FEUX.md` (crops téléphone ≤ 48 px vus par le classifieur,
   cible 200 crops étiquetables, vie privée : crops seuls, jamais la scène).
2. **Corpus de validation = les 235 lampes Panoramax IGN**, hors dépôt
   (déjà cité par `docs/COLLECTE-FEUX.md` §5).
3. **Appoint éventuel, jamais vérité FR** : S2TLD (MIT) comme négatifs ou
   pré-entraînement véhiculaire, PTL comme point de comparaison recherche —
   tous deux à re-valider sur crops FR avant tout usage dans une barrière GO.

## 4. Méthode

Matrice établie le 31/10/2026 sur les documentations publiques (pages,
README, formulaires cités au §2). **Aucune image téléchargée**, aucune
inscription faite : les verdicts « accessible » reposent sur les pages
publiques, pas sur un rapatriement. Accès à re-vérifier avant tout usage
(les liens 2009-2019 meurent vite : PTLR et LaRa en témoignent).
