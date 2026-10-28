# CI et file de merge

Relu le **2026-10-28** sur cet arbre (découpe v9 : natif hors PR). Chaque affirmation
ci-dessous vient d'un fichier du dépôt : `.github/workflows/`, `.github/ci-budget/`,
`.github/couche0/`.

## 1. La règle : un verdict en 5 minutes

- Chaque job déclare `timeout-minutes: 5` — c'est le plafond, jamais un réglage à allonger.
- Checkout superficiel (`fetch-depth: 1`) partout, `concurrency` par workflow pour annuler les runs
  devenus obsolètes.
- Le garde-fou est un workflow à part : `ci-budget.yml` lance `check_timeouts.py`, qui lit les
  workflows ligne à ligne et **échoue** sur un job sans `timeout-minutes` ou au-delà de 5 minutes ;
  il **avertit** (sans échouer) sur un `concurrency` absent ou un checkout sans `fetch-depth: 1`.
- Il tourne sur chaque PR pour que le check `ci-budget` existe toujours : c'est une condition
  d'entrée de la file. Sans lui, une PR pourrait entrer dans la file avec un job de 30 minutes.

## 2. Le job `ci` de `ci.yml` (et le natif hors PR)

Déclencheurs : `pull_request` et `push`. La PR ne porte qu'**un seul job**,
`ci` (`ci.yml:30`) : périmètre, chaîne d'outils, gardes, tests JVM `:core` +
`:app`, rapport de tests. Runner **auto-hébergé** —
`runs-on: [self-hosted, linux, x64, vision]` —, cache Gradle via
`gradle/actions/setup-gradle@v4` avec `cache-read-only: false`,
`timeout-minutes: 5`.

Les builds natifs (`apk` = `:app:assembleDebug` + artefact sur `master`,
`apk-release` = `:app:assembleRelease`, `lint` = `:app:lintDebug` + rapport)
vivaient dans `ci.yml` jusqu'au 2026-10-28 : 4 jobs × N PRs saturaient les
runners (file jusqu'à 10 min, budget wall-clock cassé). Ils ont déménagé
dans `ci-native.yml`, vérifiés à chaque push sur `master`, à chaque tag et
sur demande manuelle — même `timeout-minutes: 5`, mêmes commandes.

Il n'y a **pas** de job dédié au banc lunettes : le banc est compilé et testé avec `:app`
(`:app:assembleDebug` au job `apk` de `ci-native.yml`, ses tests JVM dans
`:app:testDebugUnitTest` au job `ci`).
Détail : la page « Le banc lunettes dans la CI » sous `docs/` (nom de fichier inchangé pour ne
pas casser les liens).

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

## 5. Protection de `master` et file de merge (balayeur v9)

`master` est protégé (PR obligatoire, historique linéaire, pas de push direct) et les
PR sont fusionnées par le workflow `pr-auto-merge` (jobs `discover` + `sweep` en
matrice, un job par PR : à chaque fin de CI, toutes les 10 min en filet, et à
chaque push sur `master` ou événement de PR).
La seule porte reste la CI : les 3 checks `ci`, `ci-budget`, `couche0` verts, puis
merge en rebase. Le natif (`apk`, `apk-release`, `lint`) est vérifié sur `master`
par `ci-native.yml` après chaque atterrissage. Pas de gate humaine, pas de merge
à la main (sauf amorçage unique de la découpe v9, qui change le contrat du
balayeur lui-même).

Concrètement, à chaque passage, pour chaque PR interne non-brouillon :

- `BEHIND` : vrai rebase sur `master` (pas de bulle de merge), poussé avec bail —
  jamais d'écrasement d'un push d'auteur arrivé entre-temps. Conflit : on saute,
  l'auteur rebase à la main ;
- `CLEAN` + CI verte : merge (`--rebase --match-head-commit`) ;
- autre état : rien à faire (une PR `BLOCKED` par retard + base stricte ne sera
  reprise qu'une fois rebasée — limite connue, à l'auteur de rebaser).

Le balayeur a besoin du secret dépôt **`AUTOMERGE_PAT`** (PAT `repo`, créé le
2026-10-27) : `GITHUB_TOKEN` ne suffit pas (ni auto-merge, ni pushes qui relancent
la CI — runs fantômes `action_required`). Sans le secret, pause propre (« file en
pause », exit 0) au lieu d'échouer — file à l'arrêt jusqu'à sa (re)création.
`gh`/`jq` viennent de `~/bin` sur les runners (`export PATH`, vu le 27/10 : sans
ça, `gh` introuvable et file muette selon le runner).

Une branche en retard sur `master` reste verte : `stale-base-guard` avertit
(informatif), c'est le rebase du balayeur qui met la PR à jour.

## 6. Ce qui n'est pas dans ce dépôt

- Aucun gate LLM ni service de revue externe : la seule porte est la CI décrite ci-dessus.
- Secrets dépôt : `AUTOMERGE_PAT` (file de merge, section 5) et `MODAL_TOKEN_ID` /
  `MODAL_TOKEN_SECRET` (déploiement GPU) exigés ; les clés du build restent
  facultatives (section 2).
- Aucun outil de file externe : le balayeur du dépôt fusionne dès que les checks
  requis sont verts.