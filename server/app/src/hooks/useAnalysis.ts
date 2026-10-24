import { useCallback, useEffect, useRef, useState } from 'react';
import { analyze, describeError } from '../api';
import type { AnalyzeResponse, CapturedPhoto } from '../types';

export type AnalysisState =
  | { status: 'loading' }
  | { status: 'ready'; data: AnalyzeResponse }
  | { status: 'error'; message: string };

/** Lance /analyze sur la photo courante, avec relance manuelle. */
export function useAnalysis(photo: CapturedPhoto, apiUrl: string) {
  const [state, setState] = useState<AnalysisState>({ status: 'loading' });
  const requestId = useRef(0);

  const run = useCallback(async () => {
    requestId.current += 1;
    const current = requestId.current;
    setState({ status: 'loading' });
    try {
      const data = await analyze(apiUrl, photo.base64);
      if (requestId.current === current) setState({ status: 'ready', data });
    } catch (caught) {
      if (requestId.current === current) setState({ status: 'error', message: describeError(caught) });
    }
  }, [apiUrl, photo.base64]);

  useEffect(() => {
    void run();
  }, [run]);

  return { state, retry: run };
}