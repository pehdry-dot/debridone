import { AnimatePresence, motion } from 'framer-motion';
import {
  Bell, BookImage, BookOpen, CheckCheck, ChevronLeft, ChevronRight, Clapperboard, Clock, Cloud, Compass, Crown, Download, FolderHeart, Gamepad2, Home, Library,
  LogIn, LogOut, Menu as MenuIcon, MoreHorizontal, Music, PanelLeftClose, PanelLeftOpen, Plug, Puzzle, Search, Settings, Trash2, User, UserPlus, X, type LucideIcon,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useT } from '../lib/i18n';
import { cn, timeAgo } from '../lib/utils';
import { useAuth } from '../store/auth';
import { searchCatalog, useCatalog } from '../store/catalog';
import { isActive, useDownloads } from '../store/downloads';
import { usePlayer } from '../store/player';
import { useUi } from '../store/ui';
import { NAV_IDS, useStore, type NavId } from '../store/useStore';
import { TYPE_LABEL } from '../types';
import { PALETTES } from './Cover';
import { Thumb, TYPE_ICON } from './media';
import { NowPlaying, PlayerBar } from './Player';
import { Badge, IconButton, Menu } from './ui';
import { desktop } from '../adapters/desktop';

export const NAV: Record<NavId, { to: string; icon: LucideIcon; key: `nav.${NavId}` }> = {
  home: { to: '/', icon: Home, key: 'nav.home' },
  discover: { to: '/discover', icon: Compass, key: 'nav.discover' },
  movies: { to: '/movies', icon: Clapperboard, key: 'nav.movies' },
  music: { to: '/music', icon: Music, key: 'nav.music' },
  games: { to: '/games', icon: Gamepad2, key: 'nav.games' },
  manga: { to: '/manga', icon: BookImage, key: 'nav.manga' },
  books: { to: '/books', icon: BookOpen, key: 'nav.books' },
  downloads: { to: '/downloads', icon: Download, key: 'nav.downloads' },
  library: { to: '/library', icon: Library, key: 'nav.library' },
  sources: { to: '/sources', icon: Plug, key: 'nav.sources' },
  addons: { to: '/addons', icon: Puzzle, key: 'nav.addons' },
  debrid: { to: '/debrid', icon: Cloud, key: 'nav.debrid' },
  settings: { to: '/settings', icon: Settings, key: 'nav.settings' },
  pro: { to: '/pro', icon: Crown, key: 'nav.pro' },
};

/** Sidebar order honours Pro navigation customisation; Settings and Pro can never be hidden. */
export function useNavItems(): NavId[] {
  const nav = useStore((s) => s.nav);
  const pro = useStore((s) => s.proDemo);
  return useMemo(() => {
    if (!pro) return [...NAV_IDS];
    const order = [...nav.order.filter((id) => NAV_IDS.includes(id)), ...NAV_IDS.filter((id) => !nav.order.includes(id))];
    return order.filter((id) => !nav.hidden.includes(id) || id === 'settings' || id === 'pro');
  }, [nav, pro]);
}

export function Logo({ compact }: { compact?: boolean }) {
  return (
    <Link to="/" className="flex items-center gap-2.5 rounded-btn" aria-label="DebridOne home">
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-[10px] bg-gradient-to-br from-accent to-accent/60 shadow-[0_6px_18px_-6px_rgb(var(--accent)/0.9)]">
        <svg viewBox="0 0 64 64" className="h-5 w-5" aria-hidden><path d="M19 16h13c9.4 0 16 6.6 16 16s-6.6 16-16 16H19z" fill="none" stroke="rgb(var(--accent-fg))" strokeWidth="7" strokeLinejoin="round" /><circle cx="31" cy="32" r="4.5" fill="rgb(var(--accent-fg))" /></svg>
      </span>
      {!compact && <span className="text-[1.05rem] font-semibold tracking-tight">Debrid<span className="text-accent">One</span></span>}
    </Link>
  );
}

function NavList({ compact, onNavigate }: { compact?: boolean; onNavigate?: () => void }) {
  const t = useT();
  const items = useNavItems();
  const active = useDownloads((s) => s.items.filter(isActive).length);
  const pro = useStore((s) => s.proDemo);
  const account = useAuth((s) => s.current);
  return (
    <nav aria-label="Main" className="space-y-0.5">
      {items.map((id) => {
        const { to, icon: Icon, key } = NAV[id];
        return (
          <NavLink key={id} to={to} end={to === '/'} onClick={onNavigate} title={compact ? t(key) : undefined}
            className={({ isActive: on }) => cn('group relative flex h-10 items-center gap-3 rounded-btn px-3 text-[0.84rem] font-medium transition-colors',
              compact && 'justify-center px-0', on ? 'bg-line/10 text-fg' : 'text-muted hover:bg-line/[0.06] hover:text-fg',
              id === 'pro' && !on && 'text-accent hover:text-accent')}>
            {({ isActive: on }) => (
              <>
                {on && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r-full bg-accent" />}
                <Icon className={cn('h-[1.1rem] w-[1.1rem] shrink-0', on && 'text-accent')} />
                {!compact && <span className="min-w-0 flex-1 truncate">{t(key)}</span>}
                {id === 'downloads' && active > 0 && <span className={cn('flex h-4 min-w-4 items-center justify-center rounded-full bg-accent px-1 text-[0.625rem] font-bold text-accent-fg', compact && 'absolute right-1.5 top-1')}>{active}</span>}
                {id === 'pro' && !compact && <Badge tone={pro ? 'accent' : 'outline'}>{account ? 'Active' : pro ? 'Demo' : 'Free'}</Badge>}
              </>
            )}
          </NavLink>
        );
      })}
    </nav>
  );
}

function PinnedCollections({ onNavigate }: { onNavigate?: () => void }) {
  const pinned = useStore((s) => s.collections).filter((c) => c.pinned);
  if (!pinned.length) return null;
  return (
    <div className="mt-6">
      <p className="label mb-2 px-3">Pinned</p>
      <div className="space-y-0.5">
        {pinned.map((c) => (
          <NavLink key={c.id} to={`/collection/${c.id}`} onClick={onNavigate}
            className={({ isActive: on }) => cn('flex h-9 items-center gap-3 rounded-btn px-3 text-[0.8125rem] transition-colors', on ? 'bg-line/10 text-fg' : 'text-muted hover:bg-line/[0.06] hover:text-fg')}>
            <FolderHeart className="h-4 w-4 shrink-0" /><span className="truncate">{c.name}</span>
          </NavLink>
        ))}
      </div>
    </div>
  );
}

function Sidebar() {
  const collapsed = useStore((s) => s.nav.collapsed);
  const set = useStore((s) => s.set);
  return (
    <aside className={cn('app-sidebar fx-glass hidden shrink-0 flex-col border-r border-line/[0.06] bg-panel transition-[width] duration-200 md:flex', collapsed ? 'w-[4.5rem]' : 'w-60')}>
      <div className={cn('flex h-14 shrink-0 items-center', collapsed ? 'justify-center' : 'px-4')}><Logo compact={collapsed} /></div>
      <div className={cn('min-h-0 flex-1 overflow-y-auto pb-4 pt-2 no-scrollbar', collapsed ? 'px-2.5' : 'px-3')}>
        <NavList compact={collapsed} />
        {!collapsed && <PinnedCollections />}
      </div>
      <div className={cn('flex shrink-0 border-t border-line/[0.06] p-2', collapsed ? 'justify-center' : 'justify-between pl-4')}>
        {!collapsed && <span className="self-center text-[0.6875rem] text-muted">{desktop ? 'Stored on this PC' : 'Stored in this browser'}</span>}
        <IconButton label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} icon={collapsed ? PanelLeftOpen : PanelLeftClose} size="sm" onClick={() => set('nav', (n) => ({ ...n, collapsed: !n.collapsed }))} />
      </div>
    </aside>
  );
}

function MobileDrawer() {
  const open = useUi((s) => s.mobileNav);
  const close = () => useUi.setState({ mobileNav: false });
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  return (
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-[75] md:hidden" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
          <div className="absolute inset-0 bg-black/60" onClick={close} aria-hidden />
          <motion.aside role="dialog" aria-modal="true" aria-label="Navigation" initial={{ x: '-100%' }} animate={{ x: 0 }} exit={{ x: '-100%' }} transition={{ type: 'tween', duration: 0.22 }}
            className="absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col bg-panel shadow-pop">
            <div className="flex h-14 items-center justify-between px-4"><Logo /><IconButton label="Close menu" icon={X} onClick={close} /></div>
            <div className="flex-1 overflow-y-auto px-3 pb-6"><NavList onNavigate={close} /><PinnedCollections onNavigate={close} /></div>
          </motion.aside>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

function MobileTabs() {
  const t = useT();
  const tabs: NavId[] = ['home', 'discover', 'music', 'library'];
  return (
    <nav aria-label="Primary" className="fx-glass flex h-14 shrink-0 items-stretch border-t border-line/[0.07] bg-panel pb-[env(safe-area-inset-bottom)] md:hidden">
      {tabs.map((id) => {
        const { to, icon: Icon, key } = NAV[id];
        return (
          <NavLink key={id} to={to} end={to === '/'} className={({ isActive: on }) => cn('flex flex-1 flex-col items-center justify-center gap-0.5 text-[0.625rem] font-medium', on ? 'text-accent' : 'text-muted')}>
            <Icon className="h-5 w-5" /><span className="max-w-full truncate px-1">{t(key)}</span>
          </NavLink>
        );
      })}
      <button type="button" onClick={() => useUi.setState({ mobileNav: true })} className="flex flex-1 flex-col items-center justify-center gap-0.5 text-[0.625rem] font-medium text-muted">
        <MoreHorizontal className="h-5 w-5" /><span>{t('nav.more')}</span>
      </button>
    </nav>
  );
}

/* ----------------------------- global search ----------------------------- */

function SearchBox() {
  const t = useT();
  const navigate = useNavigate();
  const catalog = useCatalog();
  const history = useStore((s) => s.searchHistory);
  const pushSearch = useStore((s) => s.pushSearch);
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [cursor, setCursor] = useState(-1);
  const input = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const hits = useMemo(() => searchCatalog(catalog, q).slice(0, 7), [catalog, q]);

  useEffect(() => {
    const focus = () => { input.current?.focus(); input.current?.select(); };
    const onDown = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    window.addEventListener('d1:focus-search', focus);
    window.addEventListener('pointerdown', onDown);
    return () => { window.removeEventListener('d1:focus-search', focus); window.removeEventListener('pointerdown', onDown); };
  }, []);
  useEffect(() => setCursor(-1), [q]);

  const submit = (term: string) => {
    if (!term.trim()) return;
    pushSearch(term);
    setOpen(false);
    input.current?.blur();
    navigate(`/search?q=${encodeURIComponent(term.trim())}`);
  };
  const openHit = (i: number) => {
    const hit = hits[i];
    if (!hit) return;
    pushSearch(q);
    setOpen(false); setQ('');
    input.current?.blur();
    navigate(`/item/${hit.item.id}`);
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setCursor((c) => Math.min(hits.length - 1, c + 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(-1, c - 1)); }
    else if (e.key === 'Enter') { e.preventDefault(); if (cursor >= 0) openHit(cursor); else submit(q); }
    else if (e.key === 'Escape') { setOpen(false); input.current?.blur(); }
  };

  return (
    <div ref={box} className="relative min-w-0 flex-1 md:max-w-xl">
      <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
      <input ref={input} type="search" role="combobox" aria-expanded={open} aria-controls="global-search-results" aria-label="Search everything" autoComplete="off"
        placeholder={t('search.placeholder')} value={q} onChange={(e) => { setQ(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} onKeyDown={onKey}
        className="h-9 w-full rounded-full border border-line/[0.08] bg-line/[0.06] pl-10 pr-10 text-[0.8125rem] outline-none transition-colors placeholder:text-muted/80 focus:border-accent/60 focus:bg-line/[0.09] [&::-webkit-search-cancel-button]:hidden" />
      <kbd className="pointer-events-none absolute right-3 top-1/2 hidden -translate-y-1/2 rounded border border-line/15 px-1.5 text-[0.625rem] font-medium text-muted sm:block">/</kbd>
      {open && (q.trim() || history.length > 0) && (
        <div id="global-search-results" role="listbox" className="absolute inset-x-0 top-full z-50 mt-2 max-h-[70vh] overflow-y-auto rounded-[calc(var(--radius)*1.1)] border border-line/10 bg-card p-1.5 shadow-pop">
          {!q.trim() ? (
            <>
              <p className="label px-2.5 pb-1 pt-1.5">Recent searches</p>
              {history.map((h) => (
                <button key={h} type="button" role="option" aria-selected={false} onClick={() => { setQ(h); submit(h); }} className="flex w-full items-center gap-3 rounded-btn px-2.5 py-2 text-left text-[0.8125rem] hover:bg-line/10">
                  <Clock className="h-4 w-4 text-muted" />{h}
                </button>
              ))}
            </>
          ) : hits.length ? (
            <>
              {hits.map((h, i) => {
                const Icon = TYPE_ICON[h.item.type];
                return (
                  <button key={`${h.item.id}-${h.track?.id ?? ''}`} type="button" role="option" aria-selected={cursor === i} onMouseEnter={() => setCursor(i)} onClick={() => openHit(i)}
                    className={cn('flex w-full items-center gap-3 rounded-btn px-2.5 py-1.5 text-left', cursor === i && 'bg-line/10')}>
                    <Thumb item={h.item} className="w-8" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[0.8125rem] font-medium">{h.track ? h.track.title : h.item.title}</span>
                      <span className="block truncate text-xs text-muted">{h.track ? `Song · ${h.item.title}` : [h.item.creator, h.item.releaseDate?.slice(0, 4)].filter(Boolean).join(' · ')}</span>
                    </span>
                    <Badge icon={Icon}>{TYPE_LABEL[h.item.type]}</Badge>
                  </button>
                );
              })}
              <button type="button" onClick={() => submit(q)} className="mt-1 flex w-full items-center gap-3 rounded-btn border-t border-line/[0.07] px-2.5 py-2.5 text-left text-[0.8125rem] font-medium text-accent hover:bg-line/10">
                <Search className="h-4 w-4" />See all results for “{q.trim()}”
              </button>
            </>
          ) : (
            <p className="px-3 py-6 text-center text-[0.8125rem] text-muted">No matches for “{q.trim()}” in your catalogue.</p>
          )}
        </div>
      )}
    </div>
  );
}

function Notifications() {
  const list = useStore((s) => s.notifications);
  const set = useStore((s) => s.set);
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const unread = list.filter((n) => !n.read).length;
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('pointerdown', onDown); window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('pointerdown', onDown); window.removeEventListener('keydown', onKey); };
  }, [open]);
  const tones = { info: 'bg-sky-400', success: 'bg-emerald-400', warn: 'bg-amber-400', error: 'bg-red-400' };
  return (
    <div ref={box} className="relative">
      <IconButton label={`Notifications${unread ? ` (${unread} unread)` : ''}`} icon={Bell} onClick={() => setOpen((o) => !o)} aria-expanded={open} />
      {unread > 0 && <span className="pointer-events-none absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-accent ring-2 ring-bg" />}
      {open && (
        <div className="absolute right-0 top-full z-50 mt-2 w-[min(22rem,calc(100vw-1.5rem))] animate-fade-up rounded-[calc(var(--radius)*1.1)] border border-line/10 bg-card shadow-pop" role="dialog" aria-label="Notifications">
          <div className="flex items-center justify-between border-b border-line/[0.07] px-4 py-2.5">
            <p className="text-sm font-semibold">Notifications</p>
            <div className="flex">
              <IconButton label="Mark all as read" icon={CheckCheck} size="sm" disabled={!unread} onClick={() => set('notifications', (n) => n.map((x) => ({ ...x, read: true })))} />
              <IconButton label="Clear all notifications" icon={Trash2} size="sm" disabled={!list.length} onClick={() => set('notifications', [])} />
            </div>
          </div>
          <div className="max-h-[60vh] overflow-y-auto p-1.5">
            {list.length ? list.map((n) => (
              <button key={n.id} type="button" className="flex w-full gap-3 rounded-btn px-2.5 py-2.5 text-left hover:bg-line/[0.07]"
                onClick={() => { set('notifications', (all) => all.map((x) => (x.id === n.id ? { ...x, read: true } : x))); if (n.link) { navigate(n.link); setOpen(false); } }}>
                <span className={cn('mt-1.5 h-2 w-2 shrink-0 rounded-full', n.read ? 'bg-line/20' : tones[n.tone ?? 'info'])} />
                <span className="min-w-0 flex-1">
                  <span className="block text-[0.8125rem] font-medium">{n.title}</span>
                  {n.body && <span className="mt-0.5 block text-xs leading-relaxed text-muted">{n.body}</span>}
                  <span className="mt-1 block text-[0.6875rem] text-muted/70">{timeAgo(n.ts)}</span>
                </span>
              </button>
            )) : <p className="px-4 py-8 text-center text-[0.8125rem] text-muted">You’re all caught up.</p>}
          </div>
        </div>
      )}
    </div>
  );
}

export function Avatar({ className }: { className?: string }) {
  const profile = useStore((s) => s.profile);
  const [a, b] = PALETTES[profile.avatar % PALETTES.length];
  return (
    <span className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-bold uppercase text-white', className)} style={{ background: `linear-gradient(135deg, ${a}, ${b})` }}>
      {profile.name.trim().slice(0, 1) || <User className="h-4 w-4" />}
    </span>
  );
}

function TopBar() {
  const navigate = useNavigate();
  const pro = useStore((s) => s.proDemo);
  const name = useStore((s) => s.profile.name);
  const set = useStore((s) => s.set);
  const account = useAuth((s) => s.current);
  return (
    <header className="app-topbar fx-glass sticky top-0 z-30 flex h-14 shrink-0 items-center gap-2 border-b border-line/[0.06] bg-bg/90 px-3 md:gap-3 md:px-6">
      <div className="md:hidden"><Logo compact /></div>
      <div className="hidden items-center md:flex">
        <IconButton label="Back" icon={ChevronLeft} size="sm" onClick={() => navigate(-1)} />
        <IconButton label="Forward" icon={ChevronRight} size="sm" onClick={() => navigate(1)} />
      </div>
      <SearchBox />
      <div className="ml-auto flex shrink-0 items-center gap-0.5">
        <Link to="/pro" className={cn('mr-1 hidden h-7 items-center gap-1.5 rounded-full px-2.5 text-[0.6875rem] font-semibold sm:flex', pro ? 'bg-accent/15 text-accent' : 'border border-line/10 text-muted hover:text-fg')}>
          <Crown className="h-3.5 w-3.5" />{account ? 'Pro' : pro ? 'Demo Pro' : 'Free tier'}
        </Link>
        <Notifications />
        <Menu label="Profile menu" className="ml-1 rounded-full" items={[
          account
            ? { label: `${name} · @${account.username}`, icon: User, onClick: () => navigate('/settings/account') }
            : { label: `${name} · guest profile`, icon: User, onClick: () => navigate('/settings/account') },
          !account && { label: 'Create account', icon: UserPlus, onClick: () => useUi.setState({ auth: 'signup' }) },
          !account && { label: 'Sign in', icon: LogIn, onClick: () => useUi.setState({ auth: 'signin' }) },
          'divider',
          { label: 'My Library', icon: Library, onClick: () => navigate('/library') },
          { label: 'Downloads', icon: Download, onClick: () => navigate('/downloads') },
          { label: 'Settings', icon: Settings, onClick: () => navigate('/settings') },
          'divider',
          account
            ? { label: 'Sign out', icon: LogOut, onClick: () => useAuth.getState().signOut() }
            : { label: 'Demo Pro Mode', icon: Crown, checked: pro, onClick: () => set('proDemo', !pro) },
        ]}>
          <Avatar />
        </Menu>
      </div>
    </header>
  );
}

let startPageApplied = false;

export default function Layout() {
  const location = useLocation();
  const navigate = useNavigate();
  const main = useRef<HTMLElement>(null);
  const hasQueue = usePlayer((s) => s.queue.length > 0);

  useEffect(() => { main.current?.scrollTo(0, 0); useUi.setState({ mobileNav: false }); }, [location.pathname]);

  // Settings → General → Startup page: applied once per app launch.
  useEffect(() => {
    if (startPageApplied) return;
    startPageApplied = true;
    const start = useStore.getState().settings.startPage;
    if (location.pathname === '/' && start && start !== '/') navigate(start, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Toasts float above whatever is docked at the bottom.
  useEffect(() => {
    const mobile = window.matchMedia('(max-width: 767px)');
    const apply = () => document.documentElement.style.setProperty('--dock', `${(hasQueue ? (mobile.matches ? 56 : 76) : 0) + (mobile.matches ? 56 : 0)}px`);
    apply();
    mobile.addEventListener('change', apply);
    return () => mobile.removeEventListener('change', apply);
  }, [hasQueue]);

  return (
    <div className="app-shell flex h-full flex-col bg-bg">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[200] focus:rounded-btn focus:bg-accent focus:px-3 focus:py-2 focus:text-sm focus:text-accent-fg">Skip to content</a>
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar />
          <main id="main" ref={main} tabIndex={-1} className="app-main fx-glow relative min-h-0 flex-1 overflow-y-auto overflow-x-hidden outline-none">
            <div key={location.pathname} className="relative z-[1] animate-fade-up overflow-x-clip"><Outlet /></div>
          </main>
        </div>
      </div>
      {hasQueue && <PlayerBar />}
      <MobileTabs />
      <MobileDrawer />
      <NowPlaying />
    </div>
  );
}
