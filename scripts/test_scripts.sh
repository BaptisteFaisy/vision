#!/usr/bin/env bash
# Tests de `scripts/` — une seule commande : bash scripts/test_scripts.sh
# Sort 0 si tout passe, 1 sinon. Aucun appareil réel n'est contacté : `adb` est
# remplacé par un faux binaire, et rien de Gradle n'est lancé.
set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" || exit 1
LIB="${HERE}/lib/common.sh"
. "$LIB" || { printf 'ERREUR : %s introuvable\n' "$LIB" >&2; exit 1; }

BUILD="${HERE}/build.sh"
INSTALL="${HERE}/install-emulator.sh"
VERIFY="${HERE}/verify-tap-voix.sh"
VERIFY_PORT="${HERE}/verify-longpress-port.sh"
LATEST="${HERE}/install-phone-latest.sh"
VERIFY_NAV="${HERE}/verify-voice-nav.sh"
VERIFY_STREET="${HERE}/verify-street.sh"
SCRIPTS=("$BUILD" "$INSTALL" "$VERIFY" "$VERIFY_PORT" "$LATEST" "$VERIFY_NAV" "$VERIFY_STREET")

TMP="$(mktemp -d "${TMPDIR:-/tmp}/test-scripts.XXXXXX")" || exit 1
trap 'safe_rm_rf "$TMP"' EXIT

PASS=0
FAIL=0
OUT=""
RC=0

# --- petit harnais ---------------------------------------------------------
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

# --- faux adb --------------------------------------------------------------
# Ne contacte rien. STUB_OFFLINE=1 simule un émulateur éteint ; `pull` crée le
# fichier de destination pour que la capture soit vérifiable.
mkdir -p "${TMP}/bin"
cat >"${TMP}/bin/adb" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
while [[ "${1:-}" == "-s" ]]; do shift 2; done
case "${1:-}" in
  devices)
    printf 'List of devices attached\n'
    [[ "${STUB_OFFLINE:-0}" == "1" ]] || printf 'emulator-5554\tdevice\n'
    ;;
  pull) [[ -n "${3:-}" ]] && printf 'PNG\n' >"$3" ;;
esac
exit 0
STUB
chmod +x "${TMP}/bin/adb"
PATH_BIN="${TMP}/bin:${PATH}"

printf 'fake apk\n' >"${TMP}/app-debug.apk"
mkdir -p "${TMP}/fakejdk/bin"
printf '#!/bin/sh\nexit 0\n' >"${TMP}/fakejdk/bin/javac"
chmod +x "${TMP}/fakejdk/bin/javac"
mkdir -p "${TMP}/sdk"
mkdir -p "${TMP}/homevide"

# --- 1. syntaxe et hygiène de base ----------------------------------------
echo "== 1. syntaxe, options, hygiène shell =="
for f in "${SCRIPTS[@]}" "$LIB" "${HERE}/test_scripts.sh"; do
  run bash -n "$f"
  expect_status 0 "bash -n $(basename -- "$f")"
done

for f in "${SCRIPTS[@]}"; do
  run grep -q '^set -euo pipefail$' "$f"
  expect_status 0 "$(basename -- "$f") porte 'set -euo pipefail'"
  run bash "$f" --help
  expect_status 0 "$(basename -- "$f") --help sort 0"
  expect_grep 'Usage' "$(basename -- "$f") --help affiche l'usage"
  run bash "$f" --bogus
  expect_status 2 "$(basename -- "$f") --bogus sort 2"
  expect_grep 'argument inconnu' "$(basename -- "$f") --bogus explique l'erreur"
done

# --- 2. build.sh -----------------------------------------------------------
echo "== 2. build.sh : gardes d'environnement =="
run env JAVA_HOME="${TMP}/fakejdk" ANDROID_HOME="${TMP}/pas-de-sdk" bash "$BUILD"
expect_nonzero 'build.sh refuse un ANDROID_HOME inexistant'
expect_grep 'ANDROID_HOME' 'build.sh nomme ANDROID_HOME'

run env JAVA_HOME="${TMP}/fakejdk" ANDROID_HOME="${TMP}/sdk" GRADLE_TIMEOUT=abc bash "$BUILD"
expect_nonzero 'build.sh refuse un GRADLE_TIMEOUT non numérique'
expect_grep 'GRADLE_TIMEOUT' 'build.sh nomme GRADLE_TIMEOUT'

run env HOME="${TMP}/homevide" JAVA_HOME= ANDROID_HOME="${TMP}/sdk" bash "$BUILD"
expect_nonzero 'build.sh échoue sans JDK fournissant javac'
expect_grep 'JDK' 'build.sh explique le JDK manquant'

# --- 3. install-emulator.sh ------------------------------------------------
echo "== 3. install-emulator.sh =="
run env PATH="$PATH_BIN" SKIP_BUILD=1 APK="${TMP}/absent.apk" bash "$INSTALL"
expect_nonzero 'install-emulator refuse un APK absent'
expect_grep 'APK absent' 'install-emulator nomme l APK manquant'

run env PATH="$PATH_BIN" SKIP_BUILD=1 APK="${TMP}/app-debug.apk" STUB_OFFLINE=1 bash "$INSTALL"
expect_nonzero 'install-emulator refuse un émulateur hors ligne'
expect_grep 'pas en ligne' 'install-emulator dit que la série est hors ligne'

run env PATH="$PATH_BIN" SKIP_BUILD=1 APK="${TMP}/app-debug.apk" TMPDIR="$TMP" bash "$INSTALL"
expect_status 0 'install-emulator chemin nominal (adb factice)'
expect_grep '^OK :' 'install-emulator conclut par OK'
shot="$(printf '%s' "$OUT" | sed -n 's/^OK : .*capture : //p')"
if [[ -n "$shot" && -s "$shot" ]]; then
  ok 'install-emulator produit une capture non vide'
else
  ko "install-emulator produit une capture non vide (chemin='$shot')"
fi

run env ADB="${TMP}/pas-un-adb" APK="${TMP}/app-debug.apk" bash "$INSTALL"
expect_nonzero 'install-emulator refuse un ADB non exécutable'
expect_grep 'ADB=' 'install-emulator nomme ADB'

# --- 4. verify-tap-voix.sh -------------------------------------------------
echo "== 4. verify-tap-voix.sh =="
run env PATH="$PATH_BIN" APK="${TMP}/absent.apk" bash "$VERIFY"
expect_nonzero 'verify-tap-voix refuse un APK absent'
expect_grep 'APK absent' 'verify-tap-voix nomme l APK manquant'

run env PATH="$PATH_BIN" APK="${TMP}/absent.apk" bash "$VERIFY_PORT"
expect_nonzero 'verify-longpress-port refuse un APK absent'
expect_grep 'APK absent' 'verify-longpress-port nomme l APK manquant'

run env PATH="$PATH_BIN" APK="${TMP}/absent.apk" bash "$VERIFY_NAV"
expect_nonzero 'verify-voice-nav refuse un APK absent'
expect_grep 'APK absent' 'verify-voice-nav nomme l APK manquant'

run env PATH="$PATH_BIN" APK="${TMP}/absent.apk" bash "$VERIFY_STREET"
expect_nonzero 'verify-street refuse un APK absent'
expect_grep 'APK absent' 'verify-street nomme l APK manquant'

# --- 5. safe_rm_rf ---------------------------------------------------------
echo "== 5. safe_rm_rf : garde des suppressions =="
run bash -c '. "$1"; safe_rm_rf ""' _ "$LIB"
expect_nonzero 'safe_rm_rf refuse un chemin vide'
run bash -c '. "$1"; safe_rm_rf "${2}/../.."' _ "$LIB" "$TMP"
expect_nonzero "safe_rm_rf refuse un chemin avec '..'"
run bash -c '. "$1"; safe_rm_rf "/etc"' _ "$LIB"
expect_nonzero 'safe_rm_rf refuse hors de TMPDIR'
mkdir -p "${TMP}/a-|nettoyer/sous"
run bash -c '. "$1"; safe_rm_rf "$2"' _ "$LIB" "${TMP}/a-|nettoyer"
expect_status 0 'safe_rm_rf supprime un chemin sous TMPDIR'
if [[ -e "${TMP}/a-|nettoyer" ]]; then ko 'safe_rm_rf a bien effacé la cible'; else ok 'safe_rm_rf a bien effacé la cible'; fi

# --- 5 bis. install-phone-latest.sh --------------------------------------
echo "== 5 bis. install-phone-latest.sh : miroir et pause =="
run env VISION_MIRROR="${TMP}/pas-de-miroir" VISION_PHONE_STATE="${TMP}/etat" bash "$LATEST"
expect_nonzero 'install-phone-latest refuse un miroir absent'
expect_grep 'miroir absent' 'install-phone-latest nomme le miroir manquant'
expect_grep '--init' 'install-phone-latest indique --init'

run env VISION_PHONE_STATE="${TMP}/etat" bash "$LATEST" --lock "recette micro"
expect_status 0 'install-phone-latest --lock sort 0'
if [ -f "${TMP}/etat/lock" ]; then ok 'install-phone-latest --lock écrit le verrou'; else ko 'install-phone-latest --lock écrit le verrou'; fi

run env VISION_PHONE_STATE="${TMP}/etat" VISION_MIRROR="${TMP}/pas-de-miroir" bash "$LATEST"
expect_status 0 'install-phone-latest respecte le verrou (aucun build)'
expect_grep 'verrouillé' 'le verrou dit qui le tient'

run env VISION_PHONE_STATE="${TMP}/etat" bash "$LATEST" --unlock
expect_status 0 'install-phone-latest --unlock sort 0'
if [ ! -f "${TMP}/etat/lock" ]; then ok 'install-phone-latest --unlock retire le verrou'; else ko 'install-phone-latest --unlock retire le verrou'; fi

run grep -n 'build-keys.properties' "$LATEST"
expect_status 0 'install-phone-latest lie le fichier de cles de la machine'
run grep -nE '(cat|echo|tee|printf|head|tail)[^#]*build-keys' "$LATEST"
expect_nonzero 'le fichier de cles ne sort jamais du script'

# --- 6. garde-fous statiques ----------------------------------------------
echo "== 6. garde-fous statiques =="
run grep -nE '(^|[^[:alnum:]_])eval[[:space:]]' "${SCRIPTS[@]}" "$LIB"
expect_nonzero 'aucun eval sur une donnée externe'

run grep -nE '/tmp/[A-Za-z0-9._-]+' "${SCRIPTS[@]}" "$LIB"
expect_nonzero 'aucun fichier temporaire à nom fixe dans /tmp'

run grep -nE '/home/' "${SCRIPTS[@]}" "$LIB"
expect_nonzero 'aucun chemin absolu de machine'

run grep -nE '(cat|echo|tee|printf|head|tail)[^#]*local\.properties' "${SCRIPTS[@]}" "$LIB"
expect_nonzero 'local.properties jamais affiché'

run grep -c 'rm -rf' "$LIB"
expect_eq 1 'une seule suppression récursive, dans safe_rm_rf'
run grep -n 'rm -rf' "${SCRIPTS[@]}"
expect_nonzero 'aucun rm -rf brut dans les scripts'

for f in "${SCRIPTS[@]}" "$LIB" "${HERE}/test_scripts.sh"; do
  lines="$(wc -l <"$f")"
  if (( lines < 500 )); then ok "$(basename -- "$f") < 500 lignes ($lines)"; else
    RC=$lines; OUT=""; ko "$(basename -- "$f") < 500 lignes ($lines)"
  fi
done

# --- 7. recette navigation vocale : phrases E2E verrouillées -----------------
# Les phrases dictées sur le téléphone sont recopiées à l'identique dans le
# script, la fiche et le verrou JVM : toute dérive casse ici.
echo "== 7. recette navigation vocale : phrases E2E =="
NAV_TEST="${ROOT}/core/src/test/java/dev/sam3vision/core/nav/VoiceNavScriptTest.kt"
NAV_DOC="${HERE}/../docs/RECETTE-NAVIGATION-VOCALE.md"
for f in "$NAV_TEST" "$NAV_DOC"; do
  run test -f "$f"
  expect_status 0 "$(basename -- "$f") existe"
done
run grep -q -F 'je veux aller à la gare de Lyon' "$VERIFY_NAV"
expect_status 0 'le script dicte la gare de Lyon'
run grep -q -F 'Emmène-moi à la tour Eiffel' "$VERIFY_NAV"
expect_status 0 'le script cite la tour Eiffel'
run grep -q -F 'je veux aller à la gare de Lyon' "$NAV_DOC"
expect_status 0 'la fiche dicte la gare de Lyon'
run grep -q -F 'Où est la pharmacie la plus proche' "$NAV_DOC"
expect_status 0 'la fiche contre-éprouve la question'
run grep -q -F 'Emmène-moi à »' "$NAV_DOC"
expect_status 0 'la fiche cite la destination vide'
run grep -q -F 'je veux aller à la gare de Lyon' "$NAV_TEST"
expect_status 0 'le verrou JVM dicte la gare de Lyon'
run grep -q -F 'Emmène-moi à la tour Eiffel' "$NAV_TEST"
expect_status 0 'le verrou JVM dicte la tour Eiffel'
run grep -q -F 'Où est la pharmacie la plus proche' "$NAV_TEST"
expect_status 0 'le verrou JVM contre-éprouve la question'
run grep -q -F '"Emmène-moi à"' "$NAV_TEST"
expect_status 0 'le verrou JVM cite la destination vide'
run grep -q -F '"va à gauche"' "$NAV_TEST"
expect_status 0 'le verrou JVM documente le piège cap'

# --- 8. recette rue : phrases E2E verrouillées ------------------------------
# Les phrases dictées puis écoutées dans la rue sont recopiées à l'identique
# dans le script et la fiche : toute dérive casse ici.
echo "== 8. recette rue : phrases E2E =="
STREET_DOC="${HERE}/../docs/RECETTE-RUE.md"
run test -f "$STREET_DOC"
expect_status 0 "RECETTE-RUE.md existe"
for phrase in 'je veux aller à' 'Passage protégé dans' 'Mode traversée activé.' 'Traversée terminée.' 'Attention au poteau' 'Vous êtes arrivé à' 'Continuez dans cette direction' 'Tournez à' '≤ 4 m'; do
  run grep -q -F "$phrase" "$VERIFY_STREET"
  expect_status 0 "verify-street cite $phrase"
  run grep -q -F "$phrase" "$STREET_DOC"
  expect_status 0 "RECETTE-RUE.md cite $phrase"
done
run grep -q -F 'PHONE_WAIT' "$VERIFY_STREET"
expect_status 0 'verify-street passe par le verrou téléphone (PHONE_WAIT)'

# --- bilan -----------------------------------------------------------------
printf '\n%s tests : %s ok, %s KO\n' "$((PASS + FAIL))" "$PASS" "$FAIL"
(( FAIL == 0 )) || exit 1
exit 0