import type { ReactNode } from 'react';
import { StyleSheet, Text, View, Pressable } from 'react-native';
import { colors, spacing } from '../theme';

interface Props {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  backLabel?: string;
  right?: ReactNode;
}

export function ScreenHeader({ title, subtitle, onBack, backLabel = 'Retour', right }: Props) {
  return (
    <View style={styles.row}>
      <View style={styles.titles}>
        <Text style={styles.title} numberOfLines={1}>{title}</Text>
        {subtitle ? <Text style={styles.subtitle} numberOfLines={1}>{subtitle}</Text> : null}
      </View>
      {onBack ? (
        <Pressable accessibilityRole="button" onPress={onBack} hitSlop={8}>
          <Text style={styles.action}>{backLabel}</Text>
        </Pressable>
      ) : null}
      {right}
    </View>
  );
}

export function HeaderAction({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} hitSlop={8} style={styles.actionBox}>
      <Text style={styles.action}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: spacing.lg, paddingVertical: spacing.md, gap: spacing.md },
  titles: { flex: 1 },
  title: { color: colors.text, fontSize: 18, fontWeight: '700' },
  subtitle: { color: colors.textMuted, fontSize: 12, marginTop: 2 },
  action: { color: colors.primary, fontSize: 14, fontWeight: '600' },
  actionBox: { paddingLeft: spacing.sm },
});