#!/usr/bin/env python3
"""Porte anti-dérive du budget de latence (lane L23, unité u09).

Le budget publié vit dans `docs/LATENCE-BUDGET.md` ; les chiffres qu'il emprunte au
code doivent être **ceux du code**. C'est ce que vérifie cette porte — elle échoue
dès qu'un chiffre publié diverge de la valeur réellement présente dans le Kotlin.

Trois contrôles :

A. **renvois explicites** du document — `chemin.kt::SYMBOLE` et `chemin.kt:ligne`.
   Le chiffre publié sur la ligne du document doit être la valeur lue dans le code
   (un renvoi qui ne se lit plus est une dérive, pas une tolérance).
B. **registre** (`REGISTRE`) — les constantes de latence qui dérivent le plus vite :
   délais, cadence, tailles de frame, objets. Toute ligne du document qui les cite
   doit porter leur valeur du code.
C. le registre ne cite que des symboles encore présents : une porte qui renvoie à du
   code disparu ne garde rien.

Un chiffre **absent** n'est pas une divergence : ces cas sortent en avertissement
(`--strict` les refuse). Document absent = budget pas encore publié : avertissement.

Réutilisation : la neutralisation des commentaires et chaînes Kotlin vient de
`tools/check-kotlin-limits.py` (`strip_strings_comments`) — une seule implémentation.

Usage : python3 tools/check-budget-derive.py [--root .] [--strict] [--list]
Sortie : 0 si conforme, 1 si un chiffre diverge, 2 si la racine n'existe pas.
"""
import argparse
import importlib.util
import os
import re
import sys
from collections import namedtuple

DOC_DEFAUT = "docs/LATENCE-BUDGET.md"
GARDE = "check-kotlin-limits.py"
CACHES = {".git", "build", ".gradle", ".idea", "__pycache__"}

# Registre : (clé lisible, chemin du code, symbole lu, citations acceptées dans la doc).
# La chaîne `citer` reste étroite : un motif large ferait échouer des lignes qui ne
# publient pas ce chiffre-là.
Fait = namedtuple("Fait", "cle chemin symbole citer")

REGISTRE = (
    Fait("garde client du réveil vidéo",
         "core/src/main/java/dev/sam3vision/core/net/HttpLiveClient.kt",
         "WAKE_TIMEOUT_SECONDS", r"WAKE_TIMEOUT_SECONDS"),
    Fait("garde de l'extinction",
         "core/src/main/java/dev/sam3vision/core/net/SamLifecycle.kt",
         "RELEASE_TIMEOUT_SECONDS", r"RELEASE_TIMEOUT_SECONDS|SamLifecycle"),
    Fait("fenêtre de réveil côté app",
         "app/src/main/java/dev/sam3vision/app/live/LiveWakeGuard.kt",
         "WAKE_TIMEOUT_MS", r"WAKE_TIMEOUT_MS|LiveWakeGuard"),
    Fait("plancher de cadence du Direct",
         "core/src/main/java/dev/sam3vision/core/live/LiveSession.kt",
         "Cadence.Direct.fastMs", r"Cadence\.Direct|fastMs"),
    Fait("budget de latence d'iSee",
         "core/src/main/java/dev/sam3vision/core/live/LiveSession.kt",
         "Cadence.Fast.latencyBudgetMs", r"Cadence\.Fast|latencyBudgetMs"),
    Fait("côté long de la frame iSee",
         "app/src/main/java/dev/sam3vision/app/isee/FramePolicy.kt",
         "EDGE", r"FramePolicy\.EDGE|côté long de la frame iSee"),
    Fait("côté long de la frame du Direct",
         "app/src/main/java/dev/sam3vision/app/isee/FramePolicy.kt",
         "EDGE", r"FramePolicy\.EDGE|côté long de la frame du Direct"),
    Fait("objets affichés par défaut",
         "core/src/main/java/dev/sam3vision/core/model/ObjectLimits.kt",
         "DEFAULT_ON_SCREEN", r"DEFAULT_ON_SCREEN|ObjectLimits"),
    Fait("délai d'envoi d'une vidéo",
         "core/src/main/java/dev/sam3vision/core/net/HttpSam31VideoClient.kt",
         "UPLOAD_TIMEOUT_SECONDS", r"UPLOAD_TIMEOUT_SECONDS"),
    Fait("ancien délai du direct",
         "core/src/main/java/dev/sam3vision/core/net/HttpLiveClient.kt",
         "LIVE_TIMEOUT_SECONDS", r"LIVE_TIMEOUT_SECONDS"),
)

# Unités d'un chiffre du document : unité -> (dimension, facteur canonique).
UNITES = {
    "ms": ("temps", 1.0), "s": ("temps", 1000.0), "min": ("temps", 60_000.0),
    "us": ("temps", 0.001), "µs": ("temps", 0.001), "ns": ("temps", 1e-6),
    "px": ("px", 1.0), "objet": ("nombre", 1.0), "objets": ("nombre", 1.0),
    "concept": ("nombre", 1.0), "concepts": ("nombre", 1.0),
}

# Le suffixe Kotlin (`45L`, `3.5f`) est consommé, et l'unité n'est prise que si elle
# est collée au chiffre : « 60L » vaut 60, « 180 s » vaut 180 000 ms.
JETON = re.compile(
    r"(-?\d[\d_]*(?:[.,]\d+)?)\s*(µs|us|ns|ms|s|min|px|objets?|concepts?)?[LlFfDd]?"
    r"(?![A-Za-z0-9_])")
RENVOI = re.compile(
    r"([A-Za-z0-9_./-]+\.kt)(?:::(?P<symbole>[A-Za-z0-9_.]+)|:(?P<ligne>\d+))")


# ————— lecture du dépôt —————

def charge_garde():
    """Charge `tools/check-kotlin-limits.py` (neutralisation Kotlin réutilisée)."""
    chemin = os.path.join(os.path.dirname(os.path.abspath(__file__)), GARDE)
    spec = importlib.util.spec_from_file_location("check_kotlin_limits", chemin)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def lis_texte(chemin):
    """Contenu d'un fichier, ou `None` s'il n'existe pas (encodage tolérant)."""
    try:
        with open(chemin, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return None


def index_kotlin(racine):
    """Chemins relatifs des `.kt` du dépôt (caches de build exclus)."""
    trouves = []
    for dossier, sous, fichiers in os.walk(racine):
        sous[:] = [d for d in sous if d not in CACHES]
        for nom in fichiers:
            if nom.endswith(".kt"):
                relatif = os.path.relpath(os.path.join(dossier, nom), racine)
                trouves.append(relatif.replace(os.sep, "/"))
    return sorted(trouves)


def resous(chemin, index, racine):
    """Résout un renvoi du document : exact, sinon suffixe unique. Rend (absolu, souci)."""
    if os.path.isfile(os.path.join(racine, chemin)):
        return os.path.join(racine, chemin), None
    candidats = [p for p in index if p == chemin or p.endswith("/" + chemin)]
    if len(candidats) == 1:
        return os.path.join(racine, candidats[0]), None
    if not candidats:
        return None, "introuvable dans l'arbre"
    return None, "ambigu (%s)" % ", ".join(candidats[:3])


# ————— valeurs du code —————

def unite_du_symbole(symbole):
    """Dimension lue dans le nom du symbole : (dimension, facteur vers l'unité canonique)."""
    if symbole.endswith("_SECONDS"):
        return "temps", 1000.0
    if symbole.endswith("_MS") or symbole.endswith("Ms"):
        return "temps", 1.0
    if "EDGE" in symbole:
        return "px", 1.0
    return "nombre", 1.0


def motif_simple(nom):
    """Déclaration `val NOM … = <nombre>` (const, type explicite, suffixe L)."""
    return r"\bval\s+%s\s*(?::[^=\n]*)?=\s*(-?[\d_]+(?:\.\d+)?)" % re.escape(nom)


def motif_membre(membre, parametre):
    """Argument nommé `parametre = <nombre>` dans `val membre = Type(...)`."""
    return (r"\bval\s+%s\s*=\s*[A-Za-z0-9_]+\(([^)]*)\)" % re.escape(membre),
            r"\b%s\s*=\s*(-?[\d_]+(?:\.\d+)?)" % re.escape(parametre))


def valeur_brute(net, symbole):
    """(valeur, dimension, facteur) du symbole dans un Kotlin déjà neutralisé."""
    morceaux = symbole.split(".")
    if len(morceaux) == 1:
        trouve = re.search(motif_simple(morceaux[0]), net)
    elif len(morceaux) == 3:
        bloc, argument = motif_membre(morceaux[1], morceaux[2])
        enveloppe = re.search(bloc, net, re.S)
        trouve = re.search(argument, enveloppe.group(1)) if enveloppe else None
    else:
        return None
    if not trouve:
        return None
    dimension, facteur = unite_du_symbole(symbole)
    return float(trouve.group(1).replace("_", "")), dimension, facteur


def valeur_code(texte, symbole, garde):
    """(valeur, dimension, facteur) d'un symbole, commentaires neutralisés."""
    return valeur_brute(garde.strip_strings_comments(texte), symbole)


def etiquette(code):
    """« 45 s », « 320 px », « 8 » : la valeur du code telle que son nom l'annonce."""
    valeur, dimension, facteur = code
    if dimension == "temps":
        if facteur == 1000.0:
            return "%g s" % valeur
        if valeur >= 1000 and valeur % 1000 == 0:
            return "%g s" % (valeur / 1000)
        return "%g ms" % valeur
    if dimension == "px":
        return "%g px" % valeur
    return "%g" % valeur


# ————— chiffres d'une ligne du document —————

def jetons(ligne):
    """[(valeur, unité|None)] des chiffres d'une ligne (unités inconnues gardées)."""
    resultat = []
    for trouve in JETON.finditer(ligne):
        brut = trouve.group(1).replace("_", "").replace(",", ".")
        resultat.append((float(brut), trouve.group(2)))
    return resultat


def proche(a, b):
    """Égalité numérique tolérante : les chiffres publiés sont arrondis."""
    return abs(a - b) <= max(1e-6, 0.005 * abs(b))


def correspond(valeur, unite, code):
    """Ce chiffre porte-t-il la valeur du code ? Dimension respectée, jamais devinée."""
    attendu, dimension, facteur = code
    if unite is None:
        return proche(valeur, attendu)
    entree = UNITES.get(unite)
    if entree is None or entree[0] != dimension:
        return False
    return proche(valeur * entree[1], attendu * facteur)


def publie(ligne, code):
    """La ligne publie-t-elle la valeur du code ?"""
    return any(correspond(valeur, unite, code) for valeur, unite in jetons(ligne))


def entiers(ligne):
    """Valeurs entières d'une ligne, pour comparer deux lignes de code."""
    return {int(valeur) for valeur, _ in jetons(ligne) if float(valeur).is_integer()}


def decimaux(ligne):
    """Valeurs non entières d'une ligne : 50,3 se lit 50.3, comme dans le code."""
    return [valeur for valeur, _ in jetons(ligne) if not float(valeur).is_integer()]


def memes_decimaux(ligne, ligne_code):
    """Les deux lignes publient-elles un même nombre à virgule (arrondi près) ?

    Les entiers se comparent exactement ([entiers]) ; les chiffres à virgule — 50,3 ms,
    81,4 ms — n'ont pas d'écriture entière et se comparent donc à l'arrondi près.
    """
    return any(proche(a, b) for a in decimaux(ligne) for b in decimaux(ligne_code))


# ————— contrôle A : renvois explicites —————

def controle_renvoi(chemin, absolu, symbole, ligne_code, ligne, numero, garde):
    """Un renvoi : la ligne du document doit publier la valeur de ce qu'elle cite."""
    texte = lis_texte(absolu)
    if texte is None:
        return ["ligne %d : `%s` illisible" % (numero, chemin)]
    if symbole:
        code = valeur_code(texte, symbole, garde)
        if code is None:
            return ["ligne %d : `%s::%s` ne se lit plus dans le code" % (numero, chemin, symbole)]
        if publie(ligne, code):
            return []
        return ["ligne %d : `%s::%s` vaut %s dans le code — la ligne ne publie pas cette valeur"
                % (numero, chemin, symbole, etiquette(code))]
    lignes_code = texte.splitlines()
    cible = int(ligne_code)
    if cible < 1 or cible > len(lignes_code):
        return ["ligne %d : `%s:%d` dépasse le fichier" % (numero, chemin, cible)]
    ligne_code_texte = lignes_code[cible - 1]
    if entiers(ligne_code_texte) & entiers(ligne) or memes_decimaux(ligne, ligne_code_texte):
        return []
    return ["ligne %d : `%s:%d` ne porte aucun des chiffres publiés (renvoi périmé ?)"
            % (numero, chemin, cible)]


def verifie_renvois(racine, lignes, garde):
    """Contrôle A : chaque renvoi du document vers un fichier Kotlin."""
    erreurs = []
    index = index_kotlin(racine)
    for numero, ligne in lignes:
        for trouve in RENVOI.finditer(ligne):
            chemin, symbole, ligne_code = trouve.group(1), trouve.group("symbole"), trouve.group("ligne")
            absolu, souci = resous(chemin, index, racine)
            if souci:
                erreurs.append("ligne %d : renvoi `%s` %s" % (numero, chemin, souci))
                continue
            erreurs += controle_renvoi(chemin, absolu, symbole, ligne_code, ligne, numero, garde)
    return erreurs


# ————— contrôle B/C : registre —————

def verifie_fait(racine, lignes, fait, garde):
    """Un fait du registre : (erreurs, avertissements) sur sa publication."""
    texte = lis_texte(os.path.join(racine, fait.chemin))
    if texte is None:
        return ["registre : `%s` n'existe plus (porte périmée ?)" % fait.chemin], []
    code = valeur_code(texte, fait.symbole, garde)
    if code is None:
        return ["registre : `%s::%s` ne se lit plus dans le code" % (fait.chemin, fait.symbole)], []
    motif = re.compile(fait.citer)
    citees = [(numero, ligne) for numero, ligne in lignes if motif.search(ligne)]
    if not citees:
        return [], ["registre : « %s » (`%s` = %s) n'est publié nulle part dans le document"
                    % (fait.cle, fait.symbole, etiquette(code))]
    fautives = [numero for numero, ligne in citees if not publie(ligne, code)]
    if not fautives:
        return [], []
    return ["registre : « %s » — ligne(s) %s citent `%s` sans la valeur du code (%s)"
            % (fait.cle, ", ".join(str(n) for n in fautives[:3]), fait.symbole,
               etiquette(code))], []


def verifie_registre(racine, lignes, garde):
    """Contrôles B et C : le registre, publié ou non, existe encore ou non."""
    erreurs, avertissements = [], []
    for fait in REGISTRE:
        constats, manques = verifie_fait(racine, lignes, fait, garde)
        erreurs += constats
        avertissements += manques
    return erreurs, avertissements


# ————— sortie —————

def liste(racine, garde):
    """`--list` : la valeur actuelle de chaque constante (pour écrire le budget)."""
    erreurs = []
    print("registre du budget de latence — valeurs lues dans le code :")
    for fait in REGISTRE:
        texte = lis_texte(os.path.join(racine, fait.chemin))
        code = None if texte is None else valeur_code(texte, fait.symbole, garde)
        if code is None:
            erreurs.append("registre : `%s::%s` ne se lit pas" % (fait.chemin, fait.symbole))
            print("  ?   %-32s %s" % (fait.cle, fait.symbole))
            continue
        print("  %-5s %-32s %s::%s" % (etiquette(code), fait.cle,
                                       os.path.basename(fait.chemin), fait.symbole))
    return erreurs


def rapport(erreurs, avertissements, strict):
    """Imprime les constats (annotations GitHub) et rend le code de sortie."""
    if strict:
        erreurs, avertissements = erreurs + avertissements, []
    for message in avertissements:
        print("::warning::%s" % message)
    for message in erreurs:
        print("::error::%s" % message)
    print("check-budget-derive : %d constante(s) suivie(s), %d erreur(s), %d avertissement(s)"
          % (len(REGISTRE), len(erreurs), len(avertissements)))
    if erreurs:
        print("Règle : un chiffre publié dans %s vient du code, jamais de mémoire." % DOC_DEFAUT)
        return 1
    print("OK — le budget publié ne dérive pas du code.")
    return 0


def parse_args(argv):
    """Options de la porte."""
    analyseur = argparse.ArgumentParser(
        description="Porte anti-dérive du budget de latence (doc ↔ code).")
    analyseur.add_argument("--root", default=".", help="racine du dépôt (défaut : .)")
    analyseur.add_argument("--doc", default=DOC_DEFAUT, help="budget publié à contrôler")
    analyseur.add_argument("--strict", action="store_true",
                           help="refuse aussi les chiffres non publiés")
    analyseur.add_argument("--list", action="store_true",
                           help="affiche la valeur actuelle des constantes suivies")
    return analyseur.parse_args(argv)


def main(argv=None):
    """Charge le document, joue les contrôles, rend le code de sortie."""
    args = parse_args(argv)
    if not os.path.isdir(args.root):
        print("racine introuvable : %s" % args.root, file=sys.stderr)
        return 2
    garde = charge_garde()
    if args.list:
        return rapport(liste(args.root, garde), [], args.strict)
    texte = lis_texte(os.path.join(args.root, args.doc))
    if texte is None:
        print("::warning::%s absent : aucun chiffre de budget à contrôler, la porte "
              "s'activera à sa publication." % args.doc)
        return 0
    lignes = list(enumerate(texte.splitlines(), start=1))
    erreurs = verifie_renvois(args.root, lignes, garde)
    erreurs_registre, avertissements = verifie_registre(args.root, lignes, garde)
    return rapport(erreurs + erreurs_registre, avertissements, args.strict)


if __name__ == "__main__":
    sys.exit(main())