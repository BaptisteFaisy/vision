# MASK-DECODE — décodage client des masques du direct

Comment le téléphone relit un masque de la réponse `/live` (`/live/ws` inclus), pour les
quatre formats du codec masques. Le contrat serveur est celui de la lane L11 : `png`
(historique), `bits` (format `M1`), `rle` (runs LEB128), `poly` (anneaux entiers), plus
`none` (aucun masque, l'écran dessine la boîte).

Tout le décodage pur vit dans `core/src/main/java/dev/sam3vision/core/mask/` et se teste sur
JVM, sans Android : seul le coloriage final touche `android.graphics.Bitmap`.

## 1. Requête — les knobs du codec

`liveFrameJson` (`core/net/Json.kt`) porte **le corps historique inchangé**
(`image_b64`, `prompts`, `work_edge`, `max_objects`, `mask_encoding: "bits"`) et y ajoute,
seulement quand ils sont demandés, les deux knobs du codec :

| Champ | Type | Sens |
|---|---|---|
| `mask_format` | chaîne | `png`, `rle` ou `poly` ; **absent** ⇒ corps d'avant, `mask_encoding` seul |
| `mask_edge` | entier | côté long de la grille réduite (`256`), `0` ⇒ pleine résolution |

Les valeurs demandées par l'app sont **isolées dans `app/LiveMaskTransport.kt`**
(`MASK_FORMAT = "rle"`, `MASK_EDGE = 256`) et traversent `liveClient(...)` jusqu'à
`WsLiveClient` / `HttpLiveClient`. Revenir au corps historique = `maskFormat = null`.

Deux ceintures, exprès :

- `mask_encoding: "bits"` reste envoyé : un backend **déployé** qui ne connaît pas encore
  `mask_format` continue de servir le format `M1` qu'il sait produire ;
- un backend à jour lit `mask_format` et sert `rle` sur 256 px. Un champ inconnu est ignoré
  des deux côtés (`extra = ignore` côté pydantic), donc **aucune valeur ne provoque de 400**.

## 2. Réponse — les champs de masque

Le champ de frame `mask_encoding` existait déjà ; le codec ajoute trois champs **par objet**
(`core/model/Models.kt`, `SegObject`) :

| Champ | Type | Sens |
|---|---|---|
| `mask_encoding` | chaîne | format **de l'objet** ; prime sur celui de la frame. Absent ⇒ celui de la frame, absent ⇒ `png` |
| `mask_scale` | flottant | **ratio grille/frame** (256 px pour une frame de 512 ⇒ `0,5`) ; absent ou `1` ⇒ masque à la taille de la frame |
| `mask_poly` | tableau | anneaux `poly` : un tableau **plat** `[x0, y0, x1, y1, …]` par anneau, refermé implicitement |

```json
{"width":512,"height":206,"mask_encoding":"rle","objects":[
  {"label":"personne","score":0.91,"box":[1,2,30,40],"mask_b64":"AgMH",
   "mask_encoding":"rle","mask_scale":0.5}]}
```

Un backend qui n'envoie aucun de ces champs garde **exactement** le décodage d'avant
(`png`, pleine résolution) : c'est ce qui rend la bascule réversible dans les deux sens.

## 3. Décodage — un seul point d'entrée

| Fonction | Sortie | Usage |
|---|---|---|
| `decodeObjectMask(obj, frameW, frameH)` | masque **dans la grille du serveur** | comparaison à un raster de référence, bancs |
| `decodeObjectGray(obj, frameW, frameH)` | masque **à la taille de la frame** | affichage (agrandissement compris) |
| `MaskPainter.overlays(frame)` (iSee) | `Bitmap` coloriés, tampons réutilisés | écrans (`LiveViewModel`, sonde) |

Chaîne : `mask_encoding` de l'objet → `rle` (`MaskRle.kt`), `poly` (`MaskPoly.kt`), sinon
`decodeMaskAny` (`Masks.kt`, `png`/`bits`/`none`). Les formats compacts se relisent sur la
grille déduite de `mask_scale` (`MaskUpscale.kt::maskGrid`), puis sont ramenés à la frame par
**plus proche voisin centré** (`upscaleNearest`) quand `mask_scale < 1`.

### `rle` — sans perte

Runs **LEB128 non signés**, ligne majeure, **premier run = fond**, niveaux alternés 0/255.
Aucune dimension dans le corps : la somme des runs doit valoir exactement
`largeur × hauteur`, sinon le masque est refusé (`null`) plutôt que dessiné de travers.
Un raster encodé puis décodé revient donc **identique au bit près**.

### `poly` — perte bornée

Anneaux entiers remplis en **even-odd** (un trou = un anneau de plus), test au **centre du
pixel** (`x + 0,5`, `y + 0,5`) et règle demi-ouverte `[y1, y2)` pour les croisements — un
sommet partagé n'est compté qu'une fois. C'est la règle du serveur : le même masque se relit
qu'il soit arrivé en `rle` ou en `poly` (`MaskPolyTest`), mais le poly est une
**approximation** (sommets simplifiés, tolérance du serveur) : il ne convient pas à un masque
bruité.

### Garde-fous

- **4 Mpx** (`MASK_MAX_PIXELS`) : grille refusée avant toute allocation, donc un en-tête
  hostile ne fait pas réserver des mégaoctets ;
- varint borné à cinq octets et refusé au-delà de la capacité d'un `Int` (pas de boucle
  infinie, pas de débordement silencieux) ;
- `mask_scale` absurde (non fini, ≤ 0, > 4) ⇒ pas de grille, donc `null` : pas de masque,
  pas de masque faux ;
- tout échec rend `null`, jamais d'exception : l'écran dessine alors la boîte.

### Agrandissement et métrique d'erreur

`upscaleNearest` produit des **blocs** à la grille réduite (256 → 512 : blocs 2 × 2) : le
masque affiché n'est pas exact au pixel. La métrique d'erreur du calque local
(`errorThreshold` 0,12, fraction de désaccord) doit donc se calculer **après** cet
agrandissement, sur la grille réellement décodée — sinon elle surévalue l'erreur.

## 4. Ce qui est prouvé, et ce qui ne l'est pas

Prouvé par les tests JVM (`core/src/test/java/dev/sam3vision/core/mask/`) :

- `MaskRleTest` — aller-retour sans perte, premier run = fond, corps tronqué ou démesuré
  refusé, base64 tolérant ;
- `MaskPolyTest` — rectangle, anneau à trou (even-odd), anneau non refermé, forme hors
  grille, égalité `poly` ≡ `rle` sur la même forme ;
- `MaskUpscaleTest` — ratio grille/frame (`512 × 206`, `0,5` ⇒ `256 × 103`), blocs 2 × 2
  centrés, garde 4 Mpx ;
- `MaskDecodeTest` — aiguillage par encodage, `mask_scale` absent ou faux ⇒ `null`, lecture
  JSON par objet, repli PNG d'un backend d'avant ;
- `MaskBenchTest` — banc de décodage 256 × 103 (valeurs imprimées, bornes larges) ;
- `LiveMaskTransportTest` (module `:app`) — les knobs demandés sont relus par le décodeur.

Mesuré sur le **poste** (JVM, `MaskBenchTest` lancé hors Gradle, 8 objets RLE 256 × 103) :
**0,013 ms par objet** RLE (0,10 ms pour les 8, soit ~0,1 ms par frame de 8 objets) et
**0,056 ms par objet** en rasterisation poly — très en dessous du budget d'une frame
(~500 ms). Ce n'est **pas** une mesure téléphone.

**Non prouvé ici** : aucun serveur du codec dans ce dépôt (il vit côté backend
`mask_codec.py`), donc l'aller-retour réel serveur → client n'est pas joué ; aucune mesure
sur téléphone ; les IoU et les poids d'octets appartiennent à la lane L11 côté serveur et ne
sont pas reproduits ici.