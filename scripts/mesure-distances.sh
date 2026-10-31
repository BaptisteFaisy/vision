#!/usr/bin/env bash
# Vérifie un CSV de relevés terrain des distances (protocole
# docs/RECETTE-DISTANCES.md) : biais médian, p90 des erreurs absolues,
# pires cas et valeurs absurdes, verdict selon les seuils de la fiche.
# Le CSV porte objet,vraie_m,affichee_m (point décimal, en-tête tolérée).
# Sort 0 si les seuils passent, 1 sinon (relevés malformés, seuils ratés).
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
. "$(dirname -- "${BASH_SOURCE[0]}")/lib/common.sh" ||
  { printf 'ERREUR : scripts/lib/common.sh introuvable\n' >&2; exit 1; }

read -r -d '' USAGE <<'EOF' || true
Usage : mesure-distances.sh [--help] RELEVES.csv

Vérifie un CSV de relevés terrain des distances (protocole
docs/RECETTE-DISTANCES.md) et imprime le biais médian, le p90 des erreurs
absolues, les pires cas et le verdict selon les seuils de la fiche
(médiane des erreurs signées < 10 %, p90 < 25 %, 0 valeur absurde,
c'est-à-dire aucune affichée au-delà du double de la vraie distance).

Format : objet,vraie_m,affichee_m — séparateur virgule, point décimal,
nombres strictement positifs, en-tête 'objet,vraie_m,affichee_m' tolérée.
La colonne confiance de la fiche papier ne part pas dans le CSV.

Sort 0 si les seuils passent, 1 si un relevé est malformé ou si les
seuils sont ratés, 2 en cas d'argument inconnu.
EOF

CSV=""
while (( $# > 0 )); do
  case "$1" in
    -h | --help)
      printf '%s\n' "$USAGE"
      exit 0
      ;;
    --*)
      printf "ERREUR : mesure-distances.sh : argument inconnu : '%s'\n(essayez --help)\n" "$1" >&2
      exit 2
      ;;
    *)
      [[ -z "$CSV" ]] || {
        printf "ERREUR : mesure-distances.sh : argument inconnu : '%s'\n(essayez --help)\n" "$1" >&2
        exit 2
      }
      CSV="$1"
      shift
      ;;
  esac
done
[[ -n "$CSV" ]] || die "aucun CSV de relevés indiqué (essayez --help)."
[[ -f "$CSV" ]] || die "relevés introuvables : '$CSV'."
require_cmd awk
require_cmd sort

make_tmpdir
trap 'safe_rm_rf "$TMP_WORK"' EXIT
ERREURS="${TMP_WORK}/erreurs.tsv"

# Passe 1 : validation stricte + erreurs relatives en points-virgule :
# signée %, absolue %, objet, vraie, affichée.
awk -F',' '
  function trim(s) { sub(/^[ \t\r]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
  function num_ok(v) { return (v ~ /^[0-9]+(\.[0-9]+)?$/ && v + 0 > 0) }
  NR == 1 && trim($1) == "objet" { next }
  /^[ \t\r]*$/ { next }
  {
    obj = trim($1); vraie = trim($2); aff = trim($3)
    if (NF != 3) { printf "ligne %d : 3 colonnes attendues (objet,vraie_m,affichee_m), %d lues\n", NR, NF > "/dev/stderr"; bad = 1; next }
    if (obj == "") { printf "ligne %d : objet vide\n", NR > "/dev/stderr"; bad = 1; next }
    if (!num_ok(vraie)) { printf "ligne %d : vraie_m invalide ('%s', nombre > 0 attendu)\n", NR, $2 > "/dev/stderr"; bad = 1; next }
    if (!num_ok(aff)) { printf "ligne %d : affichee_m invalide ('%s', nombre > 0 attendu)\n", NR, $3 > "/dev/stderr"; bad = 1; next }
    err = (aff - vraie) / vraie * 100
    aerr = err < 0 ? -err : err
    printf "%.4f\t%.4f\t%s\t%s\t%s\n", err, aerr, obj, vraie, aff
  }
  END { if (bad) exit 1 }
' "$CSV" >"$ERREURS" || die "relevés malformés (détail ci-dessus)."

N="$(wc -l <"$ERREURS" | tr -d ' ')"
[[ "$N" -gt 0 ]] || die "aucun relevé dans '$CSV'."

# Médiane des erreurs signées (tri sur la colonne 1).
MEDIANE="$(sort -t'	' -k1,1n "$ERREURS" | awk -v n="$N" '
  { v[NR] = $1 }
  END {
    if (n % 2) { print v[(n + 1) / 2] } else { print (v[n / 2] + v[n / 2 + 1]) / 2 }
  }')"
# p90 des erreurs absolues : plus petite valeur couvrant 90 % des relevés.
P90="$(sort -t'	' -k2,2n "$ERREURS" | awk -v n="$N" '
  { v[NR] = $2 }
  END { k = int(n * 0.9 + 0.999999); if (k < 1) k = 1; print v[k] }')"
# Valeurs absurdes : affichée au-delà du double de la vraie (erreur > 100 %).
ABSURDES="$(awk -F'	' '$1 > 100 { c++ } END { print c + 0 }' "$ERREURS")"

# Verdict : |médiane| < 10, p90 < 25, 0 absurde.
if awk -v m="$MEDIANE" -v p="$P90" 'BEGIN { am = m < 0 ? -m : m; exit !(am < 10 && p < 25) }' \
  && [[ "$ABSURDES" == "0" ]]; then
  VERDICT="OK"
  RC=0
else
  VERDICT="SEUILS NON ATTEINTS"
  RC=1
fi

printf '%s : %s relevés — biais médian %+.1f %%, p90 %.1f %%, %s valeur(s) absurde(s) (seuils : médiane < 10 %%, p90 < 25 %%, 0 absurde)\n' \
  "$VERDICT" "$N" "$MEDIANE" "$P90" "$ABSURDES"
printf 'pires cas :\n'
sort -t'	' -k2,2nr "$ERREURS" | head -3 | awk -F'	' '{ printf "  %s : vraie %s m, affichée %s m (%+.1f %%)\n", $3, $4, $5, $1 }'
exit "$RC"
