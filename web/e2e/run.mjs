// End-to-end tests in a real Chrome against a real server and the live LLM.
//
//   npm run build && npm run e2e                 (from web/; needs the server built: gradlew :server:installDist)
//   E2E_ONLY=xray npm run e2e                    (run the tests whose name contains "xray")
//   E2E_URL=http://localhost:8080 npm run e2e    (reuse a running server instead of starting one; writes real chats!)
//
// By default it starts its own server on a spare port with a throwaway SQLite file and in-memory memory (no Graphiti),
// so your chats and graph are never touched. The DeepSeek key comes from ../.env and is never printed. Chrome is driven
// over the DevTools protocol (no extra packages). Any uncaught page error fails the test it happened in. Screenshots of
// failures land in e2e/out/.

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../..')
const out = join(here, 'out'); rmSync(out, { recursive: true, force: true }); mkdirSync(out, { recursive: true })
const CHROME = process.env.CHROME ?? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium'].find(existsSync)
const PORT = 8091, CDP = 9339
const sleep = ms => new Promise(r => setTimeout(r, ms))
const LLM_WAIT = 150_000

// ── server ──
let server, base = process.env.E2E_URL
const work = join(tmpdir(), `strategos-e2e-${process.pid}`)
async function startServer() {
  if (base) return
  const bin = join(root, 'server/build/install/server/bin/server')
  if (!existsSync(bin)) throw new Error('Build the server first: ./gradlew -Pstrategos.serverOnly=true :server:installDist')
  const env = { ...process.env }
  const dotenv = join(root, '.env')
  if (existsSync(dotenv)) for (const line of readFileSync(dotenv, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)\s*$/); if (m && m[2] && !(m[1] in process.env)) env[m[1]] = m[2].replace(/^["']|["']$/g, '')
  }
  if (!env.DEEPSEEK_API_KEY && !env.LLM_API_KEY) throw new Error('No DEEPSEEK_API_KEY in ../.env or the environment: these tests use the live model.')
  mkdirSync(work, { recursive: true })
  delete env.GRAPHITI_URL; delete env.STRATEGOS_AUTH_TOKEN
  Object.assign(env, { PORT: String(PORT), HOST: '127.0.0.1', DATABASE_URL: `jdbc:sqlite:${join(work, 'e2e.db')}`, WEB_DIR: join(root, 'web/dist') })
  server = spawn(bin, [], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  let log = ''; server.stdout.on('data', d => log += d); server.stderr.on('data', d => log += d)
  base = `http://127.0.0.1:${PORT}`
  for (let i = 0; i < 120; i++) { try { if ((await fetch(`${base}/api/health`)).ok) return } catch {} await sleep(250) }
  throw new Error('Server did not start:\n' + log.slice(-2000))
}

// ── Chrome over CDP ──
let chrome, ws, msgId = 0
const pending = new Map(), listeners = new Set()
async function startChrome() {
  chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${CDP}`, '--hide-scrollbars', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${join(work, 'chrome')}`, 'about:blank'], { stdio: 'ignore' })
  for (let i = 0; i < 80; i++) {
    try { const t = await (await fetch(`http://127.0.0.1:${CDP}/json`)).json(); const p = t.find(x => x.type === 'page'); if (p) { ws = new WebSocket(p.webSocketDebuggerUrl); break } } catch {}
    await sleep(150)
  }
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j })
  ws.onmessage = m => {
    const d = JSON.parse(m.data)
    if (d.id && pending.has(d.id)) { const { r, j } = pending.get(d.id); pending.delete(d.id); d.error ? j(new Error(d.error.message)) : r(d.result) }
    else listeners.forEach(l => l(d))
  }
  await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Network.enable')
}
const cdp = (method, params = {}) => new Promise((r, j) => { const id = ++msgId; pending.set(id, { r, j }); ws.send(JSON.stringify({ id, method, params })) })

// Page errors collected per test.
let pageErrors = []
listeners.add(d => {
  if (d.method === 'Runtime.exceptionThrown') pageErrors.push(d.params.exceptionDetails.exception?.description?.split('\n')[0] ?? d.params.exceptionDetails.text)
  if (d.method === 'Runtime.consoleAPICalled' && d.params.type === 'error') pageErrors.push('console.error: ' + d.params.args.map(a => a.value ?? a.description ?? '').join(' ').split('\n')[0])
})

// Request interception: rules are [{ match: RegExp, method?, handle: async ({ url, method, status, body }) => ... }].
// handle returns { status, body } to replace the response, 'fail' to drop the connection, or null to let it through.
let rules = []
listeners.add(async d => {
  if (d.method !== 'Fetch.requestPaused') return
  const { requestId, request, responseStatusCode } = d.params
  const rule = rules.find(r => r.match.test(request.url) && (!r.method || r.method === request.method))
  try {
    if (!rule) return await cdp('Fetch.continueRequest', { requestId })
    let body = null
    if (responseStatusCode) { const b = await cdp('Fetch.getResponseBody', { requestId }); body = b.base64Encoded ? Buffer.from(b.body, 'base64').toString() : b.body }
    const res = await rule.handle({ url: request.url, method: request.method, status: responseStatusCode, body })
    if (res === 'fail') return await cdp('Fetch.failRequest', { requestId, errorReason: 'InternetDisconnected' })
    if (!res) return responseStatusCode ? await cdp('Fetch.continueResponse', { requestId }) : await cdp('Fetch.continueRequest', { requestId })
    await cdp('Fetch.fulfillRequest', { requestId, responseCode: res.status ?? 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(res.body).toString('base64') })
  } catch { /* the page navigated away mid-request */ }
})
async function intercept(newRules, stage = 'Response') {
  rules = newRules
  if (!rules.length) return cdp('Fetch.disable')
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', requestStage: stage }] })
}

// ── page helpers ──
async function ev(expr) {
  const r = await cdp('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(`eval failed: ${expr.slice(0, 80)}: ${r.exceptionDetails.exception?.description?.split('\n')[0]}`)
  return r.result.value
}
const q = s => JSON.stringify(s)
async function viewport(w, h, mobile = false) {
  await cdp('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile })
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: mobile })
}
async function open(hash = '#/') {
  // A hash-only change would not reload the page; always load it fresh, as a person opening the link would.
  await cdp('Page.navigate', { url: 'about:blank' })
  await waitFor(`location.href === 'about:blank'`, 5_000, 'blank page')
  await cdp('Page.navigate', { url: `${base}/${hash}` })
  await waitFor(`document.readyState === 'complete' && !!document.querySelector('.shell')`, 15_000, 'app shell')
  await sleep(300)
}
async function waitFor(expr, ms = 10_000, what = expr) {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) { try { if (await ev(expr)) return Date.now() - t0 } catch {} await sleep(250) }
  throw new Error(`timed out after ${ms} ms waiting for ${what}`)
}
const click = sel => ev(`(() => { const e = document.querySelector(${q(sel)}); if (!e) throw new Error('no element ' + ${q(sel)}); e.click(); return true })()`)
const clickText = (sel, text) => ev(`(() => { const e = [...document.querySelectorAll(${q(sel)})].find(x => x.textContent.trim().includes(${q(text)})); if (!e) throw new Error('no ' + ${q(sel)} + ' with text ' + ${q(text)}); e.click(); return true })()`)
const count = sel => ev(`document.querySelectorAll(${q(sel)}).length`)
const text = sel => ev(`document.querySelector(${q(sel)})?.innerText ?? null`)
const exists = sel => ev(`!!document.querySelector(${q(sel)})`)
async function type(sel, value) { await ev(`document.querySelector(${q(sel)}).focus()`); await cdp('Input.insertText', { text: value }) }
async function key(k, mods = {}) {
  const modifiers = (mods.alt ? 1 : 0) | (mods.ctrl ? 2 : 0) | (mods.meta ? 4 : 0) | (mods.shift ? 8 : 0)
  const codes = { Enter: 13, Escape: 27, ArrowRight: 39, ArrowLeft: 37 }
  const vk = codes[k] ?? k.toUpperCase().charCodeAt(0)
  const textOf = k === 'Enter' && !mods.meta ? '\r' : undefined
  await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code: k.length === 1 ? `Key${k.toUpperCase()}` : k, windowsVirtualKeyCode: vk, modifiers, text: textOf })
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code: k.length === 1 ? `Key${k.toUpperCase()}` : k, windowsVirtualKeyCode: vk, modifiers })
}
async function shot(name) { const r = await cdp('Page.captureScreenshot', { format: 'png' }); writeFileSync(join(out, `${name}.png`), Buffer.from(r.data, 'base64')) }
const api = async (path, init) => { const r = await fetch(base + path, init); return r.status === 204 ? null : r.json() }
const hashId = async () => (await ev('location.hash')).match(/#\/c\/([^/]+)/)?.[1]
function assert(cond, msg) { if (!cond) throw new Error(msg) }

/** Sends from the composer and waits for one more reply (or a failure box). */
async function sendAndWait(message, how = 'button') {
  const before = await count('.reply')
  await type('.composer textarea', message)
  if (how === 'enter') await key('Enter'); else await click('.composer .send')
  await waitFor(`document.querySelectorAll('.reply').length > ${before} || !!document.querySelector('.failed')`, LLM_WAIT, 'a reply')
  const failed = await text('.failed')
  assert(!failed, `the turn failed: ${failed}`)
}

// ── the tests ──
const tests = []
const test = (name, fn) => tests.push({ name, fn })
const state = {}

test('home: logo, greeting, starters and an enabled-on-typing send button', async () => {
  await viewport(1280, 820); await open('#/')
  assert(await exists('.hello-mark'), 'logo above the greeting')
  assert((await text('.hello h1')) === "What's on your mind?", 'greeting')
  assert((await count('.starters button')) === 3, 'three starters')
  assert(await ev(`document.querySelector('.composer .send').disabled`), 'send disabled when empty')
  await type('.composer textarea', '   ')
  assert(await ev(`document.querySelector('.composer .send').disabled`), 'send disabled for whitespace')
  await ev(`(() => { const t = document.querySelector('.composer textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(t, ''); t.dispatchEvent(new Event('input', { bubbles: true })) })()`)
  assert((await ev(`document.querySelector('link[rel=icon]')?.getAttribute('href')`)) === '/favicon.svg', 'favicon linked')
})

test('new chat from the composer: first reply, pill, sidebar entry, title', async () => {
  await open('#/')
  const msg = 'My flatmate keeps eating my food from the fridge and denies it. I want it to stop without a big fight.'
  await type('.composer textarea', msg)
  await click('.composer .send')
  await waitFor(`!!document.querySelector('.thinking .stage')`, 5_000, 'the thinking placeholder')
  await waitFor(`location.hash.startsWith('#/c/') && document.querySelectorAll('.reply').length === 1`, LLM_WAIT, 'the first reply')
  state.chat = await hashId()
  assert(state.chat, 'URL has the chat id')
  assert(await exists('.engine-pill'), 'engine pill under the reply')
  assert((await text('.msg.me .bubble')) === msg, 'my message shown as sent')
  await waitFor(`!!document.querySelector('.chat-item.on')`, 5_000, 'the chat highlighted in the sidebar')
  const title = await text('.chat-item.on .chat-open')
  assert(title && title.startsWith('My flatmate keeps eating'), `sidebar title: ${title}`)
  assert((await text('.chat-group-h')) === 'Today', 'grouped under Today')
  assert((await text('.top-title'))?.startsWith('My flatmate'), 'top bar shows the title')
})

test('follow-up with the send button, then with Enter; Shift+Enter is a new line', async () => {
  await sendAndWait('He just said "it was only one yogurt, relax". What now?', 'button')
  assert((await count('.reply')) === 2, 'two replies')
  await type('.composer textarea', 'line one')
  await key('Enter', { shift: true })
  assert((await count('.msg.me')) === 2, 'Shift+Enter did not send')
  await ev(`(() => { const t = document.querySelector('.composer textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(t, ''); t.dispatchEvent(new Event('input', { bubbles: true })) })()`)
  await sendAndWait('I tried labelling my shelf and he laughed it off.', 'enter')
  assert((await count('.reply')) === 3, 'three replies after Enter')
  const turns = await api(`/api/threads/${(await api('/api/chats')).find(c => c.projectId === state.chat).threadId}/messages`)
  assert(turns.length === 3, `server has 3 turns, has ${turns.length}`)
})

test('reload keeps the whole thread; Back returns from Journal', async () => {
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.reply').length === 3`, 10_000, 'three replies after reload')
  await click('.nav-row[aria-label="Journal"]')
  await waitFor(`location.hash === '#/journal'`)
  await ev('history.back()')
  await waitFor(`location.hash === '#/c/${state.chat}' && document.querySelectorAll('.reply').length === 3`, 10_000, 'back in the chat')
})

test('regression: a stored card without "options" does not blank the page, and its chip sends', async () => {
  await intercept([{ match: /\/api\/threads\/[^/]+\/messages$/, method: 'GET', handle: ({ body }) => {
    const t = JSON.parse(body); t[t.length - 1].cardsJson = JSON.stringify({ quickAnswers: [{ label: 'Usually digs in', text: 'He usually digs in when I push on something like this.' }] })
    return { body: JSON.stringify(t) }
  } }])
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.reply').length === 3`, 10_000, 'the thread')
  assert(await exists('.chip-btn'), 'the quick answer chip shows')
  assert(!(await exists('.guard')), 'no error fallback')
  await clickText('.chip-btn', 'Usually digs in')
  await waitFor(`document.querySelectorAll('.msg.me').length === 4`, 5_000, 'the chip sent a message')
  await intercept([])
  await waitFor(`document.querySelectorAll('.reply').length === 4 || !!document.querySelector('.failed')`, LLM_WAIT, 'the reply to the chip')
  assert(!(await exists('.failed')), 'chip turn failed: ' + await text('.failed'))
  assert((await text('.msg.me:last-of-type .bubble, .turn:last-child .msg.me .bubble'))?.includes('digs in'), 'the chip text was sent')
})

test('corrupt stored data: bad JSON, null directive, empty reply, still usable', async () => {
  await intercept([{ match: /\/api\/threads\/[^/]+\/messages$/, method: 'GET', handle: ({ body }) => {
    const t = JSON.parse(body)
    t[0].cardsJson = '{oops'; t[0].engineDirectiveJson = 'not json'; t[1].assistantReply = ''; t[1].engineDirectiveJson = null
    t[2].cardsJson = JSON.stringify({ quickAnswers: 'x', options: [null, { role: 'Best' }] })
    return { body: JSON.stringify(t) }
  } }])
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.msg.me').length >= 4`, 10_000, 'the thread')
  assert(!(await exists('.guard')), 'no error fallback for corrupt cards')
  assert(await exists('.composer textarea'), 'composer still there')
  await intercept([])
})

test('server busy (503): a clear error with Try again, which then works', async () => {
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.reply').length >= 4`, 10_000, 'the thread')
  await intercept([{ match: /\/api\/threads\/[^/]+\/messages$/, method: 'POST', handle: () => ({ status: 503, body: JSON.stringify({ error: 'The AI is busy right now. Try again in a moment.', retryable: true }) }) }], 'Request')
  const before = await count('.reply')
  await type('.composer textarea', 'Should I just talk to the landlord instead?')
  await click('.composer .send')
  await waitFor(`!!document.querySelector('.failed')`, 10_000, 'the error box')
  assert((await text('.failed')).includes('busy'), 'shows the server message')
  assert(await exists('.failed .link'), 'Try again link')
  await intercept([])
  await click('.failed .link')
  await waitFor(`document.querySelectorAll('.reply').length > ${before} && !document.querySelector('.failed')`, LLM_WAIT, 'the retried reply')
})

test('network down while sending: an error, not a crash or endless spinner', async () => {
  await intercept([{ match: /\/api\/threads\/[^/]+\/messages$/, method: 'POST', handle: () => 'fail' }], 'Request')
  await type('.composer textarea', 'What if he denies it again?')
  await click('.composer .send')
  await waitFor(`!!document.querySelector('.failed')`, 10_000, 'the error box')
  assert(!(await exists('.thinking .stage')), 'no spinner left behind')
  await intercept([])
  pageErrors = pageErrors.filter(e => !/Failed to fetch|ERR_INTERNET_DISCONNECTED|net::/.test(e))
})

test('double-clicking send makes one turn, not two', async () => {
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.reply').length >= 5`, 10_000, 'the thread')
  const threadId = (await api('/api/chats')).find(c => c.projectId === state.chat).threadId
  const before = (await api(`/api/threads/${threadId}/messages`)).length
  await type('.composer textarea', 'Ok, I will talk to him tonight.')
  await ev(`(() => { const b = document.querySelector('.composer .send'); b.click(); b.click(); document.querySelector('.composer form, form.composer')?.requestSubmit?.() })()`)
  await waitFor(`!document.querySelector('.thinking .stage')`, LLM_WAIT, 'the reply')
  await sleep(1500)
  const after = (await api(`/api/threads/${threadId}/messages`)).length
  assert(after === before + 1, `expected ${before + 1} turns, got ${after}`)
})

test('X-ray: opens from the reply, Arena and Board, slider by keyboard, keep, Escape', async () => {
  await open(`#/c/${state.chat}`)
  await waitFor(`!!document.querySelector('.xray-open')`, 10_000, 'X-ray button')
  await click('.xray-open')
  await waitFor(`!!document.querySelector('.xr .xr-title') || !!document.querySelector('.xr .xr-notenough, .xr [class*=enough]')`, 10_000, 'the X-ray sheet')
  assert(await exists('.xr-title'), 'X-ray has a title (the engine move): ' + (await text('.xr'))?.slice(0, 200))
  const pill = await text('.reply:last-of-type .engine-pill span, .turn:last-child .engine-pill span')
  const tabs = await ev(`[...document.querySelectorAll('.xr-seg button')].map(b => b.textContent)`)
  assert(tabs.includes('Arena') && tabs.includes('Board'), 'both tabs: ' + tabs)
  await clickText('.xr-seg button', 'Board')
  await waitFor(`!!document.querySelector('.xr-board canvas') || !!document.querySelector('.xr-table')`, 5_000, 'the board')
  assert((await count('.xr-move')) >= 2, 'move rows listed')
  await click('.xr-move'); await sleep(300)
  assert(await exists('.xr-move.open'), 'a move row expands')
  const pct0 = await ev(`document.querySelector('.xr-rail').getAttribute('aria-valuenow')`)
  await ev(`document.querySelector('.xr-rail').focus()`)
  for (let i = 0; i < 3; i++) await key('ArrowRight', { shift: true })
  const pct1 = await ev(`document.querySelector('.xr-rail').getAttribute('aria-valuenow')`)
  assert(+pct1 === Math.min(100, +pct0 + 30), `slider moved 30 points: ${pct0} → ${pct1}`)
  await waitFor(`document.querySelector('.xr-actions')?.classList.contains('show')`, 3_000, 'Reset / Keep shown after a change')
  await clickText('.xr-actions button', 'Keep')
  // Kept = the sheet reloads at the new odds and Reset / Keep fold away.
  await waitFor(`!document.querySelector('.xr-actions')?.classList.contains('show')`, 15_000, 'the keep to finish')
  const x = await api(`/api/projects/${state.chat}/xray`)
  assert(Math.abs(x.coachOdds - pct1 / 100) < 0.011, `kept odds saved: ${x.coachOdds} vs ${pct1}`)
  await key('Escape')
  await waitFor(`!document.querySelector('.xr-wrap')`, 3_000, 'X-ray closed')
  state.pill = pill
})

test('X-ray deep links: #/xray opens the Arena, #/board opens the Board', async () => {
  await open(`#/c/${state.chat}/xray`)
  await waitFor(`!!document.querySelector('.xr-title')`, 10_000, 'X-ray from the link')
  await open(`#/c/${state.chat}/board`)
  await waitFor(`!!document.querySelector('.xr-move')`, 10_000, 'Board from the link')
  await key('Escape')
})

test('X-ray with no moves yet says so instead of crashing', async () => {
  await intercept([{ match: /\/xray$/, method: 'GET', handle: ({ body }) => { const x = JSON.parse(body); return { body: JSON.stringify({ ...x, ready: true, rows: [], bestIndex: 0 }) } } }])
  await open(`#/c/${state.chat}/xray`)
  await sleep(1500)
  assert(!(await exists('.guard')), 'no error fallback')
  assert(await exists('.xr-wrap'), 'the sheet is open')
  await intercept([])
  await key('Escape')
})

test('sidebar search filters chats and says when nothing matches', async () => {
  await open('#/')
  await click('.nav-row[aria-label="Search chats"]')
  await waitFor(`document.activeElement?.getAttribute('aria-label') === 'Search chats'`, 5_000, 'search focused')
  await type('.side-search input', 'flatmate')
  assert((await count('.chat-item')) >= 1, 'matching chat shown')
  await type('.side-search input', 'zzqqxx')
  await waitFor(`/No chats match/.test(document.querySelector('.chat-list').innerText)`, 3_000, 'no-match note')
  await key('Escape')
  assert(!(await exists('.side-search')), 'Escape closes search')
})

test('rename from the ⋯ menu updates the sidebar and the top bar', async () => {
  await open(`#/c/${state.chat}`)
  await waitFor(`!!document.querySelector('.chat-item.on')`)
  await click('.chat-item.on .chat-more')
  await waitFor(`!!document.querySelector('.chat-menu')`, 3_000, 'menu')
  await clickText('.chat-menu button', 'Rename')
  await waitFor(`!!document.querySelector('.chat-item.editing input')`, 3_000, 'rename box')
  await ev(`document.querySelector('.chat-item.editing input').select()`)
  await cdp('Input.insertText', { text: 'Fridge standoff' })
  await key('Enter')
  await waitFor(`document.querySelector('.chat-item.on .chat-open')?.innerText === 'Fridge standoff'`, 5_000, 'renamed in sidebar')
  await waitFor(`document.querySelector('.top-title')?.innerText === 'Fridge standoff'`, 5_000, 'renamed in top bar')
  assert((await api('/api/chats')).some(c => c.title === 'Fridge standoff'), 'saved on the server')
})

test('menu closes on outside click and on Escape', async () => {
  await click('.chat-item.on .chat-more')
  await waitFor(`!!document.querySelector('.chat-menu')`)
  await ev(`document.querySelector('.main').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))`)
  await waitFor(`!document.querySelector('.chat-menu')`, 2_000, 'closed by outside click')
  await click('.chat-item.on .chat-more')
  await waitFor(`!!document.querySelector('.chat-menu')`)
  await key('Escape')
  await waitFor(`!document.querySelector('.chat-menu')`, 2_000, 'closed by Escape')
})

test('a link to a chat that does not exist explains itself', async () => {
  await open('#/c/00000000-0000-0000-0000-000000000000')
  await waitFor(`/isn't here/.test(document.querySelector('.hello h1')?.innerText ?? '')`, 5_000, 'the not-found note')
  assert(!(await exists('.thinking')), 'no endless placeholder')
})

test('collapse to the icon rail, survive a reload, expand with the shortcut', async () => {
  await viewport(1280, 820); await open('#/')
  await click('.side-head .desk-only')
  await waitFor(`document.querySelector('.side').classList.contains('rail')`, 2_000, 'rail')
  await waitFor(`document.querySelector('.side').getBoundingClientRect().width <= 64`, 2_000, 'rail is narrow after its slide')
  await open('#/')
  assert(await ev(`document.querySelector('.side').classList.contains('rail')`), 'rail remembered after reload')
  await key('s', { meta: true, shift: true })
  await waitFor(`!document.querySelector('.side').classList.contains('rail')`, 2_000, 'expanded by ⇧⌘S')
})

test('shortcuts: ⌘K opens search, ⇧⌘O starts a new chat', async () => {
  await open(`#/c/${state.chat}`)
  await key('k', { meta: true })
  await waitFor(`document.activeElement?.getAttribute('aria-label') === 'Search chats'`, 2_000, 'search focused by ⌘K')
  await key('Escape')
  await key('o', { meta: true, shift: true })
  await waitFor(`location.hash === '#/' && !!document.querySelector('.hello')`, 2_000, 'new chat by ⇧⌘O')
})

test('theme: Dark and Light stick across reloads, System follows the device', async () => {
  await open('#/')
  await click('.account-btn')
  await clickText('.account-menu button', 'Dark')
  assert((await ev(`document.documentElement.dataset.theme`)) === 'dark', 'dark set')
  const bg = await ev(`getComputedStyle(document.body).backgroundColor`)
  assert(bg === 'rgb(15, 15, 18)', 'dark background: ' + bg)
  await open('#/')
  assert((await ev(`document.documentElement.dataset.theme`)) === 'dark', 'dark remembered')
  await click('.account-btn')
  await clickText('.account-menu button', 'Light')
  assert((await ev(`document.documentElement.dataset.theme`)) === 'light', 'light set')
  await clickText('.account-menu button', 'System')
  assert((await ev(`document.documentElement.dataset.theme ?? null`)) === null, 'system clears it')
  await key('Escape')
})

test('Settings shows status; Journal saves an entry', async () => {
  await click('.account-btn'); await clickText('.account-menu button', 'Settings')
  await waitFor(`location.hash === '#/settings' && document.querySelectorAll('.status li').length === 3`, 5_000, 'status list')
  await click('.nav-row[aria-label="Journal"]')
  await waitFor(`location.hash === '#/journal'`)
  const before = await count('.entry')
  await type('.page textarea', 'Felt calmer today after deciding to talk to my flatmate.')
  await clickText('.page button', 'Save privately')
  await waitFor(`!!document.querySelector('.toast')`, 15_000, 'saved toast')
  await waitFor(`document.querySelectorAll('.entry').length === ${before + 1}`, 15_000, 'entry listed')
})

test('a long pasted chat (≈6,000 chars) goes through and the bubble stays contained', async () => {
  await open('#/')
  const paste = Array.from({ length: 80 }, (_, i) => `[${10 + (i % 12)}:${String(i % 60).padStart(2, '0')}] ${i % 2 ? 'Ravi' : 'Me'}: ${i % 2 ? 'the client wants it by friday, no extra budget' : 'we agreed scope last month, this is new work'}`).join('\n')
    + '\nMy client keeps adding work without paying more. How do I push back without losing them?'
  await type('.composer textarea', paste)
  await click('.composer .send')
  await waitFor(`location.hash.startsWith('#/c/') && document.querySelectorAll('.reply').length === 1 || !!document.querySelector('.failed')`, LLM_WAIT, 'a reply')
  assert(!(await exists('.failed')), 'failed: ' + await text('.failed'))
  const h = await ev(`document.querySelector('.msg.me .bubble').getBoundingClientRect().height`)
  assert(h <= 380, `bubble is capped and scrolls (height ${h})`)
  state.second = await hashId()
})

test('delete with confirmation removes the chat and leaves an open chat', async () => {
  await open(`#/c/${state.second}`)
  await waitFor(`!!document.querySelector('.chat-item.on')`)
  const n = await count('.chat-item')
  await click('.chat-item.on .chat-more')
  await clickText('.chat-menu button', 'Delete')
  assert(await exists('.chat-menu .danger.strong'), 'asks to confirm')
  assert((await count('.chat-item')) === n, 'not deleted before confirming')
  await clickText('.chat-menu button', 'Delete for good?')
  await waitFor(`document.querySelectorAll('.chat-item').length === ${n - 1} && location.hash === '#/'`, 5_000, 'deleted and sent home')
  assert(!(await api('/api/chats')).some(c => c.projectId === state.second), 'gone on the server')
})

test('phone: drawer opens and closes, chat readable, no sideways scroll', async () => {
  await viewport(390, 844, true)
  await open(`#/c/${state.chat}`)
  await waitFor(`document.querySelectorAll('.reply').length >= 5`, 10_000, 'the thread')
  assert(await ev(`getComputedStyle(document.querySelector('.topbar [aria-label="Open sidebar"]')).display !== 'none'`), 'menu button shown')
  assert(await ev(`document.documentElement.scrollWidth <= innerWidth && document.querySelector('.scroll').scrollWidth <= document.querySelector('.scroll').clientWidth`), 'no sideways scroll')
  await click('.topbar [aria-label="Open sidebar"]')
  await waitFor(`document.querySelector('.side').classList.contains('open') && !!document.querySelector('.scrim')`, 2_000, 'drawer open')
  await click('.scrim')
  await waitFor(`!document.querySelector('.side').classList.contains('open')`, 2_000, 'drawer closed by the scrim')
  await click('.topbar [aria-label="Open sidebar"]')
  await click('.chat-item .chat-open')
  await waitFor(`!document.querySelector('.side').classList.contains('open')`, 2_000, 'drawer closes after picking a chat')
  await shot('phone-chat')
})

test('phone: send a turn with the button (Enter makes a new line on touch)', async () => {
  await type('.composer textarea', 'Quick one: should I text him or say it in person?')
  await key('Enter')
  assert((await ev(`document.querySelector('.composer textarea').value`)).includes('\n') || (await count('.thinking .stage')) === 0, 'Enter did not send on touch')
  const before = await count('.reply')
  await click('.composer .send')
  await waitFor(`document.querySelectorAll('.reply').length > ${before} || !!document.querySelector('.failed')`, LLM_WAIT, 'the reply')
  assert(!(await exists('.failed')), 'failed: ' + await text('.failed'))
})

test('phone: X-ray is a bottom sheet with the slider reachable', async () => {
  await click('.xray-open')
  await waitFor(`!!document.querySelector('.xr-title')`, 10_000, 'X-ray')
  await sleep(400)
  const r = await ev(`(() => { const b = document.querySelector('.xr').getBoundingClientRect(); const d = document.querySelector('.xr-dock').getBoundingClientRect(); return { top: b.top, w: b.width, dockBottom: d.bottom, h: innerHeight } })()`)
  assert(r.w === 390 && r.top > 0, 'full-width sheet from below: ' + JSON.stringify(r))
  assert(r.dockBottom <= r.h + 1, 'slider dock on screen')
  await shot('phone-xray')
  await key('Escape')
})

test('every button has a name, at desktop and phone width', async () => {
  for (const [w, h, m] of [[1280, 820, false], [390, 844, true]]) {
    await viewport(w, h, m); await open(`#/c/${state.chat}`)
    await waitFor(`document.querySelectorAll('.reply').length >= 1`, 10_000)
    const unnamed = await ev(`[...document.querySelectorAll('button')].filter(b => b.offsetParent && !(b.getAttribute('aria-label') || b.textContent.trim() || b.title)).map(b => b.className)`)
    assert(unnamed.length === 0, `buttons without a name at ${w}px: ${unnamed.join(', ')}`)
  }
  await viewport(1280, 820)
})

// ── runner ──
const only = process.env.E2E_ONLY?.toLowerCase()
let passed = 0, failed = 0
const t0 = Date.now()
try {
  await startServer(); await startChrome(); await viewport(1280, 820)
  console.log(`E2E against ${base}${process.env.E2E_URL ? '' : ' (throwaway server)'}\n`)
  for (const t of tests) {
    if (only && !only.split('|').some(o => t.name.toLowerCase().includes(o))) continue
    pageErrors = []
    const s = Date.now()
    try {
      await t.fn()
      const errs = pageErrors.filter(e => !/favicon|DevTools/.test(e))
      if (errs.length) throw new Error('page errors: ' + errs.slice(0, 3).join(' | '))
      passed++; console.log(`  ✓ ${t.name}  (${((Date.now() - s) / 1000).toFixed(1)}s)`)
    } catch (e) {
      failed++; console.log(`  ✗ ${t.name}\n      ${e.message}`)
      const name = t.name.replace(/[^a-z0-9]+/gi, '-').slice(0, 60)
      try { await shot(`FAIL-${name}`); await intercept([]) } catch {}
    }
  }
} catch (e) {
  failed++; console.log('Setup failed: ' + e.message)
} finally {
  console.log(`\n${passed} passed, ${failed} failed in ${((Date.now() - t0) / 1000).toFixed(0)}s${failed ? `  (screenshots: ${out})` : ''}`)
  try { ws?.close() } catch {}
  chrome?.kill(); server?.kill()
  await sleep(300); rmSync(work, { recursive: true, force: true })
  process.exit(failed ? 1 : 0)
}
