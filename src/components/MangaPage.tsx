import { memo } from 'react';
import { hash, rng } from '../lib/utils';
import { PALETTES } from './Cover';

/*
 * Original placeholder comic pages.
 *
 * The demo manga series have no scanned artwork. Each page is drawn by this
 * component from a seed (series + chapter + page): a panel layout, simple
 * scenery, silhouettes, sound effects and speech bubbles. It is deliberately
 * abstract — enough to exercise every reader mode without using anyone's art.
 */

const LAYOUTS: number[][][] = [
  [[0, 0, 100, 38], [0, 40, 48, 28], [52, 40, 48, 28], [0, 70, 100, 30]],
  [[0, 0, 60, 45], [62, 0, 38, 45], [0, 47, 100, 22], [0, 71, 40, 29], [42, 71, 58, 29]],
  [[0, 0, 100, 30], [0, 32, 32, 36], [34, 32, 32, 36], [68, 32, 32, 36], [0, 70, 100, 30]],
  [[0, 0, 48, 60], [52, 0, 48, 28], [52, 30, 48, 30], [0, 62, 100, 38]],
  [[0, 0, 100, 72], [0, 74, 48, 26], [52, 74, 48, 26]],
  [[0, 0, 38, 34], [40, 0, 60, 34], [0, 36, 100, 30], [0, 68, 62, 32], [64, 68, 36, 32]],
];
const LINES = ['…', 'Did you hear that?', 'We’re late.', 'Not again!', 'Hold on—', 'It’s ticking.', 'Look up.', 'After you.', 'Hm.', 'Run!', 'That’s impossible.', 'One more time.', 'Trust me.', 'Too quiet…', 'Here it comes.', 'I knew it.', 'Wait for me!', 'So this is it.'];
const SFX = ['WHOOSH', 'CLANK', 'TICK', 'DON!', 'ZZZT', 'THUD', 'FWIP', 'KRAK'];

interface Props { series: string; chapter: number; page: number; total: number; title: string; chapterTitle: string; color?: boolean; palette?: number; className?: string }

export const PAGE_W = 800;
export const pageHeight = (color?: boolean) => (color ? 1400 : 1200);

export const MangaPage = memo(function MangaPage({ series, chapter, page, total, title, chapterTitle, color, palette = 0, className }: Props) {
  const H = pageHeight(color);
  const seed = hash(`${series}:${chapter}:${page}`);
  const r = rng(seed);
  const uid = `mp${seed.toString(36)}`;
  const [pa, pb, pc] = PALETTES[palette % PALETTES.length];
  const tones = color ? [pb, pa, pc, '#ffffff', '#111111'] : ['#1a1a1a', '#555555', '#9a9a9a', '#ffffff', '#111111'];
  const [dark, mid, light, paper, ink] = tones;
  const M = 40, IW = PAGE_W - M * 2, IH = H - M * 2 - 30;
  const isTitle = page === 0;
  const layout = isTitle ? [[0, 0, 100, 100]] : color ? [[0, 0, 100, 30], [0, 32, 100, 22], [0, 56, 100, 44]].map(([x, y, w, h]) => [x, y + (r() - 0.5) * 3, w, h]) : LAYOUTS[Math.floor(r() * LAYOUTS.length)];

  const panels = layout.map(([px, py, pw, ph], i) => {
    const x = M + (px / 100) * IW, y = M + (py / 100) * IH, w = (pw / 100) * IW, h = (ph / 100) * IH;
    const kind = isTitle ? 9 : Math.floor(r() * 6);
    const cx = x + w * (0.25 + r() * 0.5), cy = y + h * (0.3 + r() * 0.4);
    const parts: JSX.Element[] = [];
    const bg = [light, paper, mid, paper, light, dark][kind % 6];
    parts.push(<rect key="bg" x={x} y={y} width={w} height={h} fill={kind === 9 ? dark : bg} />);

    if (kind === 9) {
      // Chapter title splash
      for (let k = 0; k < 28; k++) { const a = (k / 28) * Math.PI * 2; parts.push(<line key={`l${k}`} x1={x + w / 2} y1={y + h * 0.42} x2={x + w / 2 + Math.cos(a) * w} y2={y + h * 0.42 + Math.sin(a) * h} stroke={paper} strokeWidth={k % 2 ? 1 : 3} opacity="0.14" />); }
      parts.push(<circle key="c" cx={x + w / 2} cy={y + h * 0.42} r={w * 0.2} fill={color ? pc : paper} opacity="0.92" />);
      parts.push(<rect key="hf" x={x} y={y} width={w} height={h} fill={`url(#${uid}d)`} opacity="0.25" />);
      parts.push(<text key="t1" x={x + w / 2} y={y + h * 0.72} textAnchor="middle" fontSize="30" fontWeight="700" letterSpacing="8" fill={paper} opacity="0.8">CHAPTER {chapter}</text>);
      parts.push(<text key="t2" x={x + w / 2} y={y + h * 0.79} textAnchor="middle" fontSize="52" fontWeight="900" fill={paper}>{chapterTitle.toUpperCase().slice(0, 22)}</text>);
      parts.push(<text key="t3" x={x + w / 2} y={y + h * 0.86} textAnchor="middle" fontSize="22" fontStyle="italic" fill={paper} opacity="0.75">{title}</text>);
    } else if (kind === 0) {
      // Mountains and moon
      parts.push(<circle key="m" cx={cx} cy={y + h * 0.3} r={Math.min(w, h) * 0.14} fill={paper} stroke={ink} strokeWidth="2" />);
      for (let k = 0; k < 3; k++) { const bx = x + r() * w * 0.6 - w * 0.1, bw = w * (0.5 + r() * 0.5), top = y + h * (0.35 + r() * 0.3); parts.push(<polygon key={`p${k}`} points={`${bx},${y + h} ${bx + bw / 2},${top} ${bx + bw},${y + h}`} fill={k === 1 ? dark : mid} stroke={ink} strokeWidth="2" />); }
      parts.push(<rect key="hf" x={x} y={y + h * 0.6} width={w} height={h * 0.4} fill={`url(#${uid}d)`} opacity="0.3" />);
    } else if (kind === 1) {
      // City blocks
      let bx = x - 10;
      for (let k = 0; bx < x + w; k++) { const bw = 36 + r() * 60, bh = h * (0.3 + r() * 0.55); parts.push(<rect key={`b${k}`} x={bx} y={y + h - bh} width={bw} height={bh} fill={k % 2 ? dark : mid} stroke={ink} strokeWidth="2" />); for (let wy = y + h - bh + 14; wy < y + h - 12; wy += 22) parts.push(<rect key={`w${k}-${wy}`} x={bx + 8} y={wy} width={bw - 16} height={6} fill={paper} opacity={r() > 0.4 ? 0.85 : 0.2} />); bx += bw + 4; }
    } else if (kind === 2) {
      // Speed lines and a figure
      for (let k = 0; k < 40; k++) { const a = r() * Math.PI * 2, d0 = Math.min(w, h) * (0.22 + r() * 0.1); parts.push(<line key={`s${k}`} x1={cx + Math.cos(a) * d0} y1={cy + Math.sin(a) * d0} x2={cx + Math.cos(a) * w} y2={cy + Math.sin(a) * w} stroke={ink} strokeWidth={0.6 + r() * 2.4} />); }
      const s = Math.min(w, h) * 0.11;
      parts.push(<path key="f" d={`M${cx - s * 1.2} ${cy + s * 2.6} L${cx - s * 0.7} ${cy + s * 0.6} Q${cx} ${cy + s * 0.2} ${cx + s * 0.7} ${cy + s * 0.6} L${cx + s * 1.2} ${cy + s * 2.6} Z`} fill={ink} />);
      parts.push(<circle key="h" cx={cx} cy={cy - s * 0.2} r={s * 0.7} fill={ink} />);
    } else if (kind === 3) {
      // Two figures talking
      [0.3, 0.7].forEach((fx, k) => { const px2 = x + w * fx, s = Math.min(w, h) * 0.13, base = y + h; parts.push(<path key={`f${k}`} d={`M${px2 - s * 1.5} ${base} L${px2 - s} ${base - s * 2.2} Q${px2} ${base - s * 2.8} ${px2 + s} ${base - s * 2.2} L${px2 + s * 1.5} ${base} Z`} fill={k ? dark : ink} />, <circle key={`h${k}`} cx={px2} cy={base - s * 3.4} r={s * 0.85} fill={k ? dark : ink} />); });
      parts.push(<rect key="hf" x={x} y={y} width={w} height={h * 0.5} fill={`url(#${uid}d)`} opacity="0.18" />);
    } else if (kind === 4) {
      // Halftone burst with a sound effect
      parts.push(<rect key="hf" x={x} y={y} width={w} height={h} fill={`url(#${uid}d)`} opacity="0.35" />);
      parts.push(<text key="sfx" x={x + w / 2} y={y + h / 2 + Math.min(w, h) * 0.1} textAnchor="middle" fontSize={Math.min(w / 4.2, h / 2)} fontWeight="900" fontStyle="italic" fill={paper} stroke={ink} strokeWidth="5" paintOrder="stroke" transform={`rotate(${-12 + r() * 24} ${x + w / 2} ${y + h / 2})`}>{SFX[Math.floor(r() * SFX.length)]}</text>);
    } else {
      // Gear / lantern on dark
      const rad = Math.min(w, h) * 0.26;
      for (let k = 0; k < 12; k++) { const a = (k / 12) * Math.PI * 2; parts.push(<rect key={`t${k}`} x={cx - 7} y={cy - rad - 14} width={14} height={20} fill={paper} transform={`rotate(${(a * 180) / Math.PI} ${cx} ${cy})`} />); }
      parts.push(<circle key="g1" cx={cx} cy={cy} r={rad} fill={paper} />, <circle key="g2" cx={cx} cy={cy} r={rad * 0.55} fill={dark} />, <circle key="g3" cx={cx} cy={cy} r={rad * 0.2} fill={paper} />);
    }

    // Speech bubble
    if (kind !== 9 && kind !== 4 && w > 150 && h > 120 && r() < 0.8) {
      const text = LINES[Math.floor(r() * LINES.length)];
      const words = text.split(' ');
      const lines = words.length > 2 ? [words.slice(0, Math.ceil(words.length / 2)).join(' '), words.slice(Math.ceil(words.length / 2)).join(' ')] : [text];
      const bw = Math.min(w * 0.6, Math.max(...lines.map((l) => l.length)) * 11 + 46), bh = lines.length * 26 + 26;
      const bx = x + (r() < 0.5 ? 16 : w - bw - 16), by = y + 14;
      parts.push(<ellipse key="bb" cx={bx + bw / 2} cy={by + bh / 2} rx={bw / 2} ry={bh / 2} fill="#fff" stroke="#111" strokeWidth="2.5" />);
      parts.push(<path key="bt" d={`M${bx + bw * 0.4} ${by + bh - 4} L${bx + bw * 0.45} ${by + bh + 18} L${bx + bw * 0.55} ${by + bh - 6}`} fill="#fff" stroke="#111" strokeWidth="2.5" strokeLinejoin="round" />);
      lines.forEach((l, k) => parts.push(<text key={`bl${k}`} x={bx + bw / 2} y={by + bh / 2 + (k - (lines.length - 1) / 2) * 24 + 7} textAnchor="middle" fontSize="19" fontWeight="600" fill="#111">{l}</text>));
    }
    return (
      <g key={i}>
        <clipPath id={`${uid}c${i}`}><rect x={x} y={y} width={w} height={h} /></clipPath>
        <g clipPath={`url(#${uid}c${i})`}>{parts}</g>
        <rect x={x} y={y} width={w} height={h} fill="none" stroke={ink} strokeWidth={color ? 0 : 4} />
      </g>
    );
  });

  return (
    <svg viewBox={`0 0 ${PAGE_W} ${H}`} className={className} role="img" aria-label={`${title}, chapter ${chapter}, page ${page + 1} of ${total}. Placeholder artwork.`}
      style={{ fontFamily: '"Arial Black", "Helvetica Neue", Arial, sans-serif', display: 'block' }}>
      <defs><pattern id={`${uid}d`} width="9" height="9" patternUnits="userSpaceOnUse"><circle cx="4.5" cy="4.5" r="2" fill={ink} /></pattern></defs>
      <rect width={PAGE_W} height={H} fill={color ? pb : '#f6f4ee'} />
      {panels}
      <text x={PAGE_W / 2} y={H - 26} textAnchor="middle" fontSize="15" fontWeight="400" fill={color ? '#fff' : '#666'} style={{ fontFamily: 'Arial, sans-serif' }}>{page + 1} / {total} · placeholder artwork · DebridOne demo</text>
    </svg>
  );
});
