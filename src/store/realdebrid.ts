import { create } from 'zustand';
import { desktop, type RdStatus } from '../adapters/desktop';
import { rdStatus } from '../adapters/realdebrid';

/** Live Real-Debrid connection for this session (the token itself stays in the desktop app). */
export const useRd = create<{ checked: boolean; status: RdStatus | null; refresh(): Promise<void> }>((set) => ({
  checked: false,
  status: null,
  refresh: async () => {
    if (!desktop) {
      set({ checked: true, status: { connected: false } });
      return;
    }
    try {
      set({ checked: true, status: await rdStatus() });
    } catch (e) {
      set({ checked: true, status: { connected: true, error: (e as Error).message } });
    }
  },
}));

/** True when the desktop app reports a working Real-Debrid connection. */
export const useRdReady = () => useRd((s) => !!s.status?.connected && !s.status.error);
