import { Reorder } from 'framer-motion';
import {
  ArrowDown, ArrowUp, CheckCircle2, CircleDashed, Download, ExternalLink, FileJson, FolderOpen, GripVertical, KeyRound, Pencil, Play, Plug, Plus, RefreshCw,
  ShieldAlert, ShieldCheck, Trash2, TriangleAlert, Unplug, Upload, WifiOff,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DEBRID_ADAPTERS, type DebridAccount, type DebridFile } from '../adapters/providers';
import { desktop } from '../adapters/desktop';
import { RealDebridSummary } from './Debrid';
import { BUILTIN_SOURCES, checkSourceUrl, SAMPLE_MANIFEST, testConnection, toManifest, validateManifest, type ManifestResult } from '../adapters/sources';
import { Badge, Button, Callout, Card, Chip, EmptyState, Field, Menu, Modal, PageHeader, Select, Spinner, Switch } from '../components/ui';
import { useEntitled } from '../lib/features';
import { cn, fmtBytes, moveItem, pickJson, saveJson, timeAgo, uid } from '../lib/utils';
import { startDownload } from '../store/downloads';
import { confirmDialog, toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Source, SourceCapability, SourceCategory, SourceStatus } from '../types';
import { SOURCE_CAPABILITIES, SOURCE_CATEGORIES } from '../types';

const STATUS: Record<SourceStatus, { label: string; dot: string; tone: 'success' | 'warn' | 'error' | 'default' }> = {
  unknown: { label: 'Not tested', dot: 'bg-line/30', tone: 'default' },
  checking: { label: 'Testing…', dot: 'bg-sky-400 animate-pulse', tone: 'default' },
  online: { label: 'Online', dot: 'bg-emerald-400', tone: 'success' },
  restricted: { label: 'Reachable, browser-restricted', dot: 'bg-amber-400', tone: 'warn' },
  offline: { label: 'Unreachable', dot: 'bg-red-400', tone: 'error' },
};
const CAT_LABEL: Record<SourceCategory, string> = { movies: 'Movies', tv: 'TV', music: 'Music', games: 'Games', manga: 'Manga', books: 'Books' };

/** Credentials typed for a source live here only — in memory, for this tab, never persisted or sent anywhere by the prototype. */
const sessionSecrets = new Map<string, string>();

export async function runSourceTest(source: Source, quiet = false) {
  const s = useStore.getState();
  s.updateSource(source.id, { status: 'checking' });
  const result = await testConnection(source);
  useStore.getState().updateSource(source.id, { status: result.status, statusNote: result.note, lastChecked: Date.now() });
  if (!quiet) toast(`${source.name}: ${STATUS[result.status].label}`, result.status === 'online' ? 'success' : result.status === 'restricted' ? 'warn' : 'error');
  if (result.status === 'offline' && s.settings.notify.sources) s.notify({ title: 'Source unreachable', body: `${source.name} — ${result.note}`, link: '/sources', tone: 'warn' });
  return result;
}

/* --------------------------- debrid providers --------------------------- */

export function DebridPanel() {
  const navigate = useNavigate();
  const connected = useStore((s) => s.debrid);
  const set = useStore((s) => s.set);
  const [busy, setBusy] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<Record<string, DebridAccount>>({});
  const [files, setFiles] = useState<Record<string, DebridFile[] | 'loading'>>({});

  const connect = async (id: string) => {
    const adapter = DEBRID_ADAPTERS.find((a) => a.id === id)!;
    setBusy(id);
    const account = await adapter.connect();
    setAccounts((a) => ({ ...a, [id]: account }));
    set('debrid', (d) => ({ ...d, [id]: { connected: true, since: Date.now() } }));
    setBusy(null);
    toast(`${adapter.name} demo adapter connected`, 'success');
  };
  const disconnect = (id: string) => {
    set('debrid', (d) => { const { [id]: _, ...rest } = d; void _; return rest; });
    setFiles((f) => { const { [id]: _, ...rest } = f; void _; return rest; });
    toast('Disconnected');
  };
  const toggleFiles = async (id: string) => {
    if (files[id]) { setFiles((f) => { const { [id]: _, ...rest } = f; void _; return rest; }); return; }
    setFiles((f) => ({ ...f, [id]: 'loading' }));
    const list = await DEBRID_ADAPTERS.find((a) => a.id === id)!.listFiles();
    setFiles((f) => ({ ...f, [id]: list }));
  };

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {desktop && <div className="lg:col-span-2"><RealDebridSummary /></div>}
      {DEBRID_ADAPTERS.filter((a) => !(desktop && a.id === 'real-debrid')).map((a) => {
        const on = !!connected[a.id]?.connected;
        const list = files[a.id];
        return (
          <div key={a.id} className="surface p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2 text-sm font-semibold">{a.name}<Badge tone="warn">Demo adapter</Badge>{on ? <Badge tone="success" icon={CheckCircle2}>Connected</Badge> : <Badge icon={CircleDashed}>Not connected</Badge>}</p>
                <p className="mt-1 text-xs text-muted">{a.blurb}</p>
                {on && <p className="mt-1 text-xs text-muted">Account: {accounts[a.id]?.username ?? 'demo-user'} · {accounts[a.id]?.plan ?? 'Demo adapter (no real account)'} · since {timeAgo(connected[a.id].since)}</p>}
              </div>
              <div className="flex shrink-0 gap-2">
                {on
                  ? <><Button size="sm" icon={FolderOpen} onClick={() => toggleFiles(a.id)}>{list ? 'Hide files' : 'My files'}</Button><Button size="sm" variant="ghost" icon={Unplug} onClick={() => disconnect(a.id)}>Disconnect</Button></>
                  : <Button size="sm" variant="primary" icon={Plug} loading={busy === a.id} onClick={() => connect(a.id)}>Connect demo account</Button>}
              </div>
            </div>
            <label className="mt-3 block">
              <span className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-muted"><KeyRound className="h-3.5 w-3.5" />API token (live connection)</span>
              <input className="field" disabled placeholder="Unavailable in the browser prototype" aria-describedby={`${a.id}-token-note`} />
              <span id={`${a.id}-token-note`} className="mt-1 block text-[0.6875rem] leading-relaxed text-muted/80">A live {a.name} connection needs a server-side adapter that keeps your token off the page and applies the provider’s terms. No credentials are collected here, and no request is sent to {a.website.replace('https://', '')}.</span>
            </label>
            {list === 'loading' && <div className="mt-3 flex justify-center py-4"><Spinner /></div>}
            {Array.isArray(list) && (
              <ul className="mt-3 divide-y divide-line/[0.06] rounded-btn border border-line/[0.07] px-3">
                {list.map((f) => (
                  <li key={f.id} className="flex items-center gap-3 py-2">
                    <div className="min-w-0 flex-1"><p className="truncate font-mono text-[0.75rem]">{f.name}</p><p className="text-xs text-muted">{f.sizeMB} MB · {f.source.license} · sample file</p></div>
                    <Button size="sm" icon={Play} onClick={() => navigate(`/watch/${f.itemId}?file=${encodeURIComponent(f.id)}${f.episodeId ? `&ep=${f.episodeId}` : ''}`)}>Stream</Button>
                    <Button size="sm" variant="ghost" icon={ExternalLink} aria-label={`Get ${f.name}`} onClick={() => { window.open(f.source.url, '_blank', 'noopener,noreferrer'); toast('Opened the underlying openly licensed file at its host.', 'info'); }} />
                  </li>
                ))}
              </ul>
            )}
          </div>
        );
      })}
    </div>
  );
}

/* ----------------------------- source editor ----------------------------- */

function SourceModal({ source, open, onClose }: { source?: Source; open: boolean; onClose: () => void }) {
  const blank = { name: '', url: 'https://', description: '', categories: [] as SourceCategory[], capabilities: ['catalog'] as SourceCapability[], auth: 'none' as Source['auth']['type'], secret: '' };
  const [f, setF] = useState(blank);
  useEffect(() => {
    if (open) setF(source ? { name: source.name, url: source.url, description: source.description, categories: source.categories, capabilities: source.capabilities, auth: source.auth.type, secret: sessionSecrets.get(source.id) ?? '' } : blank);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, source?.id]);
  const internal = /^(builtin|local):\/\//.test(f.url) && source?.trust === 'builtin';
  const urlError = internal ? null : checkSourceUrl(f.url.trim());
  const valid = f.name.trim().length >= 2 && !urlError && f.categories.length > 0;
  const toggle = <T,>(arr: T[], v: T) => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);
  const save = () => {
    if (!valid) return;
    const s = useStore.getState();
    const data = { name: f.name.trim().slice(0, 80), url: f.url.trim(), description: f.description.trim().slice(0, 400), categories: f.categories, capabilities: f.capabilities.length ? f.capabilities : (['catalog'] as SourceCapability[]), auth: { type: f.auth } };
    if (source) {
      const changedUrl = source.url !== data.url;
      s.updateSource(source.id, { ...data, ...(changedUrl ? { trust: 'unreviewed' as const, status: 'unknown' as const, statusNote: undefined } : {}) });
      if (f.secret) sessionSecrets.set(source.id, f.secret); else sessionSecrets.delete(source.id);
      toast('Source updated', 'success');
    } else {
      const id = uid('src:');
      s.set('sources', (list) => [...list, { ...data, id, enabled: true, status: 'unknown', trust: 'unreviewed' }]);
      if (f.secret) sessionSecrets.set(id, f.secret);
      toast(`Added “${data.name}” as an unreviewed source`, 'success');
    }
    onClose();
  };
  return (
    <Modal open={open} onClose={onClose} title={source ? 'Edit source' : 'Add source'} subtitle="Private to this browser. Nobody approves or reviews sources you add." size="md"
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!valid} onClick={save}>{source ? 'Save source' : 'Add source'}</Button></>}>
      <div className="space-y-4">
        <Field label="Name *"><input autoFocus className="field" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} maxLength={80} placeholder="e.g. My public-domain shelf" /></Field>
        <Field label="URL or endpoint *" hint={internal ? 'Built-in endpoint — cannot be changed.' : urlError ?? 'Looks good. https only; local and private-network addresses are refused.'}>
          <input className={cn('field font-mono text-xs', urlError && f.url.length > 8 && '!border-red-400/60')} value={f.url} disabled={internal} onChange={(e) => setF({ ...f, url: e.target.value })} spellCheck={false} />
        </Field>
        <Field label="Description"><textarea className="field min-h-[3.5rem]" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} maxLength={400} /></Field>
        <div><p className="mb-1.5 text-xs font-medium text-muted">Content categories *</p><div className="flex flex-wrap gap-1.5">{SOURCE_CATEGORIES.map((c) => <Chip key={c} active={f.categories.includes(c)} onClick={() => setF({ ...f, categories: toggle(f.categories, c) })}>{CAT_LABEL[c]}</Chip>)}</div></div>
        <div><p className="mb-1.5 text-xs font-medium text-muted">Capabilities</p><div className="flex flex-wrap gap-1.5">{SOURCE_CAPABILITIES.map((c) => <Chip key={c} active={f.capabilities.includes(c)} onClick={() => setF({ ...f, capabilities: toggle(f.capabilities, c) })}>{c}</Chip>)}</div></div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Authentication"><Select label="Authentication type" className="w-full" value={f.auth} onChange={(v) => setF({ ...f, auth: v })} options={[{ value: 'none', label: 'None' }, { value: 'apiKey', label: 'API key' }, { value: 'bearer', label: 'Bearer token' }, { value: 'basic', label: 'Basic (user:password)' }]} /></Field>
          {f.auth !== 'none' && <Field label="Credential (this tab only)" hint="Kept in memory until you close the tab. Never saved, exported or sent by this prototype."><input className="field" type="password" autoComplete="off" value={f.secret} onChange={(e) => setF({ ...f, secret: e.target.value })} /></Field>}
        </div>
      </div>
    </Modal>
  );
}

function ItemsModal({ source, onClose }: { source: Source | null; onClose: () => void }) {
  return (
    <Modal open={!!source} onClose={onClose} title={source ? `Files listed by ${source.name}` : ''} subtitle={source?.trust === 'unreviewed' ? 'Unreviewed source — links and descriptions are supplied by the manifest and have not been checked.' : 'Built-in sample files.'} size="md" footer={<Button variant="primary" onClick={onClose}>Close</Button>}>
      <ul className="divide-y divide-line/[0.06]">
        {source?.items?.map((it) => (
          <li key={it.url} className="flex flex-wrap items-center gap-3 py-2.5">
            <div className="min-w-0 flex-1 basis-52">
              <p className="flex flex-wrap items-center gap-2 text-[0.8125rem] font-medium">{it.title}{it.executable && <Badge tone="error" icon={TriangleAlert}>Executable</Badge>}</p>
              <p className="truncate font-mono text-[0.6875rem] text-muted">{it.url}</p>
              <p className="text-xs text-muted">{CAT_LABEL[it.type]}{it.sizeBytes ? ` · ${fmtBytes(it.sizeBytes)}` : ''}{it.license ? ` · ${it.license}` : ''}</p>
            </div>
            <Button size="sm" icon={Download} onClick={() => { void startDownload({ title: it.title, fileName: decodeURIComponent(it.url.split('?')[0].split('/').pop() || 'download'), source: `${source.name}${source.trust === 'unreviewed' ? ' · unreviewed' : ''}`, kind: 'fetch', url: it.url, size: it.sizeBytes }).then((id) => id && toast('Added to Downloads', 'success')); }}>Download</Button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}

function ManifestModal({ result, onClose }: { result: ManifestResult | null; onClose: () => void }) {
  const add = () => {
    if (!result?.source) return;
    useStore.getState().set('sources', (list) => [...list, { ...result.source!, id: uid('src:'), enabled: true, status: 'unknown', trust: 'unreviewed' }]);
    toast(`Imported “${result.source.name}” as an unreviewed source`, 'success');
    onClose();
  };
  return (
    <Modal open={!!result} onClose={onClose} title={result?.ok ? 'Manifest passed validation' : 'Manifest rejected'} size="md"
      footer={result?.ok ? <><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" onClick={add}>Add as unreviewed source</Button></> : <Button variant="primary" onClick={onClose}>Close</Button>}>
      {result?.ok && result.source ? (
        <div className="space-y-3 text-[0.8125rem]">
          <dl className="space-y-1.5">
            <div className="flex justify-between gap-4"><dt className="text-muted">Name</dt><dd className="text-right font-medium">{result.source.name}</dd></div>
            <div className="flex justify-between gap-4"><dt className="shrink-0 text-muted">Endpoint</dt><dd className="break-all text-right font-mono text-xs">{result.source.url}</dd></div>
            <div className="flex justify-between gap-4"><dt className="text-muted">Categories</dt><dd className="text-right">{result.source.categories.map((c) => CAT_LABEL[c]).join(', ')}</dd></div>
            <div className="flex justify-between gap-4"><dt className="text-muted">Capabilities</dt><dd className="text-right">{result.source.capabilities.join(', ')}</dd></div>
            <div className="flex justify-between gap-4"><dt className="text-muted">Listed files</dt><dd className="text-right">{result.source.items?.length ?? 0}</dd></div>
          </dl>
          {result.warnings.map((w) => <Callout key={w} tone="warn">{w}</Callout>)}
          <Callout tone="warn" title="This source has not been reviewed by anyone">The structure is valid, but DebridOne cannot vouch for what it links to. It will be labelled “Unreviewed”. Only add sources for content you are authorised to use.</Callout>
        </div>
      ) : (
        <div>
          <p className="mb-3 text-[0.8125rem] text-muted">Nothing was added. Fix these problems and import again:</p>
          <ul className="space-y-1.5">{result?.errors.map((e) => <li key={e} className="flex gap-2 rounded-btn bg-red-500/[0.08] px-3 py-2 text-[0.8125rem] text-red-300"><TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />{e}</li>)}</ul>
        </div>
      )}
    </Modal>
  );
}

/* --------------------------------- page --------------------------------- */

export default function SourcesPage() {
  const sources = useStore((s) => s.sources);
  const sort = useStore((s) => s.sourceSort);
  const set = useStore((s) => s.set);
  const updateSource = useStore((s) => s.updateSource);
  const canOrganise = useEntitled('sources.presets');
  const [category, setCategory] = useState<SourceCategory | 'all'>('all');
  const [editing, setEditing] = useState<Source | 'new' | null>(null);
  const [browsing, setBrowsing] = useState<Source | null>(null);
  const [manifest, setManifest] = useState<ManifestResult | null>(null);
  const [testingAll, setTestingAll] = useState(false);

  const activeSort = canOrganise ? sort : 'priority';
  const view = useMemo(() => {
    const list = sources.filter((s) => category === 'all' || s.categories.includes(category));
    if (activeSort === 'name') return [...list].sort((a, b) => a.name.localeCompare(b.name));
    if (activeSort === 'category') return [...list].sort((a, b) => a.categories[0].localeCompare(b.categories[0]) || a.name.localeCompare(b.name));
    if (activeSort === 'status') { const rank: SourceStatus[] = ['online', 'restricted', 'checking', 'unknown', 'offline']; return [...list].sort((a, b) => Number(b.enabled) - Number(a.enabled) || rank.indexOf(a.status) - rank.indexOf(b.status)); }
    return list;
  }, [sources, category, activeSort]);
  const reorderable = activeSort === 'priority' && category === 'all';

  const move = (id: string, dir: -1 | 1) => { const i = sources.findIndex((s) => s.id === id); set('sources', moveItem(sources, i, i + dir)); };
  const remove = async (s: Source) => {
    if (!(await confirmDialog({ title: `Remove “${s.name}”?`, body: s.trust === 'builtin' ? 'This is a built-in source. You can bring it back with “Restore built-in sources”.' : 'The source and its configuration will be deleted from this browser.', confirmLabel: 'Remove source', danger: true }))) return;
    set('sources', (list) => list.filter((x) => x.id !== s.id));
    sessionSecrets.delete(s.id);
    toast('Source removed', 'success');
  };
  const importManifest = async () => {
    try { const data = await pickJson(); if (data != null) setManifest(validateManifest(data)); }
    catch (e) { setManifest({ ok: false, errors: [(e as Error).message?.includes('5 MB') ? (e as Error).message : 'The file is not valid JSON.'], warnings: [] }); }
  };
  const testAll = async () => {
    setTestingAll(true);
    const results = await Promise.all(sources.filter((s) => s.enabled).map((s) => runSourceTest(s, true)));
    setTestingAll(false);
    toast(`Tested ${results.length} sources: ${results.filter((r) => r.status === 'online').length} online, ${results.filter((r) => r.status === 'restricted').length} browser-restricted, ${results.filter((r) => r.status === 'offline').length} unreachable`, 'info');
  };
  const restoreBuiltins = () => {
    const missing = BUILTIN_SOURCES.filter((b) => !sources.some((s) => s.id === b.id));
    if (!missing.length) { toast('All built-in sources are already present.'); return; }
    set('sources', [...sources, ...missing]);
    toast(`Restored ${missing.length} built-in source${missing.length === 1 ? '' : 's'}`, 'success');
  };

  const row = (s: Source, index: number) => {
    const st = STATUS[s.status];
    return (
      <div className={cn('surface flex flex-wrap items-start gap-x-4 gap-y-3 p-4', !s.enabled && 'opacity-60')}>
        <div className="flex shrink-0 items-center gap-2 pt-0.5">
          {reorderable ? <GripVertical className="h-4 w-4 cursor-grab text-muted" aria-hidden /> : <span className="w-4" />}
          <span className="flex h-7 w-7 items-center justify-center rounded-full bg-line/[0.07] text-xs font-semibold tabular-nums text-muted" title="Priority">{sources.indexOf(s) + 1}</span>
        </div>
        <div className="min-w-0 flex-1 basis-64">
          <p className="flex flex-wrap items-center gap-2 text-sm font-semibold">
            {s.name}
            {s.trust === 'builtin' ? <Badge tone="success" icon={ShieldCheck}>Built-in</Badge> : <Badge tone="warn" icon={ShieldAlert}>Unreviewed</Badge>}
            {s.auth.type !== 'none' && <Badge icon={KeyRound}>{s.auth.type}</Badge>}
          </p>
          <p className="mt-0.5 truncate font-mono text-[0.6875rem] text-muted">{s.url}</p>
          {s.description && <p className="mt-1.5 text-xs leading-relaxed text-muted">{s.description}</p>}
          <div className="mt-2 flex flex-wrap gap-1.5">
            {s.categories.map((c) => <Badge key={c} tone="accent">{CAT_LABEL[c]}</Badge>)}
            {s.capabilities.map((c) => <Badge key={c} tone="outline">{c}</Badge>)}
          </div>
          <p className="mt-2.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
            <span className={cn('h-2 w-2 shrink-0 rounded-full', st.dot)} /><span className="font-medium text-fg">{st.label}</span>
            {s.statusNote && <span>· {s.statusNote}</span>}{s.lastChecked && <span>· checked {timeAgo(s.lastChecked)}</span>}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {!!s.items?.length && <Button size="sm" icon={FolderOpen} onClick={() => setBrowsing(s)}>{s.items.length} files</Button>}
          <Button size="sm" icon={RefreshCw} loading={s.status === 'checking'} onClick={() => runSourceTest(s)}>Test</Button>
          <Switch label={`${s.enabled ? 'Disable' : 'Enable'} ${s.name}`} checked={s.enabled} onChange={(v) => { updateSource(s.id, { enabled: v }); toast(`${s.name} ${v ? 'enabled' : 'disabled'}`); }} />
          <Menu label={`Actions for ${s.name}`} items={[
            { label: 'Edit source', icon: Pencil, onClick: () => setEditing(s) },
            { label: 'Export manifest', icon: FileJson, onClick: () => { saveJson(toManifest(s), `${s.name.replace(/[^\w]+/g, '-').toLowerCase()}.manifest.json`); toast('Manifest exported (no credentials included)', 'success'); } },
            'divider',
            { label: 'Raise priority', icon: ArrowUp, disabled: sources.indexOf(s) === 0, onClick: () => move(s.id, -1) },
            { label: 'Lower priority', icon: ArrowDown, disabled: sources.indexOf(s) === sources.length - 1, onClick: () => move(s.id, 1) },
            'divider',
            { label: 'Remove source…', icon: Trash2, danger: true, onClick: () => remove(s) },
          ]} />
        </div>
        <span className="sr-only">Position {index + 1}</span>
      </div>
    );
  };

  return (
    <div className="page">
      <PageHeader title="Sources" icon={Plug} subtitle="Where DebridOne may look for content. Built-in sources point at legal, openly licensed material; anything you add yourself is private to this browser."
        actions={<>
          <Button icon={RefreshCw} loading={testingAll} onClick={testAll}>Test all</Button>
          <Button icon={Upload} onClick={importManifest}>Import manifest</Button>
          <Button variant="primary" icon={Plus} onClick={() => setEditing('new')}>Add source</Button>
        </>} />

      <section className="mb-10">
        <h2 className="mb-1 text-lg font-semibold tracking-tight">Debrid providers</h2>
        <p className="mb-4 max-w-3xl text-xs leading-relaxed text-muted">Demo adapters that show how a personal file list and authorised stream links flow through the app. They never contact the real services; the sample files are the same openly licensed films used elsewhere in the demo.</p>
        <DebridPanel />
      </section>

      <section>
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <h2 className="mr-2 text-lg font-semibold tracking-tight">Content sources</h2>
          <Chip active={category === 'all'} onClick={() => setCategory('all')}>All ({sources.length})</Chip>
          {SOURCE_CATEGORIES.map((c) => <Chip key={c} active={category === c} onClick={() => setCategory(category === c ? 'all' : c)}>{CAT_LABEL[c]} ({sources.filter((s) => s.categories.includes(c)).length})</Chip>)}
          <div className="ml-auto flex items-center gap-2" title={canOrganise ? undefined : 'Source organisation presets are a Pro feature'}>
            <span className="text-xs text-muted">Organise by</span>
            <Select label="Organise sources by" value={activeSort} disabled={!canOrganise} onChange={(v) => set('sourceSort', v)} options={[{ value: 'priority', label: 'Priority (manual)' }, { value: 'category', label: 'Category' }, { value: 'status', label: 'Enabled & status' }, { value: 'name', label: 'Name' }]} />
            {!canOrganise && <Badge tone="accent">Pro</Badge>}
          </div>
        </div>
        {!view.length ? (
          <EmptyState icon={WifiOff} title="No sources in this category" body="Add one, import a manifest, or restore the built-in set." action={<Button onClick={restoreBuiltins}>Restore built-in sources</Button>} />
        ) : reorderable ? (
          <Reorder.Group axis="y" values={sources} onReorder={(next) => set('sources', next)} className="space-y-3">
            {sources.map((s, i) => <Reorder.Item key={s.id} value={s}>{row(s, i)}</Reorder.Item>)}
          </Reorder.Group>
        ) : <ul className="space-y-3">{view.map((s, i) => <li key={s.id}>{row(s, i)}</li>)}</ul>}
        <p className="mt-3 text-xs text-muted">{reorderable ? 'Drag to change priority, or use ⋯ → Raise / Lower priority. Higher sources are preferred when several offer the same item.' : 'Switch to “Priority (manual)” with “All” selected to drag sources into a new order.'}</p>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="ghost" icon={FileJson} onClick={() => { saveJson(SAMPLE_MANIFEST, 'debridone-sample.manifest.json'); toast('Sample manifest saved — edit it, then use Import manifest', 'success'); }}>Download sample manifest</Button>
          <Button variant="ghost" icon={Download} onClick={() => { saveJson({ debridoneSources: 1, sources: sources.map(toManifest) }, 'debridone-sources.json'); toast('Exported every source (no credentials included)', 'success'); }}>Export all sources</Button>
          <Button variant="ghost" icon={RefreshCw} onClick={restoreBuiltins}>Restore built-in sources</Button>
        </div>
      </section>

      <Card className="mt-10" title="How user-added sources are kept safe" description="You never need anyone’s approval to add a source, so the app applies these checks automatically.">
        <ul className="grid gap-x-8 gap-y-2 text-[0.8125rem] text-muted sm:grid-cols-2">
          {['Manifests are validated field by field; unknown or malformed fields are rejected.', 'Nothing in a manifest is ever executed. Script-like fields cause the whole file to be refused.', 'Only https endpoints are accepted. Localhost and private-network addresses are blocked.', 'There is no server-side fetching: connection tests run inside the browser sandbox.', 'Executable downloads need explicit, per-file consent.', 'Credentials stay in memory for the current tab and are never exported.', 'Titles, descriptions and links from a source are treated as untrusted text.', 'User-added sources are always labelled “Unreviewed” — never verified or safe.'].map((t) => (
            <li key={t} className="flex gap-2"><ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />{t}</li>
          ))}
        </ul>
      </Card>

      <SourceModal open={!!editing} source={editing && editing !== 'new' ? editing : undefined} onClose={() => setEditing(null)} />
      <ItemsModal source={browsing} onClose={() => setBrowsing(null)} />
      <ManifestModal result={manifest} onClose={() => setManifest(null)} />
    </div>
  );
}
