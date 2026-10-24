export type Rgb = [number, number, number];

/** Convertit #rgb ou #rrggbb en composantes 0-255 (blanc si invalide). */
export function hexToRgb(hex: string): Rgb {
  const clean = hex.trim().replace('#', '');
  const full = clean.length === 3 ? clean.split('').map((char) => char + char).join('') : clean;
  const value = Number.parseInt(full, 16);
  if (full.length !== 6 || !Number.isFinite(value)) return [255, 255, 255];
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
}