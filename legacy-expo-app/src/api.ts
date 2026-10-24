import { parseAnalyze, parseAsk, parseHealth, parseSegment } from './parse';
import type {
  AnalyzeResponse,
  AskResponse,
  ChatMessage,
  FailureKind,
  HealthResponse,
  SegmentResponse,
} from './types';
import { messageOf } from './utils/errors';
import { normalizeBaseUrl } from './utils/url';

/** Délai maximal par requête (spec : 60 s). */
export const REQUEST_TIMEOUT_MS = 60_000;

export class ApiError extends Error {
  readonly kind: FailureKind;
  readonly status?: number;

  constructor(kind: FailureKind, message: string, status?: number) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
  }
}

function postInit(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

function timeoutMessage(): string {
  return `Le backend n'a pas répondu en ${REQUEST_TIMEOUT_MS / 1000} s. Réessaie, ou vérifie l'URL dans Réglages.`;
}

function networkMessage(baseUrl: string): string {
  const target = normalizeBaseUrl(baseUrl) || '(URL vide)';
  return `Impossible de joindre le backend (${target}). Vérifie l'URL dans Réglages et ta connexion.`;
}

function isAbort(error: unknown): boolean {
  return error instanceof Error && (error.name === 'AbortError' || /abort/i.test(error.message));
}

function errorDetail(raw: string): string | null {
  try {
    const body = JSON.parse(raw) as { detail?: unknown };
    return typeof body.detail === 'string' ? body.detail : null;
  } catch {
    return null;
  }
}

function httpMessage(path: string, status: number, raw: string): string {
  const detail = errorDetail(raw);
  return detail ? `${path} a refusé la requête (${status}) : ${detail}` : `${path} a répondu une erreur HTTP ${status}.`;
}

async function readJson<T>(response: Response, path: string, parse: (value: unknown) => T): Promise<T> {
  const raw = await response.text();
  if (!response.ok) throw new ApiError('http', httpMessage(path, response.status, raw), response.status);
  let body: unknown;
  try {
    body = JSON.parse(raw) as unknown;
  } catch {
    throw new ApiError('invalid', `${path} a renvoyé une réponse non JSON.`);
  }
  try {
    return parse(body);
  } catch (error) {
    throw new ApiError('invalid', `${path} : réponse inattendue (${messageOf(error)}).`);
  }
}

/** POST JSON unique point d'entrée : timeout 60 s, erreurs typées, parsing validé. */
async function request<T>(
  baseUrl: string,
  path: string,
  init: RequestInit,
  parse: (value: unknown) => T,
): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    let response: Response;
    try {
      response = await fetch(`${normalizeBaseUrl(baseUrl)}${path}`, { ...init, signal: controller.signal });
    } catch (error) {
      throw isAbort(error) ? new ApiError('timeout', timeoutMessage()) : new ApiError('network', networkMessage(baseUrl));
    }
    return await readJson(response, path, parse);
  } finally {
    clearTimeout(timer);
  }
}

export function segment(baseUrl: string, imageB64: string, prompts: string[] | null = null): Promise<SegmentResponse> {
  return request(baseUrl, '/segment', postInit({ image_b64: imageB64, prompts }), parseSegment);
}

export function analyze(baseUrl: string, imageB64: string): Promise<AnalyzeResponse> {
  return request(baseUrl, '/analyze', postInit({ image_b64: imageB64 }), parseAnalyze);
}

export function ask(
  baseUrl: string,
  imageB64: string,
  question: string,
  history: ChatMessage[] | null = null,
): Promise<AskResponse> {
  const body = { image_b64: imageB64, question: question, history: history && history.length > 0 ? history : null };
  return request(baseUrl, '/ask', postInit(body), parseAsk);
}

export function health(baseUrl: string): Promise<HealthResponse> {
  return request(baseUrl, '/health', { method: 'GET' }, parseHealth);
}

/** Message court et honnête pour l'interface. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return messageOf(error, 'Erreur inconnue.');
}