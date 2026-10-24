import { Linking, StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../theme';
import { AppButton } from './AppButton';

/** Forme minimale d'un PermissionResponse d'Expo (évite une dépendance directe). */
interface PermissionLike {
  granted: boolean;
  canAskAgain?: boolean | null;
}

interface Props {
  title: string;
  body: string;
  permission: PermissionLike;
  onRequest: () => void;
  onOpenAppSettings: () => void;
}

/** Écran de demande d'autorisation : message clair si l'utilisateur a refusé. */
export function PermissionRequest({ title, body, permission, onRequest, onOpenAppSettings }: Props) {
  const canAskAgain = permission.canAskAgain !== false;
  return (
    <View style={styles.root}>
      <View style={styles.card}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.body}>{body}</Text>
        {!canAskAgain ? (
          <Text style={styles.denied}>
            Autorisation refusée définitivement. Ouvre les réglages du téléphone pour l'accorder, puis reviens dans l'app.
          </Text>
        ) : null}
        <AppButton
          label={canAskAgain ? 'Autoriser' : 'Ouvrir les réglages du téléphone'}
          onPress={canAskAgain ? onRequest : () => void Linking.openSettings()}
        />
        <AppButton label="Réglages du backend" variant="ghost" onPress={onOpenAppSettings} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg, justifyContent: 'center', padding: spacing.xl },
  card: { backgroundColor: colors.surface, borderRadius: radius.lg, padding: spacing.xl, gap: spacing.md },
  title: { color: colors.text, fontSize: 18, fontWeight: '700' },
  body: { color: colors.textMuted, fontSize: 14, lineHeight: 20 },
  denied: { color: colors.danger, fontSize: 13, lineHeight: 19 },
});