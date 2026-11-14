import { create } from 'zustand';
import { kv } from '../lib/kv';
import { uid } from '../lib/utils';

/*
 * Accounts.
 *
 * DebridOne has no server, so accounts are local to this PC: each one is a
 * named profile with its own library, settings, themes, addons and downloads.
 * The Mac app keeps them in its profile file; logging out never deletes them.
 * The password only gates the profile on this PC; it is stored as a salted
 * PBKDF2 hash, never as text. Nothing syncs anywhere.
 *
 * Every account on this personal build has the Pro plan.
 */

export interface Account { id: string; username: string; name: string; email?: string; salt: string; hash: string; plan: 'pro'; createdAt: number }

const ACCOUNTS_KEY = 'debridone:accounts';
const SESSION_KEY = 'debridone:session';

const readAccounts = (): Account[] => {
  try { const v = JSON.parse(kv.get(ACCOUNTS_KEY) ?? '[]'); return Array.isArray(v) ? v : []; } catch { return []; }
};
const writeAccounts = (list: Account[]) => kv.set(ACCOUNTS_KEY, JSON.stringify(list));

const initialAccounts = readAccounts();
/** The account signed in on this launch. Switching accounts reloads the app so every store reopens under the right key. */
export const sessionAccountId: string | null = (() => {
  const id = kv.get(SESSION_KEY);
  return id && initialAccounts.some((a) => a.id === id) ? id : null;
})();

/** Storage key for the current account (or the guest profile when signed out). */
export const storageKey = (base: string, accountId: string | null = sessionAccountId) => (accountId ? `${base}:${accountId}` : base);
/** Keys that belong to one profile; copied when an account is created and removed when it is deleted. */
export const PROFILE_KEYS = ['debridone:v1', 'debridone:downloads:v1', 'debridone:player:v1'];

const hex = (buf: ArrayBuffer) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

async function hashPassword(password: string, salt: string): Promise<string> {
  if (!crypto?.subtle) throw new Error('Password protection needs a secure page (https or localhost).');
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  return hex(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode(salt), iterations: 150_000 }, key, 256));
}

interface AuthState {
  accounts: Account[];
  current: Account | null;
  signUp(input: { name: string; username: string; email?: string; password: string; keepGuestData: boolean }): Promise<void>;
  signIn(username: string, password: string): Promise<void>;
  signOut(): void;
  changePassword(current: string, next: string): Promise<void>;
  deleteAccount(password: string): Promise<void>;
}

const reload = () => { window.location.hash = '#/'; window.location.reload(); };

export const useAuth = create<AuthState>((set, get) => ({
  accounts: initialAccounts,
  current: initialAccounts.find((a) => a.id === sessionAccountId) ?? null,

  async signUp({ name, username, email, password, keepGuestData }) {
    const handle = username.trim().toLowerCase();
    if (!/^[a-z0-9_.-]{3,24}$/.test(handle)) throw new Error('Usernames are 3–24 characters: letters, numbers, dots, dashes or underscores.');
    if (name.trim().length < 1) throw new Error('Enter a display name.');
    if (password.length < 8) throw new Error('Use a password of at least 8 characters.');
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) throw new Error('That email address doesn’t look right.');
    const accounts = readAccounts();
    if (accounts.some((a) => a.username === handle)) throw new Error('That username is already taken on this PC.');
    const salt = uid('s') + uid();
    const account: Account = { id: uid('u'), username: handle, name: name.trim().slice(0, 30), email: email?.trim() || undefined, salt, hash: await hashPassword(password, salt), plan: 'pro', createdAt: Date.now() };
    // Optionally carry the guest profile (library, progress, themes…) into the new account.
    if (keepGuestData) for (const key of PROFILE_KEYS) { const v = kv.get(key); if (v) kv.set(storageKey(key, account.id), v); }
    writeAccounts([...accounts, account]);
    kv.set(SESSION_KEY, account.id);
    reload();
  },

  async signIn(username, password) {
    const account = readAccounts().find((a) => a.username === username.trim().toLowerCase());
    // The same message either way, so the form doesn't reveal which usernames exist.
    if (!account || (await hashPassword(password, account.salt)) !== account.hash) throw new Error('That username and password don’t match an account on this PC.');
    kv.set(SESSION_KEY, account.id);
    reload();
  },

  // Logging out keeps everything: the account, its library and settings stay in the PC's profile store.
  signOut() { kv.remove(SESSION_KEY); reload(); },

  async changePassword(current, next) {
    const me = get().current;
    if (!me) throw new Error('You are not signed in.');
    if ((await hashPassword(current, me.salt)) !== me.hash) throw new Error('Your current password is incorrect.');
    if (next.length < 8) throw new Error('Use a password of at least 8 characters.');
    const salt = uid('s') + uid();
    const updated = { ...me, salt, hash: await hashPassword(next, salt) };
    const accounts = readAccounts().map((a) => (a.id === me.id ? updated : a));
    writeAccounts(accounts);
    set({ accounts, current: updated });
  },

  async deleteAccount(password) {
    const me = get().current;
    if (!me) throw new Error('You are not signed in.');
    if ((await hashPassword(password, me.salt)) !== me.hash) throw new Error('That password is incorrect.');
    for (const key of PROFILE_KEYS) kv.remove(storageKey(key, me.id));
    writeAccounts(readAccounts().filter((a) => a.id !== me.id));
    kv.remove(SESSION_KEY);
    reload();
  },
}));

export const isProAccount = () => useAuth.getState().current?.plan === 'pro';
