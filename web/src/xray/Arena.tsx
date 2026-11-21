import { useEffect, useRef, useState } from 'react'
import type { Arena as ArenaData, ArenaLine } from '../api'
import { fit, hex, rgba, Tween, XR } from './anim'
import { roundRect } from './Board'

// Slide 1, "who is in the game" (the app's XRayArenaStage.kt): three isometric platforms (you, them, the strongest
// outside voice), a tether between you and them, and a dashed pull from the outsider. Authored in a 340 × 236 box.
const W = 340, H = 236

type Pt = [number, number]

export default function Arena({ a, still }: { a: ArenaData; still: boolean }) {
  return (
    <div className="xr-arena">
      <Stage a={a} still={still} />
      {(a.trust != null || a.tension != null) && (
        <div className="xr-gauges">
          {a.trust != null && <Gauge label="TRUST" value={a.trust} word={band(a.trust, 'low', 'fair', 'strong')} color={XR.trust} still={still} />}
          {a.tension != null && <Gauge label="TENSION" value={a.tension} word={band(a.tension, 'calm', 'tight', 'strained')} color={XR.tension} still={still} />}
        </div>
      )}
      <Person dot={XR.iris} name={a.you.name} role={a.you.role} lines={a.you.lines} />
      <Person dot={XR.tension} name={a.them.name} role={a.them.role} lines={a.them.lines} />
      {a.influencer && <Person dot={XR.amber} name={a.influencer.name} role={a.influencer.role}
        lines={[{ label: 'Pull', value: `${a.influencer.relation} · sway ${a.influencer.sway}`, inferred: a.influencer.inferred }]} />}
    </div>
  )
}

const band = (v: number, low: string, mid: string, high: string) => v < 40 ? low : v < 65 ? mid : high

function Stage({ a, still }: { a: ArenaData; still: boolean }) {
  const wrap = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const enter = useRef(new Tween(still ? 1 : 0, t => t))
  useEffect(() => { enter.current.set(1, still ? 0 : 1100) }, [still])

  useEffect(() => {
    let raf = 0
    const t0 = performance.now()
    const draw = () => {
      const el = canvas.current, box = wrap.current
      if (!el || !box) { raf = requestAnimationFrame(draw); return }
      const width = Math.min(box.clientWidth, 380)
      el.style.width = `${width}px`
      const ctx = fit(el, width, width * H / W)
      const s = width / W, now = performance.now()
      const e = enter.current.value(now)
      const flow = still ? 0 : ((now - t0) % 2400) / 2400
      const tw = 30, th = 15, ox = 170, oy = 66
      const p = (x: number, y: number, z = 0): Pt => [(ox + (x - y) * tw) * s, (oy + (x + y) * th - z) * s]
      const stage = (from: number, to: number) => { const v = Math.min(1, Math.max(0, (e - from) / (to - from))); return 1 - (1 - v) * (1 - v) }

      const floor = stage(0, .3)
      ctx.lineWidth = s; ctx.strokeStyle = `rgba(${XR.grid},${.13 * floor})`
      for (let k = 0; k <= 5; k++) {
        line(ctx, p(k, 0), p(k, 5)); line(ctx, p(0, k), p(5, k))
      }
      const you: Pt = [1.0, 3.6], them: Pt = [3.6, 1.0], out: Pt = [4.4, 4.0]
      const aYou = stage(.1, .4), aThem = stage(.25, .55), aOut = stage(.4, .7)
      platform(ctx, p, s, you, 1.3, XR.iris, .28, aYou)
      platform(ctx, p, s, them, 1.3, XR.tension, .16, aThem)
      if (a.influencer) platform(ctx, p, s, out, 1.0, XR.amber, .16, aOut)

      // Tether: you → them, green to coral, dashes flowing toward them.
      const y0 = p(you[0], you[1], 7), t0p = p(them[0], them[1], 7)
      const start: Pt = [y0[0] + 10 * s, y0[1] - 30 * s], end: Pt = [t0p[0] - 10 * s, t0p[1] - 30 * s]
      const ctrl: Pt = [(start[0] + end[0]) / 2, start[1] - 78 * s]
      const drawn = stage(.5, .9)
      if (drawn > 0) {
        const pts = quad(start, ctrl, end, drawn)
        const grad = ctx.createLinearGradient(start[0], start[1], end[0], end[1])
        grad.addColorStop(0, XR.trust); grad.addColorStop(1, XR.tension)
        ctx.lineCap = 'round'
        ctx.globalAlpha = .22; ctx.strokeStyle = grad; ctx.lineWidth = 7 * s; path(ctx, pts); ctx.stroke()
        ctx.globalAlpha = 1; ctx.lineWidth = 3 * s; ctx.setLineDash([7 * s, 6 * s]); ctx.lineDashOffset = -flow * 13 * s
        path(ctx, pts); ctx.stroke(); ctx.setLineDash([])
        dot(ctx, start, 3.5 * s, XR.trust)
        if (drawn >= 1) dot(ctx, end, 3.5 * s, XR.tension)
      }
      // The outsider's pull: a thin dashed amber arrow into the counterpart's platform.
      if (a.influencer) {
        const o0 = p(out[0], out[1], 7)
        const from: Pt = [o0[0] + 9 * s, o0[1] - 26 * s], to: Pt = [t0p[0] - 16 * s, t0p[1] + 10 * s], c: Pt = [from[0] + 14 * s, to[1] + 6 * s]
        const pull = stage(.7, 1)
        if (pull > 0) {
          ctx.strokeStyle = rgba(hex(XR.amber), .85); ctx.lineWidth = 1.6 * s; ctx.setLineDash([4 * s, 4 * s])
          path(ctx, quad(from, c, to, pull)); ctx.stroke(); ctx.setLineDash([])
          if (pull >= 1) {
            const ang = Math.atan2(to[1] - c[1], to[0] - c[0]), hd = 7 * s
            ctx.fillStyle = XR.amber; ctx.beginPath(); ctx.moveTo(to[0], to[1])
            ctx.lineTo(to[0] - hd * Math.cos(ang - .45), to[1] - hd * Math.sin(ang - .45))
            ctx.lineTo(to[0] - hd * Math.cos(ang + .45), to[1] - hd * Math.sin(ang + .45)); ctx.closePath(); ctx.fill()
            ctx.font = `${10 * s}px ui-monospace, Menlo, monospace`; ctx.textAlign = 'left'; ctx.textBaseline = 'top'
            ctx.fillText('pull', c[0] + 10 * s, c[1] + 2 * s)
          }
        }
      }
      pawn(ctx, s, y0, XR.iris, XR.irisSoft, aYou)
      pawn(ctx, s, t0p, XR.themBody, XR.themHead, aThem)
      if (a.influencer) pawn(ctx, s, p(out[0], out[1], 7), XR.outBody, XR.outHead, aOut)

      // Names sit under each platform, clear of the tether and the pawns.
      const name = (text: string, at: Pt, sz: number, alpha: number) => {
        if (alpha <= 0) return
        ctx.font = `600 ${12.5 * s}px Inter, -apple-system, system-ui, sans-serif`
        let t = text; while (ctx.measureText(t).width > 96 * s && t.length > 2) t = t.slice(0, -2) + '…'
        const c = p(at[0] + sz / 2, at[1] + sz / 2)
        const w = ctx.measureText(t).width + 14 * s, h = 12.5 * s + 6 * s
        const x = Math.min(Math.max(0, c[0] - w / 2), width - w), y = c[1] + 5 * s
        ctx.fillStyle = rgba(hex(XR.panel), .86 * alpha); roundRect(ctx, x, y, w, h, h / 2); ctx.fill()
        ctx.fillStyle = rgba(hex(XR.ink), alpha); ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(t, x + 7 * s, y + h / 2)
      }
      name(a.you.name, you, 1.3, aYou); name(a.them.name, them, 1.3, aThem)
      if (a.influencer) name(a.influencer.name, out, 1.0, aOut)
      raf = requestAnimationFrame(draw)
    }
    raf = requestAnimationFrame(draw)
    return () => cancelAnimationFrame(raf)
  }, [a, still])

  const said = `Arena: you and ${a.them.name}${a.influencer ? `, with ${a.influencer.name} pulling from outside` : ''}.`
  return <div ref={wrap} className="xr-stage"><canvas ref={canvas} role="img" aria-label={said} /></div>
}

function line(ctx: CanvasRenderingContext2D, a: Pt, b: Pt) { ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke() }
function dot(ctx: CanvasRenderingContext2D, c: Pt, r: number, color: string) { ctx.fillStyle = color; ctx.beginPath(); ctx.arc(c[0], c[1], r, 0, Math.PI * 2); ctx.fill() }
function path(ctx: CanvasRenderingContext2D, pts: Pt[]) { ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]) }
/** The first [part] of a quadratic Bézier, as points (so it can draw itself in). */
function quad(a: Pt, c: Pt, b: Pt, part: number): Pt[] {
  const out: Pt[] = []; const steps = 40
  for (let i = 0; i <= steps * part; i++) {
    const t = i / steps, u = 1 - t
    out.push([u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]])
  }
  return out.length > 1 ? out : [a, a]
}

function platform(ctx: CanvasRenderingContext2D, p: (x: number, y: number, z?: number) => Pt, s: number, c: Pt, sz: number, edge: string, fill: number, alpha: number) {
  if (alpha <= 0) return
  const a = c[0] - sz / 2, b = c[1] - sz / 2, cc = c[0] + sz / 2, d = c[1] + sz / 2
  const h = 7, sinkBy = (1 - alpha) * 8
  const q = (x: number, y: number, z: number) => p(x, y, z - sinkBy)
  const shape = (pts: Pt[], color: string) => { path(ctx, pts); ctx.closePath(); ctx.fillStyle = color; ctx.fill() }
  const e = hex(edge)
  const mid = q(c[0], c[1], h), r = sz * 34 * s
  const g = ctx.createRadialGradient(mid[0], mid[1], 0, mid[0], mid[1], r)
  g.addColorStop(0, rgba(e, .22 * alpha)); g.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(mid[0], mid[1], r, 0, Math.PI * 2); ctx.fill()
  shape([q(cc, b, 0), q(cc, d, 0), q(cc, d, h), q(cc, b, h)], rgba(hex(XR.side1), alpha))
  shape([q(a, d, 0), q(cc, d, 0), q(cc, d, h), q(a, d, h)], rgba(hex(XR.side2), alpha))
  const top = [q(a, b, h), q(cc, b, h), q(cc, d, h), q(a, d, h)]
  shape(top, rgba(hex(XR.panel), alpha)); shape(top, rgba(e, fill * alpha))
  path(ctx, top); ctx.closePath(); ctx.strokeStyle = rgba(e, .7 * alpha); ctx.lineWidth = 1.5 * s; ctx.stroke()
}

function pawn(ctx: CanvasRenderingContext2D, s: number, foot: Pt, body: string, head: string, alpha: number) {
  if (alpha <= 0) return
  const lift = (1 - alpha) * 14 * s, u = 1.2 * s
  ctx.fillStyle = `rgba(0,0,0,${XR.shadow * alpha})`; ctx.beginPath(); ctx.ellipse(foot[0], foot[1], 8 * u, 4 * u, 0, 0, Math.PI * 2); ctx.fill()
  ctx.fillStyle = rgba(hex(body), alpha); roundRect(ctx, foot[0] - 6 * u, foot[1] - 22 * u - lift, 12 * u, 19 * u, 6 * u); ctx.fill()
  ctx.fillStyle = rgba(hex(head), alpha); ctx.beginPath(); ctx.arc(foot[0], foot[1] - 28 * u - lift, 6 * u, 0, Math.PI * 2); ctx.fill()
}

/** Trust or tension: its own colour, number and plain word; counts up when the arena opens. */
function Gauge({ label, value, word, color, still }: { label: string; value: number; word: string; color: string; still: boolean }) {
  const [n, setN] = useState(still ? value : 0)
  useEffect(() => {
    if (still) { setN(value); return }
    const t = new Tween(0); const start = performance.now() + 250
    let raf = 0
    const tick = () => {
      const now = performance.now()
      if (now >= start) { t.set(value, 800, start); setN(Math.round(t.value(now))) }
      if (now < start + 820) raf = requestAnimationFrame(tick); else setN(value)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [value, still])
  return (
    <div className="xr-gauge" aria-label={`${label.toLowerCase()}: ${value} out of 100, ${word}`}>
      <div className="xr-gauge-h"><i style={{ background: color }} /><span className="mono">{label}</span><b style={{ color }}>{word}</b></div>
      <div className="xr-gauge-n"><span className="mono">{n}</span><small className="mono">/ 100</small></div>
      <div className="xr-track"><div style={{ width: `${n}%`, background: color }} /></div>
    </div>
  )
}

function Person({ dot, name, role, lines }: { dot: string; name: string; role: string; lines: ArenaLine[] }) {
  return (
    <div className="xr-card">
      <div className="xr-person"><i style={{ background: dot }} /><b>{name}</b>{role && <small>{role}</small>}</div>
      {lines.map(l => (
        <div key={l.label + l.value} className="xr-line">
          <span className="k">{l.label}</span><span className="v">{l.value}</span>
          {l.inferred && <span className="inferred mono">INFERRED</span>}
        </div>
      ))}
    </div>
  )
}
