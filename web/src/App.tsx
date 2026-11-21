import { FormEvent, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { api, ApiError, ChatSummary, Health, JournalEntry, LlmSettings, TurnRecord, token } from './api'
import Reply, { cardsOf, pillOf } from './Reply'
import Guard from './Guard'
import XRay from './xray/XRay'
import { ArrowUp, Menu, SquarePen } from 'lucide-react'
import Sidebar, { Theme } from './Sidebar'
import { LogoMark } from './Logo'

// A ChatGPT-style shell: chats down the left (each one continuous thread), the conversation in the middle, and the
// X-ray sliding over from the right. Routes live in the hash so Back works and a chat can be linked: #/c/<id>.

type Route = { page: 'chat'; id?: string; xray?: 'arena' | 'board' } | { page: 'journal' } | { page: 'settings' }

const parse = (): Route => {
  const h = window.location.hash.replace(/^#\/?/, '')
  if (h.startsWith('c/')) { const [id, x] = h.slice(2).split('/'); return { page: 'chat', id, xray: x === 'xray' ? 'arena' : x === 'board' ? 'board' : undefined } }
  if (h === 'journal') return { page: 'journal' }
  if (h === 'settings') return { page: 'settings' }
  return { page: 'chat' }
}
const go = (r: Route) => { window.location.hash = r.page === 'chat' ? (r.id ? `/c/${r.id}` : '/') : `/${r.page}` }

const store = {
  get: (k: string) => { try { return localStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* private mode: keep it for this visit */ } },
}

function applyTheme(t: Theme) {
  const root = document.documentElement
  if (t === 'system') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', t)
}

export default function App() {
  const [route, setRoute] = useState<Route>(parse)
  const [chats, setChats] = useState<ChatSummary[]>([])
  const [chatsLoaded, setChatsLoaded] = useState(false)
  const [navOpen, setNavOpen] = useState(false)
  const [collapsed, setCollapsed] = useState(() => store.get('side') === 'rail')
  const [theme, setTheme] = useState<Theme>(() => (store.get('theme') as Theme) || 'system')
  const [searchSignal, setSearchSignal] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { applyTheme(theme); store.set('theme', theme) }, [theme])
  useEffect(() => { store.set('side', collapsed ? 'rail' : 'full') }, [collapsed])
  useEffect(() => {
    const on = () => { setRoute(parse()); setNavOpen(false) }
    window.addEventListener('hashchange', on); return () => window.removeEventListener('hashchange', on)
  }, [])
  const loadChats = useCallback(() => api.chats().then(c => { setChats(c); setChatsLoaded(true); setError(null) }).catch(e => setError(e.message)), [])
  useEffect(() => { loadChats() }, [loadChats])

  const phone = () => window.matchMedia('(max-width: 760px)').matches
  const toggleSide = useCallback(() => { if (phone()) setNavOpen(o => !o); else setCollapsed(c => !c) }, [])

  // ChatGPT's shortcuts: new chat, search chats, toggle the sidebar.
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return
      const k = e.key.toLowerCase()
      if (e.shiftKey && k === 'o') { e.preventDefault(); go({ page: 'chat' }) }
      else if (!e.shiftKey && k === 'k') { e.preventDefault(); if (phone()) setNavOpen(true); else setCollapsed(false); setSearchSignal(n => n + 1) }
      else if (e.shiftKey && k === 's') { e.preventDefault(); toggleSide() }
    }
    window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key)
  }, [toggleSide])

  const chat = route.page === 'chat' && route.id ? chats.find(c => c.projectId === route.id) : undefined
  const title = route.page === 'journal' ? 'Journal' : route.page === 'settings' ? 'Settings' : chat?.title ?? ''

  return (
    <div className="shell">
      <Sidebar chats={chats} activeId={route.page === 'chat' ? route.id : undefined} page={route.page}
        collapsed={collapsed} mobileOpen={navOpen} searchSignal={searchSignal} theme={theme} onTheme={setTheme}
        onToggle={toggleSide} onCloseMobile={() => setNavOpen(false)}
        onNew={() => { setNavOpen(false); go({ page: 'chat' }) }}
        onOpen={id => { setNavOpen(false); go({ page: 'chat', id }) }}
        onJournal={() => { setNavOpen(false); go({ page: 'journal' }) }}
        onSettings={() => { setNavOpen(false); go({ page: 'settings' }) }}
        onRename={async (id, t) => { await api.renameChat(id, t); await loadChats() }}
        onDelete={async id => { await api.deleteChat(id); if (route.page === 'chat' && route.id === id) go({ page: 'chat' }); await loadChats() }} />
      {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} />}

      <main className="main">
        <header className="topbar">
          <button className="ibtn phone-only" aria-label="Open sidebar" onClick={() => setNavOpen(true)}><Menu size={20} /></button>
          <div className="top-title">{title || <span className="top-brand">Strategos</span>}</div>
          <button className="ibtn phone-only" aria-label="New chat" onClick={() => go({ page: 'chat' })}><SquarePen size={19} /></button>
        </header>
        {error && <div className="banner" role="alert">{error} <button className="link" onClick={loadChats}>Retry</button></div>}
        <Guard key={JSON.stringify(route)} label="page">
        {route.page === 'chat' && (
          <Chat key={route.id ?? 'new'} projectId={route.id} threadId={chat?.threadId ?? undefined} openXRay={route.xray}
            missing={chatsLoaded && !!route.id && !chat}
            onStarted={id => { loadChats().then(() => go({ page: 'chat', id })) }} onTurn={loadChats} />
        )}
        {route.page === 'journal' && <Journal chats={chats} />}
        {route.page === 'settings' && <Settings />}
        </Guard>
      </main>
    </div>
  )
}

// What the pipeline is doing, shown while the turn runs (the app's onActivity lines). Timed: the server does not stream.
const STAGES = ['Reading what you said…', 'Running the game-theory engine…', 'Checking how solid the pick is…', 'Writing my reply…']

interface Pending { text: string; failed?: string }

function Chat({ projectId, threadId, openXRay, missing, onStarted, onTurn }: {
  projectId?: string; threadId?: string; missing?: boolean; openXRay?: 'arena' | 'board'; onStarted: (projectId: string) => void; onTurn: () => void
}) {
  const [turns, setTurns] = useState<TurnRecord[]>([])
  const [loading, setLoading] = useState(!!projectId)
  const [pending, setPending] = useState<Pending | null>(null)
  const [stage, setStage] = useState(0)
  const [fresh, setFresh] = useState<string | null>(null)
  const [xray, setXray] = useState(!!openXRay)
  const [tt, setTt] = useState<{ trust: number; tension: number } | null>(null)
  const scroller = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!threadId) { setLoading(!!projectId); return }
    api.turns(threadId).then(t => {
      setTurns(t); setLoading(false)
      const l = t[t.length - 1]; if (l) setTt({ trust: l.trustScore, tension: l.tensionScore })
      // A chat opened right after its first message plays that reply in, as if it had just arrived.
      if (t.length === 1 && Date.now() - t[0].timestamp < 15_000) setFresh(t[0].id)
    }).catch(() => setLoading(false))
  }, [threadId, projectId])

  useEffect(() => {
    if (!pending || pending.failed) return
    setStage(0)
    const ids = [2200, 5200, 8200].map((ms, i) => setTimeout(() => setStage(i + 1), ms))
    return () => ids.forEach(clearTimeout)
  }, [pending])

  useLayoutEffect(() => {
    const el = scroller.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: turns.length > 0 && !loading ? 'smooth' : 'auto' })
  }, [turns.length, pending, loading])

  const busy = !!pending && !pending.failed
  const send = async (text: string) => {
    const t = text.trim()
    if (!t || busy) return
    setPending({ text: t })
    try {
      if (!projectId) {
        const r = await api.startChat(t)
        onStarted(r.projectId)
        return
      }
      const r = await api.send(threadId!, t)
      const all = await api.turns(threadId!)
      setTurns(all); setFresh(all[all.length - 1]?.id ?? null); setPending(null)
      setTt({ trust: r.trustScore, tension: r.tensionScore })
      onTurn()
    } catch (e) {
      setPending({ text: t, failed: e instanceof ApiError ? e.message : 'Something went wrong. Try again in a moment.' })
    }
  }
  /** "Draft exact words": the words for the current move, from the saved situation (one wording call, no new reading). */
  const draft = async () => {
    if (!threadId || busy) return
    const t = 'Draft the exact words for this move.'
    setPending({ text: t })
    try {
      await api.draftWords(threadId)
      const all = await api.turns(threadId)
      setTurns(all); setFresh(all[all.length - 1]?.id ?? null); setPending(null)
      onTurn()
    } catch (e) {
      setPending({ text: t, failed: e instanceof ApiError ? e.message : 'Something went wrong. Try again in a moment.' })
    }
  }

  const empty = !projectId && !pending
  if (missing) return (
    <div className="chat"><div className="scroll"><div className="hello">
      <h1>This chat isn't here</h1>
      <p>It may have been deleted, or the link is from another device.</p>
      <button className="primary" onClick={() => go({ page: 'chat' })}>Start a new chat</button>
    </div></div></div>
  )
  return (
    <div className={`chat ${empty ? 'empty' : ''}`}>
      <div className="scroll" ref={scroller}>
        {empty ? (
          <div className="hello">
            <LogoMark size={52} className="hello-mark" />
            <h1>What's on your mind?</h1>
            <p>Tell me what's going on in your own words, or paste a whole chat. I'll model it, run it through the engine and tell you the move that holds up.</p>
            <Composer onSend={send} busy={busy} big />
            <div className="starters">
              {['My manager wants me to take on a second project without a raise.', 'My partner and I keep fighting about where to live.',
                'A client is pushing my price down 15% and I have a floor.'].map(s => <button key={s} onClick={() => send(s)}>{s}</button>)}
            </div>
          </div>
        ) : (
          <div className="thread">
            {tt && <div className="gauges-row"><span><i className="dot trust" />Trust {Math.round(tt.trust)}</span><span><i className="dot tension" />Tension {Math.round(tt.tension)}</span></div>}
            {loading && <div className="thinking"><div className="sk" /><div className="sk w2" /><div className="sk w3" /></div>}
            {turns.map((t, i) => {
              const cards = cardsOf(t.cardsJson)
              return (
                <div key={t.id} className="turn">
                  <div className="msg me"><div className="bubble">{t.userMessage}</div></div>
                  <div className="msg them">
                    <div className="avatar" aria-hidden><LogoMark size={28} /></div>
                    <Guard inline label="reply">
                      <Reply text={t.assistantReply} pill={pillOf(t.engineDirectiveJson)} cards={cards} animate={t.id === fresh}
                        answered={i < turns.length - 1} onXRay={() => setXray(true)} onSend={send} onDraft={draft} busy={busy} />
                    </Guard>
                  </div>
                </div>
              )
            })}
            {pending && (
              <div className="turn">
                <div className="msg me"><div className="bubble rise">{pending.text}</div></div>
                <div className="msg them">
                  <div className={`avatar ${busy ? 'busy' : ''}`} aria-hidden><LogoMark size={28} /></div>
                  {pending.failed ? (
                    <div className="failed">{pending.failed} <button className="link" onClick={() => send(pending.text)}>Try again</button></div>
                  ) : (
                    <div className="thinking" aria-live="polite">
                      <div className="sk" /><div className="sk w2" /><div className="sk w3" />
                      <div key={stage} className="stage">{STAGES[stage]}</div>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
      {!empty && <Composer onSend={send} busy={busy} big={false} />}
      {xray && projectId && <Guard label="x-ray"><XRay projectId={projectId} startOnBoard={openXRay === 'board'} onClose={() => setXray(false)} /></Guard>}
    </div>
  )
}

function Composer({ onSend, busy, big }: { onSend: (t: string) => void; busy: boolean; big: boolean }) {
  const [text, setText] = useState('')
  const area = useRef<HTMLTextAreaElement>(null)
  useLayoutEffect(() => {
    const el = area.current; if (!el) return
    el.style.height = 'auto'; el.style.height = `${Math.min(el.scrollHeight, 220)}px`
  }, [text])
  const submit = (e?: FormEvent) => { e?.preventDefault(); if (!text.trim() || busy) return; onSend(text); setText('') }
  return (
    <form className={`composer ${big ? 'big' : ''}`} onSubmit={submit}>
      <div className="composer-box">
        <textarea ref={area} rows={1} value={text} placeholder={big ? 'Describe the situation…' : 'Reply, add what happened, or ask "what if…"'}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && window.matchMedia('(pointer: fine)').matches) { e.preventDefault(); submit() } }} />
        <button type="submit" className="send" aria-label="Send" disabled={!text.trim() || busy}><ArrowUp size={18} strokeWidth={2.5} /></button>
      </div>
      <div className="composer-note">The move comes from Strategos's own engine; the AI only reads and words it.</div>
    </form>
  )
}

function Journal({ chats }: { chats: ChatSummary[] }) {
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
    setTimeout(() => { setToast(null); load() }, 6000)
  }
  return (
    <div className="page">
      <p className="muted">What you feel and how you'd like to grow. Private, and never judged. If it touches one of your chats, a small Do / Don't appears there.</p>
      <form className="stack" onSubmit={submit}>
        <textarea rows={5} placeholder="Write freely…" value={text} onChange={e => setText(e.target.value)} />
        <button className="primary" type="submit" disabled={!text.trim()}>Save privately</button>
      </form>
      {toast && <div className="toast rise">{toast} 🍃</div>}
      <div className="entries">
        {entries.map(e => (
          <div key={e.id} className="entry">
            <small>{new Date(e.createdAt).toLocaleString()}{e.isSignificantShift && ` · added a note to ${e.affectedProjectIds.map(id => chats.find(c => c.projectId === id)?.title ?? 'a chat').join(', ')}`}</small>
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
    } catch (e) { setSaved(e instanceof Error ? e.message : 'Could not reach the server') }
  }, [])
  useEffect(() => { load() }, [load])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const l = await api.saveLlm({ provider: form.provider, model: form.model || undefined, baseUrl: form.baseUrl || undefined, apiKey: form.apiKey || undefined })
      setLlm(l); setForm(f => ({ ...f, apiKey: '' })); setSaved("Saved. The key is kept in the server's memory only."); load()
    } catch (e) { setSaved(e instanceof Error ? e.message : 'Could not save') }
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
