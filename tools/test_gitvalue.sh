#!/usr/bin/env bash
# Banc d'essai de `gitValue()` (app/build.gradle.kts) : que valent versionCode/versionName
# quand git n'est pas exploitable ? Trois situations, chacune une configuration Gradle
# réelle dans un arbre **hors dépôt git** (tarball produit par `git archive`) :
#
#   1. arbre extrait d'un tarball : aucun `.git` ;
#   2. idem avec un PATH sans `git` du tout (binaire réellement absent) ;
#   3. dépôt git valide mais commandes git en échec (`GIT_DIR` bidon, code 128) :
#      le cas « code de retour non nul » que `runCatching` seul ne voit pas ;
#   4. git bavard : sortie de plusieurs lignes, code 0 — la valeur doit rester
#      la première ligne seule (sinon `toIntOrNull()` rend `null` et versionCode
#      retombe à 1 : INSTALL_FAILED_VERSION_DOWNGRADE sur l'appareil).
#
# Critère : configuration sans erreur, `versionCode` entier > 0, `versionName`
# propre (`1.0.<commits>-<sha>`), aucune trace d'un message d'erreur git dans la valeur.
#
# Usage : bash tools/test_gitvalue.sh        (sortie 0 = tout passe)
# Sortie : 0 si tout passe, 1 sinon, 2 sur argument inconnu.
# Prérequis : JDK 17 (JAVA_HOME) et ANDROID_HOME ; Gradle et les dépendances en cache
# (`--offline`). Rien n'est publié, rien n'est installé, aucun appareil contacté.
set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || exit 1
. "${HERE}/../scripts/lib/common.sh" || exit 1

std_args test_gitvalue.sh "Usage : bash tools/test_gitvalue.sh [--help]
Vérifie versionCode/versionName hors dépôt git (tarball) et quand git échoue."

: "${JAVA_HOME:=${HOME}/toolchains/jdk17}"
: "${ANDROID_HOME:=${HOME}/android-sdk}"
export JAVA_HOME ANDROID_HOME

require_cmd git "Installez git puis relancez."
[[ -x "${JAVA_HOME}/bin/javac" ]] || die "JDK 17 introuvable : JAVA_HOME=${JAVA_HOME}"
[[ -d "${ANDROID_HOME}" ]] || die "SDK Android introuvable : ANDROID_HOME=${ANDROID_HOME}"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/test-gitvalue.XXXXXX")" || exit 1
trap 'safe_rm_rf "$TMP"' EXIT

PASS=0
FAIL=0
OUT=""
RC=0

ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() {
  FAIL=$((FAIL + 1))
  printf '  KO   %s\n' "$1"
  printf '       code=%s valeurs=%s\n' "$RC" "$(printf '%s' "${OUT:-}" | grep 'GITVALUE' | head -4 | tr '\n' ' ')"
}

# --- init Gradle : imprime la configuration calculée par :app -----------------
# Écrit hors du dépôt : aucun fichier du projet n'est modifié par le banc.
INIT="${TMP}/print-version.gradle"
cat >"$INIT" <<'GRADLE'
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app == null) {
        println 'GITVALUE absent : module :app'
        return
    }
    def cfg = app.extensions.getByName('android').defaultConfig
    println "GITVALUE versionCode=${cfg.versionCode}"
    println "GITVALUE versionName=${cfg.versionName}"
}
GRADLE

# --- arbre « tarball » : les fichiers suivis par git, sans .git --------------
TARBALL="${TMP}/tarball"
mkdir -p "$TARBALL"
if ! git -C "${HERE}/.." archive --format=tar HEAD | tar -x -C "$TARBALL"; then
  die "git archive a échoué : le banc doit tourner dans le dépôt (HEAD requis)."
fi
[[ -f "${TARBALL}/app/build.gradle.kts" ]] || die "tarball incomplet : app/build.gradle.kts manquant"
[[ ! -d "${TARBALL}/.git" ]] || die "tarball non conforme : un .git s'y trouve"
if [[ -n "$(git -C "${HERE}/.." status --porcelain)" ]]; then
  warn "arbre de travail modifié : le banc teste HEAD, pas les modifications en cours."
fi

# --- PATH minimal sans git (binaire réellement absent) ----------------------
# Un faux `git` non exécutable ne suffit pas : execvp saute une entrée PATH non
# exécutable et trouve le vrai git plus loin. On ne laisse donc que les outils
# dont Gradle a besoin, symlinkés, et aucun git.
MINBIN="${TMP}/minbin"
mkdir -p "$MINBIN"
for cmd in sh timeout env dirname basename uname which sed grep cat mkdir rm mv cp tr cut head tail sort find xargs expr ls tar gzip; do
  src="$(command -v -- "$cmd" 2>/dev/null)" && ln -sf "$src" "${MINBIN}/${cmd}"
done
if [[ -e "${MINBIN}/git" ]] || PATH="$MINBIN" command -v git >/dev/null 2>&1; then
  die "PATH minimal non conforme : git y est encore joignable"
fi

# --- faux git bavard : plusieurs lignes, code 0 ------------------------------
mkdir -p "${TMP}/bavard"
cat >"${TMP}/bavard/git" <<'STUB'
#!/usr/bin/env bash
# git bavard : la valeur utile puis des lignes parasites, et un code de retour nul.
case "$*" in
  *--format=%ct*) printf '1799999999\nnote : ligne parasite\n' ;;
  *rev-list*) printf '42\nnote : ligne parasite\n' ;;
  *rev-parse*) printf 'abc1234\nnote : ligne parasite\n' ;;
  *) printf 'note : ligne parasite\n' ;;
esac
exit 0
STUB
chmod +x "${TMP}/bavard/git"

# --- harnais : une configuration Gradle, puis lecture des valeurs ------------
# `--no-daemon` est obligatoire ici : un démon Gradle garde l'environnement de son
# premier client, donc le GIT_DIR ou le PATH d'une situation fuiterait dans la
# suivante (constaté : git répondait 128 dans le dépôt normal). Démon à usage
# unique = un environnement par situation.
run_gradle() { # run_gradle <libellé> <dossier> [VAR=valeur...]
  local label="$1" dir="$2"
  shift 2
  OUT="$(cd -- "$dir" && env "$@" timeout 280 ./gradlew -q --offline --no-daemon -I "$INIT" :app:properties 2>&1)"
  RC=$?
  printf '\n== %s (code %s) ==\n' "$label" "$RC"
  printf '%s\n' "$OUT" | grep 'GITVALUE' || true
}

version_code() { printf '%s\n' "$OUT" | sed -n 's/^GITVALUE versionCode=//p' | head -1; }
version_name() { printf '%s\n' "$OUT" | sed -n 's/^GITVALUE versionName=//p' | head -1; }

check_clean() { # check_clean <libellé> : configuration saine et valeur exploitable
  if [[ "$RC" == "0" ]]; then ok "$1 : configuration Gradle sans erreur"; else ko "$1 : configuration Gradle sans erreur"; fi
  if [[ "$(version_code)" =~ ^[1-9][0-9]{0,9}$ ]]; then
    ok "$1 : versionCode entier positif ($(version_code))"
  else
    ko "$1 : versionCode entier positif (reçu '$(version_code)')"
  fi
  if [[ "$(version_name)" =~ ^1\.0\.[0-9]+-[A-Za-z0-9._-]+$ ]]; then
    ok "$1 : versionName propre ($(version_name))"
  else
    ko "$1 : versionName propre (reçu '$(version_name)')"
  fi
}

check_gitless() { # check_gitless <libellé> : repli complet, sans empreinte git
  if [[ "$(version_name)" == "1.0.0-nogit" ]]; then
    ok "$1 : repli gitless complet (1.0.0-nogit)"
  else
    ko "$1 : repli gitless complet (reçu '$(version_name)')"
  fi
}

# --- 0. dépôt git normal : la valeur réelle doit passer inchangée -----------
EXPECT_SECS="$(git -C "${HERE}/.." log -1 --format=%ct)"
EXPECT_COUNT="$(git -C "${HERE}/.." rev-list --count HEAD)"
EXPECT_SHA="$(git -C "${HERE}/.." rev-parse --short=7 HEAD)"
run_gradle '0. dépôt git normal' "${HERE}/.."
check_clean 'dépôt git normal'
if [[ "$(version_code)" == "$EXPECT_SECS" && "$(version_name)" == "1.0.${EXPECT_COUNT}-${EXPECT_SHA}" ]]; then
  ok "dépôt git normal : valeur réelle conservée (${EXPECT_SECS}, 1.0.${EXPECT_COUNT}-${EXPECT_SHA})"
else
  ko "dépôt git normal : valeur réelle conservée (attendu ${EXPECT_SECS} / 1.0.${EXPECT_COUNT}-${EXPECT_SHA})"
fi

# --- 1. tarball : aucun .git ------------------------------------------------
run_gradle '1. tarball, aucun .git' "$TARBALL"
check_clean 'tarball'
check_gitless 'tarball'

# --- 2. tarball + PATH sans git ---------------------------------------------
run_gradle '2. tarball, git introuvable' "$TARBALL" PATH="$MINBIN"
check_clean 'git introuvable'
check_gitless 'git introuvable'

# --- 3. dépôt git, commandes git en échec (code retour 128) -----------------
run_gradle '3. dépôt git, git en échec (GIT_DIR bidon)' "${HERE}/.." GIT_DIR="${TMP}/pas-un-depot"
check_clean 'git en échec'
check_gitless 'git en échec'
if printf '%s' "$OUT" | grep -q 'GITVALUE versionName=.*fatal'; then
  ko 'git en échec : aucune fuite du message fatal dans versionName'
else
  ok 'git en échec : aucune fuite du message fatal dans versionName'
fi

# --- 4. git bavard : sortie multi-lignes, code 0 ----------------------------
run_gradle '4. git bavard (sortie multi-lignes)' "$TARBALL" PATH="${TMP}/bavard:${PATH}"
if [[ "$(version_code)" == "1799999999" ]]; then
  ok "git bavard : versionCode lit la 1re ligne (1799999999)"
else
  ko "git bavard : versionCode lit la 1re ligne (reçu '$(version_code)')"
fi
if [[ "$(version_name)" == "1.0.42-abc1234" ]]; then
  ok 'git bavard : versionName ne garde que la 1re ligne (1.0.42-abc1234)'
else
  ko "git bavard : versionName ne garde que la 1re ligne (reçu '$(version_name)')"
fi

printf '\n%s tests : %s ok, %s KO\n' "$((PASS + FAIL))" "$PASS" "$FAIL"
(( FAIL == 0 )) || exit 1
exit 0
