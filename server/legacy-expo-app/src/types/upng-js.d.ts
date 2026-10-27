/** Types minimaux pour upng-js (décodeur/encodeur PNG pur JS, sans dépendance native). */
declare module 'upng-js' {
  export interface DecodedPng {
    width: number;
    height: number;
    depth: number;
    ctype: number;
    frames: unknown[];
    data: Uint8Array;
    tabs: Record<string, unknown>;
  }

  const UPNG: {
    decode(buffer: ArrayBuffer | Uint8Array): DecodedPng;
    toRGBA8(image: DecodedPng): ArrayBuffer[];
    encode(buffers: ArrayBuffer[], width: number, height: number, cnum?: number, delays?: number[]): ArrayBuffer;
  };

  export default UPNG;
}