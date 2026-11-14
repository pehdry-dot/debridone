import { useStore } from '../store/useStore';

/*
 * Feature gates. Every Pro-only capability is named here and checked through
 * `useEntitled` / `isEntitled`, so wiring in real billing later means changing
 * one function: where it says `proDemo`, read the verified subscription state
 * from your backend instead. This prototype has no checkout and takes no money.
 */

export type Feature =
  | 'theme.oled' | 'theme.customAccent' | 'theme.wallpaper' | 'theme.radius' | 'theme.fonts' | 'theme.effects' | 'theme.animation'
  | 'theme.customCss' | 'theme.presets' | 'nav.customize' | 'home.dashboards' | 'meta.rules' | 'meta.customFields'
  | 'shortcuts.custom' | 'layout.presets' | 'sources.presets';

export const FEATURE_LABEL: Record<Feature, string> = {
  'theme.oled': 'OLED black mode',
  'theme.customAccent': 'Any accent colour',
  'theme.wallpaper': 'Custom background image',
  'theme.radius': 'Card radius',
  'theme.fonts': 'Interface fonts',
  'theme.effects': 'Visual effects',
  'theme.animation': 'Animation intensity',
  'theme.customCss': 'Custom CSS themes',
  'theme.presets': 'Theme presets, import & export',
  'nav.customize': 'Sidebar & navigation layout',
  'home.dashboards': 'Multiple personal dashboards',
  'meta.rules': 'Metadata rules',
  'meta.customFields': 'Custom field definitions',
  'shortcuts.custom': 'Custom keyboard shortcuts',
  'layout.presets': 'Layout presets',
  'sources.presets': 'Source organisation presets',
};

/** Accent colours available on the free tier. */
export const FREE_ACCENTS = ['#0A84FF', '#8B5CF6', '#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#EC4899'];

export const isEntitled = (_feature: Feature, state = useStore.getState()) => state.proDemo;
export const useEntitled = (_feature: Feature) => useStore((s) => s.proDemo);
