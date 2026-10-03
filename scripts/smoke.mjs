import assert from 'node:assert/strict'

// Real HTTP only: start both servers first. No browser or mocked responses.
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5173'
const sid = `smoke-${Date.now()}`
let checks = 0
const check = (value, name) => { assert.ok(value, name); checks++; console.log(`PASS ${name}`) }
async function raw(path, options = {}) {
  return fetch(new URL(path, base), { ...options, signal: AbortSignal.timeout(75000) })
}
async function api(path, method = 'GET', body, session = sid, status = 200) {
  const url = new URL(`/api${path}`, base)
  if (session) url.searchParams.set('sessionId', session)
  const response = await raw(url, { method, ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) })
  assert.equal(response.status, status, `${method} ${path}`)
  const result = await response.json()
  assert.equal(result.code, status === 200 ? 0 : status)
  return result.data
}
const home = await raw('/')
check(home.ok && (await home.text()).includes('/src/main.ts'), 'Vite home HTML served (not browser rendering)')
const products = await api('/products')
check(products.length === 10, '10 real products through Vite proxy')
check((await api('/scenes')).length === 5, '5 scenes')
const online = await api('/agent/status')
check(online.mode === 'ONLINE_ONLY', 'production agent is online only')
let externalModelVerified = false
if (!online.ready) {
  await api('/agent/chat', 'POST', { sessionId: sid, message: '我第一次穿，去芙蓉园，想要唐制，预算300元' }, sid, 503)
  check(true, 'unconfigured online agent returns 503 without fallback')
} else if (process.env.SMOKE_ONLINE === 'true') {
  const reply = await api('/agent/chat', 'POST', { sessionId: sid, message: '我第一次穿，去芙蓉园，想要唐制，预算300元' })
  check(reply.recommendations.length <= 3 && reply.recommendations.every(r => products.some(p => p.id === r.product.id)), 'online agent selects only real catalog IDs')
  const trace = await api('/trace/events')
  check(trace.some(e => e.type === 'LLM_CALL_RESULT') && trace.some(e => e.type === 'TOOL_RESULT'), 'actual model/tool trace observed')
  externalModelVerified = true
} else { console.log('SKIP paid online call: set SMOKE_ONLINE=true explicitly') }
await api('/user/body-profile', 'PUT', { height: 168, weightKg: 60, chest: 88, waist: 72, hip: 94, loose: false })
check((await api('/user/body-profile')).weightKg === 60, 'synthetic body fixture stays in local profile API')
// Independent demo commerce fixture, NOT a model recommendation or fallback.
const product = products.find(p => p.id === 'p1')
const sku = product.skus.find(s => s.size === 'M' && s.stock > 0)
assert.ok(sku)
// Backend-generated clothing illustration: synthetic pixels, never a real person.
const imageResponse = await raw(product.images[0])
const image = await imageResponse.arrayBuffer()
check(imageResponse.ok && imageResponse.headers.get('content-type').includes('image/png'), 'synthetic non-person PNG fixture')
async function upload(authorized) {
  const form = new FormData()
  form.append('file', new Blob([image], { type: 'image/png' }), 'synthetic-clothing.png')
  return raw(`/api/tryon/portrait?sessionId=${sid}&authorized=${authorized}`, { method: 'POST', body: form })
}
check((await upload(false)).status === 400, 'upload without consent rejected')
const uploaded = await upload(true)
assert.equal(uploaded.status, 200)
const portrait = (await uploaded.json()).data
check(portrait.width === 480 && portrait.height === 640, 'authorized synthetic upload decoded')
let task = await api('/tryon/generate', 'POST', { sessionId: sid, portraitId: portrait.id, productId: product.id, skuId: sku.id })
for (let i = 0; i < 40 && !['DONE', 'DEGRADED'].includes(task.status); i++) {
  await new Promise(resolve => setTimeout(resolve, 100))
  task = await api(`/tryon/tasks/${task.id}`)
}
check(task.status === 'DONE' && task.demo && task.stage === 'SELF_CHECK', 'try-on completes as honest demo')
const result = await raw(`/api/tryon/tasks/${task.id}/result?sessionId=${sid}`)
const bytes = Buffer.from(await result.arrayBuffer())
check(result.ok && result.headers.get('cache-control').includes('no-store') && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])), 'result PNG signature and no-store')
await api(`/tryon/tasks/${task.id}`, 'GET', undefined, `${sid}-other`, 404)
check(true, 'cross-session try-on rejected')
const cart = await api('/cart', 'POST', { productId: product.id, skuId: sku.id, quantity: 1 })
const preview = await api('/orders/preview', 'POST')
check(cart.total === sku.price && preview.payable === sku.price && preview.demo, 'server cart and preview prices')
const order = await api('/orders', 'POST')
check(order.status === 'DEMO_PAID' && order.demo && (await api('/cart')).quantity === 0, 'mock order clears cart')
check((await api(`/products/${product.id}`)).skus.find(s => s.id === sku.id).stock === sku.stock - 1, 'real in-memory inventory decremented')
const keyword = `验收独立纹样${Date.now()}`
check((await api(`/knowledge/search?q=${encodeURIComponent(keyword)}`)).abstained, 'unknown knowledge abstains')
await api('/knowledge/add', 'POST', { title: '验收合成资料', topic: '验收', content: '仅用于接口验收的合成知识，不是历史事实。', kind: 'ADVICE', source: 'HTTP smoke synthetic fixture', keywords: [keyword], claimKey: keyword, claimValue: 'demo' })
const knowledge = await api(`/knowledge/search?q=${encodeURIComponent(keyword)}`)
check(!knowledge.abstained && knowledge.hits.some(h => h.article.content.includes('合成知识')), 'knowledge supplement immediately retrieved')
const events = await api('/trace/events')
for (const stage of ['PARSING', 'MATCHING', 'RENDERING', 'SELF_CHECK']) check(events.some(e => e.type === `TRYON_${stage}`), `real trace stage ${stage}`)
check(events.some(e => e.type === 'KNOWLEDGE_GAP') && events.some(e => e.type === 'ORDER_DEMO_PAID'), 'real gap/order trace events')
check(!JSON.stringify(events).includes('168') && !JSON.stringify(events).includes('胸围'), 'trace omits submitted body values')
const summary = await api('/ops/summary')
check(summary.prebuilt === false && summary.events > 0, 'ops uses real event count')
const stream = await raw(`/api/trace/stream?sessionId=${sid}&after=0`)
assert.ok(stream.ok && stream.headers.get('content-type').includes('text/event-stream'))
const reader = stream.body.getReader()
let sse = ''
try { for (let i = 0; i < 20 && !sse.includes('event:trace'); i++) { const chunk = await reader.read(); if (chunk.done) break; sse += new TextDecoder().decode(chunk.value) } } finally { await reader.cancel() }
check(sse.includes('event:trace'), 'SSE replay crosses proxy')
await api(`/tryon/portrait/${portrait.id}`, 'DELETE')
await api(`/tryon/tasks/${task.id}`, 'GET', undefined, sid, 404)
await api('/user/body-profile', 'DELETE')
check((await api('/user/body-profile')) === null, 'explicit portrait/task/body cleanup')
console.log(JSON.stringify({ base, sessionId: sid, checks, productId: product.id, taskId: task.id, orderId: order.id, browserTested: false, externalModelVerified, note: 'Order stock decrement, synthetic knowledge and non-sensitive traces remain until restart.' }, null, 2))
