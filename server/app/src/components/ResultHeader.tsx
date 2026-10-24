import { StyleSheet, Text, View } from 'react-native';
import type { AnalysisState } from '../hooks/useAnalysis';
import { colors, spacing } from '../theme';
import type { CapturedPhoto, SegmentObject } from '../types';
import { DescriptionCard } from './DescriptionCard';
import { ErrorBanner, LoadingBlock } from './Feedback';
import { ObjectList } from './ObjectList';
import { PhotoStage } from './PhotoStage';

interface Props {
  photo: CapturedPhoto;
  analysis: AnalysisState;
  masksVisible: boolean;
  onToggleMasks: () => void;
  onRetryAnalysis: () => void;
}

function AnalysisSection({ analysis, onRetryAnalysis }: Pick<Props, 'analysis' | 'onRetryAnalysis'>) {
  if (analysis.status === 'loading') return <LoadingBlock label="Segmentation et description en cours…" />;
  if (analysis.status === 'error') return <ErrorBanner message={analysis.message} onRetry={onRetryAnalysis} />;
  return (
    <>
      <ObjectList objects={analysis.data.objects} />
      <DescriptionCard description={analysis.data.description} tags={analysis.data.tags} />
    </>
  );
}

/** En-tête défilant de l'écran Résultat : photo, masques, objets, description, chat. */
export function ResultHeader({ photo, analysis, masksVisible, onToggleMasks, onRetryAnalysis }: Props) {
  const objects: SegmentObject[] = analysis.status === 'ready' ? analysis.data.objects : [];
  return (
    <View style={styles.wrap}>
      <PhotoStage photo={photo} objects={objects} masksVisible={masksVisible} onToggleMasks={onToggleMasks} />
      <AnalysisSection analysis={analysis} onRetryAnalysis={onRetryAnalysis} />
      <Text style={styles.sectionTitle}>Questions</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.lg, paddingBottom: spacing.md },
  sectionTitle: { color: colors.text, fontSize: 15, fontWeight: '700' },
});