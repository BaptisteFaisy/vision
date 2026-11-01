#!/usr/bin/env bash
# Recette rue v4 en UNE prise (~15 min) : destination dictée (« mode GPS »),
# obstacles en clips anglais (voiture, humain, mobilier, bordure, escalier,
# passage piéton), virage synthèse, traversée (mode + verdict), rappel GPS,
# repli, arrivée. Miroir exact de docs/RECETTE-RUE.md (les phrases E2E sont
# recopiées à l'identique ici et dans la fiche — § 8 de test_scripts.sh).
# L'intégrateur marche et parle, le script capture l'observable (phrases
# entendues horodatées, écran, crash) et rend la checklist. Téléphone via le
# verrou ~/bin/phone, jamais de force (PHONE_WAIT=0 : occupé = on s'arrête,
# on relance plus tard).
# Env : PHONE_SERIAL (défaut 2c6852ac), APK, ADB, PHONE_BIN, PHONE_WAIT, EXPECT_REF (défaut master).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : verify-street.sh [--help]

Recette rue v4 en une prise (~15 min), sur téléphone réel (jamais
d'émulateur : GPS, Valhalla et marche réelle ne s'y prêtent pas). Installe
l'APK, vérifie que le build vient du HEAD attendu sur origin (EXPECT_REF,
défaut master — règle ISEE-TEST-MASTER-SEUL, qui se lit « HEAD de la ref
recettée »), puis déroule le parcours v4 — dictée « mode GPS », obstacles
en clips anglais, virage, traversée, rappel GPS, repli, arrivée — en
demandant à l'intégrateur de marcher et de noter chaque phrase entendue,
et imprime la checklist remplie (observé + réponses, horodatés). Script de
diagnostic : il raconte ce qu'il voit, il ne valide pas par un code de
sortie (seule une panne d'infrastructure — appareil, APK, SHA, verrou —
interrompt). Miroir de docs/RECETTE-RUE.md (phrases E2E à l'identique).

Tout le téléphone passe par le verrou ~/bin/phone (règle AGENTS.md §10,
jamais d'adb nu), avec PHONE_WAIT=0 : téléphone occupé = arrêt immédiat,
on relance plus tard, jamais de force.

Variables d'environnement :
  PHONE_SERIAL  série adb du téléphone (défaut : 2c6852ac)
  APK           chemin de l'APK (défaut : <dépôt>/app/build/outputs/apk/debug/app-debug.apk)
  ADB           binaire adb (défaut : adb du PATH, sinon $ANDROID_HOME/platform-tools/adb)
  PHONE_BIN     verrou téléphone (défaut : $HOME/bin/phone)
  PHONE_WAIT    attente max du verrou en secondes (défaut : 0, jamais de force)
  EXPECT_REF    branche attendue sur origin pour le SHA (défaut : master ;
                mettre la branche d'intégration pour recetter une branche)

Aucune valeur secrète n'est lue ni affichée : le logcat est compté, jamais recopié.
EOF

PKG="dev.sam3vision.app"
SERIAL=""
APK_PATH=""
UI_XML=""
REMOTE_UI=""
REPORT=""
# EVIDENCE : dossier du procès-verbal + captures (mktemp séparé, jamais nettoyé
# par le trap : la preuve survit à la recette, comme la capture d'install-emulator).
EVIDENCE=""
PHONE=""
PHONE_WAIT="${PHONE_WAIT:-0}"

# ph <libellé> <args adb...> : seul point de passage du téléphone, le verrou.
# Sort 2 si le téléphone est tenu par un autre agent (jamais de force).
ph() {
  local label="$1"
  shift
  timeout "$((PHONE_WAIT + 120))" "$PHONE" run --wait "$PHONE_WAIT" \
    --task "rue: $label" -- "$ADB" -s "$SERIAL" "$@"
}

# require_phone : le verrou répond et la série est en ligne, sinon on s'arrête.
# Appelée en début de chaque étape (jamais dans une substitution : die y sort vraiment).
require_phone() {
  local st rc=0
  st="$(ph "présence" get-state 2>&1)" || rc=$?
  [[ "$rc" == "2" ]] && die "téléphone occupé (tenu par un autre agent) — relancez plus tard, jamais de force."
  [[ "$st" == "device" ]] || die "série $SERIAL pas en ligne (adb devices, câble, autorisation RSA)."
}

# call <commande adb shell...> : sortie et erreurs regroupées, via le verrou.
# Un verrou occupé en cours d'étape donne un observable vide (signalé) ; le
# contrôle strict est fait par require_phone en début d'étape.
call() {
  local out rc=0
  out="$(ph "shell $1" shell "$@" 2>&1)" || rc=$?
  [[ "$rc" == "2" ]] && warn "téléphone occupé pendant : $* (observable vide, require_phone tranche à l'étape suivante)"
  printf '%s\n' "$out"
  return "$rc"
}

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
crashes() { call "logcat -d -b crash" | grep -c "$PKG" || :; }

# note <ligne> : ajoute au procès-verbal (fichier + écran).
note() { printf '%s\n' "$1" | tee -a "$REPORT"; }

# obs <ligne> : observable horodaté (heure locale de la prise).
obs() { note "  [$(date '+%H:%M:%S')] $1"; }

install_apk() {
  local out rc=0
  out="$(timeout 420 "$PHONE" run --wait "$PHONE_WAIT" --task "rue: installation APK" \
    -- "$ADB" -s "$SERIAL" install -r "$APK_PATH" 2>&1)" || rc=$?
  [[ "$rc" == "2" ]] && die "téléphone occupé (tenu par un autre agent) — relancez plus tard, jamais de force."
  if [[ "$rc" != "0" ]]; then
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

# expect_sha : HEAD distant de la ref attendue (EXPECT_REF, défaut master).
# Chaîne vide si le réseau refuse : on prévient, on note.
expect_sha() {
  git -C "$ROOT" ls-remote origin "${EXPECT_REF:-master}" 2>/dev/null | cut -f1 | tr -d '\r' || :
}

# shot <nom> : capture d'écran rangée avec le procès-verbal (survit à la recette).
shot() {
  local remote="/sdcard/isee_street_$1_$$.png" dest="${EVIDENCE}/shot-$1.png"
  call "screencap -p $remote" >/dev/null || :
  ph "capture $1" pull "$remote" "$dest" >/dev/null 2>&1 || :
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

# hear <quoi> : phrase exacte entendue, saisie libre + horodatée au procès-verbal.
hear() {
  local phrase=""
  printf '  Phrase exacte entendue — %s : ' "$1"
  read -r phrase || phrase=""
  obs "entendu ($1) : ${phrase:-non saisi}"
}

main() {
  std_args "verify-street.sh" "$USAGE" "$@"
  resolve_adb
  require_cmd timeout "Installez coreutils (paquet 'coreutils')."
  require_cmd git "Installez git (vérification du SHA master)."
  require_uint "PHONE_WAIT" "$PHONE_WAIT"
  SERIAL="${PHONE_SERIAL:-2c6852ac}"

  APK_PATH="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  [[ -f "$APK_PATH" ]] ||
    die "APK absent : $APK_PATH (build-serveur sur le r630, jamais Gradle ici)"

  PHONE="${PHONE_BIN:-$HOME/bin/phone}"
  [[ -x "$PHONE" ]] ||
    die "verrou téléphone absent : $PHONE (PHONE_BIN, lancez depuis la machine du téléphone)"

  [[ -t 0 ]] || die "recette interactive : lancez dans un terminal (marche + voix + Entrée à chaque étape)."
  require_phone

  make_tmpdir
  UI_XML="${TMP_WORK}/street-ui.xml"
  REMOTE_UI="/sdcard/isee_street_$$.xml"
  EVIDENCE="$(mktemp -d "${TMPDIR:-/tmp}/street.XXXXXX")" ||
    die "mktemp -d a échoué pour le procès-verbal (TMPDIR='${TMPDIR:-/tmp}')"
  REPORT="${EVIDENCE}/street-report.txt"
  trap cleanup EXIT
  { echo "Procès-verbal recette rue — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"; echo; } >"$REPORT"

  note "### 0. prérequis (SHA master, install, réglages, terrain)"
  install_apk
  local want="" got=""
  want="$(expect_sha)"
  got="$(installed_sha)"
  note "  SHA attendu (origin/${EXPECT_REF:-master}) : ${want:-inconnu — vérifiez git ls-remote à la main}"
  note "  SHA installé (versionName)  : ${got:-illisible}"
  if [[ -n "$want" && -n "$got" ]]; then
    case "$want" in
      "$got"*) note "  SHA : CONFORME (build du HEAD de ${EXPECT_REF:-master})" ;;
      *) die "SHA NON CONFORME : l'APK ne vient pas du HEAD de origin/${EXPECT_REF:-master} (règle ISEE-TEST-MASTER-SEUL)" ;;
    esac
  else
    warn "SHA non vérifié automatiquement — notez-le à la main dans le procès-verbal."
  fi
  call "pm grant $PKG android.permission.CAMERA" || :
  call "pm grant $PKG android.permission.RECORD_AUDIO" || :
  call "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" || :
  call "am force-stop $PKG" >/dev/null || :
  call "am start -n $PKG/.MainActivity" >/dev/null || warn "am start a échoué."
  sleep 9
  back_to_isee
  note "  clés voix renseignées (ElevenLabs + voix) : $(ask 'Réglages iSee : clé ElevenLabs et voix renseignées')"
  note "  URL Valhalla renseignée (routeBaseUrl) : $(ask 'Réglages iSee : l\u2019URL Valhalla est-elle renseignée')"
  note "  terrain (IdF, GPS, réseau, jour) : $(ask 'Êtes-vous en Île-de-France, GPS fixé, réseau OK, de jour')"
  note "  parcours (~200 m, voiture + humain + poteau + mur + bordure + escalier + passage piéton + virage) : $(ask 'Le parcours de ~200 m passe-t-il par ces imposés')"

  note "### 1. dictée ordre (« mode GPS I want to go to X »)"
  require_phone
  local dest=""
  printf '  Destination choisie (adresse à ~200 m, Île-de-France) : '
  read -r dest || dest=""
  note "  destination : ${dest:-non saisie}"
  dire "mode GPS I want to go to ${dest:-...}"
  sleep 4
  hear "confirmation (attendu « Guidance started. », pas de question LLM)"
  note "  écran après dictée : $(ui)"
  note "  crash iSee : $(crashes) | pid : $(call "pidof $PKG" | tr -d '\r ')"
  dire "je veux aller à ${dest:-...}"
  sleep 4
  note "  contre-essai sans « mode » parti au LLM (pas de guidage) : $(ask 'Sans « mode », êtes-vous parti en question LLM (pas de guidage)')"

  note "### 2. voiture (« Car. », 4 s, garée = silence)"
  require_phone
  printf '\n  Marchez vers la destination. Une voiture bouge à < 20 m.\n'
  printf '  Entrée quand l\u2019annonce est passée (ou après 30 s sans rien) : '
  local _junk=""
  read -r _junk || _junk=""
  hear "voiture (attendu « Car. » ou « Cars. », sans cadran ni distance, 4 s)"
  note "  garée = silence : $(ask 'Une voiture garée est-elle restée silencieuse')"

  note "### 3. humain (« Person. », 2 s, « Stop. » à ≤ 2 m)"
  printf '\n  Approchez une personne (< 10 m), dont un passage à ≤ 2 m.\n'
  printf '  Entrée quand l\u2019annonce est passée (ou après 30 s sans rien) : '
  _junk=""
  read -r _junk || _junk=""
  hear "humain (attendu « Person. » + heure du cadran + distance, 2 s)"
  hear "imminent ≤ 2 m (attendu « Stop. » immédiat)"

  note "### 4. mobilier (« Pole. », 2 s / 4 s)"
  printf '\n  Continuez. Poteau, lampadaire, panneau, grillage, mur, barrière à < 6 m.\n'
  printf '  Entrée quand l\u2019annonce est passée (ou après 30 s sans rien) : '
  _junk=""
  read -r _junk || _junk=""
  hear "mobilier (attendu le nom seul « Pole. », « Wall. », …, 2 s poteaux/mobilier, 4 s grillages/murs/barrières)"
  note "  capture : $(shot mobilier)"

  note "### 5. bordures (« Curb up. » / « Curb down. », 2 s)"
  printf '\n  Montée puis descente de bordure.\n'
  printf '  Entrée quand l\u2019annonce est passée (ou après 30 s sans rien) : '
  _junk=""
  read -r _junk || _junk=""
  hear "bordures (attendu « Curb up. » puis « Curb down. », 2 s)"

  note "### 6. escalier (jalons 4/2/1 + marches)"
  require_phone
  printf '\n  Marchez vers un escalier.\n'
  printf '  Entrée après le passage : '
  _junk=""
  read -r _junk || _junk=""
  hear "escalier (attendu jalons « Stairs up, 4 meters. », … + marches « Five. Steps. »)"

  note "### 7. passage piéton (« Crosswalk ahead », 1 fois par vue)"
  printf '\n  Continuez vers le passage piéton.\n'
  printf '  Entrée après le passage : '
  _junk=""
  read -r _junk || _junk=""
  hear "passage (attendu « Crosswalk ahead/left/right. » 1 fois par vue, « Aligned with the crosswalk. » au changement, « On the crosswalk. » 1 fois)"
  note "  doublon : $(ask 'Avez-vous entendu un doublon (annonce répétée sans changement)')"

  note "### 8. virage (manœuvre synthèse + 0 chevauchement)"
  require_phone
  printf '\n  Continuez jusqu\u2019au premier virage de l\u2019itinéraire.\n'
  printf '  Entrée quand la consigne est passée : '
  _junk=""
  read -r _junk || _junk=""
  hear "virage (attendu manœuvre en synthèse réseau)"
  note "  0 chevauchement virage/obstacle : $(ask 'Virage et obstacle ne se sont-ils jamais parlé dessus (jamais deux voix)')"
  note "  capture : $(shot virage)"

  note "### 9. traversée (« Crossing mode on. », verdict, GPS/chat muets)"
  printf '\n  Au passage piéton de l\u2019itinéraire, dictez « mode crossing on ».\n'
  dire "mode crossing on"
  sleep 3
  hear "mode (attendu « Crossing mode on. », chat et GPS muets, dictée active)"
  hear "verdict (attendu « Cross. » / « Don't cross. », 3 s, changement immédiat)"
  note "  humains (101) puis bordures (102) après le verdict : $(ask 'Humains puis bordures ont-ils parlé après le verdict')"
  printf '  Traversez (si le verdict le permet). Entrée une fois passé : '
  _junk=""
  read -r _junk || _junk=""
  dire "mode crossing off"
  sleep 2
  hear "retour (attendu « Crossing mode off. », le guidage reprend)"
  note "  capture : $(shot traversee)"

  note "### 10. rappel GPS (15 s de silence)"
  require_phone
  printf '\n  Marchez 15 s sans annonce.\n'
  printf '  Entrée après le rappel (ou après 30 s sans rien) : '
  _junk=""
  read -r _junk || _junk=""
  hear "rappel (attendu dernière consigne répétée + distance restante, synthèse)"

  note "### 11. repli (300 ms de silence)"
  hear "repli (attendu « Left. » / « Right. » / « Straight. », une fois)"

  note "### 12. arrivée (une seule fois)"
  printf '\n  Marchez jusqu\u2019à la destination.\n'
  printf '  Entrée quand l\u2019arrivée est annoncée : '
  _junk=""
  read -r _junk || _junk=""
  hear "arrivée (attendue une seule fois)"
  note "  crash iSee : $(crashes) | pid : $(call "pidof $PKG" | tr -d '\r ')"
  call "am start -n $PKG/.MainActivity" >/dev/null || :
  sleep 4
  note "  retour iSee : $(ui | cut -c1-120)"
  note "  capture : $(shot arrivee)"

  note "### 13. interdits v4 (0 bus/vélo/camion/moto, 0 clés dehors, 0 Maps, 0 FR)"
  note "  aucun bus/vélo/camion/moto entendu : $(ask 'Avez-vous entendu bus, vélo, camion ou moto')"
  note "  aucune clé dehors : $(ask 'Avez-vous entendu les clés dehors')"
  note "  aucun passage Google Maps : $(ask 'Le guidage est-il passé par Google Maps')"
  note "  aucune voix FR hors chat/GPS : $(ask 'Avez-vous entendu du français hors chat et GPS')"
  note ""
  note "Fin — procès-verbal : $REPORT (captures : ${EVIDENCE}/shot-*.png)"
}

# cleanup : supprime le dump distant et les temporaires locaux, sans jamais échouer.
cleanup() {
  if [[ -n "${REMOTE_UI:-}" && -n "${PHONE:-}" && -n "${ADB:-}" && -n "${SERIAL:-}" ]]; then
    timeout 20 "$PHONE" run --wait 0 --task "rue: nettoyage" -- \
      "$ADB" -s "$SERIAL" shell rm -f "$REMOTE_UI" >/dev/null 2>&1 || :
  fi
  [[ -n "${TMP_WORK:-}" ]] && safe_rm_rf "$TMP_WORK" || :
}

main "$@"
