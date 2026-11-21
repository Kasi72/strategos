import { useEffect, useRef } from 'react'
import type { XRayRow } from '../api'
import { kindOf } from './grid'
import { fit, hex, mix, rgba, Tween, XR, easeOut } from './anim'

// The payoff grid as a landscape (the app's XRayBoard.kt): your moves A–C run front-right, the two replies and your
// blended score run front-left, and each block's height is its score out of 100. 2:1 projection, painted back to front:
// screen x = (x − y)·w, screen y = (x + y)·w/2 − z.

const GAP = 1.16, COLS = 3, TOP_ROOM = 2.0, BOTTOM_ROOM = 0.9, SIDE_ROOM = 0.6
const WHITE = hex('#FFFFFF'), BLACK = hex('#000000')
/** The board's colours for the current theme (see XR in anim.ts). */
function palette() {
  const LO = hex(XR.lo), MID = hex(XR.mid), HI = hex(XR.hi)
  return {
    BLEND: hex(XR.blend), AMBER: hex(XR.amberFill), AMBER_SIDE: hex(XR.amberSide), CRIMSON: hex(XR.crimson), CRIMSON_SIDE: hex(XR.crimsonSide),
    shade: (v: number) => { const t = Math.min(100, Math.max(0, v)); return t < 50 ? mix(LO, MID, t / 50) : mix(MID, HI, (t - 50) / 50) },
    faces: (b: [number, number, number]) => ({ top: mix(b, WHITE, XR.topLighten), left: b, right: mix(b, BLACK, XR.sideDarken) }),
  }
}

interface Props {
  rows: XRayRow[]; blend: number[]; best: number; coach: number; selected: number; still: boolean
  onSelect: (i: number) => void
}

type Pt = [number, number]

export default function Board({ rows, blend, best, coach, selected, still, onSelect }: Props) {
  const wrap = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const props = useRef({ rows, blend, best, coach, selected, still })
  props.current = { rows, blend, best, coach, selected, still }
  const hits = useRef<{ k: number; poly: Pt[] }[]>([])
  const select = useRef(onSelect); select.current = onSelect

  // One tween per animated value, kept across renders so changes glide instead of jump.
  const anim = useRef<{ h: Tween[]; lead: Tween[]; sink: Tween[]; dim: Tween[]; intro: Tween; drop: Tween; lastBest: number } | null>(null)
  if (!anim.current || anim.current.h.length !== rows.length * COLS) {
    anim.current = {
      h: Array.from({ length: rows.length * COLS }, () => new Tween(0)),
      lead: rows.map(() => new Tween(0)), sink: rows.map(() => new Tween(0)), dim: rows.map(() => new Tween(1)),
      intro: new Tween(still ? 1 : 0, t => t), drop: new Tween(0), lastBest: best,
    }
    anim.current.intro.set(1, still ? 0 : 760)
  }

  // Targets follow the props.
  useEffect(() => {
    const a = anim.current!
    const now = performance.now()
    rows.forEach((r, i) => {
      for (let j = 0; j < COLS; j++) {
        const v = j === 0 ? r.well : j === 1 ? r.bad : blend[i]
        const sunk = selected === i && kindOf(r) === 'BACKFIRES'
        const target = (0.1 + 1.4 * v / 100) * (sunk ? 0.45 : 1) + (j === 2 && i === best ? 0.35 : 0)
        const h = a.h[i * COLS + j]
        if (h.value(now) === 0 && h.done(now)) h.snap(target)        // first frame: the intro does the rising
        else h.set(target, still ? 0 : j === 2 ? 140 : 240, now)
      }
      a.lead[i].set(i === best ? 1 : 0, still ? 0 : 240, now)
      a.sink[i].set(selected === i && kindOf(r) === 'BACKFIRES' ? 1 : 0, still ? 0 : 240, now)
      a.dim[i].set(selected >= 0 && selected !== i ? 0.3 : 1, still ? 0 : 200, now)
    })
    if (best !== a.lastBest) {
      a.lastBest = best
      if (!still) { a.drop.snap(1); a.drop.set(0, 320) }
    }
  }, [rows, blend, best, selected, still])

  useEffect(() => {
    let raf = 0
    const draw = () => {
      const el = canvas.current, box = wrap.current
      if (!el || !box) { raf = requestAnimationFrame(draw); return }
      const { rows, blend, best, coach, selected } = props.current
      const a = anim.current!
      const now = performance.now()
      const n = rows.length
      const spanX = (COLS - 1) * GAP + 1, spanY = (n - 1) * GAP + 1
      const W = box.clientWidth
      const { BLEND, AMBER, AMBER_SIDE, CRIMSON, CRIMSON_SIDE, shade, faces } = palette()
      const tw = Math.min(36, W / (spanX + spanY + 2 * SIDE_ROOM)), th = tw / 2
      const H = tw * (TOP_ROOM + (spanX + spanY) / 2 + BOTTOM_ROOM)
      const ctx = fit(el, W, H)
      const ox = W / 2 + (spanY - spanX) * tw / 2, oy = TOP_ROOM * tw
      const p = (x: number, y: number, z: number): Pt => [ox + (x - y) * tw, oy + (x + y) * th - z]
      const poly = (pts: Pt[], fill?: string, stroke?: string, width = 1, dashed = false) => {
        ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]); ctx.closePath()
        if (fill) { ctx.fillStyle = fill; ctx.fill() }
        if (stroke) { ctx.setLineDash(dashed ? [width * 3, width * 2.4] : []); ctx.lineWidth = width; ctx.strokeStyle = stroke; ctx.stroke(); ctx.setLineDash([]) }
      }
      const label = (text: string, size: number, c: Pt, color: string) => {
        ctx.font = `500 ${size}px ui-monospace, "JetBrains Mono", Menlo, monospace`; ctx.fillStyle = color
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(text, c[0], c[1])
      }

      // Floor, with the score lane tinted so column S reads as "the answer".
      poly([p(-.25, -.25, 0), p(spanX + .25, -.25, 0), p(spanX + .25, spanY + .25, 0), p(-.25, spanY + .25, 0)], `rgba(${XR.grid},.05)`, `rgba(${XR.grid},.22)`)
      const sx = (COLS - 1) * GAP
      poly([p(sx - .08, -.25, 0), p(spanX + .25, -.25, 0), p(spanX + .25, spanY + .25, 0), p(sx - .08, spanY + .25, 0)], 'rgba(240,182,78,.07)')

      const intro = a.intro.value(now)
      const hitList: { k: number; poly: Pt[] }[] = []
      for (let d = 0; d <= n - 1 + COLS - 1; d++) for (let i = 0; i < n; i++) {
        const j = d - i
        if (j < 0 || j >= COLS) continue
        const k = i * COLS + j, r = rows[i]
        const v = j === 0 ? r.well : j === 1 ? r.bad : blend[i]
        const rise = easeOut(Math.min(1, Math.max(0, intro * 1.7 - .7 * d / Math.max(1, n + COLS - 2))))
        const z = Math.max(2, a.h[k].value(now) * tw * rise)
        const amber = j === 2 ? a.lead[i].value(now) : 0, crimson = a.sink[i].value(now), dim = a.dim[i].value(now)
        const f = faces(j === 2 ? BLEND : shade(v))
        const top = mix(mix(f.top, AMBER, amber), CRIMSON, crimson)
        const left = mix(mix(f.left, AMBER_SIDE, amber), CRIMSON_SIDE, crimson)
        const right = mix(mix(f.right, mix(AMBER_SIDE, BLACK, XR.sideDarken), amber), mix(CRIMSON_SIDE, BLACK, XR.sideDarken), crimson)
        const x0 = j * GAP, y0 = i * GAP, x1 = x0 + 1, y1 = y0 + 1
        const centre = p(x0 + .5, y0 + .5, z)
        const glow = Math.max(amber, crimson) * dim
        if (glow > .01) {
          const g = ctx.createRadialGradient(centre[0], centre[1], 0, centre[0], centre[1], tw * 1.5)
          g.addColorStop(0, rgba(crimson > amber ? CRIMSON : AMBER, .34 * glow)); g.addColorStop(1, 'rgba(0,0,0,0)')
          ctx.fillStyle = g; ctx.beginPath(); ctx.arc(centre[0], centre[1], tw * 1.5, 0, Math.PI * 2); ctx.fill()
        }
        poly([p(x1, y0, 0), p(x1, y1, 0), p(x1, y1, z), p(x1, y0, z)], rgba(right, dim))
        poly([p(x0, y1, 0), p(x1, y1, 0), p(x1, y1, z), p(x0, y1, z)], rgba(left, dim))
        const face = [p(x0, y0, z), p(x1, y0, z), p(x1, y1, z), p(x0, y1, z)]
        ctx.globalAlpha = dim; poly(face, rgba(top, dim), XR.edge); ctx.globalAlpha = 1
        if (i === coach && coach !== best) poly(face, undefined, `rgba(${XR.coachRing},${dim})`, 1.5, true)
        hitList.push({ k, poly: [face[0], face[1], p(x1, y0, 0), p(x1, y1, 0), p(x0, y1, 0), face[3]] })
        const onAmber = amber > .5 && crimson < .5
        const covered = j === 2 ? a.lead[i].value(now) : 0
        label(String(Math.round(v)), tw * .36, centre, onAmber ? `rgba(42,28,5,${dim * rise * (1 - covered)})` : `rgba(${XR.blockInk},${dim * rise * (1 - covered)})`)
      }
      hits.current = hitList

      // Edge labels on the two open front edges.
      ;['1', '2', 'S'].forEach((t, j) => label(t, tw * .30, p(j * GAP + .5, spanY + .48, 0), j === 2 ? XR.amber : XR.ink3))
      for (let i = 0; i < n; i++) label(String.fromCharCode(65 + i), tw * .30, p(spanX + .48, i * GAP + .5, 0), selected === i || (selected < 0 && i === best) ? XR.ink : XR.ink3)

      // The pawn stands on the leading score block, under a label with its score.
      if (best >= 0 && best < n && intro > .85) {
        const z = a.h[best * COLS + 2].value(now) * tw
        const foot = p(sx + .5, best * GAP + .5, z)
        const dd = a.drop.value(now), al = (1 - dd) * a.dim[best].value(now), u = tw / 36, lift = dd * tw * .5
        ctx.fillStyle = `rgba(0,0,0,${XR.shadow * .9 * al})`; ctx.beginPath(); ctx.ellipse(foot[0], foot[1], 9 * u, 4.5 * u, 0, 0, Math.PI * 2); ctx.fill()
        ctx.fillStyle = rgba(hex(XR.iris), al); roundRect(ctx, foot[0] - 6.5 * u, foot[1] - 24 * u - lift, 13 * u, 20 * u, 6.5 * u); ctx.fill()
        ctx.fillStyle = rgba(hex(XR.irisSoft), al); ctx.beginPath(); ctx.arc(foot[0], foot[1] - 30 * u - lift, 6.5 * u, 0, Math.PI * 2); ctx.fill()
        const text = `${rows[best].expected} · best`
        ctx.font = `500 ${tw * .30}px ui-monospace, "JetBrains Mono", Menlo, monospace`
        const pw = ctx.measureText(text).width + 16 * u, ph = tw * .30 + 8 * u
        const px = Math.min(Math.max(2, foot[0] - pw / 2), W - pw - 2), py = Math.max(1, foot[1] - 40 * u - ph - lift)
        ctx.fillStyle = rgba(hex(XR.card), al); roundRect(ctx, px, py, pw, ph, ph / 2); ctx.fill()
        ctx.strokeStyle = rgba(hex(XR.amber), .55 * al); ctx.lineWidth = 1; ctx.stroke()
        ctx.fillStyle = rgba(hex(XR.amber), al); ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(text, px + 8 * u, py + ph / 2)
      }
      raf = requestAnimationFrame(draw)
    }
    raf = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(raf)
  }, [])

  const click = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    const pt: Pt = [e.clientX - r.left, e.clientY - r.top]
    // Front-most block first, so a tap lands on what the eye sees.
    for (let q = hits.current.length - 1; q >= 0; q--) if (inside(pt, hits.current[q].poly)) { select.current(Math.floor(hits.current[q].k / COLS)); return }
  }

  return (
    <div ref={wrap} className="xr-board">
      <canvas ref={canvas} onClick={click} role="img"
        aria-label={'Board. ' + rows.map(r => `${r.label}: ${r.well} if it goes well, ${r.bad} if badly, ${r.expected} overall`).join('. ')} />
    </div>
  )
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath()
}

function inside(p: Pt, poly: Pt[]) {
  let hit = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i], b = poly[j]
    if ((a[1] > p[1]) !== (b[1] > p[1]) && p[0] < (b[0] - a[0]) * (p[1] - a[1]) / (b[1] - a[1]) + a[0]) hit = !hit
  }
  return hit
}

export { roundRect }
