import type { SongSpec } from '../types';
import { clamp, rng } from './utils';

/*
 * Tiny procedural music generator.
 *
 * DebridOne's demo music library contains no recordings. Each track is a
 * numeric spec (seed, tempo, key, style) that this module turns into a real
 * stereo WAV file in the browser, so the player, queue, seeking and downloads
 * can all be exercised with original, licence-free audio.
 */

const SR = 32000;
const TAIL = 2.5;

export const songDuration = (s: SongSpec) => Math.round((s.bars * 4 * 60) / s.bpm + TAIL);

const hz = (note: number) => 440 * Math.pow(2, (note - 69) / 12);
type Wave = 'sine' | 'tri' | 'soft' | 'saw' | 'pulse';

export function renderSong(spec: SongSpec): Blob {
  const beat = 60 / spec.bpm;
  const bar = beat * 4;
  const N = Math.floor((spec.bars * bar + TAIL) * SR);
  const L = new Float32Array(N), R = new Float32Array(N), D = new Float32Array(N); // melodic L/R + dry drum bus
  const rand = rng(spec.seed);
  let noiseState = (spec.seed ^ 0x9e3779b9) >>> 0;
  const noise = () => { noiseState = (Math.imul(noiseState, 1664525) + 1013904223) >>> 0; return noiseState / 2147483648 - 1; };

  const scale = spec.minor ? [0, 2, 3, 5, 7, 8, 10] : [0, 2, 4, 5, 7, 9, 11];
  const progs = spec.minor ? [[0, 5, 2, 6], [0, 3, 5, 4], [0, 5, 3, 6], [0, 2, 5, 4]] : [[0, 4, 5, 3], [0, 5, 3, 4], [0, 3, 4, 3], [0, 2, 3, 4]];
  const prog = progs[Math.floor(rand() * progs.length)];
  const deg = (d: number) => spec.root + scale[((d % 7) + 7) % 7] + 12 * Math.floor(d / 7);

  function tone(t0: number, dur: number, note: number, amp: number, wave: Wave, pan = 0, atk = 0.01, rel = 0.1, vib = 0) {
    const i0 = Math.max(0, Math.floor(t0 * SR));
    const sus = Math.floor(dur * SR), a = Math.max(1, Math.floor(atk * SR)), r = Math.max(1, Math.floor(rel * SR));
    const n = Math.min(sus + r, N - i0);
    const gl = amp * Math.cos(((pan + 1) * Math.PI) / 4), gr = amp * Math.sin(((pan + 1) * Math.PI) / 4);
    const inc = hz(note) / SR, TAU = Math.PI * 2;
    let ph = 0;
    for (let i = 0; i < n; i++) {
      let env = i < a ? i / a : 1;
      if (i > sus) env *= 1 - (i - sus) / r;
      ph += vib ? inc * (1 + vib * Math.sin((i / SR) * TAU * 5)) : inc;
      const p = ph - Math.floor(ph);
      let s: number;
      if (wave === 'sine') s = Math.sin(TAU * p);
      else if (wave === 'tri') s = 4 * Math.abs(p - 0.5) - 1;
      else if (wave === 'soft') s = 0.7 * Math.sin(TAU * p) + 0.24 * Math.sin(2 * TAU * p) + 0.1 * Math.sin(3 * TAU * p);
      else if (wave === 'saw') s = 0.6 * (Math.sin(TAU * p) + Math.sin(2 * TAU * p) / 2 + Math.sin(3 * TAU * p) / 3 + Math.sin(4 * TAU * p) / 4 + Math.sin(5 * TAU * p) / 5);
      else s = p < 0.5 ? 0.55 : -0.55;
      s *= env;
      L[i0 + i] += s * gl;
      R[i0 + i] += s * gr;
    }
  }
  function kick(t0: number, amp = 0.9) {
    const i0 = Math.floor(t0 * SR), n = Math.min(Math.floor(0.3 * SR), N - i0);
    let ph = 0;
    for (let i = 0; i < n; i++) {
      const t = i / SR;
      ph += (44 + 120 * Math.exp(-t * 30)) / SR;
      D[i0 + i] += Math.sin(Math.PI * 2 * ph) * Math.exp(-t * 10) * amp;
    }
  }
  /** colour: 1 = bright (hi-hat), 0 = dull (snare body / lo-fi) */
  function hit(t0: number, dur: number, amp: number, colour: number) {
    const i0 = Math.floor(t0 * SR), n = Math.min(Math.floor(dur * SR), N - i0);
    let prev = 0, lp = 0;
    for (let i = 0; i < n; i++) {
      const w = noise();
      const bright = w - prev; prev = w;
      lp += (w - lp) * 0.25;
      D[i0 + i] += (bright * colour + lp * (1 - colour) * 1.6) * Math.exp((-i / n) * 5) * amp;
    }
  }
  const snare = (t: number, amp = 0.32) => { hit(t, 0.16, amp, 0.45); tone(t, 0.04, 50, amp * 0.35, 'sine', 0, 0.001, 0.06); };

  // Melody: a random walk over scale degrees that snaps to chord tones on strong beats.
  let walk = 7 + Math.floor(rand() * 4);
  const nextNote = (chord: number, strong: boolean) => {
    walk = clamp(walk + [-2, -1, -1, 0, 1, 1, 2][Math.floor(rand() * 7)], 5, 15);
    if (strong) {
      const tones = [chord, chord + 2, chord + 4, chord + 7, chord + 9, chord + 11];
      walk = tones.reduce((best, t) => (Math.abs(t - walk) < Math.abs(best - walk) ? t : best), tones[0]);
    }
    return deg(walk) + 12;
  };
  const arpA = [0, 2, 4, 7, 4, 2, 0, 4], arpB = [0, 4, 7, 9, 7, 4, 2, 4];
  const style = spec.style;

  for (let b = 0; b < spec.bars; b++) {
    const t = b * bar;
    const d = prog[(style === 'ambient' ? Math.floor(b / 2) : b) % prog.length];
    const intro = b < 2, outro = b >= spec.bars - 2, sectionB = Math.floor(b / 8) % 2 === 1;
    const full = !intro && !outro;
    const chord = [deg(d), deg(d + 2), deg(d + 4)];

    if (style === 'ambient') {
      if (b % 2 === 0) {
        chord.forEach((n, i) => tone(t, bar * 2, n + 12, 0.075, 'soft', (i - 1) * 0.6, 1.4, 2.2, 0.002));
        tone(t, bar * 2, chord[0] + 24, 0.03, 'sine', 0, 2, 2.2);
        tone(t, bar * 2, chord[0] - 12, 0.13, 'sine', 0, 0.9, 1.6);
      }
      for (let k = 0; k < 4; k++) if (rand() < (intro ? 0.2 : 0.42)) tone(t + k * beat + rand() * 0.1, 0.05, deg(d + [0, 2, 4, 6, 7, 9][Math.floor(rand() * 6)]) + 24, 0.06, 'sine', rand() * 1.6 - 0.8, 0.004, 1.8);
    } else if (style === 'synthwave') {
      chord.forEach((n, i) => tone(t, bar * 0.98, n + 12, 0.045, 'saw', (i - 1) * 0.5, 0.06, 0.3));
      const pat = sectionB ? arpB : arpA;
      for (let k = 0; k < 16; k++) tone(t + (k * beat) / 4, beat / 5, deg(d + pat[k % 8]) + 24, intro ? 0.03 : 0.05, 'tri', k % 2 ? 0.5 : -0.5, 0.004, 0.14);
      if (!intro) for (let k = 0; k < 8; k++) tone(t + (k * beat) / 2, beat * 0.38, chord[0] - 12, 0.15, 'saw', 0, 0.004, 0.05);
      if (full && sectionB) for (let k = 0; k < 4; k++) if (k % 2 === 0 || rand() < 0.5) tone(t + k * beat, beat * 0.9, nextNote(d, k === 0), 0.085, 'soft', 0.1, 0.02, 0.25, 0.004);
      if (full) for (let k = 0; k < 8; k++) {
        if (k % 4 === 0) kick(t + (k * beat) / 2);
        if (k % 4 === 2) snare(t + (k * beat) / 2);
        hit(t + (k * beat) / 2, 0.04, 0.07, 1);
      }
    } else if (style === 'lofi') {
      const swing = beat * 0.09;
      const seventh = [...chord, deg(d + 6)];
      [[0, 1.5], [2.5, 1.2]].forEach(([at, len]) => seventh.forEach((n, i) => tone(t + at * beat, len * beat, n + 12, 0.06, 'tri', (i - 1.5) * 0.4, 0.03, 0.5)));
      tone(t, beat * 1.3, chord[0] - 12, 0.2, 'sine', 0, 0.01, 0.2);
      tone(t + 2.5 * beat, beat * 0.9, chord[0] - 12, 0.17, 'sine', 0, 0.01, 0.2);
      if (!intro) for (let k = 0; k < 8; k++) if (rand() < 0.46) tone(t + (k * beat) / 2 + (k % 2 ? swing : 0), beat * 0.45, nextNote(d, k % 4 === 0), 0.085, 'soft', -0.15, 0.01, 0.35, 0.003);
      if (full) {
        kick(t, 0.75); kick(t + 2.5 * beat, 0.6);
        hit(t + beat, 0.14, 0.2, 0.15); hit(t + 3 * beat, 0.14, 0.2, 0.15);
        for (let k = 0; k < 8; k++) hit(t + (k * beat) / 2 + (k % 2 ? swing : 0), 0.035, 0.035, 0.9);
      }
    } else if (style === 'chip') {
      for (let k = 0; k < 16; k++) tone(t + (k * beat) / 4, beat / 4.4, chord[k % 3] + 12, 0.032, 'pulse', 0.4, 0.002, 0.02);
      for (let k = 0; k < 8; k++) tone(t + (k * beat) / 2, beat * 0.42, (k % 2 ? chord[2] : chord[0]) - 12, 0.17, 'tri', 0, 0.003, 0.04);
      if (!intro) for (let k = 0; k < 8; k++) if (k % 2 === 0 || rand() < 0.55) tone(t + (k * beat) / 2, beat * (rand() < 0.3 ? 0.9 : 0.42), nextNote(d, k % 4 === 0) + (sectionB ? 12 : 0), 0.07, 'pulse', -0.3, 0.002, 0.05);
      if (full) for (let k = 0; k < 8; k++) {
        if (k % 4 === 0) hit(t + (k * beat) / 2, 0.09, 0.4, 0);
        if (k % 4 === 2) hit(t + (k * beat) / 2, 0.1, 0.22, 0.6);
        hit(t + (k * beat) / 2 + beat / 4, 0.02, 0.05, 1);
      }
    } else {
      // house
      [1, 2.5, 3.5].forEach((at) => chord.forEach((n, i) => tone(t + at * beat, beat * 0.3, n + 12, intro ? 0.035 : 0.055, 'saw', (i - 1) * 0.6, 0.004, 0.18)));
      if (!intro) for (let k = 0; k < 4; k++) tone(t + (k + 0.5) * beat, beat * 0.4, chord[0] - 12, 0.2, 'sine', 0, 0.005, 0.06);
      if (full && sectionB) for (let k = 0; k < 8; k++) if (rand() < 0.5) tone(t + (k * beat) / 2, beat * 0.3, nextNote(d, k % 4 === 0) + 12, 0.06, 'tri', 0.3, 0.003, 0.25);
      if (!intro && !outro) for (let k = 0; k < 4; k++) {
        kick(t + k * beat, 0.95);
        hit(t + (k + 0.5) * beat, 0.07, 0.09, 1);
        if (k % 2 === 1) hit(t + k * beat, 0.11, 0.2, 0.5);
      }
    }
  }

  // Ping-pong delay on the melodic bus only, then mix in the dry drums.
  const dl = Math.floor(beat * 0.75 * SR), fb = style === 'ambient' ? 0.42 : 0.26;
  for (let i = dl; i < N; i++) {
    const l = L[i] + R[i - dl] * fb, r = R[i] + L[i - dl] * fb;
    L[i] = l; R[i] = r;
  }
  if (style === 'lofi') for (let i = 0; i < N; i++) { const hiss = noise() * 0.004 + (rand() < 0.0004 ? noise() * 0.12 : 0); L[i] += hiss; R[i] += hiss; }

  let peak = 0;
  for (let i = 0; i < N; i++) {
    L[i] = Math.tanh((L[i] + D[i]) * 1.5);
    R[i] = Math.tanh((R[i] + D[i]) * 1.5);
    peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
  }
  const gain = peak > 0 ? 0.9 / peak : 1;
  const fadeIn = Math.floor(0.03 * SR), fadeOut = Math.floor(TAIL * SR);

  // 16-bit stereo PCM WAV
  const buf = new ArrayBuffer(44 + N * 4);
  const view = new DataView(buf);
  const str = (o: number, s: string) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); view.setUint32(4, 36 + N * 4, true); str(8, 'WAVE'); str(12, 'fmt ');
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 2, true);
  view.setUint32(24, SR, true); view.setUint32(28, SR * 4, true); view.setUint16(32, 4, true); view.setUint16(34, 16, true);
  str(36, 'data'); view.setUint32(40, N * 4, true);
  for (let i = 0; i < N; i++) {
    const f = gain * (i < fadeIn ? i / fadeIn : 1) * (i > N - fadeOut ? (N - i) / fadeOut : 1);
    view.setInt16(44 + i * 4, Math.round(L[i] * f * 32767), true);
    view.setInt16(46 + i * 4, Math.round(R[i] * f * 32767), true);
  }
  return new Blob([buf], { type: 'audio/wav' });
}

const blobCache = new Map<number, Blob>();
const urlCache = new Map<number, string>();

/** Render (or reuse) a track. Yields to the event loop first so a loading state can paint. */
export async function getSongBlob(spec: SongSpec): Promise<Blob> {
  const hit = blobCache.get(spec.seed);
  if (hit) return hit;
  await new Promise((r) => setTimeout(r, 20));
  const blob = renderSong(spec);
  blobCache.set(spec.seed, blob);
  return blob;
}
export async function getSongUrl(spec: SongSpec): Promise<string> {
  const hit = urlCache.get(spec.seed);
  if (hit) return hit;
  const url = URL.createObjectURL(await getSongBlob(spec));
  urlCache.set(spec.seed, url);
  return url;
}
