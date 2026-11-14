import DOMPurify from 'dompurify';
import JSZip from 'jszip';
import { BOOK_TEXTS } from '../data/bookTexts';
import type { MediaItem } from '../types';
import { blobUrl, getBlob } from './idb';
import { fetchText } from './net';

/** Project Gutenberg texts wrap the book in licence boilerplate; keep only the book itself. */
function trimGutenberg(text: string): string {
  const start = text.search(/\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG/i);
  const end = text.search(/\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG/i);
  const from = start >= 0 ? text.indexOf('\n', start) + 1 : 0;
  return text.slice(from, end > from ? end : text.length);
}
import { escapeHtml } from './utils';

/*
 * Book loading for the reader.
 *   - bundled demo books: paragraphs from data/bookTexts.ts
 *   - imported .txt: split into chapters heuristically
 *   - imported .epub: unzipped with JSZip, spine documents sanitised with
 *     DOMPurify (no scripts, styles, forms or remote resources survive)
 *   - imported .pdf: handed to the browser's built-in PDF viewer
 */

export interface BookChapter { title: string; html: string }
export type BookContent = { kind: 'html'; chapters: BookChapter[] } | { kind: 'pdf'; url: string };

const MIME: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml' };

const paragraphsToHtml = (ps: string[]) => ps.map((p) => `<p>${escapeHtml(p)}</p>`).join('\n');

export function splitText(text: string): BookChapter[] {
  const paras = text.replace(/\r\n?/g, '\n').split(/\n\s*\n/).map((p) => p.replace(/\s*\n\s*/g, ' ').trim()).filter(Boolean);
  const heading = /^(chapter|book|part|letter|act|scene|prologue|epilogue|preface|introduction)\b.{0,70}$/i;
  const chapters: { title: string; paras: string[] }[] = [];
  let current: { title: string; paras: string[] } = { title: 'Beginning', paras: [] };
  for (const p of paras) {
    if (p.length < 80 && (heading.test(p) || /^[IVXLC]{1,7}\.?$/.test(p))) {
      if (current.paras.length) chapters.push(current);
      current = { title: p, paras: [] };
    } else current.paras.push(p);
  }
  if (current.paras.length) chapters.push(current);
  // No recognisable headings: cut long texts into even parts so navigation stays usable.
  if (chapters.length === 1 && chapters[0].paras.length > 120) {
    const all = chapters[0].paras, out: BookChapter[] = [];
    for (let i = 0; i < all.length; i += 80) out.push({ title: `Part ${out.length + 1}`, html: paragraphsToHtml(all.slice(i, i + 80)) });
    return out;
  }
  return chapters.map((c) => ({ title: c.title, html: paragraphsToHtml(c.paras) }));
}

const resolvePath = (base: string, href: string) => {
  const parts = (base + href.split('#')[0]).split('/');
  const out: string[] = [];
  for (const p of parts) { if (p === '..') out.pop(); else if (p && p !== '.') out.push(decodeURIComponent(p)); }
  return out.join('/');
};

export interface ParsedEpub { title?: string; author?: string; chapters: BookChapter[]; cover?: Blob }

export async function parseEpub(file: Blob): Promise<ParsedEpub> {
  const zip = await JSZip.loadAsync(file);
  const container = await zip.file('META-INF/container.xml')?.async('string');
  if (!container) throw new Error('Not a valid EPUB: META-INF/container.xml is missing.');
  const xml = (s: string) => new DOMParser().parseFromString(s, 'application/xml');
  const rootPath = xml(container).querySelector('rootfile')?.getAttribute('full-path');
  const opfText = rootPath ? await zip.file(rootPath)?.async('string') : undefined;
  if (!rootPath || !opfText) throw new Error('Not a valid EPUB: the package document is missing.');
  const opf = xml(opfText);
  const base = rootPath.includes('/') ? rootPath.slice(0, rootPath.lastIndexOf('/') + 1) : '';
  const meta = (name: string) => opf.getElementsByTagNameNS('*', name)[0]?.textContent?.trim() || undefined;

  const manifest = new Map<string, { href: string; type: string; props: string }>();
  opf.querySelectorAll('manifest > item').forEach((el) => manifest.set(el.getAttribute('id') ?? '', { href: el.getAttribute('href') ?? '', type: el.getAttribute('media-type') ?? '', props: el.getAttribute('properties') ?? '' }));
  const spine = [...opf.querySelectorAll('spine > itemref')].map((el) => manifest.get(el.getAttribute('idref') ?? '')).filter((m): m is NonNullable<typeof m> => !!m && /html/.test(m.type));

  const chapters: BookChapter[] = [];
  for (const entry of spine.slice(0, 400)) {
    const path = resolvePath(base, entry.href);
    const raw = await zip.file(path)?.async('string');
    if (!raw) continue;
    const dir = path.includes('/') ? path.slice(0, path.lastIndexOf('/') + 1) : '';
    const clean = DOMPurify.sanitize(raw, {
      WHOLE_DOCUMENT: false,
      FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'link', 'form', 'input', 'button', 'video', 'audio', 'svg', 'math', 'meta', 'base'],
      FORBID_ATTR: ['style', 'class', 'id', 'srcset'],
    });
    const doc = new DOMParser().parseFromString(`<body>${clean}</body>`, 'text/html');
    for (const img of [...doc.querySelectorAll('img')]) {
      const src = img.getAttribute('src') ?? '';
      const data = /^(https?:|data:|blob:|\/\/)/i.test(src) ? null : await zip.file(resolvePath(dir, src))?.async('blob');
      if (!data) { img.remove(); continue; }
      const ext = src.split('.').pop()?.toLowerCase() ?? '';
      img.setAttribute('src', URL.createObjectURL(new Blob([data], { type: MIME[ext] ?? 'image/jpeg' })));
      img.removeAttribute('width'); img.removeAttribute('height');
    }
    doc.querySelectorAll('a').forEach((a) => a.removeAttribute('href'));
    const text = doc.body.textContent?.trim() ?? '';
    if (text.length < 20 && !doc.querySelector('img')) continue;
    const title = doc.querySelector('h1, h2, h3')?.textContent?.trim().slice(0, 80) || `Section ${chapters.length + 1}`;
    chapters.push({ title, html: doc.body.innerHTML });
  }
  if (!chapters.length) throw new Error('This EPUB has no readable text sections.');

  let cover: Blob | undefined;
  const coverId = opf.querySelector('metadata > meta[name="cover"]')?.getAttribute('content');
  const coverEntry = [...manifest.entries()].find(([id, m]) => m.props.includes('cover-image') || id === coverId)?.[1];
  if (coverEntry && coverEntry.type.startsWith('image/')) {
    const data = await zip.file(resolvePath(base, coverEntry.href))?.async('blob');
    if (data) cover = new Blob([data], { type: coverEntry.type });
  }
  return { title: meta('title'), author: meta('creator'), chapters, cover };
}

/** Build a small, valid EPUB 3 file from chapters. Used for "Download as EPUB" on demo books. */
export async function buildEpub(title: string, author: string, chapters: BookChapter[], language = 'en'): Promise<Blob> {
  const zip = new JSZip();
  const x = escapeHtml;
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file('META-INF/container.xml', `<?xml version="1.0" encoding="UTF-8"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`);
  const page = (t: string, body: string) => `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${language}"><head><meta charset="utf-8"/><title>${x(t)}</title></head><body>${body}</body></html>`;
  chapters.forEach((c, i) => zip.file(`OEBPS/ch${i + 1}.xhtml`, page(c.title, `<h2>${x(c.title)}</h2>\n${c.html}`)));
  zip.file('OEBPS/nav.xhtml', page(title, `<nav epub:type="toc"><h1>${x(title)}</h1><ol>${chapters.map((c, i) => `<li><a href="ch${i + 1}.xhtml">${x(c.title)}</a></li>`).join('')}</ol></nav>`));
  zip.file('OEBPS/content.opf', `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:debridone:${x(title.toLowerCase().replace(/[^a-z0-9]+/g, '-'))}</dc:identifier>
    <dc:title>${x(title)}</dc:title><dc:creator>${x(author)}</dc:creator><dc:language>${language}</dc:language>
    <meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d+Z$/, 'Z')}</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    ${chapters.map((_, i) => `<item id="ch${i + 1}" href="ch${i + 1}.xhtml" media-type="application/xhtml+xml"/>`).join('\n    ')}
  </manifest>
  <spine>${chapters.map((_, i) => `<itemref idref="ch${i + 1}"/>`).join('')}</spine>
</package>`);
  return zip.generateAsync({ type: 'blob', mimeType: 'application/epub+zip' });
}

const cache = new Map<string, BookContent>();

export async function loadBook(item: MediaItem): Promise<BookContent> {
  const key = item.book?.contentKey ?? '';
  const hit = cache.get(item.id);
  if (hit) return hit;
  let content: BookContent;
  if (key.startsWith('url:')) {
    // Public-domain text from a web source (for example Project Gutenberg). Boilerplate is trimmed.
    const r = await fetchText(key.slice(4), { accept: 'text/plain, */*', maxBytes: 12_000_000, timeoutMs: 40_000 });
    if (!r.ok) throw new Error(`The book could not be fetched (HTTP ${r.status}).`);
    const body = trimGutenberg(r.text);
    content = { kind: 'html', chapters: splitText(body) };
  } else if (!key.startsWith('idb:')) {
    const text = BOOK_TEXTS[key];
    if (!text) throw new Error('The text for this book is not bundled with the prototype.');
    content = { kind: 'html', chapters: text.chapters.map((c) => ({ title: c.title, html: paragraphsToHtml(c.paragraphs) })) };
  } else {
    const idbKey = key.slice(4);
    if (item.book?.format === 'pdf') {
      const url = await blobUrl(idbKey);
      if (!url) throw new Error('This imported PDF is no longer in browser storage. Import it again.');
      content = { kind: 'pdf', url };
    } else {
      const blob = await getBlob(idbKey);
      if (!blob) throw new Error('This imported book is no longer in browser storage. Import it again.');
      content = { kind: 'html', chapters: item.book?.format === 'epub' ? (await parseEpub(blob)).chapters : splitText(await blob.text()) };
    }
  }
  cache.set(item.id, content);
  return content;
}

export const bookPlainText = (chapters: BookChapter[]) =>
  chapters.map((c) => `${c.title}\n\n${new DOMParser().parseFromString(c.html.replace(/<\/p>/g, '</p>\n\n'), 'text/html').body.textContent?.trim() ?? ''}`).join('\n\n\n');
