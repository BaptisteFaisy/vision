# NOTES — vérifications API SAM 3.1 (2026-10-24)

- Source de vérité : `transformers` **v5.20.2** (tag `v5.20.2`, dernière version publiée) — `src/transformers/models/sam3/`.
- Il n'existe **aucune** classe `Sam3_1` / `Sam3_1Model` : `__all__` = `Sam3Model`, `Sam3VisionModel`, `Sam3ViTModel`, `Sam3PreTrainedModel` (+ `Sam3Processor`). `config.json` de `unsloth/sam3.1` ⇒ `architectures = ["Sam3Model"]`. Les variantes « 3.1 » ne sont que des poids.
- `Sam3Processor.post_process_instance_segmentation(outputs, threshold=0.3, mask_threshold=0.5, target_sizes=None)` **existe** (processing_sam3.py:612) et renvoie `list[dict]` : `scores (N,)`, `boxes (N,4)` xyxy en pixels (normalisées puis × `target_sizes`), `masks (N,H,W)` 0/1.
- `Sam3Model.forward(pixel_values | vision_embeds, input_ids | text_embeds, attention_mask, input_boxes, …)` → `Sam3ImageSegmentationOutput` (`pred_masks`, `pred_boxes`, `pred_logits`, `presence_logits`). `vision_embeds` est réutilisable : un seul passage encodeur image pour les N prompts (implémenté ainsi).
- Processeur : resize fixe **1008×1008**, `mask_size` 288 ⇒ segmentation lancée sur une copie ≤ 1024 px, masques remontés en nearest à la taille d'origine (mémoire + latence).
- Piège Modal 1.6.2 : `@app.cls(max_inputs>1)` lève `InvalidError("Only max_inputs=1 is currently supported")` ⇒ concurrence 1 (verrou `threading.Lock` conservé autour du GPU).
- Chemins image : `Path(__file__).resolve().parent` (indépendant du CWD) ; modules copiés dans `/root/backend_src` + `PYTHONPATH`, imports plats (`import imaging`).
- `modal.Secret.from_name("sam3-llm")` est paresseux : `import modal_app` passe sans token. Imports torch/transformers/PIL/fastapi/openai différés ⇒ import local OK.
- Encodeur texte = CLIP **anglais** : les concepts français courants sont traduits pour le modèle (`model_concept`), le label renvoyé reste celui du client.
- Piège FastAPI : avec `from __future__ import annotations`, les modèles importés **localement** dans `web()` ne sont pas résolus ⇒ le corps devient un paramètre de query (400). Future-import retiré de `modal_app.py`.
- Vérifié hors GPU : `py_compile`, `import modal_app`, 8 tests logiques (décodage, resize, masque PNG, prompts, boxes, JSON, réglages, plomberie LLM) et 5 tests HTTP sur la vraie app FastAPI (routes, 400 image/validation/prompts, 500 interne, payloads SPEC).
- Non vérifié ici (ni torch ni GPU en local, et pas de `modal deploy` lancé) : `from_pretrained` réel, forwards bf16, mémoire A10G.
