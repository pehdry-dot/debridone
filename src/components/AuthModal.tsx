import { Crown, HardDrive } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useAuth } from '../store/auth';
import { useUi } from '../store/ui';
import { Button, Callout, Field, Modal, Segmented } from './ui';

/** Create-account and sign-in dialog. Open it with `useUi.setState({ auth: 'signup' | 'signin' })`. */
export function AuthModal() {
  const mode = useUi((s) => s.auth);
  const accounts = useAuth((s) => s.accounts);
  const [form, setForm] = useState({ name: '', username: '', email: '', password: '', confirm: '', keep: true });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { setError(null); setBusy(false); }, [mode]);
  const close = () => useUi.setState({ auth: null });
  const bind = (k: 'name' | 'username' | 'email' | 'password' | 'confirm') => ({ value: form[k], onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setForm({ ...form, [k]: e.target.value }); setError(null); } });
  const hasGuestData = !!localStorage.getItem('debridone:v1');

  const submit = async () => {
    setBusy(true); setError(null);
    try {
      if (mode === 'signup') {
        if (form.password !== form.confirm) throw new Error('The two passwords don’t match.');
        await useAuth.getState().signUp({ name: form.name, username: form.username, email: form.email, password: form.password, keepGuestData: form.keep && hasGuestData });
      } else await useAuth.getState().signIn(form.username, form.password);
    } catch (e) { setError((e as Error).message); setBusy(false); }
  };

  return (
    <Modal open={!!mode} onClose={close} title={mode === 'signup' ? 'Create your account' : 'Sign in'} size="sm"
      footer={<><Button variant="ghost" onClick={close}>Cancel</Button><Button variant="primary" loading={busy} onClick={submit}>{mode === 'signup' ? 'Create account' : 'Sign in'}</Button></>}>
      <Segmented className="mb-4" value={mode ?? 'signup'} onChange={(v) => useUi.setState({ auth: v })} options={[{ value: 'signup', label: 'Create account' }, { value: 'signin', label: `Sign in${accounts.length ? ` (${accounts.length})` : ''}` }]} />
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        {mode === 'signup' && <Field label="Display name"><input autoFocus className="field" autoComplete="name" maxLength={30} {...bind('name')} /></Field>}
        <Field label="Username"><input autoFocus={mode === 'signin'} className="field" autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={24} {...bind('username')} /></Field>
        {mode === 'signup' && <Field label="Email (optional)" hint="Kept on this device only. Nothing is sent or verified."><input className="field" type="email" autoComplete="email" {...bind('email')} /></Field>}
        <Field label="Password"><input className="field" type="password" autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} {...bind('password')} /></Field>
        {mode === 'signup' && <Field label="Confirm password"><input className="field" type="password" autoComplete="new-password" {...bind('confirm')} /></Field>}
        {mode === 'signup' && hasGuestData && (
          <label className="flex cursor-pointer items-start gap-2.5 rounded-btn border border-line/10 bg-bg/50 p-3 text-[0.8125rem]">
            <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[rgb(var(--accent))]" checked={form.keep} onChange={(e) => setForm({ ...form, keep: e.target.checked })} />
            <span>Bring my current library, progress, themes and settings into this account</span>
          </label>
        )}
        {error && <p className="text-xs text-red-400" role="alert">{error}</p>}
        <button type="submit" className="hidden" aria-hidden tabIndex={-1} />
      </form>
      {mode === 'signup' && <Callout tone="success" className="mt-4" title="Pro is included"><span className="flex items-start gap-2"><Crown className="mt-0.5 h-3.5 w-3.5 shrink-0 text-accent" />Accounts on this personal build are Pro: every customisation feature is unlocked, with no payment.</span></Callout>}
      <Callout tone="desktop" className="mt-3"><span className="flex items-start gap-2"><HardDrive className="mt-0.5 h-3.5 w-3.5 shrink-0" />Accounts live in this browser on this device. There is no server, so there is no password reset and nothing syncs. The password keeps your profile separate here; it is stored only as a salted hash.</span></Callout>
    </Modal>
  );
}
