#!/usr/bin/env python3
"""Garde clés embarquées : l'APK téléchargé contient des clés qui répondent.

Tant que les secrets du dépôt existent, chaque APK bâti DOIT embarquer la clé
LLM du secret ET cette clé DOIT répondre sur la base du secret — sinon le job
est rouge et aucun artefact ne part. Sans secret, comportement inchangé (APK
sans clé, saisie dans Réglages) : la garde reste inactive.

Trois contrôles, aucun secret affiché (présence, comptes et HTTP seulement) :
A. `local.properties` contient la clé du secret (l'étape « Clés du build »).
B. la clé est DANS le dex de l'APK (le chemin `BuildConfig` a embarqué).
C. la clé RÉPOND : ping `POST {base}/chat/completions` minimal (`max_tokens=5`,
   seuls les tokens émis sont facturés) — un secret périmé échoue ici.

Usage : LLM_BASE_URL=... LLM_API_KEY=... LLM_MODEL=... python3 tools/check-apk-keys.py [--root .]
Sortie : 0 si conforme ou sans secret, 1 sinon. Sans secret, la garde émet un
avertissement `::warning::` (visible dans le résumé du job) au lieu de se taire.
"""
import argparse
import glob
import json
import os
import sys
import urllib.error
import urllib.request
import zipfile

PING_TIMEOUT_SECONDS = 90


def lire_properties(chemin):
    """Contenu brut d'un `local.properties` (commentaires `#` ignorés)."""
    props = {}
    with open(chemin, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                cle, valeur = line.split("=", 1)
                props[cle.strip()] = valeur.strip()
    return props


def dernier_apk(racine):
    """Le plus récent des APK bâtis, ou None (un seul par job en CI)."""
    apks = glob.glob(os.path.join(racine, "app/build/outputs/apk/*/*.apk"))
    if not apks:
        return None
    return max(apks, key=os.path.getmtime)


def cle_dans_dex(chemin_apk, cle):
    """(trouvée, dex lus) : la clé brute figure-t-elle dans un dex ?"""
    trouves, lus = False, 0
    with zipfile.ZipFile(chemin_apk) as zf:
        for nom in zf.namelist():
            if nom.endswith(".dex"):
                lus += 1
                if cle.encode() in zf.read(nom):
                    trouves = True
    return trouves, lus


def ping_llm(base, cle, modele):
    """Statut HTTP d'un appel minimal à `{base}/chat/completions`."""
    corps = json.dumps({"model": modele,
                        "messages": [{"role": "user", "content": "ping"}],
                        "max_tokens": 5}).encode()
    requete = urllib.request.Request(
        base.rstrip("/") + "/chat/completions", data=corps,
        headers={"Authorization": "Bearer " + cle,
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(requete,
                                    timeout=PING_TIMEOUT_SECONDS) as reponse:
            return reponse.status
    except urllib.error.HTTPError as e:
        return e.code


def verifier(racine, base, cle, modele):
    """Liste des erreurs (vide = conforme). Rien n'imprime de secret."""
    if not cle:
        return []  # garde inactive : comportement « APK sans clé » conservé
    if not base or not modele:
        return ["secrets LLM incomplets (base ou modèle vide)"]
    try:
        props = lire_properties(os.path.join(racine, "local.properties"))
    except OSError:
        return ["local.properties absent (étape « Clés du build » manquante ?)"]
    if props.get("llm.api.key") != cle:
        return ["local.properties sans la clé du secret"]
    apk = dernier_apk(racine)
    if apk is None:
        return ["aucun APK trouvé sous app/build/outputs/apk/"]
    trouvee, lus = cle_dans_dex(apk, cle)
    if not trouvee:
        return ["clé absente du dex (%d dex lus)" % lus]
    statut = ping_llm(base, cle, modele)
    if statut != 200:
        return ["ping LLM refusé (HTTP %s)" % statut]
    return []


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    base = os.environ.get("LLM_BASE_URL", "")
    cle = os.environ.get("LLM_API_KEY", "")
    modele = os.environ.get("LLM_MODEL", "")
    if not cle:
        print("Pas de secret LLM : garde inactive (APK sans clé).")
        print("::warning::check-apk-keys : aucun secret LLM — APK sans clé, "
              "garde inactive (saisie dans Réglages).")
        return 0
    erreurs = verifier(args.root, base, cle, modele)
    for erreur in erreurs:
        print("ERREUR : " + erreur)
    if erreurs:
        return 1
    print("ping LLM : HTTP 200 — clés embarquées et valides.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
