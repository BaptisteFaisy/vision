"""Tests du tracker (30/10) : plafond, dédup FR/EN, texte traduit pour le CLIP.

Chargé par ``scripts/test_units.py`` comme les autres groupes (voir weights_tests).
"""


def test_tracking(tracking) -> None:
    """Plafond PROMPT_LIMIT + FR→EN (dédup et texte, label brut gardé)."""
    from test_units import check, raises

    check("tracker : plafond 18 (murs iSee, ordre user 31/10)",
          tracking.PROMPT_LIMIT == 22, str(tracking.PROMPT_LIMIT))
    many = [f"c{i}" for i in range(20)]
    check("tracker : troncature au plafond",
          tracking.clean_prompts(many) == many[:tracking.PROMPT_LIMIT])
    check("tracker : dédupliqués",
          tracking.clean_prompts(["car", "car", "pole"]) == ["car", "pole"])
    check("tracker : dédup FR/EN sur le concept modèle",
          tracking.clean_prompts(["trottoir", "sidewalk", "car"]) == ["trottoir", "car"])
    check("tracker : rebord et curb = un seul concept",
          tracking.clean_prompts(["rebord", "curb"]) == ["rebord"])
    check("tracker : vides → PromptTooLong",
          raises(lambda: tracking.clean_prompts([]), tracking.PromptTooLong))
    import tempfile
    import types

    seen: dict = {}

    class _FakePredictor:
        model = object()

        def handle_request(self, req):
            if req["type"] == "start_session":
                return {"session_id": "ut"}
            seen.update(req)
            return {"outputs": {}}

    with tempfile.TemporaryDirectory() as tmp:
        root = tracking.SESSIONS_ROOT
        tracking.SESSIONS_ROOT = tmp
        try:
            session = tracking.LiveSession(
                _FakePredictor(), "ut-fr", ["rebord"],
                types.SimpleNamespace(size=(100, 100)), capacity=4)
            objects = session.start()
        finally:
            tracking.SESSIONS_ROOT = root
    check("tracker : FR traduit pour le CLIP, label brut gardé",
          seen.get("text") == "curb"
          and session.subsessions[0]["prompt"] == "rebord"
          and objects == [])
