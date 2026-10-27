"""Résumés d'erreurs de requête, partagés par les apps Modal (sam3-vision, sam3-live).

``RequestValidationError.errors()`` contient la valeur fautive ``input`` : un corps
malformé qui se trouve contenir l'image renverrait ~2 Mo de base64 au client (et dans
chaque log entre les deux). Seuls le chemin du champ et le message sont gardés, et le
tout est borné. Stdlib uniquement : importable hors conteneur.
"""

MAX_DETAIL_ITEMS = 8
MAX_DETAIL_CHARS = 300


def validation_detail(errors: list) -> str:
    """Résumer les erreurs de validation sans réémettre le payload soumis."""
    parts = []
    for error in errors[:MAX_DETAIL_ITEMS]:
        location = ".".join(str(item) for item in error.get("loc", ())) or "body"
        parts.append(f"{location}: {error.get('msg', 'invalid value')}")
    if len(errors) > MAX_DETAIL_ITEMS:
        parts.append(f"(+{len(errors) - MAX_DETAIL_ITEMS} plus)")
    return ("invalid request body: " + "; ".join(parts))[:MAX_DETAIL_CHARS]
