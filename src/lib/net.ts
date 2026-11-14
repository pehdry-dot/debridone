import { desktop } from '../adapters/desktop';

export interface TextResponse { ok: boolean; status: number; contentType: string; text: string }

/** True for addresses on this computer or the local network (self-hosted addons). */
export function isLocalUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname.replace(/^\[|\]$/g, '');
    return host === 'localhost' || /^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|::1$)/.test(host) || /\.(local|lan|home|internal)$/.test(host);
  } catch {
    return false;
  }
}

/**
 * Fetch a text resource. In the Mac app the request goes through the native
 * proxy, which isn't limited by browser CORS rules but keeps the same safety
 * checks. In a browser it is a normal fetch (browsers don't allow setting a
 * User-Agent, so that header is skipped there).
 */
export async function fetchText(
  url: string,
  opts: { accept?: string; headers?: Record<string, string>; timeoutMs?: number; maxBytes?: number; allowLocal?: boolean } = {},
): Promise<TextResponse> {
  const maxBytes = opts.maxBytes ?? 6_000_000;
  const headers = { Accept: opts.accept ?? 'application/json, text/plain, */*', ...(opts.headers ?? {}) };
  if (desktop) {
    return desktop.net.fetchText({ url, headers, timeoutMs: opts.timeoutMs, maxBytes, allowLocal: !!opts.allowLocal })
      .catch((e: unknown) => {
        throw new Error((e instanceof Error ? e.message : String(e)).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''));
      });
  }
  const browserHeaders = Object.fromEntries(Object.entries(headers).filter(([k]) => k.toLowerCase() !== 'user-agent'));
  const res = await fetch(url, {
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    headers: browserHeaders,
    signal: AbortSignal.timeout(opts.timeoutMs ?? 14_000),
  });
  const text = await res.text();
  if (text.length > maxBytes) throw new Error('The response is too large.');
  return { ok: res.ok, status: res.status, contentType: res.headers.get('content-type') ?? '', text };
}
