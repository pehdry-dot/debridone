import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { sanitizeCss, scopeCss } from '../lib/cssSanitizer';
import { FREE_ACCENTS } from '../lib/features';
import { blobUrl } from '../lib/idb';
import { hexToTriplet, isHex, luminance } from '../lib/utils';
import { usePlayer } from '../store/player';
import { DEFAULT_SHORTCUTS, DEFAULT_THEME, useStore } from '../store/useStore';
import type { Theme } from '../types';

export const WALLPAPER_KEY = 'theme:wallpaper';

const MODES: Record<Theme['mode'], Record<string, string>> = {
  dark: { '--bg': '11 11 15', '--bg2': '21 21 27', '--card': '30 30 38', '--fg': '245 245 247', '--muted': '161 161 170', '--line': '255 255 255', '--shadow-a': '0.5' },
  oled: { '--bg': '0 0 0', '--bg2': '7 7 9', '--card': '17 17 21', '--fg': '245 245 247', '--muted': '150 150 160', '--line': '255 255 255', '--shadow-a': '0.7' },
  light: { '--bg': '243 243 247', '--bg2': '255 255 255', '--card': '255 255 255', '--fg': '17 17 24', '--muted': '92 92 104', '--line': '20 20 40', '--shadow-a': '0.14' },
};

export const FONTS: Record<Theme['font'], { label: string; stack: string; pro?: boolean }> = {
  system: { label: 'System', stack: `-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif` },
  rounded: { label: 'Rounded', stack: `ui-rounded, 'SF Pro Rounded', 'Hiragino Maru Gothic ProN', Nunito, 'Varela Round', system-ui, sans-serif` },
  humanist: { label: 'Humanist', stack: `'Avenir Next', Avenir, 'Segoe UI', Seravek, 'Gill Sans Nova', Ubuntu, Calibri, sans-serif` },
  serif: { label: 'Serif', stack: `'Iowan Old Style', 'Palatino Linotype', Palatino, 'Book Antiqua', Georgia, serif`, pro: true },
  mono: { label: 'Mono', stack: `ui-monospace, 'SF Mono', 'Cascadia Code', 'JetBrains Mono', Menlo, Consolas, monospace`, pro: true },
};

/** What actually gets rendered: Pro-only choices fall back to defaults when Pro is off. */
export function effectiveTheme(t: Theme, pro: boolean): Theme {
  if (pro) return t;
  return {
    ...DEFAULT_THEME,
    mode: t.mode === 'oled' ? 'dark' : t.mode,
    accent: FREE_ACCENTS.includes(t.accent.toUpperCase()) ? t.accent : DEFAULT_THEME.accent,
    density: t.density,
    font: FONTS[t.font]?.pro ? 'system' : t.font,
    glass: true, glow: true,
    cardSize: t.cardSize, headerStyle: t.headerStyle, shadows: t.shadows, sidebarStyle: t.sidebarStyle,
  };
}

/** Writes the active theme to CSS custom properties on <html>. Mounted once. */
export function ThemeApplier() {
  const theme = useStore((s) => s.theme);
  const pro = useStore((s) => s.proDemo);

  useEffect(() => {
    const t = effectiveTheme(theme, pro);
    const root = document.documentElement;
    const accent = isHex(t.accent) ? t.accent : DEFAULT_THEME.accent;
    const vars: Record<string, string> = {
      ...MODES[t.mode],
      '--accent': hexToTriplet(accent),
      '--accent-fg': luminance(accent) > 0.55 ? '17 17 24' : '255 255 255',
      '--radius': `${t.radius}px`,
      '--font': FONTS[t.font].stack,
      '--anim': t.animation === 'off' ? '0' : t.animation === 'full' ? '1.5' : '1',
      '--bg-dim': String(t.wallpaperDim / 100),
      '--bg-blur': `${t.wallpaperBlur}px`,
    };
    for (const [k, v] of Object.entries(vars)) root.style.setProperty(k, v);
    root.dataset.mode = t.mode;
    root.dataset.density = t.density;
    root.dataset.glass = t.glass ? '1' : '0';
    root.dataset.glow = t.glow ? '1' : '0';
    root.dataset.grain = t.grain ? '1' : '0';
    root.dataset.anim = t.animation;
    root.dataset.card = t.cardSize;
    root.dataset.header = t.headerStyle;
    root.dataset.shadow = t.shadows;
    root.dataset.sidebar = t.sidebarStyle;
    document.querySelector('meta[name=theme-color]')?.setAttribute('content', `rgb(${MODES[t.mode]['--bg'].replace(/ /g, ',')})`);

    let cancelled = false;
    if (t.wallpaper) {
      blobUrl(WALLPAPER_KEY).then((url) => {
        if (cancelled) return;
        if (url) { root.style.setProperty('--wallpaper', `url("${url}")`); root.dataset.wallpaper = '1'; }
        else root.dataset.wallpaper = '0';
      });
    } else root.dataset.wallpaper = '0';

    let style = document.getElementById('d1-custom-css') as HTMLStyleElement | null;
    if (!style) { style = document.createElement('style'); style.id = 'd1-custom-css'; document.head.appendChild(style); }
    style.textContent = t.customCssEnabled && t.customCss ? scopeCss(sanitizeCss(t.customCss).css) : '';
    return () => { cancelled = true; };
  }, [theme, pro]);

  return null;
}

/* ------------------------- keyboard shortcuts ------------------------- */

const CODE_KEYS: Record<string, string> = { Comma: ',', Period: '.', Slash: '/', Semicolon: ';', Quote: "'", BracketLeft: '[', BracketRight: ']', Backslash: '\\', Minus: '-', Equal: '=', Backquote: '`', Space: 'Space' };

/** Normalise a keyboard event to a combo string such as "Alt+H" or "/". Uses `code` so Option-key glyphs on macOS don't matter. */
export function comboFromEvent(e: KeyboardEvent | React.KeyboardEvent): string | null {
  const code = e.code;
  let key: string;
  if (code.startsWith('Key')) key = code.slice(3);
  else if (code.startsWith('Digit')) key = code.slice(5);
  else if (CODE_KEYS[code]) key = CODE_KEYS[code];
  else if (/^(Arrow(Up|Down|Left|Right)|F\d{1,2}|Enter|Home|End|PageUp|PageDown)$/.test(code)) key = code;
  else return null;
  return [e.ctrlKey && 'Ctrl', e.metaKey && 'Cmd', e.altKey && 'Alt', e.shiftKey && 'Shift', key].filter(Boolean).join('+');
}

export function Shortcuts() {
  const navigate = useNavigate();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const combo = comboFromEvent(e);
      if (!combo) return;
      const target = e.target as HTMLElement | null;
      const typing = !!target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
      const hasModifier = e.altKey || e.ctrlKey || e.metaKey;
      if (typing && !hasModifier) return;
      const s = useStore.getState();
      const map = s.proDemo ? s.shortcuts : DEFAULT_SHORTCUTS;
      const action = Object.keys(map).find((a) => map[a] === combo);
      if (!action) return;
      const player = usePlayer.getState();
      const run: Record<string, () => void> = {
        search: () => window.dispatchEvent(new CustomEvent('d1:focus-search')),
        home: () => navigate('/'), library: () => navigate('/library'), downloads: () => navigate('/downloads'), settings: () => navigate('/settings'),
        playPause: () => player.toggle(), nextTrack: () => player.next(), prevTrack: () => player.prev(),
        sidebar: () => s.set('nav', (n) => ({ ...n, collapsed: !n.collapsed })),
      };
      if (run[action]) { e.preventDefault(); run[action](); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [navigate]);
  return null;
}
