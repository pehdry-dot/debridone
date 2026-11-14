import { AnimatePresence, motion } from 'framer-motion';
import { AlertTriangle, Check, CheckCircle2, ChevronDown, Info, Loader2, Lock, MoreHorizontal, X, XCircle, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState, type ButtonHTMLAttributes, type CSSProperties, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { FEATURE_LABEL, useEntitled, type Feature } from '../lib/features';
import { cn } from '../lib/utils';
import { useUi, type Tone } from '../store/ui';

/* ------------------------------------------------------------------
   DebridOne UI kit — small, themeable primitives used everywhere.
------------------------------------------------------------------- */

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'outline';
type Size = 'sm' | 'md' | 'lg';

const VARIANT: Record<Variant, string> = {
  primary: 'bg-accent text-accent-fg hover:brightness-110 shadow-[0_6px_20px_-8px_rgb(var(--accent)/0.8)]',
  secondary: 'bg-line/10 text-fg hover:bg-line/[0.16]',
  ghost: 'text-muted hover:text-fg hover:bg-line/10',
  outline: 'border border-line/15 text-fg hover:bg-line/[0.07]',
  danger: 'bg-red-500/15 text-red-400 hover:bg-red-500/25',
};
const SIZE: Record<Size, string> = { sm: 'h-8 px-3 text-xs gap-1.5', md: 'h-9 px-4 text-sm gap-2', lg: 'h-11 px-6 text-sm gap-2' };

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { variant?: Variant; size?: Size; icon?: LucideIcon; loading?: boolean }

export function Button({ variant = 'secondary', size = 'md', icon: Icon, loading, className, children, disabled, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={cn('inline-flex shrink-0 select-none items-center justify-center whitespace-nowrap rounded-btn font-medium transition-all duration-150 active:scale-[0.97] disabled:pointer-events-none disabled:opacity-45', VARIANT[variant], SIZE[size], className)}
      {...rest}
    >
      {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : Icon && <Icon className={size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4'} />}
      {children}
    </button>
  );
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> { label: string; icon: LucideIcon; active?: boolean; size?: Size; tone?: 'default' | 'solid' }

export function IconButton({ label, icon: Icon, active, size = 'md', tone = 'default', className, type = 'button', ...rest }: IconButtonProps) {
  const box = size === 'sm' ? 'h-7 w-7' : size === 'lg' ? 'h-11 w-11' : 'h-9 w-9';
  const glyph = size === 'sm' ? 'h-3.5 w-3.5' : size === 'lg' ? 'h-5 w-5' : 'h-[1.05rem] w-[1.05rem]';
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      aria-pressed={active}
      className={cn('inline-flex shrink-0 items-center justify-center rounded-full transition-all duration-150 active:scale-90 disabled:pointer-events-none disabled:opacity-40', box,
        tone === 'solid' ? 'bg-line/10 text-fg hover:bg-line/20' : active ? 'text-accent hover:bg-accent/10' : 'text-muted hover:bg-line/10 hover:text-fg', className)}
      {...rest}
    >
      <Icon className={glyph} />
    </button>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('h-5 w-5 animate-spin text-muted', className)} aria-label="Loading" />;
}

export function Badge({ tone = 'default', children, className, icon: Icon }: { tone?: 'default' | 'accent' | 'success' | 'warn' | 'error' | 'outline'; children: ReactNode; className?: string; icon?: LucideIcon }) {
  const tones = {
    default: 'bg-line/10 text-muted', accent: 'bg-accent/15 text-accent', success: 'bg-emerald-500/15 text-emerald-400',
    warn: 'bg-amber-500/15 text-amber-400', error: 'bg-red-500/15 text-red-400', outline: 'border border-line/15 text-muted',
  };
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-[0.6875rem] font-medium leading-5', tones[tone], className)}>
      {Icon && <Icon className="h-3 w-3" />}{children}
    </span>
  );
}

export function Chip({ active, onClick, children, className }: { active?: boolean; onClick?: () => void; children: ReactNode; className?: string }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={active}
      className={cn('inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-3.5 text-xs font-medium transition-colors',
        active ? 'border-transparent bg-fg text-bg' : 'border-line/10 bg-line/[0.04] text-muted hover:bg-line/10 hover:text-fg', className)}>
      {children}
    </button>
  );
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)}
      className={cn('relative h-6 w-10 shrink-0 rounded-full transition-colors disabled:opacity-40', checked ? 'bg-accent' : 'bg-line/20')}>
      <span className={cn('absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform', checked && 'translate-x-4')} />
    </button>
  );
}

export function Segmented<T extends string>({ value, onChange, options, size = 'md', className }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: string; icon?: LucideIcon }[]; size?: 'sm' | 'md'; className?: string;
}) {
  return (
    <div role="tablist" className={cn('inline-flex max-w-full gap-0.5 overflow-x-auto rounded-btn bg-line/[0.07] p-0.5 no-scrollbar', className)}>
      {options.map((o) => (
        <button key={o.value} type="button" role="tab" aria-selected={value === o.value} onClick={() => onChange(o.value)}
          className={cn('inline-flex items-center gap-1.5 whitespace-nowrap rounded-[calc(var(--radius)*0.55)] font-medium transition-colors', size === 'sm' ? 'h-7 px-2.5 text-xs' : 'h-8 px-3.5 text-[0.8125rem]',
            value === o.value ? 'bg-card text-fg shadow-sm ring-1 ring-line/10' : 'text-muted hover:text-fg')}>
          {o.icon && <o.icon className="h-3.5 w-3.5" />}{o.label}
        </button>
      ))}
    </div>
  );
}

export function Select<T extends string>({ value, onChange, options, label, className, disabled }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: string }[]; label: string; className?: string; disabled?: boolean;
}) {
  return (
    <div className={cn('relative inline-block', className)}>
      <select aria-label={label} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as T)}
        className="field h-9 w-full cursor-pointer appearance-none py-0 pr-8 text-[0.8125rem]">
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
    </div>
  );
}

export function Range({ value, min = 0, max = 100, step = 1, onChange, label, className, disabled, style }: {
  value: number; min?: number; max?: number; step?: number; onChange: (v: number) => void; label: string; className?: string; disabled?: boolean; style?: CSSProperties;
}) {
  const pct = max > min ? ((value - min) / (max - min)) * 100 : 0;
  return (
    <input type="range" aria-label={label} className={cn('d1-range', className)} min={min} max={max} step={step} value={value} disabled={disabled}
      onChange={(e) => onChange(Number(e.target.value))} style={{ ['--pct' as string]: `${pct}%`, ...style }} />
  );
}

export function ProgressBar({ value, className, tone = 'accent' }: { value: number; className?: string; tone?: 'accent' | 'success' | 'error' | 'muted' }) {
  const color = { accent: 'bg-accent', success: 'bg-emerald-400', error: 'bg-red-400', muted: 'bg-line/40' }[tone];
  return (
    <div className={cn('h-1 w-full overflow-hidden rounded-full bg-line/15', className)} role="progressbar" aria-valuenow={Math.round(value)} aria-valuemin={0} aria-valuemax={100}>
      <div className={cn('h-full rounded-full transition-[width] duration-300', color)} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  );
}

export function Field({ label, hint, children, className }: { label: string; hint?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <label className={cn('block', className)}>
      <span className="mb-1.5 block text-xs font-medium text-muted">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[0.6875rem] leading-relaxed text-muted/80">{hint}</span>}
    </label>
  );
}

export function EmptyState({ icon: Icon, title, body, action, className }: { icon: LucideIcon; title: string; body?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-col items-center justify-center rounded-card border border-dashed border-line/10 px-6 py-12 text-center', className)}>
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-line/[0.07] text-muted"><Icon className="h-5 w-5" /></div>
      <p className="text-sm font-semibold">{title}</p>
      {body && <p className="mt-1 max-w-sm text-[0.8125rem] leading-relaxed text-muted">{body}</p>}
      {action && <div className="mt-4 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  );
}

export function PageHeader({ title, subtitle, icon: Icon, actions, children }: { title: string; subtitle?: ReactNode; icon?: LucideIcon; actions?: ReactNode; children?: ReactNode }) {
  return (
    <header className="mb-6">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h1 className="h-display page-title flex items-center gap-3">
            {Icon && <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-btn bg-accent/15 text-accent"><Icon className="h-5 w-5" /></span>}
            <span className="truncate">{title}</span>
          </h1>
          {subtitle && <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-muted">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
    </header>
  );
}

/** Notice explaining that something is demo data, needs a backend, or needs the desktop app. */
export function Callout({ tone = 'info', title, children, className, action }: { tone?: Tone | 'desktop'; title?: string; children: ReactNode; className?: string; action?: ReactNode }) {
  const map = {
    info: ['border-sky-400/20 bg-sky-400/[0.06]', 'text-sky-400', Info], success: ['border-emerald-400/20 bg-emerald-400/[0.06]', 'text-emerald-400', CheckCircle2],
    warn: ['border-amber-400/25 bg-amber-400/[0.06]', 'text-amber-400', AlertTriangle], error: ['border-red-400/25 bg-red-400/[0.06]', 'text-red-400', XCircle],
    desktop: ['border-line/10 bg-line/[0.04]', 'text-muted', Lock],
  } as const;
  const [box, color, Icon] = map[tone];
  return (
    <div className={cn('flex gap-3 rounded-btn border px-3.5 py-3 text-[0.8125rem] leading-relaxed', box, className)}>
      <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', color)} />
      <div className="min-w-0 flex-1 text-muted">
        {title && <p className="font-semibold text-fg">{title}</p>}
        {children}
      </div>
      {action}
    </div>
  );
}

/* ------------------------------ Modal ------------------------------ */

export function Modal({ open, onClose, title, subtitle, children, footer, size = 'md' }: {
  open: boolean; onClose: () => void; title: string; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode; size?: 'sm' | 'md' | 'lg' | 'xl';
}) {
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } };
    window.addEventListener('keydown', onKey, true);
    requestAnimationFrame(() => { if (panel.current && !panel.current.contains(document.activeElement)) panel.current.focus(); });
    return () => { window.removeEventListener('keydown', onKey, true); prev?.focus?.(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const width = { sm: 'sm:max-w-md', md: 'sm:max-w-xl', lg: 'sm:max-w-3xl', xl: 'sm:max-w-5xl' }[size];
  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-[90] flex items-end justify-center sm:items-center sm:p-6" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
          <div className="absolute inset-0 bg-black/65 backdrop-blur-[3px]" onClick={onClose} aria-hidden />
          <motion.div ref={panel} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title}
            initial={{ y: 18, scale: 0.98 }} animate={{ y: 0, scale: 1 }} exit={{ y: 12, scale: 0.98 }} transition={{ type: 'spring', damping: 30, stiffness: 400 }}
            className={cn('relative flex max-h-[92dvh] w-full flex-col overflow-hidden rounded-t-[calc(var(--radius)*1.3)] border border-line/10 bg-panel shadow-pop outline-none sm:rounded-[calc(var(--radius)*1.3)]', width)}>
            <div className="flex items-start justify-between gap-4 border-b border-line/[0.07] px-5 py-4">
              <div className="min-w-0">
                <h2 className="truncate text-base font-semibold">{title}</h2>
                {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
              </div>
              <IconButton label="Close" icon={X} size="sm" onClick={onClose} className="-mr-1.5 -mt-0.5" />
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
            {footer && <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line/[0.07] bg-bg/40 px-5 py-3">{footer}</div>}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}

/* ------------------------------- Menu ------------------------------- */

export interface MenuItem { label: string; icon?: LucideIcon; onClick: () => void; danger?: boolean; disabled?: boolean; checked?: boolean; hint?: string }

export function Menu({ items, label = 'More actions', children, className, align = 'end' }: {
  items: (MenuItem | 'divider' | false | null | undefined)[]; label?: string; children?: ReactNode; className?: string; align?: 'start' | 'end';
}) {
  const [pos, setPos] = useState<CSSProperties | null>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const list = items.filter(Boolean) as (MenuItem | 'divider')[];

  useEffect(() => {
    if (!pos) return;
    const close = () => setPos(null);
    const onDown = (e: PointerEvent) => { if (!menu.current?.contains(e.target as Node) && !btn.current?.contains(e.target as Node)) close(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { close(); btn.current?.focus(); } };
    window.addEventListener('pointerdown', onDown, true);
    window.addEventListener('keydown', onKey);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    menu.current?.querySelector<HTMLElement>('[role=menuitem]:not([disabled])')?.focus();
    return () => {
      window.removeEventListener('pointerdown', onDown, true);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', close, true);
    };
  }, [pos]);

  const toggle = (e: React.MouseEvent) => {
    e.preventDefault(); e.stopPropagation();
    if (pos) { setPos(null); return; }
    const r = btn.current!.getBoundingClientRect();
    const estimated = list.length * 36 + 16;
    const up = r.bottom + estimated > window.innerHeight - 8 && r.top > estimated;
    setPos({
      ...(up ? { bottom: window.innerHeight - r.top + 6 } : { top: r.bottom + 6 }),
      ...(align === 'end' ? { right: Math.max(8, window.innerWidth - r.right) } : { left: Math.max(8, r.left) }),
    });
  };
  const onMenuKey = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const els = [...(menu.current?.querySelectorAll<HTMLElement>('[role=menuitem]:not([disabled])') ?? [])];
    const i = els.indexOf(document.activeElement as HTMLElement);
    els[(i + (e.key === 'ArrowDown' ? 1 : -1) + els.length) % els.length]?.focus();
  };

  return (
    <>
      <button ref={btn} type="button" aria-haspopup="menu" aria-expanded={!!pos} aria-label={label} title={label} onClick={toggle}
        className={className ?? 'inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted transition-colors hover:bg-line/10 hover:text-fg'}>
        {children ?? <MoreHorizontal className="h-[1.05rem] w-[1.05rem]" />}
      </button>
      {pos && createPortal(
        <div ref={menu} role="menu" style={pos} onKeyDown={onMenuKey}
          className="fixed z-[95] min-w-[13rem] max-w-[18rem] animate-fade-up rounded-[calc(var(--radius)*0.85)] border border-line/10 bg-card p-1.5 shadow-pop">
          {list.map((it, i) => it === 'divider' ? <div key={i} className="my-1 h-px bg-line/[0.08]" /> : (
            <button key={i} type="button" role="menuitem" disabled={it.disabled}
              onClick={(e) => { e.stopPropagation(); setPos(null); it.onClick(); }}
              className={cn('flex w-full items-center gap-2.5 rounded-[calc(var(--radius)*0.5)] px-2.5 py-2 text-left text-[0.8125rem] outline-none transition-colors focus-visible:bg-line/10 disabled:opacity-40',
                it.danger ? 'text-red-400 hover:bg-red-500/10' : 'text-fg hover:bg-line/10')}>
              {it.icon && <it.icon className={cn('h-4 w-4 shrink-0', !it.danger && 'text-muted')} />}
              <span className="min-w-0 flex-1 truncate">{it.label}</span>
              {it.hint && <span className="text-[0.6875rem] text-muted">{it.hint}</span>}
              {it.checked && <Check className="h-4 w-4 text-accent" />}
            </button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}

/* ------------------------- Toasts & confirm ------------------------- */

export function ToastHost() {
  const toasts = useUi((s) => s.toasts);
  const dismiss = useUi((s) => s.dismiss);
  const icons = { info: Info, success: CheckCircle2, warn: AlertTriangle, error: XCircle };
  const colors = { info: 'text-sky-400', success: 'text-emerald-400', warn: 'text-amber-400', error: 'text-red-400' };
  return createPortal(
    <div className="pointer-events-none fixed inset-x-0 bottom-[calc(var(--dock,0px)+1rem)] z-[100] flex flex-col items-center gap-2 px-4" role="status" aria-live="polite">
      <AnimatePresence>
        {toasts.map((t) => {
          const Icon = icons[t.tone];
          return (
            <motion.div key={t.id} layout initial={{ opacity: 0, y: 16, scale: 0.96 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, scale: 0.96 }}
              className="pointer-events-auto flex max-w-md items-center gap-3 rounded-full border border-line/10 bg-card py-2 pl-3.5 pr-2 text-[0.8125rem] shadow-pop">
              <Icon className={cn('h-4 w-4 shrink-0', colors[t.tone])} />
              <span className="min-w-0 flex-1">{t.message}</span>
              {t.action && <button type="button" className="shrink-0 rounded-full bg-line/10 px-3 py-1 text-xs font-semibold hover:bg-line/20" onClick={() => { t.action!.run(); dismiss(t.id); }}>{t.action.label}</button>}
              <IconButton label="Dismiss" icon={X} size="sm" onClick={() => dismiss(t.id)} />
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>,
    document.body,
  );
}

export function ConfirmHost() {
  const req = useUi((s) => s.confirm);
  const [checked, setChecked] = useState(false);
  useEffect(() => setChecked(false), [req]);
  const done = (ok: boolean) => { req?.resolve(ok); useUi.setState({ confirm: null }); };
  return (
    <Modal open={!!req} onClose={() => done(false)} title={req?.title ?? ''} size="sm"
      footer={<>
        <Button variant="ghost" onClick={() => done(false)}>{req?.cancelLabel ?? 'Cancel'}</Button>
        <Button variant={req?.danger ? 'danger' : 'primary'} disabled={!!req?.requireCheck && !checked} onClick={() => done(true)} autoFocus={!req?.requireCheck}>{req?.confirmLabel ?? 'Confirm'}</Button>
      </>}>
      {req?.body && <p className="text-sm leading-relaxed text-muted">{req.body}</p>}
      {req?.requireCheck && (
        <label className="mt-4 flex cursor-pointer items-start gap-2.5 rounded-btn border border-line/10 bg-bg/50 p-3 text-[0.8125rem]">
          <input type="checkbox" className="mt-0.5 h-4 w-4 accent-[rgb(var(--accent))]" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          <span>{req.requireCheck}</span>
        </label>
      )}
    </Modal>
  );
}

/* --------------------------- Feature gates --------------------------- */

/** Wraps Pro-only controls. When the feature is not unlocked, the content is dimmed and replaced by an upgrade hint. */
export function ProGate({ feature, children, compact }: { feature: Feature; children: ReactNode; compact?: boolean }) {
  const entitled = useEntitled(feature);
  const navigate = useNavigate();
  if (entitled) return <>{children}</>;
  return (
    <div className="relative">
      <div className="pointer-events-none select-none opacity-30 blur-[1px]" aria-hidden>{children}</div>
      <div className="absolute inset-0 flex items-center justify-center">
        <button type="button" onClick={() => navigate('/pro')}
          className={cn('flex items-center gap-2 rounded-full border border-line/10 bg-card/95 font-medium shadow-lift hover:bg-card', compact ? 'px-3 py-1.5 text-xs' : 'px-4 py-2 text-[0.8125rem]')}>
          <Lock className="h-3.5 w-3.5 text-accent" />
          <span>{FEATURE_LABEL[feature]} is a Pro feature</span>
          <span className="text-accent">Enable Demo Pro →</span>
        </button>
      </div>
    </div>
  );
}

export function SettingRow({ title, description, children, className }: { title: string; description?: ReactNode; children?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-x-6 gap-y-2 py-3.5', className)}>
      <div className="min-w-0 flex-1 basis-56">
        <p className="text-sm font-medium">{title}</p>
        {description && <p className="mt-0.5 text-xs leading-relaxed text-muted">{description}</p>}
      </div>
      {children && <div className="flex shrink-0 flex-wrap items-center gap-2">{children}</div>}
    </div>
  );
}

export function Card({ title, description, action, children, className }: { title?: string; description?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn('surface p-5', className)}>
      {(title || action) && (
        <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            {title && <h2 className="text-[0.9375rem] font-semibold">{title}</h2>}
            {description && <p className="mt-0.5 text-xs leading-relaxed text-muted">{description}</p>}
          </div>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
