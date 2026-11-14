import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

// Installable app + offline shell. Browser production builds only; the Mac app
// and the dev server never register a service worker.
if (!window.debridone && 'serviceWorker' in navigator && (import.meta as ImportMeta & { env: { PROD: boolean } }).env.PROD) {
  window.addEventListener('load', () => { navigator.serviceWorker.register('./sw.js').catch(() => {}); });
}
