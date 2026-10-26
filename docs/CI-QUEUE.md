# CI et file de merge

Relu le **2026-10-26 après 19 h** sur cet arbre. Chaque affirmation ci-dessous vient d'un fichier du
dépôt : `.github/workflows/`, `.github/ci-budget/`, `.github/couche0/`.

## 1. La règle : un verdict en 5 minutes

- Chaque job déclare `timeout-minutes: 5` — c'est le plafond, jamais un réglage à allonger.
- Checkout superficiel (`fetch-depth: 1`) partout, `concurrency` par workflow pour annuler les runs
  devenus obsolètes.
- Le garde-fou est un workflow à part : `ci-budget.yml` lance `check_timeouts.py`, qui lit les
  workflows ligne à ligne et **échoue** sur un job sans `timeout-minutes` ou au-delà de 5 minutes ;
  il **avertit** (sans échouer) sur un `concurrency` absent ou un checkout sans `fetch-depth: 1`.
- Il tourne sur chaque PR pour que le check `ci-budget` existe toujours : c'est une condition
  d'entrée de la file. Sans lui, une PR pourrait entrer dans la file avec un job de 30 minutes.

## 2. Les cinq jobs de `ci.yml`

Déclencheurs : `pull_request` et `push`. Runners `ubuntu-latest`, cache Gradle via
`gradle/actions/setup-gradle`, mise en cache avec `cache-read-only: false`.

| Job | Ce qu'il fait |
|---|---|
| `ci` | périmètre, chaîne d'outils, quatre gardes, tests JVM `:core` + `:app`, rapport de tests |
| `apk` | `:app:assembleDebug` ; artefact publié seulement sur `master` |
| `apk-release` | `:app:assembleRelease` |
| `lint` | `:app:lintDebug` + rapport |
| `spike` | module `:glasses-spike` : assemble + tests |

**Périmètre.** Le job `ci` demande d'abord à l'API la liste des fichiers de la PR (le checkout est
superficiel, la base n'y est pas) et publie `steps.perimetre.outputs.code`. Une PR purement
documentaire saute les étapes Gradle : le verdict tombe en quelques secondes. Un push est comparé à
`github.event.before` par la même mécanique.

**Les quatre gardes du job `ci`.**

1. chaîne d'outils : `JAVA_HOME` et `ANDROID_HOME` présents, version Java affichée ;
2. aucun secret ni fichier local versionné : refus de `local.properties` suivi, et refus des motifs
   de clés en clair (`sk-…`, `sk_…`, `ghp_…`) hors `.github`, `docs` et `*.md` ;
3. pas de `org.gradle.java.home` codé en dur dans `gradle.properties` ;
4. taille Kotlin : `tools/check-kotlin-limits.py` refuse un fichier ≥ 500 lignes ou une fonction
   ≥ 50 lignes.

**Les clés du build.** `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `TTS_API_KEY`, `TTS_VOICE_ID`,
`ASR_API_KEY` sont lus depuis les secrets du dépôt et écrits dans `local.properties` (gitignoré,
valeurs jamais affichées). Sans aucun secret, l'étape sort en succès : l'APK est produit sans clé et
l'utilisateur saisit la sienne dans Réglages.

## 3. `couche0` — la garde de périmètre

`.github/workflows/couche0.yml` + `.github/couche0/check_couche0.py` (bibliothèque standard seule) :

- `scope` : tout fichier modifié par la PR doit être déclaré dans la section `## Scope` du corps, et
  toute déclaration doit servir ;
- `forbidden` : motifs refusés dans les lignes ajoutées du diff, plus une liste de fichiers de garde
  gelés — `.github/ci-budget/`, `.github/couche0/`, `.github/workflows/couche0.yml`,
  `.github/workflows/ci-budget.yml`, `.github/workflows/ci.yml`. Les toucher exige une mention explicite et le drapeau `SAMVISION_ALLOW_GUARD_BUMP`.

Le juge est **téléchargé depuis `master`**, jamais depuis le head de la PR : une PR ne peut pas
assouplir son propre garde. Re-armable à la main : `gh workflow run couche0.yml -f pr=<n>`.

## 4. `stale-base-guard` — informatif

`stale-base-guard.yml` compare la PR à sa base et **avertit** si la branche est en retard
(`behind_by`), sans jamais échouer : `exit 0` dans tous les cas. Il peut servir de check « requis »
sans bloquer : seul un vrai conflit s'oppose à un rebase.

## 5. Protection de `master` et file de merge native

`master` est protégé par un **ruleset GitHub** (règles du dépôt, aucun outil externe) :

- **PR obligatoire** : pas de push direct sur `master` ;
- **checks requis** : `ci`, `ci-budget`, `couche0`, exigés sur une base à jour (`strict`) ;
- **historique linéaire** : que des rebases, pas de commit de fusion ;
- **force-push et suppression de `master` interdits** ;
- **merge queue native** : une PR dont l'auto-merge est activé entre dans la file GitHub.

Comment ça marche, concrètement :

1. la PR est ouverte, la CI tourne sur `pull_request` ;
2. l'auto-merge est activé (`gh pr merge <n> --auto --rebase`, ou le bouton « Enable auto-merge ») ;
3. GitHub crée un **groupe de merge** (`gh-readonly-queue/master/pr-<n>-<sha>`) et y rejoue les
   checks requis — `couche0` se déclenche aussi sur ces refs, en retrouvant le numéro de PR dans le
   nom du ref (section 3) ;
4. le groupe est rebasé sur `master`, les checks repassent, la PR fusionne.

Tant que la règle « Require merge queue » n'est pas ajoutée au ruleset, l'étape 3 n'existe pas :
l'auto-merge fusionne directement dès que les trois checks requis sont verts (vérifié sur deux PR).

Une branche en retard sur `master` reste verte : `stale-base-guard` avertit, c'est le rebase du
groupe qui met la PR à jour.

## 6. Ce qui n'est pas dans ce dépôt

- Aucun gate LLM ni service de revue externe : la seule porte est la CI décrite ci-dessus.
- Aucun secret de dépôt n'est exigé : les clés du build sont facultatives (section 2).
- Aucun outil de file externe : l'auto-merge du dépôt est activé, la PR fusionne donc dès que les
  checks requis sont verts.