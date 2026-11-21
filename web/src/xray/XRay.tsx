import { useEffect, useMemo, useRef, useState } from 'react'
import { api, XRayPayload, XRayRow } from '../api'
import Arena from './Arena'
import Board from './Board'
import OddsSlider from './OddsSlider'
import { at, blendOf, explain, kindOf, kindWord } from './grid'
import { reducedMotion, XR } from './anim'

// The X-ray sheet (the app's board 07): a header with the move and its verdict, two slides (Arena: who is in the game;
// Board: how each move scores), a Play walkthrough, and the what-if slider pinned to the bottom. Always the dark panel.

const LONG_MOVE = 60

export default function XRay({ projectId, startOnBoard, onClose }: { projectId: string; startOnBoard?: boolean; onClose: () => void }) {
  const [data, setData] = useState<XRayPayload | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [dragged, setDragged] = useState<number | null>(null)
  const [tab, setTab] = useState(0)
  const [selected, setSelected] = useState(-1)
  const [table, setTable] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [closing, setClosing] = useState(false)
  const still = useMemo(reducedMotion, [])

  useEffect(() => {
    api.xray(projectId).then(raw => {
      // A board needs rows and a ring on one of them; anything less is shown as "not enough yet", never drawn half-way.
      const d = raw.ready && (!raw.rows?.length || raw.bestIndex < 0 || raw.bestIndex >= raw.rows.length)
        ? { ...raw, ready: false, notEnough: raw.notEnough || 'Not enough to draw the board yet. Tell me a little more.' } : raw
      setData(d); setTab(d.arena && !startOnBoard ? 0 : 1) }).catch(e => setFailed(e.message ?? 'Could not load the X-ray'))
  }, [projectId])

  const close = () => { if (still) onClose(); else { setClosing(true); setTimeout(onClose, 200) } }
  useEffect(() => {
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', esc); return () => window.removeEventListener('keydown', esc)
  })

  const coach = data?.coachOdds ?? 0.5
  const odds = dragged ?? coach
  const changed = Math.abs(odds - coach) > 0.004
  // Rows, their order and the ring are fixed at the coach's odds; a drag only re-blends them.
  const g = useMemo(() => data?.ready ? (changed ? at(data.rows, odds, data.bestIndex) : { rows: data.rows, bestIndex: data.bestIndex, robust: data.robust, odds: coach }) : null,
    [data, odds, changed, coach])
  const coachBest = data?.ready ? data.rows[data.bestIndex].label : ''
  const flipped = !!g && changed && g.rows[g.bestIndex].label !== coachBest
  const robust = g ? (!changed ? (data?.engineRobust ?? g.robust) : g.robust) : false

  // Play: Arena, then each move worst to best, then a sweep across the flip and back. Any touch stops it.
  const playRef = useRef(0)
  useEffect(() => {
    if (!playing || !data?.ready) return
    const run = ++playRef.current
    const live = () => playRef.current === run
    const wait = (ms: number) => new Promise(r => setTimeout(r, ms))
    ;(async () => {
      setSelected(-1); setTable(false); setDragged(null)
      if (data.arena) { setTab(0); await wait(3000) }
      if (!live()) return
      setTab(1); await wait(1300)
      for (let i = data.rows.length - 1; i >= 0; i--) { if (!live()) return; setSelected(i); await wait(1900) }
      if (!live()) return
      setSelected(-1); await wait(600)
      const cp = Math.round(coach * 100)
      const up = data.flips.filter(f => f > cp), down = data.flips.filter(f => f < cp)
      const to = Math.min(98, Math.max(2, up.length ? Math.min(...up) + 14 : down.length ? Math.max(...down) - 14 : cp <= 50 ? 92 : 8))
      const step = to >= cp ? 1 : -1
      let p = cp
      while (p !== to) { if (!live()) return; p += step; setDragged(p / 100); await wait(34) }
      await wait(1400)
      while (p !== cp) { if (!live()) return; p -= step; setDragged(p / 100); await wait(24) }
      if (live()) { setDragged(null); setPlaying(false) }
    })()
    return () => { playRef.current++ }
  }, [playing, data, coach])
  const stop = () => { if (playing) setPlaying(false) }

  const keep = async () => {
    if (dragged == null) return
    const d = await api.keepOdds(projectId, dragged)
    setData(d); setDragged(null)
  }

  const pct = Math.round(odds * 100)
  return (
    <div className={`xr-wrap ${closing ? 'closing' : ''}`} onClick={close}>
      <aside className="xr" role="dialog" aria-label="X-ray" onClick={e => e.stopPropagation()}>
        <div className="xr-scroll" onPointerDown={stop}>
          {!data || !g ? (
            <div className="xr-pad">
              <div className="xr-head-row"><span className="xr-kicker mono">X-RAY</span><Close onClick={close} /></div>
              {failed ? <p className="xr-ink2">{failed}</p>
                : !data ? <p className="xr-ink2 xr-loading">Working it out…</p>
                : <><h2 className="xr-title">Not enough to rank yet</h2><p className="xr-ink2">{data.notEnough}</p></>}
            </div>
          ) : (
            <>
              <header className="xr-pad xr-header">
                <div className="xr-head-row">
                  <span className="xr-kicker mono">{changed ? 'WITH YOUR ODDS' : "THE COACH'S MOVE"}</span>
                  <Close onClick={close} />
                </div>
                <h2 key={g.rows[g.bestIndex].label} className={`xr-title fade-in ${g.rows[g.bestIndex].label.length > LONG_MOVE ? 'long' : ''}`}>{g.rows[g.bestIndex].label}</h2>
                <div className="xr-verdict">
                  <span className={`xr-badge mono ${robust ? 'ok' : 'warn'}`}>{robust ? 'ROBUST' : 'FRAGILE'}</span>
                  <span className="xr-ink2">{robust ? 'Holds up even if your guesses are a bit off.' : 'Only holds if your guesses are right.'}</span>
                </div>
                <div className="mono xr-sub" style={{ color: flipped ? XR.amber : XR.ink3 }}>
                  {flipped ? `Flipped · the coach said: ${coachBest}` : changed ? `Same move as the coach · at ${pct}%` : `At the coach's odds · ${pct}%`}
                </div>
              </header>

              {data.arena && (
                <div className="xr-pad xr-tabs-row">
                  <div className="xr-seg" role="tablist">
                    {['Arena', 'Board'].map((t, i) => (
                      <button key={t} role="tab" aria-selected={tab === i} className={tab === i ? 'on' : ''} onClick={() => { stop(); setTab(i) }}>{t}</button>
                    ))}
                  </div>
                  {!still && <button className="xr-play" onClick={e => { e.stopPropagation(); setPlaying(p => !p) }} onPointerDown={e => e.stopPropagation()}>
                    {playing ? '■ Stop' : '▶ Play'}
                  </button>}
                </div>
              )}

              <div className="xr-pad" key={tab === 0 && data.arena ? 'arena' : 'board'}>
                <div className="xr-slide">
                  {tab === 0 && data.arena ? <Arena a={data.arena} still={still} /> : (
                    <BoardSlide data={data} rows={g.rows} best={g.bestIndex} pct={pct} odds={odds} coachBest={coachBest} changed={changed}
                      selected={selected} table={table} still={still}
                      onSelect={i => setSelected(s => s === i ? -1 : i)} onTable={() => setTable(t => !t)} />
                  )}
                </div>
              </div>
            </>
          )}
        </div>

        {data?.ready && g && (
          <div className="xr-dock" onPointerDown={stop}>
            <OddsSlider value={odds} coach={coach} flips={data.flips} wellLabel={data.wellLabel} badLabel={data.badLabel}
              spoken={`Chance ${data.oddsLabel}. Best move: ${g.rows[g.bestIndex].label}.${flipped ? " Changed from the coach's move." : ''}`}
              status={flipped ? `Flipped. Best now: ${g.rows[g.bestIndex].label}` : changed ? `Still best: ${g.rows[g.bestIndex].label}` : null}
              statusColor={flipped ? XR.amber : XR.ink2}
              onChange={v => setDragged(v)} />
            <div className={`xr-actions ${changed ? 'show' : ''}`}>
              <button className="xr-reset" onClick={() => setDragged(null)}>Reset</button>
              <button className="xr-keep" onClick={keep}>Keep as my estimate</button>
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}

function Close({ onClick }: { onClick: () => void }) {
  return <button className="xr-close" aria-label="Close X-ray" onClick={onClick}>✕</button>
}

function BoardSlide({ data, rows, best, pct, odds, coachBest, changed, selected, table, still, onSelect, onTable }: {
  data: XRayPayload; rows: XRayRow[]; best: number; pct: number; odds: number; coachBest: string; changed: boolean
  selected: number; table: boolean; still: boolean; onSelect: (i: number) => void; onTable: () => void
}) {
  const coach = rows.findIndex(r => r.label.toLowerCase() === coachBest.toLowerCase())
  const blend = rows.map(r => blendOf(r, odds))
  return (
    <div className="xr-boardslide">
      <div className="xr-legend mono">
        <span><b style={{ color: XR.trust }}>1</b> {data.wellLabel} · {pct}%</span>
        <span><b style={{ color: XR.tension }}>2</b> {data.badLabel} · {100 - pct}%</span>
        <span><b style={{ color: XR.amber }}>S</b> your score</span>
      </div>
      {table ? <Table rows={rows} best={best} wellPct={pct} wellLabel={data.wellLabel} badLabel={data.badLabel} />
        : <Board rows={rows} blend={blend} best={best} coach={coach} selected={selected} still={still} onSelect={onSelect} />}
      {!changed && rows.some(r => r.expected > rows[best].expected) &&
        <p className="xr-note">The coach picks the move that holds up, not only the highest score.</p>}

      <div className="xr-kicker mono dim">YOUR MOVES · TAP ONE TO SEE WHAT YOU GET</div>
      <div className="xr-moves">
        {rows.map((r, i) => <MoveRow key={r.label} i={i} r={r} pct={pct} wellLabel={data.wellLabel} badLabel={data.badLabel}
          isCoach={i === coach} isBest={i === best} open={selected === i} onClick={() => onSelect(i)} />)}
      </div>
      <button className="xr-link" onClick={onTable}>{table ? 'See it as blocks' : 'See it as a table'}</button>

      {data.pattern && <>
        <div className="xr-kicker mono dim">WHY THIS WORKS</div>
        <h3 className="xr-h3">{data.pattern.name.toUpperCase()}</h3>
        <p className="xr-ink2">{data.pattern.summary}</p>
        <a className="xr-link" href={data.pattern.wikiUrl} target="_blank" rel="noreferrer">Read more: {data.pattern.name}</a>
      </>}
      {data.humanLens && <>
        <div className="xr-kicker mono dim">HUMAN LENS</div>
        <p>{data.humanLens.line}</p>
        <a className="xr-link" href={data.humanLens.url} target="_blank" rel="noreferrer">Read more: {data.humanLens.topic}</a>
      </>}
      {data.modelStrip && <div className="mono xr-strip">{data.modelStrip}</div>}
    </div>
  )
}

function MoveRow({ i, r, pct, wellLabel, badLabel, isCoach, isBest, open, onClick }: {
  i: number; r: XRayRow; pct: number; wellLabel: string; badLabel: string; isCoach: boolean; isBest: boolean; open: boolean; onClick: () => void
}) {
  const kind = kindOf(r)
  const tone = kind === 'HOLDS_UP' ? XR.trust : kind === 'BACKFIRES' ? XR.crimson : XR.ink2
  return (
    <button className={`xr-move ${open ? 'open' : ''} ${kind === 'BACKFIRES' ? 'bad' : ''}`} onClick={onClick} aria-expanded={open}>
      <div className="xr-move-row">
        <span className="mono letter" style={{ color: isBest ? XR.amber : XR.ink3 }}>{String.fromCharCode(65 + i)}</span>
        <div className="xr-move-main">
          <b>{r.label}</b>
          <div className="xr-move-tags"><span style={{ color: tone }}>{kindWord(kind)}</span>{isCoach && <span className="coach">Coach's move</span>}</div>
        </div>
        <span className="mono score">{r.expected}</span>
      </div>
      <div className="xr-move-more">
        <div>
          <Outcome label={`If ${wellLabel}`} score={r.well} color={XR.trust} />
          <Outcome label={`If ${badLabel}`} score={r.bad} color={XR.tension} />
          <p className="xr-ink2 small">{explain(r, pct)}</p>
        </div>
      </div>
    </button>
  )
}

function Outcome({ label, score, color }: { label: string; score: number; color: string }) {
  return (
    <div className="xr-outcome">
      <span className="xr-ink2">{label}</span>
      <div className="xr-track"><div style={{ width: `${score}%`, background: color }} /></div>
      <span className="mono">{score}</span>
    </div>
  )
}

/** Table cell fill by score, on the dark panel: dark coral (20) → slate (50) → green (80). */
function cellColor(score: number) {
  const x = Math.min(80, Math.max(20, score))
  const lo = [0x7A, 0x30, 0x2D], mid = [0x3A, 0x3A, 0x48], hi = [0x2A, 0x7A, 0x5A]
  const [a, b, t] = x <= 50 ? [lo, mid, (x - 20) / 30] : [mid, hi, (x - 50) / 30]
  return `rgb(${a.map((v, i) => Math.round(v + (b[i] - v) * t)).join(',')})`
}

function Table({ rows, best, wellPct, wellLabel, badLabel }: { rows: XRayRow[]; best: number; wellPct: number; wellLabel: string; badLabel: string }) {
  return (
    <div className="xr-table">
      <div className="xr-trow head mono"><span>YOUR MOVE</span><span>WELL {wellPct}%</span><span>BADLY {100 - wellPct}%</span><span>SCORE</span></div>
      {rows.map((r, i) => (
        <div key={r.label} className={`xr-trow ${i === best ? 'best' : ''}`}>
          <b>{r.label}</b>
          <span className="cell mono" style={{ background: cellColor(r.well) }}>{r.well}</span>
          <span className="cell mono" style={{ background: cellColor(r.bad) }}>{r.bad}</span>
          <span className="mono">{r.expected}</span>
        </div>
      ))}
      <small className="xr-ink3">Well = {wellLabel.toLowerCase()} · Badly = {badLabel.toLowerCase()}</small>
    </div>
  )
}
