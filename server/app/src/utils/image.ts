import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import type { CapturedPhoto } from '../types';

/** Bord le plus long envoyé au backend (limite le poids et la taille des masques). */
export const MAX_IMAGE_EDGE = 1024;
const JPEG_QUALITY = 0.8;

/**
 * Redimensionne et compresse la photo avant l'envoi : le backend renvoie des
 * masques à la taille de l'image reçue, donc une entrée bornée protège la mémoire.
 */
export async function preparePhoto(uri: string): Promise<CapturedPhoto> {
  const context = ImageManipulator.manipulate(uri, {
    maxWidth: MAX_IMAGE_EDGE,
    maxHeight: MAX_IMAGE_EDGE,
  });
  const rendered = await context.renderAsync();
  const result = await rendered.saveAsync({ base64: true, compress: JPEG_QUALITY, format: SaveFormat.JPEG });
  if (!result.base64) throw new Error("Préparation de l'image impossible (base64 absent).");
  return { uri: result.uri, base64: result.base64, width: result.width, height: result.height };
}