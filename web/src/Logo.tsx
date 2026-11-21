// The Strategos mark, ported 1:1 from the Android app (app/src/main/res/drawable/vector_logo.xml): an S stroke inside an
// orbit with the four compass nodes, ink and bronze. Colours follow the app's light/night palette via CSS variables
// (--logo-*, see styles.css). At small sizes the hairlines are thickened a little so they don't vanish.

const PATHS = {
  orbit: 'M54 24C70.57 24 84 37.43 84 54S70.57 84 54 84 24 70.57 24 54 37.43 24 54 24Z',
  guide: 'M24 54H84M54 24V84',
  arcTop: 'M54 39V32C66.5 34.2 77 43.3 84 54',
  arcBottom: 'M24 54C33 62.1 44.7 68 54 69.4V84',
  s: 'M44 26C33 30.3 31.2 40 41.7 49.1 48.2 54.8 60.2 57.1 66.3 65 72.4 72.9 66.2 80.3 55.3 82.7 69 82.1 75.7 71.5 69.4 61.5 64.8 53.8 54.1 49 46.3 43.6 39.4 38.9 39.4 31.3 44 26Z',
  sAccent: 'M61 56.3C63.1 58 64.9 60 66.3 62.7 71 71.2 66.3 79 55.3 82.7 69 82.1 75.7 71.5 69.4 61.5 67.7 58.8 65.1 56.2 61 53.8Z',
}

export function LogoMark({ size = 28, tile = false, className }: { size?: number; tile?: boolean; className?: string }) {
  const w = size < 40 ? 1.5 : size < 80 ? 1 : 0.7
  return (
    <svg className={`logo ${className ?? ''}`} width={size} height={size} viewBox={tile ? '14 14 80 80' : '19 19 70 70'} aria-hidden focusable="false">
      {tile && <rect x="14" y="14" width="80" height="80" rx="18" fill="var(--logo-bg)" />}
      <g className="lm-lines" fill="none" strokeWidth={w}>
        <path d={PATHS.orbit} stroke="var(--logo-orbit)" />
        <path d={PATHS.guide} stroke="var(--logo-guide)" strokeWidth={w * 0.9} />
        <path d={PATHS.arcTop} stroke="var(--logo-orbit)" />
        <path d={PATHS.arcBottom} stroke="var(--logo-orbit)" />
      </g>
      <path className="lm-s" d={PATHS.s} fill="var(--logo-primary)" />
      <path className="lm-s-accent" d={PATHS.sAccent} fill="var(--logo-accent)" />
      <g className="lm-nodes">
        <circle cx="24" cy="54" r="2.2" fill="var(--logo-primary)" />
        <circle cx="84" cy="54" r="2.2" fill="var(--logo-primary)" />
        <circle className="lm-node-a" cx="54" cy="24" r="2.2" fill="var(--logo-accent)" />
        <circle className="lm-node-a" cx="54" cy="84" r="2.2" fill="var(--logo-accent)" />
      </g>
    </svg>
  )
}

export function Wordmark({ size = 28 }: { size?: number }) {
  return (
    <span className="wordmark">
      <LogoMark size={size} />
      <span className="wordmark-text">Strategos</span>
    </span>
  )
}
