import { useState } from 'react';
import { DEFAULT_API_URL, resetApiUrl, saveApiUrl } from '../utils/config';
import { messageOf } from '../utils/errors';
import { isProbablyValidUrl } from '../utils/url';

export type SettingsNote = { kind: 'error' | 'success'; text: string } | null;

/** Formulaire d'URL du backend : brouillon, validation, persistance, réinitialisation. */
export function useApiUrlSettings(apiUrl: string, onChange: (url: string) => void) {
  const [draft, setDraft] = useState(apiUrl);
  const [note, setNote] = useState<SettingsNote>(null);
  const [busy, setBusy] = useState(false);

  const apply = async (persist: () => Promise<string>, successText: string, fallback: string) => {
    setBusy(true);
    try {
      const url = await persist();
      setDraft(url);
      onChange(url);
      setNote({ kind: 'success', text: successText });
    } catch (caught) {
      setNote({ kind: 'error', text: messageOf(caught, fallback) });
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!isProbablyValidUrl(draft)) {
      setNote({ kind: 'error', text: 'URL invalide : elle doit commencer par http:// ou https://.' });
      return;
    }
    await apply(() => saveApiUrl(draft), 'URL enregistrée, elle sera utilisée dès la prochaine requête.', 'Enregistrement impossible.');
  };

  const reset = () =>
    apply(() => resetApiUrl(), 'URL par défaut restaurée.', 'Réinitialisation impossible.');

  return { draft, setDraft, note, busy, save, reset, defaultUrl: DEFAULT_API_URL };
}