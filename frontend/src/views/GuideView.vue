<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { request, errorText } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import { useTrace, traceLabel } from '../composables/useTrace'
import { fieldNames } from '../types'
import type { AgentReply, Recommendation } from '../types'
import ProductArt from '../components/ProductArt.vue'
import KnowledgePanel from '../components/KnowledgePanel.vue'
import TracePanel from '../components/TracePanel.vue'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
import SizeAssistant from '../components/SizeAssistant.vue'
import RetailSelection from '../components/RetailSelection.vue'
import type { RetailRequirements } from '../retail'

const store = useSession(), route = useRoute(), session = store.current
const requirements=reactive<RetailRequirements>({...(session.turns.at(-1)?.reply.requirements??{budget:null,size:null,color:null,quantity:1})})
const draft = ref(typeof route.query.prompt === 'string' ? route.query.prompt : ''), suggestions = ref<string[]>([])
const loading = ref(false), error = ref(''), stopped = ref(false), initial = useAsync()
const latest = computed(() => session.turns.at(-1)?.reply)
const displayedTurns = computed(() => session.turns.map((turn, index) => ({ ...turn, number: index + 1 })).reverse())
const expandedTurnIds = ref(new Set(session.turns.at(-1) ? [session.turns.at(-1)!.id] : []))
const sizeProductId = computed(() => typeof route.query.productId === 'string' ? route.query.productId : '')
const sizeSkuId = computed(() => typeof route.query.skuId === 'string' ? route.query.skuId : '')
function toggleReply(id: string) { const expanded = new Set(expandedTurnIds.value); expanded.has(id) ? expanded.delete(id) : expanded.add(id); expandedTurnIds.value = expanded }
watch(() => route.query.prompt, value => { if (typeof value === 'string' && !loading.value) draft.value = value })
const trace = useTrace(() => store.activeId)
const { events, state: traceState, error: traceError, paused: tracePaused } = trace
interface OnlineStatus { enabled: boolean; configured: boolean; ready: boolean; mode: string; connection: string; message: string }
const online = ref<OnlineStatus | null>(null), statusError = ref(''), lastAttempt = ref('')
const startedAt = ref(0), elapsedMs = ref(0), baselineEventId = ref(0), completedSeconds = ref<number>()
const elapsedSeconds = computed(() => Math.floor(elapsedMs.value / 1000))
const statusLabel = computed(() => statusError.value ? '在线状态读取失败' : !online.value ? '正在读取在线状态' : !online.value.enabled ? '在线 Agent 已禁用' : !online.value.configured ? '在线 Agent 未配置' : ['FAILED','TIMEOUT'].includes(online.value.connection) ? '最近模型调用失败，可重试' : online.value.connection === 'RESPONDED' ? '在线 Agent · 模型已响应' : '在线 Agent · 已配置，连通性待验证')
const progressTypes = new Set(['AGENT_STARTED', 'AGENT_PROGRESS', 'AGENT_CONTEXT_READY', 'LOCAL_CHECKS_READY', 'LLM_CALL_START', 'LLM_CALL_RESULT', 'TOOL_CALL', 'TOOL_RESULT', 'TOOL_FAILURE', 'AGENT_ONLINE_DONE', 'LLM_FAILURE'])
const currentStep = computed(() => {
  if (!startedAt.value) return undefined
  // A prior stopped request can still emit events. Do not call those the new turn's progress.
  const incoming = events.value.filter(event => event.id > baselineEventId.value && new Date(event.timestamp).getTime() >= startedAt.value)
  const beginning = incoming.findIndex(event => event.type === 'AGENT_STARTED')
  return beginning < 0 ? undefined : incoming.slice(beginning).reverse().find(event => progressTypes.has(event.type))
})
const progressTitle = computed(() => currentStep.value ? traceLabel(currentStep.value.type) : '正在提交需求，等待服务端确认')
let controller: AbortController | undefined, clock: ReturnType<typeof setInterval> | undefined
let disposed = false, requestGeneration = 0
const example = '我第一次穿，去芙蓉园，想要唐制，颜色素雅，预算300元'
const promptGroups = [
  { id: 'scene', label: '按场景', prompts: [
    { label: '园林漫游', text: '准备去园林散步和拍照，想选一套素雅、方便走动的衣裳，请推荐并说明搭配。' },
    { label: '古城出游', text: '准备去古城游玩一整天，希望衣裳方便行走、穿脱不太复杂，预算500元以内。' },
    { label: '节日赏灯', text: '想穿汉服去节日灯会，偏好明亮但不过分张扬的颜色，请推荐合适款式和搭配。' },
    { label: '日常通勤', text: '想找适合日常通勤的汉服搭配，偏好简洁、低饱和颜色，希望行动方便。' },
    { label: '朋友雅集', text: '准备参加朋友的汉服雅集，想要端庄、有层次的搭配，朝代不限，请说明推荐理由。' },
  ] },
  { id: 'dynasty', label: '按朝代', prompts: [
    { label: '汉 · 古朴端正', text: '想了解汉代服饰参考款，偏好古朴端正的感觉，请推荐现有款式，并说明形制参考和出处。' },
    { label: '唐 · 明丽舒展', text: '想选唐代服饰参考款，偏好明丽舒展的风格，请推荐现有款式，并说明形制参考和出处。' },
    { label: '宋 · 清雅简约', text: '想选宋代服饰参考款，偏好清雅简约的搭配，请推荐现有款式，并说明形制参考和出处。' },
    { label: '元 · 服饰参考', text: '想了解元代服饰与蒙古族服饰参考，请介绍目录中现有款式的形制和出处，并说明适合的穿着场景。' },
    { label: '明 · 端庄有序', text: '想选明代服饰参考款，偏好端庄有层次的搭配，请推荐现有款式，并说明形制参考和出处。' },
  ] },
  { id: 'style', label: '按风格', prompts: [
    { label: '素雅低饱和', text: '喜欢素雅、低饱和颜色，不想太亮，朝代不限，请推荐适合日常出游的搭配。' },
    { label: '温柔淡色', text: '喜欢温柔的浅色系，想去园林拍照，请推荐现有衣裳，并给出配色和配饰建议。' },
    { label: '利落飒爽', text: '想要利落飒爽的穿搭，偏好方便行走的款式，请推荐并说明衣裳的结构特点。' },
    { label: '端庄正式', text: '想要适合文化活动的端庄穿搭，装饰不要太繁复，请推荐款式和配饰。' },
    { label: '明丽华美', text: '喜欢明丽华美的颜色和有层次的搭配，准备用来拍照，请推荐现有款式。' },
  ] },
  { id: 'budget', label: '按预算', prompts: [
    { label: '300元内入门', text: '第一次购买汉服，衣裳预算300元以内，想要素雅、容易搭配的款式；没有合适款式请直接说明。' },
    { label: '500元内出游', text: '衣裳预算500元以内，准备古城出游，希望穿着步骤简单，请推荐现有款式。' },
    { label: '800元内雅集', text: '衣裳预算800元以内，准备参加汉服雅集，偏好端庄的款式，请说明价格和搭配建议。' },
    { label: '衣裳预算600元', text: '衣裳预算600元，请根据现有款式推荐，配饰我会另行选择。' },
    { label: '先看价格再挑', text: '我还没确定预算，请介绍当前目录不同价格的衣裳和主要区别，方便我选择。' },
  ] },
  { id: 'beginner', label: '初穿与搭配', prompts: [
    { label: '第一次穿怎么选', text: '第一次穿汉服，暂时不了解朝代和形制，想先选穿脱简单的款式，请用容易理解的话介绍。' },
    { label: '不想穿太多层', text: '想选层数少、方便活动的搭配，准备日常出游，请说明推荐款式需要哪些衣物。' },
    { label: '配饰怎样搭', text: '想了解汉服的鞋履和配饰怎么搭，请根据当前推荐的衣裳给出简洁搭配，并标明目录里能购买的配饰。' },
    { label: '尺码从哪里看', text: '我不知道怎么选尺码，请说明衣裳尺码表要看哪些项目，并告诉我如何使用商品的问尺码助手。' },
    { label: '怎么穿与打理', text: '我第一次穿汉服，请结合推荐的款式说明基本穿着步骤和打理注意事项，清洗方式以商品说明为准。' },
  ] },
]
const activePromptGroup = ref(promptGroups[0]!.id)
const visiblePrompts = computed(() => promptGroups.find(group => group.id === activePromptGroup.value)?.prompts ?? [])
const promptNotice = ref('')
function fillPrompt(text: string) {
  if (loading.value) return
  draft.value = text
  promptNotice.value = '已填入下方输入框，可修改后再送出问衣笺。'
}
function stopClock() {
  if (startedAt.value) elapsedMs.value = Date.now() - startedAt.value
  clearInterval(clock); clock = undefined
}
async function refreshStatus() {
  try { const value = await request<OnlineStatus>('GET', '/agent/status'); if (!disposed) { online.value = value; statusError.value = '' } }
  catch (cause) { if (!disposed) statusError.value = errorText(cause) }
}
async function send(text = draft.value) {
  const message = text.trim()
  if (!message || loading.value || disposed) return
  const token = ++requestGeneration, activeController = new AbortController()
  controller = activeController; loading.value = true; error.value = ''; stopped.value = false; completedSeconds.value = undefined
  draft.value = message; lastAttempt.value = message; baselineEventId.value = events.value.at(-1)?.id ?? 0
  startedAt.value = Date.now(); elapsedMs.value = 0
  clearInterval(clock); clock = setInterval(() => { elapsedMs.value = Date.now() - startedAt.value }, 1000)
  if (tracePaused.value) void trace.connect()
  try {
    const reply = await request<AgentReply>('POST', '/agent/chat', undefined, { sessionId: session.id, message,requirements:{...requirements,budget:requirements.budget==null||String(requirements.budget)===''?null:Number(requirements.budget),size:requirements.size||null,color:requirements.color||null,quantity:Number(requirements.quantity)} }, {}, activeController.signal)
    if (disposed || token !== requestGeneration || activeController.signal.aborted) return
    const turnId = crypto.randomUUID()
    session.turns.push({ id: turnId, message, reply })
    if(reply.requirements)Object.assign(requirements,reply.requirements)
    expandedTurnIds.value = new Set([turnId])
    if (draft.value.trim() === message) draft.value = ''
    completedSeconds.value = Math.ceil((Date.now() - startedAt.value) / 1000)
  } catch (cause) {
    if (!disposed && token === requestGeneration && !activeController.signal.aborted) error.value = errorText(cause)
  } finally {
    if (!disposed && token === requestGeneration) {
      stopClock(); loading.value = false; controller = undefined
      void refreshStatus()
    }
  }
}
function stopWaiting() {
  if (!loading.value) return
  requestGeneration++; controller?.abort(); controller = undefined
  stopClock(); loading.value = false; stopped.value = true; error.value = ''
}
function tryQuery(rec: Recommendation) { return { productId: rec.product.id, skuId: rec.eligibleSkus[0]?.id, from: 'guide' } }
const load = () => initial.run(async () => {
  await Promise.all([refreshStatus(), request<string[]>('GET', '/suggestions', session.id).then(value => { if (!disposed) suggestions.value = value }), trace.connect()])
})
onMounted(load)
onBeforeUnmount(() => { disposed = true; requestGeneration++; controller?.abort(); stopClock(); draft.value = ''; lastAttempt.value = '' })
</script>

<template>
  <div class="page guide-page">
    <div class="section-heading"><div><p class="eyebrow">问衣使 · 有据可循</p><h1>把犹豫，交给一场问答</h1></div><span class="tag">{{ statusLabel }}</span></div>
    <div class="guide-layout">
      <aside class="panel history">
        <h2>我的问衣笺</h2><button class="primary full" @click="store.create">＋ 新开一笺</button>
        <button v-for="s in store.sessions" :key="s.id" :class="['session-item', { selected: s.id === store.activeId }]" @click="store.activeId = s.id">{{ s.name }}<small>{{ s.turns.length }} 轮答复</small></button>
        <p class="muted">对话草稿在本页暂存；选购条件、候选规格和商家回复保存在你的账号中。</p><RouterLink to="/reception">选购记录与商家回复 →</RouterLink><br><RouterLink to="/profile">管理身材与人像 →</RouterLink>
      </aside>
      <section class="chat-main">
        <SizeAssistant v-if="sizeProductId" :key="`${sizeProductId}:${sizeSkuId}`" :product-id="sizeProductId" :sku-id="sizeSkuId"/>
        <div class="panel intro">
          <span class="seal">问</span><h2>何处游，何衣宜？</h2>
          <p class="online-detail" role="status">{{ statusError || online?.message }}<button type="button" @click="refreshStatus">刷新在线状态</button></p>
          <p>告诉我场景、朝代、预算与是否首次穿着。需求将发给配置的在线模型；请勿输入身材、人像、联系方式等敏感信息。尺码可从推荐商品的“问尺码助手”单独核对，或在个人中心管理资料。</p>
          <div class="prompt-picker">
            <div class="prompt-heading"><h3>从一句心意开始</h3><span>25条问衣灵感</span></div>
            <p class="prompt-hint">先选一个方向，点选短句填入下方；也可以改成自己的需求。</p>
            <div class="prompt-categories" role="group" aria-label="问衣提示词分类">
              <button v-for="group in promptGroups" :key="group.id" type="button" :class="{ active: activePromptGroup === group.id }" :aria-pressed="activePromptGroup === group.id" @click="activePromptGroup = group.id">{{ group.label }}</button>
            </div>
            <div class="prompt-options" role="group" aria-label="可选问衣提示词">
              <button v-for="prompt in visiblePrompts" :key="prompt.label" type="button" :class="{ picked: draft === prompt.text }" :disabled="loading" :aria-pressed="draft === prompt.text" @click="fillPrompt(prompt.text)"><span aria-hidden="true">＋</span>{{ prompt.label }}</button>
            </div>
            <p v-if="promptNotice" class="prompt-notice" role="status">{{ promptNotice }}</p>
          </div>
          <details class="more-prompts">
            <summary>更多快捷建议与完整示例</summary>
            <button type="button" :disabled="loading" @click="fillPrompt(example)">填入完整示例（预算300元）</button>
            <EmptyErrorLoading :loading="initial.loading.value" :error="initial.error.value" @retry="load"><div class="chips"><button v-for="suggestion in suggestions" :key="suggestion" type="button" :disabled="loading" @click="fillPrompt(suggestion)">{{ suggestion }}</button></div></EmptyErrorLoading>
          </details>
        </div>

        <form class="panel composer" @submit.prevent="send()">
          <div class="purchase-needs"><label>本次预算（元）<input v-model.number="requirements.budget" type="number" min="0.01" max="999999.99" step="0.01" placeholder="不限预算"></label><label>尺码<select v-model="requirements.size"><option :value="null">未限定</option><option v-for="s in ['XS','S','M','L','XL','XXL','XXXL','均码']" :key="s" :value="s">{{ s }}</option></select></label><label>颜色<input v-model="requirements.color" maxlength="20" placeholder="填写商品颜色，如月白"></label><label>数量<input v-model.number="requirements.quantity" type="number" min="1" max="99" required></label></div><p class="caption">预算按所选衣裳 × 数量计算，不含另选配饰。场景和首次穿着情况选填；金额也可写“预算300元”。</p>
          <div v-if="latest" class="chips"><button type="button" :disabled="loading" @click="fillPrompt('颜色太亮，保留其他需求，换低饱和的')">颜色太亮，保留其他需求</button><button type="button" :disabled="loading" @click="fillPrompt('想显瘦，保留其他需求')">想显瘦一些</button></div>
          <label for="message">写下你的需求</label><textarea id="message" v-model="draft" maxlength="2000" rows="3" placeholder="例如：第一次穿，去芙蓉园，想要唐制……" required/>
          <p class="caption">在线通道只接受非敏感需求。身材与照片请在本地资料页管理，不随问衣内容发送给在线模型。</p>
          <div class="between composer-actions"><button v-if="loading" type="button" @click="stopWaiting">停止等待</button><span v-else class="muted">至多三套 · 有据可循</span><button type="submit" class="primary" :disabled="loading || !draft.trim()">{{ loading ? '正在问衣…' : stopped || error ? '重新送出问衣笺 ↗' : '送出问衣笺 ↗' }}</button></div>
          <div v-if="loading" class="request-state" aria-live="polite">
            <div class="between"><strong><span class="loading-mark" aria-hidden="true">◇</span> {{ progressTitle }}</strong><span class="elapsed">已等待 {{ elapsedSeconds }} 秒</span></div>
            <p class="request-message"><span>本轮需求</span>{{ lastAttempt }}</p>
            <p v-if="currentStep" class="stage-summary">{{ currentStep.summary }}</p><p v-else class="stage-summary">尚未收到本轮处理阶段，页面会随真实记录更新。</p>
            <small>停止等待会立即释放页面；服务端可能仍在处理，不会自动重发。</small>
          </div>
          <div v-else-if="stopped" class="request-state paused-state" role="status"><strong>已停止等待 · {{ elapsedSeconds }} 秒</strong><p>需求仍在输入框，可修改后重新发送。停止的是浏览器等待，服务端调用可能尚未结束。</p></div>
          <div v-else-if="error" class="error request-failure" role="alert"><strong>本次未收到可用回信</strong><p>{{ error }}</p><p class="failed-question">本轮需求：{{ lastAttempt }}</p><small>内容已保留在输入框。修改后点击“重新送出问衣笺”，不会自动重复请求。</small></div>
          <p v-else-if="completedSeconds !== undefined" class="reply-arrived" role="status">回信已送达，用时 {{ completedSeconds }} 秒。最新回信在下方。</p>
        </form>

        <div v-if="!session.turns.length && !loading" class="state">{{ stopped || error ? '尚未收到回信，你的需求仍然保留。' : '第一张问衣笺，等你落笔。' }}</div>
        <article v-for="turn in displayedTurns" :key="turn.id" class="panel reply">
          <div class="between reply-heading"><h2>问衣使的第 {{ turn.number }} 封回信</h2><div class="reply-heading-actions"><span class="tag">{{ turn.reply.status === 'NEED_SLOT' ? '待补充' : turn.reply.status === 'HANDOFF' ? '请人工核验' : '已整理' }}</span><button class="reply-toggle" type="button" :aria-expanded="expandedTurnIds.has(turn.id)" :aria-controls="`reply-${turn.id}`" @click="toggleReply(turn.id)">{{ expandedTurnIds.has(turn.id) ? '收起回信' : '展开回信' }}<span aria-hidden="true">{{ expandedTurnIds.has(turn.id) ? '⌃' : '⌄' }}</span></button></div></div>
          <p v-if="!expandedTurnIds.has(turn.id)" class="reply-preview">{{ turn.message || turn.reply.message }}</p>
          <div v-show="expandedTurnIds.has(turn.id)" :id="`reply-${turn.id}`" class="reply-content">
          <p v-if="turn.message" class="user-question"><span>你的问衣笺</span>{{ turn.message }}</p>
          <p class="reply-text">{{ turn.reply.message }}</p>
          <div class="need-card"><h3>本轮需求卡</h3><div class="chips"><span class="tag">场景：{{ turn.reply.slots.scene || '未限定' }}</span><span class="tag">朝代：{{ turn.reply.slots.dynasty || '不限' }}</span><span class="tag">风格：{{ turn.reply.slots.style || '不限' }}</span><span class="tag">初次穿着：{{ turn.reply.slots.firstWear === null ? '未填写' : turn.reply.slots.firstWear ? '是' : '否' }}</span><span v-if="turn.reply.slots.muted" class="tag">低饱和</span><span v-if="turn.reply.slots.slim" class="tag">显瘦偏好</span></div><p v-if="turn.reply.missingFields.length" class="notice">还需要：{{ turn.reply.missingFields.map(field => fieldNames[field] || field).join('、') }}。请在上方输入框补充。</p></div>
          <div class="funnel" aria-label="最近一次工具目录查询计数"><span>目录总数 <b>{{ turn.reply.funnel.total }}</b></span><span>场景 <b>{{ turn.reply.funnel.sceneMatched }}</b></span><span>朝代筛选 <b>{{ turn.reply.funnel.styleMatched }}</b></span><span>可售 <b>{{ turn.reply.funnel.available }}</b></span><span>推荐 <b>{{ turn.reply.funnel.returned }}</b></span></div>
          <section v-for="rec in turn.reply.recommendations.slice(0, 3)" :key="rec.product.id" class="recommendation"><ProductArt :product="rec.product"/><div><h3>{{ rec.product.name }}</h3><p>{{ rec.product.description }}</p><p>尺码建议：{{ rec.sizeAdvice.size || '信息不足，暂不推荐尺码' }} · {{ rec.sizeAdvice.confidence }}</p><details><summary>为何选它？查看依据、尺码与搭配</summary><ul><li v-for="reason in rec.reasons" :key="reason">{{ reason }}</li></ul><p>{{ rec.sizeAdvice.algorithm }}</p><p v-if="rec.sizeAdvice.missingFields.length">待补充：{{ rec.sizeAdvice.missingFields.map(field => fieldNames[field] || field).join('、') }}</p><p>搭配：<RouterLink v-for="accessory in rec.accessories" :key="accessory.id" :to="`/product/${accessory.id}`">{{ accessory.name }} </RouterLink><span v-if="!rec.accessories.length">暂无搭配建议</span></p><p class="muted">依据来自当前商品目录与本轮知识检索；尺码不构成合身保证。</p></details><RetailSelection :rec="rec" :run-id="turn.reply.workflowId" :quantity="turn.reply.requirements.quantity"/><div class="actions"><RouterLink class="button primary" :to="{ path: '/tryon', query: tryQuery(rec) }">选这套试穿</RouterLink><RouterLink class="button" :to="{path:'/guide',query:{mode:'size',...tryQuery(rec)}}">问尺码助手</RouterLink><RouterLink class="button" :to="`/product/${rec.product.id}`">看详情</RouterLink></div></div></section>
          <p v-if="turn.reply.workflowId" class="caption"><RouterLink to="/reception">查看本次选购记录及商家处理结果 →</RouterLink></p><KnowledgePanel :knowledge="turn.reply.knowledge"/>
          </div>
        </article>
      </section>
      <aside class="panel trace-aside"><details open><summary>追踪与依据 · 可折叠</summary><TracePanel :events="events" :state="traceState" :error="traceError" :paused="tracePaused" @pause="trace.stop" @reconnect="trace.connect"/></details></aside>
    </div>
  </div>
</template>

<style scoped>
.purchase-needs{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.purchase-needs input,.purchase-needs select{width:100%;margin-top:6px}@media(max-width:720px){.purchase-needs{grid-template-columns:1fr 1fr}}
.prompt-picker{margin-top:22px;padding-top:19px;border-top:1px solid var(--line)}.prompt-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.prompt-heading h3{margin:0;font-size:16px}.prompt-heading>span{font-size:11px;color:var(--muted);white-space:nowrap}.intro .prompt-hint{font-size:12px;margin:8px 0 14px;color:var(--muted)}.prompt-categories{display:flex;flex-wrap:wrap;gap:6px;padding-bottom:12px}.prompt-categories button{font-size:12px;min-height:36px;padding:6px 12px;background:transparent;border-color:transparent;color:var(--muted)}.prompt-categories button.active{background:#eee6d7;color:var(--wood);border-color:#d9c7ac}.prompt-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.prompt-options button{display:flex;align-items:center;gap:8px;min-height:41px;padding:9px 12px;text-align:left;font-size:12px;line-height:1.6;background:var(--paper,#faf7f0)}.prompt-options button>span{color:var(--gold);font-size:14px}.prompt-options button.picked{border-color:var(--wood);background:#f0e7d9}.intro .prompt-notice{margin:11px 0 0;font-size:11px;color:var(--green)}.more-prompts{margin-top:16px;font-size:12px}.more-prompts>summary{cursor:pointer;color:var(--wood);padding:6px 0}.more-prompts>button{margin-top:12px;font-size:12px}.more-prompts .chips button{font-size:12px;text-align:left;line-height:1.6}.prompt-categories button:focus-visible,.prompt-options button:focus-visible{outline:2px solid var(--wood);outline-offset:3px}@media(max-width:420px){.prompt-heading{align-items:flex-start}.prompt-categories{gap:3px}.prompt-categories button{padding:6px 9px;font-size:11px}.prompt-options button{padding:8px;font-size:11px;gap:5px}}
.reply-heading{gap:10px}.reply-heading h2{margin:0;font-size:20px}.reply-heading-actions{display:flex;gap:9px;align-items:center;flex-wrap:wrap}.reply-toggle{min-height:38px;padding:7px 10px;font-size:12px;background:transparent}.reply-toggle span{color:var(--wood);font-size:18px}.reply-preview{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden;margin:12px 0 0;font-size:12px;line-height:1.8;color:var(--muted)}.reply-content{padding-top:6px}
.online-detail{font-size:12px;color:var(--muted)}.online-detail button{font-size:11px;padding:5px 9px;min-height:32px;margin:8px 0 0 8px}.composer label{margin-top:0}.composer-actions{gap:12px}.composer-actions>.muted{font-size:12px}.request-state{padding:15px 16px;margin-top:18px;background:#f1eee4;border:1px solid var(--line);border-left:3px solid var(--gold);border-radius:10px}.request-state .between{gap:8px}.request-state strong{font-size:13px;color:var(--wood)}.elapsed{font-size:11px;color:var(--muted);white-space:nowrap}.request-message,.user-question{font-size:12px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere}.request-message{margin:12px 0 8px;max-height:100px;overflow:auto}.request-message>span,.user-question>span{display:block;font-size:10px;color:var(--wood);letter-spacing:.06em;margin-bottom:4px}.stage-summary{font-size:11px;line-height:1.8;color:var(--muted);margin:8px 0}.request-state small{font-size:10px}.paused-state p{font-size:12px;margin:9px 0 0}.request-failure p{font-size:12px;line-height:1.8;margin:9px 0}.request-failure small{font-size:11px;color:inherit}.failed-question{max-height:80px;overflow:auto;white-space:pre-wrap}.reply-arrived{font-size:12px;color:var(--green);margin:17px 0 0}.user-question{background:#f4f0e6;padding:12px 14px;border-radius:8px;margin:16px 0 20px}.composer .chips:first-child{margin-top:0}@media(max-width:600px){.request-state{padding:13px}.composer-actions>.muted{font-size:11px}.composer-actions .primary{padding-left:13px;padding-right:13px}}
</style>
