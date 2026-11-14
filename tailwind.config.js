/** @type {import('tailwindcss').Config} */
const v = (name) => `rgb(var(${name}) / <alpha-value>)`;
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: v('--bg'),
        panel: v('--bg2'),
        card: v('--card'),
        fg: v('--fg'),
        muted: v('--muted'),
        line: v('--line'),
        accent: v('--accent'),
        'accent-fg': v('--accent-fg'),
      },
      borderRadius: {
        card: 'var(--radius)',
        btn: 'calc(var(--radius) * 0.72)',
      },
      fontFamily: { sans: 'var(--font)' },
      boxShadow: {
        pop: '0 24px 60px -12px rgb(0 0 0 / calc(var(--shadow-a) + 0.08)), 0 0 0 1px rgb(var(--line) / 0.06)',
        lift: '0 12px 30px -10px rgb(0 0 0 / var(--shadow-a))',
      },
      keyframes: {
        'fade-up': { from: { opacity: '0', transform: 'translateY(6px)' }, to: { opacity: '1', transform: 'none' } },
        eq: { '0%,100%': { transform: 'scaleY(0.35)' }, '50%': { transform: 'scaleY(1)' } },
      },
      animation: {
        'fade-up': 'fade-up calc(260ms * var(--anim)) ease-out both',
        eq: 'eq 0.9s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};
