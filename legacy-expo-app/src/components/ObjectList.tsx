import { StyleSheet, Text, View } from 'react-native';
import { colors, maskColor, radius, spacing } from '../theme';
import type { SegmentObject } from '../types';
import { formatScore } from '../utils/format';

interface Props {
  objects: SegmentObject[];
}

export function ObjectList({ objects }: Props) {
  if (objects.length === 0) {
    return <Text style={styles.empty}>Aucun objet segmenté sur cette photo.</Text>;
  }
  return (
    <View style={styles.card}>
      <Text style={styles.title}>Objets détectés</Text>
      {objects.map((object, index) => (
        <View key={`${index}-${object.label}`} style={styles.row}>
          <View style={[styles.dot, { backgroundColor: maskColor(index) }]} />
          <Text style={styles.label} numberOfLines={1}>
            {object.label}
          </Text>
          <Text style={styles.score}>{formatScore(object.score)}</Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.lg, gap: spacing.sm },
  title: { color: colors.text, fontSize: 15, fontWeight: '700', marginBottom: spacing.xs },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.xs },
  dot: { width: 10, height: 10, borderRadius: 5 },
  label: { flex: 1, color: colors.text, fontSize: 14 },
  score: { color: colors.textMuted, fontSize: 13, fontVariant: ['tabular-nums'] },
  empty: { color: colors.textMuted, fontSize: 14, paddingVertical: spacing.sm },
});