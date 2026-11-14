import { Play } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { VideoPlayer } from '../components/VideoPlayer';
import { EmptyState, Button } from '../components/ui';
import { currentPlayer } from '../lib/play';

/** Full-screen player for links that aren't part of the library (Real-Debrid files, downloads, addon streams). */
export default function PlayPage() {
  const navigate = useNavigate();
  const req = currentPlayer();
  if (!req) {
    return (
      <div className="flex h-full items-center justify-center bg-bg p-6">
        <EmptyState icon={Play} title="Nothing to play" body="Choose a file from Real-Debrid, a download, or an addon stream."
          action={<Button variant="primary" onClick={() => navigate('/')}>Go Home</Button>} />
      </div>
    );
  }
  return (
    <div className="h-full bg-black">
      <VideoPlayer src={req.url} title={req.title} subtitle={req.subtitle} onBack={() => navigate(-1)} />
    </div>
  );
}
