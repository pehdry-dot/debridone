/*
 * Desktop bridge.
 *
 * The Mac app exposes `window.debridone` from its preload script (electron/preload.cjs).
 * In a normal browser it is absent, and every desktop-only feature says so.
 * The bridge is deliberately narrow: named actions only, never Node or the filesystem.
 */

export interface RdUser { username: string; type: string; premiumDays: number; expires?: string; points: number; email?: string }
export interface RdStatus { connected: boolean; user?: RdUser; error?: string }
export type DlEvent =
  | { id: string; type: 'progress'; received: number; total: number; speed: number }
  | { id: string; type: 'done'; path: string; received: number; total: number }
  | { id: string; type: 'error'; message: string }
  | { id: string; type: 'canceled' };

export interface DesktopBridge {
  isDesktop: true;
  platform: string;
  rd: {
    status(): Promise<RdStatus>;
    connect(token: string): Promise<RdUser>;
    disconnect(): Promise<void>;
    request(method: 'GET' | 'POST' | 'DELETE', path: string, params?: Record<string, unknown>): Promise<any>;
  };
  net: {
    fetchText(o: { url: string; headers?: Record<string, string>; timeoutMs?: number; maxBytes?: number; allowLocal?: boolean }):
      Promise<{ ok: boolean; status: number; contentType: string; text: string }>;
  };
  downloads: {
    start(o: { id: string; url: string; fileName: string }): Promise<{ id: string; path: string }>;
    cancel(id: string): Promise<void>;
    reveal(path: string): Promise<void>;
    onEvent(cb: (m: DlEvent) => void): () => void;
  };
  storage: {
    loadAll(): Promise<Record<string, string>>;
    set(key: string, value: string): Promise<void>;
    remove(key: string): Promise<void>;
  };
  openExternal(url: string): Promise<void>;
}

declare global {
  interface Window { debridone?: DesktopBridge }
}

export const desktop: DesktopBridge | null =
  typeof window !== 'undefined' && window.debridone?.isDesktop ? window.debridone : null;

export const isDesktop = () => !!desktop;

if (desktop && typeof document !== 'undefined') {
  document.documentElement.classList.add('is-desktop');
}

/** Electron prefixes IPC errors with the channel name; show only the message. */
export function ipc<T>(promise: Promise<T>): Promise<T> {
  return promise.catch((e: unknown) => {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(message.replace(/^Error invoking remote method '[^']+': (Error: )?/, ''));
  });
}

export const DESKTOP_ONLY = [
  'Choosing where downloads are saved (files go to your Downloads folder)',
  'Installing and launching native games',
  'Torrent downloading on this computer (Real-Debrid does it for you)',
];
