import { useMemo } from 'react';
import { Image, StyleSheet, Switch, Text, View } from 'react-native';
import { MASK_OPACITY, colors, maskColor, radius, spacing } from '../theme';
import type { CapturedPhoto, SegmentObject } from '../types';
import { colorizeMask } from '../utils/mask';

interface Overlay {
  key: string;
  uri: string;
}

function buildOverlays(objects: SegmentObject[]): Overlay[] {
  return objects.flatMap((object, index) => {
    const uri = colorizeMask({ maskB64: object.mask_b64, color: maskColor(index), opacity: MASK_OPACITY });
    return uri ? [{ key: `mask-${index}-${object.label}`, uri }] : [];
  });
}

interface Props {
  photo: CapturedPhoto;
  objects: SegmentObject[];
  masksVisible: boolean;
  onToggleMasks: () => void;
}

export function PhotoStage({ photo, objects, masksVisible, onToggleMasks }: Props) {
  const overlays = useMemo(() => buildOverlays(objects), [objects]);
  const ratio = photo.height > 0 ? photo.width / photo.height : 1;
  return (
    <View style={styles.wrap}>
      <View style={[styles.stage, { aspectRatio: ratio }]}>
        <Image source={{ uri: photo.uri }} style={StyleSheet.absoluteFill} resizeMode="cover" />
        {masksVisible
          ? overlays.map((overlay) => (
              <Image key={overlay.key} source={{ uri: overlay.uri }} style={StyleSheet.absoluteFill} resizeMode="stretch" />
            ))
          : null}
      </View>
      <View style={styles.toolbar}>
        <Text style={styles.caption}>{objects.length} objet(s) détecté(s)</Text>
        <View style={styles.toggle}>
          <Text style={styles.toggleLabel}>Masques</Text>
          <Switch
            value={masksVisible}
            onValueChange={onToggleMasks}
            trackColor={{ false: colors.surfaceAlt, true: colors.primary }}
            thumbColor={colors.text}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.sm },
  stage: {
    width: '100%',
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  toolbar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.xs },
  caption: { color: colors.textMuted, fontSize: 13 },
  toggle: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  toggleLabel: { color: colors.text, fontSize: 13, fontWeight: '600' },
});