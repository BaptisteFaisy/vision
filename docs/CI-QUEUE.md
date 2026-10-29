# CI et file de merge

Relu le **2026-10-28** sur cet arbre (découpe v9 : natif hors PR), mis à jour le
**2026-10-29** (fluidité v11 : `ci-budget` replié dans `ci.yml`, `stale-base-guard` supprimé,
un seul balayage par couple tête/base, journal `merge=union`). Chaque affirmation
ci-dessous vient d'un fichier du dépôt : `.github/workflows/`, `.github/ci-budget/`,
`.github/couche0/`.

## 1. La règle : un verdict en 5 minutes

- Chaque job déclare `timeout-minutes: 5` — c'est le plafond, jamais un réglage à allonger.
- Checkout superficiel (`fetch-depth: 1`) partout, `concurrency` par workflow pour annuler les runs
  devenus obsolètes.
- Le garde-fou est le job **`ci-budget` de `ci.yml`** (replié le 29/10 : c'était un workflow à part,
  qui coûtait un run de plus par push de PR). Il lance `check_timeouts.py`, qui lit les workflows
  ligne à ligne et **échoue** sur un job sans `timeout-minutes` ou au-delà de 5 minutes ; il
  **avertit** (sans échouer) sur un `concurrency` absent ou un checkout sans `fetch-depth: 1`.
- Il tourne sur chaque PR pour que le check `ci-budget` existe toujours : c'est une condition
  d'entrée de la file. Sans lui, une PR pourrait entrer dans la file avec un job de 30 minutes.
  Le nom du check est celui du **job**, pas du fichier : la protection de branche est inchangée.

## 2. Le job `ci` de `ci.yml` (et le natif hors PR)

Déclencheurs : `pull_request` et `push`. La PR ne porte qu'**un seul job**,
`ci` (`ci.yml:32`) : périmètres, chaîne d'outils, gardes, tests Python (gardes,
portes, backend), tests JVM `:core` + `:app`, rapport de tests. Runner **auto-hébergé** —
`runs-on: [self-hosted, linux, x64, vision]` —, cache Gradle via
`gradle/actions/setup-gradle@v4` avec `cache-read-only: false`,
`timeout-minutes: 5`.

Les builds natifs (`apk` = `:app:assembleDebug` + artefact sur `master`,
`apk-release` = `:app:assembleRelease`, `lint` = `:app:lintDebug` + rapport)
vivaient dans `ci.yml` jusqu'au 2026-10-28 : 4 jobs × N PRs saturaient les
runners (file jusqu'à 10 min, budget wall-clock cassé). Ils ont déménagé
dans `ci-native.yml`, vérifiés à chaque push sur `master`, à chaque tag et
sur demande manuelle — `timeout-minutes: 10` (hors PR : cache froid possible),
mêmes commandes.

Il n'y a **pas** de job dédié au banc lunettes : le banc est compilé et testé avec `:app`
(`:app:assembleDebug` au job `apk` de `ci-native.yml`, ses tests JVM dans
`:app:testDebugUnitTest` au job `ci`).
Détail : la page « Le banc lunettes dans la CI » sous `docs/` (nom de fichier inchangé pour ne
pas casser les liens).

**Périmètres.** Le job `ci` demande d'abord à l'API la liste des fichiers de la PR (le checkout est
superficiel, la base n'y est pas) et publie `steps.perimetre.outputs.code` (Gradle : `app/`,
`core/`, `gradle/`, fichiers racine — hors `.md`/`.txt`) et `steps.perimetre.outputs.backend`
(Python : `server/`). `tools/` n'est dans aucun périmètre : une PR 100 % Python ne fait tourner
que les gardes. Une PR purement documentaire saute Gradle et le backend : le verdict tombe en
quelques secondes. Un push est comparé à `github.event.before` par la même mécanique.

**Les gardes du job `ci`, dans l'ordre.**

1. chaîne d'outils : `JAVA_HOME` et `ANDROID_HOME` présents, version Java affichée ;
2. aucun secret ni fichier local versionné : refus de `local.properties` suivi, et refus des motifs
   de clés en clair (`sk-…`, `sk_…`, `ghp_…`) hors `.github`, `docs` et `*.md` ;
3. pas de `org.gradle.java.home` codé en dur dans `gradle.properties` ;
4. taille Kotlin : `tools/check-kotlin-limits.py` refuse un fichier ≥ 500 lignes ou une fonction
   ≥ 50 lignes (+ 28 tests) ;
5. budget de latence (+ 18 tests), design iSee Portrait, navigation vocale (+ 12 tests),
   clés embarquées (+ 8 tests) ;
6. gardes CI elles-mêmes : agent de conflits (16 tests), couche0 (19 tests), budget (11 tests) ;
7. portes Python et shell : `test_scripts.sh` (101 assertions), goldens des masques,
   chiffres du budget, shader du suivi (+ 6 tests).

**Les clés du build** (action composite `.github/actions/cles-build`, partagée avec
`ci-native.yml`). `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`, `LLM_LIVE_API_KEY`, `TTS_API_KEY`,
`TTS_VOICE_ID`, `ASR_API_KEY` sont lus depuis les secrets du dépôt et écrits dans
`local.properties` (gitignoré, valeurs jamais affichées). Sans aucun secret, l'étape sort en
succès : l'APK est produit sans clé et l'utilisateur saisit la sienne dans Réglages.

## 3. `couche0` — la garde de périmètre

`.github/workflows/couche0.yml` + `.github/couche0/check_couche0.py` (bibliothèque standard seule) :

- `scope` : tout fichier modifié par la PR doit être déclaré dans la section `## Scope` du corps, et
  toute déclaration doit servir ;
- `forbidden` : motifs refusés dans les lignes ajoutées du diff, plus une liste de fichiers de garde
  gelés — `.github/ci-budget/`, `.github/couche0/`, `.github/workflows/couche0.yml`,
  `.github/workflows/ci-budget.yml`, `.github/workflows/ci.yml` (gelé le 26/10),
  `.github/workflows/ci-native.yml` et `.github/workflows/pr-auto-merge.yml` (gelés le 29/10 :
  vider `lint`/`apk-release` ou l'auto-merge passerait sinon inaperçu). Les toucher exige une
  mention explicite et le drapeau `SAMVISION_ALLOW_GUARD_BUMP`.

Le juge est **téléchargé depuis `master`**, jamais depuis le head de la PR : une PR ne peut pas
assouplir son propre garde. Re-armable à la main : `gh workflow run couche0.yml -f pr=<n>`.

## 4. `stale-base-guard` — supprimé (29/10)

Ancien workflow informatif : il comparait la PR à sa base et avertissait si la branche était en
retard, avec `exit 0` dans tous les cas — donc **jamais rouge**, et son avertissement redisait ce
que le balayeur fait déjà (il rébase les PR `BEHIND`). Coût mesuré : 52 runs par jour pour un
verdict qui ne pouvait pas changer. Supprimé ; le retard se lit dans l'état `BEHIND` de la PR et
dans le commentaire d'état du balayeur (§5).

## 5. Protection de `master` et file de merge (balayeur v10)

`master` est protégé (PR obligatoire, historique linéaire, pas de push direct) et les
PR sont fusionnées par le workflow `pr-auto-merge` (jobs `discover` + `sweep` en
matrice, un job par PR : à chaque fin de `CI` ou de `couche0`, toutes les 15 min en filet, à
chaque push sur `master` et au passage brouillon → prête). Depuis la v11 (29/10), un couple
**(tête de PR, base, état de fusion)** n'est balayé qu'une fois : l'empreinte vit dans un
commentaire unique édité sur place (`<!-- automerge-etat -->`), et un changement d'état
(`BLOCKED` → `CLEAN` quand les checks passent) en fait un nouveau couple. Avant, les trois
déclencheurs refaisaient le même clone et le même rebase (153 runs/jour, 63 % annulés).
Les conflits de `CHANGELOG.md` sont résolus par `merge=union` (journal additif, les deux entrées
sont gardées, aucun appel LLM) : c'est ce qui cassait la boucle « master bouge → conflit →
agent → push → CI → master bouge ».
La seule porte reste la CI : les 3 checks `ci`, `ci-budget`, `couche0` verts, puis
merge en rebase. Le natif (`apk`, `apk-release`, `lint`) est vérifié sur `master`
par `ci-native.yml` après chaque atterrissage. Pas de gate humaine, pas de merge
à la main (sauf amorçage unique de la découpe v9, qui change le contrat du
balayeur lui-même).

Concrètement, à chaque passage, pour chaque PR interne non-brouillon :

- `BEHIND` : vrai rebase sur `master` (pas de bulle de merge), poussé avec bail —
  jamais d'écrasement d'un push d'auteur arrivé entre-temps. Conflit : l'agent de
  conflit tente la résolution, sinon l'auteur rebase à la main ;
- `CLEAN` + CI verte : merge (`--rebase --match-head-commit`) ;
- `BLOCKED` : ce n'est plus « rien à faire ». Le balayeur regarde **quel** check
  requis est rouge et agit :
  - `ci` / `ci-budget` tombés sur une panne d'infrastructure (job perdu, checkout,
    cache, runner) : les jobs en échec sont relancés, **2 fois maximum par tête de
    PR**, avec un marqueur en commentaire — jamais de boucle ;
  - `ci` / `ci-budget` tombés sur une étape de tests : aucune relance (un test rouge
    ne devient pas vert en le relançant, et le budget est de 5 min par job) ; un
    commentaire dit quelle étape est en cause, une fois par tête ;
  - `couche0` rouge sur le périmètre : l'agent de périmètre rédige la déclaration
    manquante depuis le diff (section 5.1), vérifiée avec le garde de `master`
    avant écriture ; l'édition du corps relance `couche0` ;
- `UNKNOWN` : sondage borné (4 × 5 s) avant de rendre la main au passage suivant.

Ce trou de la v9 est ce qui laissait les PR d'auteur bloquées pour toujours : un
`## Scope` absent rend `couche0` rouge de façon **déterministe**, la PR reste
`BLOCKED`, et un `BLOCKED` n'était jamais repris ni relancé.

### 5.1 Les deux agents Fireworks (jamais des portes)

Les deux écrivent dans le dépôt, aucun ne juge un merge : la seule porte reste la
CI. Tous deux sont lus sur `origin/master` (la branche d'une PR peut être
antérieure), aiguillés par le secret `FIREWORKS_API_KEY`, et coupés par une
variable de dépôt.

| Agent | Quand | Ce qu'il fait | Coupe-circuit |
|---|---|---|---|
| `conflict_agent.py` | rebase en conflit | résout conflit par conflit, termine le rebase, pousse avec bail | `FIREWORKS_AGENT_RESOLVE=0` |
| `scope_agent.py` | `couche0` rouge sur le périmètre | rédige une puce `` - `chemin` — raison `` par fichier depuis le diff, vérifie le corps avec le garde de `master`, réécrit `## Scope` | `FIREWORKS_AGENT_SCOPE=0` |

`scope_agent.py` **n'assouplit pas `couche0`** : il importe le garde de `master`,
rejoue `check_scope` sur le corps recomposé et n'écrit que si le verdict est vert.
Un fichier non déclaré et une déclaration inutile restent refusés ; c'est la
déclaration manquante qui est écrite, avec une raison tirée du diff. Plafond
25 fichiers ; au-delà, main humaine (label `intervention-humaine`).

Le balayeur a besoin du secret dépôt **`AUTOMERGE_PAT`** (PAT `repo`, créé le
2026-10-27) : `GITHUB_TOKEN` ne suffit pas (ni auto-merge, ni pushes qui relancent
la CI — runs fantômes `action_required`). Sans le secret, pause propre (« file en
pause », exit 0) au lieu d'échouer — file à l'arrêt jusqu'à sa (re)création.
`gh`/`jq` viennent de `~/bin` sur les runners (`export PATH`, vu le 27/10 : sans
ça, `gh` introuvable et file muette selon le runner).

Une branche en retard sur `master` reste verte : c'est le rebase du balayeur (état `BEHIND`) qui
la met à jour, sans check bloquant.

## 6. Ce qui n'est pas dans ce dépôt

- Aucun gate LLM ni service de revue externe : la seule porte est la CI décrite ci-dessus.
- Secrets dépôt : `AUTOMERGE_PAT` (file de merge, section 5) et `MODAL_TOKEN_ID` /
  `MODAL_TOKEN_SECRET` (déploiement GPU) exigés ; les clés du build restent
  facultatives (section 2).
- Aucun outil de file externe : le balayeur du dépôt fusionne dès que les checks
  requis sont verts.