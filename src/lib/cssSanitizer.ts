/*
 * Custom CSS themes (Pro) are user-supplied text, so they are sanitised and
 * isolated before being injected:
 *   - anything that can load a remote resource or run script is removed
 *     (@import, url(), expression(), behaviours, HTML tags, escape sequences)
 *   - braces are balanced so the stylesheet cannot "break out" of its scope
 *   - the result is nested under #root, so it can restyle the app but not the
 *     document shell, dialogs' backdrop or anything outside the app container
 */

const RULES: [RegExp, string][] = [
  [/\/\*[\s\S]*?\*\//g, ''],
  [/<\/?[a-z!][^>]*>/gi, 'HTML tags'],
  [/\\/g, 'escape sequences'],
  [/@import\b[^;{}]*;?/gi, '@import'],
  [/@charset\b[^;{}]*;?/gi, '@charset'],
  [/@namespace\b[^;{}]*;?/gi, '@namespace'],
  [/@font-face\s*\{[^}]*\}?/gi, '@font-face'],
  [/(?:url|image-set|src|element|paint)\s*\([^)]*\)?/gi, 'url() and remote resources'],
  [/expression\s*\([^)]*\)?/gi, 'expression()'],
  [/(?:java|vb)script\s*:/gi, 'script URLs'],
  [/-moz-binding\s*:[^;}]*/gi, '-moz-binding'],
  [/behavior\s*:[^;}]*/gi, 'behavior'],
];

export function sanitizeCss(input: string): { css: string; removed: string[] } {
  const removed = new Set<string>();
  let css = input.slice(0, 20_000);
  if (input.length > 20_000) removed.add('everything past 20,000 characters');
  for (const [re, label] of RULES) {
    css = css.replace(re, () => { if (label) removed.add(label); return ''; });
  }
  // Balance braces: drop any "}" that would close more than was opened, then close what is left open.
  let depth = 0, out = '';
  for (const ch of css) {
    if (ch === '{') depth++;
    if (ch === '}') { if (depth === 0) { removed.add('unbalanced }'); continue; } depth--; }
    out += ch;
  }
  if (depth > 0) { out += '}'.repeat(depth); removed.add('unclosed {'); }
  return { css: out.trim(), removed: [...removed] };
}

/** Scope sanitised CSS to the app container using native CSS nesting. */
export const scopeCss = (css: string) => (css ? `#root {\n${css}\n}` : '');
