"""Logique de segmentation : prompts, mapping FR→EN, boîtes, coupe top-global."""
import numpy as np


def test_segmentation_logic(segmentation) -> None:
    from test_units import check, raises

    check("prompts null géré en amont (normalize lève)",
          raises(lambda: segmentation.normalize_prompts(None), segmentation.PromptError))
    check("prompts nettoyés et dédupliqués",
          segmentation.normalize_prompts([" voiture ", "voiture", "piéton"]) == ["voiture", "piéton"])
    check("prompts vides → PromptError",
          raises(lambda: segmentation.normalize_prompts([]), segmentation.PromptError))
    check("prompts que vides → PromptError",
          raises(lambda: segmentation.normalize_prompts(["", "   "]), segmentation.PromptError))
    check("prompts > MAX → PromptError",
          raises(lambda: segmentation.normalize_prompts([f"c{i}" for i in range(segmentation.MAX_PROMPTS + 1)]),
                 segmentation.PromptError))
    check("prompt trop long → PromptError",
          raises(lambda: segmentation.normalize_prompts(["x" * (segmentation.MAX_PROMPT_CHARS + 1)]),
                 segmentation.PromptError))
    check("concept FR → EN", segmentation.model_concept("une voiture") == "car")
    check("concept inconnu passes through", segmentation.model_concept("kayak") == "kayak")
    box = segmentation._clamp_box([-10, 5, 9999, 20], 2.0, 2.0, (100, 40))
    check("box bornée dans l'image", box == [0.0, 10.0, 100.0, 40.0], str(box))


def test_finalize_truncated(segmentation) -> None:
    """finalize liste les labels évincés du top global (champ `truncated`)."""
    from test_units import check

    entries = [
        {"label": f"objet{i}", "score": 0.9 - i * 0.1, "box": [0.0, 0.0, 4.0, 4.0],
         "mask": np.zeros((8, 8), dtype=np.uint8)}
        for i in range(5)
    ]
    small = segmentation.finalize(entries, (8, 8), 3, "none")
    check("truncated: objets gardés",
          [item["label"] for item in small["objects"]] == ["objet0", "objet1", "objet2"],
          str([item["label"] for item in small["objects"]]))
    check("truncated: évincés listés dans l'ordre",
          small["truncated"] == ["objet3", "objet4"], str(small.get("truncated")))
    wide = segmentation.finalize(entries, (8, 8), 8, "none")
    check("truncated: rien d'évincé → vide", wide["truncated"] == [], str(wide.get("truncated")))
    rle = segmentation.finalize(entries, (8, 8), 3, "bits", mask_format="rle", mask_edge=256)
    check("truncated: même liste en rle", rle["truncated"] == ["objet3", "objet4"],
          str(rle.get("truncated")))
