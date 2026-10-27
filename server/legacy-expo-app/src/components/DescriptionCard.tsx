import { StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../theme';

interface Props {
  description: string;
  tags: string[];
}

export function DescriptionCard({ description, tags }: Props) {
  return (
    <View style={styles.card}>
      <Text style={styles.title}>Description</Text>
      <Text style={styles.body}>{description}</Text>
      {tags.length > 0 ? (
        <View style={styles.tags}>
          {tags.map((tag) => (
            <Text key={tag} style={styles.tag}>
              {tag}
            </Text>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { backgroundColor: colors.surface, borderRadius: radius.md, padding: spacing.lg, gap: spacing.sm },
  title: { color: colors.text, fontSize: 15, fontWeight: '700' },
  body: { color: colors.text, fontSize: 14, lineHeight: 21 },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xs },
  tag: {
    color: colors.textMuted,
    fontSize: 12,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    overflow: 'hidden',
  },
});