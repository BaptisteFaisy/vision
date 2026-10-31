"""Image helpers for the sam3-vision backend.

Base64 <-> PIL decoding, resizing, binary-mask PNG encoding and the JPEG data
URL handed to the vision LLM all live here so every route reuses one path.
"""

from __future__ import annotations

import base64
import binascii
import io
import struct
import zlib

import numpy as np
from PIL import Image, UnidentifiedImageError

JPEG_QUALITY = 85
LLM_MAX_EDGE = 1024

# Format « bits » (compact, sans PNG) : en-tête fixe de 6 octets puis un flux zlib.
#   magic  : 2 octets, b"M1"   (identifie la version du format)
#   largeur: uint16 little-endian, hauteur: uint16 little-endian (taille exacte, en px)
#   corps  : zlib(niveau 6) sur les lignes du masque binaire empaquetées par
#            ``numpy.packbits`` (1 bit/pixel, chaque ligne complétée à l'octet).
# Le client relit ``hauteur`` lignes de ``ceil(largeur / 8)`` octets, déplie les bits et
# ne garde que ``largeur`` colonnes : 1 = objet, 0 = fond (aucune perte, 0,165 ms et
# 166 o mesurés contre 1,015 ms et 504 o en PNG gris).
MASK_BITS_MAGIC = b"M1"
MASK_BITS_HEADER = "<2sHH"
MASK_BITS_HEADER_SIZE = 6
MASK_BITS_ZLIB_LEVEL = 6
# uint16 dans l'en-tête : au-delà, le format ne sait pas décrire la taille.
MASK_MAX_SIDE = 65535
# Représentations servables : « bits » (le plus léger/rapide), « png » (contrat
# historique des APK installés) et « none » (aucun masque, le client dessine la boîte).
MASK_ENCODINGS = ("bits", "png", "none")
# Défaut « png » et non « bits » : un téléphone qui n'envoie pas ``mask_encoding`` lit
# ``mask_b64`` comme un PNG sans regarder le champ. Changer le défaut leur enverrait du
# M1 illisible → overlay mort. Le codec léger reste donc strictly sur demande.
DEFAULT_MASK_ENCODING = "png"


class ImageDecodeError(ValueError):
    """Raised when an incoming base64 payload is not a readable image."""


class MaskFormatError(ValueError):
    """Masque « bits » illisible : base64, en-tête M1 ou flux zlib invalide."""


class MaskEncodingError(ValueError):
    """Représentation de masque inconnue demandée par le client."""


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


def decode_jpeg_bytes(raw: bytes) -> Image.Image:
    """Décode un JPEG/PNG brut (entrée ``octet-stream`` de ``POST /live``) en RGB."""
    if not isinstance(raw, (bytes, bytearray)) or not raw:
        raise ImageDecodeError("empty image body")
    try:
        with Image.open(io.BytesIO(bytes(raw))) as opened:
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


def _mask_header(size: tuple[int, int]) -> bytes:
    """En-tête « M1 » (magic + largeur + hauteur, uint16 little-endian) validé pour (w, h)."""
    width, height = size
    if not (0 < width <= MASK_MAX_SIDE and 0 < height <= MASK_MAX_SIDE):
        raise MaskFormatError(f"mask size out of range for the bits format: {width}x{height}")
    return struct.pack(MASK_BITS_HEADER, MASK_BITS_MAGIC, width, height)


def encode_mask_bits_b64(mask, size: tuple[int, int]) -> str:
    """Encode un masque binaire en 1 bit/pixel + zlib, base64 (format « M1 »).

    Trois fois moins d'octets que le PNG gris 8 bits et aucun décodeur d'image côté
    client : un simple ``Inflater`` suffit. Le masque restitué par
    :func:`decode_mask_bits_b64` est identique à celui de :func:`mask_to_uint8` — déjà
    binaire 0/255, il n'y a donc aucune perte.
    """
    header = _mask_header(size)
    packed = np.packbits(mask_to_uint8(mask, size) > 0, axis=1)
    blob = header + zlib.compress(packed.tobytes(), MASK_BITS_ZLIB_LEVEL)
    return base64.b64encode(blob).decode("ascii")


def decode_mask_bits_b64(payload: str) -> np.ndarray:
    """Inverse de :func:`encode_mask_bits_b64` : tableau uint8 0/255 de forme (h, w)."""
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError, TypeError) as exc:
        raise MaskFormatError(f"invalid base64 payload: {exc}") from exc
    if len(raw) < MASK_BITS_HEADER_SIZE:
        raise MaskFormatError("payload shorter than the M1 header")
    magic, width, height = struct.unpack(MASK_BITS_HEADER, raw[:MASK_BITS_HEADER_SIZE])
    if magic != MASK_BITS_MAGIC:
        raise MaskFormatError(f"unknown mask magic {magic!r}")
    row_bytes = -(-width // 8)  # octets par ligne, arrondis à l'octet supérieur
    try:
        body = zlib.decompress(raw[MASK_BITS_HEADER_SIZE:])
    except zlib.error as exc:
        raise MaskFormatError(f"invalid zlib stream: {exc}") from exc
    bits = np.frombuffer(body, dtype=np.uint8)
    if bits.size != row_bytes * height:
        raise MaskFormatError(f"payload holds {bits.size} bytes, expected {row_bytes * height}")
    unpacked = np.unpackbits(bits).reshape(height, row_bytes * 8)
    return (unpacked[:, :width] * 255).astype(np.uint8)


# Format « rle » (codec compact) : runs LEB128, sans perte, sur une grille réduite.
#   runs   : alternance 0/255 en commençant par 0, chaque run = varint LEB128 non
#            signé (7 bits/octet, MSB = suite) ; la somme des runs vaut w*h ; un masque
#            commençant par 1 s'ouvre donc par un run 0 (éventuellement nul).
#   grille : côté long = ``mask_edge`` (défaut 256), ratio porté par ``mask_scale``.
# Miroir exact du décodeur Kotlin (``MaskRle.kt`` + ``maskGrid``) : la grille est
# calculée ici avec la même arithmétique float32 que le client, donc le client
# retrouve octet pour octet les dimensions annoncées — un run RLE ne porte aucune
# dimension, la moindre divergence rendrait le masque illisible.
RLE_DEFAULT_EDGE = 256
RLE_MIN_EDGE = 64
RLE_MAX_EDGE = 1024


def _java_round(value: float) -> int:
    """``Math.round(float)`` : plancher de x+0,5 (jamais le ``round`` banquier de Python)."""
    return int(np.floor(np.float32(value) + np.float32(0.5)))


def rle_grid(image_size: tuple[int, int], mask_edge: int) -> tuple[tuple[int, int], float]:
    """Grille RLE pour une frame ``image_size`` : ((largeur, hauteur), ``mask_scale``).

    ``mask_edge`` est borné à [64, 1024] (jamais rejeté, comme ``work_edge``) et la
    grille est dérivée en float32 exactement comme le client la redérivera.
    """
    width, height = image_size
    edge = max(RLE_MIN_EDGE, min(int(mask_edge or RLE_DEFAULT_EDGE), RLE_MAX_EDGE))
    scale = edge / max(max(width, height), 1)
    grid_w = max(1, _java_round(np.float32(width) * np.float32(scale)))
    grid_h = max(1, _java_round(np.float32(height) * np.float32(scale)))
    return (grid_w, grid_h), scale


def _encode_varint(value: int) -> bytes:
    """Entier non signé en LEB128 (miroir de ``readVarint`` côté Kotlin)."""
    out = bytearray()
    remaining = int(value)
    while True:
        chunk = remaining & 0x7F
        remaining >>= 7
        if remaining:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def encode_mask_rle_b64(mask, grid: tuple[int, int]) -> str:
    """Encode un masque en runs LEB128 sur ``grid`` (w, h), base64, sans perte.

    Les frontières 0/1 sont trouvées par différences numpy, pas par une boucle
    Python sur les ~65 000 cellules : même suite de runs, même base64, ~100×
    moins d'itérations. Un masque commençant par 1 s'ouvre par un run 0
    (éventuellement nul), comme le décodeur Kotlin l'attend.
    """
    cells = (mask_to_uint8(mask, grid) > 0).reshape(-1)
    changes = np.flatnonzero(np.diff(cells.astype(np.int8))).tolist()
    bounds = [0] + [change + 1 for change in changes] + [cells.size]
    lengths = [stop - start for start, stop in zip(bounds, bounds[1:])]
    if cells.size and cells[0]:
        lengths = [0] + lengths
    runs = b"".join(_encode_varint(length) for length in lengths)
    return base64.b64encode(runs).decode("ascii")


def decode_mask_rle_b64(payload: str, grid: tuple[int, int]) -> np.ndarray:
    """Inverse de :func:`encode_mask_rle_b64` : tableau uint8 0/255 de forme (h, w).

    Miroir du décodeur Kotlin (mêmes refus : varint tronqué, dépassement, total
    inexact) : les tests l'utilisent comme oracle du format servi.
    """
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError, TypeError) as exc:
        raise MaskFormatError(f"invalid base64 payload: {exc}") from exc
    width, height = grid
    total = width * height
    pixels = np.zeros(total, dtype=np.uint8)
    at, level, index = 0, 0, 0
    size = len(raw)
    while index < size:
        value, shift = 0, 0
        while True:
            if index >= size:
                raise MaskFormatError("truncated varint in RLE payload")
            chunk = raw[index]
            index += 1
            value |= (chunk & 0x7F) << shift
            shift += 7
            if not chunk & 0x80 or shift > 35:
                break
        if value > total - at:
            raise MaskFormatError(f"RLE run overflows the {width}x{height} grid")
        if level:
            pixels[at:at + value] = 255
        at += value
        level = 255 - level
    if at != total:
        raise MaskFormatError(f"RLE holds {at} pixels, expected {total}")
    return pixels.reshape(height, width)


def check_mask_encoding(mask_encoding) -> str:
    """Valeur d'encodage appliquée : absent/vide → ``png`` (contrat historique), sinon validé."""
    encoding = (mask_encoding or DEFAULT_MASK_ENCODING).strip().lower()
    if encoding not in MASK_ENCODINGS:
        raise MaskEncodingError(
            f"unknown mask_encoding {mask_encoding!r} "
            f"(expected one of {', '.join(MASK_ENCODINGS)})")
    return encoding


def encode_mask_b64(mask, size: tuple[int, int], mask_encoding: str) -> str:
    """Encode un masque dans la représentation négociée ("" quand le client n'en veut pas).

    Voie unique d'encodage, partagée par ``/live`` (``fast_core``) et ``/segment``
    (``segmentation``) : un seul endroit où le coût par masque vit, jamais deux.
    """
    if mask_encoding == "none":
        return ""
    if mask_encoding == "bits":
        return encode_mask_bits_b64(mask, size)
    return encode_mask_png_b64(mask, size)


def to_llm_data_url(image: Image.Image) -> str:
    """Encode the image as a downscaled JPEG data URL for the vision LLM."""
    buffer = io.BytesIO()
    resize_max_edge(image, LLM_MAX_EDGE).save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")