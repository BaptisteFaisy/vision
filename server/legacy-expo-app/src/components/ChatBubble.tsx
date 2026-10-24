import { StyleSheet, Text, View } from 'react-native';
import { colors, radius, spacing } from '../theme';
import type { ChatMessage } from '../types';

interface Props {
  message: ChatMessage;
}

export function ChatBubble({ message }: Props) {
  const isUser = message.role === 'user';
  return (
    <View style={[styles.bubble, isUser ? styles.user : styles.assistant]}>
      <Text style={styles.author}>{isUser ? 'Toi' : 'SAM Vision'}</Text>
      <Text style={styles.text}>{message.content}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  bubble: { borderRadius: radius.md, padding: spacing.md, gap: spacing.xs, marginBottom: spacing.sm },
  user: { backgroundColor: 'rgba(79,142,247,0.16)', alignSelf: 'flex-end' },
  assistant: { backgroundColor: colors.surfaceAlt, alignSelf: 'flex-start' },
  author: { color: colors.textMuted, fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 },
  text: { color: colors.text, fontSize: 14, lineHeight: 20 },
});