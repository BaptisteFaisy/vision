const DEFAULT_MESSAGE = 'Une erreur inattendue est survenue.';

/** Message lisible à partir de n'importe quelle valeur jetée. */
export function messageOf(error: unknown, fallback: string = DEFAULT_MESSAGE): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  if (typeof error === 'string' && error.trim()) return error;
  return fallback;
}