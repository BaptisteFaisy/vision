import { useState } from 'react';
import { ActivityIndicator, StyleSheet, TextInput, View } from 'react-native';
import { colors, radius, spacing } from '../theme';
import { AppButton } from './AppButton';

interface Props {
  pending: boolean;
  onSend: (question: string) => void;
}

/** Barre de saisie du chat, ancrée en bas de l'écran Résultat. */
export function ChatInput({ pending, onSend }: Props) {
  const [draft, setDraft] = useState('');
  const canSend = !pending && draft.trim().length > 0;

  const submit = () => {
    if (!canSend) return;
    const text = draft.trim();
    setDraft('');
    onSend(text);
  };

  return (
    <View style={styles.bar}>
      {pending ? <ActivityIndicator color={colors.primary} /> : null}
      <TextInput
        value={draft}
        onChangeText={setDraft}
        placeholder="Pose une question sur la photo…"
        placeholderTextColor={colors.textMuted}
        editable={!pending}
        multiline
        style={styles.input}
        onSubmitEditing={submit}
      />
      <AppButton label="Envoyer" compact onPress={submit} disabled={!canSend} />
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: spacing.sm,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
    paddingBottom: spacing.md,
    borderTopWidth: 1,
    borderTopColor: colors.border,
    backgroundColor: colors.surface,
  },
  input: {
    flex: 1,
    minHeight: 42,
    maxHeight: 110,
    color: colors.text,
    backgroundColor: colors.surfaceAlt,
    borderColor: colors.border,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    fontSize: 14,
    textAlignVertical: 'top',
  },
});