import { History } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useDateFormat } from '../lib/i18n';
import { getItem } from '../store/catalog';
import { useStore } from '../store/useStore';
import type { HistoryEntry, MediaType } from '../types';
import { TYPE_LABEL } from '../types';
import { Thumb } from './media';
import { Badge, EmptyState } from './ui';

const VERB: Record<HistoryEntry['kind'], string> = { watch: 'Watched', listen: 'Listened to', play: 'Played', read: 'Read' };

/** Viewing / listening / playing / reading history, optionally limited to some media types. */
export function HistoryList({ types, limit = 60 }: { types?: MediaType[]; limit?: number }) {
  const history = useStore((s) => s.history);
  const tracking = useStore((s) => s.settings.privacy.history);
  const fmt = useDateFormat();
  const state = useStore.getState();
  const rows = history.map((h) => ({ h, item: getItem(h.id, state) })).filter((r) => r.item && (!types || types.includes(r.item.type))).slice(0, limit);
  if (!rows.length) {
    return <EmptyState icon={History} title="No history yet" body={tracking ? 'Things you watch, hear, play and read will show up here.' : 'History is turned off in Settings → Privacy.'} />;
  }
  return (
    <ul className="surface divide-y divide-line/[0.06] px-3">
      {rows.map(({ h, item }) => (
        <li key={`${h.id}-${h.ts}`}>
          <Link to={`/item/${h.id}`} className="flex items-center gap-3 py-2.5 hover:opacity-90">
            <Thumb item={item!} className="w-9" />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[0.8125rem] font-medium">{item!.title}</span>
              <span className="block truncate text-xs text-muted">{VERB[h.kind]}{h.detail ? ` · ${h.detail}` : ''}</span>
            </span>
            <Badge className="hidden sm:inline-flex">{TYPE_LABEL[item!.type]}</Badge>
            <span className="shrink-0 text-xs tabular-nums text-muted">{fmt(h.ts)}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
