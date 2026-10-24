import { useState } from 'react';
import { FlatList, KeyboardAvoidingView, Platform, StyleSheet, Text, View } from 'react-native';
import { ChatBubble } from '../components/ChatBubble';
import { ChatInput } from '../components/ChatInput';
import { ErrorBanner } from '../components/Feedback';
import { ResultHeader } from '../components/ResultHeader';
import { HeaderAction, ScreenHeader } from '../components/ScreenHeader';
import { useAnalysis } from '../hooks/useAnalysis';
import { useChat } from '../hooks/useChat';
import { colors, spacing } from '../theme';
import type { CapturedPhoto } from '../types';

interface Props {
  photo: CapturedPhoto;
  apiUrl: string;
  onNewPhoto: () => void;
  onOpenSettings: () => void;
}

export function ResultScreen({ photo, apiUrl, onNewPhoto, onOpenSettings }: Props) {
  const analysis = useAnalysis(photo, apiUrl);
  const chat = useChat(photo, apiUrl);
  const [masksVisible, setMasksVisible] = useState(true);

  return (
    <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScreenHeader
        title="Résultat"
        onBack={onNewPhoto}
        backLabel="Nouvelle photo"
        right={<HeaderAction label="Réglages" onPress={onOpenSettings} />}
      />
      <FlatList
        data={chat.messages}
        keyExtractor={(item, index) => `chat-${index}-${item.role}`}
        renderItem={({ item }) => <ChatBubble message={item} />}
        ListHeaderComponent={
          <ResultHeader
            photo={photo}
            analysis={analysis.state}
            masksVisible={masksVisible}
            onToggleMasks={() => setMasksVisible((visible) => !visible)}
            onRetryAnalysis={() => void analysis.retry()}
          />
        }
        ListEmptyComponent={<Text style={styles.hint}>Demande ce que tu veux : objets, couleurs, position, texte…</Text>}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      />
      {chat.error ? (
        <View style={styles.errorWrap}>
          <ErrorBanner message={chat.error} onRetry={chat.retry} />
        </View>
      ) : null}
      <ChatInput pending={chat.pending} onSend={(question) => void chat.send(question)} />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { paddingHorizontal: spacing.lg, paddingBottom: spacing.lg },
  hint: { color: colors.textMuted, fontSize: 13, lineHeight: 19, paddingVertical: spacing.sm },
  errorWrap: { paddingHorizontal: spacing.lg, paddingBottom: spacing.sm },
});