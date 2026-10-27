import AsyncStorage from '@react-native-async-storage/async-storage';
import Constants from 'expo-constants';
import { normalizeBaseUrl } from './url';

const STORAGE_KEY = '***';

function defaultFromConfig(): string {
  const extra = (Constants.expoConfig?.extra ?? {}) as { apiUrl?: unknown };
  return typeof extra.apiUrl === 'string' ? extra.apiUrl : '';
}

/** Valeur par défaut déclarée dans app.json → expo.extra.apiUrl. */
export const DEFAULT_API_URL: string = normalizeBaseUrl(defaultFromConfig());

/** URL du backend : valeur stockée si présente, sinon défaut app.json. */
export async function loadApiUrl(): Promise<string> {
  try {
    const stored = await AsyncStorage.getItem(STORAGE_KEY);
    if (stored && stored.trim()) return normalizeBaseUrl(stored);
  } catch {
    // Storage indisponible : on retombe sur le défaut compilé.
  }
  return DEFAULT_API_URL;
}

/** Persiste l'URL et renvoie la valeur normalisée. */
export async function saveApiUrl(value: string): Promise<string> {
  const url = normalizeBaseUrl(value);
  await AsyncStorage.setItem(STORAGE_KEY, url);
  return url;
}

/** Efface la valeur stockée et revient au défaut app.json. */
export async function resetApiUrl(): Promise<string> {
  await AsyncStorage.removeItem(STORAGE_KEY);
  return DEFAULT_API_URL;
}