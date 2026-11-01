# Recette — distances : protocole terrain au mètre ruban

But : remplacer le « ±15 à 20 % non mesuré » de `docs/DISTANCES.md`
(estimation d'ingénierie) par un chiffre **mesuré au mètre ruban**, sur
téléphone réel, en plein jour. Ce document est le **protocole seul** :
aucune mesure n'y est inventée, les cases de relevé restent vides jusqu'au
passage terrain.

Hors sujet : la formule sténopé elle-même, déjà couverte par la caméra
virtuelle exacte (`DistanceBenchmarkTest` : aller-retour au centimètre,
enveloppe de quantification à ±1 px). Ici on ne mesure que l'écart
terrain — hauteur réelle supposée contre objet réel.

## Conditions imposées

- **Lumière** : jour, extérieur ou pièce très claire. Pas de nuit, pas de
  contre-jour franc (le masque flotte et la boîte ne mesure plus rien).
- **Champ de vision** : Réglages → « Champ de vision caméra (°) » à
  **0**, c'est-à-dire lecture du capteur (voie normale). Noter dans le
  procès-verbal si le driver était muet et qu'une valeur a dû être saisie.
- **Bascule** : « Distances sur le direct » activée (défaut).
- **Cadrage** : objet **entier et debout** (ou dans sa posture nominale),
  entièrement dans le cadre, sans toucher les bords haut ni bas — un objet
  coupé n'affiche rien par construction (`Pinhole.isVerticallyCut`) et ne
  donne pas un relevé, il donne une absence à noter comme telle.
- **Stabilité** : attendre que la pastille se stabilise (lissage par piste),
  relever la valeur affichée telle quelle, avec son « ≈ ».

## Objets et distances

10 objets × 6 distances = 60 relevés. Distances vraies au mètre ruban :
1 m, 2 m, 3 m, 5 m, 10 m, 20 m (caméra → objet, au sol).

| Objet | Hauteur supposée par `KnownSizes` | Remarque de visée |
|---|---|---|
| personne debout | 1,70 m | debout, de face ou de profil, entière |
| personne assise | 1,25 m | assise droite, dossier inclus |
| enfant | 1,20 m | debout, entier |
| voiture | 1,45 m | de profil, tourisme |
| porte | 2,05 m | fermée, de face |
| chaise | 0,90 m | vide, de profil |
| chien | 0,55 m | debout, immobile si possible |
| borne | 0,80 m | borne ou plot, verticale |
| arbre | 4,00 m | tronc et houppier entiers dans le cadre |
| panneau | 2,20 m | panneau seul, de face |

L'arbre à 1 m et 2 m ne rentre pas entier dans le cadre : ces deux cases
restent « non applicable » (noter `n/a`, pas un chiffre). Même règle pour
tout couple objet et distance impossible à cadrer entier.

## Déroulé

1. Poser le mètre ruban au sol depuis le téléphone, marquer la distance.
2. Placer ou viser l'objet à la marque, entier dans le cadre.
3. Attendre la pastille stabilisée, relever : objet, vraie distance,
   distance affichée, confiance (voir fiche).
4. Répéter pour les 6 distances, puis changer d'objet.
5. Saisir les relevés en CSV (point décimal, voir format) et lancer le
   script de vérification (`scripts/mesure-distances.sh`).

## Fiche de relevé

Une ligne par relevé. La confiance est la confiance interne de la lecture
(`ReadingConfidence`, 0 à 1) : elle n'est pas affichée sur la pastille,
la relever depuis le journal si elle y est exposée, sinon noter `-`.
Le CSV vérifié par le script ne retient que les trois premières colonnes.

| objet | vraie (m) | affichée (m) | confiance |
|---|---|---|---|
| (vide — à remplir sur le terrain) | | | |

Format CSV accepté par le script (séparateur virgule, point décimal,
en-tête `objet,vraie_m,affichee_m` tolérée) :

```
objet,vraie_m,affichee_m
personne debout,2.0,2.1
porte,5.0,4.8
```

## Seuils de passage

Erreur relative d'un relevé : `(affichée − vraie) / vraie`, en %.

- **biais médian** (médiane des erreurs signées) : strictement sous 10 % ;
- **p90** (90ᵉ percentile des erreurs absolues) : strictement sous 25 % ;
- **0 valeur absurde** : aucun relevé au-delà du double de la vraie
  distance (affichée > 2 × vraie), ni aucune distance à zéro ou négative
  (refusée par le script avant tout calcul).

Le script imprime les trois chiffres, les pires cas et le verdict :

```
bash scripts/mesure-distances.sh releves.csv
```

Recette **passée** si le script sort 0. S'il sort 1, le procès-verbal
garde ses chiffres tels quels et la cause est cherchée côté hauteurs
supposées ou cadrage, jamais en retouchant les relevés.

## Après le passage

Reporter dans `docs/DISTANCES.md` le biais médian, le p90 et le nombre de
relevés mesurés, en remplaçant la mention « non mesuré » — travail de
constat, pas de protocole, donc hors de cette fiche.

## Variante lunettes (même protocole, 3 substitutions)

Objets, distances, déroulé, seuils et script : inchangés. Seuls changent :

- **Conditions** : lunettes portées, tête droite ; Réglages → « Champ de
  vision lunettes (°) » — noter au procès-verbal le repli estimé (0) ou la
  valeur saisie/mire active ;
- **Déroulé** : mètre ruban posé au sol **depuis les lunettes portées**
  (pas depuis le téléphone, qui reste en poche) ;
- **Après** : reporter vers la section « Lunettes cibles » de
  `docs/DISTANCES.md` (mêmes 3 chiffres + focale mesurée si mire).

Variante allégée admise pour un premier passage : ±10 % sur objet connu à
3 distances (`LUNETTES-RECETTE-TEL.md` § 6).
