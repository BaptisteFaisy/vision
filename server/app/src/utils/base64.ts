// 4096 : reste sous les limites d'arguments des moteurs JS (Hermes inclus).
const CHUNK_SIZE = 4096;

/** Décode une chaîne base64 (sans préfixe data:) en octets. */
export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/** Encode des octets en base64 (sans préfixe data:). */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += CHUNK_SIZE) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + CHUNK_SIZE));
  }
  return btoa(binary);
}

/** Préfixe data: à partir de données base64 pour alimenter un composant Image. */
export function toDataUri(base64: string, mime: string = 'image/jpeg'): string {
  return `data:${mime};base64,${base64}`;
}