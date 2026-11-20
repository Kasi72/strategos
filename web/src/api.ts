// Thin client for the Strategos server (/api). Same origin in production; Vite proxies it in dev.

export type DomainType = 'PERSONAL_RELATIONSHIP' | 'COMMERCIAL_NEGOTIATION' | 'COALITION_ORG' | 'GENERAL'

export interface ProjectSummary {
  id: string; title: string; domainType: DomainType; trust: number; tension: number
  createdAt: number; updatedAt: number; archived: boolean
}
export interface TopicThread {
  id: string; projectId: string; title: string; rollingSummary: string; turnCount: number; lastActiveAt: number
}
export interface ScoredOption { label: string; posture?: string | null; net: number }
export interface Priors { probSoftensOnRepair: number; probRespectsBoundary: number; probEscalatesOnReactive: number }
export interface PostNote { id: string; projectId: string; habitOrTrigger: string; doThis: string; dontDoThis: string; createdAt: number }
export interface ProjectState {
  project: ProjectSummary; knownFacts: string[]; priors?: Priors | null; options: ScoredOption[]
  postNotes: PostNote[]; threads: TopicThread[]
}
export interface DirectiveSummary {
  ready: boolean; recommendedMove?: string | null; moveToImplement?: string | null; safeFallbackMove?: string | null
  blockedOrDominatedMoves: string[]; stressVerdict?: string | null; pattern?: string | null; confidence?: string | null
}
export interface TurnResponse {
  turnIndex: number; reply: string; intent: string; directive?: DirectiveSummary | null
  directiveJson?: Record<string, unknown> | null; trustScore: number; tensionScore: number
  memoryBackend: string; coachUsed: boolean; savedChanges: number
}
export interface TurnRecord {
  id: string; turnIndex: number; userMessage: string; assistantReply: string
  engineDirectiveJson?: string | null; trustScore: number; tensionScore: number; timestamp: number
}
export interface MemoryFact { id: string; subject: string; predicate: string; statement: string; validFrom: number; invalidAt?: number | null }
export interface ThreadContext {
  thread: TopicThread
  memory: { currentFacts: MemoryFact[]; superseded: MemoryFact[]; backend: string }
}
export interface JournalEntry { id: string; content: string; affectedProjectIds: string[]; isSignificantShift: boolean; createdAt: number }
export interface Health { ok: boolean; database: boolean; memory: string; memoryHealthy: boolean; llmProvider: string; llmModel: string }
export interface LlmSettings { provider: string; model: string; baseUrl?: string | null; hasKey: boolean }

const TOKEN_KEY = 'strategos.token'
export const token = {
  get(): string { try { return localStorage.getItem(TOKEN_KEY) ?? '' } catch { return '' } },
  set(v: string) { try { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY) } catch { /* private mode */ } },
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly retryable = false) { super(message) }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const t = token.get()
  if (t) headers.Authorization = `Bearer ${t}`
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const data = text ? JSON.parse(text) : undefined
  if (!res.ok) throw new ApiError(data?.error ?? `Request failed (${res.status})`, res.status, data?.retryable ?? false)
  return data as T
}

export const api = {
  health: () => call<Health>('GET', '/api/health'),
  projects: () => call<ProjectSummary[]>('GET', '/api/projects'),
  createProject: (title: string, domainType: DomainType) => call<ProjectSummary>('POST', '/api/projects', { title, domainType }),
  project: (id: string) => call<ProjectState>('GET', `/api/projects/${id}`),
  archiveProject: (id: string) => call<ProjectSummary>('PATCH', `/api/projects/${id}`, { archived: true }),
  createThread: (projectId: string, title: string) => call<TopicThread>('POST', `/api/projects/${projectId}/threads`, { title }),
  turns: (threadId: string) => call<TurnRecord[]>('GET', `/api/threads/${threadId}/messages`),
  send: (threadId: string, message: string) => call<TurnResponse>('POST', `/api/threads/${threadId}/messages`, { message }),
  context: (threadId: string) => call<ThreadContext>('GET', `/api/threads/${threadId}/context`),
  quickCheck: (threadId: string, triedMove: string, counterpartReaction: string, userState?: string) =>
    call<{ priorsBefore?: Priors; priorsAfter?: Priors }>('POST', `/api/threads/${threadId}/quick-check`, { triedMove, counterpartReaction, userState }),
  journal: () => call<JournalEntry[]>('GET', '/api/journal'),
  saveJournal: (content: string, projectId?: string) => call<{ id: string; message: string }>('POST', '/api/journal', { content, projectId }),
  llm: () => call<LlmSettings>('GET', '/api/settings/llm'),
  saveLlm: (s: { provider: string; model?: string; baseUrl?: string; apiKey?: string }) => call<LlmSettings>('PUT', '/api/settings/llm', s),
}
