import { useCameraPermissions, CameraView } from 'expo-camera';
import { StyleSheet, View } from 'react-native';
import { AppButton } from '../components/AppButton';
import { ErrorBanner, LoadingOverlay } from '../components/Feedback';
import { HeaderAction, ScreenHeader } from '../components/ScreenHeader';
import { PermissionRequest } from '../components/PermissionRequest';
import { usePhotoCapture } from '../hooks/usePhotoCapture';
import { colors, radius, spacing } from '../theme';
import type { CapturedPhoto } from '../types';

interface Props {
  onCaptured: (photo: CapturedPhoto) => void;
  onOpenSettings: () => void;
}

export function CameraScreen({ onCaptured, onOpenSettings }: Props) {
  const [permission, requestPermission] = useCameraPermissions();
  const { cameraRef, busy, error, capture, pickFromGallery, reportError, clearError } = usePhotoCapture({ onCaptured });

  if (!permission) {
    return <View style={styles.root} />;
  }
  if (!permission.granted) {
    return (
      <PermissionRequest
        title="Accès à la caméra requis"
        body="SAM Vision photographie la scène puis l'envoie à ton backend pour la segmentation. Rien n'est envoyé sans que tu appuies sur le déclencheur."
        permission={permission}
        onRequest={() => void requestPermission()}
        onOpenAppSettings={onOpenSettings}
      />
    );
  }

  return (
    <View style={styles.root}>
      <ScreenHeader title="SAM Vision" subtitle="Photo → segmentation → questions" right={<HeaderAction label="Réglages" onPress={onOpenSettings} />} />
      <View style={styles.preview}>
        <CameraView
          ref={cameraRef}
          style={StyleSheet.absoluteFill}
          facing="back"
          onMountError={(event) => reportError(`Caméra indisponible : ${event.message}`)}
        />
        {busy ? <LoadingOverlay label="Préparation de la photo…" /> : null}
      </View>
      <View style={styles.footer}>
        {error ? <ErrorBanner message={error} onRetry={clearError} retryLabel="Fermer" /> : null}
        <View style={styles.actions}>
          <AppButton label="Galerie" variant="secondary" onPress={() => void pickFromGallery()} disabled={busy} style={styles.flex} />
          <AppButton label={busy ? '…' : 'Photographier'} onPress={() => void capture()} disabled={busy} style={styles.flex} />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  preview: { flex: 1, marginHorizontal: spacing.lg, borderRadius: radius.lg, overflow: 'hidden', backgroundColor: '#000000' },
  footer: { padding: spacing.lg, gap: spacing.md },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  flex: { flex: 1 },
});