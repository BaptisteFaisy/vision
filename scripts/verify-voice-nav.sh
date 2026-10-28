#!/usr/bin/env bash
# Recette navigation vocale en UNE prise : commande parlée, confirmation,
# guidage Maps, hors-trace, arrivée. L'intégrateur parle (5 phrases), le script
# capture l'observable (écran, app au premier plan, crash) et rend la checklist.
# Env : PHONE_SERIAL (défaut 2c6852ac), APK, ADB (voir --help).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : verify-voice-nav.sh [--help]

Recette interactive en une prise, sur téléphone réel (jamais d'émulateur :
la voix et Google Maps ne s'y prêtent pas). Installe l'APK, vérifie que le
build vient du HEAD de origin/master (règle ISEE-TEST-MASTER-SEUL), puis
déroule 5 étapes — commande vocale, confirmation, guidage, hors-trace,
arrivée — en demandant à l'intégrateur de parler à chaque étape, et imprime
la checklist remplie (observé + réponses). Script de diagnostic : il raconte
ce qu'il voit, il ne valide pas par un code de sortie (seule une panne
d'infrastructure — appareil, APK, SHA — interrompt).

Phrases à dicter (verrouillées par VoiceNavScriptTest, recopies exactes) :
  « je veux aller à la gare de Lyon » puis « Emmène-moi à la tour Eiffel ».

Variables d'environnement :
  PHONE_SERIAL  série adb du téléphone (défaut : 2c6852ac)
  APK           chemin de l'APK (défaut : <dépôt>/app/build/outputs/apk/debug/app-debug.apk)
  ADB           binaire adb (défaut : adb du PATH, sinon $ANDROID_HOME/platform-tools/adb)

Aucune valeur secrète n'est lue ni affichée : le logcat est compté, jamais recopié.
EOF

PKG="dev.sam3vision.app"
MAPS="com.google.android.apps.maps"
SERIAL=""
APK_PATH=""
UI_XML=""
REMOTE_UI=""
REPORT=""
# EVIDENCE : dossier du procès-verbal + captures (mktemp séparé, jamais nettoyé
# par le trap : la preuve survit à la recette, comme la capture d'install-emulator).
EVIDENCE=""

# call <commande adb shell...> : sortie et erreurs regroupées, délai 40 s.
call() { timeout 40 "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# dump : uiautomator dump → UI_XML, 3 tentatives (le premier dump échoue souvent).
dump() {
  local _
  for _ in 1 2 3; do
    call "uiautomator dump $REMOTE_UI >/dev/null 2>&1; cat $REMOTE_UI" >"$UI_XML" || :
    grep -q '</hierarchy>' "$UI_XML" && return 0
  done
  return 1
}

# ui : texte des nœuds de l'écran courant, sur une ligne bornée à 200 caractères.
ui() {
  dump || :
  grep -o 'text="[^"]*"' "$UI_XML" | sed 's/text=//' | grep -v '^""$' |
    tr '\n' '|' | cut -c1-200 || :
  echo
}

# focused : app au premier plan (ligne mFocusedApp, bornée, sans secret).
focused() { call "dumpsys activity activities" | grep -m1 'mFocusedApp' | cut -c1-160 || :; }
maps_pid() { call "pidof $MAPS" | tr -d '\r ' || :; }
crashes() { call "logcat -d -b crash" | grep -c "$PKG" || :; }

# note <ligne> : ajoute au procès-verbal (fichier + écran).
note() { printf '%s\n' "$1" | tee -a "$REPORT"; }

install_apk() {
  local out
  if ! out="$(timeout 240 "$ADB" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"; then
    printf '%s\n' "$out" >&2
    die "échec de l'installation de $APK_PATH sur $SERIAL"
  fi
  printf '%s\n' "$out" | tail -1
}

# back_to_isee : l'app reprend l'écran où elle était ; on revient à iSee avant de tester.
back_to_isee() {
  local _
  for _ in 1 2 3; do
    dump || :
    grep -q 'Photographier\|Dicter\|Pose' "$UI_XML" && return 0
    call "input keyevent KEYCODE_BACK" >/dev/null || :
    sleep 2
  done
  return 0
}

# installed_sha : SHA court embarqué dans versionName (1.0.<n>-<sha>), ou vide.
installed_sha() {
  call "dumpsys package $PKG" | grep -m1 'versionName' |
    grep -oE '\-[0-9a-f]{7,40}' | tail -1 | tr -d '-' | tr -d '\r' || :
}

# expect_sha : HEAD distant (chaîne vide si le réseau refuse : on prévient, on note).
expect_sha() {
  git -C "$ROOT" ls-remote origin master 2>/dev/null | cut -f1 | tr -d '\r' || :
}

# shot <nom> : capture d'écran rangée avec le procès-verbal (survit à la recette).
shot() {
  local remote="/sdcard/isee_nav_$1_$$.png" dest="${EVIDENCE}/shot-$1.png"
  call "screencap -p $remote" >/dev/null || :
  timeout 40 "$ADB" -s "$SERIAL" pull "$remote" "$dest" >/dev/null 2>&1 || :
  call "rm -f $remote" >/dev/null || :
  [[ -s "$dest" ]] && printf '%s' "$dest" || printf '(capture impossible)'
}

# dire <phrase> : l'intégrateur fait appui long + dicte ; Entrée quand c'est fait.
dire() {
  printf '\n  PARLEZ — appui long sur iSee (≥ 300 ms), dictez puis relâchez :\n'
  printf '    « %s »\n' "$1"
  printf '  Entrée quand c\u2019est fait : '
  local _junk=""
  read -r _junk || _junk=""
}

# ask <question> : réponse oui/non de l'intégrateur, rendue telle quelle.
ask() {
  local ans=""
  printf '  %s [o/n] : ' "$1"
  read -r ans || ans=""
  case "$ans" in
    o | O | y | Y | oui | OUI) printf 'oui' ;;
    *) printf 'non (saisi : %s)' "$ans" ;;
  esac
}

main() {
  std_args "verify-voice-nav.sh" "$USAGE" "$@"
  resolve_adb
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  require_cmd git "Installez git (vérification du SHA master)."
  SERIAL="${PHONE_SERIAL:-2c6852ac}"

  APK_PATH="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  [[ -f "$APK_PATH" ]] ||
    die "APK absent : $APK_PATH (build-serveur sur le r630, jamais Gradle ici)"

  [[ -t 0 ]] || die "recette interactive : lancez dans un terminal (voix + Entrée à chaque étape)."
  [[ "$(timeout 20 "$ADB" -s "$SERIAL" get-state 2>&1)" == "device" ]] ||
    die "série $SERIAL pas en ligne (adb devices, câble, autorisation RSA)."

  make_tmpdir
  UI_XML="${TMP_WORK}/nav-ui.xml"
  REMOTE_UI="/sdcard/isee_nav_$$.xml"
  EVIDENCE="$(mktemp -d "${TMPDIR:-/tmp}/voice-nav.XXXXXX")" ||
    die "mktemp -d a échoué pour le procès-verbal (TMPDIR='${TMPDIR:-/tmp}')"
  REPORT="${EVIDENCE}/voice-nav-report.txt"
  trap cleanup EXIT
  { echo "Procès-verbal navigation vocale — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"; echo; } >"$REPORT"

  note "### 0. prérequis (SHA master, install, Maps, réglage)"
  install_apk
  local want="" got=""
  want="$(expect_sha)"
  got="$(installed_sha)"
  note "  SHA attendu (origin/master) : ${want:-inconnu — vérifiez git ls-remote à la main}"
  note "  SHA installé (versionName)  : ${got:-illisible}"
  if [[ -n "$want" && -n "$got" ]]; then
    case "$want" in
      "$got"*) note "  SHA : CONFORME (build du HEAD master)" ;;
      *) die "SHA NON CONFORME : l'APK ne vient pas du HEAD de origin/master (règle ISEE-TEST-MASTER-SEUL)" ;;
    esac
  else
    warn "SHA non vérifié automatiquement — notez-le à la main dans le procès-verbal."
  fi
  if call "pm list packages" | grep -q "$MAPS"; then
    note "  Google Maps : installé"
  else
    note "  Google Maps : ABSENT — la recette déroulera les replis (navigateur)"
  fi
  call "pm grant $PKG android.permission.CAMERA" || :
  call "pm grant $PKG android.permission.RECORD_AUDIO" || :
  call "am force-stop $PKG" >/dev/null || :
  call "am start -n $PKG/.MainActivity" >/dev/null || warn "am start a échoué."
  sleep 9
  back_to_isee
  note "  réglage « Guider vers la destination confirmée » sur OUI : $(ask 'Réglages iSee : la bascule guidage est-elle sur OUI')"
  note "  clés voix renseignées (ElevenLabs + voix) : $(ask 'Réglages iSee : clé ElevenLabs et voix renseignées')"

  note "### 1. commande vocale (appui long + dictée)"
  dire "je veux aller à la gare de Lyon"
  sleep 4
  note "  écran après dictée : $(ui)"
  note "  transcription affichée : $(ask 'La transcription « je veux aller à la gare de Lyon » s\u2019affiche-t-elle')"
  note "  crash iSee : $(crashes) | pid : $(call "pidof $PKG" | tr -d '\r ')"

  note "### 2. confirmation (voix + destination)"
  note "  voix « Navigation vers la gare de Lyon » entendue : $(ask 'Avez-vous entendu « Navigation vers la gare de Lyon »')"
  note "  écran : $(ui)"

  note "### 3. guidage (Google Maps prend l'écran)"
  sleep 3
  note "  premier plan : $(focused)"
  note "  pid Maps : $(maps_pid)"
  note "  capture : $(shot guidance)"
  note "  guidage Maps actif vers la gare de Lyon : $(ask 'Maps guide-t-il vers la gare de Lyon')"
  local mode=""
  printf '  mode observé dans Maps (voiture/piéton/vélo/transports) : '
  read -r mode || mode=""
  note "  mode observé : ${mode:-non saisi} (câblé : voiture — TravelMode.DRIVE, écart avec NAVIGATION.md relevé)"

  note "### 4. hors-trace (Maps recalcule, iSee se tait)"
  printf '\n  Écartez-vous de l\u2019itinéraire (un pâté de maisons suffit).\n'
  printf '  Entrée quand Maps a réagi : '
  local _junk=""
  read -r _junk || _junk=""
  note "  Maps a recalculé seul : $(ask 'Maps a-t-il recalculé sans toucher à iSee')"
  note "  iSee silencieux (ni voix, ni overlay, ni « écarté ») : $(ask 'iSee n\u2019a-t-il rien dit ni affiché')"
  note "  crash iSee : $(crashes)"

  note "### 5. arrivée (Maps annonce, iSee se tait)"
  printf '\n  Allez au bout de l\u2019itinéraire (ou terminez la démo dans Maps).\n'
  printf '  Entrée quand l\u2019arrivée est annoncée : '
  _junk=""
  read -r _junk || _junk=""
  note "  Maps a annoncé l'arrivée : $(ask 'Maps a-t-il annoncé l\u2019arrivée')"
  note "  iSee silencieux : $(ask 'iSee n\u2019a-t-il rien dit ni affiché')"
  call "am start -n $PKG/.MainActivity" >/dev/null || :
  sleep 4
  note "  retour iSee : $(ui | cut -c1-120)"
  note "  capture : $(shot arrivee)"
  note "  crash iSee : $(crashes) | pid : $(call "pidof $PKG" | tr -d '\r ')"

  note ""
  note "Fin — procès-verbal : $REPORT (captures : ${EVIDENCE}/shot-*.png)"
}

# cleanup : supprime le dump distant et les temporaires locaux, sans jamais échouer.
cleanup() {
  if [[ -n "${REMOTE_UI:-}" && -n "${ADB:-}" && -n "${SERIAL:-}" ]]; then
    timeout 20 "$ADB" -s "$SERIAL" shell rm -f "$REMOTE_UI" >/dev/null 2>&1 || :
  fi
  [[ -n "${TMP_WORK:-}" ]] && safe_rm_rf "$TMP_WORK" || :
}

main "$@"
