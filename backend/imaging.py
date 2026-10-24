"""Image helpers for the sam3-vision backend.

Base64 <-> PIL decoding, resizing, binary-mask PNG encoding and the JPEG data
URL handed to the vision LLM all live here so every route reuses one path.
"""

from __future__ import annotations

import base64
import binascii
import io

import numpy as np
from PIL import Image, UnidentifiedImageError

JPEG_QUALITY = 85
LLM_MAX_EDGE = 1024


class ImageDecodeError(ValueError):
    """Raised when an incoming base64 payload is not a readable image."""


def decode_image(image_b64: str) -> Image.Image:
    """Decode a base64 PNG/JPEG payload (optional ``data:`` prefix) into RGB."""
    if not isinstance(image_b64, str) or not image_b64.strip():
        raise ImageDecodeError("image_b64 is empty")
    raw = image_b64.strip()
    if raw.startswith("data:"):
        raw = raw.partition(",")[2]
    try:
        data = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ImageDecodeError(f"invalid base64 payload: {exc}") from exc
    try:
        with Image.open(io.BytesIO(data)) as opened:
            return opened.convert("RGB")
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise ImageDecodeError(f"unreadable image: {exc}") from exc


def resize_max_edge(image: Image.Image, max_edge: int) -> Image.Image:
    """Return the image scaled down so its longest edge is at most ``max_edge``."""
    width, height = image.size
    longest = max(width, height)
    if longest <= max_edge:
        return image
    ratio = max_edge / longest
    size = (max(1, round(width * ratio)), max(1, round(height * ratio)))
    return image.resize(size, Image.Resampling.BILINEAR)


def scale_factors(source_size: tuple[int, int], target_size: tuple[int, int]) -> tuple[float, float]:
    """Pixel multipliers from ``source_size`` to ``target_size`` as (x, y)."""
    source_width, source_height = source_size
    target_width, target_height = target_size
    return target_width / max(source_width, 1), target_height / max(source_height, 1)


def mask_to_uint8(mask, size: tuple[int, int]) -> np.ndarray:
    """Normalise a binary mask (torch tensor or array) to an 8-bit 0/255 map."""
    array = mask.detach().cpu().numpy() if hasattr(mask, "detach") else np.asarray(mask)
    width, height = size
    binary = (np.asarray(array) > 0).astype(np.uint8) * 255
    if binary.shape != (height, width):
        binary = np.asarray(Image.fromarray(binary).resize((width, height), Image.Resampling.NEAREST))
    return binary


def encode_mask_png_b64(mask, size: tuple[int, int]) -> str:
    """Encode a binary mask as an 8-bit grayscale PNG at exactly ``size`` (w, h)."""
    buffer = io.BytesIO()
    Image.fromarray(mask_to_uint8(mask, size)).save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def to_llm_data_url(image: Image.Image) -> str:
    """Encode the image as a downscaled JPEG data URL for the vision LLM."""
    buffer = io.BytesIO()
    resize_max_edge(image, LLM_MAX_EDGE).save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")