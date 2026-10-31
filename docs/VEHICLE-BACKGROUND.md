# Véhicule / fond — qui bouge ? (V1)

Règle produit (résidu = boîte − fond, en fractions d'image/s) :

- **Le résidu est franc** ⇒ **la voiture bouge vraiment**.
- **Le résidu est nul mais le fond défile** ⇒ **la caméra bouge**
  (la boîte suit le porteur, la voiture est à l'arrêt).
- **Les deux sont faibles** ⇒ **arrêt** ; doute (fond plat, basse
  confiance) ⇒ silence, jamais d'arrêt inventé.

## V1 : boîte compensée du mouvement global

Le mouvement propre de la boîte est estimé en lui **retranchant le
mouvement global du fond** (translation médiane entre deux frames) :
un objet fixe dans un fond qui défile ne doit pas être déclaré mobile,
et une boîte qui s'écarte du fond signale un véhicule en mouvement
(même poursuivi par la caméra : une boîte fixe sur fond qui défile
veut dire que le véhicule compense le mouvement du porteur).

## Seuils initiaux (à calibrer)

| Seuil | Valeur | Rôle |
|---|---|---|
| `TRUST_MIN` | 0,12 | Confiance min. du mouvement global pour décider |
| `STILL_SPEED` | 0,02 | Sous ce résidu (fraction d'image/s), l'objet est à l'arrêt |
| `LATERAL_RATIO` | 0,03 | Au-delà (fraction d'image/s), résidu ou fond franc |

TODO : calibrer ces trois seuils sur séquences réelles (rue, parking,
embouteillage) avant de les figer.

## Vérifications

```bash
./gradlew :core:testDebugUnitTest --offline
python3 tools/check-kotlin-limits.py
```
