import { useStore } from '../store/useStore';
import type { Settings } from '../types';

/*
 * Minimal i18n layer. Navigation, greetings and the search prompt are
 * translated; the rest of the prototype is English. Adding a string means
 * adding a key here and calling t('key').
 */

type Lang = Settings['language'];
export const LANGUAGES: { id: Lang; label: string }[] = [
  { id: 'en', label: 'English' }, { id: 'es', label: 'Español' }, { id: 'pt', label: 'Português' },
  { id: 'fr', label: 'Français' }, { id: 'de', label: 'Deutsch' }, { id: 'ja', label: '日本語' },
];

const EN = {
  'nav.home': 'Home', 'nav.discover': 'Discover', 'nav.movies': 'Movies & TV', 'nav.music': 'Music', 'nav.games': 'Gaming', 'nav.manga': 'Manga',
  'nav.books': 'Books & E-books', 'nav.downloads': 'Downloads', 'nav.library': 'My Library', 'nav.sources': 'Sources', 'nav.addons': 'Addons', 'nav.debrid': 'Real-Debrid', 'nav.settings': 'Settings', 'nav.pro': 'DebridOne Pro',
  'search.placeholder': 'Search movies, music, games, manga, books…',
  'greet.morning': 'Good morning', 'greet.afternoon': 'Good afternoon', 'greet.evening': 'Good evening',
  'nav.more': 'More',
};
type Key = keyof typeof EN;

const DICT: Record<Lang, Partial<Record<Key, string>>> = {
  en: EN,
  es: { 'nav.home': 'Inicio', 'nav.discover': 'Descubrir', 'nav.movies': 'Películas y TV', 'nav.music': 'Música', 'nav.games': 'Juegos', 'nav.manga': 'Manga', 'nav.books': 'Libros y e-books', 'nav.downloads': 'Descargas', 'nav.library': 'Mi biblioteca', 'nav.sources': 'Fuentes', 'nav.addons': 'Complementos', 'nav.settings': 'Ajustes', 'nav.pro': 'DebridOne Pro', 'search.placeholder': 'Buscar películas, música, juegos, manga, libros…', 'greet.morning': 'Buenos días', 'greet.afternoon': 'Buenas tardes', 'greet.evening': 'Buenas noches', 'nav.more': 'Más' },
  pt: { 'nav.home': 'Início', 'nav.discover': 'Descobrir', 'nav.movies': 'Filmes e TV', 'nav.music': 'Música', 'nav.games': 'Jogos', 'nav.manga': 'Mangá', 'nav.books': 'Livros e e-books', 'nav.downloads': 'Downloads', 'nav.library': 'Minha biblioteca', 'nav.sources': 'Fontes', 'nav.addons': 'Complementos', 'nav.settings': 'Configurações', 'nav.pro': 'DebridOne Pro', 'search.placeholder': 'Buscar filmes, música, jogos, mangá, livros…', 'greet.morning': 'Bom dia', 'greet.afternoon': 'Boa tarde', 'greet.evening': 'Boa noite', 'nav.more': 'Mais' },
  fr: { 'nav.home': 'Accueil', 'nav.discover': 'Découvrir', 'nav.movies': 'Films et séries', 'nav.music': 'Musique', 'nav.games': 'Jeux', 'nav.manga': 'Manga', 'nav.books': 'Livres et e-books', 'nav.downloads': 'Téléchargements', 'nav.library': 'Ma bibliothèque', 'nav.sources': 'Sources', 'nav.addons': 'Extensions', 'nav.settings': 'Réglages', 'nav.pro': 'DebridOne Pro', 'search.placeholder': 'Rechercher films, musique, jeux, manga, livres…', 'greet.morning': 'Bonjour', 'greet.afternoon': 'Bon après-midi', 'greet.evening': 'Bonsoir', 'nav.more': 'Plus' },
  de: { 'nav.home': 'Start', 'nav.discover': 'Entdecken', 'nav.movies': 'Filme & Serien', 'nav.music': 'Musik', 'nav.games': 'Spiele', 'nav.manga': 'Manga', 'nav.books': 'Bücher & E-Books', 'nav.downloads': 'Downloads', 'nav.library': 'Meine Bibliothek', 'nav.sources': 'Quellen', 'nav.addons': 'Add-ons', 'nav.settings': 'Einstellungen', 'nav.pro': 'DebridOne Pro', 'search.placeholder': 'Filme, Musik, Spiele, Manga, Bücher suchen…', 'greet.morning': 'Guten Morgen', 'greet.afternoon': 'Guten Tag', 'greet.evening': 'Guten Abend', 'nav.more': 'Mehr' },
  ja: { 'nav.home': 'ホーム', 'nav.discover': '見つける', 'nav.movies': '映画とテレビ', 'nav.music': 'ミュージック', 'nav.games': 'ゲーム', 'nav.manga': 'マンガ', 'nav.books': '本と電子書籍', 'nav.downloads': 'ダウンロード', 'nav.library': 'マイライブラリ', 'nav.sources': 'ソース', 'nav.addons': 'アドオン', 'nav.settings': '設定', 'nav.pro': 'DebridOne Pro', 'search.placeholder': '映画、音楽、ゲーム、マンガ、本を検索…', 'greet.morning': 'おはようございます', 'greet.afternoon': 'こんにちは', 'greet.evening': 'こんばんは', 'nav.more': 'その他' },
};

export function useT() {
  const lang = useStore((s) => s.settings.language);
  return (key: Key) => DICT[lang]?.[key] ?? EN[key];
}

/** Date/time formatting that follows Settings → General. */
export function useDateFormat() {
  const timeFormat = useStore((s) => s.settings.timeFormat);
  const dateFormat = useStore((s) => s.settings.dateFormat);
  const lang = useStore((s) => s.settings.language);
  return (ts: number, withTime = true) => {
    const d = new Date(ts);
    const date = dateFormat === 'iso'
      ? `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      : d.toLocaleDateString(lang, dateFormat === 'long' ? { year: 'numeric', month: 'long', day: 'numeric' } : undefined);
    if (!withTime) return date;
    return `${date} ${d.toLocaleTimeString(lang, { hour: 'numeric', minute: '2-digit', hour12: timeFormat === '12h' })}`;
  };
}
