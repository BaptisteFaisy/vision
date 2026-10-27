"""Groupes de tests unitaires du backend, chargés par ``scripts/test_units.py``.

Un module par thème ; ``test_units.py`` reste le point d'entrée unique et fournit la
plomberie (stubs ``modal``/``torch``, ``check``, ``raises``, appel ASGI factice). Les
importations de ``test_units`` se font **dans** les fonctions : le package est chargé par
``test_units`` lui-même, un import au sommet serait circulaire.
"""
