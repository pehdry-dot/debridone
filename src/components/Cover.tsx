import { memo, useId, useState } from 'react';
import type { MediaItem } from '../types';
import { cn, hash, rng, safeImageUrl, yearOf } from '../lib/utils';

/*
 * Artwork. The sample library ships without any third-party images: every
 * cover and banner is original, drawn deterministically from the item's id so
 * it is stable across sessions. If the user sets custom artwork (upload or
 * https URL) that image is shown instead, with the generated art as fallback
 * when it fails to load.
 */

export const PALETTES: [string, string, string][] = [
  ['#8B5CF6', '#2A1463', '#F0ABFC'], ['#0EA5E9', '#0B2A5B', '#67E8F9'], ['#F97316', '#5F1608', '#FDE68A'], ['#10B981', '#053B2E', '#A7F3D0'],
  ['#EC4899', '#5A1238', '#FBCFE8'], ['#EAB308', '#5C330E', '#FEF08A'], ['#6366F1', '#17153F', '#C7D2FE'], ['#14B8A6', '#0E3C3A', '#99F6E4'],
  ['#EF4444', '#3A0909', '#FCA5A5'], ['#9CA3AF', '#131316', '#F4F4F5'], ['#F59E0B', '#172E4D', '#FDE68A'], ['#22D3EE', '#35075C', '#F0ABFC'],
  ['#84CC16', '#172904', '#ECFCCB'], ['#FB7185', '#1A2230', '#FECDD3'], ['#C084FC', '#0C1326', '#38BDF8'], ['#D6B98C', '#33221A', '#F5E6CC'],
];

export const Art = memo(function Art({ seed, palette, className, shade = true }: { seed: string; palette: number; className?: string; shade?: boolean }) {
  const uid = useId().replace(/:/g, '');
  const h = hash(seed);
  const r = rng(h);
  const [a, b, c] = PALETTES[((palette % PALETTES.length) + PALETTES.length) % PALETTES.length];
  const angle = Math.round(r() * 360);
  const o1 = { x: 15 + r() * 70, y: 5 + r() * 45, s: 34 + r() * 26 };
  const o2 = { x: 10 + r() * 80, y: 50 + r() * 45, s: 26 + r() * 30 };
  const motif = h % 6;
  const cx = 30 + r() * 40, cy = 28 + r() * 30;

  let shapes: JSX.Element | null = null;
  if (motif === 0) {
    shapes = <g fill="none" stroke={c} strokeWidth="0.6">{[10, 18, 27, 37, 48, 60].map((rad, i) => <circle key={i} cx={cx} cy={cy} r={rad} opacity={0.42 - i * 0.055} />)}</g>;
  } else if (motif === 1) {
    shapes = <g transform={`rotate(${-35 + r() * 20} 50 50)`}>{Array.from({ length: 9 }, (_, i) => <rect key={i} x={-30} y={i * 13 - 8 + r() * 4} width={160} height={1.5 + r() * 5} fill="#fff" opacity={0.05 + r() * 0.13} />)}</g>;
  } else if (motif === 2) {
    shapes = (
      <g>
        <circle cx={cx} cy={cy + 6} r={19} fill={c} opacity="0.92" />
        {[0, 1, 2, 3, 4].map((i) => <rect key={i} x={cx - 22} y={cy + 6 + i * 4.4} width={44} height={1 + i * 0.55} fill={b} />)}
        <g stroke={c} strokeWidth="0.35" opacity="0.35">{[62, 68, 75, 83, 92].map((y) => <line key={y} x1="0" x2="100" y1={y} y2={y} />)}</g>
      </g>
    );
  } else if (motif === 3) {
    const pt = () => `${(r() * 110 - 5).toFixed(1)},${(r() * 110 - 5).toFixed(1)}`;
    shapes = <g>{[0, 1, 2, 3, 4].map((i) => <polygon key={i} points={`${pt()} ${pt()} ${pt()}`} fill={i % 2 ? '#fff' : '#000'} opacity={i % 2 ? 0.09 : 0.16} />)}</g>;
  } else if (motif === 4) {
    const dots: JSX.Element[] = [];
    for (let y = 0; y < 11; y++) for (let x = 0; x < 11; x++) {
      const px = 5 + x * 9, py = 5 + y * 9;
      const d = Math.hypot(px - cx, py - cy) / 70;
      dots.push(<circle key={`${x}-${y}`} cx={px} cy={py} r={Math.max(0.3, 3.3 * (1 - d))} />);
    }
    shapes = <g fill={c} opacity="0.4">{dots}</g>;
  } else {
    shapes = (
      <g fill="none" stroke={c} strokeWidth="0.7" strokeLinecap="round">
        {[0, 1, 2, 3, 4].map((i) => { const y = 30 + i * 11, amp = 4 + r() * 7; return <path key={i} d={`M-5 ${y} C 20 ${y - amp}, 35 ${y + amp}, 55 ${y} S 90 ${y - amp}, 110 ${y}`} opacity={0.55 - i * 0.08} />; })}
      </g>
    );
  }

  return (
    <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice" className={cn('absolute inset-0 h-full w-full', className)} aria-hidden>
      <defs>
        <linearGradient id={`g${uid}`} gradientTransform={`rotate(${angle} 0.5 0.5)`}><stop offset="0" stopColor={b} /><stop offset="1" stopColor={a} /></linearGradient>
        <radialGradient id={`o${uid}`}><stop offset="0" stopColor={c} stopOpacity="0.75" /><stop offset="1" stopColor={c} stopOpacity="0" /></radialGradient>
        <radialGradient id={`p${uid}`}><stop offset="0" stopColor={a} stopOpacity="0.9" /><stop offset="1" stopColor={a} stopOpacity="0" /></radialGradient>
        <linearGradient id={`s${uid}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0.35" stopColor="#000" stopOpacity="0" /><stop offset="1" stopColor="#000" stopOpacity="0.72" /></linearGradient>
      </defs>
      <rect width="100" height="100" fill={`url(#g${uid})`} />
      <circle cx={o1.x} cy={o1.y} r={o1.s} fill={`url(#o${uid})`} />
      <circle cx={o2.x} cy={o2.y} r={o2.s} fill={`url(#p${uid})`} />
      {shapes}
      {shade && <rect width="100" height="100" fill={`url(#s${uid})`} />}
    </svg>
  );
});

function CoverText({ item }: { item: MediaItem }) {
  const year = yearOf(item.releaseDate);
  const base = 'absolute inset-0 flex flex-col text-white [text-shadow:0_1px_8px_rgb(0_0_0/0.55)]';
  switch (item.type) {
    case 'album':
      return (
        <div className={cn(base, 'justify-between p-[8cqw]')}>
          <span className="text-[5cqw] font-semibold uppercase tracking-[0.18em] opacity-80">{item.album?.kind ?? 'Album'}</span>
          <div>
            <p className="line-clamp-2 text-[10.5cqw] font-bold leading-[1.05] tracking-tight">{item.title}</p>
            <p className="mt-[1.5cqw] truncate text-[6cqw] font-medium opacity-85">{item.creator}</p>
          </div>
        </div>
      );
    case 'book':
      return (
        <div className={cn(base, 'items-center justify-between p-[9cqw] text-center')} style={{ fontFamily: 'Georgia, "Iowan Old Style", "Times New Roman", serif' }}>
          <div className="pointer-events-none absolute inset-[5cqw] rounded-[2cqw] border border-white/30" />
          <span className="mt-[4cqw] text-[5cqw] uppercase tracking-[0.2em] opacity-75">{year}</span>
          <p className="line-clamp-4 text-[11.5cqw] font-semibold leading-[1.12]">{item.title}</p>
          <p className="mb-[4cqw] line-clamp-2 text-[6.2cqw] italic opacity-90">{item.creator}</p>
        </div>
      );
    case 'manga':
      return (
        <div className={cn(base, 'justify-between p-[7cqw] pt-[17cqw]')}>
          <p className="line-clamp-3 -skew-x-6 text-[13cqw] font-black uppercase leading-[0.95] tracking-tight">{item.title}</p>
          <div className="flex items-end justify-between gap-2">
            <p className="truncate text-[5.6cqw] font-semibold opacity-90">{item.creator}</p>
            <span className="shrink-0 rounded-[1.5cqw] bg-white px-[2.5cqw] py-[0.8cqw] text-[5cqw] font-black text-black [text-shadow:none]">{item.manga?.direction === 'webtoon' ? 'TOON' : 'VOL.1'}</span>
          </div>
        </div>
      );
    case 'game':
      return (
        <div className={cn(base, 'justify-end p-[7cqw]')}>
          <div className="mb-[3cqw] h-[1.4cqw] w-[16cqw] rounded-full bg-white/90" />
          <p className="line-clamp-3 text-[11.5cqw] font-extrabold uppercase italic leading-[1] tracking-tight">{item.title}</p>
          <p className="mt-[2cqw] truncate text-[5.4cqw] font-medium uppercase tracking-[0.12em] opacity-80">{item.creator}</p>
        </div>
      );
    default:
      return (
        <div className={cn(base, 'justify-end p-[7cqw]')}>
          <p className="text-[5cqw] font-semibold uppercase tracking-[0.2em] opacity-80">{item.type === 'tv' ? 'Series' : year}</p>
          <p className="mt-[1.5cqw] line-clamp-3 text-[11cqw] font-bold uppercase leading-[1.02] tracking-[0.02em]">{item.title}</p>
          <p className="mt-[2cqw] truncate text-[5.4cqw] font-medium opacity-80">{item.creator}</p>
        </div>
      );
  }
}

/** Cover artwork for an item. Set an aspect ratio and width with `className`. */
export const Cover = memo(function Cover({ item, className, text = true }: { item: Pick<MediaItem, 'id' | 'type' | 'title' | 'creator' | 'cover' | 'palette' | 'releaseDate' | 'album' | 'manga'> & Partial<Pick<MediaItem, 'banner' | 'remote'>>; className?: string; text?: boolean }) {
  const [failed, setFailed] = useState<string[]>([]);
  // Remote titles (games especially) may only have wide artwork; try it before falling back to generated art.
  const src = [safeImageUrl(item.cover), item.remote ? safeImageUrl(item.banner) : undefined].find((u) => u && !failed.includes(u));
  const showImage = !!src;
  return (
    <div className={cn('relative isolate overflow-hidden bg-card [container-type:inline-size]', className)}>
      {showImage
        ? <img src={src} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" onError={() => setFailed((f) => [...f, src!])} className="absolute inset-0 h-full w-full object-cover" />
        : <><Art seed={item.id} palette={item.palette} />{text && <CoverText item={item as MediaItem} />}</>}
    </div>
  );
});

/** Wide banner artwork (hero areas, detail pages). */
export function Backdrop({ item, className }: { item: Pick<MediaItem, 'id' | 'banner' | 'palette'>; className?: string }) {
  const src = safeImageUrl(item.banner);
  const [failed, setFailed] = useState(false);
  return (
    <div className={cn('overflow-hidden', className)} aria-hidden>
      <div className="relative h-full w-full">
        {src && !failed
          ? <img src={src} alt="" referrerPolicy="no-referrer" onError={() => setFailed(true)} className="absolute inset-0 h-full w-full object-cover" />
          : <Art seed={`${item.id}:banner`} palette={item.palette} shade={false} />}
      </div>
    </div>
  );
}
