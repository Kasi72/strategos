// The X-ray grid maths, ported from the app's XRayGrid (domain/agent/XRayGrid.kt) so the slider re-blends locally.
import type { MoveKind, XRayRow } from '../api'

export const ROBUST_FLOOR = 50
const EPS = 1e-9

export interface Grid { rows: XRayRow[]; bestIndex: number; robust: boolean; odds: number }

export const kindOf = (r: Pick<XRayRow, 'well' | 'bad'>): MoveKind =>
  Math.min(r.well, r.bad) >= ROBUST_FLOOR ? 'HOLDS_UP' : Math.max(r.well, r.bad) < ROBUST_FLOOR ? 'BACKFIRES' : 'GAMBLE'

export const blendOf = (r: XRayRow, p: number) => p * r.well + (1 - p) * r.bad

/** The same rows, re-blended at [odds]; the ring stays on [prefer] (the coach's row) unless another really leads. */
export function at(rows: XRayRow[], odds: number, prefer: number): Grid {
  const p = Math.min(1, Math.max(0, odds))
  if (rows.length === 0) return { rows: [], bestIndex: -1, robust: false, odds: p }
  const blend = rows.map(r => blendOf(r, p))
  const top = Math.max(...blend)
  const best = blend[prefer] !== undefined && blend[prefer] >= top - EPS ? prefer : blend.findIndex(v => v >= top - EPS)
  const r = rows.map((x, i) => ({ ...x, expected: Math.round(blend[i]) }))
  return { rows: r, bestIndex: best, robust: Math.min(r[best].well, r[best].bad) >= ROBUST_FLOOR, odds: p }
}

export const kindWord = (k: MoveKind) => k === 'HOLDS_UP' ? 'Holds up' : k === 'BACKFIRES' ? 'Backfires' : 'A gamble'

export function explain(r: XRayRow, pct: number): string {
  switch (kindOf(r)) {
    case 'HOLDS_UP': return `Holds up either way: the worse case still scores ${Math.min(r.well, r.bad)}. At ${pct}% odds you get ${r.expected}.`
    case 'BACKFIRES': return `Hurts either way: both outcomes score under 50. At ${pct}% odds you get ${r.expected}.`
    default: return `Scores ${Math.max(r.well, r.bad)} one way and ${Math.min(r.well, r.bad)} the other, so it rides on the odds. At ${pct}% you get ${r.expected}.`
  }
}
