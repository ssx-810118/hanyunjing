// Component interaction checks using Vue's real compiler and a non-browser renderer.
// No network, database writes, extra packages, or external browser are used.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import { isAxiosError } from 'axios'
import * as Pinia from 'pinia'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const records = JSON.parse(fs.readFileSync(path.join(root, '../.local/catalogue-repair-before.json'), 'utf8'))
const active = records.filter(r => r.status === 'ACTIVE').map(r => r.product)
const dynasties = ['汉', '唐', '宋', '元', '明']
let emptyDynasty = ''
const knowledge = { hits: [], abstained: false, humanRequired: false }
const navigations = [], productRequests = []
const deleteRequests = []
const replyRequests = [], ratingRequests = []
const adminReviews = [{ id: 'review-1', productId: 'p2', author: '衣友', rating: 5, content: '这件衣裳的款式很好看', reply: '', createdAt: '2026-10-03T00:00:00Z' }]
const publicReviews = [{ ...adminReviews[0], mine: false, purchased: false }]
const retailActions=[],cartNotices=[]
let retailRejected=false
const retailRuns=[{id:'run-1',origin:'LIVE',status:'DONE',summary:'已核对预算和库存',budget:500,size_label:'M',color_label:null,quantity:1,dynasty:'唐',scene:null,created_at:'2026-10-03T00:00:00Z',finished_at:'2026-10-03T00:00:01Z',elapsed_ms:1000,model_calls:1,tool_calls:1,input_tokens:10,output_tokens:5,candidates:[{sku_id:'p2-M',product_id:'p2',quoted_price:239,size_label:'M',color:'黛青',name:'圆领袍',status:'ACTIVE',deleted_at:null}],cases:[{state:'OPEN',reason:'请核对适用场景',reply:'',created_at:'2026-10-03T00:00:00Z',resolved_at:null}],steps:[{step_number:1,kind:'SERVER_TOOL',detail:'products',elapsed_ms:3},{step_number:2,kind:'MODEL_TOOL',detail:'submitDecision',elapsed_ms:1}],evidence:[],selections:[]}]
let adminDeletedId = '', rejectDelete = false, actualSession, fakeTimers, timerId = 0
const supportTickets = [null, '', 'null', 'undefined', 'p2', 'p1', 'offline'].map((productId, index) => ({ id: 'ticket-' + index, number: 'KF-' + index, category: '尺码选择', productId, orderId: null, message: '请帮我查看这件汉服。', status: 'RECORDED', createdAt: '2026-10-03T00:00:00Z' }))
supportTickets.push({ ...supportTickets[0], id: 'order-ticket', orderId: 'order-1' })
async function request(method, endpoint, session, body, params = {}) {
  if (endpoint === '/retail/runs' || endpoint === '/admin/retail/runs') return structuredClone(retailRuns)
  if (endpoint === '/admin/retail/metrics') return {total:1,failed:0,confirmed:0,running:0,openCases:1,resolvedCases:0,averageMs:1000,p95Ms:1000,cost:null,costNote:'未核验',scope:'排除独立评测'}
  if (endpoint === '/retail/runs/run-1/confirm') {
    retailActions.push({endpoint,body:structuredClone(body)})
    if(retailRejected)throw new Error('价格已变化，请重新问衣')
    retailRuns[0].status='CONFIRMED';retailRuns[0].selections=[{sku_id:body.skuId,confirmed_at:'2026-10-03T00:00:00Z'}]
    return {lines:[],quantity:1,total:239}
  }
  if(endpoint === '/admin/retail/cases/run-1'){
    retailActions.push({endpoint,body:structuredClone(body)})
    retailRuns[0].cases[0].state='RESOLVED';retailRuns[0].cases[0].reply=body.reply
    return null
  }
  if (endpoint === '/support/tickets') return structuredClone(supportTickets)
  if (endpoint.endsWith('/reply')) return { reply: '', status: 'RECORDED' }
  if (endpoint === '/knowledge/dynasties') return dynasties
  if (endpoint === '/products') return params.dynasty === emptyDynasty ? [] : active.filter(p => p.dynasty === params.dynasty)
  if (endpoint === '/knowledge/search' || endpoint.endsWith('/references')) return knowledge
  if (endpoint === '/admin/access') return { admin: true, setupRequired: false }
  if (endpoint === '/admin/reviews') return structuredClone(adminReviews)
  if (method === 'PUT' && endpoint === '/admin/reviews/review-1') {
    replyRequests.push(structuredClone(body))
    Object.assign(adminReviews[0], { reply: body.reply })
    return structuredClone(adminReviews[0])
  }
  if (endpoint === '/admin/products') return structuredClone(records.filter(r => r.product.id !== adminDeletedId))
  if (endpoint === '/admin/summary') return { products: records.filter(r => r.product.id !== adminDeletedId).length, activeProducts: active.filter(p => p.id !== adminDeletedId).length, stock: 1, reviewCount: adminReviews.length, orders: 0, paidTotal: 0, lowStock: [] }
  if (method === 'DELETE' && endpoint.startsWith('/admin/products/')) {
    deleteRequests.push({ endpoint, params })
    if (rejectDelete) throw new Error('商品或库存已变化，请刷新后再确认删除')
    adminDeletedId = endpoint.split('/').at(-1)
    return null
  }
  if (endpoint.startsWith('/admin/')) return []
  if (endpoint === '/products/p2/reviews') {
    if (method === 'POST') {
      ratingRequests.push(structuredClone(body))
      Object.assign(publicReviews[0], { rating: body.rating, content: body.content, mine: true })
    }
    return structuredClone(publicReviews)
  }
  if (endpoint.startsWith('/products/')) {
    productRequests.push(endpoint)
    if (endpoint === '/products/offline') throw new Error('网络暂不可用，请稍后重试。')
    const found = active.find(p => endpoint === '/products/' + p.id)
    if (found) return found
    throw Object.assign(new Error('Unavailable product'), { isAxiosError: true, response: { status: 404 } })
  }
  throw new Error('Unexpected API call ' + endpoint)
}
const stub = { setup(_, { slots }) { return () => Vue.h('stub', {}, slots.default?.()) } }
function load(file) {
  const source = fs.readFileSync(file, 'utf8')
  const compiled = file.endsWith('.vue') ? compileScript(parse(source).descriptor, { id: file, inlineTemplate: true }).content : source
  const js = ts.transpileModule(compiled, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  const require = id => {
    if (id === 'vue') return { ...Vue, Transition: stub, Teleport: stub, vModelText: {}, vModelSelect: {}, vModelCheckbox: {}, vModelRadio: {} }
    if (id === 'pinia') return Pinia
    if (id.endsWith('/api')) return { request, errorText: e => String(e) }
    if (id === 'axios') return { isAxiosError }
    if (id === 'vue-router') return { useRoute: () => ({ query: {}, params: {}, fullPath: '/support' }), useRouter: () => ({ push: async to => { navigations.push(to) } }) }
    if (id.endsWith('/stores/session')) return { useSession: () => actualSession || ({ activeId: 'component-check', current: { id: 'component-check' },confirmCartAddition:message=>cartNotices.push(message) }) }
    if (id.endsWith('/retail')) return load(path.join(root,'src/retail.ts'))
    if (id.endsWith('/stores/auth')) return { useAuth: () => ({ authenticated: true, initialize: async () => {} }) }
    if (id.endsWith('/composables/useAsync')) return load(path.join(root, 'src/composables/useAsync.ts'))
    if (id.endsWith('/types')) return { money: n => String(n), orderStatusNames: {} }
    if (id.endsWith('/EmptyErrorLoading.vue')) return load(path.join(root, 'src/components/EmptyErrorLoading.vue'))
    if (id.endsWith('.vue')) return { default: stub }
    throw new Error('Unexpected import ' + id)
  }
  const schedule = (callback, delay) => { if (!fakeTimers) return setTimeout(callback, delay); const id = ++timerId; fakeTimers.set(id, { callback, delay }); return id }
  const cancel = id => { if (fakeTimers) fakeTimers.delete(id); else clearTimeout(id) }
  vm.runInNewContext(js, { module, exports: module.exports, require, console, crypto: globalThis.crypto, AbortController, structuredClone, setTimeout: schedule, clearTimeout: cancel }, { filename: file })
  return module.exports
}
const node = (type, text = '') => ({ type, text, props: {}, children: [], parent: null, open: false, showModal() { this.open = true }, close() { this.open = false } })
function insert(child, parent, anchor) {
  if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
  const index = anchor ? parent.children.indexOf(anchor) : -1
  parent.children.splice(index < 0 ? parent.children.length : index, 0, child)
  child.parent = parent
}
const renderer = Vue.createRenderer({
  createElement: type => node(type), createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text; n.children = [] },
  parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
  patchProp: (n, key, before, after) => { n.props[key] = after }, insert,
  remove: n => { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = null },
  insertStaticContent: (text, parent, anchor) => { const n = node('static', text); insert(n, parent, anchor); return [n, n] }
})
function mount(file, props = {}) {
  const tree = node('root')
  const app = renderer.createApp(load(path.join(root, file)).default, props)
  app.component('RouterLink', { props: ['to'], setup(props, { slots }) { return () => Vue.h('a', { to: props.to }, slots.default?.()) } })
  app.mount(tree)
  return { tree, app }
}
function all(tree, match) { return (match(tree) ? [tree] : []).concat(tree.children.flatMap(n => all(n, match))) }
const textOf = n => n.text + n.children.map(textOf).join('')
const productIds = tree => all(tree, n => Boolean(n.props['data-product-id'])).map(n => n.props['data-product-id']).sort()
const banner = tree => all(tree, n => n.type === 'img' && n.props['data-source'])[0]
async function settle() { for (let i = 0; i < 15; i++) { await Promise.resolve(); await Vue.nextTick() } }
const publicPage = mount('src/views/CultureView.vue')
await settle()
async function select(dynasty) {
  const button = all(publicPage.tree, n => n.props['aria-label'] === '查阅' + dynasty + '代衣冠')[0]
  assert.ok(button, 'Dynasty tab exists: ' + dynasty)
  await button.props.onClick()
  await settle()
}
for (const dynasty of dynasties) {
  await select(dynasty)
  assert.deepEqual(productIds(publicPage.tree), active.filter(p => p.dynasty === dynasty).map(p => p.id).sort())
  assert.ok(banner(publicPage.tree), 'Available banner: ' + dynasty)
}
await select('唐')
assert.equal(banner(publicPage.tree).props['data-source'], active.find(p => p.id === 'p2').images[0], 'Withdrawn preferred image falls back')
const count = active.filter(p => p.dynasty === '唐' && p.images[0]).length
for (let i = 0; i < count; i++) {
  const current = banner(publicPage.tree)
  current.props.onError({ target: { dataset: { source: current.props['data-source'] } } })
  await settle()
}
assert.equal(banner(publicPage.tree), undefined, 'Failed images exhaust safely')
all(publicPage.tree, n => n.type === 'button' && textOf(n) === '重载图片')[0].props.onClick()
await settle()
assert.match(banner(publicPage.tree).props.src, /[?&]retry=[0-9]+/, 'Retry bypasses stale image cache')
await select('宋')
assert.ok(banner(publicPage.tree), 'Image errors reset on dynasty change')
await Promise.all([select('唐'), select('明')])
assert.deepEqual(productIds(publicPage.tree), active.filter(p => p.dynasty === '明').map(p => p.id).sort(), 'Late response cannot replace the latest dynasty')
emptyDynasty = '唐'
await select('唐')
assert.equal(banner(publicPage.tree), undefined)
assert.ok(!all(publicPage.tree, n => typeof n.props.to === 'string' && n.props.to.startsWith('/product/')).length, 'Empty dynasty has no dangling product link')
publicPage.app.unmount()

const adminPage = mount('admin/AdminView.vue')
await settle()
assert.deepEqual(productIds(adminPage.tree), active.map(p => p.id).sort(), 'Admin defaults to the public ACTIVE catalogue')
for (const [label, status] of [['草稿', 'DRAFT'], ['已下架', 'ARCHIVED'], ['全部记录', ''], ['前台展示', 'ACTIVE']]) {
  const group = all(adminPage.tree, n => n.props['aria-label'] === '商品展示状态')[0]
  all(group, n => n.type === 'button' && textOf(n).startsWith(label))[0].props.onClick()
  await settle()
  assert.deepEqual(productIds(adminPage.tree), records.filter(r => !status || r.status === status).map(r => r.product.id).sort(), label)
}
const dynastySelect = all(adminPage.tree, n => n.props['aria-label'] === '筛选朝代')[0]
dynastySelect.props['onUpdate:modelValue']('唐')
await settle()
assert.deepEqual(productIds(adminPage.tree), active.filter(p => p.dynasty === '唐').map(p => p.id).sort())
adminPage.app.unmount()
console.log('PASS: all 24 published items render; banner fallback, exhausted-image retry, dynasty switching, empty catalogue, race handling and admin status filters work.')

const supportPage = mount('src/views/SupportView.vue')
await settle()
const garmentButtons = () => all(supportPage.tree, n => n.type === 'button' && n.props.class === 'related-product-button')
const selectionDialog = all(supportPage.tree, n => n.type === 'dialog')[0]
assert.equal(garmentButtons().length, 7, 'Unlinked tickets retain the requested action; order tickets use their order link')
for (let index = 0; index < 4; index++) {
  const requestsBefore = productRequests.length
  await garmentButtons()[index].props.onClick()
  await settle()
  assert.equal(selectionDialog.open, true, 'Missing garment opens a modal')
  assert.ok(textOf(selectionDialog).includes('你还没有选择汉服'))
  assert.equal(productRequests.length, requestsBefore, 'No request for missing or sentinel product IDs')
  assert.equal(navigations.length, 0, 'Missing selection never navigates to an error page')
  all(selectionDialog, n => n.type === 'button' && textOf(n) === '我知道了')[0].props.onClick()
  assert.equal(selectionDialog.open, false, 'Confirmation closes the modal')
}
await garmentButtons()[4].props.onClick()
await settle()
assert.deepEqual(navigations, ['/product/p2'], 'A valid associated garment opens its detail page')
await garmentButtons()[5].props.onClick()
await settle()
assert.equal(selectionDialog.open, true)
assert.ok(textOf(selectionDialog).includes('已下架或不存在'))
assert.equal(navigations.length, 1, 'Withdrawn garment remains on the support page')
selectionDialog.close()
await garmentButtons()[6].props.onClick()
await settle()
assert.ok(textOf(selectionDialog).includes('网络暂不可用'))
assert.equal(navigations.length, 1, 'Network errors do not navigate')
assert.ok(all(supportPage.tree, n => n.props.to?.path === '/orders' && n.props.to.query.orderId === 'order-1').length)
assert.ok(all(selectionDialog, n => n.props.to === '/culture').length, 'Modal offers a garment selection destination')
supportPage.app.unmount()
console.log('PASS: missing/null/empty selections show the modal; valid garments navigate; unavailable and network-failed links stay on the support page; order links remain intact.')

const deletionPage = mount('admin/AdminView.vue')
await settle()
const deleteButtons = () => all(deletionPage.tree, n => n.type === 'button' && n.props.class === 'delete-product')
async function deletionFilter(label) {
  const group = all(deletionPage.tree, n => n.props['aria-label'] === '商品展示状态')[0]
  all(group, n => n.type === 'button' && textOf(n).startsWith(label))[0].props.onClick()
  await settle()
}
assert.equal(deleteButtons().length, 0, 'Published catalogue offers no delete action')
await deletionFilter('全部记录')
assert.equal(deleteButtons().length, 0, 'All-records view offers no delete action')
await deletionFilter('草稿')
assert.equal(deleteButtons().length, records.filter(r => r.status === 'DRAFT').length)
assert.ok(deleteButtons().length > 0, 'Draft products offer deletion')
await deleteButtons()[0].props.onClick()
await settle()
const draftDialog = all(deletionPage.tree, n => n.type === 'dialog')[0]
assert.equal(draftDialog.open, true, 'Draft deletion also requires confirmation')
all(draftDialog, n => n.type === 'button' && textOf(n) === '取消')[0].props.onClick()
assert.equal(deleteRequests.length, 0, 'Canceling draft deletion sends no request')
await deletionFilter('已下架')
assert.equal(deleteButtons().length, records.filter(r => r.status === 'ARCHIVED').length)
const deletingRow = () => all(deletionPage.tree, n => n.props['data-product-id'] === 'p1')[0]
const openDelete = async () => { await all(deletingRow(), n => n.type === 'button' && n.props.class === 'delete-product')[0].props.onClick(); await settle() }
await openDelete()
const deletionDialog = all(deletionPage.tree, n => n.type === 'dialog')[0]
assert.equal(deletionDialog.open, true)
assert.equal(deleteRequests.length, 0, 'Opening confirmation does not delete anything')
all(deletionDialog, n => n.type === 'button' && textOf(n) === '取消')[0].props.onClick()
assert.equal(deletionDialog.open, false)
assert.equal(deleteRequests.length, 0, 'Cancel is non-destructive')
await openDelete()
rejectDelete = true
await all(deletionDialog, n => n.type === 'button' && textOf(n) === '确认删除')[0].props.onClick()
await settle()
assert.ok(deletingRow(), 'Failed deletion keeps the row')
assert.equal(deletionDialog.open, true)
assert.ok(textOf(deletionDialog).includes('商品或库存已变化'))
rejectDelete = false
await all(deletionDialog, n => n.type === 'button' && textOf(n) === '确认删除')[0].props.onClick()
await settle()
assert.equal(deletionDialog.open, false)
assert.equal(deletingRow(), undefined, 'Successful deletion removes the row even after refresh')
assert.equal(deleteRequests.at(-1).endpoint, '/admin/products/p1')
assert.equal(deleteRequests.at(-1).params.revision, records.find(r => r.product.id === 'p1').revision)
deletionPage.app.unmount()
console.log('PASS: deletion is limited to draft/archived lists; confirmation, cancel, stale-data error, revision submission and row removal work.')

const replyPage = mount('admin/AdminView.vue')
await settle()
const modules = all(replyPage.tree, n => n.props['aria-label'] === '后台模块')[0]
all(modules, n => n.type === 'button' && textOf(n) === '评论回复')[0].props.onClick()
await settle()
const reviewPanel = () => all(replyPage.tree, n => n.type === 'article' && n.props.class === 'panel review-panel')[0]
const replyButton = () => all(reviewPanel(), n => n.type === 'button')[0]
assert.equal(all(reviewPanel(), n => n.type === 'button').length, 1, 'Review has one confirmation action')
assert.equal(textOf(replyButton()), '确认回复')
assert.equal(replyButton().props.disabled, true, 'Empty replies cannot be submitted')
assert.ok(!/审核|不予展示|待审核/.test(textOf(replyPage.tree)), 'No review approval workflow remains in the admin page')
assert.ok(textOf(replyPage.tree).includes('评论总数'))
all(reviewPanel(), n => n.type === 'textarea')[0].props['onUpdate:modelValue']('   ')
await settle()
assert.equal(replyButton().props.disabled, true, 'Whitespace-only replies cannot be submitted')
all(reviewPanel(), n => n.type === 'textarea')[0].props['onUpdate:modelValue']('  谢谢你的喜爱！  ')
await settle()
assert.equal(replyButton().props.disabled, false)
await replyButton().props.onClick()
await settle()
assert.deepEqual(replyRequests, [{ reply: '谢谢你的喜爱！' }])
assert.ok(!textOf(reviewPanel()).includes('已展示'))
assert.ok(textOf(replyPage.tree).includes('商家回复已确认并保存'))
replyPage.app.unmount()
console.log('PASS: admin provides merchant replies only, without approval controls, states or payload fields.')

const ratingPage = mount('src/components/ProductReviews.vue', { productId: 'p2' })
await settle()
assert.ok(textOf(ratingPage.tree).includes('这件衣裳的款式很好看'), 'Unreplied comments are displayed')
assert.ok(!textOf(ratingPage.tree).includes('审核'))
const radios = () => all(ratingPage.tree, n => n.type === 'input' && n.props.type === 'radio')
const starLabels = () => all(ratingPage.tree, n => n.type === 'label' && n.props.class === 'rating-star')
const starText = () => starLabels().map(textOf).join('')
assert.equal(all(ratingPage.tree, n => n.type === 'select').length, 0, 'Rating has no dropdown')
assert.equal(radios().length, 5)
assert.deepEqual(radios().map(n => n.props['aria-label']), ['1星', '2星', '3星', '4星', '5星'])
assert.ok(radios().every(n => n.props.name === 'review-rating-p2'))
assert.equal(starText(), '★★★★★')
starLabels()[1].props.onMouseenter()
await settle()
assert.equal(starText(), '★★☆☆☆', 'Hover previews the selected star count')
all(ratingPage.tree, n => n.props.class === 'rating-stars')[0].props.onMouseleave()
await settle()
assert.equal(starText(), '★★★★★', 'Leaving restores the saved rating')
for (const value of [1, 3, 5]) {
  radios()[value - 1].props['onUpdate:modelValue'](value)
  await settle()
  assert.equal(starText(), '★'.repeat(value) + '☆'.repeat(5 - value))
  all(ratingPage.tree, n => n.type === 'textarea')[0].props['onUpdate:modelValue']('这是一条有效的体验评论')
  await all(ratingPage.tree, n => n.type === 'form')[0].props.onSubmit({ preventDefault() {} })
  await settle()
  assert.equal(ratingRequests.at(-1).rating, value, 'Selected rating reaches the submission payload')
  assert.equal(typeof ratingRequests.at(-1).rating, 'number')
  assert.ok(textOf(ratingPage.tree).includes('评论已发布'))
  assert.ok(textOf(ratingPage.tree).includes('这是一条有效的体验评论'))
  assert.ok(!textOf(ratingPage.tree).includes('审核'))
}
ratingPage.app.unmount()
console.log('PASS: five rating radios show hover/selection states and submit numeric ratings of 1, 3 and 5.')

const selection = mount('src/components/RetailSelection.vue',{runId:'run-1',quantity:1,rec:{product:active.find(p=>p.id==='p2'),eligibleSkus:[{id:'p2-M',color:'黛青',size:'M',price:239,stock:2}],evidence:[]}})
await settle()
const selectConfirm=()=>all(selection.tree,n=>n.type==='button'&&textOf(n)==='确认选购')[0]
assert.equal(selectConfirm().props.disabled,true)
all(selection.tree,n=>n.type==='select')[0].props['onUpdate:modelValue']('p2-M')
await settle()
await selectConfirm().props.onClick();await settle()
const selectionConfirmation=all(selection.tree,n=>n.type==='dialog')[0]
assert.equal(selectionConfirmation.open,true)
assert.equal(retailActions.length,0,'Opening purchase confirmation performs no write')
all(selectionConfirmation,n=>n.type==='button'&&textOf(n)==='取消')[0].props.onClick()
assert.equal(retailActions.length,0)
await selectConfirm().props.onClick();await settle()
retailRejected=true
await all(selectionConfirmation,n=>n.type==='button'&&textOf(n)==='确认加入衣囊')[0].props.onClick()
await settle();assert.ok(textOf(selectionConfirmation).includes('价格已变化'));assert.equal(selectionConfirmation.open,true)
retailRejected=false
await all(selectionConfirmation,n=>n.type==='button'&&textOf(n)==='确认加入衣囊')[0].props.onClick()
await settle();assert.equal(selectionConfirmation.open,false);assert.ok(textOf(selection.tree).includes('已加入衣囊'))
assert.deepEqual(retailActions.at(-1).body,{skuId:'p2-M'});assert.equal(cartNotices.length,1)
selection.app.unmount()
const retailAdmin=mount('src/components/RetailHistory.vue',{admin:true})
await settle()
assert.ok(textOf(retailAdmin.tree).includes('服务端预查询'));assert.ok(textOf(retailAdmin.tree).includes('模型调用工具'))
const finishCase=()=>all(retailAdmin.tree,n=>n.type==='button'&&textOf(n)==='回复并完成待办')[0]
assert.equal(finishCase().props.disabled,true)
all(retailAdmin.tree,n=>n.type==='textarea')[0].props['onUpdate:modelValue']('已核对，适合日常游园。')
await settle();await finishCase().props.onClick();await settle()
assert.equal(finishCase(),undefined)
assert.ok(textOf(retailAdmin.tree).includes('已核对，适合日常游园。'))
retailAdmin.app.unmount()
const retailCustomer=mount('src/components/RetailHistory.vue')
await settle()
assert.ok(textOf(retailCustomer.tree).includes('已核对，适合日常游园。'))
assert.equal(all(retailCustomer.tree,n=>n.type==='textarea').length,0,'Customers cannot edit merchant replies')
assert.ok(textOf(retailCustomer.tree).includes('已确认加购'))
retailCustomer.app.unmount()
console.log('PASS: retail selection requires confirmation, shows stale-price errors, confirms exact SKU, and merchant replies reach account history.')

Pinia.setActivePinia(Pinia.createPinia())
actualSession = load(path.join(root, 'src/stores/session.ts')).useSession()
fakeTimers = new Map()
actualSession.notice = '已加入衣囊。'
actualSession.confirmCartAddition('已加入衣囊：第一件')
const cartNotice = mount('src/components/CartAddedNotice.vue')
await settle()
assert.equal(actualSession.notice, '', 'Cart confirmation no longer creates a persistent duplicate toast')
assert.ok(textOf(cartNotice.tree).includes('已加入衣囊：第一件'))
assert.equal(fakeTimers.size, 1)
const oldTimer = [...fakeTimers.values()][0]
assert.equal(oldTimer.delay, 2200)
actualSession.notice = '其他操作提示'
actualSession.confirmCartAddition('已加入衣囊：第二件')
await settle()
assert.equal(actualSession.notice, '其他操作提示', 'Other messages are preserved')
assert.equal(fakeTimers.size, 1, 'Repeat additions reset the timer')
oldTimer.callback()
assert.equal(actualSession.cartConfirmation.message, '已加入衣囊：第二件', 'A stale timeout cannot close the new message')
const [latestId, latestTimer] = [...fakeTimers.entries()][0]
fakeTimers.delete(latestId); latestTimer.callback()
await settle()
assert.equal(actualSession.cartConfirmation, null)
assert.equal(all(cartNotice.tree, n => n.props.class === 'cart-confirmation-card').length, 0)
actualSession.confirmCartAddition()
await settle()
all(cartNotice.tree, n => n.props['aria-label'] === '关闭加入衣囊提示')[0].props.onClick()
await settle()
assert.equal(actualSession.cartConfirmation, null)
assert.equal(fakeTimers.size, 0)
actualSession.confirmCartAddition()
await settle()
cartNotice.app.unmount()
assert.equal(fakeTimers.size, 0, 'Unmount cancels the pending dismissal')
console.log('PASS: cart confirmation expires automatically, resets on repeat additions, supports manual dismissal and clears timers.')
