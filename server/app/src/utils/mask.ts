import UPNG from 'upng-js';
import { base64ToBytes, bytesToBase64 } from './base64';
import { hexToRgb } from './color';

/** Les masques sont réduits avant ré-encodage : ils sont affichés en petit. */
export const MAX_MASK_EDGE = 512;

export interface MaskRequest {
  maskB64: string;
  color: string;
  opacity: number;
}

/**
 * Transforme un masque PNG 8 bits niveaux de gris (255 = objet) en PNG RGBA
 * colorisé et semi-transparent, superposable à la photo.
 * @returns data URI PNG, ou null si le masque n'est pas exploitable.
 */
export function colorizeMask({ maskB64, color, opacity }: MaskRequest): string | null {
  try {
    const decoded = UPNG.decode(base64ToBytes(maskB64));
    const rgba = new Uint8Array(UPNG.toRGBA8(decoded)[0]);
    const { width, height } = decoded;
    const step = Math.max(1, Math.ceil(Math.max(width, height) / MAX_MASK_EDGE));
    const outWidth = Math.max(1, Math.floor(width / step));
    const outHeight = Math.max(1, Math.floor(height / step));
    const [red, green, blue] = hexToRgb(color);
    const out = new Uint8Array(outWidth * outHeight * 4);
    for (let y = 0; y < outHeight; y += 1) {
      for (let x = 0; x < outWidth; x += 1) {
        const src = (y * step * width + x * step) * 4;
        const dst = (y * outWidth + x) * 4;
        out[dst] = red;
        out[dst + 1] = green;
        out[dst + 2] = blue;
        out[dst + 3] = Math.round(rgba[src] * opacity);
      }
    }
    const png = UPNG.encode([out.buffer], outWidth, outHeight, 0);
    return `data:image/png;base64,${bytesToBase64(new Uint8Array(png))}`;
  } catch {
    return null;
  }
}