"""Concepts FR→EN remis à l'encodeur texte (CLIP anglais) de SAM 3.1.

Sans torch ni réseau : importable par les tests unitaires et l'outillage.
Déployé avec chaque copie de ``segmentation.py`` (racine ``backend/`` et
``live_fast/``) — les deux exemplaires doivent rester identiques.
"""

from __future__ import annotations

import unicodedata


# SAM 3.1's text encoder is an English CLIP: French concepts are mapped to their
# English counterpart for the model, while the label stays as the user typed it.
CONCEPT_ALIASES = {
    # Personnes
    "personne": "person",
    "personnes": "person",
    "gens": "person",
    "humain": "human",
    "homme": "man",
    "femme": "woman",
    "enfant": "child",
    "piéton": "pedestrian",
    "piétons": "pedestrian",
    "passant": "passerby",
    "passants": "passerby",
    "cycliste": "cyclist",
    "cyclistes": "cyclist",
    "motard": "motorcyclist",
    "motards": "motorcyclist",
    "joggeur": "jogger",
    "joggeurs": "jogger",
    "foule": "crowd",
    # Véhicules
    "voiture": "car",
    "voitures": "car",
    "véhicule": "vehicle",
    "camion": "truck",
    "camionnette": "van",
    "fourgon": "van",
    "autocar": "coach",
    "vélo": "bicycle",
    "moto": "motorcycle",
    "trottinette": "scooter",
    "trottinettes": "scooter",
    "tramway": "tram",
    "tram": "tram",
    "métro": "subway",
    # « bateau » = bateau de trottoir (curb ramp) en contexte piéton — le sens
    # « boat » (hors domaine malvoyant) reste via « bateau à voile ».
    "bateau": "curb ramp",
    "bateaux": "curb ramp",
    "bateau à voile": "boat",
    "avion": "airplane",
    "hélicoptère": "helicopter",
    # Animaux
    "animal": "animal",
    "animaux": "animal",
    "chien": "dog",
    "chat": "cat",
    "oiseau": "bird",
    "cheval": "horse",
    "pigeon": "pigeon",
    "pigeons": "pigeon",
    "canard": "duck",
    "canards": "duck",
    "écureuil": "squirrel",
    "lapin": "rabbit",
    "vache": "cow",
    "mouton": "sheep",
    "chèvre": "goat",
    "poule": "chicken",
    "papillon": "butterfly",
    "abeille": "bee",
    # Nature et météo
    "arbre": "tree",
    "plante": "plant",
    "fleur": "flower",
    "buisson": "bush",
    "buissons": "bush",
    "haie": "hedge",
    "haies": "hedge",
    "herbe": "grass",
    "pelouse": "lawn",
    "champ": "field",
    "forêt": "forest",
    "colline": "hill",
    "vallée": "valley",
    "falaise": "cliff",
    "rivière": "river",
    "fleuve": "river",
    "lac": "lake",
    "plage": "beach",
    "ciel": "sky",
    "soleil": "sun",
    "lune": "moon",
    "étoile": "star",
    "nuage": "cloud",
    "nuages": "cloud",
    "pluie": "rain",
    "neige": "snow",
    "orage": "storm",
    "brouillard": "fog",
    "arc-en-ciel": "rainbow",
    "mer": "sea",
    "montagne": "mountain",
    "eau": "water",
    # Bâtiments et façades
    "maison": "house",
    "immeuble": "building",
    "bâtiment": "building",
    "tour": "tower",
    "tours": "tower",
    "mur": "wall",
    "murs": "wall",
    "muret": "wall",
    "murets": "wall",
    "façade": "facade",
    "façades": "facade",
    "porte": "door",
    "portes": "door",
    "portail": "gate",
    "portails": "gate",
    "portillon": "gate",
    "fenêtre": "window",
    "fenêtres": "window",
    "vitre": "window",
    "vitres": "window",
    "vitrine": "storefront",
    "vitrines": "storefront",
    "toit": "roof",
    "toits": "roof",
    "balcon": "balcony",
    "terrasse": "terrace",
    "cheminée": "chimney",
    "auvent": "awning",
    "escalier": "stairs",
    "escaliers": "stairs",
    "marche": "step",
    "marches": "steps",
    "rampe": "ramp",
    "rampes": "ramp",
    "rambarde": "railing",
    "rambardes": "railing",
    "garde-corps": "railing",
    "main courante": "handrail",
    "balustrade": "railing",
    "église": "church",
    "cathédrale": "cathedral",
    "mosquée": "mosque",
    "temple": "temple",
    "château": "castle",
    "musée": "museum",
    "école": "school",
    "hôpital": "hospital",
    "mairie": "city hall",
    "théâtre": "theater",
    "cinéma": "cinema",
    "hôtel": "hotel",
    "boulangerie": "bakery",
    "pharmacie": "pharmacy",
    "supermarché": "supermarket",
    "magasin": "store",
    "magasins": "store",
    "gare": "train station",
    "quai": "platform",
    "aéroport": "airport",
    "pont": "bridge",
    "passerelle": "footbridge",
    "souterrain": "underpass",
    "parking": "parking lot",
    "station-service": "gas station",
    "péage": "toll booth",
    "chantier": "construction site",
    "travaux": "construction",
    "échafaudage": "scaffolding",
    # Rue et circulation
    "rue": "street",
    "route": "road",
    "autoroute": "highway",
    "carrefour": "intersection",
    "rond-point": "roundabout",
    "trottoir": "sidewalk",
    "trottoirs": "sidewalk",
    "bordure": "curb",
    "bordures": "curb",
    "rebord": "curb",
    "rebords": "curb",
    "bordure de trottoir": "curb",
    "bordure du trottoir": "curb",
    "rebord de trottoir": "curb",
    "rebord du trottoir": "curb",
    "bord de trottoir": "curb",
    "bord du trottoir": "curb",
    "kerb": "curb",
    "marche d'escalier": "step",
    "marches d'escalier": "steps",
    "caniveau": "gutter",
    "caniveaux": "gutter",
    "rigole": "gutter",
    "bateau de trottoir": "curb ramp",
    "bateaux de trottoir": "curb ramp",
    "passage bateau": "curb ramp",
    "passage piéton": "crosswalk",
    "passage clouté": "crosswalk",
    "piste cyclable": "bike lane",
    "bande cyclable": "bike lane",
    "feu tricolore": "traffic light",
    "feux tricolores": "traffic light",
    "feu rouge": "traffic light",
    "feu vert": "traffic light",
    "stop": "stop sign",
    "sens interdit": "no entry sign",
    "panneau de signalisation": "traffic sign",
    # Mobilier urbain
    "barrière": "barrier",
    "barrières": "barrier",
    "poteau": "pole",
    "poteaux": "pole",
    "pylône": "pylon",
    "plot": "bollard",
    "plots": "bollard",
    "banc": "bench",
    "bancs": "bench",
    "lampadaire": "street lamp",
    "lampadaires": "street lamp",
    "réverbère": "street lamp",
    "poubelle": "trash can",
    "poubelles": "trash can",
    "boîte aux lettres": "mailbox",
    "horodateur": "parking meter",
    "parcmètre": "parking meter",
    "abribus": "bus shelter",
    "arrêt de bus": "bus stop",
    "kiosque": "kiosk",
    "fontaine": "fountain",
    "grillage": "fence",
    "clôture": "fence",
    "palissade": "fence",
    # Mobilier intérieur
    "table": "table",
    "chaise": "chair",
    "canapé": "sofa",
    "lit": "bed",
    # Objets et divers
    "ordinateur": "computer",
    "téléphone": "phone",
    "nourriture": "food",
    "repas": "meal",
    "fruit": "fruit",
    "légume": "vegetable",
    "vêtement": "clothing",
    "chaussure": "shoe",
    "sac": "bag",
    "objet": "object",
    "chose": "thing",
    "texte": "text",
    "panneau": "sign",
}
_ARTICLES = ("un ", "une ", "le ", "la ", "les ", "des ", "du ", "l'", "the ", "a ", "an ")


def _deaccent(text: str) -> str:
    """``barrière`` → ``barriere`` : la frappe sans accents trouve l'alias."""
    return "".join(
        char for char in unicodedata.normalize("NFKD", text) if not unicodedata.combining(char)
    )


# Recherche insensible aux accents : « barriere » et « barrière » matchent.
_ALIASES_NORM = {_deaccent(key): value for key, value in CONCEPT_ALIASES.items()}


def model_concept(prompt: str) -> str:
    """Concept anglais remis au modèle.

    Les alias FR/EN sont traduits ; un prompt inconnu est remis **normalisé**
    (minuscules, articles retirés, accents retirés, espaces pliés) au lieu d'être
    recopié tel quel : c'est aussi la clé de dédoublonnage de
    ``segmentation.normalize_prompts``, donc « chaise », « Chair » et
    « une chaise » ne peuvent plus désigner deux concepts différents pour le
    serveur — pas plus que « barriere » et « une barrière ».
    """
    key = prompt.strip().lower()
    for article in _ARTICLES:
        if key.startswith(article):
            key = key[len(article):].strip()
            break
    norm = _deaccent(key)
    return _ALIASES_NORM.get(norm, norm)
