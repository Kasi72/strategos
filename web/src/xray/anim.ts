// Small animation helpers for the canvas drawings: tweened values and colour mixing, like Compose's animate*AsState.

export const reducedMotion = () => {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches } catch { return false }
}

export const easeOut = (t: number) => 1 - Math.pow(1 - t, 3)            // LinearOutSlowIn, near enough
export const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2)

/** One animated number: set a target, read the current value each frame. */
export class Tween {
  private from: number; private to: number; private start = 0; private dur = 0
  constructor(v: number, private ease: (t: number) => number = easeOut) { this.from = v; this.to = v }
  set(target: number, ms: number, now = performance.now()) {
    if (target === this.to) return
    this.from = this.value(now); this.to = target; this.start = now; this.dur = Math.max(0, ms)
  }
  snap(v: number) { this.from = v; this.to = v; this.dur = 0 }
  value(now = performance.now()) {
    if (this.dur <= 0) return this.to
    const t = Math.min(1, (now - this.start) / this.dur)
    return this.from + (this.to - this.from) * this.ease(t)
  }
  done(now = performance.now()) { return this.dur <= 0 || now - this.start >= this.dur }
}

export type RGB = [number, number, number]
export const hex = (h: string): RGB => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)]
export const mix = (a: RGB, b: RGB, t: number): RGB => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
export const rgba = (c: RGB, a = 1) => `rgba(${Math.round(c[0])},${Math.round(c[1])},${Math.round(c[2])},${a})`

/** The X-ray's own palette (XR in the app). Dark is the original "machine room"; light is its daytime twin, used
 *  when the page is in light theme. Amber is the engine's colour in both. Read per frame, so it follows the theme. */
const XR_DARK = {
  panel: '#101014', card: '#1A1A1E', dock: '#141418',
  ink: '#F7F7FA', ink2: '#B4B4C0', ink3: '#8A8A96',
  iris: '#8A86FF', irisFill: '#5B57E0', irisSoft: '#D6D4FF',
  amber: '#F0B64E', trust: '#67C495', tension: '#FF6B63', crimson: '#EF4444',
  // canvas-only
  grid: '138,134,255', side1: '#141418', side2: '#1B1B21', shadow: .45,
  themBody: '#C9625C', themHead: '#FFD0CB', outBody: '#B88A3A', outHead: '#FBE3B4',
  lo: '#762C2A', mid: '#383846', hi: '#247656', blend: '#4C4028', amberFill: '#F0B64E', amberSide: '#A3711F', crimsonSide: '#962626',
  sideDarken: .32, topLighten: .2, edge: 'rgba(255,255,255,.10)', coachRing: '180,180,192', blockInk: '247,247,250',
}
const XR_LIGHT: typeof XR_DARK = {
  panel: '#F7F7FA', card: '#FFFFFF', dock: '#FFFFFF',
  ink: '#17171C', ink2: '#5C5C66', ink3: '#83838F',
  iris: '#5B57E0', irisFill: '#4B47C9', irisSoft: '#9D99FF',
  amber: '#A26E12', trust: '#2E7D53', tension: '#D1453B', crimson: '#DC2626',
  grid: '91,87,224', side1: '#D9D9E3', side2: '#E6E6EE', shadow: .16,
  themBody: '#D1453B', themHead: '#F4A7A1', outBody: '#B07A14', outHead: '#F2CD84',
  lo: '#EFA9A3', mid: '#DADAE4', hi: '#8FCFAC', blend: '#E9DDBF', amberFill: '#F0B64E', amberSide: '#C98A1E', crimsonSide: '#B91C1C',
  sideDarken: .16, topLighten: .35, edge: 'rgba(0,0,0,.07)', coachRing: '92,92,102', blockInk: '23,23,28',
}

let light = false
function readTheme() {
  if (typeof document === 'undefined') return
  const t = document.documentElement.getAttribute('data-theme')
  light = t ? t === 'light' : !(typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches)
}
if (typeof document !== 'undefined') {
  readTheme()
  new MutationObserver(readTheme).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  if (typeof matchMedia !== 'undefined') matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', readTheme)
}
export const xrIsLight = () => light
export const XR: typeof XR_DARK = new Proxy(XR_DARK, { get: (_, k) => (light ? XR_LIGHT : XR_DARK)[k as keyof typeof XR_DARK] })

/** Sizes a canvas to its CSS box at the device's pixel ratio and returns a 2D context in CSS pixels. */
export function fit(canvas: HTMLCanvasElement, cssW: number, cssH: number) {
  const dpr = Math.min(window.devicePixelRatio || 1, 3)
  if (canvas.width !== Math.round(cssW * dpr) || canvas.height !== Math.round(cssH * dpr)) {
    canvas.width = Math.round(cssW * dpr); canvas.height = Math.round(cssH * dpr)
  }
  canvas.style.height = `${cssH}px`
  const ctx = canvas.getContext('2d')!
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, cssW, cssH)
  return ctx
}
