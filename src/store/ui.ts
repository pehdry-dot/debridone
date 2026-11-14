import { create } from 'zustand';
import { uid } from '../lib/utils';
import { useStore } from './useStore';

/* Ephemeral UI state: toasts, confirm dialogs and globally reachable modals. Not persisted. */

export type Tone = 'info' | 'success' | 'warn' | 'error';
export interface Toast { id: string; message: string; tone: Tone; action?: { label: string; run: () => void } }
export interface ConfirmRequest {
  title: string; body?: string; confirmLabel?: string; cancelLabel?: string; danger?: boolean;
  /** when set, the user must tick a checkbox with this label before confirming */
  requireCheck?: string;
  resolve: (ok: boolean) => void;
}

interface UiState {
  toasts: Toast[];
  confirm: ConfirmRequest | null;
  editItemId: string | null;
  collectItemId: string | null;
  nowPlaying: boolean;
  queueOpen: boolean;
  mobileNav: boolean;
  auth: 'signup' | 'signin' | null;
  set(patch: Partial<UiState>): void;
  dismiss(id: string): void;
}

export const useUi = create<UiState>((set) => ({
  toasts: [], confirm: null, editItemId: null, collectItemId: null, nowPlaying: false, queueOpen: false, mobileNav: false, auth: null,
  set: (patch) => set(patch),
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}));

/** Transient feedback. Respects Settings → General → Notifications → In-app toasts. */
export function toast(message: string, tone: Tone = 'info', action?: Toast['action']) {
  if (!useStore.getState().settings.notify.toasts && tone !== 'error') return;
  const id = uid('t-');
  useUi.setState((s) => ({ toasts: [...s.toasts.slice(-3), { id, message, tone, action }] }));
  setTimeout(() => useUi.getState().dismiss(id), action ? 7000 : 4200);
}

export function confirmDialog(opts: Omit<ConfirmRequest, 'resolve'>): Promise<boolean> {
  return new Promise((resolve) => useUi.setState({ confirm: { ...opts, resolve } }));
}

export const openEditor = (itemId: string) => useUi.setState({ editItemId: itemId });
export const openCollector = (itemId: string) => useUi.setState({ collectItemId: itemId });
