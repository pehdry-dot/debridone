import { Cloud, Copy, Download, ExternalLink, KeyRound, LogOut, Play, RefreshCw, Trash2 } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { desktop } from '../adapters/desktop';
import {
  deleteDownload, deleteTorrent, isRdMagnet, listDownloads, listTorrents, playFile, rdConnect, rdDisconnect, startTorrent, unrestrict, unrestrictTorrent,
  TERMINAL_FAILURES, type RdLink, type RdTorrent, type RdUnrestricted,
} from '../adapters/realdebrid';
import { Badge, Button, Callout, Card, EmptyState, Field, PageHeader, ProgressBar, Segmented, Spinner } from '../components/ui';
import { useTab } from '../lib/hooks';
import { cn, fmtBytes, timeAgo } from '../lib/utils';
import { startDownload } from '../store/downloads';
import { useRd } from '../store/realdebrid';
import { confirmDialog, toast } from '../store/ui';

const STATUS_LABEL: Record<string, string> = {
  magnet_conversion: 'Reading torrent', waiting_files_selection: 'Choosing file', queued: 'Queued', downloading: 'Downloading',
  downloaded: 'Ready', error: 'Error', magnet_error: 'Invalid magnet', virus: 'Blocked by Real-Debrid', compressing: 'Packing', uploading: 'Preparing', dead: 'Dead torrent',
};
const isHttp = (s: string) => /^https?:\/\//i.test(s.trim());

const openUrl = (url: string) => desktop?.openExternal(url);

/** The desktop app's connection state, as a card on the Sources page. */
export function RealDebridSummary() {
  const status = useRd((s) => s.status);
  const checked = useRd((s) => s.checked);
  const refresh = useRd((s) => s.refresh);
  useEffect(() => { if (desktop) void refresh(); }, [refresh]);
  return (
    <Card title="Real-Debrid" description="Live account connection, handled by the DebridOne Mac app.">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0 text-[0.8125rem] text-muted">
          {!desktop ? 'Available in the DebridOne Mac app.'
            : !checked ? 'Checking…'
              : status?.connected && status.user ? <>Connected as <span className="font-medium text-fg">{status.user.username}</span>{status.user.premiumDays ? ` · ${status.user.premiumDays} days of premium left` : ' · free account'}</>
                : 'Not connected yet.'}
        </div>
        <Button size="sm" icon={Cloud} onClick={() => { window.location.hash = '#/debrid'; }}>Open Real-Debrid</Button>
      </div>
    </Card>
  );
}

function ConnectCard() {
  const refresh = useRd((s) => s.refresh);
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const user = await rdConnect(token);
      setToken('');
      toast(`Connected to Real-Debrid as ${user.username}`, 'success');
      await refresh();
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  };
  return (
    <div className="max-w-2xl space-y-6">
      <Card title="Connect your Real-Debrid account" description="DebridOne uses your Real-Debrid API token. Your account’s API page shows it.">
        <ol className="mb-5 list-decimal space-y-1.5 pl-5 text-[0.8125rem] leading-relaxed text-muted">
          <li>Open your API token page and copy the token shown there.</li>
          <li>Paste it below and press Connect. DebridOne checks it with Real-Debrid.</li>
          <li>The token is stored in your Mac’s Keychain. It stays inside the app and is not sent to any other service.</li>
        </ol>
        <div className="mb-4">
          <Button icon={ExternalLink} onClick={() => openUrl('https://real-debrid.com/apitoken')}>Open my API token page</Button>
        </div>
        <form className="flex flex-wrap items-end gap-2" onSubmit={submit}>
          <Field label="API token" className="min-w-[16rem] flex-1">
            <input className="field font-mono text-xs" type="password" autoComplete="off" spellCheck={false} value={token}
              onChange={(e) => { setToken(e.target.value); setError(null); }} placeholder="Paste your token" />
          </Field>
          <Button type="submit" variant="primary" icon={KeyRound} loading={busy} disabled={!token.trim()}>Connect</Button>
        </form>
        {error && <p className="mt-3 text-xs text-red-400" role="alert">{error}</p>}
      </Card>
      <Callout tone="info">Real-Debrid is a paid service. Premium accounts get full-speed downloads and streams. Only use it with content you are entitled to.</Callout>
    </div>
  );
}

function DirectResult({ result }: { result: RdUnrestricted }) {
  return (
    <div className="surface flex flex-wrap items-center gap-3 p-4">
      <div className="min-w-0 flex-1 basis-56">
        <p className="truncate text-sm font-medium">{result.filename}</p>
        <p className="text-xs text-muted">{fmtBytes(result.filesize)} · ready</p>
      </div>
      <Button size="sm" variant="primary" icon={Play} onClick={() => { void playFile(result.filename, result).catch((e: Error) => toast(e.message, 'error')); }}>Play</Button>
      <Button size="sm" icon={Download} onClick={() => { void startDownload({ title: result.filename, fileName: result.filename, source: 'Real-Debrid', kind: 'fetch', url: result.download, size: result.filesize }); }}>Download</Button>
      <Button size="sm" variant="ghost" icon={Copy} aria-label="Copy direct link" onClick={() => navigator.clipboard?.writeText(result.download).then(() => toast('Link copied', 'success'))} />
    </div>
  );
}

function AddTab({ onAdded }: { onAdded: () => void }) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<RdUnrestricted | null>(null);
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = value.trim();
    setBusy(true); setError(null); setResult(null);
    try {
      if (isRdMagnet(v)) {
        await startTorrent(v);
        setValue('');
        toast('Sent to Real-Debrid. Track it in Torrents.', 'success');
        onAdded();
      } else if (isHttp(v)) {
        const u = await unrestrict(v);
        setResult(u);
        setValue('');
      } else {
        throw new Error('Paste a magnet link, or a web link from a file host.');
      }
    } catch (err) {
      setError((err as Error).message);
    }
    setBusy(false);
  };
  return (
    <div className="max-w-3xl space-y-5">
      <Card title="Add a torrent or a link" description="Magnet links become torrents on Real-Debrid, which downloads them to the cloud. Web links from file hosts are unlocked to a direct link.">
        <form className="flex flex-wrap items-end gap-2" onSubmit={submit}>
          <Field label="Magnet link or web link" className="min-w-[18rem] flex-1">
            <input className="field font-mono text-xs" value={value} onChange={(e) => { setValue(e.target.value); setError(null); }}
              placeholder="magnet:?xt=urn:btih:…  or  https://…" spellCheck={false} />
          </Field>
          <Button type="submit" variant="primary" loading={busy} disabled={!value.trim()}>Add to Real-Debrid</Button>
        </form>
        {error && <p className="mt-3 text-xs text-red-400" role="alert">{error}</p>}
      </Card>
      {result && <DirectResult result={result} />}
    </div>
  );
}

function TorrentsTab({ reloadKey }: { reloadKey: number }) {
  const [items, setItems] = useState<RdTorrent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const load = () => listTorrents().then((list) => { setItems(list); setError(null); }).catch((e: Error) => setError(e.message));
  useEffect(() => { void load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [reloadKey]);
  const busyNow = items?.some((t) => t.status !== 'downloaded' && !TERMINAL_FAILURES.includes(t.status)) ?? false;
  useEffect(() => {
    if (!busyNow) return;
    const timer = setInterval(load, 8000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busyNow]);

  const play = async (t: RdTorrent) => {
    setBusy(t.id);
    try { const u = await unrestrictTorrent(t.id); await playFile(t.filename, u); }
    catch (e) { toast((e as Error).message, 'error'); }
    setBusy(null);
  };
  const download = async (t: RdTorrent) => {
    setBusy(t.id);
    try {
      const u = await unrestrictTorrent(t.id);
      await startDownload({ title: t.filename, fileName: u.filename, source: 'Real-Debrid', kind: 'fetch', url: u.download, size: u.filesize });
    } catch (e) { toast((e as Error).message, 'error'); }
    setBusy(null);
  };
  const remove = async (t: RdTorrent) => {
    if (!(await confirmDialog({ title: `Remove “${t.filename}”?`, body: 'It is removed from your Real-Debrid list. Files you already downloaded to this Mac are not affected.', confirmLabel: 'Remove', danger: true }))) return;
    try { await deleteTorrent(t.id); toast('Removed from Real-Debrid', 'success'); load(); }
    catch (e) { toast((e as Error).message, 'error'); }
  };

  if (error && !items) return <Callout tone="error" action={<Button size="sm" onClick={load}>Retry</Button>}>{error}</Callout>;
  if (!items) return <div className="flex justify-center py-16"><Spinner /></div>;
  if (!items.length) return <EmptyState icon={Cloud} title="No torrents yet" body="Add a magnet link on the Add tab and it will appear here while Real-Debrid works on it." />;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between text-xs text-muted">
        <span>{items.length} torrent{items.length === 1 ? '' : 's'}{busyNow ? ' · refreshing every few seconds' : ''}</span>
        <Button size="sm" variant="ghost" icon={RefreshCw} onClick={load}>Refresh</Button>
      </div>
      <ul className="space-y-3">
        {items.map((t) => {
          const ready = t.status === 'downloaded';
          const failed = TERMINAL_FAILURES.includes(t.status);
          return (
            <li key={t.id} className="surface p-4">
              <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
                <div className="min-w-0 flex-1 basis-64">
                  <p className="truncate text-sm font-medium">{t.filename}</p>
                  <p className="mt-0.5 text-xs text-muted">{fmtBytes(t.bytes)}{t.added ? ` · added ${timeAgo(Date.parse(t.added))}` : ''}</p>
                </div>
                <Badge tone={ready ? 'success' : failed ? 'error' : 'accent'}>{STATUS_LABEL[t.status] ?? t.status}</Badge>
                <div className="flex shrink-0 items-center gap-1.5">
                  {ready && <Button size="sm" variant="primary" icon={Play} loading={busy === t.id} onClick={() => play(t)}>Play</Button>}
                  {ready && <Button size="sm" icon={Download} disabled={busy === t.id} onClick={() => download(t)}>Download</Button>}
                  <Button size="sm" variant="ghost" icon={Trash2} aria-label={`Remove ${t.filename}`} onClick={() => remove(t)} />
                </div>
              </div>
              {!ready && !failed && (
                <div className="mt-3">
                  <ProgressBar value={Number(t.progress) || 0} />
                  <p className="mt-1.5 text-xs tabular-nums text-muted">{Math.round(Number(t.progress) || 0)}%{t.speed ? ` · ${fmtBytes(t.speed)}/s` : ''}{t.seeders ? ` · ${t.seeders} seeders` : ''}</p>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function DownloadsTab() {
  const [items, setItems] = useState<RdLink[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => listDownloads().then((list) => { setItems(list); setError(null); }).catch((e: Error) => setError(e.message));
  useEffect(() => { void load(); }, []);
  const remove = async (d: RdLink) => {
    if (!(await confirmDialog({ title: `Remove “${d.filename}”?`, body: 'It is removed from your Real-Debrid downloads list.', confirmLabel: 'Remove', danger: true }))) return;
    try { await deleteDownload(d.id); toast('Removed', 'success'); load(); }
    catch (e) { toast((e as Error).message, 'error'); }
  };
  if (error && !items) return <Callout tone="error" action={<Button size="sm" onClick={load}>Retry</Button>}>{error}</Callout>;
  if (!items) return <div className="flex justify-center py-16"><Spinner /></div>;
  if (!items.length) return <EmptyState icon={Download} title="No unlocked links yet" body="Links you unlock on the Add tab, or download from Torrents, are listed here." />;
  return (
    <ul className="space-y-3">
      {items.map((d) => (
        <li key={d.id} className="surface flex flex-wrap items-center gap-3 p-4">
          <div className="min-w-0 flex-1 basis-56">
            <p className="truncate text-sm font-medium">{d.filename}</p>
            <p className="text-xs text-muted">{fmtBytes(d.filesize)} · {d.host}</p>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            {d.streamable === 1 && <Button size="sm" variant="primary" icon={Play} onClick={() => { void playFile(d.filename, d).catch((e: Error) => toast(e.message, 'error')); }}>Play</Button>}
            <Button size="sm" icon={Download} onClick={() => { void startDownload({ title: d.filename, fileName: d.filename, source: 'Real-Debrid', kind: 'fetch', url: d.download, size: d.filesize }); }}>Download</Button>
            <Button size="sm" variant="ghost" icon={Copy} aria-label="Copy direct link" onClick={() => navigator.clipboard?.writeText(d.download).then(() => toast('Link copied', 'success'))} />
            <Button size="sm" variant="ghost" icon={Trash2} aria-label={`Remove ${d.filename}`} onClick={() => remove(d)} />
          </div>
        </li>
      ))}
    </ul>
  );
}

export default function DebridPage() {
  const status = useRd((s) => s.status);
  const checked = useRd((s) => s.checked);
  const refresh = useRd((s) => s.refresh);
  const [tab, setTab] = useTab<'add' | 'torrents' | 'downloads'>('add');
  const [reloadKey, setReloadKey] = useState(0);
  useEffect(() => { if (desktop) void refresh(); }, [refresh]);

  const disconnect = async () => {
    if (!(await confirmDialog({ title: 'Disconnect Real-Debrid?', body: 'DebridOne forgets your token on this Mac. Your Real-Debrid account and files are not changed.', confirmLabel: 'Disconnect', danger: true }))) return;
    try { await rdDisconnect(); await refresh(); toast('Disconnected from Real-Debrid'); }
    catch (e) { toast((e as Error).message, 'error'); }
  };

  return (
    <div className="page">
      <PageHeader title="Real-Debrid" icon={Cloud}
        subtitle="Torrents and file-host links, unlocked to fast direct links you can stream or save to your Mac.">
        {status?.connected && status.user && <Badge tone="accent">@{status.user.username}</Badge>}
      </PageHeader>

      {!desktop && (
        <Callout tone="desktop" title="Real-Debrid runs in the DebridOne Mac app">
          Browsers can’t reach Real-Debrid directly, so the connection is handled by the desktop app, which keeps your token in the macOS Keychain. Open the DebridOne app to connect.
        </Callout>
      )}

      {desktop && !checked && <div className="flex justify-center py-16"><Spinner /></div>}

      {desktop && checked && !status?.connected && <ConnectCard />}

      {desktop && checked && status?.connected && (
        <div className="space-y-5">
          {status.error && <Callout tone="warn" title="Real-Debrid could not confirm your account">{status.error}</Callout>}
          <Card>
            <div className="flex flex-wrap items-center gap-4">
              <span className={cn('flex h-11 w-11 items-center justify-center rounded-full', status.user?.premiumDays ? 'bg-accent/15 text-accent' : 'bg-line/10 text-muted')}><Cloud className="h-5 w-5" /></span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-semibold">{status.user?.username ?? 'Connected'}</p>
                <p className="text-xs text-muted">
                  {status.user?.type === 'premium' || status.user?.premiumDays
                    ? `Premium · ${status.user?.premiumDays ?? 0} day${status.user?.premiumDays === 1 ? '' : 's'} left${status.user?.expires ? ` (until ${new Date(status.user.expires).toLocaleDateString()})` : ''}`
                    : 'Free account · some links may be limited'}
                </p>
              </div>
              <Button size="sm" variant="ghost" icon={LogOut} onClick={disconnect}>Disconnect</Button>
            </div>
          </Card>
          <Segmented value={tab} onChange={setTab} options={[{ value: 'add', label: 'Add' }, { value: 'torrents', label: 'Torrents' }, { value: 'downloads', label: 'Unlocked links' }]} />
          {tab === 'add' && <AddTab onAdded={() => { setReloadKey((k) => k + 1); setTab('torrents'); }} />}
          {tab === 'torrents' && <TorrentsTab reloadKey={reloadKey} />}
          {tab === 'downloads' && <DownloadsTab />}
        </div>
      )}
    </div>
  );
}
