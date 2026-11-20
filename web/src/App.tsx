import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  api, ApiError, DomainType, Health, JournalEntry, LlmSettings, ProjectState, ProjectSummary, ThreadContext,
  TopicThread, TurnRecord, token,
} from './api'

type Tab = 'talk' | 'journal' | 'settings'

const DOMAINS: { value: DomainType; label: string }[] = [
  { value: 'PERSONAL_RELATIONSHIP', label: 'Personal / relationship' },
  { value: 'COMMERCIAL_NEGOTIATION', label: 'Deal / negotiation' },
  { value: 'COALITION_ORG', label: 'Team / organisation' },
  { value: 'GENERAL', label: 'Something else' },
]

const LAST = 'strategos.last'
const remember = (p?: string, t?: string) => { try { localStorage.setItem(LAST, JSON.stringify({ p, t })) } catch { /* ignore */ } }
const recall = (): { p?: string; t?: string } => {
  // A link can open a project and thread directly: /?p=<project>&t=<thread>
  const q = new URLSearchParams(window.location.search)
  if (q.get('p')) return { p: q.get('p') ?? undefined, t: q.get('t') ?? undefined }
  try { return JSON.parse(localStorage.getItem(LAST) ?? '{}') } catch { return {} }
}

export default function App() {
  const [tab, setTab] = useState<Tab>('talk')
  const [projects, setProjects] = useState<ProjectSummary[]>([])
  const [state, setState] = useState<ProjectState | null>(null)
  const [threadId, setThreadId] = useState<string | undefined>()
  const [navOpen, setNavOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadProjects = useCallback(async () => {
    try { setProjects(await api.projects()); setError(null) } catch (e) { setError(message(e)) }
  }, [])

  const openProject = useCallback(async (id: string, preferThread?: string) => {
    try {
      const s = await api.project(id)
      setState(s)
      const t = s.threads.find(x => x.id === preferThread) ?? s.threads[0]
      setThreadId(t?.id)
      remember(id, t?.id)
    } catch (e) { setError(message(e)) }
  }, [])

  const refreshState = useCallback(async () => {
    if (state) setState(await api.project(state.project.id))
  }, [state])

  useEffect(() => {
    loadProjects().then(() => { const { p, t } = recall(); if (p) openProject(p, t) })
  }, [loadProjects, openProject])

  const project = state?.project
  const thread = state?.threads.find(t => t.id === threadId)

  return (
    <div className="shell">
      <aside className={`nav ${navOpen ? 'open' : ''}`}>
        <div className="brand">Strategos</div>
        <ProjectList projects={projects} current={project?.id} onOpen={id => { openProject(id); setNavOpen(false); setTab('talk') }}
          onCreated={async p => { await loadProjects(); await openProject(p.id); setNavOpen(false); setTab('talk') }} />
        {state && (
          <ThreadList threads={state.threads} current={threadId} projectId={state.project.id}
            onOpen={id => { setThreadId(id); remember(state.project.id, id); setNavOpen(false); setTab('talk') }}
            onCreated={async t => { await openProject(state.project.id, t.id); setNavOpen(false) }} />
        )}
      </aside>
      {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} />}

      <main className="main">
        <header className="topbar">
          <button className="icon" aria-label="Projects and threads" onClick={() => setNavOpen(true)}>☰</button>
          <div className="title">
            <div className="t1">{tab === 'talk' ? (project?.title ?? 'Start a project') : tab === 'journal' ? 'Journal' : 'Settings'}</div>
            {tab === 'talk' && thread && <div className="t2">{thread.title}</div>}
          </div>
          {tab === 'talk' && project && <Gauges trust={project.trust} tension={project.tension} />}
        </header>

        {error && <div className="banner" role="alert">{error} <button className="link" onClick={() => { setError(null); loadProjects() }}>Retry</button></div>}

        <section className="content">
          {tab === 'talk' && (state && thread
            ? <Talk key={thread.id} thread={thread} state={state} onTurn={refreshState} onError={setError} />
            : <Empty hasProject={!!state} onNew={() => setNavOpen(true)} />)}
          {tab === 'journal' && <Journal projects={projects} onSaved={refreshState} />}
          {tab === 'settings' && <Settings />}
        </section>

        <nav className="tabs">
          {(['talk', 'journal', 'settings'] as Tab[]).map(t => (
            <button key={t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>
              {t === 'talk' ? 'Talk' : t === 'journal' ? 'Journal' : 'Settings'}
            </button>
          ))}
        </nav>
      </main>
    </div>
  )
}

function message(e: unknown) { return e instanceof Error ? e.message : 'Something went wrong' }

function Gauges({ trust, tension }: { trust: number; tension: number }) {
  return (
    <div className="gauges" title="Trust and tension, smoothed over the conversation">
      <span><i className="dot trust" />Trust {Math.round(trust)}</span>
      <span><i className="dot tension" />Tension {Math.round(tension)}</span>
    </div>
  )
}

function Empty({ hasProject, onNew }: { hasProject: boolean; onNew: () => void }) {
  return (
    <div className="empty">
      <h2>{hasProject ? 'Open a thread' : 'What are you working through?'}</h2>
      <p>{hasProject ? 'Pick a thread, or start a new one for a different topic in this project.'
        : 'Start a project for a relationship, a negotiation or a decision. Strategos keeps its memory for the whole month.'}</p>
      <button className="primary" onClick={onNew}>{hasProject ? 'Threads' : 'New project'}</button>
    </div>
  )
}

function ProjectList({ projects, current, onOpen, onCreated }: {
  projects: ProjectSummary[]; current?: string; onOpen: (id: string) => void; onCreated: (p: ProjectSummary) => void
}) {
  const [adding, setAdding] = useState(false)
  const [title, setTitle] = useState('')
  const [domain, setDomain] = useState<DomainType>('PERSONAL_RELATIONSHIP')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return
    const p = await api.createProject(title.trim(), domain)
    setTitle(''); setAdding(false); onCreated(p)
  }
  return (
    <div className="group">
      <div className="group-h"><span>Projects</span><button className="link" onClick={() => setAdding(a => !a)}>{adding ? 'Cancel' : '+ New'}</button></div>
      {adding && (
        <form className="stack" onSubmit={submit}>
          <input autoFocus placeholder="e.g. Maya, FinCorp renewal" value={title} onChange={e => setTitle(e.target.value)} />
          <select value={domain} onChange={e => setDomain(e.target.value as DomainType)}>
            {DOMAINS.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
          </select>
          <button className="primary" type="submit">Create</button>
        </form>
      )}
      {projects.map(p => (
        <button key={p.id} className={`item ${p.id === current ? 'on' : ''}`} onClick={() => onOpen(p.id)}>
          <span>{p.title}</span><small>{DOMAINS.find(d => d.value === p.domainType)?.label}</small>
        </button>
      ))}
    </div>
  )
}

function ThreadList({ threads, current, projectId, onOpen, onCreated }: {
  threads: TopicThread[]; current?: string; projectId: string; onOpen: (id: string) => void; onCreated: (t: TopicThread) => void
}) {
  const [title, setTitle] = useState('')
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const t = await api.createThread(projectId, title.trim() || 'Main')
    setTitle(''); onCreated(t)
  }
  return (
    <div className="group">
      <div className="group-h"><span>Threads</span></div>
      {threads.map(t => (
        <button key={t.id} className={`item ${t.id === current ? 'on' : ''}`} onClick={() => onOpen(t.id)}>
          <span>{t.title}</span><small>{t.turnCount} turns</small>
        </button>
      ))}
      <form className="row" onSubmit={submit}>
        <input placeholder={threads.length ? 'New topic…' : 'First thread, e.g. Main'} value={title} onChange={e => setTitle(e.target.value)} />
        <button type="submit">Add</button>
      </form>
    </div>
  )
}

interface Pending { text: string; failed?: string }

function Talk({ thread, state, onTurn, onError }: {
  thread: TopicThread; state: ProjectState; onTurn: () => void; onError: (e: string | null) => void
}) {
  const [turns, setTurns] = useState<TurnRecord[]>([])
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState<Pending | null>(null)
  const [xray, setXray] = useState<TurnRecord | null>(null)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => { api.turns(thread.id).then(setTurns).catch(e => onError(message(e))) }, [thread.id, onError])
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [turns.length, pending])

  const send = async (text: string) => {
    if (!text.trim() || (pending && !pending.failed)) return
    setPending({ text }); setDraft('')
    try {
      await api.send(thread.id, text)
      setTurns(await api.turns(thread.id))
      setPending(null)
      onTurn()
    } catch (e) {
      setPending({ text, failed: e instanceof ApiError && e.retryable ? `${e.message}` : message(e) })
    }
  }

  return (
    <div className="talk">
      <div className="messages">
        {turns.length === 0 && !pending && (
          <div className="hint">Tell me what's going on, in your own words: who is involved, what happened, what you're weighing.
            Paste a whole chat if you have one. I'll read all of it.</div>
        )}
        {turns.map(t => (
          <div key={t.id} className="turn">
            <div className="bubble me">{t.userMessage}</div>
            <div className="bubble them">
              <Reply text={t.assistantReply} />
              {t.engineDirectiveJson && <button className="xray-btn" onClick={() => setXray(t)}>X-Ray</button>}
            </div>
          </div>
        ))}
        {pending && (
          <div className="turn">
            <div className="bubble me">{pending.text}</div>
            {pending.failed
              ? <div className="bubble them error">{pending.failed} <button className="link" onClick={() => send(pending.text)}>Try again</button></div>
              : <div className="bubble them thinking"><span /><span /><span /> Reading, running the engine, writing…</div>}
          </div>
        )}
        <div ref={end} />
      </div>
      <form className="composer" onSubmit={e => { e.preventDefault(); send(draft) }}>
        <textarea rows={1} placeholder="Message" value={draft} onChange={e => setDraft(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && window.matchMedia('(min-width: 720px)').matches) { e.preventDefault(); send(draft) } }} />
        <button className="primary" type="submit" disabled={!draft.trim() || (!!pending && !pending.failed)}>Send</button>
      </form>
      {xray && <XRay turn={xray} thread={thread} state={state} onClose={() => setXray(null)} onChecked={onTurn} />}
    </div>
  )
}

/** The reply keeps its own structure: labelled lines ("Best move for now: …") get their label set apart, bullets stay bullets. */
function Reply({ text }: { text: string }) {
  return (
    <div className="reply">
      {text.split(/\n{2,}/).map((block, i) => (
        <div key={i} className="block">
          {block.split('\n').map((line, j) => {
            const bullet = line.match(/^\s*[•\-]\s+(.*)$/)
            if (bullet) return <div key={j} className="li">{bullet[1]}</div>
            const label = line.match(/^([A-Z][^:]{2,40}):\s+(.*)$/)
            if (label) return <p key={j}><b>{label[1]}</b> {label[2]}</p>
            return <p key={j}>{line}</p>
          })}
        </div>
      ))}
    </div>
  )
}

function XRay({ turn, thread, state, onClose, onChecked }: {
  turn: TurnRecord; thread: TopicThread; state: ProjectState; onClose: () => void; onChecked: () => void
}) {
  const d = turn.engineDirectiveJson ? JSON.parse(turn.engineDirectiveJson) as Record<string, any> : {}
  const [ctx, setCtx] = useState<ThreadContext | null>(null)
  useEffect(() => { api.context(thread.id).then(setCtx).catch(() => setCtx(null)) }, [thread.id])
  const max = Math.max(1, ...state.options.map(o => Math.abs(o.net)))
  const blocked: string[] = (d.blocked_or_dominated_moves ?? []).map((b: any) => typeof b === 'string' ? b : b.move)

  return (
    <div className="drawer-wrap" onClick={onClose}>
      <div className="drawer" role="dialog" aria-label="X-Ray" onClick={e => e.stopPropagation()}>
        <div className="drawer-h"><b>X-Ray</b><button className="icon" aria-label="Close" onClick={onClose}>✕</button></div>

        <h4>The engine's pick</h4>
        <p className="pick">{d.move_to_implement ?? d.recommended_move ?? 'No confident pick yet'}</p>
        <div className="chips">
          {d.stress_verdict && <span className={`chip ${d.stress_verdict === 'FRAGILE' ? 'warn' : ''}`}>{d.stress_verdict === 'FRAGILE' ? 'Fragile' : 'Robust'}</span>}
          {d.game_theoretic_pattern && <span className="chip">{d.game_theoretic_pattern}</span>}
          {d.confidence?.tier && <span className="chip">Confidence: {String(d.confidence.tier).toLowerCase()}</span>}
        </div>
        {d.safe_fallback_move && <p><b>If that's too much:</b> {d.safe_fallback_move}</p>}
        {blocked.length > 0 && <><h4>Traps</h4><ul>{blocked.map(b => <li key={b}>{b}</li>)}</ul></>}

        <h4>Your options, scored by the engine</h4>
        <div className="bars">
          {state.options.map(o => (
            <div key={o.label} className="bar">
              <div className="bar-l"><span>{o.label}</span>{o.posture && <small>{o.posture}</small>}</div>
              <div className="bar-t"><div className={`bar-f ${o.posture === 'reactive' ? 'neg' : ''}`} style={{ width: `${Math.max(4, Math.abs(o.net) / max * 100)}%` }} /></div>
            </div>
          ))}
        </div>

        {state.priors && (
          <>
            <h4>How they tend to respond (learned)</h4>
            <Prior label="Softens when you repair" v={state.priors.probSoftensOnRepair} />
            <Prior label="Respects a boundary" v={state.priors.probRespectsBoundary} />
            <Prior label="Escalates when you react" v={state.priors.probEscalatesOnReactive} />
          </>
        )}

        {ctx && ctx.memory.currentFacts.length > 0 && (
          <>
            <h4>Remembered ({ctx.memory.backend})</h4>
            <ul className="facts">
              {ctx.memory.currentFacts.slice(0, 8).map(f => <li key={f.id}><small>{new Date(f.validFrom).toLocaleDateString()}</small> {f.subject} — {f.predicate}: {f.statement}</li>)}
              {ctx.memory.superseded.slice(0, 3).map(f => <li key={f.id} className="old"><small>no longer true</small> {f.statement}</li>)}
            </ul>
          </>
        )}

        {state.postNotes.length > 0 && (
          <>
            <h4>From your journal</h4>
            {state.postNotes.slice(0, 2).map(n => <div key={n.id} className="note"><b>{n.habitOrTrigger}</b><p>Do: {n.doThis}</p><p>{n.dontDoThis}</p></div>)}
          </>
        )}

        <QuickCheck thread={thread} options={state.options.map(o => o.label)} onDone={onChecked} />
      </div>
    </div>
  )
}

function Prior({ label, v }: { label: string; v: number }) {
  return <div className="prior"><span>{label}</span><div className="bar-t"><div className="bar-f" style={{ width: `${Math.round(v * 100)}%` }} /></div><small>{Math.round(v * 100)}%</small></div>
}

function QuickCheck({ thread, options, onDone }: { thread: TopicThread; options: string[]; onDone: () => void }) {
  const [move, setMove] = useState(options[0] ?? '')
  const [reaction, setReaction] = useState('softened')
  const [feel, setFeel] = useState('')
  const [done, setDone] = useState<string | null>(null)
  if (options.length === 0) return null
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const r = await api.quickCheck(thread.id, move, reaction, feel || undefined)
    const a = r.priorsBefore?.probSoftensOnRepair, b = r.priorsAfter?.probSoftensOnRepair
    setDone(a !== undefined && b !== undefined && a !== b ? 'Noted. What I expect from them has shifted.' : 'Noted.')
    onDone()
  }
  return (
    <form className="stack quick" onSubmit={submit}>
      <h4>How did it go?</h4>
      <select value={move} onChange={e => setMove(e.target.value)}>{options.map(o => <option key={o}>{o}</option>)}</select>
      <select value={reaction} onChange={e => setReaction(e.target.value)}>
        <option value="softened">They softened or opened up</option>
        <option value="withdrew_or_escalated">They pulled away or pushed back</option>
        <option value="none">Nothing changed yet</option>
      </select>
      <input placeholder="How do you feel? (optional)" value={feel} onChange={e => setFeel(e.target.value)} />
      <button className="primary" type="submit">Save</button>
      {done && <p className="ok">{done}</p>}
    </form>
  )
}

function Journal({ projects, onSaved }: { projects: ProjectSummary[]; onSaved: () => void }) {
  const [entries, setEntries] = useState<JournalEntry[]>([])
  const [text, setText] = useState('')
  const [toast, setToast] = useState<string | null>(null)
  const load = useCallback(() => api.journal().then(setEntries).catch(() => setEntries([])), [])
  useEffect(() => { load() }, [load])
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!text.trim()) return
    const r = await api.saveJournal(text.trim())
    setText(''); setToast(r.message); load()
    setTimeout(() => { setToast(null); load(); onSaved() }, 6000)
  }
  return (
    <div className="page">
      <p className="muted">What you feel and how you'd like to grow. Private. Nothing here is judged; if it touches one of your projects, a small Do / Don't appears there.</p>
      <form className="stack" onSubmit={submit}>
        <textarea rows={5} placeholder="Write freely…" value={text} onChange={e => setText(e.target.value)} />
        <button className="primary" type="submit" disabled={!text.trim()}>Save privately</button>
      </form>
      {toast && <div className="toast">{toast}</div>}
      <div className="entries">
        {entries.map(e => (
          <div key={e.id} className="entry">
            <small>{new Date(e.createdAt).toLocaleString()}{e.isSignificantShift && ` · added a note to ${e.affectedProjectIds.map(id => projects.find(p => p.id === id)?.title ?? 'a project').join(', ')}`}</small>
            <p>{e.content}</p>
          </div>
        ))}
      </div>
    </div>
  )
}

function Settings() {
  const [health, setHealth] = useState<Health | null>(null)
  const [llm, setLlm] = useState<LlmSettings | null>(null)
  const [form, setForm] = useState({ provider: 'deepseek', model: '', baseUrl: '', apiKey: '' })
  const [auth, setAuth] = useState(token.get())
  const [saved, setSaved] = useState<string | null>(null)
  const load = useCallback(async () => {
    try {
      setHealth(await api.health())
      const l = await api.llm(); setLlm(l)
      setForm(f => ({ ...f, provider: l.provider, model: l.model, baseUrl: l.baseUrl ?? '' }))
    } catch (e) { setSaved(message(e)) }
  }, [])
  useEffect(() => { load() }, [load])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const l = await api.saveLlm({ provider: form.provider, model: form.model || undefined, baseUrl: form.baseUrl || undefined, apiKey: form.apiKey || undefined })
      setLlm(l); setForm(f => ({ ...f, apiKey: '' })); setSaved('Saved. The key is kept in the server\'s memory only.')
      load()
    } catch (e) { setSaved(message(e)) }
  }
  return (
    <div className="page">
      <h3>Status</h3>
      {health && (
        <ul className="status">
          <li className={health.database ? 'ok' : 'bad'}>Database</li>
          <li className={health.memoryHealthy ? 'ok' : 'bad'}>Memory: {health.memory}</li>
          <li className={llm?.hasKey ? 'ok' : 'bad'}>AI: {health.llmProvider} {health.llmModel}</li>
        </ul>
      )}
      <h3>AI provider</h3>
      <form className="stack" onSubmit={save}>
        <label>Provider<select value={form.provider} onChange={e => setForm({ ...form, provider: e.target.value })}>
          <option value="deepseek">DeepSeek</option><option value="openai-compatible">Other (OpenAI-compatible)</option>
        </select></label>
        <label>Model<input value={form.model} placeholder="deepseek-chat" onChange={e => setForm({ ...form, model: e.target.value })} /></label>
        {form.provider !== 'deepseek' && <label>Base URL<input value={form.baseUrl} placeholder="https://…/v1" onChange={e => setForm({ ...form, baseUrl: e.target.value })} /></label>}
        <label>API key<input type="password" autoComplete="off" value={form.apiKey} placeholder={llm?.hasKey ? 'Set (leave empty to keep)' : 'Paste your key'} onChange={e => setForm({ ...form, apiKey: e.target.value })} /></label>
        <button className="primary" type="submit">Save</button>
      </form>
      <h3>Server access</h3>
      <form className="stack" onSubmit={e => { e.preventDefault(); token.set(auth.trim()); setSaved('Access token saved on this device.'); load() }}>
        <label>Access token<input type="password" autoComplete="off" value={auth} placeholder="Only if the server asks for one" onChange={e => setAuth(e.target.value)} /></label>
        <button type="submit">Save on this device</button>
      </form>
      {saved && <p className="ok">{saved}</p>}
    </div>
  )
}
