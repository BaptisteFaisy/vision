import type { AnalyzeResponse, AskResponse, Box, HealthResponse, SegmentObject, SegmentResponse } from './types';

type Json = Record<string, unknown>;

function asRecord(value: unknown, field: string): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${field} : objet attendu`);
  }
  return value as Json;
}

function asString(value: unknown, field: string): string {
  if (typeof value !== 'string') throw new Error(`${field} : chaîne attendue`);
  return value;
}

function asNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${field} : nombre attendu`);
  return value;
}

function asArray(value: unknown, field: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${field} : tableau attendu`);
  return value;
}

function asStringArray(value: unknown, field: string): string[] {
  return asArray(value, field).map((item, index) => asString(item, `${field}[${index}]`));
}

function asBox(value: unknown, field: string): Box {
  const items = asArray(value, field);
  if (items.length !== 4) throw new Error(`${field} : 4 coordonnées attendues`);
  const [x1, y1, x2, y2] = items.map((item, index) => asNumber(item, `${field}[${index}]`));
  return [x1, y1, x2, y2];
}

function parseObject(value: unknown, field: string): SegmentObject {
  const raw = asRecord(value, field);
  return {
    label: asString(raw.label, `${field}.label`),
    score: asNumber(raw.score, `${field}.score`),
    box: asBox(raw.box, `${field}.box`),
    mask_b64: asString(raw.mask_b64, `${field}.mask_b64`),
  };
}

export function parseObjects(value: unknown, field: string = 'objects'): SegmentObject[] {
  return asArray(value, field).map((item, index) => parseObject(item, `${field}[${index}]`));
}

export function parseSegment(value: unknown): SegmentResponse {
  const raw = asRecord(value, 'réponse /segment');
  return {
    width: asNumber(raw.width, 'width'),
    height: asNumber(raw.height, 'height'),
    objects: parseObjects(raw.objects),
  };
}

export function parseAnalyze(value: unknown): AnalyzeResponse {
  const raw = asRecord(value, 'réponse /analyze');
  const tags = raw.tags === undefined || raw.tags === null ? [] : asStringArray(raw.tags, 'tags');
  return { description: asString(raw.description, 'description'), tags, objects: parseObjects(raw.objects) };
}

export function parseAsk(value: unknown): AskResponse {
  const raw = asRecord(value, 'réponse /ask');
  return { answer: asString(raw.answer, 'answer') };
}

export function parseHealth(value: unknown): HealthResponse {
  const raw = asRecord(value, 'réponse /health');
  return {
    ok: raw.ok === true,
    model: typeof raw.model === 'string' ? raw.model : 'inconnu',
    device: typeof raw.device === 'string' ? raw.device : 'inconnu',
  };
}