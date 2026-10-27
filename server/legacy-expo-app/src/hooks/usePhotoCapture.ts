import type { CameraView } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import type { RefObject } from 'react';
import { useCallback, useRef, useState } from 'react';
import type { CapturedPhoto } from '../types';
import { messageOf } from '../utils/errors';
import { preparePhoto } from '../utils/image';

interface Options {
  onCaptured: (photo: CapturedPhoto) => void;
}

async function takeCameraUri(cameraRef: RefObject<CameraView | null>): Promise<string> {
  const picture = await cameraRef.current?.takePictureAsync({ quality: 0.9 });
  if (!picture?.uri) throw new Error('Aucune photo capturée, réessaie.');
  return picture.uri;
}

async function pickGalleryUri(): Promise<string | null> {
  const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
  if (!permission.granted) {
    throw new Error('Accès à la galerie refusé. Autorise les photos dans les réglages du téléphone.');
  }
  const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.9 });
  if (result.canceled) return null;
  return result.assets[0]?.uri ?? null;
}

/** Capture caméra ou galerie, avec photo normalisée prête à envoyer. */
export function usePhotoCapture({ onCaptured }: Options) {
  const cameraRef = useRef<CameraView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(
    async (task: () => Promise<string | null>, fallback: string) => {
      if (busy) return;
      setBusy(true);
      setError(null);
      try {
        const uri = await task();
        if (uri) onCaptured(await preparePhoto(uri));
      } catch (caught) {
        setError(messageOf(caught, fallback));
      } finally {
        setBusy(false);
      }
    },
    [busy, onCaptured],
  );

  const capture = useCallback(() => run(() => takeCameraUri(cameraRef), 'Capture impossible.'), [run]);
  const pickFromGallery = useCallback(() => run(pickGalleryUri, 'Sélection impossible.'), [run]);

  const reportError = useCallback((message: string) => setError(message), []);
  const clearError = useCallback(() => setError(null), []);

  return { cameraRef, busy, error, capture, pickFromGallery, reportError, clearError };
}