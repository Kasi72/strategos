import type { AnswerCard, ReplyCards, ReplyMode } from './api'
import Brief, { briefOf } from './Brief'

// A coach reply laid out like the app's CoachCard: it flows onto the page (no bubble), the words to say are set off as
// a quote, "Do this / Don't do this" become a quick-read box, and the engine's pill opens the X-ray.

type Block =
  | { kind: 'lead'; text: string }
  | { kind: 'move'; label: string; text: string }
  | { kind: 'say'; text: string }
  | { kind: 'para'; text: string }
  | { kind: 'heading'; text: string }
  | { kind: 'bullets'; items: string[] }
  | { kind: 'quick'; dos: string[]; donts: string[] }

const LEAD = /^What's happening underneath:\s*/
const MOVE = /^(Clear-win move|Best move for now):\s*/
const SAY = /^What to say:\s*/

export function parseReply(text: string): Block[] {
  const lines = text.split('\n')
  const out: Block[] = []
  let dos: string[] | null = null, donts: string[] | null = null, list: 'do' | 'dont' | 'plain' | null = null
  const quick = () => {
    if (dos || donts) { out.push({ kind: 'quick', dos: dos ?? [], donts: donts ?? [] }); dos = donts = null }
  }
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) { list = list === 'plain' ? null : list; continue }
    const bullet = line.match(/^[•\-]\s+(.*)$/)
    if (bullet) {
      if (list === 'do') dos!.push(bullet[1])
      else if (list === 'dont') donts!.push(bullet[1])
      else {
        const last = out[out.length - 1]
        if (last?.kind === 'bullets') last.items.push(bullet[1]); else out.push({ kind: 'bullets', items: [bullet[1]] })
        list = 'plain'
      }
      continue
    }
    if (/^Do this:?$/.test(line)) { dos = dos ?? []; list = 'do'; continue }
    if (/^Don't do this:?$/.test(line)) { donts = donts ?? []; list = 'dont'; continue }
    if (list === 'do' || list === 'dont') { quick(); list = null }
    if (LEAD.test(line)) out.push({ kind: 'lead', text: line.replace(LEAD, '') })
    else if (MOVE.test(line)) out.push({ kind: 'move', label: line.match(MOVE)![1], text: line.replace(MOVE, '') })
    else if (SAY.test(line)) out.push({ kind: 'say', text: line.replace(SAY, '').replace(/^"|"$/g, '') })
    else if (line.length < 48 && !/[.?!:"]$/.test(line) && !line.includes(':')) out.push({ kind: 'heading', text: line })
    else out.push({ kind: 'para', text: line })
  }
  quick()
  return out
}

/** Cards from a stored turn. Tolerates missing lists and bad JSON: a card problem must never take the reply down. */
export function cardsOf(json?: string | null): ReplyCards | null {
  if (!json) return null
  try {
    const c = JSON.parse(json)
    const quickAnswers = Array.isArray(c?.quickAnswers) ? c.quickAnswers.filter((q: any) => q && typeof q.label === 'string' && typeof q.text === 'string') : []
    const options = Array.isArray(c?.options) ? c.options.filter((o: any) => o && typeof o.move === 'string') : []
    const mode: ReplyMode = MODES.includes(c?.mode) ? c.mode : ''
    const withMode = <T extends object>(x: T) => (mode ? { ...x, mode } : x)
    const brief = briefOf(c?.brief)
    if (brief) return withMode({ quickAnswers, options: [], brief })
    const answer = answerOf(c?.answer)
    if (answer) return withMode({ quickAnswers, options: [], answer })
    return quickAnswers.length || options.length || mode ? withMode({ quickAnswers, options }) : null
  } catch { return null }
}

const MODES: ReplyMode[] = ['brief', 'compare', 'answer', 'script']

/** A stored answer, or null when it is not usable (the reply text is shown instead). */
export function answerOf(v: unknown): AnswerCard | null {
  const a = v as any
  if (!a || typeof a.answer !== 'string' || !a.answer.trim() || typeof a.bottomLine !== 'string') return null
  return { answer: a.answer, bottomLine: a.bottomLine, followUp: typeof a.followUp === 'string' ? a.followUp : '', move: typeof a.move === 'string' ? a.move : '' }
}

/** The chips under the latest reply. "Draft exact words" only where the reply has no words yet; nothing on a script. */
export function chipsFor(mode: ReplyMode | undefined): { draft: boolean; prosCons: boolean; pushBack: boolean } {
  const strategic = mode === 'brief' || mode === 'compare' || mode === 'answer'
  return { draft: strategic, prosCons: mode === 'answer', pushBack: strategic }
}

export const PROS_CONS_ASK = 'What are the pros and cons of my options?'
export const PUSH_BACK_ASK = 'What if they push back?'

export interface Pill { move: string; pattern?: string; robust: boolean }

export function pillOf(directiveJson?: string | null): Pill | null {
  if (!directiveJson) return null
  try {
    const d = JSON.parse(directiveJson)
    if (!d.move_to_implement) return null
    return { move: d.move_to_implement, pattern: d.game_theoretic_pattern ?? undefined, robust: d.stress_verdict !== 'FRAGILE' }
  } catch { return null }
}

export default function Reply({ text, pill, cards, animate, onXRay, onSend, onDraft, busy, answered }: {
  text: string; pill: Pill | null; cards?: ReplyCards | null; animate: boolean
  onXRay: () => void; onSend: (text: string) => void; onDraft?: () => void; busy?: boolean; answered: boolean
}) {
  // A brief or an answer draws from its own structure; the reply text is its plain-text twin (for memory and other clients).
  const blocks = cards?.brief || cards?.answer ? [] : parseReply(text)
  let n = 0
  const step = () => (animate ? { style: { animationDelay: `${(n++) * 90}ms` }, className: 'rise' } : { className: '' })
  const chips = chipsFor(cards?.mode)
  const showChips = !answered && !!pill && (chips.draft || chips.prosCons || chips.pushBack)
  const a = cards?.answer
  const sa = a ? [step(), step(), step()] : []
  return (
    <div className="reply">
      {cards?.brief && <Brief b={cards.brief} step={step} />}
      {a && <>
        <div {...sa[0]} className={`answer ${sa[0].className}`}>{a.answer.split(/\n{2,}/).map((p, i) => <p key={i}>{p}</p>)}</div>
        {a.bottomLine && <div {...sa[1]} className={`move ${sa[1].className}`}><span className="move-label">Bottom line</span><p>{a.bottomLine}</p></div>}
        {a.followUp && <p {...sa[2]} className={`para ${sa[2].className}`}>{a.followUp}</p>}
      </>}
      {blocks.map((b, i) => {
        const s = step()
        switch (b.kind) {
          case 'lead': return <p key={i} {...s} className={`lead ${s.className}`}>{b.text}</p>
          case 'move': return (
            <div key={i} {...s} className={`move ${s.className}`}>
              <span className="move-label">{b.label}</span>
              <p>{b.text}</p>
            </div>
          )
          case 'say': return <blockquote key={i} {...s} className={`say ${s.className}`}>{b.text}</blockquote>
          case 'heading': return <h4 key={i} {...s} className={`r-h ${s.className}`}>{b.text}</h4>
          case 'bullets': return <ul key={i} {...s} className={`r-ul ${s.className}`}>{b.items.map((x, j) => <li key={j}>{x}</li>)}</ul>
          case 'quick': return (
            <div key={i} {...s} className={`quick ${s.className}`}>
              {b.dos.length > 0 && <><div className="q-h do">Do this</div>{b.dos.map((x, j) => <div key={j} className="q-line"><span className="do">✓</span>{x}</div>)}</>}
              {b.donts.length > 0 && <><div className="q-h dont">Don't do this</div>{b.donts.map((x, j) => <div key={j} className="q-line"><span className="dont">✕</span>{x}</div>)}</>}
            </div>
          )
          default: return <p key={i} {...s} className={`para ${s.className}`}>{b.text}</p>
        }
      })}

      {cards && !answered && cards.quickAnswers.length > 0 && (
        <div {...step()} className={`chips-row ${animate ? 'rise' : ''}`}>
          <span className="chips-label">Quick answer</span>
          {cards.quickAnswers.map(q => <button key={q.label} className="chip-btn" onClick={() => onSend(q.text)}>{q.label}</button>)}
        </div>
      )}
      {cards && cards.options.length > 0 && (
        <div {...step()} className={`options ${animate ? 'rise' : ''}`}>
          <div className="options-h">Your options</div>
          {cards.options.map(o => (
            <button key={o.move} className="option" onClick={() => onSend(`Tell me more about this option: ${o.move}`)}>
              <span className="option-role">{o.role}</span>
              <span className="option-move">{o.move}</span>
              <span className="option-go" aria-hidden>›</span>
            </button>
          ))}
        </div>
      )}

      {/* The X-ray opens from every reply; the engine's pill joins it when this turn named a move. */}
      <div {...step()} className={`pill-row ${animate ? 'rise' : ''}`}>
        {pill && (
          <button className="engine-pill" onClick={onXRay} title="Open the X-ray">
            <i />
            <span>{[pill.move, pill.pattern, pill.robust ? 'Robust' : 'Fragile'].filter(Boolean).join(' · ')}</span>
          </button>
        )}
        <button className="xray-open" onClick={onXRay}>X-ray</button>
      </div>
      {showChips && (
        <div {...step()} className={`chips-row next-row ${animate ? 'rise' : ''}`} aria-label="Next">
          {chips.draft && onDraft && <button className="chip-btn primary-chip" disabled={busy} onClick={onDraft}>Draft exact words</button>}
          {chips.prosCons && <button className="chip-btn" disabled={busy} onClick={() => onSend(PROS_CONS_ASK)}>Pros &amp; cons</button>}
          {chips.pushBack && <button className="chip-btn" disabled={busy} onClick={() => onSend(PUSH_BACK_ASK)}>What if they push back?</button>}
        </div>
      )}
    </div>
  )
}
