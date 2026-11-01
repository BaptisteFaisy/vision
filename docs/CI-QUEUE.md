# CI et file de merge

Relu le **2026-10-28** sur cet arbre (découpe v9 : natif hors PR), mis à jour le
**2026-10-29** (fluidité v11 : `ci-budget` replié dans `ci.yml`, `stale-base-guard` supprimé,
un seul balayage par couple tête/base, journal `merge=union`), puis le **2026-10-30**
(découpe v10 : `ci` coupé en `ci-core`/`ci-app` + agrégateur, natif en 2 jobs).
Chaque affirmation ci-dessous vient d'un fichier du dépôt : `.github/workflows/`,
`.github/ci-budget/`, `.github/couche0/`.

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

## 2. Les jobs `ci-core` / `ci-app` / `ci` (et le natif hors PR)

Déclencheurs : `pull_request`, `merge_group` (file native, inutilisée ici) et un
`schedule` nocturne — **plus de `push` sur `master`** (retiré le 2026-10-31). La PR porte deux moitiés en parallèle,
`ci-core` (gardes + backend + tests `:core`) et `ci-app` (tests `:app`), sous un
agrégateur `ci` vert ssi les deux moitiés sont vertes — le check requis `ci` ne
change pas (ni ruleset, ni Mergify, ni balayeur). Runner **auto-hébergé** —
`runs-on: [self-hosted, linux, x64, vision]` —, cache Gradle via
`gradle/actions/setup-gradle@v4` avec `cache-read-only: false`,
`timeout-minutes: 5`. Motif de la découpe v10 (2026-10-30) : 4 runs tués par le
timeout à ~320 s en 48 h dans « Tests JVM », contention des runners partagés.

Les builds natifs (`apk` = `:app:assembleDebug` + `:app:assembleRelease` + artefact
sur `master`, `lint` = `:app:lintDebug` + rapport) vivaient dans `ci.yml` jusqu'au
2026-10-28 : 4 jobs × N PRs saturaient les runners (file jusqu'à 10 min, budget
wall-clock cassé). Ils ont déménagé dans `ci-native.yml`, vérifiés à chaque push sur
`master` (hors doc, `server/` et tests seuls — prouvé sans effet sur l'APK ni le lint),
à chaque tag et sur demande manuelle — `timeout-minutes: 10` (hors PR : cache froid
possible). `apk` et `apk-release` ont fusionné le 2026-10-30 (marge 10 min : 2 runs
tués à ~700 s) : debug + release tiennent en ~6-7 min < 10, la garde clés embarquées
tourne après chaque assemblage, chaque variante reste vérifiée.

**Un seul passage par arbre (2026-10-31).** Le même contenu était testé trois fois : la PR, la PR
de lot de la file (qui reconstruit la même chose), puis `push: master` juste après le merge. Mesuré
du 28 au 31/10 : 131 runs `CI` sur `push: master`, **0 échec**, 68 annulés en vol par le push
suivant — 2,3 min de pool par atterrissage pour un verdict déjà connu. Désormais :

- la PR fait **le** passage réel, et c'est la porte d'entrée de la file (`check-success = ci`) ;
- un lot Mergify d'**une** PR ne rejoue plus Gradle : `update_method: rebase` fait que l'arbre du
  lot est celui de la tête de PR qui vient d'être verte. La taille du lot est lue dans le bloc
  machine `pull_requests:` du corps de la PR de lot (`tools/mergify_batch_size.py`) ; un lot de
  **deux PR ou plus** rejoue les tests, car cette combinaison-là n'a jamais été vérifiée, et une
  taille illisible fait aussi tourner les tests (défaut sûr) ;
- `push: master` ne déclenche plus `ci.yml` : `ci-native.yml` reste, lui, sur chaque atterrissage
  (`apk`, `apk-release`, `lint` — absents de la file) et rougit master si un atterrissage casse le
  build. Un contrôle complet par nuit (`cron '30 3 * * *'`) attrape la dérive qui ne vient d'aucun
  commit : image de runner, JDK, SDK, secrets, cache.

Il n'y a **pas** de job dédié au banc lunettes : le banc est compilé et testé avec `:app`
(`:app:assembleDebug` au job `apk` de `ci-native.yml`, ses tests JVM dans
`:app:testDebugUnitTest` au job `ci-app`).
Détail : la page « Le banc lunettes dans la CI » sous `docs/` (nom de fichier inchangé pour ne
pas casser les liens).

**Périmètres.** Les jobs `ci-core` et `ci-app` demandent d'abord à l'API la liste des fichiers
de la PR (le checkout est superficiel, la base n'y est pas) via l'action composite unique
`.github/actions/perimetre`, et publient `code` (Gradle : `app/`, `core/`, `gradle/`, fichiers
racine — hors `.md`/`.txt`) et `backend` (Python : `server/`). `tools/` n'est dans aucun
périmètre : une PR 100 % Python ne fait tourner que les gardes. Une PR purement documentaire
saute Gradle et le backend : le verdict tombe en quelques secondes. Un push est comparé à
`github.event.before` par la même mécanique.

**Les gardes, dans l'ordre.** Les rapides (1-4) tournent dans les deux moitiés (même action
composite `.github/actions/gardes-rapides` : un dépassement échoue vite des deux côtés, sinon
l'agrégateur attendrait la moitié lente) ; les lentes (5-7) dans `ci-core` seul, sur le même
commit — les rejouer ne changerait pas le verdict.

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
  `.github/workflows/ci.yml` (gelé le 26/10 ; porte aussi le job `ci-budget` depuis le 29/10),
  `.github/workflows/ci-native.yml` et `.github/workflows/pr-auto-merge.yml` (gelés le 29/10 :
  vider `lint`/`apk` ou l'auto-merge passerait sinon inaperçu). Les toucher exige une
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
matrice, un job par PR : à chaque fin de `CI`, toutes les 15 min en filet, à
chaque push sur `master`, au passage brouillon → prête et sur une **réécriture de corps**
(`edited`, qui réarme `couche0` seul — bloc `## Scope`)). Depuis la v27 (31/10), la fin de
`couche0` ne réveille plus le balayage : il finit ~9 s après un push quand `CI` met ~115 s, donc
ce réveil tombait structurellement avant le verdict (mesuré 20:10→22:10 : 41 fins de `couche0`
pour 21 runs `CI`, la moitié des balayages de la fenêtre). Un `couche0` rouge arrive par la fin de
`CI` de la même tête ; un `couche0` réarmé seul arrive par `edited`. Même règle pour
`auto-approve` (31/10 : 25 balayages échantillonnés, 160 runs vus, 0 approuvé). Depuis la v11 (29/10), un couple
**(tête de PR, base, état de fusion)** n'est balayé qu'une fois : l'empreinte vit dans un
commentaire unique édité sur place (`<!-- automerge-etat -->`), et un changement d'état
(`BLOCKED` → `CLEAN` quand les checks passent) en fait un nouveau couple. Avant, les trois
déclencheurs refaisaient le même clone et le même rebase (153 runs/jour, 63 % annulés).
Les conflits de `CHANGELOG.md` sont résolus par `merge=union` (journal additif, les deux entrées
sont gardées, aucun appel LLM) : c'est ce qui cassait la boucle « master bouge → conflit →
agent → push → CI → master bouge ».

**Débit (v12, 29/10).** Le frein n'était pas la durée d'un balayage (17 s) mais le **rebase
imposé** : chaque merge déplace `master`, donc les autres PR passent `BEHIND`, et les rebaser une
par une poussait un commit — ce qui relançait la CI de chaque PR (60-90 s) et re-déplaçait
`master` (taxe en O(N²) sur le nombre de PR ouvertes ; 15 merges/h au mieux, p90 70 min).
Désormais une PR `BEHIND` dont les **trois checks requis sont verts** et dont les fichiers ne
croisent **aucun** fichier atterri depuis sa base est **mergée directement** (`merge --rebase`
côté serveur, `strict: false` est déjà la politique du dépôt) : pas de clone, pas de push, pas de
CI relancée. Au moindre doute — fichiers qui se croisent, liste tronquée à 300, brouillon, checks
pas verts — on rebase comme avant. **File de merge (v14/v15, 29/10).** Le plafond ci-dessous (~1 merge par cycle de CI) se lève en
**groupant** : la file construit `master` + les PR du lot, rejoue les trois checks requis (`ci`,
`ci-budget`, `couche0`) sur l'arbre fusionné, puis merge le lot en rebase. C'est la garantie que
`strict` cherchait, sans relancer la CI d'une PR à chaque mouvement de `master`.

La file **native de GitHub n'est pas disponible ici** : la doc exige « any public repository owned
by an **organization** », et ce dépôt appartient à un compte utilisateur — l'API refuse la règle
`merge_queue` (422 `Invalid rule 'merge_queue'`, sur six variantes de paramètres et sur un ruleset
neuf). C'est donc **Mergify** qui tient la file (`.mergify.yml`) :

- `queue_conditions` = les trois checks requis + `base = master` + ni brouillon ni conflit ;
- lots de 3 (`batch_size`), 4 lots testés en parallèle, `merge_method: rebase` et
  `update_method: rebase` (historique linéaire conservé) ;
- mise en file automatique par `merge_protections_settings.auto_merge_conditions` — pas besoin du
  produit « Workflow Automation », dont `pull_request_rules` dépend.

Trois prérequis côté dépôt : l'app Mergify installée (id 10562), Mergify **acteur de contournement
du ruleset en mode `exempt`** (sinon `strict` et la règle `pull_request` bloquent les branches de
file), et `ci.yml` / `couche0.yml` déclenchés sur la **PR de lot** (`pull_request` sur
`mergify/merge-queue/**`) — pas sur le `push` de cette branche : le run en double y était annulé par
la concurrency, et l'agrégateur `ci` (`if: always()`) publiait un `FAILURE` transitoire sur le SHA du
lot que Mergify lisait pour éjecter la PR (`CHECKS_FAILED`). Constaté sur #634 (16:30) et #637
(16:36) le 31/10, déclencheurs `push` retirés le même jour.

Quand la file est active (`QUEUE`, lu à la source dans le ruleset), le balayeur **ne merge plus et
ne rebase plus** : la file met à jour les PR contre `master`. Il ne garde que ce qu'une file ne
sait pas faire — résoudre un conflit (`DIRTY`) et relancer une panne d'infrastructure (`BLOCKED`).
Si la file disparaît, `QUEUE` retombe à `false` et le balayeur reprend son ancien chemin.

**Le plafond, et pourquoi (v13, 29/10).** Le ruleset `master — PR obligatoire, checks requis` porte
`strict_required_status_checks_policy: true` : GitHub **refuse** de merger une branche en retard, même
quand la protection classique dit `strict: false`. Sous cette règle, chaque merge déplace `master` et
invalide la CI de **toutes** les autres PR : le débit est plafonné à environ un merge par cycle de CI
(~70-90 s, soit ~40/h au mieux), quel que soit le nombre de runners. Le balayeur s'y conforme :
s'il y a une PR prête (`CLEAN`, non brouillon), il merge et **ne rebase personne** (le rebase serait
invalidé aussitôt) ; sinon il prépare **une seule** PR, la plus ancienne. Avant (v12 et antérieurs),
chaque mouvement de `master` relançait un rebase + une CI pour *chaque* PR ouverte — taxe en O(N²),
et aucune ne finissait avant le mouvement suivant (15 merges/h, p90 70 min).

Le groupe `concurrency` est désormais **par tête de PR** (et non unique) : avec un groupe unique,
GitHub annulait le run *en attente* dès qu'un autre se déclenchait, donc le « CI verte, merge-moi »
d'une PR pouvait être annulé par le run d'une autre — famine, et attente jusqu'au cron. Le cron de
filet est passé à 5 min.
La seule porte reste la CI : les 3 checks `ci`, `ci-budget`, `couche0` verts, puis
merge en rebase. Le natif (`apk`, `lint`) est vérifié sur `master`
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
  - run annulé (`cancel` : runner perdu, annulation manuelle — cas réel : #434) :
    aucun verdict à juger, le run **entier** est rejoué, **2 fois maximum par
    tête** (un job annulé n'est pas un job en échec : `--failed` ne relancerait
    rien) ;
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

### 5.2 Le conflit ne se répare pas, il s'évite (côté auteur)

Mesure du 01/11/2026 (`python3 tools/pr-conflits.py`, 250 PR fusionnées) : ce n'est
pas la taille du diff qui fait le conflit, c'est le **temps d'exposition** à
`master`.

| commits de `master` traversés pendant la vie de la PR | conflits |
|---|---|
| 0 – 5 | 4 % (7/177) |
| 5 – 20 | 14 % (8/57) |
| 20 – 60 | 13 % (2/15) |

Avec ~300 commits par jour (~12/h), une PR qui vit plus d'une heure est déjà dans
la zone rouge. En 4 h de balayage, **24 % des PR (10/42) sont arrivées avec une base
périmée** : le balayeur a dû les rebaser, et la moitié a fini chez
`conflict_agent.py` — ≈15 min de boucle LLM plus un passage de CI complet.

D'où `tools/hooks/pre-push` (installé une fois par `tools/install-hooks.sh`) : la
branche est rebasée sur `origin/master` **avant** le push.

- coût mesuré : ~11 ms quand la branche est à jour, ~250 ms pour 400 commits de
  retard (`bash tools/test-hooks.sh` rejoue les six cas sur des dépôts synthétiques) ;
- si le rebase conflicte, le push est **refusé** : l'auteur résout tout de suite,
  dans son contexte, au lieu de laisser le balayeur payer une boucle LLM ;
- jamais de rebase d'un push forcé (le bail du balayeur et de `conflict_agent.py`
  reste intact), jamais sur `master` ni sur `mergify/merge-queue/*` ;
- une branche non linéaire (`master` mergé au lieu d'être rebasé) est remise à plat
  du même geste — c'est le `GITHUB_REBASE_MERGE_REFUSED` que le balayeur devait
  rattraper ;
- dépannage : `VISION_NO_FRESH_PUSH=1 git push …`.

Le hook ne remplace aucun filet : le balayeur continue de rebaser les PR `BEHIND`
et `conflict_agent.py` reste la dernière porte pour ce qui a échappé au push.

**Où sont les conflits qui restent** (mêmes 250 PR, comptes rendus de l'agent
dépouillés par `tools/pr-conflits.py` — il nomme les fichiers qu'il a
réconciliés) : 34 fichiers, dont `docs/TRAVERSEE.md` (5), `docs/VOIX-DANGER.md`
(5), `ProbeWiring.kt` (4), `LiveProbe.kt` (3). Les deux journaux de conception
pèsent 29 % des réconciliations : ce sont eux qui grossissent à chaque PR, pas
le code.

En revanche **`CHANGELOG.md` n'apparaît jamais** dans ces réconciliations (0 sur
34) : `merge=union` le règle dans le clone du balayeur avant que l'agent ne soit
saisi. Il ne coûte donc aucune boucle LLM — seulement un affichage `DIRTY` dans
l'UI GitHub, qui ignore `merge=union` (vérifié sur un dépôt jetable). Le laisser
tel quel est le bon arbitrage : une entrée générée après merge remplacerait un
journal écrit à la main (172 entrées, Constat/Fait/Preuve) par des titres de PR,
pour zéro conflit gagné.

### 5.3 Un test rouge se trie avant de se réparer (01/11/2026)

Cas vécu, PR #919 : `ci-app` tombe sur `ProbeStairsPriorityTest`, une classe que
la PR **ne touche pas**. Le nouveau test de la PR publiait de vraies frames sans
remettre les globaux de séance (`ProbeTracks`), donc la classe suivante — même
JVM, ordre alphabétique — se taisait. Le balayeur a appelé l'agent de réparation
deux fois ; sans diagnostic, il a rendu « budget de 150 s dépassé » (deux fois) et
la PR a attendu **une demi-heure** pour une ligne à ajouter.

Trois pièces, dans cet ordre :

1. **`triage-rouge.yml`** (déclenché par la fin en échec de `CI`, un passage par
   tête). Il lit le journal du run et le diff, puis `tools/triage_test.py` — pris
   sur `master`, jamais sur la PR — sort la classe tombée, dit si la PR la touche,
   **relance la classe seule** (`--verifier`) et désigne les suspects : les tests
   de la PR qui appellent `probe.onFrame`/`probe.dispatch`, donc écrivent les
   globaux de séance.
   - verte seule → verdict `pollution` : dépendance d'ordre entre tests, pas le
     code de la PR (constaté sur #919) ;
   - rouge seule → la classe échoue vraiment (`perimetre` si la PR la modifie) ;
   - `compilation` → pas un test, l'agent de réparation prend le relais comme
     avant.
2. **Rapport** : un commentaire `<!-- triage sha=… -->` (un par tête) et un label
   (`pollution-inter-tests`, `test-de-la-pr`, `test-hors-perimetre`). Rien de
   bloquant, aucune porte : seule la CI verte fait merger.
3. **Réparation** : le même pilote borné qu'avant (`test_agent.py`, zeroclaw
   `fw`, une tentative par tête, porte de compilation, 20 fichiers / 1 500
   lignes), mais il reçoit le verdict en **indice** (`--indice`) : il ne cherche
   plus la panne à l'aveugle.

Qui répare : dès que `TRIAGE_TEST` ≠ `0` (défaut), c'est `triage-rouge` — le
balayeur ne saisit plus l'agent lui-même, sinon il consommerait l'unique
tentative de la tête **sans** l'indice, exactement ce qui a échoué le 01/11.
`TRIAGE_TEST=0` rend la réparation au balayeur, `TEST_AGENT=0` coupe l'agent.

Côté code, la règle est plus simple que le symptôme : **piloter une publication,
c'est démarrer une séance**. `LiveProbe.begin()` remet les globaux de séance à
zéro (`globalsNouvelleSeance`), et `tools/check-test-globals.py` (appelé par
`scripts/test_scripts.sh`, donc par la CI) refuse un fichier de test `app/` qui
appelle `probe.onFrame` sans `begin()` ni `seanceVide()` — aucune exemption au
01/11.

## 6. Ce qui n'est pas dans ce dépôt

- Aucun gate LLM ni service de revue externe : la seule porte est la CI décrite ci-dessus.
- Secrets dépôt : `AUTOMERGE_PAT` (file de merge, section 5) et `MODAL_TOKEN_ID` /
  `MODAL_TOKEN_SECRET` (déploiement GPU) exigés ; les clés du build restent
  facultatives (section 2).
- Aucun outil de file externe : le balayeur du dépôt fusionne dès que les checks
  requis sont verts.