import { describe, expect, it } from 'vitest'
import { cardsOf, chipsFor, parseReply, pillOf } from '../Reply'
import { steadiness } from '../Brief'
import { at, blendOf, explain, kindOf, kindWord } from '../xray/grid'
import { groups } from '../Sidebar'
import type { ChatSummary, XRayRow } from '../api'

const row = (label: string, well: number, bad: number): XRayRow => ({ label, well, bad, expected: 0, kind: kindOf({ well, bad }) } as XRayRow)

describe('cardsOf: stored cards never break a reply', () => {
  it('fills in a missing options list (the bug that blanked the page)', () => {
    const c = cardsOf('{"quickAnswers":[{"label":"Usually engages","text":"Manager usually engages."}]}')
    expect(c).toEqual({ quickAnswers: [{ label: 'Usually engages', text: 'Manager usually engages.' }], options: [] })
  })
  it('fills in a missing quickAnswers list', () => {
    expect(cardsOf('{"options":[{"role":"Best","move":"Talk"}]}')?.quickAnswers).toEqual([])
  })
  it.each([
    ['null', null], ['empty', ''], ['bad JSON', '{oops'], ['JSON null', 'null'], ['a number', '42'], ['a string', '"x"'],
    ['lists of junk', '{"quickAnswers":[null,1,{"label":2}],"options":[{},null]}'], ['wrong types', '{"quickAnswers":"x","options":{}}'],
    ['both empty', '{"quickAnswers":[],"options":[]}'],
  ])('%s gives no cards', (_n, v) => expect(cardsOf(v as string | null)).toBeNull())
})

describe('pillOf', () => {
  it('reads the move, pattern and robustness', () => {
    expect(pillOf('{"move_to_implement":"Talk","game_theoretic_pattern":"Harmony","stress_verdict":"ROBUST"}')).toEqual({ move: 'Talk', pattern: 'Harmony', robust: true })
    expect(pillOf('{"move_to_implement":"Talk","stress_verdict":"FRAGILE"}')?.robust).toBe(false)
  })
  it.each([null, '', '{', '{}', '{"move_to_implement":""}', 'null', '[]'])('no pill for %s', v => expect(pillOf(v as string | null)).toBeNull())
})

describe('parseReply', () => {
  const full = [
    "What's happening underneath: You're worried he'll ask.",
    'Best move for now: Keep the meetup and settle it.',
    'What to say: "Hey, about that ₹200."',
    'Do this:', '• Send a short message', '• Keep the meetup',
    "Don't do this:", '• Postpone to dodge', '',
    'Is anything off the table for you here?',
  ].join('\n')
  it('splits a full coach reply into its blocks', () => {
    const b = parseReply(full)
    expect(b.map(x => x.kind)).toEqual(['lead', 'move', 'say', 'quick', 'para'])
    expect(b[1]).toMatchObject({ label: 'Best move for now', text: 'Keep the meetup and settle it.' })
    expect(b[2]).toMatchObject({ text: 'Hey, about that ₹200.' })
    expect(b[3]).toMatchObject({ dos: ['Send a short message', 'Keep the meetup'], donts: ['Postpone to dodge'] })
  })
  it('handles Clear-win moves and plain bullet lists', () => {
    const b = parseReply('Clear-win move: Do it.\n- one\n- two\n\nAfter.')
    expect(b.map(x => x.kind)).toEqual(['move', 'bullets', 'para'])
    expect(b[1]).toMatchObject({ items: ['one', 'two'] })
  })
  it('only a Don\'t list still makes a quick box', () => {
    expect(parseReply("Don't do this:\n- chase").slice(-1)[0]).toMatchObject({ kind: 'quick', dos: [], donts: ['chase'] })
  })
  it.each(['', '\n\n', 'Just one plain sentence.', 'x'.repeat(20000), '• orphan bullet', 'Do this:', '🙂'])('never throws on %j', t => {
    expect(() => parseReply(t)).not.toThrow()
  })
  it('a support-only reply (no engine sections) is plain paragraphs', () => {
    expect(parseReply('I hear you. That sounds really hard.\nIf you are in danger, call someone you trust.').every(b => b.kind === 'para')).toBe(true)
  })
})

describe('X-ray grid (port of XRayGrid.kt)', () => {
  const rows = [row('Repair', 80, 60), row('Boundary', 70, 55), row('Reactive', 90, 10)]
  it('classifies moves', () => {
    expect(kindOf({ well: 80, bad: 60 })).toBe('HOLDS_UP')
    expect(kindOf({ well: 40, bad: 20 })).toBe('BACKFIRES')
    expect(kindOf({ well: 90, bad: 10 })).toBe('GAMBLE')
    expect(kindOf({ well: 50, bad: 50 })).toBe('HOLDS_UP')
    expect(kindWord('GAMBLE')).toBe('A gamble')
  })
  it('blends linearly between the two outcomes', () => {
    expect(blendOf(rows[2], 1)).toBe(90); expect(blendOf(rows[2], 0)).toBe(10); expect(blendOf(rows[2], 0.5)).toBe(50)
  })
  it('keeps the ring on the coach\'s row when it ties or leads', () => {
    const g = at(rows, 0.5, 0)
    expect(g.bestIndex).toBe(0); expect(g.robust).toBe(true); expect(g.rows[0].expected).toBe(70)
  })
  it('flips to the gamble only when the odds are very high', () => {
    expect(at(rows, 0.95, 0).bestIndex).toBe(2)
    expect(at(rows, 0.95, 0).robust).toBe(false)
  })
  it('clamps odds outside 0..1', () => {
    expect(at(rows, 7, 0).odds).toBe(1); expect(at(rows, -3, 0).odds).toBe(0)
  })
  it('a bad prefer index still picks the leader', () => {
    expect(at(rows, 0.5, 99).bestIndex).toBe(0)
    expect(at(rows, 0.5, -1).bestIndex).toBe(0)
  })
  it('the coach row is ringed at every odds step it leads at, and best is always a valid row', () => {
    for (let p = 0; p <= 100; p++) {
      const g = at(rows, p / 100, 0)
      expect(g.bestIndex).toBeGreaterThanOrEqual(0); expect(g.bestIndex).toBeLessThan(rows.length)
      const top = Math.max(...g.rows.map(r => blendOf(r, p / 100)))
      expect(blendOf(g.rows[g.bestIndex], p / 100)).toBeCloseTo(top, 9)
    }
  })
  it('does not crash on no rows', () => {
    expect(() => at([], 0.5, 0)).not.toThrow()
  })
  it('explains each kind in words with the odds', () => {
    const g = at(rows, 0.5, 0)
    expect(explain(g.rows[0], 50)).toMatch(/Holds up either way.*60.*50%.*70/)
    expect(explain(g.rows[2], 50)).toMatch(/rides on the odds/)
    expect(explain({ ...row('x', 30, 20), expected: 25 }, 50)).toMatch(/Hurts either way/)
  })
})

describe('sidebar date groups', () => {
  const day = 86_400_000
  const c = (title: string, ago: number): ChatSummary => ({ projectId: title, title, domainType: 'GENERAL', updatedAt: Date.now() - ago, turns: 1 } as ChatSummary)
  it('groups like ChatGPT and keeps the order', () => {
    const midnight = new Date(); midnight.setHours(0, 0, 0, 0)
    const sinceMidnight = Date.now() - midnight.getTime()
    const g = groups([c('now', 0), c('yday', sinceMidnight + day / 2), c('week', 4 * day), c('month', 20 * day), c('old', 90 * day)])
    expect(g.map(([l]) => l)).toEqual(['Today', 'Yesterday', 'Previous 7 days', 'Previous 30 days', 'Older'])
    expect(g.map(([, l]) => l[0].title)).toEqual(['now', 'yday', 'week', 'month', 'old'])
  })
  it('no chats, no groups', () => expect(groups([])).toEqual([]))
})

describe('cardsOf: a stored strategy brief', () => {
  const brief = {
    read: 'r', oddsPct: 55, oddsLabel: 'She opens up', wellLabel: 'Open up', badLabel: 'Shut down', weights: [{ label: 'Relationships', pct: 50 }],
    rows: [{ move: 'Talk', well: 79, bad: 62, expected: 71, kind: 'HOLDS_UP', recommended: true, pros: ['p'], cons: ['c'] },
           { move: 'Wait', well: 50, bad: 40, expected: 46, kind: 'GAMBLE', recommended: false, pros: [], cons: [] }],
    flips: [80], robust: true, recommendation: 'Talk', why: 'w', plan: ['a'], ifThen: [{ ifThey: 'go quiet', thenYou: 'wait' }],
    avoid: [], say: null, followUp: 'f', moveLabel: 'Best move for now',
  }
  it('reads a brief and drops the plain options card', () => {
    const c = cardsOf(JSON.stringify({ quickAnswers: [], options: [{ role: 'Best', move: 'Talk' }], brief }))
    expect(c?.brief?.rows.map(r => r.expected)).toEqual([71, 46])
    expect(c?.options).toEqual([])
  })
  it.each([
    ['no rows', { ...brief, rows: [] }], ['a row without numbers', { ...brief, rows: [{ move: 'Talk' }] }],
    ['no recommendation', { ...brief, recommendation: '' }], ['no odds', { ...brief, oddsPct: 'x' }],
  ])('a brief with %s is ignored, so the plain reply shows', (_n, b) => {
    expect(cardsOf(JSON.stringify({ quickAnswers: [], options: [], brief: b }))).toBeNull()
  })
  it('keeps scores inside 0..100', () => {
    const c = cardsOf(JSON.stringify({ brief: { ...brief, rows: [{ ...brief.rows[0], well: 140, bad: -3 }] } }))
    expect([c?.brief?.rows[0].well, c?.brief?.rows[0].bad]).toEqual([100, 0])
  })
})

describe('steadiness: judged against the pick, not whichever row leads', () => {
  const base = { read: '', oddsLabel: '', wellLabel: '', badLabel: '', weights: [], flips: [], recommendation: 'x', why: '', plan: [], ifThen: [],
    avoid: [], say: null, followUp: '', moveLabel: '', mode: 'brief' as const, weightsAreDefault: true }
  const r = (move: string, well: number, bad: number, recommended = false) =>
    ({ move, well, bad, expected: Math.round(.5 * well + .5 * bad), kind: 'GAMBLE' as const, recommended, pros: [], cons: [] })
  it('says when the pick is not the top score, and where it would lead', () => {
    const b = { ...base, oddsPct: 50, robust: false, rows: [r('Push back', 96, 21), r('Accept', 100, 0, true), r('Trade', 82, 9)] }
    const s = steadiness(b)
    expect(s.leads).toBe(false)
    expect(s.text).toContain('not the top score at 50%')
    expect(s.text).toMatch(/only from \d+% up/)
  })
  it('says where a leading pick stops leading', () => {
    const b = { ...base, oddsPct: 50, robust: true, rows: [r('Talk', 80, 60, true), r('Wait', 95, 20)] }
    expect(steadiness(b)).toEqual({ text: 'Holds up either way · stays the top score unless the odds rise to 73%', leads: true })
  })
  it('a pick that leads everywhere says so', () => {
    const b = { ...base, oddsPct: 30, robust: true, rows: [r('Talk', 90, 70, true), r('Wait', 50, 20)] }
    expect(steadiness(b).text).toBe('Holds up either way · the top score at any odds')
  })
})

describe('reply modes', () => {
  it('reads the mode and a stored answer', () => {
    const c = cardsOf(JSON.stringify({ quickAnswers: [], options: [], mode: 'answer', answer: { answer: 'It costs more if she shuts down.', bottomLine: 'Still talk.', followUp: 'f', move: 'Talk' } }))
    expect(c?.mode).toBe('answer')
    expect(c?.answer?.bottomLine).toBe('Still talk.')
  })
  it('keeps the mode on a classic card so it gets no chips', () => {
    expect(cardsOf('{"quickAnswers":[],"options":[],"mode":"script"}')?.mode).toBe('script')
  })
  it('ignores an unknown mode and a broken answer', () => {
    const c = cardsOf(JSON.stringify({ quickAnswers: [{ label: 'a', text: 'b' }], options: [], mode: 'shout', answer: { answer: 3 } }))
    expect(c).toEqual({ quickAnswers: [{ label: 'a', text: 'b' }], options: [] })
  })
  it('reads the compare mode and the default-weighting flag on a brief', () => {
    const b = { read: '', oddsPct: 50, oddsLabel: 'x', wellLabel: 'w', badLabel: 'b', weights: [], flips: [], robust: true, recommendation: 'Talk',
      why: 'Talk beats waiting.', plan: [], ifThen: [], avoid: [], say: null, followUp: '', moveLabel: 'm', mode: 'compare', weightsAreDefault: true,
      rows: [{ move: 'Talk', well: 80, bad: 60, expected: 70, kind: 'HOLDS_UP', recommended: true, pros: [], cons: [] }] }
    const c = cardsOf(JSON.stringify({ brief: b, mode: 'compare' }))
    expect([c?.brief?.mode, c?.brief?.weightsAreDefault]).toEqual(['compare', true])
  })
  it.each([
    ['brief', { draft: true, prosCons: false, pushBack: true }],
    ['compare', { draft: true, prosCons: false, pushBack: true }],
    ['answer', { draft: true, prosCons: true, pushBack: true }],
    ['script', { draft: false, prosCons: false, pushBack: false }],
    ['', { draft: false, prosCons: false, pushBack: false }],
  ] as const)('chips for %s', (mode, want) => expect(chipsFor(mode)).toEqual(want))
})
