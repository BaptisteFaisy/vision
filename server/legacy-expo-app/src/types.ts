/** Contrat HTTP partagé avec le backend SAM 3.1 (voir docs/SPEC.md). */

export type Box = [number, number, number, number];

export interface SegmentObject {
  label: string;
  score: number;
  box: Box;
  mask_b64: string;
}

export interface SegmentResponse {
  width: number;
  height: number;
  objects: SegmentObject[];
}

export interface AnalyzeResponse {
  description: string;
  tags: string[];
  objects: SegmentObject[];
}

export interface AskResponse {
  answer: string;
}

export interface HealthResponse {
  ok: boolean;
  model: string;
  device: string;
}

export type ChatRole = 'user' | 'assistant';

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

/** Photo prête à être envoyée au backend (base64 sans préfixe data:). */
export interface CapturedPhoto {
  uri: string;
  base64: string;
  width: number;
  height: number;
}

export type FailureKind = 'timeout' | 'network' | 'http' | 'invalid';