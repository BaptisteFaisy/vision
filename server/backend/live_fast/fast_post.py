"""Post-traitement DETR à sigmoid différé (équivalent bit-à-bit au HF).

Référence : transformers 5.20.2
``models/sam3/image_processing_sam3.py::post_process_instance_segmentation``.
Le HF applique le sigmoid sur TOUS les masques (B×200×288²) avant de seuiller
les scores ; seules K requêtes survivent (K < 8 typiquement). Ici on seuille
d'abord, puis sigmoid sur les gardés seuls — le sigmoid étant
élément-par-élément, le résultat est bit-identique (parité prouvée :
``tests_units/post_tests.py``, égalité exacte, 100 graines + cas limites).

``torch`` n'est importé que dans les fonctions : ce module reste importable
et testable sans stack GPU.
"""

from __future__ import annotations


def _torch():
    import torch  # tardif : importable sans le runtime lourd
    return torch


def _scale_boxes(boxes, target_sizes):
    """Copie conforme de ``_scale_boxes`` HF (boîtes × [l, h, l, h])."""
    torch = _torch()
    if isinstance(target_sizes, (list, tuple)):
        image_height = torch.tensor([i[0] for i in target_sizes])
        image_width = torch.tensor([i[1] for i in target_sizes])
    elif isinstance(target_sizes, torch.Tensor):
        image_height, image_width = target_sizes.unbind(1)
    else:
        raise TypeError("`target_sizes` must be a list, tuple or torch.Tensor")
    scale_factor = torch.stack([image_width, image_height, image_width, image_height], dim=1)
    scale_factor = scale_factor.unsqueeze(1).to(boxes.device)
    return boxes * scale_factor


def post_detections(processor, outputs, concepts, work_size, kept_only,
                    score_thr, mask_thr):
    """Dispatche post HF / kept-only (mêmes args, même retour)."""
    target_sizes = [(work_size[1], work_size[0])] * len(concepts)
    if kept_only:
        return post_kept_only(outputs, score_thr, mask_thr, target_sizes)
    return processor.post_process_instance_segmentation(
        outputs, threshold=score_thr, mask_threshold=mask_thr, target_sizes=target_sizes)


def post_kept_only(outputs, threshold, mask_threshold, target_sizes=None):
    """Même contrat que le post HF, sigmoid après seuillage (bit-identique).

    ``outputs`` : pred_logits (B,Q), pred_boxes (B,Q,4) xyxy, pred_masks
    (B,Q,h,w), presence_logits (B,1) ou None. Rend une liste de dicts
    {scores, boxes, masks} par image, comme le HF.
    """
    if target_sizes is not None and len(target_sizes) != outputs.pred_logits.shape[0]:
        raise ValueError("Make sure that you pass in as many target sizes as images")
    torch = _torch()
    batch_scores = outputs.pred_logits.sigmoid()
    if outputs.presence_logits is not None:
        batch_scores = batch_scores * outputs.presence_logits.sigmoid()
    batch_boxes = outputs.pred_boxes
    if target_sizes is not None:
        batch_boxes = _scale_boxes(batch_boxes, target_sizes)
    results = []
    for idx, (scores, boxes, masks) in enumerate(zip(batch_scores, batch_boxes, outputs.pred_masks)):
        keep = scores > threshold
        kept = torch.sigmoid(masks[keep])
        if target_sizes is not None and len(kept) > 0:
            kept = torch.nn.functional.interpolate(
                kept.unsqueeze(0), size=target_sizes[idx],
                mode="bilinear", align_corners=False).squeeze(0)
        kept = (kept > mask_threshold).to(torch.long)
        results.append({"scores": scores[keep], "boxes": boxes[keep], "masks": kept})
    return results
