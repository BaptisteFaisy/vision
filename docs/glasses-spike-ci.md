# Le banc lunettes dans la CI (budget 5 min)

**Réécrit le 2026-10-27.** Le module `:glasses-spike` et son job `spike` n'existent plus : le
banc d'essai est compilé et testé **avec `:app`**. Ce fichier garde son nom pour ne pas casser
les liens ; il ne décrit plus un module. Décision et chemins :
[`GLASSES-DECISION.md`](GLASSES-DECISION.md).

## 1. Ce qui a disparu

`.github/workflows/` ne contient **aucune** occurrence de `glasses-spike` (mesuré le 27/10 par
`grep -rn glasses-spike .github/workflows/` → 0 ligne). Conséquence directe : il n'y a plus de
job qui compile `:glasses-spike:assembleDebug` ni `:glasses-spike:testDebugUnitTest`.

L'ancien job, pour mémoire datée (relevé du 26/10/2026, run `40378091587`, job
`121766568326`) : **50 s** au total pour les deux tâches Gradle, sous le budget de 5 min. Ce
coût disparaît avec le module, mais le **travail** ne disparaît pas — il se déplace dans les
jobs de `:app` (§2).

## 2. Où le banc est vérifié maintenant

`.github/workflows/ci.yml` déclare **4 jobs** (mesuré : `grep -n '^  [a-z-]*:' ci.yml`), chacun
borné à **5 minutes** :

| Job | Ligne | Ce qu'il fait pour le banc |
|---|---|---|
| `ci` | `:26` (`timeout-minutes: 5` `:29`) | `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest` (`:148`) → les **84 annotations `@Test`** de `app/src/test/.../bench/` tournent là, comme les tests de `:app` |
| `apk` | `:164` | `:app:assembleDebug` (`:174`) → compile le banc, y compris `MockGlassesHarness` et le mock (`debugImplementation`) |
| `apk-release` | `:186` | `:app:assembleRelease` (`:196`) → compile le banc **sans** le mock : c'est le stub `src/release/MockControl.kt` qui prend le relais |
| `lint` | `:199` | `:app:lintDebug` (`:215`) → le banc passe au lint comme le reste de l'app |

Trois propriétés à retenir :

- **le périmètre « code » nomme les modules** (`ci.yml:70`) :
  `^(app|core)/|^gradle/|^gradle\.properties$|^settings\.gradle\.kts$|^build\.gradle\.kts$|^tools/`.
  Le banc vivant sous `app/`, toute PR qui le touche déclenche Gradle ; une PR de documentation
  seule continue de sauter les étapes Gradle du job `ci` ;
- **aucun test d'instrumentation n'est exécuté** : `MockGlassesTest` et l'asset
  `app/src/androidTest/assets/mock_feed.mp4` demandent un appareil. Ce n'est pas une régression
  de la fusion, c'était déjà le cas avant ;
- **la garde de taille couvre le banc** : `tools/check-kotlin-limits.py` parcourt la racine en
  sautant `{.git, .gradle, build, .idea, tmp, …}`, donc `app/src/main/.../bench/` est contrôlé
  comme le reste. Plus gros fichier du paquet au 27/10 : `BenchScreen.kt` (337 l.), plus grosse
  limite respectée avec de la marge.

## 3. Les garde-fous de budget, inchangés

- `.github/ci-budget/check_timeouts.py` refuse tout job sans `timeout-minutes` ou au-delà de
  **5 min** ; les quatre jobs de `ci.yml` déclarent `timeout-minutes: 5`.
- `concurrency: ci-${{ github.workflow }}-${{ github.ref }}` + `cancel-in-progress: true`
  (`ci.yml:16-17`) : un nouveau push annule le run précédent de la même branche.
- Tous les `checkout` sont en `fetch-depth: 1` ; le cache Gradle vient de
  `gradle/actions/setup-gradle@v4` (`cache-read-only: false`).
- Les runners sont **auto-hébergés** (`runs-on: [self-hosted, linux, x64, vision]`,
  `ci.yml:28`), pas `ubuntu-latest` : les durées ci-dessus sont celles de cette machine, pas
  d'une VM GitHub.
- Un seul appel Gradle par job : découper davantage multiplierait checkout et configuration, que
  le budget de 5 min ne permet pas.

## 4. Si le banc grossit

Tant que `:app` reste sous la minute pour ses tests JVM, il n'y a rien à ajouter : le banc est
déjà couvert. Le jour où il faudrait du lint dédié ou des tests d'instrumentation, la sortie
propre est un job séparé avec `needs: ci` et `timeout-minutes: 5` — on n'allonge **jamais** le
timeout d'un job existant (garde `ci-budget` : un job au-delà de 5 min fait échouer la PR).

**Ne pas ressusciter un job `spike`.** La raison de la fusion est l'inverse : une seule app, un
seul paquet, une seule CI pour le banc.