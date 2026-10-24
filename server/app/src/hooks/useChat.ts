import { useCallback, useRef, useState } from 'react';
import { ask, describeError } from '../api';
import type { CapturedPhoto, ChatMessage } from '../types';

/** Historique de questions/réponses pour /ask, avec relance de la dernière question. */
export function useChat(photo: CapturedPhoto, apiUrl: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [failedQuestion, setFailedQuestion] = useState<string | null>(null);
  const inFlight = useRef(false);

  const send = useCallback(
    async (question: string) => {
      const text = question.trim();
      if (!text || inFlight.current) return;
      const history = messages;
      const optimistic: ChatMessage = { role: 'user', content: text };
      inFlight.current = true;
      setPending(true);
      setError(null);
      setFailedQuestion(null);
      setMessages((previous) => [...previous, optimistic]);
      try {
        const { answer } = await ask(apiUrl, photo.base64, text, history.length > 0 ? history : null);
        setMessages((previous) => [...previous, { role: 'assistant', content: answer }]);
      } catch (caught) {
        setMessages((previous) => previous.filter((message) => message !== optimistic));
        setFailedQuestion(text);
        setError(describeError(caught));
      } finally {
        inFlight.current = false;
        setPending(false);
      }
    },
    [apiUrl, messages, photo.base64],
  );

  const retry = useCallback(() => {
    if (failedQuestion) void send(failedQuestion);
  }, [failedQuestion, send]);

  return { messages, pending, error, send, retry };
}