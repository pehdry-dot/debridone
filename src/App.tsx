import { MotionConfig } from 'framer-motion';
import { Compass } from 'lucide-react';
import { useEffect } from 'react';
import { HashRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { AuthModal } from './components/AuthModal';
import { refreshStubAddons } from './components/addons';
import { CollectionPicker } from './components/collections';
import Layout from './components/Layout';
import { MetadataEditor } from './components/MetadataEditor';
import { Shortcuts, ThemeApplier } from './components/ThemeApplier';
import { Button, ConfirmHost, EmptyState, ToastHost } from './components/ui';
import AddonsPage, { CatalogPage } from './pages/Addons';
import DebridPage from './pages/Debrid';
import PlayPage from './pages/Play';
import { desktop } from './adapters/desktop';
import { useRd } from './store/realdebrid';
import BookReaderPage from './pages/BookReader';
import BooksPage from './pages/Books';
import DiscoverPage, { SearchPage } from './pages/Discover';
import DownloadsPage from './pages/Downloads';
import GamesPage from './pages/Games';
import HomePage from './pages/Home';
import ItemDetailPage from './pages/ItemDetail';
import LibraryPage, { CollectionPage } from './pages/Library';
import MangaPage from './pages/Manga';
import MangaReaderPage from './pages/MangaReader';
import MoviesPage from './pages/Movies';
import MusicPage, { ArtistPage, PlaylistPage } from './pages/Music';
import ProPage from './pages/Pro';
import SettingsPage from './pages/Settings';
import SourcesPage from './pages/Sources';
import WatchPage from './pages/Watch';
import { pump } from './store/downloads';
import { useStore } from './store/useStore';

function NotFound() {
  const navigate = useNavigate();
  return <div className="page"><EmptyState icon={Compass} title="Page not found" body="That address doesn’t match anything in DebridOne." action={<Button variant="primary" onClick={() => navigate('/')}>Go Home</Button>} /></div>;
}

/*
 * Routes. HashRouter keeps the app portable: it works from any static host or
 * a file-based shell, with no server rewrites. Players and readers render
 * outside the main layout so they can go edge to edge.
 */
export default function App() {
  const animation = useStore((s) => s.theme.animation);
  useEffect(() => {
    pump();
    refreshStubAddons();
    if (desktop) void useRd.getState().refresh();
  }, []);
  return (
    <MotionConfig reducedMotion={animation === 'off' ? 'always' : 'user'}>
      <HashRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ThemeApplier />
        <Shortcuts />
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<HomePage />} />
            <Route path="discover" element={<DiscoverPage />} />
            <Route path="search" element={<SearchPage />} />
            <Route path="movies" element={<MoviesPage />} />
            <Route path="music" element={<MusicPage />} />
            <Route path="music/artist/:name" element={<ArtistPage />} />
            <Route path="music/playlist/:id" element={<PlaylistPage />} />
            <Route path="games" element={<GamesPage />} />
            <Route path="manga" element={<MangaPage />} />
            <Route path="books" element={<BooksPage />} />
            <Route path="downloads" element={<DownloadsPage />} />
            <Route path="library" element={<LibraryPage />} />
            <Route path="collection/:id" element={<CollectionPage />} />
            <Route path="sources" element={<SourcesPage />} />
            <Route path="addons" element={<AddonsPage />} />
            <Route path="addons/catalog/:addonId/:type/:catalogId" element={<CatalogPage />} />
            <Route path="debrid" element={<DebridPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="settings/:section" element={<SettingsPage />} />
            <Route path="pro" element={<ProPage />} />
            <Route path="item/:id" element={<ItemDetailPage />} />
            <Route path="*" element={<NotFound />} />
          </Route>
          <Route path="watch/:id" element={<WatchPage />} />
          <Route path="read/manga/:id" element={<MangaReaderPage />} />
          <Route path="read/book/:id" element={<BookReaderPage />} />
          <Route path="play" element={<PlayPage />} />
        </Routes>
        <MetadataEditor />
        <CollectionPicker />
        <AuthModal />
        <ConfirmHost />
        <ToastHost />
      </HashRouter>
    </MotionConfig>
  );
}
