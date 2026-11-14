/** Hand a playable URL (a Real-Debrid link, a downloaded file, an addon stream) to the player page. */
export interface PlayRequest { title: string; url: string; subtitle?: string }

const KEY = 'd1:play';

export function openPlayer(title: string, url: string, subtitle?: string) {
  const req: PlayRequest = { title, url, subtitle };
  try { sessionStorage.setItem(KEY, JSON.stringify(req)); } catch { /* private mode: the player still opens from memory on next render */ }
  window.location.hash = '#/play';
}

export function currentPlayer(): PlayRequest | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as PlayRequest) : null;
  } catch {
    return null;
  }
}
