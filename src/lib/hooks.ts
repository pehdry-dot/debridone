import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

/** Tab state kept in the URL (?tab=…) so tabs are linkable and survive back/forward. */
export function useTab<T extends string>(fallback: T, key = 'tab'): [T, (next: T) => void] {
  const [params, setParams] = useSearchParams();
  const value = (params.get(key) as T | null) ?? fallback;
  const set = useCallback((next: T) => {
    const p = new URLSearchParams(params);
    if (next === fallback) p.delete(key); else p.set(key, next);
    setParams(p, { replace: true });
  }, [params, setParams, fallback, key]);
  return [value, set];
}
