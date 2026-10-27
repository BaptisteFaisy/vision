import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { AppButton } from '../components/AppButton';
import { ScreenHeader } from '../components/ScreenHeader';
import { useApiUrlSettings } from '../hooks/useApiUrlSettings';
import { useBackendCheck } from '../hooks/useBackendCheck';
import { colors, radius, spacing } from '../theme';

interface Props {
  apiUrl: string;
  onChange: (url: string) => void;
  onBack: () => void;
}

export function SettingsScreen({ apiUrl, onChange, onBack }: Props) {
  const { draft, setDraft, note, busy, save, reset, defaultUrl } = useApiUrlSettings(apiUrl, onChange);
  const backend = useBackendCheck();

  return (
    <View style={styles.root}>
      <ScreenHeader title="Réglages" subtitle="Adresse du backend" onBack={onBack} backLabel="Retour" />
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.label}>URL du backend</Text>
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder="https://…"
          placeholderTextColor={colors.textMuted}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          style={styles.input}
        />
        <Text style={styles.hint}>Valeur par défaut (app.json → expo.extra.apiUrl) : {defaultUrl || '(vide)'}</Text>
        {note ? <Text style={note.kind === 'error' ? styles.error : styles.success}>{note.text}</Text> : null}
        <AppButton label="Enregistrer" onPress={() => void save()} disabled={busy} />
        <AppButton label="Revenir à la valeur par défaut" variant="secondary" onPress={() => void reset()} disabled={busy} />
        <AppButton
          label={backend.pending ? 'Test en cours…' : 'Tester la connexion (GET /health)'}
          variant="secondary"
          onPress={() => void backend.check(draft)}
          disabled={busy || backend.pending}
        />
        {backend.result ? (
          <Text style={backend.result.kind === 'ok' ? styles.success : styles.error}>{backend.result.text}</Text>
        ) : null}
        <Text style={styles.footnote}>
          Aucune clé API n'est stockée dans l'app : toutes les requêtes passent par ce backend. Les images ne quittent
          l'appareil qu'après ton action.
        </Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { padding: spacing.lg, gap: spacing.md },
  label: { color: colors.text, fontSize: 14, fontWeight: '600' },
  input: {
    color: colors.text,
    backgroundColor: colors.surface,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    fontSize: 14,
  },
  hint: { color: colors.textMuted, fontSize: 12, lineHeight: 18 },
  error: { color: colors.danger, fontSize: 13 },
  success: { color: colors.success, fontSize: 13 },
  footnote: { color: colors.textMuted, fontSize: 12, lineHeight: 18, marginTop: spacing.lg },
});