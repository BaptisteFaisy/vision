#!/usr/bin/env python3
"""budget_latence.py — source unique des chiffres du budget de latence perçue (mode Direct).

`docs/LATENCE-BUDGET.md` ne porte aucun chiffre écrit à la main : ses 6 blocs chiffrés sont
générés ici (`--write-doc`) puis comparés ici (`--check-doc`). Corriger un chiffre se fait donc
dans ce script, jamais dans le markdown : c'est la porte anti-dérive du budget.

Chaque valeur interne est un entier de **dixièmes de milliseconde** : les sommes et les
comparaisons sont exactes, l'affichage seul arrondit (au dixième, virgule décimale).

Usage :
    python3 scripts/budget_latence.py --selftest     # 14 assertions sur l'arithmétique
    python3 scripts/budget_latence.py --check-doc    # 6 blocs : doc <-> script
    python3 scripts/budget_latence.py --write-doc    # régénère les blocs du document
    python3 scripts/budget_latence.py --verdict      # verdict chiffré sur les 100 ms

Bibliothèque standard seule ; aucun réseau, aucun fichier temporaire, aucun appareil.
"""

import ast
import os
import re
import sys

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_DEFAUT = os.path.join(RACINE, "docs", "LATENCE-BUDGET.md")
FRAPPE = re.compile(r"<!-- table:([\w-]+) -->\n(.*?)<!-- /table:\1 -->", re.S)

CIBLE_DIX = 1000                      # 100,0 ms : la cible de latence perçue


def dix_str(valeur):
    """Dixièmes de ms → « 57,6 » (virgule décimale, sans zéro inutile)."""
    entier, dixiemes = divmod(valeur, 10)
    return str(entier) if dixiemes == 0 else f"{entier},{dixiemes}"


def plage_str(bornes):
    """(576, 784) → « 57,6–78,4 » ; (1237, 1237) → « 123,7 »."""
    bas, haut = bornes
    return dix_str(bas) if bas == haut else f"{dix_str(bas)}–{dix_str(haut)}"


# --- étages du chemin client (dixièmes de ms) --------------------------------
CAPTURE = (250, 250)                  # u07, simulation (jamais lu sur un appareil)
ENCODE = (50, 80)                     # u02, estimé après la refonte NV21
DECODE = (100, 250)                   # u07, simulation
COMPOSE = (9, 30)                     # u03, mesuré 0,93 ms/frame (JVM)
DISPLAY = (167, 174)                  # 1 vsync 60 Hz, ± 1 frame
PROPAGATION = (40, 100)               # u11, cible < 8 ms/frame à 512×206
CLIENT_CLOUD = (CAPTURE[0] + ENCODE[0] + DECODE[0] + COMPOSE[0] + DISPLAY[0],
                CAPTURE[1] + ENCODE[1] + DECODE[1] + COMPOSE[1] + DISPLAY[1])
HORS_PROPAGATION = (CAPTURE[0] + COMPOSE[0] + DISPLAY[0],
                    CAPTURE[1] + COMPOSE[1] + DISPLAY[1])
CLIENT_LOCAL = (HORS_PROPAGATION[0] + PROPAGATION[0],
                HORS_PROPAGATION[1] + PROPAGATION[1])

# --- planchers mesurés (dixièmes de ms) -------------------------------------
RESEAU_FILAIRE = 930                  # 93 ms : plus petit A/R observé (banc filaire)
SERVEUR_RAPIDE = 307                  # 30,7 ms/frame : tracker B200 720p
PLANCHER_CLOUD = RESEAU_FILAIRE + SERVEUR_RAPIDE
AR_MEDIAN = 4220                      # 422 ms : médian mesuré du direct (A100 eu-west)
AR_BANDE = (3870, 5280)               # bande de la ligne « banc filaire » du § 4
H200_TEL = 6996                       # 699,6 ms modélisés (H200, téléphone)
H200_MESURE = (7540, 7750)            # 754–775 ms affichés, mesurés au terrain
COUT_MONTANT = "≈ 0,12 ms/Ko"

# --- architecture locale (§ 4, dernière ligne) et allocation (§ 7) -----------
LOCAL_AR = (0, 0)                     # aucun aller-retour réseau sur le chemin perçu
LAN_PERÇU = (836, 1431)               # boîte GPU dans le LAN, téléphone ↔ LAN
ALLOCATION = (
    ("CAPTURE", 250,
     "hypothèse u07 à valider sur l'appareil — **le premier chiffre à mesurer**, il occupe 36 % du budget"),
    ("PROPAGATION (u10+u11)", 100,
     "cible u11 < 8 ms/frame à 512×206 ; 33,3 ms de débit disponible à 30 fps"),
    ("COMPOSE", 30, "0,93 ms mesuré (u03) — 3 ms laisse la place aux 12 objets"),
    ("DISPLAY", 170, "1 vsync 60 Hz (16,7 ms) ; 90 Hz ou 120 Hz rendrait 5–8 ms"),
    ("Aléas (GC, ordonnancement, reprise d'écran)", 150,
     "u03 a retiré 6,8 Mo/frame de pression GC ; le reste est à mesurer"),
)
ALLOC_TOTAL = sum(cible for _, cible, _ in ALLOCATION)

# --- § 2 : étages du chemin Direct ------------------------------------------
ETAGES_DOC = (
    ("CAPTURE", plage_str(CAPTURE), "capteur → callback de l'analyseur CameraX",
     "u07 **simulation** (25 ms de centre, jamais lu sur un appareil)",
     "période caméra : 33,3 ms à 30 fps, 16,7 ms à 60 fps"),
    ("ENCODE", plage_str(ENCODE), "NV21 → JPEG q65 → base64 du corps de requête",
     "u02 **estimé** (17–32 ms avant la refonte NV21, 3 bitmaps/frame)",
     "≈ 2 ms pour un 512×206 (non mesuré ici)"),
    ("QUEUE", "calculée", "attente du créneau d'envoi (`intervalMsAdaptive`)",
     "u01 + hypothèse de phase uniforme (attente ≈ intervalle ÷ 2) ; cadence prudente de cet arbre : 350 / 500 / 900 ms",
     "0 ms si la frame est traitée sans tick (chemin local)"),
    ("HTTP", f"{dix_str(AR_MEDIAN)} (médian mesuré)",
     "requête émise → réponse complète lue par le client (**= A/R**)",
     "notes de campagne (12 appels keep-alive, A100 eu-west, 512×206, 4 concepts)",
     f"plancher {dix_str(PLANCHER_CLOUD)} ms = réseau 93 + serveur 30,7 (meilleur cas mesuré des deux côtés)"),
    ("DECODE", plage_str(DECODE), "base64 + PNG des masques → tampons gris",
     "u07 simulation ; u03 a supprimé 3,4 Mo/frame d'allocation (mesuré JVM)",
     "0 ms quand le masque est déjà en mémoire (propagation)"),
    ("COMPOSE", plage_str(COMPOSE), "recoloration + composition dans une seule surface",
     "u03 **mesuré** 0,93 ms/frame (JVM de CI, 8 objets, 512×206 ; 1,79 ms avant)",
     "≈ 0,9 ms (le mesuré JVM est déjà proche du plancher)"),
    ("DISPLAY", plage_str(DISPLAY), "publication de l'état → composition Compose (vsync)",
     "plancher physique 1 vsync ; u07 sim 17,4 ms (± 1 frame, ± 16 ms)",
     "16,7 ms à 60 Hz, 11,1 ms à 90 Hz, 8,3 ms à 120 Hz"),
)

# --- § 2 bis : ce que contient `HTTP` côté serveur --------------------------
SERVEUR_DOC = (
    ("base64 + décodage image (PIL `draft`)", "1,2", "mesuré (u05, r630)",
     "campagne vague 1 (hors dépôt) · `bench_live_cpu.py`"),
    ("encodeur vision (1 passe/frame)", "≈ 234",
     "**calculé par soustraction** (u05) : à confirmer par `breakdown_ms` sur un appel réel",
     "campagne vague 1 (hors dépôt) · u05"),
    ("détection DETR × 4 concepts", "≈ 85",
     "calculé (u05 : 328 − 234 − 7) ; 21,2 ms/concept compare deux mesures différentes",
     "u05 · vérification indépendante"),
    ("encodage des masques PNG + base64", "7,3",
     "mesuré (u05) — **hors du verrou GPU** après u05/u21", "17,5 → 6,8 ms en 4 threads (u21)"),
    ("masques au format RLE 256 px (u04)", "1,5", "mesuré sur masques synthétiques (u04)",
     "−84 % d'octets, −6 ms de CPU"),
    ("file d'attente GPU (N frames en vol)", "≈ 300 × (N − 1)",
     "mesuré indirectement : 1 220–1 260 ms affichés avec 6 frames en vol",
     "campagne vague 1 (hors dépôt)"),
    ("total serveur `elapsed_ms`",
     "294–328 (A100) · 244–247 (H200) · 41 (B200) · 30,7 (tracker)", "mesuré",
     "campagne vague 1 (hors dépôt) · `bench_track.py`"),
    ("démarrage à froid", "25–38 s", "mesuré (hors chemin critique, facturé au client)",
     "campagne vague 1 (hors dépôt)"),
)

# --- § 3 : planchers physiques ---------------------------------------------
PLANCHERS_DOC = (
    ("Aller-retour HTTP cloud, hôte filaire", f"{dix_str(RESEAU_FILAIRE)} ms",
     "12 appels keep-alive, frame 512×206 (20,7 Ko → 27 Ko en base64) — **le plus petit observé**"),
    ("Aller-retour HTTP cloud, téléphone", "150–250 ms",
     "mesures terrain antérieures (Xiaomi, Wi-Fi 192 Mbps) ; ~200 ms aussi depuis un PC"),
    ("Coût marginal montant", COUT_MONTANT,
     "calculé sur 2 points mesurés : 27 Ko → 93 ms, 2 437 Ko → 381 ms (soit ≈ 68 Mbit/s utiles)"),
    ("Inférence serveur, 1 concept", "41 ms (B200) · 30,7 ms (tracker 720p)",
     "mesuré ; A100 4 concepts 328 ms, H200 8 concepts 245 ms"),
    ("Période caméra", "33,3 ms (30 fps) · 16,7 ms (60 fps)",
     "physique : une frame fraîche ne peut pas exister plus souvent"),
    ("Affichage", "16,7 ms à 60 Hz", "physique : 1 vsync, et ± 1 frame d'incertitude u07"),
    ("A/R cloud complet (plancher structurel)", f"{dix_str(PLANCHER_CLOUD)} ms",
     "calculé : réseau le plus petit mesuré + serveur le plus rapide mesuré — **> 100 ms avant tout client**"),
    ("Fenêtre de fraîcheur d'un masque propagé", "200–500 ms",
     "choix d'architecture (u12) : coût d'un keyframe SAM, hors chemin perçu"),
)

# --- § 4 : budget par architecture (nom, contexte, A/R, client, attente, verdict) ---
ARCHITECTURES = (
    ("Cloud A100 eu-west, 4 concepts", "téléphone", (4440, 5780), (1686, 2229), "quart", "cloud"),
    ("Cloud A100 eu-west, 4 concepts", "banc filaire r630", (3870, 5280), (1543, 2104), "quart", "cloud"),
    ("Cloud H200, 8 concepts", "téléphone", (3940, 4970), (1561, 2026), "quart", "cloud"),
    ("Cloud B200 /live, 1 concept", "téléphone", (1910, 2910), (1053, 1511), "quart", "cloud"),
    ("Cloud B200 + tracker vidéo 720p", "téléphone", (1807, 2807), (1027, 1486), "quart", "cloud"),
    ("Cloud B200 + tracker vidéo 720p", "banc filaire r630", (1237, 1237), (576, 951), "vsync", "cloud"),
    ("Boîte GPU dans le LAN (4090/5090)", "téléphone ↔ LAN", (260, 480), (576, 951), "vsync", "limite"),
    ("Keyframe cloud + propagation locale (30 fps)", "téléphone", LOCAL_AR, CLIENT_LOCAL, "local", "local"),
)


def attente_de(ar, mode):
    """Attente du créneau : ¼ de l'A/R (phase uniforme), une demi-vsync, ou la propagation."""
    if mode == "quart":
        return ((ar[0] + 2) // 4, (ar[1] + 2) // 4)
    if mode == "vsync":
        return (0, 167)
    return PROPAGATION


def base_de(mode):
    """Coût client hors attente : tous les étages (cloud) ou capture+compose+affichage (local)."""
    return HORS_PROPAGATION if mode == "local" else CLIENT_CLOUD


def percee_de(ar, client):
    """Latence perçue d'une ligne : A/R + client hors HTTP (attente incluse dans le client)."""
    return (ar[0] + client[0], ar[1] + client[1])


def cellule_verdict(percu, mode):
    """Colonne « < 100 ms ? » du § 4."""
    if mode == "local":
        return f"**oui** (marge {dix_str(CIBLE_DIX - percu[1])} ms)"
    if mode == "limite":
        return f"à la limite ({plage_str(percu)} ms, non garanti)"
    return f"non (+{plage_str((percu[0] - CIBLE_DIX, percu[1] - CIBLE_DIX))} ms)"


def table_etages():
    lignes = ["| étage | ce qu'il couvre | valeur (ms) | provenance | plancher |",
              "|---|---|---:|---|---|"]
    lignes += [f"| `{nom}` | {couvre} | {valeur} | {provenance} | {plancher} |"
               for nom, valeur, couvre, provenance, plancher in ETAGES_DOC]
    return "\n".join(lignes)


def table_serveur():
    lignes = ["| sous-étage serveur (dans `HTTP`) | ms | mesure ou calcul | détail |",
              "|---|---:|---|---|"]
    lignes += [f"| {nom} | {ms} | {nature} | {detail} |"
               for nom, ms, nature, detail in SERVEUR_DOC]
    return "\n".join(lignes)


def table_planchers():
    lignes = ["| grandeur | valeur | comment elle est obtenue |", "|---|---:|---|"]
    lignes += [f"| {nom} | {valeur} | {comment} |" for nom, valeur, comment in PLANCHERS_DOC]
    return "\n".join(lignes)


def table_architectures():
    lignes = ["| architecture | contexte | A/R (ms) | attente (ms) | client hors HTTP (ms) | **perçu (ms)** | < 100 ms ? |",
              "|---|---|---:|---:|---:|---:|---|"]
    for nom, contexte, ar, client, mode_attente, mode_verdict in ARCHITECTURES:
        lignes.append(
            f"| {nom} | {contexte} | {plage_str(ar)} | {plage_str(attente_de(ar, mode_attente))} "
            f"| {plage_str(client)} | **{plage_str(percee_de(ar, client))}** "
            f"| {cellule_verdict(percee_de(ar, client), mode_verdict)} |")
    return "\n".join(lignes)


def table_verdict():
    local = percee_de(LOCAL_AR, CLIENT_LOCAL)
    cloud = [percee_de(ar, client) for _, _, ar, client, _, v in ARCHITECTURES if v == "cloud"]
    a100_tel = percee_de(ARCHITECTURES[0][2], ARCHITECTURES[0][3])
    return "\n".join((
        f"- plancher d'A/R cloud (banc filaire, serveur le plus rapide mesuré) : "
        f"{dix_str(PLANCHER_CLOUD)} ms → déjà > 100 ms",
        f"- meilleur perçu cloud calculé : {dix_str(min(bas for bas, _ in cloud))} ms "
        f"(banc filaire + tracker B200, hypothèse la plus favorable) ; état mesuré aujourd'hui "
        f"côté téléphone : {plage_str(a100_tel)} ms",
        f"- propagation locale : {plage_str(local)} ms perçus → "
        f"**sous 100 ms avec {dix_str(CIBLE_DIX - local[1])} ms de marge**",
        f"- boîte GPU dans le LAN : {plage_str(LAN_PERÇU)} ms → franchit la cible en bas de plage, "
        f"la dépasse en haut",
        f"- budget de calcul laissable à un modèle **local sur l'appareil** (u15) : "
        f"≤ {dix_str(CIBLE_DIX - ALLOCATION[0][1] - ALLOCATION[2][1] - ALLOCATION[3][1])} ms "
        f"par frame pour tenir 100 ms perçus à 60 Hz",
        f"- débit : à 30 fps, tout étage de calcul doit tenir {dix_str(CIBLE_DIX // 3)} ms par frame "
        f"(sinon des frames sont sautées) — contrainte distincte de la latence"))

def table_budget():
    marge = CIBLE_DIX - ALLOC_TOTAL
    lignes = ["| étage du chemin local | cible | justification |", "|---|---:|---|"]
    lignes += [f"| {nom} | ≤ {dix_str(cible)} | {justification} |"
               for nom, cible, justification in ALLOCATION]
    lignes.append(f"| **total alloué** | **≤ {dix_str(ALLOC_TOTAL)}** | "
                  f"**marge {dix_str(marge)} ms sur 100** ({marge // 10} %) |")
    return "\n".join(lignes)


def blocs():
    """Les 6 blocs chiffrés du document, dans l'ordre."""
    return {"étages": table_etages(), "serveur": table_serveur(),
            "planchers": table_planchers(), "architectures": table_architectures(),
            "verdict": table_verdict(), "budget": table_budget()}


def lire_doc(chemin):
    with open(chemin, encoding="utf-8") as fichier:
        return fichier.read()


def write_doc(chemin=DOC_DEFAUT):
    texte = lire_doc(chemin)
    remplaces = 0
    for nom, corps in blocs().items():
        mot = re.compile(r"<!-- table:" + nom + r" -->\n.*?<!-- /table:" + nom + r" -->", re.S)
        texte, compte = mot.subn(f"<!-- table:{nom} -->\n{corps}\n<!-- /table:{nom} -->", texte)
        remplaces += compte
    with open(chemin, "w", encoding="utf-8") as fichier:
        fichier.write(texte)
    print(f"OK   {remplaces} blocs écrits dans {os.path.relpath(chemin, RACINE)}")
    return 0


def check_doc(chemin=DOC_DEFAUT):
    texte = lire_doc(chemin)
    trouves = dict(FRAPPE.findall(texte))
    attendus = blocs()
    ecarts = [nom for nom, corps in attendus.items() if trouves.get(nom, "").strip() != corps.strip()]
    for nom in attendus:
        if nom not in trouves:
            print(f"KO   bloc « {nom} » absent du document")
    if ecarts:
        print(f"KO   {len(ecarts)} bloc(s) divergent : {', '.join(ecarts)}")
        return 1
    print(f"OK   {len(attendus)} tableaux retrouvés à l'identique dans "
          f"{os.path.basename(chemin)}")
    return 0


def limites():
    """Vérifie ses propres limites de la maison : < 500 lignes, chaque fonction < 50 lignes."""
    chemin = os.path.abspath(__file__)
    source = lire_doc(chemin)
    lignes = source.count("\n") + 1
    tailles = [(noeud.name, (noeud.end_lineno or noeud.lineno) - noeud.lineno + 1)
               for noeud in ast.walk(ast.parse(source))
               if isinstance(noeud, (ast.FunctionDef, ast.AsyncFunctionDef))]
    pire = max(tailles, key=lambda item: item[1])
    return lignes, pire


def verdict():
    local = percee_de(LOCAL_AR, CLIENT_LOCAL)
    marge = CIBLE_DIX - local[1]
    print(f"keyframe + propagation locale : {plage_str(local)} ms perçus → "
          f"verdict : < 100 ms (marge {dix_str(marge)} ms)")
    print(f"  étages locaux : CAPTURE {dix_str(CAPTURE[0])} + PROPAGATION "
          f"{plage_str(PROPAGATION)} + COMPOSE {plage_str(COMPOSE)} + DISPLAY "
          f"{plage_str(DISPLAY)} = {plage_str(local)} ms")
    print(f"  allocation locale : {dix_str(ALLOC_TOTAL)} ms alloués "
          f"(capture 25, propagation 10, compose 3, affichage 17, aléas 15) → "
          f"{dix_str(CIBLE_DIX - ALLOC_TOTAL)} ms de marge")
    print(f"  plancher d'A/R cloud : {dix_str(PLANCHER_CLOUD)} ms "
          f"(93 réseau + 30,7 serveur) → hors cible pour tout chemin réseau")
    return 0 if marge > 0 else 1


def assertions():
    """Les 14 assertions du `--selftest` : (libellé, prédicat)."""
    cloud = [percee_de(ar, client) for _, _, ar, client, _, v in ARCHITECTURES if v == "cloud"]
    local = percee_de(LOCAL_AR, CLIENT_LOCAL)
    ecart_max = max(abs(client[i] - (base_de(mode)[i] + attente_de(ar, mode)[i]))
                    for _, _, ar, client, mode, _ in ARCHITECTURES
                    for i in (0, 1))
    lignes, pire = limites()
    return (
        (f"sommes étages cloud : {plage_str(CLIENT_CLOUD)}", lambda: CLIENT_CLOUD == (576, 784)),
        (f"sommes keyframe + propagation : {plage_str(local)}", lambda: local == (466, 554)),
        (f"plancher A/R cloud {dix_str(PLANCHER_CLOUD)} ms > 100 ms", lambda: PLANCHER_CLOUD > CIBLE_DIX),
        ("les 6 architectures cloud dépassent 100 ms", lambda: len(cloud) == 6 and min(haut for _, haut in cloud) > CIBLE_DIX),
        ("perçu = A/R + client sur les 8 lignes",
         lambda: all(percee_de(ar, client) == (ar[0] + client[0], ar[1] + client[1])
                     for _, _, ar, client, _, _ in ARCHITECTURES)),
        (f"client = base + attente à un dixième près (écart max {dix_str(ecart_max)})",
         lambda: ecart_max <= 1),
        (f"A/R médian mesuré {dix_str(AR_MEDIAN)} ms ∈ [{dix_str(AR_BANDE[0])} ; {dix_str(AR_BANDE[1])}]",
         lambda: AR_BANDE[0] <= AR_MEDIAN <= AR_BANDE[1]),
        (f"la boîte LAN franchit la cible en bas de plage seulement ({plage_str(LAN_PERÇU)} ms)",
         lambda: LAN_PERÇU[0] < CIBLE_DIX < LAN_PERÇU[1]),
        ("la propagation locale est la seule ligne sous 100 ms",
         lambda: min(haut for _, haut in cloud) > CIBLE_DIX and local[1] < CIBLE_DIX
                 and LAN_PERÇU[1] > CIBLE_DIX),
        (f"marge du chemin local = {dix_str(CIBLE_DIX - local[1])} ms",
         lambda: CIBLE_DIX - local[1] == 446),
        (f"allocation du budget cible = {dix_str(ALLOC_TOTAL)} ms, "
         f"marge {dix_str(CIBLE_DIX - ALLOC_TOTAL)} ms",
         lambda: ALLOC_TOTAL == 700 and CIBLE_DIX - ALLOC_TOTAL == 300),
        (f"H200 téléphone {dix_str(H200_TEL)} ms modélisés < {plage_str(H200_MESURE)} ms mesurés",
         lambda: H200_TEL < H200_MESURE[0]),
        (f"débit 30 fps = {dix_str(CIBLE_DIX // 3)} ms/frame",
         lambda: CIBLE_DIX // 3 == 333),
        (f"limites du script : {lignes} l., fonction max {pire[1]} l. ({pire[0]})",
         lambda: lignes < 500 and pire[1] < 50),
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
    commande, reste = argv[0], argv[1:]
    if commande == "--selftest":
        return selftest()
    if commande == "--verdict":
        return verdict()
    if commande == "--check-doc":
        return check_doc(reste[0] if reste else DOC_DEFAUT)
    if commande == "--write-doc":
        return write_doc(reste[0] if reste else DOC_DEFAUT)
    print(f"argument inconnu : {commande}", file=sys.stderr)
    print("usage : budget_latence.py [--selftest|--check-doc|--write-doc|--verdict] [doc]",
          file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))