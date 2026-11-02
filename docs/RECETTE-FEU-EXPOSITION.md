# Recette — exposition du feu (45 min, téléphone)

Date : 2026-11-02 · Lot W8 · Code : `core/exposure/{ClipStats,ClipGovernor,FireMetering,AwbLockPolicy}`,
`app/live/LiveSensorControl.kt`, `app/LiveScreen.kt`, `app/isee/{CrossingPixels,CrossingLightTap,LightCropLogger,LightExposureSignal}.kt`.

But : vérifier **sur appareil** que l'écrêtage de la lampe est bien mesuré, que l'exposition baisse
là où il faut, et que **aucun vert sur rouge** n'apparaît. Ce document ne mesure pas le confort,
il mesure trois choses et une invariante.

> Périmètre : téléphone seul. En traversée avec lunettes, la frame vient du flux `mwdat` : aucune
> API d'exposition ne s'y applique (voir `docs/CALIBRAGE-FEU.md` §limites).

---

## 1. Ce qui a changé, et ce que chaque drapeau doit produire

| étape | ce qui tourne | drapeau | défaut | preuve attendue sur le téléphone |
|---|---|---|---|---|
| 0 | antibanding 50 Hz + AF continu sur le chemin du feu | — (toujours) | — | logcat `réglage capteur refusé` **absent** ; plus de bandes qui battent sous un LED |
| 1 | compteur d'écrêtage du crop de lampe | — (toujours) | — | champ `burn` dans `lightcrops-*.jsonl`, log `feu:` avec un pourcentage |
| 2 | gouverneur EV sur la **boîte du feu** | `EV on the light box` | **allumé** | `feu: EV 0 -> -1 (blancs écrasés 4,1 %)`, 2 crans au plus, retour à 0 après 3 s sans feu |
| 3 | verrou AWB temporisé + région AE 2 points | `AWB lock + AE region (A/B)` | **éteint** | `feu: verrou AWB posé` puis `relâché`, `feu: région AE lampe (x, y) + plein cadre` |

Les deux drapeaux sont **runtime** (Réglages → TECHNICAL SETTINGS → *Fire exposure*), **non
persistés** : ils retombent à leur défaut au redémarrage. Aucun rebind de caméra n'est nécessaire,
la bascule s'applique à la frame suivante.

**Ce que fait le gouverneur EV, exactement** — pour savoir quoi observer : il lit la part de pixels
« canal max ≥ 250 **et** saturation < 0,15 » dans le crop de la lampe (celle que le classifieur
perd : ni franche, ni délavée). Au-delà de **2 %** du crop, il descend d'un cran (−1 EV), attend
**700 ms** que l'AE converge, relit, et redescend si c'est encore cramé — **deux crans au plus**
même si le téléphone offre −6. Sous **0,6 %** (hystérésis), il remonte vers l'automatisme, et il y
retourne seul après **3 s** sans boîte de feu. Ces seuils viennent de la mesure des 13 crops
téléphone du dépôt (`core/src/test/resources/feuxfr3/`) : 0 % à 4,84 %, moyenne 1,08 %.

---

## 2. Prérequis (5 min)

1. Build debug du lot, installé : `sam3vision`. Mode **Traversée** actif (c'est la seule branche
   qui lit un feu, donc la seule qui publie une mesure).
2. Réglages → TECHNICAL SETTINGS → **Collect light crops** : allumé (on veut `burn` dans le JSONL).
3. Logcat filtré, dans un terminal : `~/bin/phone-adb -s <tel> logcat -s sam3vision | grep feu:`
4. `~/bin/phone-adb` **jamais** `adb` nu (cf. `docs/LUNETTES-LANCEMENT.md`) — plusieurs téléphones
   sont branchés sur cette machine.

---

## 3. Protocole (45 min)

Trois scènes, **30 s** chacune, une seule variable à la fois. Tenir le téléphone à hauteur d'œil,
feu dans le cadre, à ~4-6 m (distance de décision).

| # | scène | ce qu'on cherche |
|---|---|---|
| S1 | plein soleil, feu **rouge** allumé | l'écrêtage maximal : c'est là que la lampe perd sa teinte |
| S2 | ombre / rue étroite, feu **vert** | le rappel : le vert doit rester vert, et rester vert |
| S3 | contre-jour, feu **rouge** | le cas où le halo blanc domine |

**Passe A — référence (10 min).** `EV on the light box` **coupé**, `AWB lock + AE region` coupé.
S1, S2, S3, 30 s chaque. On garde la trace : `logcat -s sam3vision > A.log` (le grep `feu:` ne doit
rien contenir, c'est le but), et le fichier `lightcrops-*.jsonl` de la session.

**Passe B — EV seul (10 min).** `EV on the light box` **allumé**, AWB toujours coupé. Mêmes trois
scènes, même durée. On attend : des lignes `feu: EV 0 -> -1`, puis `-2` au plus, et un `burn` qui
**baisse** dans le JSONL entre le début et la fin de chaque scène.

**Passe C — EV + AWB/AE (10 min).** `AWB lock + AE region` **allumé** aussi. On attend, en plus :
`feu: verrou AWB posé` ~1 frame après la première boîte, `feu: région AE lampe (…) + plein cadre`
au plus toutes les 300 ms, puis `verrou AWB relâché` 3 s après que le feu est sorti du cadre.
**Vérifier le relâchement** : sortir le feu du cadre, compter 3 s, la ligne doit tomber. Pas de
ligne = le HAL a ignoré `CONTROL_AWB_LOCK` (c'est une information, pas une panne) ou la boîte est
restée vue.

**Vérification finale (5 min).** Les 13 ancres du dépôt doivent rendre les mêmes verdicts qu'avant
(aucun test ne change) : `python3 tools/check-kotlin-limits.py .` puis les tests JVM
(`ClippingTest`, `ClipGovernorTest`, `FireMeteringTest`, `AwbLockPolicyTest`, `PedLightGateTest`,
`LightCropLoggerTest`) — 0 échec attendu.

---

## 4. Ce qu'on relève, et ce qui invalide la recette

| mesure | où | attendu | invalide si |
|---|---|---|---|
| part écrasée en blanc (`burn`) | `lightcrops-*.jsonl`, champ `burn` (0..1) | baisse entre le début et la fin de S1/S3 en passe B | `burn` identique à la passe A : le gouverneur ne fait rien (vérifier `feu: EV …`) |
| crans appliqués | logcat `feu: EV a -> b` | 1 à 2 crans, jamais plus, jamais au-dessus de 0 | plus de 2 crans, ou EV qui ne revient pas à 0 après 3 s sans feu |
| verrou AWB | `feu: verrou AWB posé/relâché` | posé en passe C, relâché 3 s après la sortie du cadre | posé puis jamais relâché (teinte figée = régression) |
| région AE | `feu: région AE lampe (…) + plein cadre` | ≤ 1 par 300 ms, et le log `refusée` est absent | `refusée` en boucle (HAL sans support : garde `CONTROL_MAX_REGIONS_AE ≥ 2`) |

**Invariante du dépôt, non négociable : 0 vert sur rouge.** Un seul verdict vert à vérité terrain
rouge, toutes passes confondues, et le lot **ne part pas** — les drapeaux se coupent, pas le code.
Ce qui compte ensuite est le **rappel** : nombre de feux verts réellement lus, passe A contre passe C,
à vérité terrain visuelle. Objectif : ≥ 50 % des échecs « lampe blanche » récupérés ; on ne le sait
qu'après avoir comparé les `burn` des crops dont le verdict a changé.

Les deux réserves à garder en tête en lisant les chiffres : l'écrêtage mesuré est celui du **JPEG
q65 480 px** (le capteur sature plus tôt, jamais plus tard, donc les pourcentages sont des
minorants), et une image déjà écrêtée ne se « dé-écrête **pas** » — aucun corpus du dépôt ne peut
donc simuler le gain, d'où cette recette.

---

## 5. Rejouer / dépouiller

```bash
# 1. trace de la passe en cours (les deux filtres, sans adb nu)
~/bin/phone-adb -s <tel> logcat -s sam3vision | grep -E "feu:|réglage capteur"

# 2. rapatrier les crops de la session (chemin exact imprimé par le logcat LightCrop)
~/bin/phone-adb -s <tel> pull /data/data/dev.sam3vision.app/files/lightcrops OUT/brut/

# 3. burn par crop, sans PNG : une ligne par crop, triable
python3 - <<'PY'
import json,glob
for f in sorted(glob.glob('OUT/brut/*.jsonl')):
    for l in open(f):
        d=json.loads(l)
        print(f"{d['burn']:.4f} {d['verdict']:5s} {d['box']}")
PY
```

Pour rejouer une comparaison : mêmes trois scènes, mêmes durées, un seul drapeau change. Un second
téléphone (autre HAL) est une **autre** recette : `CONTROL_AWB_LOCK` et le métérage sont annoncés
par la plateforme, pas garantis par elle.

## 6. Rollback

Réglages → TECHNICAL SETTINGS → *Fire exposure* : couper les deux interrupteurs, ou simplement
redémarrer l'app (les drapeaux ne sont pas persistés). Le code reste en place mais inerte ; l'étape 0
(antibanding + AF) reste active, elle ne touche pas à l'exposition.
