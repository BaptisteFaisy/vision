/** Score de segmentation en pourcentage lisible. */
export function formatScore(score: number): string {
  const clamped = Math.min(1, Math.max(0, score));
  return `${Math.round(clamped * 100)} %`;
}

/** Taille d'image lisible pour les libellés d'interface. */
export function formatSize(width: number, height: number): string {
  return `${Math.round(width)} × ${Math.round(height)} px`;
}