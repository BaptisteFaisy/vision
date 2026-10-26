# `:glasses-spike` dans la CI (budget 5 min)

**Relu le 26/10/2026** sur cet arbre (`BaptisteFaisy/vision`, commit unique de ce dépôt). Le module
est **vivant**, pas historique : `include(":glasses-spike")` dans `settings.gradle.kts:20`, 21 fichiers
`.kt` (14 `main`, 6 `test`, 1 `androidTest`), 2 373 lignes — le détail est dans
[`glasses-dat-spike.md`](glasses-dat-spike.md) §0.

Ce document ne décrit que ce que la CI **fait vraiment**, lu dans `.github/workflows/ci.yml` (fichier
de garde : non modifié par cette relecture).

## 1. Ce que le job `spike` exécute

```yaml
spike:                                  # ci.yml:216
  runs-on: ubuntu-latest                # :217  (runner GitHub)
  timeout-minutes: 5                    # :218
  steps: checkout (fetch-depth: 1) → setup-gradle (cache)
         ./gradlew --console=plain -Dorg.gradle.jvmargs="-Xmx2g" \
           :glasses-spike:assembleDebug :glasses-spike:testDebugUnitTest   # :227
```

C'est **tout**. Conséquences, vérifiées une par une dans le workflow :

- **Deux tâches Gradle, pas une de plus** : compilation de l'APK debug et tests JVM du module.
- **Aucun `lintDebug`, aucun `assembleRelease`** pour le spike (le job `lint` ne vise que `:app`,
  `ci.yml:189-205`). Module de preuve : budget d'abord.
- **Aucun test d'instrumentation** : `MockGlassesTest` et l'asset `mock_feed.mp4` ne sont **jamais**
  compilés ni exécutés en CI — il faudrait un appareil.
- **Pas de rapport de tests publié** : l'étape `upload-artifact` de `**/build/reports/tests/**` est
  dans le job `ci` seulement (`ci.yml:140-150`). Un échec du spike se lit donc dans les logs.
- **Aucune ressource GPU** : `grep -iE 'modal|gpu|nvidia' .github/workflows/` → 0 résultat (mesuré
  ici), donc la règle « une seule GPU Modal à la fois » n'est jamais sollicitée par un job.

## 2. Le spike n'est pas dans le job `ci`

Le job `ci` lance exactement `:core:testDebugUnitTest :app:testDebugUnitTest`
(`ci.yml:134-138`). `:glasses-spike` n'y figure pas : son verdict est porté par le job `spike`.

Les cinq jobs tournent sur chaque PR, mais **seul `ci` est un passage obligé** pour la fusion.
Donc `spike`, `apk`, `apk-release` et `lint` ne bloquent rien tant qu'aucune protection de branche
ne les déclare requis : un module cassé peut être fusionné avec `ci` vert. Le trou se comble en
mettant `spike` en check requis.

## 3. Détection de périmètre (le piège corrigé)

L'étape « Cette PR touche-t-elle du code ? » (`ci.yml:38-71`) liste les fichiers par l'API GitHub,
ignore tout ce qui est `.md`/`.txt`, puis applique (`ci.yml:68`) :

```
^(app|core|glasses-spike)/|^gradle/|^gradle\.properties$|^settings\.gradle\.kts$|^build\.gradle\.kts$|^tools/
```

Deux propriétés à retenir :

- **tout** ce qui vit sous un module compte (manifeste, `res/`, règles ProGuard), pas seulement les
  `.kt` : sinon un manifeste modifié passerait pour « sans code » et Gradle ne tournerait pas ;
- une PR **de documentation seule** saute les étapes Gradle du job `ci` — mais le job `spike`, lui,
  n'a **pas** cette condition : il compile et teste à chaque PR, même de doc.

## 4. Coût

| Mesure | Valeur | Statut |
|---|---|---|
| `:glasses-spike:testDebugUnitTest` (56 tests) | **8 s**, dont **0,09 s** de tests | mesuré ici, cache Gradle chaud |
| `:glasses-spike:assembleDebug` | **15 s** (tout en cache après le premier run) | mesuré ici |
| job `spike` entier sur GitHub (checkout + cache + les deux tâches) | **50 s** | mesuré en CI le 26/10/2026 (run `40378091587`, job `121766568326`) |

Les autres jobs du même run, pour situer le spike : `ci` 1 min 52, `apk` 1 min 35, `apk-release` 1 min 29,
`lint` 1 min 29 — tous sous le budget de 5 min. Le spike est donc le job le moins cher du lot.

Tailles des AAR `mwdat-*` **1.0.1**, mesurées ici sur le cache Gradle (et non plus estimées) :

| Artefact | Octets |
|---|---|
| `mwdat-core-1.0.1.aar` | 8 979 777 |
| `mwdat-camera-1.0.1.aar` | 784 799 |
| `mwdat-mockdevice-1.0.1.aar` | 871 060 |
| **somme des trois (spike)** | **10 635 636 ≈ 10,64 Mo** (10,14 Mio) |

Le quatrième artefact, `mwdat-inputs` (146 878 o), n'est demandé que par `:app`. Les « ~11 Mo de `.so`
à empaqueter » dans l'APK restent **non vérifiés** : il faudrait dépaqueter les AAR, donc un build
dédié.

## 5. Garde de taille Kotlin

`tools/check-kotlin-limits.py` parcourt la racine en sautant `{.git, .gradle, build, .idea, tmp,
node_modules, __pycache__, .venv, venv}` : `glasses-spike/` est donc contrôlé comme le reste du
dépôt. Relancé ici (lecture seule, pas un build) :

```
python3 tools/check-kotlin-limits.py   →   OK — aucun dépassement
```

Dans le module, le plus gros fichier est `GlassesVoice.kt` (253 l.) et la plus grosse fonction `play`
(43 l.) : les deux limites (500 / 50) ont de la marge.

## 6. Budget 5 min et garde-fous

- `.github/ci-budget/check_timeouts.py` refuse tout job sans `timeout-minutes` ou au-delà de
  **5 min** ; les cinq jobs de `ci.yml` déclarent `timeout-minutes: 5`.
- `concurrency: ci-${{ github.workflow }}-${{ github.ref }}` + `cancel-in-progress: true`
  (`ci.yml:13-15`) : un nouveau push annule le run précédent de la même branche.
- Tous les `checkout` sont en `fetch-depth: 1` ; le cache Gradle vient de
  `gradle/actions/setup-gradle@v4`.
- Un seul appel Gradle par job : découper davantage multiplierait checkout et configuration, que le
  budget de 5 min ne permet pas.

## 7. Si le spike grossit

Tant que le module reste sous la minute, une seule étape Gradle suffit. Le jour où il faut du lint ou
des tests d'instrumentation, la sortie propre est un job dédié (`glasses`) avec `needs: ci` et
`timeout-minutes: 5` — on n'allonge **jamais** le timeout de `ci` (garde `ci-budget` : un job au-delà
de 5 min fait échouer la PR).
