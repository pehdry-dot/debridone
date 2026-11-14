import { Download, FileJson, ImagePlus, Plus, RotateCcw, Search, Trash2, Undo2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { getMetaProvider, METADATA_PROVIDERS } from '../adapters/providers';
import { cn, imageToDataUrl, pickFiles, pickJson, safeImageUrl, saveJson, splitList } from '../lib/utils';
import { CREATOR_LABEL, PUBLISHER_LABEL } from '../pages/ItemDetail';
import { originalItem, resolveItem, useItem } from '../store/catalog';
import { confirmDialog, toast, useUi } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem, MetaFields } from '../types';
import { META_KEYS, TYPE_LABEL } from '../types';
import { Backdrop, Cover } from './Cover';
import { Badge, Button, Callout, Field, Modal, Segmented } from './ui';

type Tab = 'details' | 'artwork' | 'custom' | 'providers' | 'data';
const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
const FIELD_LABEL: Partial<Record<keyof MetaFields, string>> = {
  title: 'Title', altTitle: 'Alternate title', description: 'Description', releaseDate: 'Release date', genres: 'Genres', tags: 'Tags', cover: 'Cover artwork',
  banner: 'Banner artwork', creator: 'Creator', publisher: 'Publisher', rating: 'Rating', language: 'Language', collection: 'Collection', notes: 'Personal notes', custom: 'Custom fields',
};
const show = (v: unknown): string => (v == null || v === '' ? '—' : Array.isArray(v) ? v.join(', ') || '—' : typeof v === 'object' ? Object.entries(v as object).map(([k, x]) => `${k}: ${x}`).join('; ') || '—' : String(v).length > 90 ? `${String(v).slice(0, 90)}…` : String(v));
const pickMeta = (item: MediaItem): MetaFields => Object.fromEntries(META_KEYS.map((k) => [k, item[k]])) as unknown as MetaFields;

/** Coerce untrusted JSON into metadata fields; anything that is not the right shape is dropped. */
function coerceMeta(raw: unknown): Partial<MetaFields> {
  const src = (raw && typeof raw === 'object' ? ((raw as { item?: unknown }).item ?? raw) : {}) as Record<string, unknown>;
  const out: Partial<MetaFields> = {};
  const text = (v: unknown, max: number) => (typeof v === 'string' ? v.replace(/<[^>]*>/g, '').slice(0, max) : undefined);
  const list = (v: unknown) => (Array.isArray(v) ? v.map((x) => text(x, 60)).filter((x): x is string => !!x).slice(0, 30) : undefined);
  (['title', 'altTitle', 'creator', 'publisher', 'language', 'collection'] as const).forEach((k) => { const v = text(src[k], 200); if (v !== undefined) out[k] = v; });
  (['description', 'notes'] as const).forEach((k) => { const v = text(src[k], 4000); if (v !== undefined) out[k] = v; });
  const date = text(src.releaseDate, 10); if (date && /^\d{4}(-\d{2}(-\d{2})?)?$/.test(date)) out.releaseDate = date;
  if (list(src.genres)) out.genres = list(src.genres); if (list(src.tags)) out.tags = list(src.tags);
  if (typeof src.rating === 'number' && src.rating >= 0 && src.rating <= 10) out.rating = src.rating;
  (['cover', 'banner'] as const).forEach((k) => { const v = safeImageUrl(typeof src[k] === 'string' ? (src[k] as string) : undefined); if (v) out[k] = v; });
  if (src.custom && typeof src.custom === 'object' && !Array.isArray(src.custom)) out.custom = Object.fromEntries(Object.entries(src.custom as object).slice(0, 30).map(([k, v]) => [k.slice(0, 40), String(v).slice(0, 200)]));
  if (out.title !== undefined && !out.title.trim()) delete out.title;
  return out;
}

/**
 * Universal metadata editor (movies, TV, music, games, manga and books).
 * Edits are stored as a layer on top of the original record, so they persist,
 * always win over provider imports, and can be reverted field by field.
 */
export function MetadataEditor() {
  const id = useUi((s) => s.editItemId);
  const item = useItem(id ?? undefined);
  const overrides = useStore((s) => (id ? s.overrides[id] : undefined));
  const imported = useStore((s) => (id ? s.imported[id] : undefined));
  const providerOrder = useStore((s) => s.metaProviders);
  const rules = useStore((s) => s.metaRules);
  const pro = useStore((s) => s.proDemo);
  const fieldDefs = useStore((s) => s.customFieldDefs);
  const [tab, setTab] = useState<Tab>('details');
  const [draft, setDraft] = useState<MetaFields | null>(null);
  const [lists, setLists] = useState({ genres: '', tags: '' });
  const [urls, setUrls] = useState({ cover: '', banner: '' });
  const [newKey, setNewKey] = useState('');
  const [lookup, setLookup] = useState<{ providerId: string; loading: boolean; result?: Partial<MetaFields> | null; error?: string; choice: Record<string, 'provider' | 'keep'> } | null>(null);

  const original = useMemo(() => (id ? originalItem(id) : undefined), [id, item]); // eslint-disable-line react-hooks/exhaustive-deps
  /** What the item would look like with no manual edits: original + provider imports + rules. */
  const base = useMemo(() => (original ? pickMeta(resolveItem(original, { imported: useStore.getState().imported, metaProviders: providerOrder, metaRules: rules, overrides: {}, proDemo: pro })) : null),
    [original, imported, providerOrder, rules, pro]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!item) { setDraft(null); return; }
    const m = pickMeta(item);
    setDraft(m); setLists({ genres: m.genres.join(', '), tags: m.tags.join(', ') }); setUrls({ cover: '', banner: '' }); setTab('details'); setLookup(null); setNewKey('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const close = () => useUi.setState({ editItemId: null });
  if (!id || !item || !draft || !base || !original) return <Modal open={false} onClose={close} title="Edit metadata"><span /></Modal>;

  const patch = (p: Partial<MetaFields>) => setDraft({ ...draft, ...p });
  const edited = (k: keyof MetaFields) => !same(draft[k], base[k]);
  const editedKeys = META_KEYS.filter(edited);
  const reset = (k: keyof MetaFields) => {
    patch({ [k]: base[k] } as Partial<MetaFields>);
    if (k === 'genres' || k === 'tags') setLists((l) => ({ ...l, [k]: (base[k] as string[]).join(', ') }));
  };
  const save = () => {
    if (!draft.title.trim()) { toast('A title is required.', 'warn'); setTab('details'); return; }
    const diff = Object.fromEntries(editedKeys.map((k) => [k, draft[k] ?? (typeof base[k] === 'string' ? '' : undefined)])) as Partial<MetaFields>;
    useStore.getState().replaceOverride(id, diff);
    toast(editedKeys.length ? `Saved ${editedKeys.length} edited field${editedKeys.length === 1 ? '' : 's'}` : 'No manual edits — showing original metadata', 'success');
    close();
  };
  const restoreAll = async () => {
    if (!(await confirmDialog({ title: 'Restore original metadata?', body: 'This removes your manual edits and any provider imports for this item. The original record comes back exactly as it was.', confirmLabel: 'Restore original', danger: true }))) return;
    const s = useStore.getState();
    s.replaceOverride(id, {});
    Object.keys(s.imported[id] ?? {}).forEach((p) => s.setImported(id, p, null));
    const m = pickMeta(original);
    setDraft(m); setLists({ genres: m.genres.join(', '), tags: m.tags.join(', ') });
    toast('Original metadata restored', 'success');
  };

  const setArt = async (key: 'cover' | 'banner') => {
    const [file] = await pickFiles('image/*');
    if (!file) return;
    try { patch({ [key]: await imageToDataUrl(file, key === 'cover' ? 600 : 1400, 0.8) }); }
    catch (e) { toast((e as Error).message, 'error'); }
  };
  const applyUrl = (key: 'cover' | 'banner') => {
    const url = safeImageUrl(urls[key].trim());
    if (!url || url.startsWith('data:')) { toast('Enter a full https:// image address.', 'warn'); return; }
    patch({ [key]: url }); setUrls({ ...urls, [key]: '' });
  };

  const runLookup = async (providerId: string) => {
    const provider = getMetaProvider(providerId);
    if (!provider?.lookup) return;
    setLookup({ providerId, loading: true, choice: {} });
    try {
      const result = await provider.lookup({ ...item, ...draft });
      const choice: Record<string, 'provider' | 'keep'> = {};
      Object.keys(result ?? {}).forEach((k) => { choice[k] = edited(k as keyof MetaFields) ? 'keep' : 'provider'; });
      setLookup({ providerId, loading: false, result, choice });
    } catch (e) {
      setLookup({ providerId, loading: false, error: e instanceof TypeError ? 'The provider could not be reached. Check your connection and try again.' : (e as Error).message, choice: {} });
    }
  };
  const applyLookup = () => {
    if (!lookup?.result) return;
    const accepted = Object.fromEntries(Object.entries(lookup.result).filter(([k]) => lookup.choice[k] === 'provider')) as Partial<MetaFields>;
    if (!Object.keys(accepted).length) { toast('Nothing selected to import.', 'info'); return; }
    useStore.getState().setImported(id, lookup.providerId, { ...(imported?.[lookup.providerId] ?? {}), ...accepted });
    setDraft({ ...draft, ...accepted });
    if (accepted.genres || accepted.tags) setLists((l) => ({ genres: (accepted.genres ?? draft.genres).join(', '), tags: (accepted.tags ?? draft.tags).join(', ') }));
    toast(`Imported ${Object.keys(accepted).length} field${Object.keys(accepted).length === 1 ? '' : 's'} from ${getMetaProvider(lookup.providerId)?.name}`, 'success');
    setLookup(null);
  };

  const importJson = async () => {
    try {
      const data = await pickJson();
      if (data == null) return;
      const incoming = coerceMeta(data);
      const keys = Object.keys(incoming) as (keyof MetaFields)[];
      if (!keys.length) throw new Error('No recognisable metadata fields were found in that file.');
      const conflicts = keys.filter((k) => edited(k) && !same(incoming[k], draft[k]));
      let useFile = true;
      if (conflicts.length) {
        useFile = await confirmDialog({ title: `${conflicts.length} field${conflicts.length === 1 ? '' : 's'} conflict with your edits`, body: `You have already edited: ${conflicts.map((k) => FIELD_LABEL[k]).join(', ')}. Use the values from the file instead?`, confirmLabel: 'Use file values', cancelLabel: 'Keep my edits' });
      }
      const applied = Object.fromEntries(keys.filter((k) => useFile || !conflicts.includes(k)).map((k) => [k, incoming[k]])) as Partial<MetaFields>;
      const next = { ...draft, ...applied };
      setDraft(next); setLists({ genres: next.genres.join(', '), tags: next.tags.join(', ') });
      toast(`Loaded ${Object.keys(applied).length} field${Object.keys(applied).length === 1 ? '' : 's'} into the editor. Press Save to keep them.`, 'success');
    } catch (e) { toast((e as Error).message || 'That file is not valid JSON.', 'error'); }
  };

  const customDefs = fieldDefs.filter((d) => !d.types.length || d.types.includes(item.type));
  const custom = draft.custom ?? {};
  const customKeys = [...new Set([...(pro ? customDefs.map((d) => d.name) : []), ...Object.keys(custom)])];
  const providers = providerOrder.map((p) => ({ ...p, def: getMetaProvider(p.id) })).filter((p) => p.def && p.def.types.includes(item.type));
  const mark = (k: keyof MetaFields) => edited(k) ? (
    <button type="button" onClick={() => reset(k)} className="ml-2 inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-1.5 py-px text-[0.625rem] font-semibold text-amber-400 hover:bg-amber-500/25" title="Revert this field"><Undo2 className="h-2.5 w-2.5" />Edited · revert</button>
  ) : null;
  const L = (k: keyof MetaFields, label = FIELD_LABEL[k]!) => <span className="mb-1.5 flex items-center text-xs font-medium text-muted">{label}{mark(k)}</span>;

  return (
    <Modal open onClose={close} title="Edit metadata" size="lg"
      subtitle={<>{item.title} · {TYPE_LABEL[item.type]}{editedKeys.length > 0 && <Badge tone="warn" className="ml-2">{editedKeys.length} unsaved or saved edit{editedKeys.length === 1 ? '' : 's'}</Badge>}</>}
      footer={<>
        <Button variant="ghost" icon={RotateCcw} onClick={restoreAll} disabled={!overrides && !imported && !editedKeys.length} className="mr-auto">Restore original</Button>
        <Button variant="ghost" onClick={close}>Cancel</Button>
        <Button variant="primary" onClick={save}>Save changes</Button>
      </>}>
      <Segmented className="mb-5" value={tab} onChange={setTab} options={[{ value: 'details', label: 'Details' }, { value: 'artwork', label: 'Artwork' }, { value: 'custom', label: 'Custom fields' }, { value: 'providers', label: 'Providers' }, { value: 'data', label: 'Import / export' }]} />

      {tab === 'details' && (
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block sm:col-span-2">{L('title')}<input className="field" value={draft.title} onChange={(e) => patch({ title: e.target.value })} maxLength={200} /></label>
          <label className="block">{L('altTitle')}<input className="field" value={draft.altTitle ?? ''} onChange={(e) => patch({ altTitle: e.target.value })} maxLength={200} /></label>
          <label className="block">{L('releaseDate')}<input className="field" placeholder="YYYY or YYYY-MM-DD" value={draft.releaseDate ?? ''} onChange={(e) => patch({ releaseDate: e.target.value.replace(/[^\d-]/g, '').slice(0, 10) })} /></label>
          <label className="block sm:col-span-2">{L('description')}<textarea className="field min-h-[6.5rem]" value={draft.description} onChange={(e) => patch({ description: e.target.value })} maxLength={4000} /></label>
          <label className="block">{L('creator', CREATOR_LABEL[item.type])}<input className="field" value={draft.creator ?? ''} onChange={(e) => patch({ creator: e.target.value })} maxLength={200} /></label>
          <label className="block">{L('publisher', PUBLISHER_LABEL[item.type])}<input className="field" value={draft.publisher ?? ''} onChange={(e) => patch({ publisher: e.target.value })} maxLength={200} /></label>
          <label className="block">{L('genres', 'Genres (comma separated)')}<input className="field" value={lists.genres} onChange={(e) => { setLists({ ...lists, genres: e.target.value }); patch({ genres: splitList(e.target.value) }); }} /></label>
          <label className="block">{L('tags', 'Tags (comma separated)')}<input className="field" value={lists.tags} onChange={(e) => { setLists({ ...lists, tags: e.target.value }); patch({ tags: splitList(e.target.value) }); }} /></label>
          <label className="block">{L('rating', 'Rating (0–10)')}<input className="field" type="number" min={0} max={10} step={0.1} value={draft.rating ?? ''} onChange={(e) => patch({ rating: e.target.value === '' ? undefined : Math.min(10, Math.max(0, Number(e.target.value))) })} /></label>
          <label className="block">{L('language')}<input className="field" value={draft.language ?? ''} onChange={(e) => patch({ language: e.target.value })} maxLength={80} /></label>
          <label className="block sm:col-span-2">{L('collection', 'Collection / series name')}<input className="field" placeholder="e.g. Blender Open Movies" value={draft.collection ?? ''} onChange={(e) => patch({ collection: e.target.value })} maxLength={120} /></label>
          <label className="block sm:col-span-2">{L('notes')}<textarea className="field min-h-[4.5rem]" value={draft.notes ?? ''} onChange={(e) => patch({ notes: e.target.value })} maxLength={4000} /></label>
        </div>
      )}

      {tab === 'artwork' && (
        <div className="grid gap-6 sm:grid-cols-[11rem_minmax(0,1fr)]">
          <div>
            {L('cover')}
            <Cover item={{ ...item, ...draft }} className={cn('w-full rounded-card ring-1 ring-inset ring-line/10', item.type === 'album' ? 'aspect-square' : 'aspect-[2/3]')} />
            <div className="mt-3 flex flex-wrap gap-2"><Button size="sm" icon={ImagePlus} onClick={() => setArt('cover')}>Upload</Button>{draft.cover && <Button size="sm" variant="ghost" icon={Trash2} onClick={() => patch({ cover: undefined })}>Remove</Button>}</div>
            <div className="mt-2 flex gap-1.5"><input className="field h-8 py-0 text-xs" placeholder="https://…/cover.jpg" value={urls.cover} onChange={(e) => setUrls({ ...urls, cover: e.target.value })} aria-label="Cover image URL" /><Button size="sm" onClick={() => applyUrl('cover')} disabled={!urls.cover.trim()}>Use</Button></div>
          </div>
          <div>
            {L('banner')}
            <Backdrop item={{ ...item, ...draft }} className="aspect-[16/6] w-full rounded-card ring-1 ring-inset ring-line/10" />
            <div className="mt-3 flex flex-wrap gap-2"><Button size="sm" icon={ImagePlus} onClick={() => setArt('banner')}>Upload</Button>{draft.banner && <Button size="sm" variant="ghost" icon={Trash2} onClick={() => patch({ banner: undefined })}>Remove</Button>}</div>
            <div className="mt-2 flex max-w-md gap-1.5"><input className="field h-8 py-0 text-xs" placeholder="https://…/banner.jpg" value={urls.banner} onChange={(e) => setUrls({ ...urls, banner: e.target.value })} aria-label="Banner image URL" /><Button size="sm" onClick={() => applyUrl('banner')} disabled={!urls.banner.trim()}>Use</Button></div>
            <Callout tone="info" className="mt-5">Uploads are resized and stored in this browser. Remote images must be https and are loaded without sending a referrer. With no custom artwork, DebridOne draws original cover art for the item.</Callout>
          </div>
        </div>
      )}

      {tab === 'custom' && (
        <div>
          <p className="mb-3 text-[0.8125rem] text-muted">Add any extra facts you want to track. They appear in the item’s Details panel and are searchable.{mark('custom')}</p>
          <div className="space-y-2">
            {customKeys.map((k) => (
              <div key={k} className="flex items-center gap-2">
                <span className="w-2/5 truncate rounded-btn bg-line/[0.06] px-3 py-2 text-[0.8125rem] font-medium">{k}{customDefs.some((d) => d.name === k) && <Badge className="ml-2">Defined in Pro</Badge>}</span>
                <input className="field flex-1" placeholder="Value" value={custom[k] ?? ''} onChange={(e) => patch({ custom: { ...custom, [k]: e.target.value } })} aria-label={`Value for ${k}`} maxLength={200} />
                <Button size="sm" variant="ghost" icon={Trash2} aria-label={`Remove ${k}`} onClick={() => { const { [k]: _, ...rest } = custom; void _; patch({ custom: rest }); }} />
              </div>
            ))}
            {!customKeys.length && <p className="rounded-btn border border-dashed border-line/10 px-4 py-6 text-center text-[0.8125rem] text-muted">No custom fields on this item yet.</p>}
          </div>
          <form className="mt-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); const k = newKey.trim(); if (!k || k in custom) return; patch({ custom: { ...custom, [k]: '' } }); setNewKey(''); }}>
            <input className="field max-w-xs" placeholder="New field name, e.g. “Watched with”" value={newKey} onChange={(e) => setNewKey(e.target.value)} maxLength={40} aria-label="New custom field name" />
            <Button type="submit" icon={Plus} disabled={!newKey.trim() || newKey.trim() in custom}>Add field</Button>
          </form>
        </div>
      )}

      {tab === 'providers' && (
        <div>
          <p className="mb-3 text-[0.8125rem] text-muted">Look this item up in a metadata provider. Providers are tried in the priority order set in Settings → Metadata. Your manual edits always win unless you choose otherwise below.</p>
          <ul className="space-y-2">
            {providers.map(({ id: pid, enabled, def }) => (
              <li key={pid} className="flex flex-wrap items-center gap-3 rounded-btn border border-line/[0.08] px-3 py-2.5">
                <div className="min-w-0 flex-1 basis-56">
                  <p className="flex flex-wrap items-center gap-2 text-[0.8125rem] font-medium">{def!.name}<Badge tone={def!.kind === 'live' ? 'success' : def!.kind === 'demo' ? 'warn' : 'default'}>{def!.kind === 'live' ? 'Live API' : def!.kind === 'demo' ? 'Demo data' : 'Needs backend'}</Badge>{imported?.[pid] && <Badge tone="accent">{Object.keys(imported[pid]).length} imported</Badge>}</p>
                  <p className="mt-0.5 text-xs text-muted">{def!.note}</p>
                </div>
                {imported?.[pid] && <Button size="sm" variant="ghost" icon={Trash2} onClick={() => { useStore.getState().setImported(id, pid, null); toast(`Removed ${def!.name} import. Close and reopen the editor to see the original values.`, 'info'); close(); }}>Remove import</Button>}
                <Button size="sm" icon={Search} disabled={!def!.lookup || !enabled} loading={lookup?.providerId === pid && lookup.loading} onClick={() => runLookup(pid)}>{!def!.lookup ? 'Not configured' : enabled ? 'Look up' : 'Disabled'}</Button>
              </li>
            ))}
            {!providers.length && <li className="text-[0.8125rem] text-muted">No providers cover this media type.</li>}
          </ul>
          {lookup && !lookup.loading && (
            <div className="mt-5">
              {lookup.error ? <Callout tone="error">{lookup.error}</Callout> : !lookup.result || !Object.keys(lookup.result).length ? <Callout tone="info">{getMetaProvider(lookup.providerId)?.name} has no match for this item.</Callout> : (
                <>
                  <p className="label mb-2">Resolve differences</p>
                  <div className="overflow-x-auto rounded-btn border border-line/[0.08]">
                    <table className="w-full min-w-[34rem] text-left text-xs">
                      <thead className="bg-line/[0.05] text-muted"><tr><th className="px-3 py-2 font-medium">Field</th><th className="px-3 py-2 font-medium">Current</th><th className="px-3 py-2 font-medium">{getMetaProvider(lookup.providerId)?.name}</th><th className="px-3 py-2 font-medium">Use</th></tr></thead>
                      <tbody className="divide-y divide-line/[0.06]">
                        {(Object.keys(lookup.result) as (keyof MetaFields)[]).map((k) => {
                          const identical = same(lookup.result![k], draft[k]);
                          return (
                            <tr key={k} className={identical ? 'opacity-50' : undefined}>
                              <td className="px-3 py-2 font-medium">{FIELD_LABEL[k]}{edited(k) && <Badge tone="warn" className="ml-1.5">Your edit</Badge>}</td>
                              <td className="max-w-[12rem] break-words px-3 py-2 text-muted">{k === 'cover' && draft.cover ? 'Custom image' : show(draft[k])}</td>
                              <td className="max-w-[12rem] break-words px-3 py-2">{k === 'cover' && lookup.result!.cover ? <img src={safeImageUrl(lookup.result!.cover)} alt="Provider cover" referrerPolicy="no-referrer" className="h-16 rounded" /> : show(lookup.result![k])}</td>
                              <td className="px-3 py-2">{identical ? 'Same' : (
                                <Segmented size="sm" value={lookup.choice[k]} onChange={(v) => setLookup({ ...lookup, choice: { ...lookup.choice, [k]: v } })} options={[{ value: 'keep', label: 'Keep mine' }, { value: 'provider', label: 'Provider' }]} />
                              )}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                  <div className="mt-3 flex justify-end gap-2"><Button size="sm" variant="ghost" onClick={() => setLookup(null)}>Discard</Button><Button size="sm" variant="primary" onClick={applyLookup}>Import selected fields</Button></div>
                </>
              )}
            </div>
          )}
        </div>
      )}

      {tab === 'data' && (
        <div className="space-y-5">
          <div className="flex flex-wrap gap-2">
            <Button icon={Download} onClick={() => { saveJson({ debridoneMetadata: 1, id, type: item.type, item: draft }, `${draft.title || 'item'} metadata.json`); toast('Metadata exported', 'success'); }}>Export this item (JSON)</Button>
            <Button icon={Upload} onClick={importJson}>Import JSON into editor</Button>
          </div>
          <div>
            <p className="label mb-2">Original record vs. current</p>
            {editedKeys.length || imported ? (
              <div className="overflow-x-auto rounded-btn border border-line/[0.08]">
                <table className="w-full min-w-[30rem] text-left text-xs">
                  <thead className="bg-line/[0.05] text-muted"><tr><th className="px-3 py-2 font-medium">Field</th><th className="px-3 py-2 font-medium">Original</th><th className="px-3 py-2 font-medium">Current</th><th className="px-3 py-2" /></tr></thead>
                  <tbody className="divide-y divide-line/[0.06]">
                    {META_KEYS.filter((k) => !same(original[k], draft[k])).map((k) => (
                      <tr key={k}>
                        <td className="px-3 py-2 font-medium">{FIELD_LABEL[k]}</td>
                        <td className="max-w-[13rem] break-words px-3 py-2 text-muted">{k === 'cover' || k === 'banner' ? (original[k] ? 'Image' : 'Generated art') : show(original[k])}</td>
                        <td className="max-w-[13rem] break-words px-3 py-2">{k === 'cover' || k === 'banner' ? (draft[k] ? 'Custom image' : 'Generated art') : show(draft[k])}</td>
                        <td className="px-3 py-2 text-right">{edited(k) ? <button type="button" className="text-accent hover:underline" onClick={() => reset(k)}>Revert</button> : <span className="text-muted">From provider or rule</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : <p className="flex items-center gap-2 text-[0.8125rem] text-muted"><FileJson className="h-4 w-4" />This item is showing its original metadata.</p>}
          </div>
          <Callout tone="info">The original record is never modified. Edits, provider imports and Pro metadata rules are stored as separate layers, which is why any of them can be undone at any time.</Callout>
        </div>
      )}
    </Modal>
  );
}
