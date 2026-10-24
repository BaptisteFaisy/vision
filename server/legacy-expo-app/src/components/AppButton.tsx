import type { StyleProp, ViewStyle } from 'react-native';
import { Pressable, StyleSheet, Text } from 'react-native';
import { colors, radius, spacing } from '../theme';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

interface Props {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  compact?: boolean;
  style?: StyleProp<ViewStyle>;
}

const VARIANTS: Record<ButtonVariant, { bg: string; fg: string; border: string }> = {
  primary: { bg: colors.primary, fg: '#FFFFFF', border: colors.primary },
  secondary: { bg: colors.surfaceAlt, fg: colors.text, border: colors.border },
  ghost: { bg: 'transparent', fg: colors.textMuted, border: 'transparent' },
  danger: { bg: 'transparent', fg: colors.danger, border: colors.danger },
};

export function AppButton({ label, onPress, variant = 'primary', disabled, compact, style }: Props) {
  const theme = VARIANTS[variant];
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.base,
        { backgroundColor: theme.bg, borderColor: theme.border },
        compact ? styles.compact : null,
        pressed ? styles.pressed : null,
        disabled ? styles.disabled : null,
        style,
      ]}
    >
      <Text style={[styles.label, { color: theme.fg }, compact ? styles.compactLabel : null]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    borderRadius: radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
  },
  compact: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm },
  label: { fontSize: 15, fontWeight: '600' },
  compactLabel: { fontSize: 14 },
  pressed: { opacity: 0.7 },
  disabled: { opacity: 0.4 },
});