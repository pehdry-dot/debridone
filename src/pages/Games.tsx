import { ExternalLink, FileJson, FolderPlus, Gamepad2, HardDrive, History, Monitor, Plug, Plus, Timer, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { checkSourceUrl } from '../adapters/sources';
import { CollectionTile } from '../components/collections';
import { CatalogBrowser, ItemShelf, MediaGrid, Shelf, Thumb } from '../components/media';
import { Badge, Button, Callout, Card, EmptyState, Field, Modal, PageHeader, ProgressBar, Segmented } from '../components/ui';
import { useTab } from '../lib/hooks';
import { fmtBytes, fmtRuntime, hash, pickJson, saveJson, splitList, timeAgo, uid } from '../lib/utils';
import { byType, useCatalog } from '../store/catalog';
import { startDownload, useDownloads } from '../store/downloads';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem } from '../types';
import { ContinueCard, Hero } from './Home';
import { AllGamesBrowser } from '../components/GameBrowser';

type Tab = 'all' | 'discover' | 'mine' | 'installed' | 'recent' | 'collections' | 'downloads';

const clean = (v: unknown, max = 200) => (typeof v === 'string' ? v.replace(/<[^>]*>/g, '').trim().slice(0, max) : '');

/** Build a library entry from loosely structured, untrusted game metadata. Returns null when there is no usable title. */
function gameFromRecord(r: Record<string, unknown>): MediaItem | null {
  const title = clean(r.title ?? r.name, 120);
  if (!title) return null;
  const list = (v: unknown) => (Array.isArray(v) ? v.map((x) => clean(x, 40)).filter(Boolean).slice(0, 8) : typeof v === 'string' ? splitList(clean(v, 200)) : []);
  const site = clean(r.website ?? r.url, 300);
  const id = uid('game:u-');
  return {
    id, type: 'game', demo: false, title, description: clean(r.description, 1200) || 'Added manually.', releaseDate: clean(r.releaseDate ?? r.year, 10) || undefined,
    genres: list(r.genres), tags: ['added manually', ...list(r.tags)], creator: clean(r.developer, 80) || undefined, publisher: clean(r.publisher, 80) || undefined,
    license: 'Your record', addedAt: Date.now(), popularity: 50, palette: hash(id) % 16,
    game: { platforms: list(r.platforms).length ? list(r.platforms) : ['Windows'], website: site && !checkSourceUrl(site) ? site : undefined },
  };
}

const SAMPLE_IMPORT = [
  { title: 'My Indie Platformer', developer: 'Solo Dev', publisher: 'Self-published', genres: ['Platformer', 'Indie'], platforms: ['Windows', 'Linux'], releaseDate: '2024', website: 'https://example.com/my-indie-platformer', description: 'Example record. Replace with games you own.' },
  { title: 'Tabletop Night Collection', developer: 'Board Works', genres: ['Card', 'Party'], platforms: ['Windows', 'macOS'], releaseDate: '2022' },
];

function AddGameModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const blank = { title: '', developer: '', publisher: '', genres: '', platforms: 'Windows', releaseDate: '', website: '', description: '' };
  const [f, setF] = useState(blank);
  const siteError = f.website.trim() ? checkSourceUrl(f.website.trim()) : null;
  const bind = (k: keyof typeof blank) => ({ value: f[k], onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value }) });
  const save = () => {
    const game = gameFromRecord(f);
    if (!game || siteError) return;
    useStore.getState().addUserItem(game);
    toast(`Added “${game.title}” to My Games`, 'success');
    setF(blank); onClose();
    navigate(`/item/${game.id}`);
  };
  return (
    <Modal open={open} onClose={onClose} title="Add a game manually" subtitle="Creates a library record you can track playtime and notes against. Nothing is downloaded." size="md"
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!f.title.trim() || !!siteError} onClick={save}>Add game</Button></>}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Title *" className="sm:col-span-2"><input autoFocus className="field" maxLength={120} {...bind('title')} /></Field>
        <Field label="Developer"><input className="field" {...bind('developer')} /></Field>
        <Field label="Publisher"><input className="field" {...bind('publisher')} /></Field>
        <Field label="Genres" hint="Comma separated"><input className="field" placeholder="RPG, Strategy" {...bind('genres')} /></Field>
        <Field label="Platforms" hint="Comma separated"><input className="field" {...bind('platforms')} /></Field>
        <Field label="Release year"><input className="field" placeholder="2024" maxLength={10} {...bind('releaseDate')} /></Field>
        <Field label="Official website" hint={siteError ?? 'Optional. https:// only.'}><input className="field" placeholder="https://" {...bind('website')} /></Field>
        <Field label="Description" className="sm:col-span-2"><textarea className="field min-h-[4.5rem]" maxLength={1200} {...bind('description')} /></Field>
      </div>
    </Modal>
  );
}

export default function GamesPage() {
  const navigate = useNavigate();
  const catalog = useCatalog();
  const [tab, setTab] = useTab<Tab>('all');
  const [adding, setAdding] = useState(false);
  const saved = useStore((s) => s.saved);
  const gameState = useStore((s) => s.gameState);
  const collections = useStore((s) => s.collections);
  const downloads = useDownloads((s) => s.items);

  const games = useMemo(() => byType(catalog, 'game'), [catalog]);
  const popular = useMemo(() => [...games].sort((a, b) => b.popularity - a.popularity), [games]);
  const mine = games.filter((g) => saved[g.id]);
  const installed = games.filter((g) => gameState[g.id]?.installed);
  const recent = games.filter((g) => gameState[g.id]?.lastPlayed).sort((a, b) => (gameState[b.id].lastPlayed ?? 0) - (gameState[a.id].lastPlayed ?? 0));
  const ids = new Set(games.map((g) => g.id));
  const gameCollections = collections.filter((c) => c.itemIds.some((id) => ids.has(id)));
  const gameDownloads = downloads.filter((d) => d.itemId?.startsWith('game:'));
  const totalMinutes = Object.values(gameState).reduce((n, g) => n + g.playMinutes, 0);

  const importJson = async () => {
    try {
      const data = await pickJson<unknown>();
      if (data == null) return;
      const rows = (Array.isArray(data) ? data : (data as { games?: unknown[] }).games ?? []) as Record<string, unknown>[];
      if (!Array.isArray(rows) || !rows.length) throw new Error('Expected a JSON array of games (or an object with a “games” array).');
      const items = rows.slice(0, 200).map((r) => (r && typeof r === 'object' ? gameFromRecord(r) : null)).filter((g): g is MediaItem => !!g);
      if (!items.length) throw new Error('No entries with a title were found in that file.');
      items.forEach((g) => useStore.getState().addUserItem(g));
      toast(`Imported ${items.length} game record${items.length === 1 ? '' : 's'}`, 'success');
      setTab('mine');
    } catch (e) { toast((e as Error).message || 'That file is not valid JSON.', 'error'); }
  };

  return (
    <div className="page">
      <PageHeader title="Gaming" icon={Gamepad2} subtitle="Discover free and open-source games, keep a record of what you own, and track playtime."
        actions={<>
          <Button icon={Plus} onClick={() => setAdding(true)}>Add game</Button>
          <Button icon={Upload} onClick={importJson}>Import owned games (JSON)</Button>
        </>}>
        <div className="mt-5">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'all', label: 'Every game' },  { value: 'mine', label: `My Games (${mine.length})` }, { value: 'installed', label: `Installed (${installed.length})` },
            { value: 'recent', label: 'Recently Played' }, { value: 'collections', label: 'Collections' }, { value: 'downloads', label: 'Download History' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'all' && <AllGamesBrowser />}

      {tab === 'mine' && (mine.length ? <MediaGrid items={mine.sort((a, b) => saved[b.id] - saved[a.id])} /> : (
        <EmptyState icon={Gamepad2} title="No games in your library yet" body="Save a game from Discover, add one manually, or import a list of games you own."
          action={<><Button variant="primary" icon={Plus} onClick={() => setAdding(true)}>Add game</Button><Button icon={FileJson} onClick={() => { saveJson(SAMPLE_IMPORT, 'debridone-games-sample.json'); toast('Sample file saved — edit it, then use Import', 'success'); }}>Get sample JSON</Button></>} />
      ))}

      {tab === 'installed' && (
        <>
          <Callout tone="desktop" className="mb-5">“Installed” is a record you keep by hand on each game’s page. The browser prototype cannot see what is on your disk, and it never claims to install or launch anything.</Callout>
          {installed.length ? (
            <ul className="grid gap-3 md:grid-cols-2">
              {installed.map((g) => (
                <li key={g.id}><Link to={`/item/${g.id}`} className="surface flex items-center gap-3 p-3 transition-colors hover:bg-line/[0.04]">
                  <Thumb item={g} className="w-11" />
                  <span className="min-w-0 flex-1"><span className="block truncate text-[0.8125rem] font-semibold">{g.title}</span><span className="block truncate text-xs text-muted"><HardDrive className="mr-1 inline h-3 w-3" />{gameState[g.id].location || 'No location noted'}{gameState[g.id].version ? ` · v${gameState[g.id].version}` : ''}</span></span>
                  <Badge tone="success">Installed (record)</Badge>
                </Link></li>
              ))}
            </ul>
          ) : <EmptyState icon={HardDrive} title="No installation records" body="Open a game and switch on “Mark as installed” to keep track of where it lives." />}
        </>
      )}

      {tab === 'recent' && (recent.length ? (
        <ul className="surface divide-y divide-line/[0.06] px-3">
          {recent.map((g) => (
            <li key={g.id}><Link to={`/item/${g.id}`} className="flex items-center gap-3 py-2.5">
              <Thumb item={g} className="w-9" />
              <span className="min-w-0 flex-1"><span className="block truncate text-[0.8125rem] font-medium">{g.title}</span><span className="block text-xs text-muted">Last played {timeAgo(gameState[g.id].lastPlayed!)}</span></span>
              <span className="flex items-center gap-1.5 text-xs tabular-nums text-muted"><Timer className="h-3.5 w-3.5" />{fmtRuntime(gameState[g.id].playMinutes)}</span>
            </Link></li>
          ))}
        </ul>
      ) : <EmptyState icon={History} title="No play sessions logged" body="Log a session from any game’s page to see it here." />)}

      {tab === 'collections' && (
        <>
          <div className="mb-5 flex justify-end"><Button icon={FolderPlus} onClick={() => { const id = useStore.getState().createCollection('New game collection'); toast('Collection created', 'success'); navigate(`/collection/${id}`); }}>New game collection</Button></div>
          {gameCollections.length
            ? <div className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-4 gap-y-6">{gameCollections.map((c) => <CollectionTile key={c.id} collection={c} />)}</div>
            : <EmptyState icon={FolderPlus} title="No game collections yet" body="Use “Add to collection” on any game." />}
        </>
      )}

      {tab === 'downloads' && (gameDownloads.length ? (
        <>
          <ul className="space-y-3">
            {gameDownloads.map((d) => (
              <li key={d.id} className="surface p-4">
                <div className="mb-2 flex flex-wrap items-center justify-between gap-2"><p className="truncate text-sm font-medium">{d.title}</p><Badge tone={d.kind === 'simulated' ? 'warn' : 'accent'}>{d.kind === 'simulated' ? 'Simulated' : 'Real transfer'} · {d.status}</Badge></div>
                <ProgressBar value={d.status === 'completed' ? 100 : d.size ? (d.received / d.size) * 100 : 0} />
                <p className="mt-1.5 text-xs tabular-nums text-muted">{fmtBytes(d.received)} of {fmtBytes(d.size)} · {d.source}</p>
              </li>
            ))}
          </ul>
          <div className="mt-4"><Button onClick={() => navigate('/downloads')}>Open download manager</Button></div>
        </>
      ) : <EmptyState icon={Monitor} title="No game downloads" body="Game files always come from official sites. Queue a simulated install from Discover to see how the manager behaves." action={<Button onClick={() => navigate('/downloads')}>Open download manager</Button>} />)}

      <AddGameModal open={adding} onClose={() => setAdding(false)} />
      {tab === 'mine' && mine.length > 0 && (
        <Card className="mt-8" title="Import owned-game metadata" description="Bring a list of games you own as a JSON file. Only metadata is imported: title, developer, genres, platforms, release date and website.">
          <div className="flex flex-wrap gap-2"><Button icon={Upload} onClick={importJson}>Import JSON</Button><Button variant="ghost" icon={FileJson} onClick={() => { saveJson(SAMPLE_IMPORT, 'debridone-games-sample.json'); toast('Sample file saved', 'success'); }}>Download sample file</Button></div>
        </Card>
      )}
    </div>
  );
}
