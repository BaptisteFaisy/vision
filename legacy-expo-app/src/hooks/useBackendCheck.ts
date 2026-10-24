import { useCallback, useState } from 'react';
import { describeError, health } from '../api';

export interface BackendCheck {
  kind: 'ok' | 'error';
  text: string;
}

/** Vérifie que l'URL saisie répond bien sur GET /health. */
export function useBackendCheck() {
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<BackendCheck | null>(null);

  const check = useCallback(async (url: string) => {
    setPending(true);
    setResult(null);
    try {
      const info = await health(url);
      setResult({ kind: 'ok', text: `Backend joignable : ${info.model} sur ${info.device}.` });
    } catch (error) {
      setResult({ kind: 'error', text: describeError(error) });
    } finally {
      setPending(false);
    }
  }, []);

  return { pending, result, check };
}