/** Retire les espaces et les slashs de fin d'URL. */
export function normalizeBaseUrl(value: string): string {
  return value.trim().replace(/\/+$/, '');
}

/** Vrai si l'URL ressemble à une base HTTP(S) exploitable. */
export function isProbablyValidUrl(value: string): boolean {
  return /^https?:\/\/[^\s/]+/i.test(normalizeBaseUrl(value));
}