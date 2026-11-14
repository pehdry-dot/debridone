import { Reorder } from 'framer-motion';
import { ArrowDown, ArrowUp, Bell, Crown, Database, Download, FolderOpen, Globe, GripVertical, LogIn, LogOut, Palette, Play, Plug, Puzzle, RefreshCw, Settings as SettingsIcon, Shield, Tags, Trash2, Upload, User, UserPlus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { NavLink, useNavigate, useParams } from 'react-router-dom';
import { DESKTOP_ONLY, isDesktop } from '../adapters/desktop';
import { getMetaProvider } from '../adapters/providers';
import { checkSourceUrl, testConnection } from '../adapters/sources';
import { PALETTES } from '../components/Cover';
import { Avatar, NAV } from '../components/Layout';
import { effectiveTheme, FONTS } from '../components/ThemeApplier';
import { Badge, Button, Callout, Card, Field, PageHeader, Range, Segmented, Select, SettingRow, Switch } from '../components/ui';
import { FREE_ACCENTS } from '../lib/features';
import { LANGUAGES, useDateFormat, useT } from '../lib/i18n';
import { clearBlobs, storageEstimate } from '../lib/idb';
import { cn, fmtBytes, moveItem, pickJson, saveJson } from '../lib/utils';
import { clearFinished, useDownloads } from '../store/downloads';
import { usePlayer } from '../store/player';
import { confirmDialog, toast, useUi } from '../store/ui';
import { NAV_IDS, STORAGE_KEY, useStore } from '../store/useStore';
import type { MetaFields, Settings } from '../types';
import { DebridPanel } from './Sources';
import { useAuth } from '../store/auth';

const SECTIONS = [
  { id: 'general', label: 'General', icon: SettingsIcon }, { id: 'appearance', label: 'Appearance', icon: Palette }, { id: 'playback', label: 'Playback', icon: Play },
  { id: 'downloads', label: 'Downloads', icon: Download }, { id: 'integrations', label: 'Integrations', icon: Plug }, { id: 'metadata', label: 'Metadata', icon: Tags },
  { id: 'privacy', label: 'Privacy', icon: Shield }, { id: 'account', label: 'Account', icon: User },
] as const;
type SectionId = (typeof SECTIONS)[number]['id'];
const Rows = ({ children }: { children: React.ReactNode }) => <div className="divide-y divide-line/[0.06]">{children}</div>;

function General() {
  const settings = useStore((s) => s.settings);
  const set = useStore((s) => s.setSetting);
  const t = useT();
  const fmt = useDateFormat();
  return (
    <>
      <Card title="Language & region">
        <Rows>
          <SettingRow title="Language" description="Navigation, greetings and search are translated. The rest of the prototype is in English.">
            <Select label="Language" value={settings.language} onChange={(v) => set('language', v)} options={LANGUAGES.map((l) => ({ value: l.id, label: l.label }))} />
          </SettingRow>
          <SettingRow title="Date format" description={`Preview: ${fmt(Date.now())}`}>
            <Select label="Date format" value={settings.dateFormat} onChange={(v) => set('dateFormat', v)} options={[{ value: 'locale', label: 'Regional default' }, { value: 'long', label: 'Long (12 November 2026)' }, { value: 'iso', label: 'ISO (2026-11-12)' }]} />
          </SettingRow>
          <SettingRow title="Time format"><Segmented size="sm" value={settings.timeFormat} onChange={(v) => set('timeFormat', v)} options={[{ value: '12h', label: '12-hour' }, { value: '24h', label: '24-hour' }]} /></SettingRow>
        </Rows>
      </Card>
      <Card title="Startup">
        <SettingRow title="Startup page" description="Where DebridOne opens when you launch it or reload at the root address.">
          <Select label="Startup page" value={settings.startPage} onChange={(v) => set('startPage', v)} options={NAV_IDS.filter((id) => id !== 'pro' && id !== 'settings').map((id) => ({ value: NAV[id].to, label: t(NAV[id].key) }))} />
        </SettingRow>
      </Card>
      <Card title="Notifications" description="Shown inside the app. This prototype never sends system or push notifications.">
        <Rows>
          {([['toasts', 'In-app toasts', 'Short confirmations at the bottom of the screen. Errors are always shown.'], ['downloads', 'Download activity', 'Add finished and failed downloads to the bell menu.'], ['library', 'Library activity', 'Imports and library changes.'], ['sources', 'Source health', 'Alert when a connection test finds a source unreachable.']] as const).map(([key, title, description]) => (
            <SettingRow key={key} title={title} description={description}><Switch label={title} checked={settings.notify[key]} onChange={(v) => set('notify', { ...settings.notify, [key]: v })} /></SettingRow>
          ))}
        </Rows>
      </Card>
    </>
  );
}

function Appearance() {
  const navigate = useNavigate();
  const theme = useStore((s) => s.theme);
  const pro = useStore((s) => s.proDemo);
  const setTheme = useStore((s) => s.setTheme);
  const shown = effectiveTheme(theme, pro);
  return (
    <>
      <Card title="Theme">
        <Rows>
          <SettingRow title="Mode" description={pro ? undefined : 'OLED black is part of DebridOne Pro.'}>
            <Segmented size="sm" value={shown.mode} onChange={(v) => (v === 'oled' && !pro ? navigate('/pro') : setTheme({ mode: v }))} options={[{ value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }, { value: 'oled', label: pro ? 'OLED black' : 'OLED · Pro' }]} />
          </SettingRow>
          <SettingRow title="Accent colour" description={pro ? 'Pick any colour in DebridOne Pro → Appearance.' : 'Six accents are free. Any colour is a Pro feature.'}>
            {FREE_ACCENTS.map((c) => <button key={c} type="button" aria-label={`Accent ${c}`} aria-pressed={shown.accent.toUpperCase() === c} onClick={() => setTheme({ accent: c })} className={cn('h-7 w-7 rounded-full border-2 transition-transform hover:scale-110', shown.accent.toUpperCase() === c ? 'border-fg' : 'border-transparent')} style={{ background: c }} />)}
          </SettingRow>
          <SettingRow title="Font" description="Uses fonts already on your device, so nothing is downloaded.">
            <Select label="Interface font" value={shown.font} onChange={(v) => (FONTS[v].pro && !pro ? navigate('/pro') : setTheme({ font: v }))} options={(Object.keys(FONTS) as (keyof typeof FONTS)[]).map((f) => ({ value: f, label: `${FONTS[f].label}${FONTS[f].pro && !pro ? ' · Pro' : ''}` }))} />
          </SettingRow>
          <SettingRow title="Layout density"><Segmented size="sm" value={shown.density} onChange={(v) => setTheme({ density: v })} options={[{ value: 'compact', label: 'Compact' }, { value: 'comfortable', label: 'Comfortable' }, { value: 'spacious', label: 'Spacious' }]} /></SettingRow>
          <SettingRow title="Card size" description="Width of posters and covers on shelves."><Segmented size="sm" value={shown.cardSize} onChange={(v) => setTheme({ cardSize: v })} options={[{ value: 'small', label: 'Small' }, { value: 'medium', label: 'Medium' }, { value: 'large', label: 'Large' }]} /></SettingRow>
          <SettingRow title="Page titles" description="Large titles, like Apple’s own apps, or compact ones."><Segmented size="sm" value={shown.headerStyle} onChange={(v) => setTheme({ headerStyle: v })} options={[{ value: 'large', label: 'Large' }, { value: 'compact', label: 'Compact' }]} /></SettingRow>
          <SettingRow title="Shadows" description="How much depth cards and panels get."><Segmented size="sm" value={shown.shadows} onChange={(v) => setTheme({ shadows: v })} options={[{ value: 'none', label: 'None' }, { value: 'soft', label: 'Soft' }, { value: 'deep', label: 'Deep' }]} /></SettingRow>
          <SettingRow title="Sidebar" description="Translucent lets the page show through; solid is opaque."><Segmented size="sm" value={shown.sidebarStyle} onChange={(v) => setTheme({ sidebarStyle: v })} options={[{ value: 'translucent', label: 'Translucent' }, { value: 'solid', label: 'Solid' }]} /></SettingRow>
        </Rows>
      </Card>
      <Callout tone="info" title="Want more control?" action={<Button size="sm" variant="primary" icon={Crown} onClick={() => navigate('/pro')}>Open theme editor</Button>}>
        Wallpapers, card radius, visual effects, animation intensity, sidebar layout, presets and custom CSS live in DebridOne Pro{pro ? ' — unlocked by Demo Pro Mode.' : '.'}
      </Callout>
    </>
  );
}

function Playback() {
  const settings = useStore((s) => s.settings);
  const set = useStore((s) => s.setSetting);
  const p = settings.playback;
  const up = (patch: Partial<Settings['playback']>) => set('playback', { ...p, ...patch });
  return (
    <>
      <Card title="Video">
        <Rows>
          <SettingRow title="Preferred video quality" description="Chooses the closest matching source when a title offers several."><Select label="Preferred quality" value={p.quality} onChange={(v) => up({ quality: v })} options={[{ value: 'auto', label: 'Auto (first source)' }, { value: '1080p', label: '1080p' }, { value: '720p', label: '720p' }, { value: '480p', label: '480p' }]} /></SettingRow>
          <SettingRow title="Resume where I left off"><Switch label="Resume playback" checked={p.resume} onChange={(v) => up({ resume: v })} /></SettingRow>
          <SettingRow title="Autoplay next episode"><Switch label="Autoplay next episode" checked={p.autoplayNext} onChange={(v) => up({ autoplayNext: v })} /></SettingRow>
          <SettingRow title="Default playback speed"><Select label="Default speed" value={String(p.rate)} onChange={(v) => up({ rate: Number(v) })} options={[0.5, 0.75, 1, 1.25, 1.5, 2].map((r) => ({ value: String(r), label: r === 1 ? 'Normal' : `${r}×` }))} /></SettingRow>
          <SettingRow title="Skip interval" description="Arrow keys and the skip buttons."><Segmented size="sm" value={String(p.skipSeconds)} onChange={(v) => up({ skipSeconds: Number(v) })} options={[5, 10, 15, 30].map((n) => ({ value: String(n), label: `${n}s` }))} /></SettingRow>
        </Rows>
      </Card>
      <Card title="Subtitles">
        <Rows>
          <SettingRow title="Show subtitles by default"><Switch label="Subtitles on by default" checked={p.subtitlesOn} onChange={(v) => up({ subtitlesOn: v })} /></SettingRow>
          <SettingRow title="Preferred subtitle language"><Select label="Subtitle language" value={p.subtitleLang} onChange={(v) => up({ subtitleLang: v })} options={[{ value: 'en', label: 'English' }, { value: 'es', label: 'Español' }]} /></SettingRow>
          <SettingRow title="Subtitle size"><Segmented size="sm" value={p.subtitleSize} onChange={(v) => up({ subtitleSize: v })} options={[{ value: 'sm', label: 'Small' }, { value: 'md', label: 'Medium' }, { value: 'lg', label: 'Large' }]} /></SettingRow>
        </Rows>
      </Card>
      <Card title="Audio">
        <Rows>
          <SettingRow title="Music volume" description={`${p.musicVolume}%`}><Range label="Music volume" className="w-44" value={p.musicVolume} onChange={(v) => { up({ musicVolume: v }); usePlayer.getState().setVolume(v / 100); }} /></SettingRow>
          <SettingRow title="Pause music when a video starts"><Switch label="Pause music when a video starts" checked={p.pauseMusicOnVideo} onChange={(v) => up({ pauseMusicOnVideo: v })} /></SettingRow>
        </Rows>
      </Card>
    </>
  );
}

function Downloads() {
  const navigate = useNavigate();
  const settings = useStore((s) => s.settings);
  const set = useStore((s) => s.setSetting);
  const sources = useStore((s) => s.sources);
  const count = useDownloads((s) => s.items.length);
  const d = settings.downloads;
  return (
    <>
      <Card title="Transfers">
        <Rows>
          <SettingRow title="Concurrent downloads" description="Extra downloads wait in the queue."><Segmented size="sm" value={String(d.concurrent)} onChange={(v) => set('downloads', { ...d, concurrent: Number(v) })} options={[1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: String(n) }))} /></SettingRow>
          <SettingRow title="Save finished files automatically" description="Hands each completed file to your browser’s download prompt straight away."><Switch label="Save automatically" checked={d.autoSave} onChange={(v) => set('downloads', { ...d, autoSave: v })} /></SettingRow>
          <SettingRow title="Keep download history" description="When off, finished rows are removed from the manager a few seconds after completing."><Switch label="Keep history" checked={d.keepHistory} onChange={(v) => set('downloads', { ...d, keepHistory: v })} /></SettingRow>
          <SettingRow title="Download history" description={`${count} record${count === 1 ? '' : 's'} in the manager.`}><Button size="sm" icon={Trash2} disabled={!count} onClick={() => { clearFinished(); toast('Download history cleared', 'success'); }}>Clear finished</Button></SettingRow>
        </Rows>
      </Card>
      <Card title="Default location">
        <SettingRow title="Save to" description={d.location}>
          <Button size="sm" icon={FolderOpen} onClick={() => toast(isDesktop() ? 'Folder picker is provided by the desktop shell.' : 'Browsers decide where downloads go (usually your Downloads folder). Choosing a folder needs the desktop app.', 'info')}>Choose folder…</Button>
        </SettingRow>
        <Callout tone="desktop" className="mt-2" title="Desktop-only capabilities"><ul className="mt-1 list-disc space-y-0.5 pl-4">{DESKTOP_ONLY.map((x) => <li key={x}>{x}</li>)}</ul></Callout>
      </Card>
      <Card title="Source priority" description="When several sources offer the same item, the highest one wins." action={<Button size="sm" onClick={() => navigate('/sources')}>Reorder in Sources</Button>}>
        <ol className="space-y-1.5 text-[0.8125rem]">{sources.filter((s) => s.enabled).slice(0, 5).map((s, i) => <li key={s.id} className="flex items-center gap-3"><span className="flex h-6 w-6 items-center justify-center rounded-full bg-line/[0.07] text-xs tabular-nums text-muted">{i + 1}</span><span className="truncate">{s.name}</span>{s.trust === 'unreviewed' && <Badge tone="warn">Unreviewed</Badge>}</li>)}</ol>
      </Card>
    </>
  );
}

function Integrations() {
  const settings = useStore((s) => s.settings);
  const set = useStore((s) => s.setSetting);
  const providers = useStore((s) => s.metaProviders);
  const [url, setUrl] = useState(settings.backendUrl);
  const [testing, setTesting] = useState(false);
  const error = url.trim() ? checkSourceUrl(url.trim()) : null;
  const test = async () => { setTesting(true); const r = await testConnection({ url: url.trim(), trust: 'unreviewed' }); setTesting(false); toast(`Backend: ${r.note}`, r.status === 'online' ? 'success' : 'warn'); };
  return (
    <>
      <Card title="Addons & library" description="What appears in your library, and where addons may connect.">
        <SettingRow title="Show the sample library" description="The demo films, music, games, manga and books bundled with the app. Turn it off to see only what your addons and imports provide.">
          <Switch label="Show the sample library" checked={settings.showDemo} onChange={(v) => set('showDemo', v)} />
        </SettingRow>
        <SettingRow title="Allow addons on this computer or network" description="For self-hosted addons running on localhost or your own network. Off by default, because any website you visit could otherwise reach those services.">
          <Switch label="Allow local-network addons" checked={settings.allowLocalAddons} onChange={(v) => set('allowLocalAddons', v)} />
        </SettingRow>
        <SettingRow title="Addons" description="Install, check and order the addons that supply catalogues, details, streams and subtitles.">
          <Button size="sm" icon={Puzzle} onClick={() => { window.location.hash = '#/addons'; }}>Open Addons</Button>
        </SettingRow>
      </Card>
      <Card title="Connected providers" description="Debrid services. Both are demo adapters in this prototype."><DebridPanel /></Card>
      <Card title="API configuration" description="Live integrations keep their secrets on a server. Point the app at that server here.">
        <Field label="DebridOne backend URL" hint={error ?? 'No backend ships with this prototype, so this is empty by default. Nothing is sent to this address yet; it is where a deployment would plug in its API.'}>
          <div className="flex gap-2">
            <input className="field font-mono text-xs" placeholder="https://api.your-debridone.example" value={url} onChange={(e) => setUrl(e.target.value)} spellCheck={false} />
            <Button disabled={!!error || url.trim() === settings.backendUrl} onClick={() => { set('backendUrl', url.trim()); toast(url.trim() ? 'Backend URL saved' : 'Backend URL cleared', 'success'); }}>Save</Button>
            <Button variant="ghost" icon={RefreshCw} disabled={!url.trim() || !!error} loading={testing} onClick={test}>Test</Button>
          </div>
        </Field>
        <Callout tone="info" className="mt-4">API keys are never typed into, stored in, or bundled with the web app. The backend holds them and exposes only the calls the app needs.</Callout>
      </Card>
      <Card title="Provider management" description="Services the adapter layer knows about.">
        <ul className="divide-y divide-line/[0.06]">
          {providers.map((p) => { const def = getMetaProvider(p.id); if (!def) return null; return (
            <li key={p.id} className="flex flex-wrap items-center gap-3 py-2.5">
              <Globe className="h-4 w-4 shrink-0 text-muted" />
              <div className="min-w-0 flex-1 basis-56"><p className="text-[0.8125rem] font-medium">{def.name} <span className="font-normal text-muted">· metadata</span></p><p className="text-xs text-muted">{def.note}</p></div>
              <Badge tone={def.kind === 'live' ? 'success' : def.kind === 'demo' ? 'warn' : 'default'}>{def.kind === 'live' ? 'Live, no key needed' : def.kind === 'demo' ? 'Demo data' : 'Needs backend'}</Badge>
            </li>
          ); })}
          {['Spotify', 'Apple Music'].map((n) => (
            <li key={n} className="flex flex-wrap items-center gap-3 py-2.5">
              <Globe className="h-4 w-4 shrink-0 text-muted" />
              <div className="min-w-0 flex-1 basis-56"><p className="text-[0.8125rem] font-medium">{n} <span className="font-normal text-muted">· music</span></p><p className="text-xs text-muted">Would require the service’s licensed developer API, user sign-in and a backend. DebridOne does not access its catalogue.</p></div>
              <Badge>Not available</Badge>
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}

function Metadata() {
  const navigate = useNavigate();
  const providers = useStore((s) => s.metaProviders);
  const overrides = useStore((s) => s.overrides);
  const imported = useStore((s) => s.imported);
  const rules = useStore((s) => s.metaRules);
  const set = useStore((s) => s.set);
  const [strategy, setStrategy] = useState<'mine' | 'file'>('mine');
  const edited = Object.keys(overrides).length, enriched = Object.keys(imported).length;

  const importAll = async () => {
    try {
      const data = await pickJson<{ debridoneMetadataBackup?: number; overrides?: Record<string, Partial<MetaFields>>; imported?: typeof imported }>();
      if (data == null) return;
      if (data.debridoneMetadataBackup !== 1 || typeof data.overrides !== 'object') throw new Error('That is not a DebridOne metadata export.');
      let conflicts = 0, applied = 0;
      const next = { ...overrides };
      for (const [id, fields] of Object.entries(data.overrides ?? {})) {
        if (!fields || typeof fields !== 'object') continue;
        const mine = next[id] ?? {};
        const merged: Record<string, unknown> = { ...mine };
        for (const [k, v] of Object.entries(fields)) {
          const clash = k in mine && JSON.stringify((mine as Record<string, unknown>)[k]) !== JSON.stringify(v);
          if (clash) conflicts++;
          if (!clash || strategy === 'file') { merged[k] = v; applied++; }
        }
        next[id] = merged as Partial<MetaFields>;
      }
      set('overrides', next);
      if (data.imported && typeof data.imported === 'object') set('imported', { ...data.imported, ...imported });
      toast(`Imported ${applied} field${applied === 1 ? '' : 's'}. ${conflicts} conflict${conflicts === 1 ? '' : 's'} resolved by keeping ${strategy === 'mine' ? 'your edits' : 'the file’s values'}.`, 'success');
    } catch (e) { toast((e as Error).message || 'That file is not valid JSON.', 'error'); }
  };
  const restoreAll = async () => {
    if (!(await confirmDialog({ title: 'Restore all original metadata?', body: `This removes your manual edits on ${edited} item${edited === 1 ? '' : 's'} and provider imports on ${enriched}. Custom artwork you uploaded is removed too.`, confirmLabel: 'Restore everything', danger: true }))) return;
    set('overrides', {}); set('imported', {});
    toast('All metadata restored to the original records', 'success');
  };

  return (
    <>
      <Card title="Provider priority" description="When two providers supply the same field, the higher one wins. Your manual edits always beat both.">
        <Reorder.Group axis="y" values={providers} onReorder={(next) => set('metaProviders', next)} className="space-y-1.5">
          {providers.map((p, i) => { const def = getMetaProvider(p.id); if (!def) return null; return (
            <Reorder.Item key={p.id} value={p} className="flex cursor-grab items-center gap-2.5 rounded-btn border border-line/[0.08] bg-card px-3 py-2 active:cursor-grabbing">
              <GripVertical className="h-4 w-4 shrink-0 text-muted" />
              <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-line/[0.07] text-xs tabular-nums text-muted">{i + 1}</span>
              <span className="min-w-0 flex-1"><span className="block truncate text-[0.8125rem] font-medium">{def.name}</span><span className="block truncate text-xs text-muted">{def.types.join(', ')} · {def.kind === 'live' ? 'live API' : def.kind === 'demo' ? 'demo data' : 'needs backend'}</span></span>
              <button type="button" aria-label={`Move ${def.name} up`} disabled={i === 0} onClick={() => set('metaProviders', moveItem(providers, i, i - 1))} className="rounded p-1 text-muted hover:bg-line/10 disabled:opacity-30"><ArrowUp className="h-3.5 w-3.5" /></button>
              <button type="button" aria-label={`Move ${def.name} down`} disabled={i === providers.length - 1} onClick={() => set('metaProviders', moveItem(providers, i, i + 1))} className="rounded p-1 text-muted hover:bg-line/10 disabled:opacity-30"><ArrowDown className="h-3.5 w-3.5" /></button>
              <Switch label={`Enable ${def.name}`} checked={p.enabled && !!def.lookup} disabled={!def.lookup} onChange={(v) => set('metaProviders', providers.map((x) => (x.id === p.id ? { ...x, enabled: v } : x)))} />
            </Reorder.Item>
          ); })}
        </Reorder.Group>
      </Card>
      <Card title="Import & export" description={`${edited} item${edited === 1 ? ' has' : 's have'} manual edits · ${enriched} with provider imports.`}>
        <div className="flex flex-wrap items-center gap-2">
          <Button icon={Download} onClick={() => { saveJson({ debridoneMetadataBackup: 1, exportedAt: new Date().toISOString(), overrides, imported }, 'debridone-metadata.json'); toast('Metadata exported', 'success'); }}>Export all metadata (JSON)</Button>
          <Button icon={Upload} onClick={importAll}>Import metadata</Button>
          <span className="ml-auto flex items-center gap-2 text-xs text-muted">On conflict<Segmented size="sm" value={strategy} onChange={setStrategy} options={[{ value: 'mine', label: 'Keep my edits' }, { value: 'file', label: 'Use file' }]} /></span>
        </div>
        <div className="mt-4 border-t border-line/[0.06] pt-4"><Button variant="danger" icon={RefreshCw} disabled={!edited && !enriched} onClick={restoreAll}>Restore all original metadata</Button></div>
      </Card>
      <Card title="Custom rules" description="Rules apply metadata automatically, for example tagging everything from one studio." action={<Button size="sm" icon={Crown} onClick={() => navigate('/pro?tab=advanced')}>Edit rules in Pro</Button>}>
        {rules.length ? <ul className="space-y-1.5 text-[0.8125rem]">{rules.map((r) => <li key={r.id} className={cn('flex items-center gap-2', !r.enabled && 'opacity-50')}><Tags className="h-4 w-4 text-muted" /><span className="font-medium">{r.name}</span><span className="truncate text-muted">— if {r.when.field} contains “{r.when.contains}” → {r.then.action} “{r.then.value}”</span></li>)}</ul> : <p className="text-[0.8125rem] text-muted">No rules yet.</p>}
      </Card>
    </>
  );
}

function Privacy() {
  const settings = useStore((s) => s.settings);
  const set = useStore((s) => s.setSetting);
  const history = useStore((s) => s.history.length + s.recentTracks.length + s.searchHistory.length);
  const [usage, setUsage] = useState<{ local: number; idb: number | null; quota: number | null }>({ local: 0, idb: null, quota: null });
  const measure = () => {
    let local = 0;
    for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i)!; if (k.startsWith('debridone:')) local += (localStorage.getItem(k)?.length ?? 0) * 2; }
    storageEstimate().then((e) => setUsage({ local, idb: e ? e.usage : null, quota: e ? e.quota : null }));
  };
  useEffect(measure, []);

  const exportData = () => {
    const { ...state } = useStore.getState();
    const data = Object.fromEntries(Object.entries(state).filter(([, v]) => typeof v !== 'function'));
    saveJson({ debridoneBackup: 1, exportedAt: new Date().toISOString(), note: 'Imported media files live in IndexedDB and are not part of this file.', data }, 'debridone-user-data.json');
    toast('Your data was exported', 'success');
  };
  const importData = async () => {
    try {
      const file = await pickJson<{ debridoneBackup?: number; data?: Record<string, unknown> }>();
      if (file == null) return;
      if (file.debridoneBackup !== 1 || !file.data || typeof file.data !== 'object') throw new Error('That is not a DebridOne data export.');
      if (!(await confirmDialog({ title: 'Replace current data with this backup?', body: 'Your library, progress, collections, themes and settings will be replaced by the contents of the file.', confirmLabel: 'Restore backup', danger: true }))) return;
      const current = useStore.getState();
      const allowed = Object.keys(current).filter((k) => typeof (current as unknown as Record<string, unknown>)[k] !== 'function');
      useStore.setState(Object.fromEntries(Object.entries(file.data).filter(([k]) => allowed.includes(k))));
      toast('Backup restored', 'success');
    } catch (e) { toast((e as Error).message || 'That file is not valid JSON.', 'error'); }
  };
  const wipe = async () => {
    if (!(await confirmDialog({ title: 'Delete all local DebridOne data?', body: 'Removes your library, progress, edits, themes, settings, download records and every imported file from this browser, then reloads with the fresh demo library. This cannot be undone.', confirmLabel: 'Delete everything', danger: true, requireCheck: 'I understand this permanently erases my local data' }))) return;
    await clearBlobs().catch(() => {});
    Object.keys(localStorage).filter((k) => k.startsWith('debridone:')).forEach((k) => localStorage.removeItem(k));
    window.location.hash = '#/';
    window.location.reload();
  };

  return (
    <>
      <Callout tone="success" title="Everything stays on this device">This prototype has no account system and no server. Your library, history and imports are stored in this browser only. Network requests happen only when you play a sample stream, download a sample file, test a source, or look up a book on Open Library.</Callout>
      <Card title="History">
        <Rows>
          <SettingRow title="Keep watch, listen, play and read history" description="Powers the “Continue” rows and History tabs. Progress is still saved when this is off."><Switch label="Keep activity history" checked={settings.privacy.history} onChange={(v) => set('privacy', { ...settings.privacy, history: v })} /></SettingRow>
          <SettingRow title="Remember recent searches"><Switch label="Remember recent searches" checked={settings.privacy.searchHistory} onChange={(v) => set('privacy', { ...settings.privacy, searchHistory: v })} /></SettingRow>
          <SettingRow title="Clear history" description={`${history} entr${history === 1 ? 'y' : 'ies'} across activity, recently played and searches.`}>
            <Button size="sm" icon={Trash2} disabled={!history} onClick={async () => { if (await confirmDialog({ title: 'Clear all history?', body: 'Removes activity history, recently played songs, recent searches and “recently accessed”. Progress, favorites and collections are kept.', confirmLabel: 'Clear history', danger: true })) { useStore.getState().clearHistory(); toast('History cleared', 'success'); } }}>Clear history</Button>
          </SettingRow>
        </Rows>
      </Card>
      <Card title="Local data controls" action={<Button size="sm" variant="ghost" icon={RefreshCw} onClick={measure}>Refresh</Button>}>
        <dl className="mb-4 grid gap-3 sm:grid-cols-3">
          <div className="rounded-btn bg-line/[0.05] p-3"><dt className="flex items-center gap-1.5 text-xs text-muted"><Database className="h-3.5 w-3.5" />Settings & library</dt><dd className="mt-1 text-lg font-semibold tabular-nums">{fmtBytes(usage.local)}</dd><dd className="text-[0.6875rem] text-muted">localStorage</dd></div>
          <div className="rounded-btn bg-line/[0.05] p-3"><dt className="flex items-center gap-1.5 text-xs text-muted"><Database className="h-3.5 w-3.5" />Imported files & cache</dt><dd className="mt-1 text-lg font-semibold tabular-nums">{usage.idb == null ? 'Unknown' : fmtBytes(usage.idb)}</dd><dd className="text-[0.6875rem] text-muted">site storage in use</dd></div>
          <div className="rounded-btn bg-line/[0.05] p-3"><dt className="flex items-center gap-1.5 text-xs text-muted"><Database className="h-3.5 w-3.5" />Browser allowance</dt><dd className="mt-1 text-lg font-semibold tabular-nums">{usage.quota == null ? 'Unknown' : fmtBytes(usage.quota)}</dd><dd className="text-[0.6875rem] text-muted">set by your browser</dd></div>
        </dl>
        <div className="flex flex-wrap gap-2">
          <Button icon={Download} onClick={exportData}>Export user data</Button>
          <Button icon={Upload} onClick={importData}>Restore from export</Button>
          <Button variant="danger" icon={Trash2} onClick={wipe}>Delete local prototype data</Button>
        </div>
      </Card>
    </>
  );
}

function Account() {
  const navigate = useNavigate();
  const profile = useStore((s) => s.profile);
  const pro = useStore((s) => s.proDemo);
  const debrid = useStore((s) => s.debrid);
  const set = useStore((s) => s.set);
  const account = useAuth((s) => s.current);
  const [name, setName] = useState(profile.name);
  const [pw, setPw] = useState({ current: '', next: '', confirm: '' });
  const [delPw, setDelPw] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const changePw = async () => {
    setErr(null);
    if (pw.next !== pw.confirm) { setErr('The new passwords don’t match.'); return; }
    try { await useAuth.getState().changePassword(pw.current, pw.next); setPw({ current: '', next: '', confirm: '' }); toast('Password changed', 'success'); }
    catch (e) { setErr((e as Error).message); }
  };
  const deleteAccount = async () => {
    setErr(null);
    if (!(await confirmDialog({ title: 'Delete this account?', body: 'Its library, progress, collections and settings are erased from this device. This cannot be undone.', confirmLabel: 'Delete account', danger: true }))) return;
    try { await useAuth.getState().deleteAccount(delPw); } catch (e) { setErr((e as Error).message); }
  };
  return (
    <>
      <Card title="Your account" description={account ? 'Signed in on this device.' : 'You are using a guest profile on this device. Create an account to keep a separate library, with Pro included.'} action={account ? <Badge tone="accent" icon={Crown}>Pro</Badge> : undefined}>
        {account ? (
          <>
            <dl className="mb-4 grid gap-3 text-[0.8125rem] sm:grid-cols-2">
              <div><dt className="text-xs text-muted">Username</dt><dd className="font-mono">@{account.username}</dd></div>
              <div><dt className="text-xs text-muted">Plan</dt><dd>DebridOne Pro · included</dd></div>
              <div><dt className="text-xs text-muted">Member since</dt><dd>{new Date(account.createdAt).toLocaleDateString()}</dd></div>
              {account.email && <div><dt className="text-xs text-muted">Email</dt><dd>{account.email}</dd></div>}
            </dl>
            <Button icon={LogOut} onClick={() => useAuth.getState().signOut()}>Sign out</Button>
          </>
        ) : (
          <div className="flex flex-wrap gap-2">
            <Button variant="primary" icon={UserPlus} onClick={() => useUi.setState({ auth: 'signup' })}>Create account</Button>
            <Button icon={LogIn} onClick={() => useUi.setState({ auth: 'signin' })}>Sign in</Button>
          </div>
        )}
      </Card>
      <Card title="Profile" description="Shown in greetings and on your avatar.">
        <div className="flex flex-wrap items-end gap-4">
          <Avatar className="!h-16 !w-16 !text-2xl" />
          <Field label="Display name" className="min-w-[12rem] flex-1"><input className="field" value={name} maxLength={30} onChange={(e) => setName(e.target.value)} onBlur={() => { if (name.trim() && name.trim() !== profile.name) { set('profile', { ...profile, name: name.trim() }); toast('Profile updated', 'success'); } else setName(profile.name); }} /></Field>
        </div>
        <p className="mb-1.5 mt-4 text-xs font-medium text-muted">Avatar colour</p>
        <div className="flex flex-wrap gap-2">{PALETTES.map(([a, b], i) => <button key={i} type="button" aria-label={`Avatar colour ${i + 1}`} aria-pressed={profile.avatar === i} onClick={() => set('profile', { ...profile, avatar: i })} className={cn('h-8 w-8 rounded-full border-2 transition-transform hover:scale-110', profile.avatar === i ? 'border-fg' : 'border-transparent')} style={{ background: `linear-gradient(135deg, ${a}, ${b})` }} />)}</div>
      </Card>
      {account && (
        <Card title="Change password">
          <form className="grid gap-3 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); void changePw(); }}>
            <Field label="Current password"><input className="field" type="password" autoComplete="current-password" value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} /></Field>
            <Field label="New password"><input className="field" type="password" autoComplete="new-password" value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} /></Field>
            <Field label="Confirm new password"><input className="field" type="password" autoComplete="new-password" value={pw.confirm} onChange={(e) => setPw({ ...pw, confirm: e.target.value })} /></Field>
            <div className="sm:col-span-3"><Button type="submit" variant="primary" disabled={!pw.current || !pw.next}>Change password</Button></div>
          </form>
        </Card>
      )}
      <Card title="Subscription" action={!account ? <Button size="sm" icon={Crown} onClick={() => navigate('/pro')}>Manage in Pro</Button> : undefined}>
        {account ? (
          <p className="text-sm text-muted">Pro is included with every account on this personal build. Nothing is billed.</p>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <span className={cn('flex h-11 w-11 items-center justify-center rounded-full', pro ? 'bg-accent/15 text-accent' : 'bg-line/10 text-muted')}><Crown className="h-5 w-5" /></span>
            <div className="min-w-0 flex-1"><p className="text-sm font-semibold">{pro ? 'DebridOne Pro — Demo Mode' : 'DebridOne Free'}</p><p className="text-xs text-muted">Turn on Demo Pro Mode to try every Pro feature. No payment method is requested.</p></div>
            <Switch label="Demo Pro Mode" checked={pro} onChange={(v) => { set('proDemo', v); toast(v ? 'Demo Pro Mode on' : 'Demo Pro Mode off', 'success'); }} />
          </div>
        )}
      </Card>
      <Card title="Connected services" action={<Button size="sm" onClick={() => navigate('/settings/integrations')}>Manage</Button>}>
        <ul className="space-y-2 text-[0.8125rem]">
          {[['Real-Debrid', 'real-debrid'], ['Premiumize', 'premiumize']].map(([label, id]) => <li key={id} className="flex items-center justify-between gap-3"><span>{label}</span>{debrid[id]?.connected ? <Badge tone="success">Connected · demo adapter</Badge> : <Badge>Not connected</Badge>}</li>)}
          <li className="flex items-center justify-between gap-3"><span>Open Library</span><Badge tone="success">Public API · no account</Badge></li>
          <li className="flex items-center justify-between gap-3"><span><Bell className="mr-1.5 inline h-3.5 w-3.5 text-muted" />Cloud sync</span><Badge>Not available · needs backend</Badge></li>
        </ul>
      </Card>
      {account && (
        <Card title="Delete account" description="Erases this account and everything stored for it on this device. There is no recovery.">
          <div className="flex flex-wrap items-end gap-2">
            <Field label="Confirm with your password" className="min-w-[14rem]"><input className="field" type="password" autoComplete="current-password" value={delPw} onChange={(e) => setDelPw(e.target.value)} /></Field>
            <Button variant="danger" icon={Trash2} disabled={!delPw} onClick={deleteAccount}>Delete account</Button>
          </div>
        </Card>
      )}
      {err && <Callout tone="error">{err}</Callout>}
    </>
  );
}

const PANELS: Record<SectionId, () => JSX.Element> = { general: General, appearance: Appearance, playback: Playback, downloads: Downloads, integrations: Integrations, metadata: Metadata, privacy: Privacy, account: Account };

export default function SettingsPage() {
  const { section } = useParams();
  const active = (SECTIONS.find((s) => s.id === section)?.id ?? 'general') as SectionId;
  const Panel = PANELS[active];
  return (
    <div className="page">
      <PageHeader title="Settings" icon={SettingsIcon} subtitle="Saved automatically in this browser." />
      <div className="grid gap-6 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label="Settings sections" className="flex gap-1 overflow-x-auto no-scrollbar lg:sticky lg:top-4 lg:flex-col lg:self-start">
          {SECTIONS.map(({ id, label, icon: Icon }) => (
            <NavLink key={id} to={`/settings/${id}`} replace className={() => cn('flex h-9 shrink-0 items-center gap-2.5 whitespace-nowrap rounded-btn px-3 text-[0.8125rem] font-medium transition-colors', active === id ? 'bg-line/10 text-fg' : 'text-muted hover:bg-line/[0.06] hover:text-fg')}>
              <Icon className={cn('h-4 w-4', active === id && 'text-accent')} />{label}
            </NavLink>
          ))}
        </nav>
        <div className="min-w-0 max-w-3xl space-y-6" key={active}><h2 className="sr-only">{SECTIONS.find((s) => s.id === active)?.label}</h2><Panel /></div>
      </div>
    </div>
  );
}
