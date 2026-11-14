import { Reorder } from 'framer-motion';
import { ArrowDown, ArrowUp, Check, Crown, Download, Eye, EyeOff, GripVertical, ImagePlus, Keyboard, Lock, Pin, Plus, RotateCcw, Save, Sparkles, Trash2, Upload, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { NAV } from '../components/Layout';
import { comboFromEvent, effectiveTheme, FONTS, WALLPAPER_KEY } from '../components/ThemeApplier';
import { Badge, Button, Callout, Card, Chip, PageHeader, ProGate, ProgressBar, Range, Segmented, Select, SettingRow, Switch } from '../components/ui';
import { sanitizeCss } from '../lib/cssSanitizer';
import { FEATURE_LABEL, FREE_ACCENTS, type Feature } from '../lib/features';
import { useTab } from '../lib/hooks';
import { useAuth } from '../store/auth';
import { useT } from '../lib/i18n';
import { delBlobs, putBlob } from '../lib/idb';
import { cn, imageToBlob, isHex, moveItem, pickFiles, pickJson, saveJson, uid } from '../lib/utils';
import { confirmDialog, toast } from '../store/ui';
import { DEFAULT_SHORTCUTS, DEFAULT_THEME, emptyLayout, NAV_IDS, SHORTCUT_LABELS, useStore, WIDGETS, type NavId } from '../store/useStore';
import type { MediaType, MetaRule, Theme, ThemePreset } from '../types';
import { MEDIA_TYPES, TYPE_PLURAL } from '../types';

type Tab = 'appearance' | 'homepage' | 'advanced' | 'plan';

const BUILTIN_PRESETS: ThemePreset[] = [
  { id: 'midnight', name: 'Default', builtin: true, theme: { ...DEFAULT_THEME } },
  { id: 'apple', name: 'Apple Dark', builtin: true, theme: { mode: 'dark', accent: '#0A84FF', radius: 14, font: 'system', glass: true, glow: false, grain: false, cardSize: 'medium', headerStyle: 'large', shadows: 'soft', sidebarStyle: 'translucent' } },
  { id: 'apple-light', name: 'Apple Light', builtin: true, theme: { mode: 'light', accent: '#007AFF', radius: 14, font: 'system', glass: true, glow: false, grain: false, cardSize: 'medium', headerStyle: 'large', shadows: 'soft', sidebarStyle: 'translucent' } },
  { id: 'purple', name: 'Purple', builtin: true, theme: { mode: 'dark', accent: '#8B5CF6' } },
  { id: 'ocean', name: 'Ocean', builtin: true, theme: { mode: 'dark', accent: '#0EA5E9', radius: 16, font: 'system', glass: true, glow: true, grain: false } },
  { id: 'ember', name: 'Ember', builtin: true, theme: { mode: 'dark', accent: '#F97316', radius: 10, font: 'humanist', glass: true, glow: true, grain: false } },
  { id: 'forest', name: 'Forest', builtin: true, theme: { mode: 'dark', accent: '#10B981', radius: 18, font: 'humanist', glass: true, glow: true, grain: false } },
  { id: 'rose', name: 'Rosé', builtin: true, theme: { mode: 'dark', accent: '#EC4899', radius: 24, font: 'rounded', glass: true, glow: true, grain: false } },
  { id: 'paper', name: 'Paper', builtin: true, theme: { mode: 'light', accent: '#6366F1', radius: 12, font: 'serif', glass: false, glow: false, grain: false } },
  { id: 'neon', name: 'OLED Neon', builtin: true, theme: { mode: 'oled', accent: '#22D3EE', radius: 8, font: 'system', glass: true, glow: true, grain: true } },
  { id: 'terminal', name: 'Terminal', builtin: true, theme: { mode: 'oled', accent: '#A3E635', radius: 2, font: 'mono', glass: false, glow: false, grain: false } },
];
const LAYOUT_PRESETS: { name: string; blurb: string; theme: Partial<Theme>; collapsed: boolean }[] = [
  { name: 'Balanced', blurb: 'Comfortable spacing, full sidebar', theme: { density: 'comfortable', radius: 14 }, collapsed: false },
  { name: 'Cinema', blurb: 'Roomy, rounded, icon-only sidebar', theme: { density: 'spacious', radius: 20 }, collapsed: true },
  { name: 'Compact', blurb: 'More on screen, tighter corners', theme: { density: 'compact', radius: 8 }, collapsed: false },
  { name: 'Focus', blurb: 'Icon sidebar, effects and motion off', theme: { density: 'comfortable', radius: 12, glow: false, grain: false, animation: 'off' }, collapsed: true },
];
const SAMPLE_CSS = `/* Scoped to the app. url() and @import are removed. */
.app-sidebar { border-right: 2px solid rgb(var(--accent)); }
h1 { letter-spacing: 0.01em; text-transform: uppercase; }
.surface { box-shadow: 0 0 0 1px rgb(var(--accent) / 0.25); }`;

/** Validate an imported theme file field by field; unknown or malformed values are dropped. */
function coerceTheme(raw: unknown): Partial<Theme> | null {
  const t = (raw && typeof raw === 'object' ? ((raw as { theme?: unknown }).theme ?? raw) : null) as Record<string, unknown> | null;
  if (!t || typeof t !== 'object') return null;
  const out: Partial<Theme> = {};
  if (['dark', 'light', 'oled'].includes(t.mode as string)) out.mode = t.mode as Theme['mode'];
  if (typeof t.accent === 'string' && isHex(t.accent)) out.accent = t.accent;
  if (typeof t.radius === 'number' && t.radius >= 0 && t.radius <= 28) out.radius = Math.round(t.radius);
  if (['compact', 'comfortable', 'spacious'].includes(t.density as string)) out.density = t.density as Theme['density'];
  if (typeof t.font === 'string' && t.font in FONTS) out.font = t.font as Theme['font'];
  (['glass', 'glow', 'grain', 'customCssEnabled'] as const).forEach((k) => { if (typeof t[k] === 'boolean') out[k] = t[k] as boolean; });
  if (['off', 'subtle', 'full'].includes(t.animation as string)) out.animation = t.animation as Theme['animation'];
  if (typeof t.wallpaperDim === 'number') out.wallpaperDim = Math.min(95, Math.max(0, t.wallpaperDim));
  if (typeof t.wallpaperBlur === 'number') out.wallpaperBlur = Math.min(30, Math.max(0, t.wallpaperBlur));
  if (['small', 'medium', 'large'].includes(t.cardSize as string)) out.cardSize = t.cardSize as Theme['cardSize'];
  if (['large', 'compact'].includes(t.headerStyle as string)) out.headerStyle = t.headerStyle as Theme['headerStyle'];
  if (['none', 'soft', 'deep'].includes(t.shadows as string)) out.shadows = t.shadows as Theme['shadows'];
  if (['translucent', 'solid'].includes(t.sidebarStyle as string)) out.sidebarStyle = t.sidebarStyle as Theme['sidebarStyle'];
  if (typeof t.customCss === 'string') out.customCss = t.customCss.slice(0, 20_000);
  return Object.keys(out).length ? out : null;
}

function Gate({ feature, children }: { feature: Feature; children: React.ReactNode }) { return <ProGate feature={feature} compact>{children}</ProGate>; }

function Preview() {
  return (
    <Card title="Live preview" description="These samples use the same design tokens as the rest of the app, which is updating behind this panel too." className="xl:sticky xl:top-4">
      <div className="space-y-4">
        <div className="relative overflow-hidden rounded-card bg-gradient-to-br from-accent to-accent/40 p-4 text-accent-fg">
          <p className="text-[0.6875rem] font-semibold uppercase tracking-widest opacity-80">Featured</p>
          <p className="h-display mt-1 text-xl">Your theme, live</p>
          <p className="mt-1 text-xs opacity-85">Accent, radius and type react instantly.</p>
        </div>
        <div className="grid grid-cols-3 gap-2">
          {[0, 1, 2].map((i) => <div key={i} className="aspect-[2/3] rounded-card border border-line/10 bg-gradient-to-b from-line/[0.14] to-line/[0.04]" />)}
        </div>
        <div className="surface p-3">
          <p className="text-sm font-semibold">Card title</p>
          <p className="mt-0.5 text-xs text-muted">Secondary text on a card surface.</p>
          <ProgressBar value={62} className="mt-3" />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="primary" size="sm">Primary</Button><Button size="sm">Secondary</Button><Chip active>Chip</Chip><Chip>Chip</Chip>
        </div>
        <input className="field" placeholder="Text field" aria-label="Sample text field" />
      </div>
    </Card>
  );
}

function Appearance() {
  const t = useT();
  const theme = useStore((s) => s.theme);
  const pro = useStore((s) => s.proDemo);
  const nav = useStore((s) => s.nav);
  const userPresets = useStore((s) => s.userPresets);
  const setTheme = useStore((s) => s.setTheme);
  const set = useStore((s) => s.set);
  const shown = effectiveTheme(theme, pro);
  const [hex, setHex] = useState(theme.accent);
  const [presetName, setPresetName] = useState('');
  useEffect(() => setHex(theme.accent), [theme.accent]);

  const apply = (p: ThemePreset) => { setTheme(p.theme); toast(`Applied “${p.name}”`, 'success'); };
  const upload = async () => {
    const [file] = await pickFiles('image/*');
    if (!file) return;
    try { await putBlob(WALLPAPER_KEY, await imageToBlob(file)); setTheme({ wallpaper: true }); toast('Background image set', 'success'); }
    catch (e) { toast((e as Error).message || 'That image could not be stored.', 'error'); }
  };
  const order = [...nav.order.filter((id) => NAV_IDS.includes(id)), ...NAV_IDS.filter((id) => !nav.order.includes(id))];
  const setOrder = (next: NavId[]) => set('nav', { ...nav, order: next });
  const toggleHidden = (id: NavId) => set('nav', { ...nav, hidden: nav.hidden.includes(id) ? nav.hidden.filter((x) => x !== id) : [...nav.hidden, id] });

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="min-w-0 space-y-6">
        <Card title="Theme presets" description="One click to restyle everything. Save your own below.">
          <Gate feature="theme.presets">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              {[...BUILTIN_PRESETS, ...userPresets].map((p) => {
                const bg = p.theme.mode === 'light' ? '#F3F3F7' : p.theme.mode === 'oled' ? '#000' : '#0B0B0F';
                return (
                  <div key={p.id} className="group relative">
                    <button type="button" onClick={() => apply(p)} className="block w-full overflow-hidden rounded-btn border border-line/10 text-left transition-colors hover:border-accent">
                      <span className="flex h-14 items-end gap-1 p-2" style={{ background: bg }}>
                        <span className="h-8 w-8" style={{ background: p.theme.accent ?? theme.accent, borderRadius: p.theme.radius ?? 14 }} />
                        <span className="h-5 flex-1 opacity-20" style={{ background: p.theme.mode === 'light' ? '#000' : '#fff', borderRadius: (p.theme.radius ?? 14) * 0.6 }} />
                      </span>
                      <span className="block truncate px-2 py-1.5 text-xs font-medium">{p.name}</span>
                    </button>
                    {!p.builtin && <button type="button" aria-label={`Delete preset ${p.name}`} onClick={() => set('userPresets', userPresets.filter((x) => x.id !== p.id))} className="absolute right-1 top-1 hidden h-6 w-6 items-center justify-center rounded-full bg-black/60 text-white group-hover:flex"><X className="h-3 w-3" /></button>}
                  </div>
                );
              })}
            </div>
            <form className="mt-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (!presetName.trim()) return; const { wallpaper: _w, ...rest } = theme; void _w; set('userPresets', [...userPresets, { id: uid('preset-'), name: presetName.trim(), theme: rest }]); setPresetName(''); toast('Preset saved', 'success'); }}>
              <input className="field max-w-xs" placeholder="Name the current look" value={presetName} onChange={(e) => setPresetName(e.target.value)} maxLength={30} aria-label="Preset name" />
              <Button type="submit" icon={Save} disabled={!presetName.trim()}>Save as preset</Button>
            </form>
          </Gate>
        </Card>

        <Card title="Colour">
          <SettingRow title="Mode" description="Dark, light, or true black for OLED screens.">
            <Segmented size="sm" value={shown.mode} onChange={(v) => { if (v === 'oled' && !pro) { toast('OLED black is a Pro feature. Turn on Demo Pro Mode above.', 'info'); return; } setTheme({ mode: v }); }} options={[{ value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }, { value: 'oled', label: 'OLED black' }]} />
          </SettingRow>
          <SettingRow title="Accent colour" description="Buttons, highlights, progress and focus rings." className="border-t border-line/[0.06]">
            {FREE_ACCENTS.map((c) => <button key={c} type="button" aria-label={`Accent ${c}`} aria-pressed={shown.accent.toUpperCase() === c} onClick={() => setTheme({ accent: c })} className={cn('h-7 w-7 rounded-full border-2 transition-transform hover:scale-110', shown.accent.toUpperCase() === c ? 'border-fg' : 'border-transparent')} style={{ background: c }} />)}
          </SettingRow>
          <Gate feature="theme.customAccent">
            <SettingRow title="Custom accent" description="Any colour. Text on accent surfaces flips between light and dark automatically for contrast.">
              <input type="color" value={isHex(theme.accent) ? theme.accent : DEFAULT_THEME.accent} onChange={(e) => setTheme({ accent: e.target.value.toUpperCase() })} aria-label="Pick accent colour" className="h-9 w-12 cursor-pointer rounded-btn border border-line/10 bg-transparent" />
              <input className="field h-9 w-28 py-0 font-mono text-xs uppercase" value={hex} maxLength={7} onChange={(e) => { const v = e.target.value.startsWith('#') ? e.target.value : `#${e.target.value}`; setHex(v); if (isHex(v)) setTheme({ accent: v.toUpperCase() }); }} aria-label="Accent hex value" />
            </SettingRow>
          </Gate>
        </Card>

        <Card title="Background image" description="A wallpaper behind the whole app. Stored in this browser only.">
          <Gate feature="theme.wallpaper">
            <div className="flex flex-wrap items-center gap-2">
              <Button icon={ImagePlus} onClick={upload}>{theme.wallpaper ? 'Replace image' : 'Upload image'}</Button>
              {theme.wallpaper && <Button variant="ghost" icon={Trash2} onClick={async () => { setTheme({ wallpaper: false }); await delBlobs(WALLPAPER_KEY); toast('Background image removed'); }}>Remove</Button>}
              <span className="text-xs text-muted">{theme.wallpaper ? 'Active' : 'None set'}</span>
            </div>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <label className="block"><span className="mb-1.5 flex justify-between text-xs font-medium text-muted"><span>Dim</span><span>{theme.wallpaperDim}%</span></span><Range label="Wallpaper dim" className="w-full" min={0} max={95} value={theme.wallpaperDim} disabled={!theme.wallpaper} onChange={(v) => setTheme({ wallpaperDim: v })} /></label>
              <label className="block"><span className="mb-1.5 flex justify-between text-xs font-medium text-muted"><span>Panel blur</span><span>{theme.wallpaperBlur}px</span></span><Range label="Panel blur" className="w-full" min={0} max={30} value={theme.wallpaperBlur} disabled={!theme.wallpaper} onChange={(v) => setTheme({ wallpaperBlur: v })} /></label>
            </div>
          </Gate>
        </Card>

        <Card title="Shape, type & density">
          <Gate feature="theme.radius">
            <SettingRow title="Card radius" description={`${theme.radius}px — from sharp to pill-soft.`}><Range label="Card radius" className="w-48" min={0} max={28} value={theme.radius} onChange={(v) => setTheme({ radius: v })} /></SettingRow>
          </Gate>
          <SettingRow title="Interface density" className="border-t border-line/[0.06]"><Segmented size="sm" value={shown.density} onChange={(v) => setTheme({ density: v })} options={[{ value: 'compact', label: 'Compact' }, { value: 'comfortable', label: 'Comfortable' }, { value: 'spacious', label: 'Spacious' }]} /></SettingRow>
          <SettingRow title="Card size" description="Width of posters and covers on shelves." className="border-t border-line/[0.06]"><Segmented size="sm" value={shown.cardSize} onChange={(v) => setTheme({ cardSize: v })} options={[{ value: 'small', label: 'Small' }, { value: 'medium', label: 'Medium' }, { value: 'large', label: 'Large' }]} /></SettingRow>
          <SettingRow title="Page titles" description="Large titles, like Apple’s own apps, or compact ones." className="border-t border-line/[0.06]"><Segmented size="sm" value={shown.headerStyle} onChange={(v) => setTheme({ headerStyle: v })} options={[{ value: 'large', label: 'Large' }, { value: 'compact', label: 'Compact' }]} /></SettingRow>
          <SettingRow title="Shadows" description="How much depth cards and panels get." className="border-t border-line/[0.06]"><Segmented size="sm" value={shown.shadows} onChange={(v) => setTheme({ shadows: v })} options={[{ value: 'none', label: 'None' }, { value: 'soft', label: 'Soft' }, { value: 'deep', label: 'Deep' }]} /></SettingRow>
          <SettingRow title="Sidebar" description="Translucent lets the page show through; solid is opaque." className="border-t border-line/[0.06]"><Segmented size="sm" value={shown.sidebarStyle} onChange={(v) => setTheme({ sidebarStyle: v })} options={[{ value: 'translucent', label: 'Translucent' }, { value: 'solid', label: 'Solid' }]} /></SettingRow>
          <SettingRow title="Font" description="System font stacks only — nothing is downloaded." className="border-t border-line/[0.06]">
            {(Object.keys(FONTS) as (keyof typeof FONTS)[]).map((f) => {
              const locked = !!FONTS[f].pro && !pro;
              return <button key={f} type="button" aria-pressed={shown.font === f} onClick={() => (locked ? toast(`${FONTS[f].label} is a Pro font. Turn on Demo Pro Mode above.`, 'info') : setTheme({ font: f }))} className={cn('flex h-9 items-center gap-1.5 rounded-btn border px-3 text-[0.8125rem]', shown.font === f ? 'border-accent bg-accent/10' : 'border-line/10 hover:bg-line/[0.06]', locked && 'opacity-60')} style={{ fontFamily: FONTS[f].stack }}>{FONTS[f].label}{locked && <Lock className="h-3 w-3" />}</button>;
            })}
          </SettingRow>
        </Card>

        <Card title="Effects & motion">
          <Gate feature="theme.effects">
            <div className="divide-y divide-line/[0.06]">
              <SettingRow title="Glass panels" description="Translucent, blurred sidebar, top bar and player."><Switch label="Glass panels" checked={theme.glass} onChange={(v) => setTheme({ glass: v })} /></SettingRow>
              <SettingRow title="Ambient glow" description="A soft wash of your accent colour behind page headers."><Switch label="Ambient glow" checked={theme.glow} onChange={(v) => setTheme({ glow: v })} /></SettingRow>
              <SettingRow title="Film grain" description="A faint texture over the whole interface."><Switch label="Film grain" checked={theme.grain} onChange={(v) => setTheme({ grain: v })} /></SettingRow>
            </div>
          </Gate>
          <Gate feature="theme.animation">
            <SettingRow title="Animation intensity" description="“Off” also disables page and dialog transitions." className="border-t border-line/[0.06]"><Segmented size="sm" value={theme.animation} onChange={(v) => setTheme({ animation: v })} options={[{ value: 'off', label: 'Off' }, { value: 'subtle', label: 'Subtle' }, { value: 'full', label: 'Full' }]} /></SettingRow>
          </Gate>
        </Card>

        <Card title="Sidebar & navigation" description="Drag to reorder, hide what you don’t use. Settings and Pro always stay visible." action={<Button size="sm" variant="ghost" icon={RotateCcw} onClick={() => set('nav', { order: [...NAV_IDS], hidden: [], collapsed: false })}>Reset</Button>}>
          <Gate feature="nav.customize">
            <SettingRow title="Collapse sidebar to icons"><Switch label="Collapse sidebar" checked={nav.collapsed} onChange={(v) => set('nav', { ...nav, collapsed: v })} /></SettingRow>
            <Reorder.Group axis="y" values={order} onReorder={setOrder} className="mt-2 grid gap-1.5">
              {order.map((id, i) => {
                const Icon = NAV[id].icon, hidden = nav.hidden.includes(id), fixed = id === 'settings' || id === 'pro';
                return (
                  <Reorder.Item key={id} value={id} className="flex cursor-grab items-center gap-2.5 rounded-btn border border-line/[0.08] bg-card px-3 py-1.5 active:cursor-grabbing">
                    <GripVertical className="h-4 w-4 shrink-0 text-muted" /><Icon className="h-4 w-4 shrink-0 text-muted" />
                    <span className={cn('min-w-0 flex-1 truncate text-[0.8125rem] font-medium', hidden && 'text-muted line-through')}>{t(NAV[id].key)}</span>
                    <button type="button" aria-label={`Move ${t(NAV[id].key)} up`} disabled={i === 0} onClick={() => setOrder(moveItem(order, i, i - 1))} className="rounded p-1 text-muted hover:bg-line/10 disabled:opacity-30"><ArrowUp className="h-3.5 w-3.5" /></button>
                    <button type="button" aria-label={`Move ${t(NAV[id].key)} down`} disabled={i === order.length - 1} onClick={() => setOrder(moveItem(order, i, i + 1))} className="rounded p-1 text-muted hover:bg-line/10 disabled:opacity-30"><ArrowDown className="h-3.5 w-3.5" /></button>
                    <button type="button" aria-label={`${hidden ? 'Show' : 'Hide'} ${t(NAV[id].key)}`} aria-pressed={!hidden} disabled={fixed} onClick={() => toggleHidden(id)} className="rounded p-1 text-muted hover:bg-line/10 hover:text-fg disabled:opacity-30">{hidden ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}</button>
                  </Reorder.Item>
                );
              })}
            </Reorder.Group>
          </Gate>
        </Card>

        <Card title="Layout presets" description="Bundles of density, corner radius and sidebar state.">
          <Gate feature="layout.presets">
            <div className="grid gap-2 sm:grid-cols-2">
              {LAYOUT_PRESETS.map((p) => (
                <button key={p.name} type="button" onClick={() => { setTheme(p.theme); set('nav', { ...nav, collapsed: p.collapsed }); toast(`“${p.name}” layout applied`, 'success'); }} className="rounded-btn border border-line/10 p-3 text-left transition-colors hover:border-accent hover:bg-line/[0.04]">
                  <p className="text-[0.8125rem] font-semibold">{p.name}</p><p className="mt-0.5 text-xs text-muted">{p.blurb}</p>
                </button>
              ))}
            </div>
          </Gate>
        </Card>

        <div className="flex justify-end"><Button variant="ghost" icon={RotateCcw} onClick={async () => { if (await confirmDialog({ title: 'Reset theme?', body: 'Returns colours, shape, fonts, effects and custom CSS to the default midnight purple. Your saved presets are kept.', confirmLabel: 'Reset theme' })) { setTheme({ ...DEFAULT_THEME }); toast('Theme reset to default', 'success'); } }}>Reset theme to default</Button></div>
      </div>
      <Preview />
    </div>
  );
}

function Homepage() {
  const t = useT();
  const navigate = useNavigate();
  const layouts = useStore((s) => s.layouts);
  const active = useStore((s) => s.activeLayout);
  const collections = useStore((s) => s.collections);
  const startPage = useStore((s) => s.settings.startPage);
  const set = useStore((s) => s.set);
  const updateLayout = useStore((s) => s.updateLayout);
  const [name, setName] = useState('');
  const current = layouts.find((l) => l.id === active) ?? layouts[0];
  const label = (id: string) => WIDGETS.find((w) => w.id === id)?.label ?? id;
  return (
    <div className="max-w-4xl space-y-6">
      <Card title="Personal dashboards" description="Keep several home layouts — a cinematic one for the evening, a quiet one for work — and switch from the Home page.">
        <Gate feature="home.dashboards">
          <ul className="space-y-1.5">
            {layouts.map((l) => (
              <li key={l.id} className={cn('flex items-center gap-2 rounded-btn border px-3 py-2', l.id === current.id ? 'border-accent bg-accent/[0.06]' : 'border-line/[0.08]')}>
                <button type="button" aria-label={`Use ${l.name}`} aria-pressed={l.id === current.id} onClick={() => set('activeLayout', l.id)} className={cn('flex h-5 w-5 shrink-0 items-center justify-center rounded-full border', l.id === current.id ? 'border-accent bg-accent text-accent-fg' : 'border-line/25')}>{l.id === current.id && <Check className="h-3 w-3" />}</button>
                <input className="min-w-0 flex-1 bg-transparent text-[0.8125rem] font-medium outline-none focus:underline" value={l.name} maxLength={30} onChange={(e) => updateLayout(l.id, { name: e.target.value })} aria-label="Dashboard name" />
                <span className="hidden text-xs text-muted sm:inline">{l.widgets.filter((w) => w.visible).length} sections · {l.types.length ? l.types.map((x) => TYPE_PLURAL[x]).join(', ') : 'all media'}</span>
                <Button size="sm" variant="ghost" icon={Trash2} aria-label={`Delete ${l.name}`} disabled={layouts.length === 1} onClick={() => { const next = layouts.filter((x) => x.id !== l.id); set('layouts', next); if (active === l.id) set('activeLayout', next[0].id); }} />
              </li>
            ))}
          </ul>
          <form className="mt-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (!name.trim()) return; const l = emptyLayout(name.trim()); set('layouts', [...layouts, l]); set('activeLayout', l.id); setName(''); toast(`Dashboard “${l.name}” created and selected`, 'success'); }}>
            <input className="field max-w-xs" placeholder="New dashboard name" value={name} onChange={(e) => setName(e.target.value)} maxLength={30} aria-label="New dashboard name" />
            <Button type="submit" icon={Plus} disabled={!name.trim()}>Create dashboard</Button>
          </form>
        </Gate>
      </Card>

      <Card title={`Sections on “${current.name}”`} description="Show or hide here; drag to reorder from Home → Customize." action={<Button size="sm" onClick={() => navigate('/')}>Open Home</Button>}>
        <div className="grid gap-x-8 sm:grid-cols-2">
          {current.widgets.map((w) => <SettingRow key={w.id} title={label(w.id)} className="!py-2"><Switch label={`Show ${label(w.id)}`} checked={w.visible} onChange={(v) => updateLayout(current.id, { widgets: current.widgets.map((x) => (x.id === w.id ? { ...x, visible: v } : x)) })} /></SettingRow>)}
        </div>
        <p className="label mb-2 mt-4">Preferred entertainment categories</p>
        <div className="flex flex-wrap gap-2">
          <Chip active={!current.types.length} onClick={() => updateLayout(current.id, { types: [] })}>Everything</Chip>
          {MEDIA_TYPES.map((x) => <Chip key={x} active={current.types.includes(x)} onClick={() => updateLayout(current.id, { types: current.types.includes(x) ? current.types.filter((y) => y !== x) : [...current.types, x] })}>{TYPE_PLURAL[x]}</Chip>)}
        </div>
      </Card>

      <Card title="Default landing page">
        <SettingRow title="Open DebridOne on" description="Applied the next time the app starts.">
          <Select label="Default landing page" value={startPage} onChange={(v) => useStore.getState().setSetting('startPage', v)} options={NAV_IDS.filter((id) => id !== 'pro' && id !== 'settings').map((id) => ({ value: NAV[id].to, label: t(NAV[id].key) }))} />
        </SettingRow>
      </Card>

      <Card title="Pin favorite collections" description="Pinned collections appear in the sidebar and in the “Pinned Collections” row on Home.">
        {collections.length ? <div className="divide-y divide-line/[0.06]">{collections.map((c) => <SettingRow key={c.id} title={c.name} description={`${c.itemIds.length} items`} className="!py-2.5"><Pin className={cn('h-4 w-4', c.pinned ? 'text-accent' : 'text-muted/40')} /><Switch label={`Pin ${c.name}`} checked={c.pinned} onChange={(v) => useStore.getState().updateCollection(c.id, { pinned: v })} /></SettingRow>)}</div> : <p className="text-[0.8125rem] text-muted">Create a collection in My Library first.</p>}
      </Card>
    </div>
  );
}

function ShortcutEditor() {
  const shortcuts = useStore((s) => s.shortcuts);
  const set = useStore((s) => s.set);
  const [recording, setRecording] = useState<string | null>(null);
  useEffect(() => {
    if (!recording) return;
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault(); e.stopPropagation();
      if (e.key === 'Escape') { setRecording(null); return; }
      const combo = comboFromEvent(e);
      if (!combo) return; // modifier on its own
      const clash = Object.keys(shortcuts).find((a) => a !== recording && shortcuts[a] === combo);
      if (clash) { toast(`${combo} is already used for “${SHORTCUT_LABELS[clash]}”.`, 'warn'); return; }
      set('shortcuts', { ...shortcuts, [recording]: combo });
      setRecording(null);
      toast(`${SHORTCUT_LABELS[recording]} → ${combo}`, 'success');
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [recording, shortcuts, set]);
  return (
    <Card title="Custom keyboard shortcuts" description="Click a shortcut, then press the new key combination. Escape cancels." action={<Button size="sm" variant="ghost" icon={RotateCcw} onClick={() => { set('shortcuts', { ...DEFAULT_SHORTCUTS }); toast('Shortcuts reset', 'success'); }}>Reset</Button>}>
      <Gate feature="shortcuts.custom">
        <div className="grid gap-x-8 sm:grid-cols-2">
          {Object.keys(SHORTCUT_LABELS).map((a) => (
            <SettingRow key={a} title={SHORTCUT_LABELS[a]} className="!py-2">
              <button type="button" onClick={() => setRecording(recording === a ? null : a)} aria-label={`Change shortcut for ${SHORTCUT_LABELS[a]}`}
                className={cn('flex h-8 min-w-[6.5rem] items-center justify-center gap-1.5 rounded-btn border px-3 font-mono text-xs', recording === a ? 'animate-pulse border-accent bg-accent/10 text-accent' : 'border-line/15 hover:bg-line/[0.07]')}>
                <Keyboard className="h-3.5 w-3.5" />{recording === a ? 'Press keys…' : shortcuts[a] ?? '—'}
              </button>
            </SettingRow>
          ))}
        </div>
      </Gate>
    </Card>
  );
}

function Advanced() {
  const theme = useStore((s) => s.theme);
  const rules = useStore((s) => s.metaRules);
  const fieldDefs = useStore((s) => s.customFieldDefs);
  const sourceSort = useStore((s) => s.sourceSort);
  const setTheme = useStore((s) => s.setTheme);
  const set = useStore((s) => s.set);
  const [css, setCss] = useState(theme.customCss);
  const report = useMemo(() => sanitizeCss(css), [css]);
  const blankRule = { name: '', type: 'any' as MediaType | 'any', field: 'genres' as MetaRule['when']['field'], contains: '', action: 'addTag' as MetaRule['then']['action'], value: '' };
  const [rule, setRule] = useState(blankRule);
  const [fieldName, setFieldName] = useState('');
  const [fieldTypes, setFieldTypes] = useState<MediaType[]>([]);

  const exportTheme = () => { const { wallpaper: _w, ...rest } = theme; void _w; saveJson({ debridoneTheme: 1, name: 'My DebridOne theme', theme: rest }, 'debridone-theme.json'); toast('Theme exported (the wallpaper image is not included)', 'success'); };
  const importTheme = async () => {
    try { const data = await pickJson(); if (data == null) return; const t = coerceTheme(data); if (!t) throw new Error('No valid theme settings were found in that file.'); setTheme(t); if (t.customCss !== undefined) setCss(t.customCss); toast(`Theme imported (${Object.keys(t).length} settings applied)`, 'success'); }
    catch (e) { toast((e as Error).message || 'That file is not valid JSON.', 'error'); }
  };

  return (
    <div className="max-w-4xl space-y-6">
      <Card title="Custom CSS theme" description="Write your own styles. They are sanitised, then scoped to the app container so they cannot load remote resources or affect anything outside it."
        action={<Switch label="Enable custom CSS" checked={theme.customCssEnabled} onChange={(v) => setTheme({ customCssEnabled: v })} />}>
        <Gate feature="theme.customCss">
          <textarea className="field min-h-[11rem] font-mono text-xs leading-relaxed" spellCheck={false} value={css} onChange={(e) => setCss(e.target.value)} placeholder={SAMPLE_CSS} aria-label="Custom CSS" />
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Button variant="primary" icon={Check} disabled={css === theme.customCss && theme.customCssEnabled} onClick={() => { setTheme({ customCss: css, customCssEnabled: true }); toast('Custom CSS applied', 'success'); }}>Apply</Button>
            <Button onClick={() => setCss(SAMPLE_CSS)}>Insert example</Button>
            <Button variant="ghost" icon={Trash2} disabled={!css && !theme.customCss} onClick={() => { setCss(''); setTheme({ customCss: '', customCssEnabled: false }); toast('Custom CSS removed'); }}>Clear</Button>
            <span className="ml-auto text-xs text-muted">{css.length.toLocaleString()} / 20,000 characters</span>
          </div>
          {report.removed.length > 0 && <Callout tone="warn" className="mt-3" title="Some of this will be removed for safety">{report.removed.join(' · ')}</Callout>}
          <p className="mt-3 text-xs leading-relaxed text-muted">Useful hooks: <code className="rounded bg-line/10 px-1">.app-sidebar</code>, <code className="rounded bg-line/10 px-1">.app-topbar</code>, <code className="rounded bg-line/10 px-1">.app-main</code>, <code className="rounded bg-line/10 px-1">.surface</code>, and the tokens <code className="rounded bg-line/10 px-1">--accent</code>, <code className="rounded bg-line/10 px-1">--bg</code>, <code className="rounded bg-line/10 px-1">--card</code>, <code className="rounded bg-line/10 px-1">--radius</code>.</p>
        </Gate>
      </Card>

      <Card title="Theme import & export" description="Share a look as a small JSON file. Imported files are validated setting by setting.">
        <Gate feature="theme.presets"><div className="flex flex-wrap gap-2"><Button icon={Download} onClick={exportTheme}>Export current theme</Button><Button icon={Upload} onClick={importTheme}>Import theme</Button></div></Gate>
      </Card>

      <Card title="Metadata rules" description="Applied automatically to matching items, underneath your manual edits. Turn a rule off to see the difference immediately.">
        <Gate feature="meta.rules">
          <ul className="space-y-1.5">
            {rules.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center gap-2 rounded-btn border border-line/[0.08] px-3 py-2">
                <Switch label={`Enable rule ${r.name}`} checked={r.enabled} onChange={(v) => set('metaRules', rules.map((x) => (x.id === r.id ? { ...x, enabled: v } : x)))} />
                <span className="min-w-0 flex-1 basis-56 text-[0.8125rem]"><span className="font-medium">{r.name}</span><span className="block text-xs text-muted">If {r.when.type === 'any' ? 'any item' : TYPE_PLURAL[r.when.type]}’ {r.when.field} contains “{r.when.contains}” → {{ addTag: 'add tag', addGenre: 'add genre', setCollection: 'set collection to', setLanguage: 'set language to' }[r.then.action]} “{r.then.value}”</span></span>
                <Button size="sm" variant="ghost" icon={Trash2} aria-label={`Delete rule ${r.name}`} onClick={() => set('metaRules', rules.filter((x) => x.id !== r.id))} />
              </li>
            ))}
            {!rules.length && <li className="text-[0.8125rem] text-muted">No rules yet.</li>}
          </ul>
          <form className="mt-4 grid gap-2 rounded-btn bg-line/[0.04] p-3 sm:grid-cols-6" onSubmit={(e) => { e.preventDefault(); if (!rule.contains.trim() || !rule.value.trim()) return; set('metaRules', [...rules, { id: uid('rule-'), enabled: true, name: rule.name.trim() || `${rule.action} “${rule.value.trim()}”`, when: { type: rule.type, field: rule.field, contains: rule.contains.trim() }, then: { action: rule.action, value: rule.value.trim() } }]); setRule(blankRule); toast('Rule added', 'success'); }}>
            <input className="field sm:col-span-6" placeholder="Rule name (optional)" value={rule.name} maxLength={50} onChange={(e) => setRule({ ...rule, name: e.target.value })} aria-label="Rule name" />
            <Select label="Applies to" className="sm:col-span-2" value={rule.type} onChange={(v) => setRule({ ...rule, type: v })} options={[{ value: 'any', label: 'Any media' }, ...MEDIA_TYPES.map((x) => ({ value: x, label: TYPE_PLURAL[x] }))]} />
            <Select label="Field" className="sm:col-span-2" value={rule.field} onChange={(v) => setRule({ ...rule, field: v })} options={[{ value: 'genres', label: 'Genres' }, { value: 'tags', label: 'Tags' }, { value: 'title', label: 'Title' }, { value: 'creator', label: 'Creator' }, { value: 'publisher', label: 'Publisher' }]} />
            <input className="field sm:col-span-2" placeholder="contains…" value={rule.contains} maxLength={60} onChange={(e) => setRule({ ...rule, contains: e.target.value })} aria-label="Contains text" />
            <Select label="Action" className="sm:col-span-2" value={rule.action} onChange={(v) => setRule({ ...rule, action: v })} options={[{ value: 'addTag', label: 'Add tag' }, { value: 'addGenre', label: 'Add genre' }, { value: 'setCollection', label: 'Set collection' }, { value: 'setLanguage', label: 'Set language' }]} />
            <input className="field sm:col-span-2" placeholder="value" value={rule.value} maxLength={60} onChange={(e) => setRule({ ...rule, value: e.target.value })} aria-label="Value" />
            <Button type="submit" variant="primary" icon={Plus} className="sm:col-span-2" disabled={!rule.contains.trim() || !rule.value.trim()}>Add rule</Button>
          </form>
        </Gate>
      </Card>

      <Card title="Custom fields" description="Define fields that appear ready to fill in the metadata editor for the media types you choose.">
        <Gate feature="meta.customFields">
          <ul className="space-y-1.5">
            {fieldDefs.map((d) => <li key={d.id} className="flex items-center gap-2 rounded-btn border border-line/[0.08] px-3 py-2 text-[0.8125rem]"><span className="min-w-0 flex-1 truncate font-medium">{d.name}</span><span className="text-xs text-muted">{d.types.length ? d.types.map((x) => TYPE_PLURAL[x]).join(', ') : 'All media'}</span><Button size="sm" variant="ghost" icon={Trash2} aria-label={`Delete field ${d.name}`} onClick={() => set('customFieldDefs', fieldDefs.filter((x) => x.id !== d.id))} /></li>)}
            {!fieldDefs.length && <li className="text-[0.8125rem] text-muted">No custom fields defined.</li>}
          </ul>
          <form className="mt-4 space-y-2" onSubmit={(e) => { e.preventDefault(); const n = fieldName.trim(); if (!n || fieldDefs.some((d) => d.name.toLowerCase() === n.toLowerCase())) return; set('customFieldDefs', [...fieldDefs, { id: uid('cf-'), name: n, types: fieldTypes }]); setFieldName(''); setFieldTypes([]); toast('Custom field added', 'success'); }}>
            <div className="flex gap-2"><input className="field max-w-xs" placeholder="Field name, e.g. “Lent to”" value={fieldName} maxLength={40} onChange={(e) => setFieldName(e.target.value)} aria-label="Custom field name" /><Button type="submit" icon={Plus} disabled={!fieldName.trim()}>Add field</Button></div>
            <div className="flex flex-wrap items-center gap-1.5"><span className="mr-1 text-xs text-muted">Show on</span><Chip active={!fieldTypes.length} onClick={() => setFieldTypes([])}>All media</Chip>{MEDIA_TYPES.map((x) => <Chip key={x} active={fieldTypes.includes(x)} onClick={() => setFieldTypes(fieldTypes.includes(x) ? fieldTypes.filter((y) => y !== x) : [...fieldTypes, x])}>{TYPE_PLURAL[x]}</Chip>)}</div>
          </form>
        </Gate>
      </Card>

      <ShortcutEditor />

      <Card title="Source organisation preset" description="How the Sources page arranges your list by default.">
        <Gate feature="sources.presets">
          <Segmented value={sourceSort} onChange={(v) => { set('sourceSort', v); toast('Source organisation saved', 'success'); }} options={[{ value: 'priority', label: 'Priority (manual)' }, { value: 'category', label: 'By category' }, { value: 'status', label: 'Enabled & status' }, { value: 'name', label: 'By name' }]} />
        </Gate>
      </Card>
    </div>
  );
}

function Plan() {
  const pro = useStore((s) => s.proDemo);
  const free = ['All five media modules, players and readers', 'Library, collections, search and downloads', 'Metadata editing with restore', 'Custom sources and manifests', 'Dark & light modes, six accents, density', 'Reorder and hide home sections'];
  return (
    <div className="max-w-4xl space-y-6">
      <div className="grid gap-4 md:grid-cols-2">
        <div className={cn('surface p-5', !pro && 'ring-2 ring-accent')}>
          <p className="flex items-center gap-2 text-sm font-semibold">DebridOne Free{!pro && <Badge tone="accent">Current</Badge>}</p>
          <p className="mt-1 text-xs text-muted">Everything you need to manage and enjoy your library.</p>
          <ul className="mt-4 space-y-2 text-[0.8125rem]">{free.map((f) => <li key={f} className="flex gap-2"><Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />{f}</li>)}</ul>
        </div>
        <div className={cn('surface relative overflow-hidden p-5', pro && 'ring-2 ring-accent')}>
          <div className="pointer-events-none absolute -right-10 -top-10 h-40 w-40 rounded-full bg-accent/20 blur-3xl" />
          <p className="flex items-center gap-2 text-sm font-semibold"><Crown className="h-4 w-4 text-accent" />DebridOne Pro{pro && <Badge tone="accent">Demo Mode active</Badge>}</p>
          <p className="mt-1 text-xs text-muted">An advanced personal control centre. Everything in Free, plus:</p>
          <ul className="mt-4 grid gap-2 text-[0.8125rem] sm:grid-cols-2">{(Object.keys(FEATURE_LABEL) as Feature[]).map((f) => <li key={f} className="flex gap-2"><Sparkles className="mt-0.5 h-4 w-4 shrink-0 text-accent" />{FEATURE_LABEL[f]}</li>)}</ul>
          <Button variant="primary" className="mt-5 w-full" onClick={() => toast('Subscriptions are not implemented in this prototype — there is no checkout and no payment is taken. Use Demo Pro Mode to try every Pro feature.', 'info')}>Subscribe (not available in prototype)</Button>
        </div>
      </div>
      <Callout tone="info" title="How feature gating is built">
        Every Pro capability is a named feature in <code className="rounded bg-line/10 px-1">src/lib/features.ts</code> and is checked through one function. Today that function reads the Demo Pro switch; a production build would read a subscription status verified by the backend. Turn Demo Pro Mode off to see each control lock and the theme fall back to free-tier values — your Pro choices are kept and return when you switch it back on.
      </Callout>
    </div>
  );
}

export default function ProPage() {
  const pro = useStore((s) => s.proDemo);
  const set = useStore((s) => s.set);
  const [tab, setTab] = useTab<Tab>('appearance');
  return (
    <div className="page">
      <PageHeader title="DebridOne Pro" icon={Crown} subtitle="Your personal control centre: appearance, home dashboards, rules, shortcuts and custom CSS." />
      <div className={cn('mb-6 flex flex-wrap items-center gap-4 rounded-[calc(var(--radius)*1.2)] border p-4', pro ? 'border-accent/40 bg-accent/[0.07]' : 'border-line/10 bg-line/[0.04]')}>
        <span className={cn('flex h-11 w-11 shrink-0 items-center justify-center rounded-full', pro ? 'bg-accent text-accent-fg' : 'bg-line/10 text-muted')}><Crown className="h-5 w-5" /></span>
        <div className="min-w-0 flex-1 basis-64">
          <p className="text-sm font-semibold">Demo Pro Mode is {pro ? 'on' : 'off'}</p>
          <p className="text-xs leading-relaxed text-muted">{pro ? 'Every Pro feature is unlocked so you can explore it. This is a demonstration: no payment method is requested and nothing is charged.' : 'You are seeing the free tier. Pro controls are locked and Pro-only theme choices are paused, not deleted.'}</p>
        </div>
        <Switch label="Demo Pro Mode" checked={pro} disabled={!!useAuth.getState().current} onChange={(v) => { set('proDemo', v); toast(v ? 'Demo Pro Mode on — everything is unlocked' : 'Demo Pro Mode off — showing the free tier', 'success'); }} />
      </div>
      <Segmented className="mb-6" value={tab} onChange={setTab} options={[{ value: 'appearance', label: 'Appearance' }, { value: 'homepage', label: 'Homepage' }, { value: 'advanced', label: 'Advanced' }, { value: 'plan', label: 'Plans & gating' }]} />
      {tab === 'appearance' && <Appearance />}
      {tab === 'homepage' && <Homepage />}
      {tab === 'advanced' && <Advanced />}
      {tab === 'plan' && <Plan />}
    </div>
  );
}
