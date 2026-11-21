import type { StrategyBrief } from './api'
import { kindWord } from './xray/grid'

// A reply laid out as a strategy brief: the read, the pick and why, every move on the table with the engine's numbers and
// its trade-offs, then how to carry the pick out. The numbers are the X-ray's own (the server copies them from the grid);
// the words around them are the coach's.

const lower = (s: string) => s.charAt(0).toLowerCase() + s.slice(1)

/** Whole-percent odds at which row [i] has the top blended score (ties count). */
export function leadsAt(b: Pick<StrategyBrief, 'rows'>, i: number): number[] {
  const out: number[] = []
  for (let pct = 0; pct <= 100; pct++) {
    const p = pct / 100
    const blend = b.rows.map(r => p * r.well + (1 - p) * r.bad)
    if (blend[i] >= Math.max(...blend) - 1e-9) out.push(pct)
  }
  return out
}

/** How steady the pick is, judged against the pick itself (not whichever row happens to lead). */
export function steadiness(b: StrategyBrief): { text: string; leads: boolean } {
  const pick = b.rows.findIndex(r => r.recommended)
  const base = b.robust ? 'Holds up either way' : 'Rides on their reaction'
  if (pick < 0) return { text: base, leads: true }
  const leads = leadsAt(b, pick)
  if (leads.includes(b.oddsPct)) {
    let hi = b.oddsPct; while (hi <= 100 && leads.includes(hi)) hi++
    let lo = b.oddsPct; while (lo >= 0 && leads.includes(lo)) lo--
    if (hi > 100 && lo < 0) return { text: `${base} · the top score at any odds`, leads: true }
    return { text: `${base} · stays the top score unless the odds ${hi <= 100 ? `rise to ${hi}%` : `fall to ${lo}%`}`, leads: true }
  }
  const where = leads.length === 0 ? 'at no odds' : leads[0] > b.oddsPct ? `only from ${leads[0]}% up` : `only up to ${leads[leads.length - 1]}%`
  return { text: `${base} · not the top score at ${b.oddsPct}%; on these two outcomes it leads ${where}`, leads: false }
}

function Bar({ value, tone, label }: { value: number; tone: 'well' | 'bad'; label: string }) {
  return (
    <div className="b-bar" title={`${label}: ${value} out of 100`}>
      <span className="b-bar-label">{label}</span>
      <span className="b-bar-track" aria-hidden><span className={`b-bar-fill ${tone}`} style={{ width: `${value}%` }} /></span>
      <span className="b-bar-n mono">{value}</span>
    </div>
  )
}

export default function Brief({ b, step }: { b: StrategyBrief; step: () => { className: string; style?: React.CSSProperties } }) {
  const s = Array.from({ length: 7 }, step)
  const steady = steadiness(b)
  return (
    <>
      {b.read && <p {...s[0]} className={`lead ${s[0].className}`}>{b.read}</p>}

      <div {...s[1]} className={`move ${s[1].className}`}>
        <span className="move-label">{b.moveLabel}</span>
        <p>{b.recommendation}</p>
        {b.why && (b.mode === 'compare'
          ? <p className="b-why"><span className="b-verdict">Verdict</span>{b.why}</p>
          : <p className="b-why">{b.why}</p>)}
        <span className={`b-steady ${b.robust ? 'ok' : 'warn'}`}>{steady.text}</span>
      </div>

      <section {...s[2]} className={`b-table ${s[2].className}`} aria-label="Your options">
        <header className="b-head">
          <span className="b-title">Your options</span>
          <span className="b-odds">
            <span className="b-odds-bar" aria-hidden><span style={{ width: `${b.oddsPct}%` }} /></span>
            <span><b className="mono">{b.oddsPct}%</b> {b.oddsLabel}</span>
          </span>
        </header>
        <div className="b-legend">
          <span><i className="well" />Goes well: {b.wellLabel}</span>
          <span><i className="bad" />Goes badly: {b.badLabel}</span>
        </div>
        <ol className="b-rows">
          {b.rows.map((r, i) => (
            <li key={r.move} className={`b-row ${r.recommended ? 'pick' : ''}`}>
              <div className="b-row-top">
                <span className="b-letter mono">{String.fromCharCode(65 + i)}</span>
                <div className="b-move">
                  <span className="b-move-name">{r.move}</span>
                  <span className="b-tags">
                    <span className={`b-kind ${r.kind}`}>{kindWord(r.kind)}</span>
                    {r.recommended && <span className="b-pick">The pick</span>}
                  </span>
                </div>
                <span className="b-score" title={`Blended at ${b.oddsPct}% odds`}><b className="mono">{r.expected}</b><small>/100</small></span>
              </div>
              <div className="b-bars">
                <Bar value={r.well} tone="well" label="If it goes well" />
                <Bar value={r.bad} tone="bad" label="If it goes badly" />
              </div>
              {(r.pros.length > 0 || r.cons.length > 0) && (
                <div className="b-pc">
                  <ul className="b-pros">{r.pros.map((x, j) => <li key={j}><span aria-label="Pro">+</span>{x}</li>)}</ul>
                  <ul className="b-cons">{r.cons.map((x, j) => <li key={j}><span aria-label="Con">−</span>{x}</li>)}</ul>
                </div>
              )}
            </li>
          ))}
        </ol>
        {!steady.leads && (
          <p className="b-note">The pick isn't the top score here: the engine weighs more than these two outcomes (what is blocked, what is risky, how
            the other side is likely to play it), so it can prefer a move that scores lower on this table.</p>
        )}
        {b.weights.length > 0 && (
          <footer className="b-weights">
            {b.weightsAreDefault ? 'Default weighting (not set from what you said): ' : 'Scores weigh '}
            {b.weights.map(w => `${lower(w.label)} ${w.pct}%`).join(' · ')}
          </footer>
        )}
      </section>

      {(b.plan.length > 0 || b.ifThen.length > 0) && (
        <section {...s[3]} className={`b-plan ${s[3].className}`} aria-label="How to tackle it">
          <div className="b-plan-h">How to tackle it</div>
          {b.plan.length > 0 && <ol className="b-steps">{b.plan.map((x, j) => <li key={j}>{x}</li>)}</ol>}
          {b.ifThen.length > 0 && (
            <div className="b-branches">
              {b.ifThen.map((x, j) => (
                <div key={j} className="b-branch">
                  <span className="b-if">If {x.ifThey.replace(/^if\s+/i, '')}</span>
                  <span className="b-then">{x.thenYou}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {b.avoid.length > 0 && (
        <div {...s[4]} className={`quick ${s[4].className}`}>
          <div className="q-h dont">Don't do this</div>
          {b.avoid.map((x, j) => <div key={j} className="q-line"><span className="dont">✕</span>{x}</div>)}
        </div>
      )}

      {b.say && <blockquote {...s[5]} className={`say ${s[5].className}`}>{b.say.replace(/^"|"$/g, '')}</blockquote>}
      {b.followUp && <p {...s[6]} className={`para ${s[6].className}`}>{b.followUp}</p>}
    </>
  )
}

/** A stored brief, checked field by field; anything malformed means no brief (the plain reply text is shown instead). */
export function briefOf(v: unknown): StrategyBrief | null {
  const b = v as any
  if (!b || typeof b !== 'object' || !Array.isArray(b.rows) || b.rows.length === 0) return null
  const strs = (x: unknown) => Array.isArray(x) ? x.filter((y): y is string => typeof y === 'string' && y.trim() !== '') : []
  const num = (x: unknown) => typeof x === 'number' && Number.isFinite(x) ? Math.max(0, Math.min(100, Math.round(x))) : null
  const rows = b.rows.map((r: any) => {
    const well = num(r?.well), bad = num(r?.bad), expected = num(r?.expected)
    if (typeof r?.move !== 'string' || well === null || bad === null || expected === null) return null
    const kind = ['HOLDS_UP', 'GAMBLE', 'BACKFIRES'].includes(r.kind) ? r.kind : 'GAMBLE'
    return { move: r.move, well, bad, expected, kind, recommended: r.recommended === true, pros: strs(r.pros), cons: strs(r.cons) }
  })
  const odds = num(b.oddsPct)
  if (rows.some((r: unknown) => r === null) || odds === null || typeof b.recommendation !== 'string' || !b.recommendation) return null
  const str = (x: unknown) => typeof x === 'string' ? x : ''
  return {
    read: str(b.read), oddsPct: odds, oddsLabel: str(b.oddsLabel) || 'It goes well', wellLabel: str(b.wellLabel) || 'take it well',
    badLabel: str(b.badLabel) || 'take it badly',
    weights: Array.isArray(b.weights) ? b.weights.filter((w: any) => typeof w?.label === 'string' && num(w?.pct) !== null) : [],
    rows, flips: Array.isArray(b.flips) ? b.flips.filter((f: unknown) => num(f) !== null) : [], robust: b.robust === true,
    recommendation: b.recommendation, why: str(b.why), plan: strs(b.plan),
    ifThen: Array.isArray(b.ifThen) ? b.ifThen.filter((x: any) => typeof x?.ifThey === 'string' && typeof x?.thenYou === 'string') : [],
    avoid: strs(b.avoid), say: typeof b.say === 'string' && b.say.trim() ? b.say : null, followUp: str(b.followUp),
    moveLabel: str(b.moveLabel) || 'Best move for now',
    mode: b.mode === 'compare' ? 'compare' : 'brief',
    weightsAreDefault: b.weightsAreDefault === true,
  }
}
