# Véhicule / fond — qui bouge ? (V1)

Règle produit :

- **Le fond change** (défilement global) ⇒ **la voiture bouge**.
- **Le fond est identique mais la boîte bouge** ⇒ **la caméra bouge**
  (la voiture est à l'arrêt, c'est le porteur qui se déplace).

## V1 : boîte compensée du mouvement global

Le mouvement propre de la boîte est estimé en lui **retranchant le
mouvement global du fond** (translation médiane entre deux frames) :
un objet fixe dans un fond qui défile ne doit pas être déclaré mobile,
et une boîte qui glisse sur un fond immobile signale un mouvement de caméra.

## Seuils initiaux (à calibrer)

| Seuil | Valeur | Rôle |
|---|---|---|
| `TRUST_MIN` | 0,12 | Confiance min. du mouvement global pour décider |
| `STILL_SPEED` | 0,02 | Sous ce résidu (fraction d'image/frame), l'objet est à l'arrêt |
| `LATERAL_RATIO` | 0,03 | Part latérale min. du résidu pour parler de mouvement latéral |

TODO : calibrer ces trois seuils sur séquences réelles (rue, parking,
embouteillage) avant de les figer.

## Vérifications

```bash
./gradlew :core:testDebugUnitTest --offline
python3 tools/check-kotlin-limits.py
```
