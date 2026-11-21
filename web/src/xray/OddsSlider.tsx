import { useRef, useState } from 'react'
import { XR } from './anim'

// The what-if slider as a two-way split (the app's XRayOddsSlider.kt): green is the chance they take it well, coral the
// chance they take it badly, the thumb is the line between. A grey tick marks the coach's odds; amber ticks mark where
// the best move changes.

interface Props {
  value: number; coach: number; flips: number[]; wellLabel: string; badLabel: string
  status?: string | null; statusColor?: string; spoken: string; onChange: (v: number) => void
}

export default function OddsSlider({ value, coach, flips, wellLabel, badLabel, status, statusColor, spoken, onChange }: Props) {
  const track = useRef<HTMLDivElement>(null)
  const [pressed, setPressed] = useState(false)
  const pct = Math.round(value * 100)
  const set = (clientX: number) => {
    const r = track.current!.getBoundingClientRect()
    onChange(Math.round(Math.min(1, Math.max(0, (clientX - r.left) / r.width)) * 100) / 100)
  }
  const down = (e: React.PointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId); setPressed(true); set(e.clientX)
  }
  const key = (e: React.KeyboardEvent) => {
    const step = e.shiftKey ? 0.1 : 0.01
    if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { onChange(Math.min(1, Math.round((value + step) * 100) / 100)); e.preventDefault() }
    if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { onChange(Math.max(0, Math.round((value - step) * 100) / 100)); e.preventDefault() }
  }
  // Flip labels win the space; the coach label drops to a bare tick when it would collide with one.
  const coachPct = Math.round(coach * 100)
  const shownFlips = flips.slice(0, 2)
  const coachCrowded = shownFlips.some(f => Math.abs(f - coachPct) < 14)

  return (
    <div className="xr-slider">
      <div className="xr-slider-top">
        {status
          ? <span className="xr-status" style={{ color: statusColor }}>{status}</span>
          : <>
              <span className="xr-end"><i style={{ background: XR.trust }} />{wellLabel}</span>
              <span className="xr-end"><i style={{ background: XR.tension }} />{badLabel}</span>
            </>}
      </div>
      <div className="xr-slider-row">
        <span className="mono share">{pct}%</span>
        <div ref={track} className="xr-rail" role="slider" tabIndex={0} aria-label={spoken} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}
          aria-valuetext={`${pct} percent`} onKeyDown={key}
          onPointerDown={down} onPointerMove={e => pressed && set(e.clientX)} onPointerUp={() => setPressed(false)} onPointerCancel={() => setPressed(false)}>
          <div className="xr-rail-bad" />
          <div className="xr-rail-well" style={{ width: `${pct}%` }} />
          <div className={`xr-thumb ${pressed ? 'pressed' : ''}`} style={{ left: `${pct}%` }} />
          {shownFlips.map(f => <Tick key={f} at={f} text={`flips ${f}%`} color={XR.amber} />)}
          <Tick at={coachPct} text={coachCrowded ? '' : `coach ${coachPct}%`} color={XR.ink3} />
        </div>
        <span className="mono share">{100 - pct}%</span>
      </div>
    </div>
  )
}

function Tick({ at, text, color }: { at: number; text: string; color: string }) {
  return (
    <div className="xr-tick" style={{ left: `${at}%`, color }}>
      <i style={{ borderBottomColor: color }} />
      {text && <span className="mono">{text}</span>}
    </div>
  )
}
