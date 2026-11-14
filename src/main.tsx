// Load the saved profile before any store reads it (Mac app: from the PC; browser: already in localStorage).
import { bootstrapProfileStore } from './lib/kv';

bootstrapProfileStore()
  .catch((e: unknown) => console.warn('DebridOne: could not read the saved profile', e))
  .then(() => import('./mount'));
