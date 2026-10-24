export const colors = {
  bg: '#0B0D12',
  surface: '#151A22',
  surfaceAlt: '#1E2530',
  border: '#2A3341',
  text: '#F2F5F9',
  textMuted: '#93A0B4',
  primary: '#4F8EF7',
  danger: '#F2555A',
  success: '#3ED598',
} as const;

/** Couleurs tournantes des masques superposés à la photo. */
export const MASK_COLORS = [
  '#4F8EF7',
  '#F2555A',
  '#3ED598',
  '#F5A623',
  '#A66BF7',
  '#2CC5D6',
  '#F76BAE',
  '#B8D34A',
] as const;

export const maskColor = (index: number): string =>
  MASK_COLORS[((index % MASK_COLORS.length) + MASK_COLORS.length) % MASK_COLORS.length];

export const MASK_OPACITY = 0.45;

export const spacing = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 } as const;

export const radius = { sm: 6, md: 10, lg: 16 } as const;