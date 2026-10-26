#!/usr/bin/env python3
"""gen_mask_golden.py — fixtures dorées des masques (`bits`/`M1`), IoU et arbitrage de variante.

Le client ne lit **qu'une** variante compacte de masque : `bits` (format `M1` du backend —
magic `M1`, largeur et hauteur uint16 little-endian, puis zlib de lignes 1 bit/pixel, bit de
poids fort en tête, chaque ligne rembourrée à l'octet). Ce script est l'implémentation
**serveur** de ce format, écrite en Python : il fabrique six rasters analytiques (disque,
rectangle, anneau à trou, bruit, ellipse, deux disques), les encode en `M1`, écrit les fixtures
dorées que `core.mask.MaskGoldenTest` décode côté client, et mesure l'IoU.

Les octets produits sont **ceux du backend** : `--selftest` réencode six vecteurs réels déjà
présents dans le dépôt (`MaskCoreTest`, `MaskRobustnessTest`) et exige la même base64, octet
pour octet. C'est ce qui rend la fixture dorée indépendante du décodeur Kotlin : un bug de
format compris des deux côtés (ordre des bits, rembourrage, boutisme) ne peut plus passer.

Usage :
    python3 scripts/gen_mask_golden.py --write      # écrit fixtures + mesures
    python3 scripts/gen_mask_golden.py --check      # fixtures <-> script (porte anti-dérive)
    python3 scripts/gen_mask_golden.py --selftest   # assertions internes

Bibliothèque standard seule ; aucun réseau, aucun appareil, aucun fichier temporaire.
"""

import ast
import base64
import os
import struct
import sys
import zlib

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(RACINE, "core", "src", "test", "java", "dev", "sam3vision",
                        "core", "mask", "MaskGoldenData.kt")
MESURES = os.path.join(RACINE, "docs", "MESURES-u11c.txt")

# --- rasters analytiques -----------------------------------------------------
# Tout est en coordonnées **doublées** (2·x - centre doublé) : uniquement des entiers, donc
# le même raster sur toutes les machines et toutes les versions de Python.


def dans_disque(x, y, cx2, cy2, r2):
    """Intérieur d'un disque : `cx2`/`cy2` = 2·centre, `r2` = 2·rayon."""
    return (2 * x - cx2) ** 2 + (2 * y - cy2) ** 2 <= r2 * r2


def disque(x, y, w, h):
    return dans_disque(x, y, w - 1, h - 1, 2 * (min(w, h) * 42 // 100))


def anneau(x, y, w, h):
    externe = dans_disque(x, y, w - 1, h - 1, 2 * (min(w, h) * 45 // 100))
    trou = dans_disque(x, y, w - 1, h - 1, 2 * (min(w, h) * 20 // 100))
    return externe and not trou


def rectangle(x, y, w, h):
    return (w * 15 // 100 <= x < w * 85 // 100) and (h * 15 // 100 <= y < h * 85 // 100)


def ellipse(x, y, w, h):
    a2, b2 = 2 * (w * 40 // 100), 2 * (h * 42 // 100)
    dx, dy = 2 * x - (w - 1), 2 * y - (h - 1)
    return dx * dx * b2 * b2 + dy * dy * a2 * a2 <= (a2 * b2) ** 2


def deux_disques(x, y, w, h):
    r2 = 2 * (min(w, h) * 22 // 100)
    gauche = dans_disque(x, y, 2 * (w * 25 // 100), h - 1, r2)
    droite = dans_disque(x, y, 2 * (w * 75 // 100), h - 1, r2)
    return gauche or droite


def bruit(x, y, w, h):
    """Bruit déterministe (hachage entier, aucun générateur) : reproductible partout."""
    melange = (x * 0x9E3779B1 + y * 0x85EBCA77 + 0x1B873593) & 0xFFFFFFFF
    melange ^= melange >> 15
    melange = (melange * 0x2545F491) & 0xFFFFFFFF
    return (melange ^ (melange >> 13)) & 0xFF < 115


# (nom, largeur, hauteur, prédicat) — largeurs non multiples de 8 : le rembourrage est exercé.
FORMES = (
    ("disque", 48, 32, disque),
    ("rectangle", 37, 24, rectangle),
    ("anneau", 33, 33, anneau),
    ("bruit", 40, 20, bruit),
    ("ellipse", 45, 29, ellipse),
    ("deux_disques", 48, 32, deux_disques),
)

# Vecteurs **réels du backend** dont le raster est documenté dans le dépôt : l'ancre du format.
ANCRES = (
    ("BITS_5X3", ("10010", "01001", "00100"), "TTEFAAMAeJyb4KEAAAJjAPk="),
    ("BITS_1X5", ("1", "0", "1", "0", "1"), "TTEBAAUAeJxrYGhgaAAABIUBgQ=="),
    ("BITS_15X4", ("111111111111111", "110011001100110", "101010101010101",
                   "100110011001100"), "TTEPAAQAeJz7/+/MmVWrZs4AAB4mBhs="),
    ("BITS_VIDE_8X2", ("00000000", "00000000"), "TTEIAAIAeJxjYAAAAAIAAQ=="),
    ("BITS_PLEIN_8X2", ("11111111", "11111111"), "TTEIAAIAeJz7/x8AAv8B/w=="),
    ("BITS_1X1", ("1",), "TTEBAAEAeJxrAAAAgQCB"),
)


def raster(predicat, w, h):
    """Les lignes du masque : une chaîne de `0`/`1` par ligne (`1` = objet)."""
    return ["".join("1" if predicat(x, y, w, h) else "0" for x in range(w)) for y in range(h)]


def paquet(rangs, w, h):
    """Corps `M1` : lignes 1 bit/pixel, bit de poids fort en tête, rembourrage à zéro."""
    octets = (w + 7) // 8
    corps = bytearray(octets * h)
    for y, rang in enumerate(rangs):
        for x, pixel in enumerate(rang):
            if pixel == "1":
                corps[y * octets + (x >> 3)] |= 1 << (7 - (x & 7))
    return bytes(corps)


def enveloppe(w, h, corps):
    """Payload `M1` complet : magic, dimensions uint16 little-endian, zlib du corps."""
    return b"M1" + struct.pack("<HH", w, h) + zlib.compress(corps)


def payload_b64(w, h, rangs):
    return base64.b64encode(enveloppe(w, h, paquet(rangs, w, h))).decode("ascii")


def decoder(texte_b64):
    """Décodeur Python **indépendant** du décodeur Kotlin : `(largeur, hauteur, rangs)`."""
    brut = base64.b64decode(texte_b64)
    w, h = struct.unpack("<HH", brut[2:6])
    corps = zlib.decompress(brut[6:])
    octets = (w + 7) // 8
    rangs = []
    for y in range(h):
        ligne = "".join("1" if corps[y * octets + (x >> 3)] & (1 << (7 - (x & 7)))
                        else "0" for x in range(w))
        rangs.append(ligne)
    return w, h, rangs


def iou(rangs_a, rangs_b):
    """Intersection sur union des pixels objet ; 1,0 quand les deux masques sont identiques."""
    inter = union = 0
    for ligne_a, ligne_b in zip(rangs_a, rangs_b):
        for a, b in zip(ligne_a, ligne_b):
            objet_a, objet_b = a == "1", b == "1"
            inter += 1 if objet_a and objet_b else 0
            union += 1 if objet_a or objet_b else 0
    return inter / union if union else 1.0


def objet(rangs):
    return sum(ligne.count("1") for ligne in rangs)


def png_gris8(w, h, rangs):
    """PNG gris 8 bits (filtre 0) : la variante historique, pour comparer les poids."""
    def bloc(genre, donnees):
        crc = zlib.crc32(genre + donnees) & 0xFFFFFFFF
        return struct.pack(">I", len(donnees)) + genre + donnees + struct.pack(">I", crc)

    brut = bytearray()
    for rang in rangs:
        brut.append(0)
        brut.extend(255 if pixel == "1" else 0 for pixel in rang)
    return (b"\x89PNG\r\n\x1a\n"
            + bloc(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0))
            + bloc(b"IDAT", zlib.compress(bytes(brut), 9))
            + bloc(b"IEND", b""))


def b64_len(donnees):
    return len(base64.b64encode(donnees))


# --- rédaction des deux artefacts -------------------------------------------

def texte_fixtures():
    morceaux = [
        "package dev.sam3vision.core.mask",
        "",
        "/**",
        " * Fixtures dorées des masques — fichier **généré** par `scripts/gen_mask_golden.py`.",
        " *",
        " * Ne pas éditer à la main : `python3 scripts/gen_mask_golden.py --check` compare ce",
        " * fichier à ce que produit le générateur (implémentation Python du format `M1`, du côté",
        " * serveur, identique au backend — voir l'ancre de `--selftest`).",
        " *",
        " * Chaque fixture porte le payload que le serveur envoie (`M1` : magic, largeur et hauteur",
        " * uint16, zlib de lignes 1 bit/pixel) et le raster attendu, une chaîne de `0`/`1` par",
        " * ligne (`1` = objet). `MaskGoldenTest` exige le décodage au pixel près et une IoU de 1.",
        " */",
        "internal object MaskGoldenData {",
        "",
        "    /** Un masque doré : ce que le serveur envoie, et ce que le client doit en lire. */",
        "    data class Golden(",
        "        val nom: String,",
        "        val largeur: Int,",
        "        val hauteur: Int,",
        "        val payloadB64: String,",
        "        val rangs: List<String>,",
        "    )",
        "",
        "    val GOLDEN: List<Golden> = listOf(",
    ]
    for nom, w, h, predicat in FORMES:
        rangs = raster(predicat, w, h)
        morceaux += [
            "        Golden(",
            f'            nom = "{nom}",',
            f"            largeur = {w},",
            f"            hauteur = {h},",
            f'            payloadB64 = "{payload_b64(w, h, rangs)}",',
            "            rangs = listOf(",
        ]
        morceaux += [f'                "{rang}",' for rang in rangs]
        morceaux += ["            ),", "        ),"]
    morceaux += ["    )", "}", ""]
    return "\n".join(morceaux)


def ligne_mesure(nom, w, h, predicat):
    """Une ligne de tableau : fidélité et poids de la forme à sa taille de fixture."""
    rangs = raster(predicat, w, h)
    payload = payload_b64(w, h, rangs)
    largeur, hauteur, relus = decoder(payload)
    assert (largeur, hauteur) == (w, h), f"{nom} : dimensions relues {largeur}x{hauteur}"
    return (f"| `{nom}` | {w}×{h} | {objet(rangs)} | {len(base64.b64decode(payload))} | "
            f"{len(payload)} | {b64_len(png_gris8(w, h, rangs))} | {iou(rangs, relus):.3f} |")


def poids_agreges():
    """Poids `bits` vs PNG gris 8 bits pour 6 objets, aux deux tailles citées par la lane."""
    lignes = []
    for taille, w, h in (("640×360 (direct)", 640, 360), ("256×103 (grille L11)", 256, 103)):
        lisses = [(nom, predicat) for nom, _, _, predicat in FORMES if nom != "bruit"]
        totaux = {cle: 0 for cle in ("m1", "png", "m1_lisse", "png_lisse")}
        for nom, _, _, predicat in FORMES:
            rangs = raster(predicat, w, h)
            bits = len(payload_b64(w, h, rangs))
            png = b64_len(png_gris8(w, h, rangs))
            totaux["m1"] += bits
            totaux["png"] += png
            if nom != "bruit":
                totaux["m1_lisse"] += bits
                totaux["png_lisse"] += png
        lignes.append(
            f"- **{taille}** : `bits` {totaux['m1']} car. base64 · PNG gris 8 bits "
            f"{totaux['png']} car. → {facteur(totaux['png'], totaux['m1'])} plus léger. "
            f"Sans le bruit (incompressible) : {totaux['m1_lisse']} contre "
            f"{totaux['png_lisse']} car. → {facteur(totaux['png_lisse'], totaux['m1_lisse'])}.")
    return lignes


def facteur(a, b):
    """« 1,21× » : rapport `a/b` à deux décimales, virgule française."""
    return f"{a / b:.2f}".replace(".", ",") + "×"


def entete_mesures():
    return [
        "MESURES — u11c : fixtures dorées, IoU, arbitrage de variante",
        "",
        "Dépôt : `BaptisteFaisy/vision` (app seule ; le backend SAM 3.1 vit dans un autre dépôt).",
        "Codec réel du client : `bits` — format `M1`, magic + dimensions uint16 little-endian +",
        "zlib de lignes 1 bit/pixel (`core/src/main/java/dev/sam3vision/core/mask/MaskCore.kt`).",
        "",
        "Fichier généré : `python3 scripts/gen_mask_golden.py --write`. Aucun chiffre à la main ;",
        "`--check` compare ce fichier à ce que produit le script.",
        "",
        "## 1. Fixtures dorées — fidélité serveur → client",
        "",
        "Le payload vient de l'encodeur **Python** (ancre : 6 vecteurs réels du dépôt réencodés à",
        "l'identique, `--selftest`). `MaskGoldenTest` décode chaque fixture par le chemin réel du",
        "client (`decodeMaskAny(..., \"bits\")`) et exige 6/6 au pixel près : chaque ligne est",
        "comparée, l'IoU doit valoir **1,000**.",
        "",
        "| forme | taille | pixels objet | payload `M1` (o) | base64 `M1` (car.) | "
        "base64 PNG 8 bits (car.) | IoU |",
        "|---|---|---|---|---|---|---|",
    ]


def section_arbitrage():
    return [
        "",
        "## 3. Arbitrage — une seule variante",
        "",
        "- **Retenue** : `bits` (`M1`), la seule variante compacte que le client sait lire. Elle",
        "  est **sans perte** (IoU 1,000 sur les six formes, bruit compris) et plus légère que le",
        "  PNG gris 8 bits à fidélité égale.",
        "- **Repli historique** : `png` (gris 8 bits) et `none` (aucun masque). Encodage absent ou",
        "  inconnu ⇒ `png` : un backend non redéployé reste lisible.",
        "- **Refusées** : RLE LEB128, polygones, conteneur auto-décrit (« bis ») : **absents de ce",
        "  dépôt**, aucun décodeur côté client. En introduire un casserait `MaskGoldenTest` — c'est",
        "  la porte anti-dérive du format.",
    ]


def section_non_mesure():
    return [
        "",
        "## 4. Non mesuré / hors dépôt",
        "",
        "- IoU d'une variante **à perte** (polygones ≤ 1 px, grille réduite + plus proche voisin",
        "  0,97–0,98) : sans objet ici, aucune variante à perte dans ce dépôt — **non mesuré**.",
        "- Décodage PNG côté JVM : impossible (le `BitmapFactory` des tests JVM rend `null`) —",
        "  **non mesuré** ici ; la variante `bits` n'en a pas besoin.",
        "- IoU à l'écran (aperçu, `CENTER_CROP` + filtre bilinéaire) : rendu Android, hors JVM —",
        "  **non mesuré**.",
        "- Mesure sur téléphone : hors de ce dépôt — **non mesuré**.",
    ]


def texte_mesures():
    morceaux = entete_mesures()
    morceaux += [ligne_mesure(nom, w, h, p) for nom, w, h, p in FORMES]
    morceaux += ["", "## 2. Poids — `bits` contre la variante historique", ""]
    morceaux += poids_agreges()
    morceaux += ["- Pour comparaison : la lane L11 annonce **652 car. base64** (RLE, 6 objets",
                 "  256×103) — autre codec, hors de ce dépôt, non reproduit ici."]
    morceaux += section_arbitrage() + section_non_mesure()
    return "\n".join(morceaux) + "\n"


def ecrire(chemin, contenu):
    with open(chemin, "w", encoding="utf-8") as flux:
        flux.write(contenu)
    return len(contenu.splitlines())


def write_all():
    for chemin, contenu in ((FIXTURES, texte_fixtures()), (MESURES, texte_mesures())):
        lignes = ecrire(chemin, contenu)
        print(f"OK   {os.path.relpath(chemin, RACINE)} réécrit ({lignes} lignes)")
    return 0


def check_all():
    ecarts = 0
    for chemin, contenu in ((FIXTURES, texte_fixtures()), (MESURES, texte_mesures())):
        relatif = os.path.relpath(chemin, RACINE)
        if not os.path.exists(chemin):
            print(f"KO   {relatif} absent")
            ecarts += 1
            continue
        with open(chemin, encoding="utf-8") as flux:
            actuel = flux.read()
        if actuel == contenu:
            print(f"OK   {relatif} identique au générateur")
            continue
        ecarts += 1
        attendu = contenu.splitlines()
        present = actuel.splitlines()
        premier = next((i for i, (a, b) in enumerate(zip(attendu, present)) if a != b),
                       min(len(attendu), len(present)))
        print(f"KO   {relatif} diverge (ligne {premier + 1} : {len(present)} l. sur disque, "
              f"{len(attendu)} attendues)")
    if ecarts:
        print(f"\n{ecarts} artefact(s) à régénérer : "
              f"python3 scripts/gen_mask_golden.py --write")
    return 1 if ecarts else 0


# --- assertions internes -----------------------------------------------------

def limites():
    """Vérifie ses propres limites de la maison : < 500 lignes, chaque fonction < 50 lignes."""
    with open(os.path.abspath(__file__), encoding="utf-8") as flux:
        source = flux.read()
    tailles = [(noeud.name, (noeud.end_lineno or noeud.lineno) - noeud.lineno + 1)
               for noeud in ast.walk(ast.parse(source))
               if isinstance(noeud, (ast.FunctionDef, ast.AsyncFunctionDef))]
    lignes = source.count("\n") + 1
    return lignes, max(tailles, key=lambda item: item[1])


def assertions():
    """Les assertions du `--selftest` : (libellé, prédicat)."""
    def ancre_ok(nom, rangs, attendu):
        w, h = len(rangs[0]), len(rangs)
        return payload_b64(w, h, list(rangs)) == attendu

    def iou_forme(predicat, w, h):
        rangs = raster(predicat, w, h)
        return iou(rangs, decoder(payload_b64(w, h, rangs))[2])

    def non_degenere(predicat, w, h):
        rangs = raster(predicat, w, h)
        return 0 < objet(rangs) < w * h

    anneau_rangs = raster(anneau, 33, 33)
    bruit_densite = objet(raster(bruit, 40, 20)) / (40 * 20)
    limites_script = limites()
    return (
        ("6 vecteurs réels du dépôt réencodés à l'octet près",
         lambda: all(ancre_ok(*a) for a in ANCRES)),
        ("l'ancre couvre les deux extrêmes (masque vide et plein)",
         lambda: ancre_ok(*ANCRES[3]) and ancre_ok(*ANCRES[4])),
        ("IoU du serveur vers le relu vaut 1 sur les six formes",
         lambda: all(iou_forme(p, w, h) == 1.0 for _, w, h, p in FORMES)),
        ("les six formes ont objet et fond (fixtures non dégénérées)",
         lambda: all(non_degenere(p, w, h) for _, w, h, p in FORMES)),
        ("l'anneau garde son trou (centre au fond)",
         lambda: anneau_rangs[16][16] == "0" and objet(anneau_rangs) > 0),
        (f"le bruit est équilibré ({bruit_densite:.0%} d'objets)",
         lambda: 0.3 < bruit_densite < 0.7),
        ("le bruit se relit exactement lui aussi (IoU 1)",
         lambda: iou_forme(bruit, 40, 20) == 1.0),
        ("chaque fixture encode moins lourd que son PNG gris 8 bits",
         lambda: all(len(payload_b64(w, h, raster(p, w, h)))
                     < b64_len(png_gris8(w, h, raster(p, w, h))) for _, w, h, p in FORMES)),
        ("le fichier de fixtures porte les six formes",
         lambda: texte_fixtures().count("            nom = ") == 6),
        ("les mesures nomment les quatre sections",
         lambda: all(section in texte_mesures() for section in
                     ("## 1. Fixtures", "## 2. Poids", "## 3. Arbitrage", "## 4. Non mesuré"))),
        (f"limites du script : {limites_script[0]} l., fonction max "
         f"{limites_script[1][1]} l. ({limites_script[1][0]})",
         lambda: limites_script[0] < 500 and limites_script[1][1] < 50),
    )


def selftest():
    echecs = 0
    for libelle, predicat in assertions():
        ok = bool(predicat())
        echecs += 0 if ok else 1
        print(f"{'OK  ' if ok else 'KO  '} {libelle}")
    total = len(assertions())
    print(f"\n{total} assertions, {total - echecs} OK, {echecs} KO")
    return 1 if echecs else 0


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0
    commande = argv[0]
    if commande == "--write":
        return write_all()
    if commande == "--check":
        return check_all()
    if commande == "--selftest":
        return selftest()
    print(f"argument inconnu : {commande}", file=sys.stderr)
    print("usage : gen_mask_golden.py [--write|--check|--selftest]", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))