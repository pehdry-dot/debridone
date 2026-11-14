import { AlertTriangle, Ban, Check, Download, ExternalLink, FileDown, FolderOpen, Monitor, RotateCcw, Save, Settings, Sparkles, Trash2, X } from 'lucide-react';
import { useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { DESKTOP_ONLY, desktop } from '../adapters/desktop';
import { Badge, Button, Callout, Card, EmptyState, IconButton, PageHeader, ProgressBar, Segmented } from '../components/ui';
import { BOOK_TEXTS } from '../data';
import { DEMO_TRACKS } from '../data/music';
import { buildEpub } from '../lib/books';
import { useTab } from '../lib/hooks';
import { useDateFormat } from '../lib/i18n';
import { getSongBlob } from '../lib/synth';
import { cn, escapeHtml, fmtBytes } from '../lib/utils';
import { cancelDownload, clearFinished, isActive, removeDownload, retryDownload, saveDownload, startDownload, useDownloads, type DownloadItem, type NewDownload } from '../store/downloads';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';

const MDN = 'https://interactive-examples.mdn.mozilla.net/media';
const KIND_BADGE = {
  fetch: { tone: 'accent' as const, label: 'Real transfer' },
  generated: { tone: 'success' as const, label: 'Generated file' },
  simulated: { tone: 'warn' as const, label: 'Simulated' },
  browser: { tone: 'default' as const, label: 'Browser download' },
};

interface Sample { label: string; detail: string; badge: 'fetch' | 'generated' | 'simulated' | 'browser' | 'consent' | 'failure'; run: () => void }

function useSamples(): Sample[] {
  return useMemo(() => {
    const q = (d: NewDownload) => () => { void startDownload(d).then((id) => id && toast('Added to the queue')); };
    const track = DEMO_TRACKS[0];
    const guide = BOOK_TEXTS['field-guide'];
    return [
      { label: 'Flower clip · MP4', detail: '1.1 MB · CC0 · live byte progress', badge: 'fetch', run: q({ title: 'Flower (CC0 test clip)', fileName: 'flower.mp4', source: 'MDN CC0 sample media', kind: 'fetch', url: `${MDN}/cc0-videos/flower.mp4`, size: 1128375 }) },
      { label: 'Friday clip · MP4', detail: '0.5 MB · CC0 · live byte progress', badge: 'fetch', run: q({ title: 'Friday (CC0 test clip)', fileName: 'friday.mp4', source: 'MDN CC0 sample media', kind: 'fetch', url: `${MDN}/cc0-videos/friday.mp4`, size: 515198 }) },
      { label: 'T-Rex roar · MP3', detail: '39 KB · CC0 · finishes instantly', badge: 'fetch', run: q({ title: 'T-Rex roar (CC0)', fileName: 't-rex-roar.mp3', source: 'MDN CC0 sample media', kind: 'fetch', url: `${MDN}/cc0-audio/t-rex-roar.mp3`, size: 39868 }) },
      { label: `“${track.title}” · WAV`, detail: 'Original track rendered in your browser', badge: 'generated', run: q({ title: `${track.artist} — ${track.title}`, fileName: `${track.artist} - ${track.title}.wav`, source: 'DebridOne generated audio', kind: 'generated', itemId: track.albumId, generate: () => getSongBlob(track.spec!) }) },
      { label: 'Field Guide · EPUB', detail: 'Built from the demo book, then saved', badge: 'generated', run: q({ title: 'The DebridOne Field Guide (EPUB)', fileName: 'The DebridOne Field Guide.epub', source: 'Generated in browser', kind: 'generated', itemId: 'book:field-guide', generate: () => buildEpub('The DebridOne Field Guide', 'DebridOne', guide.chapters.map((c) => ({ title: c.title, html: c.paragraphs.map((p) => `<p>${escapeHtml(p)}</p>`).join('') }))) }) },
      { label: 'Tears of Steel · 720p', detail: '76 MB · CC BY · opens at the Internet Archive', badge: 'browser', run: () => { window.open('https://archive.org/download/Tears-of-Steel/tears_of_steel_720p.mp4', '_blank', 'noopener,noreferrer'); toast('Opened at the Internet Archive. That host doesn’t allow in-app transfers, so your browser handles it.', 'info'); } },
      { label: 'Desktop game install', detail: '3.5 GB · animated stand-in, no file is written', badge: 'simulated', run: q({ title: '0 A.D. — desktop install (demo)', fileName: '0ad installer', source: 'Open-source games index', kind: 'simulated', size: 3.5e9, itemId: 'game:0ad' }) },
      { label: 'Unreachable mirror', detail: 'Fails on purpose so you can try Retry', badge: 'failure', run: q({ title: 'Unreachable mirror (sample failure)', fileName: 'sample.bin', source: 'Example community manifest · unreviewed', kind: 'fetch', url: 'https://downloads.invalid/sample.bin' }) },
      { label: 'Executable consent check', detail: 'Shows the warning for .exe files, then fails (no real file)', badge: 'consent', run: q({ title: 'Executable consent demo', fileName: 'demo-setup.exe', source: 'Example community manifest · unreviewed', kind: 'fetch', url: 'https://downloads.invalid/demo-setup.exe' }) },
    ];
  }, []);
}

function Row({ d }: { d: DownloadItem }) {
  const fmt = useDateFormat();
  const pct = d.status === 'completed' ? 100 : d.size ? (d.received / d.size) * 100 : 0;
  const kind = KIND_BADGE[d.kind];
  const statusLabel = { queued: 'Queued', downloading: d.kind === 'generated' ? 'Generating…' : 'Downloading', completed: d.kind === 'browser' && !desktop ? 'Handed to browser' : 'Completed', failed: 'Failed', canceled: 'Canceled' }[d.status];
  const StatusIcon = { queued: Download, downloading: Download, completed: Check, failed: AlertTriangle, canceled: Ban }[d.status];
  return (
    <li className="surface p-4">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        <span className={cn('mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full', d.status === 'completed' ? 'bg-emerald-500/15 text-emerald-400' : d.status === 'failed' ? 'bg-red-500/15 text-red-400' : d.status === 'canceled' ? 'bg-line/10 text-muted' : 'bg-accent/15 text-accent')}>
          <StatusIcon className="h-4 w-4" />
        </span>
        <div className="min-w-0 flex-1 basis-56">
          <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
            {d.itemId ? <Link to={`/item/${d.itemId}`} className="truncate hover:underline">{d.title}</Link> : <span className="truncate">{d.title}</span>}
            <Badge tone={kind.tone}>{kind.label}</Badge>
          </p>
          <p className="mt-0.5 truncate text-xs text-muted">{d.fileName} · Source: {d.source}</p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {isActive(d) && <Button size="sm" icon={X} onClick={() => cancelDownload(d.id)}>Cancel</Button>}
          {(d.status === 'failed' || d.status === 'canceled') && <Button size="sm" variant="primary" icon={RotateCcw} onClick={() => retryDownload(d.id)}>Retry</Button>}
          {d.desktopPath && <Button size="sm" icon={FolderOpen} onClick={() => desktop?.downloads.reveal(d.desktopPath!)}>Show in Finder</Button>}
          {d.status === 'completed' && (d.kind === 'fetch' || d.kind === 'generated') && (
            d.inMemory
              ? <Button size="sm" icon={Save} onClick={() => { if (saveDownload(d.id)) toast(`Saving ${d.fileName}`, 'success'); }}>Save file</Button>
              : <Button size="sm" icon={RotateCcw} onClick={() => retryDownload(d.id)}>Download again</Button>
          )}
          <IconButton label={`Remove ${d.title} from the list`} icon={Trash2} size="sm" onClick={() => removeDownload(d.id)} />
        </div>
      </div>
      <div className="mt-3">
        <ProgressBar value={pct} tone={d.status === 'failed' ? 'error' : d.status === 'completed' ? 'success' : d.status === 'canceled' ? 'muted' : 'accent'} className="h-1.5" />
        <div className="mt-1.5 flex flex-wrap justify-between gap-x-4 text-xs tabular-nums text-muted">
          <span>{statusLabel}{d.status === 'downloading' && d.size ? ` · ${Math.round(pct)}%` : ''}{d.status === 'downloading' && d.speed ? ` · ${fmtBytes(d.speed)}/s` : ''}{d.finishedAt ? ` · ${fmt(d.finishedAt)}` : ''}</span>
          <span>{d.status === 'completed' ? fmtBytes(d.size) : `${fmtBytes(d.received)} of ${fmtBytes(d.size)}`}</span>
        </div>
        {d.error && <p className="mt-2 text-xs text-red-400">{d.error}</p>}
        {d.kind === 'simulated' && <p className="mt-2 text-xs text-amber-400/90">Simulated transfer for demonstration. No data is downloaded and nothing is installed — this needs the desktop app.</p>}
        {d.status === 'completed' && !desktop && (d.kind === 'fetch' || d.kind === 'generated') && !d.inMemory && <p className="mt-2 text-xs text-muted">This file was handed to your browser earlier. Browser tabs can’t keep files between visits.</p>}
        {d.kind === 'browser' && !desktop && <p className="mt-2 text-xs text-muted">This link was passed to your browser, which downloads it itself — check the browser’s own downloads list for progress. {d.url && <button type="button" className="text-accent hover:underline" onClick={() => window.open(d.url, '_blank', 'noopener,noreferrer')}>Open link again</button>}</p>}
      </div>
    </li>
  );
}

type Tab = 'all' | 'active' | 'completed' | 'failed';

export default function DownloadsPage() {
  const navigate = useNavigate();
  const items = useDownloads((s) => s.items);
  const concurrent = useStore((s) => s.settings.downloads.concurrent);
  const autoSave = useStore((s) => s.settings.downloads.autoSave);
  const [tab, setTab] = useTab<Tab>('all');
  const samples = useSamples();
  const active = items.filter(isActive), completed = items.filter((i) => i.status === 'completed'), failed = items.filter((i) => i.status === 'failed' || i.status === 'canceled');
  const list = tab === 'active' ? active : tab === 'completed' ? completed : tab === 'failed' ? failed : items;
  const sampleBadge = (b: Sample['badge']) => b === 'browser' ? <Badge icon={ExternalLink}>Browser-managed</Badge> : b === 'failure' ? <Badge tone="error">Fails on purpose</Badge> : b === 'consent' ? <Badge tone="warn">Consent prompt</Badge> : <Badge tone={KIND_BADGE[b].tone}>{KIND_BADGE[b].label}</Badge>;

  return (
    <div className="page">
      <PageHeader title="Downloads" icon={Download} subtitle={`Up to ${concurrent} at a time · finished files are ${autoSave ? 'saved automatically through your browser' : 'kept until you press Save file'}.`}
        actions={<>
          <Button icon={Settings} variant="ghost" onClick={() => navigate('/settings/downloads')}>Download settings</Button>
          <Button icon={Trash2} disabled={!items.some((i) => !isActive(i))} onClick={() => { clearFinished(); toast('Cleared finished downloads'); }}>Clear finished</Button>
        </>} />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_23rem]">
        <div className="min-w-0">
          <div className="mb-4">
            <Segmented value={tab} onChange={setTab} options={[
              { value: 'all', label: `All (${items.length})` }, { value: 'active', label: `In progress (${active.length})` },
              { value: 'completed', label: `Completed (${completed.length})` }, { value: 'failed', label: `Failed (${failed.length})` },
            ]} />
          </div>
          {list.length
            ? <ul className="space-y-3">{list.map((d) => <Row key={d.id} d={d} />)}</ul>
            : <EmptyState icon={FileDown} title={tab === 'all' ? 'No downloads yet' : 'Nothing in this list'} body="Queue one of the sample files to see progress, cancel and retry working." />}
        </div>

        <aside className="space-y-6">
          <Card title="Try a sample download" description="Every file here is CC0, openly licensed or created by the app itself.">
            <ul className="space-y-1.5">
              {samples.map((s) => (
                <li key={s.label}>
                  <button type="button" onClick={s.run} className="group flex w-full items-center gap-3 rounded-btn border border-line/[0.07] px-3 py-2.5 text-left transition-colors hover:border-accent/50 hover:bg-line/[0.04]">
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2 text-[0.8125rem] font-medium">{s.label}{sampleBadge(s.badge)}</span>
                      <span className="mt-0.5 block text-xs text-muted">{s.detail}</span>
                    </span>
                    <Download className="h-4 w-4 shrink-0 text-muted group-hover:text-accent" />
                  </button>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="What the three labels mean">
            <dl className="space-y-3 text-[0.8125rem]">
              <div><dt className="flex items-center gap-2 font-medium"><Download className="h-4 w-4 text-accent" />Real transfer</dt><dd className="mt-0.5 text-xs leading-relaxed text-muted">Fetched over the network by this page with live progress, cancel and retry. Only possible for hosts that allow browser access.</dd></div>
              <div><dt className="flex items-center gap-2 font-medium"><Sparkles className="h-4 w-4 text-emerald-400" />Generated file</dt><dd className="mt-0.5 text-xs leading-relaxed text-muted">A real file assembled in your browser (audio, EPUB, exports) and passed to its download prompt.</dd></div>
              <div><dt className="flex items-center gap-2 font-medium"><Monitor className="h-4 w-4 text-amber-400" />Simulated</dt><dd className="mt-0.5 text-xs leading-relaxed text-muted">An animated placeholder for something only a desktop app can do. Nothing is downloaded or installed.</dd></div>
            </dl>
          </Card>
          <Callout tone="desktop" title="Needs the future desktop app">
            <ul className="mt-1 list-disc space-y-0.5 pl-4">{DESKTOP_ONLY.map((d) => <li key={d}>{d}</li>)}</ul>
          </Callout>
        </aside>
      </div>
    </div>
  );
}
