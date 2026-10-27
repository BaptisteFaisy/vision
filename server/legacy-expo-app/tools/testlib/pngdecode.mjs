/**
 * Décodeur PNG minimal pour les tests (non entrelacé, zlib de Node) : il ne dépend
 * pas de la bibliothèque testée. Gère les types 0/2/3/4/6 en 8 bits et les
 * profondeurs 1/2/4 pour le gris et la palette (ce que produit upng-js).
 */
import { inflateSync } from 'node:zlib';

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const CHANNELS = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 };

function assertSignature(buf) {
  if (!buf.subarray(0, 8).equals(SIGNATURE)) throw new Error('signature PNG absente');
}

function readChunks(buf) {
  const out = { palette: null, transparency: null, idat: [] };
  let offset = 8;
  while (offset + 12 <= buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.toString('ascii', offset + 4, offset + 8);
    const data = buf.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      out.width = data.readUInt32BE(0);
      out.height = data.readUInt32BE(4);
      out.depth = data[8];
      out.colorType = data[9];
      if (data[12] !== 0) throw new Error('PNG entrelacé non géré');
    } else if (type === 'PLTE') {
      out.palette = Uint8Array.from(data);
    } else if (type === 'tRNS') {
      out.transparency = Uint8Array.from(data);
    } else if (type === 'IDAT') {
      out.idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  return out;
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

/** Dé-filtre les lignes (filtres PNG 0-4) et renvoie les octets bruts par ligne. */
function unfilter(data, width, height, channels, depth) {
  const bitsPerPixel = channels * depth;
  const bpl = Math.ceil((width * bitsPerPixel) / 8);
  const bpp = Math.max(1, bitsPerPixel >> 3);
  const rows = [];
  for (let y = 0; y < height; y += 1) {
    const start = y * (bpl + 1);
    const filter = data[start];
    const row = Uint8Array.from(data.subarray(start + 1, start + 1 + bpl));
    const prev = y > 0 ? rows[y - 1] : null;
    for (let i = 0; i < bpl; i += 1) {
      const left = i >= bpp ? row[i - bpp] : 0;
      const up = prev ? prev[i] : 0;
      const upLeft = prev && i >= bpp ? prev[i - bpp] : 0;
      if (filter === 1) row[i] = (row[i] + left) & 0xff;
      else if (filter === 2) row[i] = (row[i] + up) & 0xff;
      else if (filter === 3) row[i] = (row[i] + ((left + up) >> 1)) & 0xff;
      else if (filter === 4) row[i] = (row[i] + paeth(left, up, upLeft)) & 0xff;
      else if (filter !== 0) throw new Error(`filtre PNG inconnu : ${filter}`);
    }
    rows.push(row);
  }
  return rows;
}

/** Échantillonne l'index `index` d'une ligne pour une profondeur donnée. */
function sampleAt(row, index, depth) {
  if (depth === 8) return row[index];
  if (depth === 16) throw new Error('PNG 16 bits non géré');
  const perByte = 8 / depth;
  const byte = row[Math.floor(index / perByte)];
  const shift = 8 - depth * ((index % perByte) + 1);
  return (byte >> shift) & ((1 << depth) - 1);
}

function scale(value, depth) {
  return depth === 8 ? value : Math.round((value * 255) / ((1 << depth) - 1));
}

/** Étend un pixel vers RGBA selon son type de couleur. */
function pixelAt(row, i, c, meta) {
  const { colorType, depth, palette, transparency } = meta;
  const raw = (k) => sampleAt(row, i * c + k, depth);
  if (colorType === 6) return [raw(0), raw(1), raw(2), raw(3)];
  if (colorType === 2) return [raw(0), raw(1), raw(2), 255];
  if (colorType === 4) return [raw(0), raw(0), raw(0), raw(1)];
  if (colorType === 0) {
    const gray = scale(raw(0), depth);
    const transparent = transparency && transparency.length === 2 && transparency[1] === raw(0);
    return [gray, gray, gray, transparent ? 0 : 255];
  }
  const index = raw(0);
  const alpha = transparency && index < transparency.length ? transparency[index] : 255;
  return [palette[index * 3], palette[index * 3 + 1], palette[index * 3 + 2], alpha];
}

/** Décode un PNG en { width, height, rgba }. */
export function decodePng(bytes) {
  const buf = Buffer.from(bytes);
  assertSignature(buf);
  const meta = readChunks(buf);
  const c = CHANNELS[meta.colorType];
  if (!c) throw new Error(`type de couleur PNG non géré : ${meta.colorType}`);
  if (meta.colorType === 3 && !meta.palette) throw new Error('palette PNG absente');
  const rows = unfilter(inflateSync(Buffer.concat(meta.idat)), meta.width, meta.height, c, meta.depth);
  const rgba = new Uint8Array(meta.width * meta.height * 4);
  for (let y = 0; y < meta.height; y += 1) {
    for (let x = 0; x < meta.width; x += 1) {
      rgba.set(pixelAt(rows[y], x, c, meta), (y * meta.width + x) * 4);
    }
  }
  return { width: meta.width, height: meta.height, rgba };
}