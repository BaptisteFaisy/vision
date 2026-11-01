#!/usr/bin/env bash
# Tests des hooks git du dépôt — une seule commande : bash tools/test-hooks.sh
#
# Ce qu'on protège (mesuré le 01/11/2026 sur BaptisteFaisy/vision) :
#   - 24 % des PR arrivent avec une base périmée (10 sur 42 en 4 h de balayage) ;
#     le balayeur doit les rebaser et une sur deux finit chez l'agent de conflit
#     (≈15 min de boucle LLM + un passage de CI) ;
#   - `master` reçoit ~300 commits par jour, donc une branche qui vit plus de
#     quelques heures est certaine d'être en retard.
# `tools/hooks/pre-push` rebase la branche sur `origin/master` AVANT le push :
# 11 ms quand tout est à jour, 0,25 s pour 400 commits de retard. Il ne touche
# jamais un push forcé (bail du balayeur), `master`, ni les branches de file.
#
# Tout se joue sur des dépôts synthétiques sous $TMPDIR : aucun réseau, aucun
# dépôt réel touché, aucune dépendance à un runner.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)" || exit 1
TMP="$(mktemp -d "${TMPDIR:-/tmp}/test-hooks.XXXXXX")" || exit 1
trap 'rm -rf -- "$TMP"' EXIT

PASS=0
FAIL=0
OUT=""
RC=0

run() { OUT="$("$@" 2>&1)"; RC=$?; }
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() {
  FAIL=$((FAIL + 1))
  printf '  KO   %s\n' "$1"
  printf '       code=%s sortie=%s\n' "$RC" "$(printf '%s' "${OUT:-}" | head -3 | tr '\n' ' ')"
}
expect_status() { if [[ "$RC" == "$1" ]]; then ok "$2"; else ko "$2 (attendu $1)"; fi; }
expect_nonzero() { if [[ "$RC" != "0" ]]; then ok "$1"; else ko "$1 (attendu non nul)"; fi; }
expect_eq() { if [[ "$OUT" == "$1" ]]; then ok "$2"; else ko "$2 (attendu '$1')"; fi; }
expect_grep() {
  if printf '%s' "$OUT" | grep -q -- "$1"; then ok "$2"; else ko "$2 (motif '$1' absent)"; fi
}
expect_nogrep() {
  if printf '%s' "$OUT" | grep -q -- "$1"; then RC=1; ko "$2 (motif '$1' présent)"; else ok "$2"; fi
}

# Constat du 01/11 : 24 % des PR (10 sur 42 en 4 h de balayage) arrivent avec
# une base déjà périmée ; le balayeur doit les rebaser et une sur deux finit
# chez l'agent de conflit (≈15 min de boucle LLM + un passage de CI). Le hook
# versionné `tools/hooks/pre-push` rebase AVANT le push : l'auteur voit le
# conflit dans son contexte, pour quelques centaines de ms. Vérifié ici sur des
# dépôts synthétiques : aucun réseau, aucun dépôt réel touché.
echo "== 1. hook pre-push : la branche part à jour =="
HOOK="${ROOT}/tools/hooks/pre-push"
run test -x "$HOOK"
expect_status 0 'tools/hooks/pre-push est exécutable'
run bash -n "$HOOK"
expect_status 0 'tools/hooks/pre-push : syntaxe shell valide'
run sh -n "$HOOK"
expect_status 0 'tools/hooks/pre-push : syntaxe POSIX valide'

H="${TMP}/hook"
BARE="${H}/BaptisteFaisy/vision.git"
mkdir -p "${H}/BaptisteFaisy" "${H}/hooks"
cp "$HOOK" "${H}/hooks/pre-push"
git init -q --bare "$BARE" || ko 'dépôt de test créé'
git -C "$BARE" symbolic-ref HEAD refs/heads/master
ident() {
  git -C "$1" config user.name BaptisteFaisy
  git -C "$1" config user.email 119856854+BaptisteFaisy@users.noreply.github.com
}
git clone -q "$BARE" "${H}/graine" 2>/dev/null
ident "${H}/graine"
printf 'a\n' >"${H}/graine/f.txt"
printf 'x\ny\nz\n' >"${H}/graine/g.txt"
git -C "${H}/graine" add -A && git -C "${H}/graine" commit -qm base
git -C "${H}/graine" push -q origin master || ko 'graine poussée'
git clone -q "$BARE" "${H}/work" 2>/dev/null
ident "${H}/work"
pousse() { local d="$1"; shift; run git -C "$d" -c core.hooksPath="${H}/hooks" "$@"; }
commit_dans() { local d="$1" m="$2"; git -C "$d" add -A >/dev/null; git -C "$d" commit -qm "$m"; }
expect_nogrep() {
  if printf '%s' "$OUT" | grep -q -- "$1"; then RC=1; ko "$2 (motif '$1' présent)"; else ok "$2"; fi
}

# (a) branche à jour : le hook ne doit rien dire ni rien faire.
git -C "${H}/work" checkout -q -b a-jour origin/master
printf 'b\n' >"${H}/work/h.txt"; commit_dans "${H}/work" "à jour"
pousse "${H}/work" push -q origin a-jour
expect_status 0 'branche à jour : push accepté'
expect_nogrep 'rebasée' 'branche à jour : aucun rebase inutile'

# (b) branche périmée : rebasée avant le push, sans conflit.
printf 'a2\n' >>"${H}/graine/f.txt"; commit_dans "${H}/graine" "master avance"
git -C "${H}/graine" push -q origin master
git -C "${H}/work" fetch -q origin
git -C "${H}/work" checkout -q -b perimee origin/master~1
printf 'nouveau\n' >"${H}/work/n.txt"; commit_dans "${H}/work" "périmée"
pousse "${H}/work" push -q origin perimee
expect_status 0 'branche périmée : push accepté après rebase'
expect_grep 'rebasée sur master' 'branche périmée : le hook annonce le rebase'
run git -C "${H}/work" rev-list --count HEAD..origin/master
expect_eq 0 'branche périmée : zéro commit de retard après le push'

# (c) conflit réel : push bloqué, branche intacte, rien sur le distant.
printf 'x-master\n' >"${H}/graine/g.txt"; commit_dans "${H}/graine" "master touche g"
git -C "${H}/graine" push -q origin master
git -C "${H}/work" fetch -q origin
git -C "${H}/work" checkout -q -B conflit origin/master~1
printf 'x-branche\n' >"${H}/work/g.txt"; commit_dans "${H}/work" "branche touche g"
TETE_AVANT="$(git -C "${H}/work" rev-parse HEAD)"
pousse "${H}/work" push -q origin conflit
expect_nonzero 'conflit : push refusé'
expect_grep 'CONFLIT avec master' 'conflit : le hook dit le conflit'
expect_grep 'g.txt' 'conflit : le hook nomme le fichier en conflit'
expect_grep 'VISION_NO_FRESH_PUSH' 'conflit : le hook donne la porte de sortie'
run git -C "${H}/work" rev-parse HEAD
expect_eq "$TETE_AVANT" 'conflit : la branche locale est rendue intacte'
run git -C "${BARE}" rev-parse --verify --quiet refs/heads/conflit
expect_nonzero 'conflit : rien n a été poussé sur le distant'
run git -C "${H}/work" status --porcelain
expect_eq '' 'conflit : arbre de travail propre après abandon du rebase'

# (d) arbre sale : on prévient, on ne bloque pas.
git -C "${H}/work" checkout -q -b sale origin/master~2
printf 's\n' >"${H}/work/s.txt"; commit_dans "${H}/work" "sale"
printf 'modif non commitée\n' >>"${H}/work/s.txt"
pousse "${H}/work" push -q origin sale
expect_status 0 'arbre sale : push accepté'
expect_grep 'arbre sale' 'arbre sale : le hook explique le rebase sauté'

# (e) push forcé : jamais de rebase sous un bail.
git -C "${H}/work" reset -q --hard
git -C "${H}/work" checkout -q -B force origin/master
printf 'f\n' >"${H}/work/f2.txt"; commit_dans "${H}/work" "v1"
pousse "${H}/work" push -q origin force
git -C "${H}/work" commit -q --amend -m "v2"
pousse "${H}/work" push -q --force-with-lease origin force
expect_status 0 'push forcé : accepté tel quel'
expect_grep 'push forcé' 'push forcé : le hook annonce le saut'

# (f) hors dépôt vision : le hook ne touche à rien.
git init -q --bare "${H}/autre.git"
git clone -q "${H}/autre.git" "${H}/ailleurs" 2>/dev/null
ident "${H}/ailleurs"
printf 'z\n' >"${H}/ailleurs/z.txt"; commit_dans "${H}/ailleurs" "base"
git -C "${H}/ailleurs" push -q origin master
git -C "${H}/ailleurs" checkout -q -b ailleurs origin/master~1 2>/dev/null || git -C "${H}/ailleurs" checkout -q -b ailleurs
printf 'z2\n' >"${H}/ailleurs/z.txt"; commit_dans "${H}/ailleurs" "autre dépôt"
pousse "${H}/ailleurs" push -q origin ailleurs
expect_status 0 'autre dépôt : push accepté'
expect_nogrep 'rebasée' 'autre dépôt : le hook reste muet'

# Le shim vit dans le dossier de hooks global (~/.git-hooks, qui porte déjà la
# garde d'identité) : il délègue au script versionné du dépôt courant, donc un
# `git pull` met le hook à jour sans réinstaller quoi que ce soit.
echo "== 2. tools/install-hooks.sh =="
INST="${ROOT}/tools/install-hooks.sh"
run test -x "$INST"
expect_status 0 'tools/install-hooks.sh est exécutable'
run bash -n "$INST"
expect_status 0 'tools/install-hooks.sh : syntaxe shell valide'

D="${TMP}/hooksglob"
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST" --verifier
expect_nonzero 'install-hooks : --verifier dit le shim absent'
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST"
expect_status 0 'install-hooks : installe le shim'
run test -x "$D/pre-push"
expect_status 0 'install-hooks : shim exécutable'
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST" --verifier
expect_status 0 'install-hooks : --verifier confirme'
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST"
expect_status 0 'install-hooks : idempotent'
run bash -c "ls '$D' | wc -l"
expect_eq 1 'install-hooks : un seul fichier, aucune doublure'
printf '#!/bin/sh\nexit 0\n' >"$D/pre-push"
chmod +x "$D/pre-push"
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST"
expect_status 0 'install-hooks : réinstalle par-dessus un hook étranger'
run test -x "$D/pre-push.local"
expect_status 0 'install-hooks : hook étranger préservé en pre-push.local'
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST" --uninstall
expect_status 0 'install-hooks : --uninstall retire le shim'
run env ZC_GIT_HOOKS_DIR="$D" sh "$INST" --verifier
expect_nonzero 'install-hooks : shim bien retiré'


printf '\n%s tests : %s ok, %s KO\n' "$((PASS + FAIL))" "$PASS" "$FAIL"
(( FAIL == 0 )) || exit 1
exit 0
