import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../theme';
import { AppButton } from './AppButton';

interface ErrorProps {
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}

export function ErrorBanner({ message, onRetry, retryLabel = 'Réessayer' }: ErrorProps) {
  return (
    <View style={styles.error} accessibilityRole="alert">
      <Text style={styles.errorText}>{message}</Text>
      {onRetry ? (
        <AppButton label={retryLabel} variant="danger" compact onPress={onRetry} style={styles.errorButton} />
      ) : null}
    </View>
  );
}

interface LoadingProps {
  label: string;
}

export function LoadingBlock({ label }: LoadingProps) {
  return (
    <View style={styles.block}>
      <ActivityIndicator color={colors.primary} />
      <Text style={styles.loadingText}>{label}</Text>
    </View>
  );
}

export function LoadingOverlay({ label }: LoadingProps) {
  return (
    <View pointerEvents="auto" style={styles.overlay}>
      <View style={styles.block}>
        <ActivityIndicator color={colors.primary} />
        <Text style={[styles.loadingText, { color: colors.text }]}>{label}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  error: {
    backgroundColor: 'rgba(242,85,90,0.12)',
    borderColor: colors.danger,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.sm,
  },
  errorText: { color: colors.danger, fontSize: 14, lineHeight: 19 },
  errorButton: { alignSelf: 'flex-start' },
  block: { alignItems: 'center', justifyContent: 'center', padding: spacing.xl, gap: spacing.sm },
  loadingText: { color: colors.textMuted, fontSize: 14 },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(11,13,18,0.6)',
  },
});