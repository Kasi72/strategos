import { useEffect, useMemo, useRef, useState } from 'react'
import {
  BookOpen, Check, ChevronsUpDown, Monitor, Moon, MoreHorizontal, PanelLeftClose, PanelLeftOpen, Pencil, Search,
  Settings as SettingsIcon, SquarePen, Sun, Trash2, X,
} from 'lucide-react'
import type { ChatSummary } from './api'
import { LogoMark } from './Logo'

// The left rail, laid out like ChatGPT's (and its open-source clones: LibreChat, Vercel's ai-chatbot): logo and a
// collapse toggle on top, then New chat / Search / Journal, the chats grouped by day with a hover "…" menu, and the
// account row at the bottom with Settings and the theme. On desktop it collapses to an icon rail; on a phone it is a
// drawer.

export type Theme = 'system' | 'light' | 'dark'

interface Props {
  chats: ChatSummary[]
  activeId?: string
  page: 'chat' | 'journal' | 'settings'
  collapsed: boolean
  mobileOpen: boolean
  searchSignal: number
  theme: Theme
  onTheme: (t: Theme) => void
  onToggle: () => void
  onCloseMobile: () => void
  onNew: () => void
  onOpen: (id: string) => void
  onJournal: () => void
  onSettings: () => void
  onRename: (id: string, title: string) => Promise<void>
  onDelete: (id: string) => Promise<void>
}

const mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
export const KEYS = { newChat: mac ? '⇧⌘O' : 'Ctrl+Shift+O', search: mac ? '⌘K' : 'Ctrl+K', side: mac ? '⇧⌘S' : 'Ctrl+Shift+S' }

export default function Sidebar(p: Props) {
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const search = useRef<HTMLInputElement>(null)

  // ⌘K (or the Search row) opens the filter; focus follows once the box is actually on screen.
  const [focusTick, setFocusTick] = useState(0)
  const openSearch = () => { setSearching(true); setFocusTick(n => n + 1) }
  useEffect(() => { if (p.searchSignal > 0) openSearch() }, [p.searchSignal])
  useEffect(() => { if (searching) search.current?.focus() }, [searching, focusTick, p.mobileOpen, p.collapsed])

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? p.chats.filter(c => c.title.toLowerCase().includes(q)) : p.chats
  }, [p.chats, query])

  const rail = p.collapsed && !p.mobileOpen
  const closeSearch = () => { setSearching(false); setQuery('') }

  return (
    <aside className={`side ${rail ? 'rail' : ''} ${p.mobileOpen ? 'open' : ''}`} aria-label="Sidebar">
      <div className="side-head">
        {rail ? (
          <button className="side-logo-toggle" onClick={p.onToggle} title={`Open sidebar (${KEYS.side})`} aria-label="Open sidebar">
            <LogoMark size={28} className="side-logo" />
            <PanelLeftOpen size={20} className="side-logo-alt" />
          </button>
        ) : (
          <>
            <button className="side-brand" onClick={p.onNew} aria-label="Strategos, new chat">
              <LogoMark size={28} /><span>Strategos</span>
            </button>
            <button className="ibtn desk-only" onClick={p.onToggle} title={`Close sidebar (${KEYS.side})`} aria-label="Close sidebar"><PanelLeftClose size={20} /></button>
            <button className="ibtn phone-only" onClick={p.onCloseMobile} aria-label="Close sidebar"><X size={20} /></button>
          </>
        )}
      </div>

      <div className="side-actions">
        <NavRow icon={<SquarePen size={18} />} label="New chat" kbd={KEYS.newChat} rail={rail} active={p.page === 'chat' && !p.activeId} onClick={p.onNew} />
        <NavRow icon={<Search size={18} />} label="Search chats" kbd={KEYS.search} rail={rail}
          onClick={() => { if (rail) p.onToggle(); openSearch() }} />
        <NavRow icon={<BookOpen size={18} />} label="Journal" rail={rail} active={p.page === 'journal'} onClick={p.onJournal} />
      </div>

      {!rail && (
        <nav className="chat-list" aria-label="Chats">
          {searching && (
            <div className="side-search">
              <Search size={15} />
              <input ref={search} value={query} placeholder="Search chats" aria-label="Search chats"
                onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Escape') closeSearch() }} />
              <button className="ibtn sm" onClick={closeSearch} aria-label="Close search"><X size={14} /></button>
            </div>
          )}
          {groups(shown).map(([label, list]) => (
            <section key={label} className="chat-group">
              <h2 className="chat-group-h">{label}</h2>
              {list.map(c => (
                <ChatItem key={c.projectId} c={c} active={p.activeId === c.projectId}
                  onOpen={() => p.onOpen(c.projectId)} onRename={t => p.onRename(c.projectId, t)} onDelete={() => p.onDelete(c.projectId)} />
              ))}
            </section>
          ))}
          {p.chats.length === 0 && <p className="side-empty">Your chats will appear here.</p>}
          {p.chats.length > 0 && shown.length === 0 && <p className="side-empty">No chats match "{query}".</p>}
        </nav>
      )}
      {rail && <div className="rail-fill" />}

      <Account rail={rail} page={p.page} theme={p.theme} onTheme={p.onTheme} onSettings={p.onSettings} />
    </aside>
  )
}

function NavRow({ icon, label, kbd, rail, active, onClick }: {
  icon: React.ReactNode; label: string; kbd?: string; rail: boolean; active?: boolean; onClick: () => void
}) {
  return (
    <button className={`nav-row ${active ? 'on' : ''}`} onClick={onClick} title={rail ? (kbd ? `${label} (${kbd})` : label) : undefined} aria-label={label}>
      <span className="nav-ico">{icon}</span>
      {!rail && <span className="nav-label">{label}</span>}
      {!rail && kbd && <kbd className="nav-kbd">{kbd}</kbd>}
    </button>
  )
}

export function groups(chats: ChatSummary[]): [string, ChatSummary[]][] {
  const day = 86_400_000, now = Date.now()
  const start = new Date(); start.setHours(0, 0, 0, 0)
  const out: Record<string, ChatSummary[]> = {}
  for (const c of chats) {
    const label = c.updatedAt >= start.getTime() ? 'Today' : c.updatedAt >= start.getTime() - day ? 'Yesterday'
      : now - c.updatedAt < 7 * day ? 'Previous 7 days' : now - c.updatedAt < 30 * day ? 'Previous 30 days' : 'Older'
    ;(out[label] ??= []).push(c)
  }
  return ['Today', 'Yesterday', 'Previous 7 days', 'Previous 30 days', 'Older'].filter(l => out[l]).map(l => [l, out[l]])
}

// Closes a popover on a click outside it or on Escape.
function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const down = (e: PointerEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) close() }
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('pointerdown', down); document.addEventListener('keydown', key)
    return () => { document.removeEventListener('pointerdown', down); document.removeEventListener('keydown', key) }
  }, [open, close])
  return ref
}

function ChatItem({ c, active, onOpen, onRename, onDelete }: {
  c: ChatSummary; active: boolean; onOpen: () => void; onRename: (t: string) => Promise<void>; onDelete: () => Promise<void>
}) {
  const [menu, setMenu] = useState(false)
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(c.title)
  const [confirm, setConfirm] = useState(false)
  const [up, setUp] = useState(false)
  const close = useRef(() => { setMenu(false); setConfirm(false) }).current
  const ref = useDismiss(menu, close)

  if (editing) return (
    <form className="chat-item editing" onSubmit={e => { e.preventDefault(); setEditing(false); if (title.trim() && title.trim() !== c.title) onRename(title.trim()) }}>
      <input autoFocus value={title} aria-label="Chat name" onChange={e => setTitle(e.target.value)}
        onFocus={e => e.currentTarget.select()}
        onBlur={() => { setEditing(false); if (title.trim() && title.trim() !== c.title) onRename(title.trim()) }}
        onKeyDown={e => { if (e.key === 'Escape') { setTitle(c.title); setEditing(false) } }} />
    </form>
  )
  return (
    <div ref={ref} className={`chat-item ${active ? 'on' : ''} ${menu ? 'menu-open' : ''}`}>
      <a className="chat-open" href={`#/c/${c.projectId}`} onClick={e => { e.preventDefault(); onOpen() }} title={c.title}
        aria-current={active ? 'page' : undefined} onDoubleClick={() => { setTitle(c.title); setEditing(true) }}>
        {c.title}
      </a>
      <button className="chat-more" aria-label={`Options for ${c.title}`} aria-haspopup="menu" aria-expanded={menu}
        onClick={e => { setUp(window.innerHeight - e.currentTarget.getBoundingClientRect().bottom < 140); setMenu(m => !m); setConfirm(false) }}><MoreHorizontal size={16} /></button>
      {menu && (
        <div className={`popover chat-menu ${up ? 'up' : ''}`} role="menu">
          <button role="menuitem" onClick={() => { setMenu(false); setTitle(c.title); setEditing(true) }}><Pencil size={15} />Rename</button>
          {confirm
            ? <button role="menuitem" className="danger strong" onClick={() => { setMenu(false); onDelete() }}><Trash2 size={15} />Delete for good?</button>
            : <button role="menuitem" className="danger" onClick={() => setConfirm(true)}><Trash2 size={15} />Delete</button>}
        </div>
      )}
    </div>
  )
}

function Account({ rail, page, theme, onTheme, onSettings }: {
  rail: boolean; page: string; theme: Theme; onTheme: (t: Theme) => void; onSettings: () => void
}) {
  const [open, setOpen] = useState(false)
  const close = useRef(() => setOpen(false)).current
  const ref = useDismiss(open, close)
  const themes: [Theme, React.ReactNode, string][] = [['system', <Monitor size={15} />, 'System'], ['light', <Sun size={15} />, 'Light'], ['dark', <Moon size={15} />, 'Dark']]
  return (
    <div ref={ref} className="account">
      {open && (
        <div className="popover account-menu" role="menu">
          <button role="menuitem" className={page === 'settings' ? 'on' : ''} onClick={() => { setOpen(false); onSettings() }}><SettingsIcon size={15} />Settings</button>
          <div className="menu-sep" />
          <div className="menu-h">Theme</div>
          {themes.map(([t, icon, label]) => (
            <button key={t} role="menuitemradio" aria-checked={theme === t} onClick={() => onTheme(t)}>
              {icon}{label}{theme === t && <Check size={15} className="menu-check" />}
            </button>
          ))}
          <div className="menu-sep" />
          <div className="menu-foot">Shortcuts: new chat {KEYS.newChat} · search {KEYS.search} · sidebar {KEYS.side}</div>
        </div>
      )}
      <button className={`account-btn ${open ? 'on' : ''}`} onClick={() => setOpen(o => !o)} aria-haspopup="menu" aria-expanded={open}
        title={rail ? 'Settings and theme' : undefined} aria-label="Settings and theme">
        <span className="account-av">S</span>
        {!rail && <span className="account-text"><b>Your workspace</b><small>Local · private</small></span>}
        {!rail && <ChevronsUpDown size={16} className="account-chev" />}
      </button>
    </div>
  )
}
