<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, resourceUrl, errorText } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import type { Product, Portrait, TryOn, TryOnStatus } from '../types'
import { money } from '../types'
import ProductArt from '../components/ProductArt.vue'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
import SaveTryOnImage from '../components/SaveTryOnImage.vue'
import { tryonProgress, waitingTime } from '../data/tryonProgress'

const store = useSession(), session = store.current, route = useRoute(), router = useRouter()
const sampleOriginal = '/images/tryon-example/original.png', sampleResult = '/images/tryon-example/result.png'
const sampleResultFailed = ref(false)
const product = ref<Product>(), skuId = ref(''), authorized = ref(false), aiAuthorized = ref(false)
const file = ref<File>(), input = ref<HTMLInputElement>(), localPreview = ref('')
const portraitId = ref(''), task = ref<TryOn>(), pollError = ref(''), service = ref<TryOnStatus>(), serviceError = ref('')
const now = ref(Date.now()), portraitImageFailed = ref(false)
const operation = ref(''), waitStarted = ref(0)
const waited = computed(() => waitingTime(Math.max(0, Math.floor((now.value - waitStarted.value) / 1000))))
const selected = computed(() => product.value?.skus.find(s => s.id === skuId.value))
const portraits = computed(() => session.portraits.filter(p => new Date(p.expiresAt).getTime() > now.value))
const portrait = computed(() => portraits.value.find(p => p.id === portraitId.value))
const portraitPreview = computed(() => portrait.value ? resourceUrl(`/api/tryon/portrait/${encodeURIComponent(portrait.value.id)}/image`, session.id) : '')
const init = useAsync(), action = useAsync()
let timer: ReturnType<typeof setTimeout> | undefined, clockTimer: ReturnType<typeof setInterval> | undefined, disposed = false, cancelPending = false
const running = computed(() => !!task.value && ['QUEUED', 'RUNNING'].includes(task.value.status))
const taskLabel = computed(() => ({ QUEUED: '正在等待生成', RUNNING: '正在生成试穿效果', FAILED: '本次生成未完成', CANCELLED: '本次生成已取消', DONE: '试穿效果已生成', DEGRADED: '试穿效果已生成' }[task.value?.status || ''] || '等待处理'))
const progress = computed(() => tryonProgress(task.value?.stage))
const anotherTask = computed(() => session.tasks.find(item => item.id !== task.value?.id && ['QUEUED', 'RUNNING'].includes(item.status) && new Date(item.expiresAt).getTime() > now.value))
const serviceNotice = computed(() => serviceError.value || service.value?.message?.trim() || '火山引擎图片换装 V2 尚未配置完成，暂时不能生成新结果。')

async function checkService() {
  serviceError.value = ''
  try { service.value = await request<TryOnStatus>('GET', '/tryon/status') }
  catch { service.value = undefined; serviceError.value = '暂时无法连接本站试穿服务，请稍后重新连接。' }
}
const load = () => init.run(async () => {
  const id = typeof route.query.productId === 'string' ? route.query.productId : 'p14'
  product.value = await request<Product>('GET', `/products/${encodeURIComponent(id)}`, session.id)
  const wanted = typeof route.query.skuId === 'string' ? route.query.skuId : ''
  skuId.value = product.value.skus.find(s => s.id === wanted && s.stock > 0)?.id || product.value.skus.find(s => s.stock > 0)?.id || ''
  const existing = [...session.tasks].reverse().find(item => item.productId === id && item.skuId === skuId.value && ['QUEUED', 'RUNNING'].includes(item.status) && new Date(item.expiresAt).getTime() > Date.now())
  if (existing && !disposed) { task.value = existing; waitStarted.value = Date.now(); void poll() }
})
function clearFile() {
  if (localPreview.value) URL.revokeObjectURL(localPreview.value)
  localPreview.value = ''; file.value = undefined
  if (input.value) input.value.value = ''
}
function choose(event: Event) {
  if (localPreview.value) URL.revokeObjectURL(localPreview.value)
  file.value = (event.target as HTMLInputElement).files?.[0]
  localPreview.value = file.value && ['image/png', 'image/jpeg'].includes(file.value.type) ? URL.createObjectURL(file.value) : ''
  action.error.value = ''
}
const upload = () => action.run(async () => {
  if (!file.value || !authorized.value || !aiAuthorized.value) throw new Error('请先选择照片，并确认两项授权')
  if (!service.value?.ready) throw new Error(serviceNotice.value)
  if (!['image/png', 'image/jpeg'].includes(file.value.type) || file.value.size > 5 * 1024 * 1024) throw new Error('请选择不超过 5MB 的 PNG 或 JPEG 图片')
  const form = new FormData(); form.append('file', file.value)
  operation.value = 'upload'
  try {
    const uploaded = await request<Portrait>('POST', '/tryon/portrait', session.id, form, { authorized: true, aiAuthorized: true })
    if (disposed) return
    session.portraits.push(uploaded); portraitId.value = uploaded.id; portraitImageFailed.value = false
    clearFile(); authorized.value = false; aiAuthorized.value = false
  } finally { operation.value = '' }
})
const removePortrait = () => action.run(async () => {
  if (!portrait.value) return
  const id = portrait.value.id
  await request('DELETE', `/tryon/portrait/${encodeURIComponent(id)}`, session.id)
  session.portraits = session.portraits.filter(p => p.id !== id)
  session.tasks = session.tasks.filter(t => !t.originalUrl?.includes('/portrait/' + encodeURIComponent(id) + '/image')); portraitId.value = ''; task.value = undefined; clearTimeout(timer)
  store.notice = '本地人像及其关联试穿结果已删除。'
})
async function poll() {
  if (!task.value || disposed || cancelPending) return
  clearTimeout(timer); pollError.value = ''
  try {
    const latest = await request<TryOn>('GET', `/tryon/tasks/${task.value.id}`, session.id)
    if (disposed || cancelPending) return
    task.value = latest; store.remember(latest); now.value = Date.now()
    if (['DONE', 'DEGRADED'].includes(latest.status)) { await router.push({ path: '/result/' + latest.id, query: { sessionId: session.id } }); return }
    if (['RUNNING', 'QUEUED'].includes(latest.status)) timer = setTimeout(poll, 2000)
  } catch (e) { if (!disposed) pollError.value = errorText(e) }
}
const generate = () => action.run(async () => {
  if (!service.value?.ready) throw new Error(serviceNotice.value)
  if (!product.value || !portrait.value || !selected.value) throw new Error('请先上传已授权的人像，并确认衣裳规格')
  if (running.value || anotherTask.value) throw new Error('已有试穿任务正在生成，请先查看该任务。')
  operation.value = 'generate'; waitStarted.value = Date.now(); now.value = Date.now()
  try {
    const created = await request<TryOn>('POST', '/tryon/generate', undefined, { sessionId: session.id, portraitId: portrait.value.id, productId: product.value.id, skuId: skuId.value })
    store.remember(created)
    if (disposed) return
    task.value = created; void poll()
  } finally { operation.value = '' }
})
const cancel = () => action.run(async () => {
  if (!task.value) return
  cancelPending = true; clearTimeout(timer)
  try {
    await request('POST', `/tryon/tasks/${task.value.id}/cancel`, session.id)
    task.value = await request<TryOn>('GET', `/tryon/tasks/${task.value.id}`, session.id); store.remember(task.value)
  } finally { cancelPending = false }
})
onMounted(() => {
  portraitId.value = portraits.value.at(-1)?.id || ''
  void load(); void checkService()
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); clearInterval(clockTimer); clearFile() })
</script>

<template>
  <div class="page narrow tryon-page">
    <div class="center page-heading"><p class="eyebrow">菱花镜 · 照见衣之美</p><h1>把喜欢的衣裳，穿进照片里</h1><p class="heading-note">上传一张照片，选择一套衣裳，看看属于你的汉服模样。</p></div>
    <RouterLink class="sample-entry" to="/result/example" aria-label="查看月白袄马面裙 AI 换装示例与原图对比">
      <div class="sample-pictures" aria-hidden="true"><img :src="sampleOriginal" alt=""><span>→</span><span v-if="sampleResultFailed" class="sample-pending">待生成</span><img v-else :src="sampleResult" alt="" @error="sampleResultFailed = true"></div>
      <div><span class="eyebrow">先看一眼 · 无需上传</span><h2>一袭月白，换个模样</h2><p>{{ sampleResultFailed ? 'AI 示例人像已准备好，月白袄马面裙的换装图尚未生成。' : '看看明 · 月白袄马面裙的换装前后。人物与效果均为 AI 示例。' }}</p></div>
      <span class="sample-link">{{ sampleResultFailed ? '查看示例状态' : '查看示例对比' }} <span aria-hidden="true">↗</span></span>
    </RouterLink>
    <div v-if="serviceError || (service && !service.ready)" class="notice service-notice" role="status"><p>{{ serviceNotice }}</p><button @click="checkService">{{ serviceError ? '重新连接' : '刷新服务状态' }}</button></div>
    <div class="tryon-grid">
      <section class="panel upload-panel">
        <p class="eyebrow">第一步 · 留下你的模样</p><h2>准备一张正面照片</h2>
        <p class="muted">单人、光线清晰、尽量完整露出身体，双臂自然放松。复杂遮挡可能影响换装效果。</p>
        <div v-if="localPreview || (portraitPreview && !portraitImageFailed)" class="portrait-preview"><img :src="localPreview || portraitPreview" :alt="localPreview ? '待上传的照片，仅在当前浏览器预览' : '已授权上传的人像'" @error="portraitImageFailed = true"><span>{{ localPreview ? '待上传 · 仅本地预览' : '已授权人像' }}</span></div>
        <div v-else class="portrait-placeholder" aria-hidden="true"><span>入镜</span><p>一张照片，开启这次相逢</p></div>
        <label for="portrait-file">选择人像照片</label><input id="portrait-file" ref="input" type="file" accept="image/png,image/jpeg" :disabled="running" @change="choose">
        <small class="upload-hint">PNG / JPEG，最大 5MB。边长 64–4096 像素，总像素不超过 1600 万。</small>
        <div class="consent-box">
          <label class="check"><input v-model="authorized" type="checkbox" :disabled="running">我确认照片属于本人，或已获得照片中人物的使用授权。</label>
          <label class="check"><input v-model="aiAuthorized" type="checkbox" :disabled="running">我同意将人像与所选服饰图片发送至火山引擎，通过「图片换装 V2」生成试穿效果。</label>
          <p>本站在内存中临时保存人像及关联结果，最长 30 分钟，可随时删除。删除会清除本站副本；火山引擎的处理与保留规则以其服务条款及隐私政策为准。</p>
        </div>
        <button class="primary full" :disabled="!authorized || !aiAuthorized || !file || action.loading.value || running || !service?.ready" @click="upload">{{ operation === 'upload' ? '正在上传并整理照片…' : '同意授权并上传照片' }}</button>
        <div v-if="portraits.length" class="saved-portrait"><label for="portrait-choice">本次使用的人像</label><select id="portrait-choice" v-model="portraitId" :disabled="running" @change="portraitImageFailed = false"><option v-for="(p,index) in portraits" :key="p.id" :value="p.id">人像 {{ index + 1 }} · {{ new Date(p.expiresAt).toLocaleTimeString('zh-CN') }} 到期</option></select><button class="text-button" :disabled="action.loading.value || running" @click="removePortrait">删除所选人像及关联结果</button></div>
        <p v-else-if="portraitId" class="notice">之前的人像已到期，请重新上传。</p>
      </section>
      <section class="panel garment-panel">
        <p class="eyebrow">第二步 · 选好这一袭衣裳</p><h2>衣裳已备，静候入镜</h2>
        <EmptyErrorLoading :loading="init.loading.value" :error="init.error.value" @retry="load"><template v-if="product"><ProductArt :product="product"/><h3>{{ product.name }}</h3><label for="try-sku">颜色与尺码</label><select id="try-sku" v-model="skuId" :disabled="running"><option v-for="sku in product.skus" :key="sku.id" :value="sku.id" :disabled="sku.stock < 1">{{ sku.color }} / {{ sku.size }} · {{ money(sku.price) }} · {{ sku.stock > 0 ? `余 ${sku.stock}` : '缺货' }}</option></select><p class="caption">图像展示穿搭风格，不测量身材，也不保证尺码合身。</p><RouterLink :to="`/product/${product.id}`">查看尺码与衣裳详情 →</RouterLink></template></EmptyErrorLoading>
        <div class="generation-box"><p v-if="service?.ready" class="service-ready"><span aria-hidden="true"></span>火山引擎 · 图片换装 V2 已配置</p><p v-else-if="!serviceError && !service" class="caption">正在读取试穿服务状态…</p><button class="primary full" :disabled="action.loading.value || running || !!anotherTask || !portrait || !selected?.stock || !service?.ready" @click="generate">{{ operation === 'generate' ? '正在提交任务…' : running ? '正在生成，请稍候…' : task?.status === 'FAILED' ? '重新发起生成 →' : '生成我的试穿效果 →' }}</button><small>点击后通过火山引擎 · 图片换装 V2 发起一次生成，可能产生接口调用费用。失败后不会自动重复生成。</small></div>
        <p v-if="anotherTask && !running" class="notice">还有一次试穿正在进行。<RouterLink :to="{path:'/result/' + anotherTask.id,query:{sessionId:session.id}}">查看本次生成进度 →</RouterLink></p>
        <section v-if="task" class="task-progress" aria-live="polite">
          <div class="between"><h3>{{ taskLabel }}</h3><span v-if="running" class="loading-mark" aria-hidden="true">❖</span></div>
          <template v-if="running">
            <p><strong>{{ progress.title }}</strong><br>{{ progress.detail }}</p>
            <p class="caption">本页已等待 {{ waited }} · 只跟踪同一次任务</p>
            <p v-if="now - waitStarted > 90000" class="caption">本次等待较长，远端排队与生成耗时可能变化。可以先浏览其他页面，稍后从「个人中心」查看；无需重复提交。</p>
            <RouterLink class="button" :to="{path:'/result/' + task.id,query:{sessionId:session.id}}">打开本次试穿进度 →</RouterLink>
            <button :disabled="action.loading.value" @click="cancel">取消本次生成</button>
          </template>
          <p v-if="task.status === 'FAILED'" class="error">{{ task.error || '图像服务未能完成本次换装，请稍后再试。' }}<br><small>重新生成会发起新的请求，可能再次产生调用费用。</small></p>
          <p v-if="task.status === 'CANCELLED'" class="caption">已停止展示本次结果。若请求已发出，图像服务仍可能完成处理或计费。</p>
          <RouterLink v-if="['DONE','DEGRADED'].includes(task.status)" class="button primary" :to="{path:'/result/' + task.id,query:{sessionId:session.id}}">查看效果，选择加入衣囊或购买 →</RouterLink>
          <SaveTryOnImage v-if="task.status==='DONE' && task.resultUrl && new Date(task.expiresAt).getTime()>now" :key="task.id" :url="resourceUrl(task.resultUrl,session.id)" :task-id="task.id"/>
          <p v-if="pollError" class="error" role="alert">{{ pollError }}<button @click="poll">只刷新任务状态</button></p>
        </section>
        <p v-if="action.error.value" class="error" role="alert">{{ action.error.value }}</p>
        <RouterLink class="button full" to="/culture">去衣冠志，再挑一套</RouterLink>
      </section>
    </div>
    <p class="footnote">AI 换装预览可能改变人物或服饰细节，仅供搭配参考；实际面料、版型与上身效果请以商品和试穿为准。</p>
  </div>
</template>

<style scoped>
.sample-pending{display:flex;align-items:center;justify-content:center;width:71px;height:99px;border:1px dashed var(--gold);border-radius:8px;font-size:12px;flex-shrink:1}
.heading-note{color:var(--muted);max-width:560px;margin:0 auto}.sample-entry{display:grid;grid-template-columns:170px minmax(0,1fr) auto;align-items:center;gap:25px;padding:23px 28px;border:1px solid #ddd0b9;border-radius:18px;background:linear-gradient(120deg,#f4ede1,#fbf9f3);margin-bottom:30px;color:var(--ink)}.sample-entry:hover{border-color:var(--gold);color:var(--ink)}.sample-entry h2{font-size:25px;margin:5px 0 7px}.sample-entry p{font-size:12px;color:var(--muted);margin:0}.sample-entry .eyebrow{font-size:10px}.sample-pictures{display:flex;align-items:center;gap:8px}.sample-pictures img{width:71px;height:99px;object-fit:cover;object-position:top;border-radius:8px;border:1px solid #ded4c5}.sample-pictures>span{color:var(--wood);font-size:14px}.sample-link{font-size:13px;color:var(--red);white-space:nowrap}.service-notice{display:flex;align-items:center;gap:20px;justify-content:space-between;margin-bottom:25px}.service-notice p{margin:0}.service-notice button{flex-shrink:0;font-size:12px}.upload-panel>.muted{font-size:13px}.portrait-placeholder{height:220px;display:flex;flex-direction:column;align-items:center;justify-content:center;border:1px dashed #cabb9e;background:radial-gradient(ellipse at center,#fbf8f1,#eee9dd);border-radius:60px 60px 12px 12px;margin:24px auto;color:var(--wood)}.portrait-placeholder>span{font-family:SimSun,serif;font-size:34px;letter-spacing:.25em;writing-mode:vertical-rl}.portrait-placeholder p{font-size:11px;letter-spacing:.15em;margin:16px 0 0}.portrait-preview{background:#eeebe3;border-radius:16px;margin:22px auto;position:relative;overflow:hidden;max-width:250px}.portrait-preview img{width:100%;height:275px;object-fit:contain}.portrait-preview>span{display:block;padding:8px;background:#f4ede1;font-size:11px;text-align:center;color:var(--wood)}.upload-hint{display:block;margin-top:8px}.consent-box{padding:15px;background:#f6f3eb;border-radius:12px;margin-top:20px}.consent-box .check{align-items:flex-start;margin:0 0 13px;font-size:12px;line-height:1.8}.consent-box input{margin-top:2px}.consent-box p{font-size:11px;color:var(--muted);margin:0;padding-top:8px;border-top:1px solid var(--line)}.saved-portrait{margin-top:22px;padding-top:14px;border-top:1px solid var(--line)}.text-button{background:none;border:0;padding:10px 0;color:var(--red);font-size:12px;justify-content:flex-start}.garment-panel>.product-art{max-width:230px;margin:20px auto}.generation-box{margin-top:26px;border-top:1px solid var(--line);padding-top:12px}.generation-box small{display:block;margin-top:12px;font-size:11px}.service-ready{font-size:12px;color:var(--green);display:flex;align-items:center;gap:8px;margin:4px 0 0}.service-ready span{width:6px;height:6px;border-radius:50%;background:var(--green)}.task-progress{margin-top:24px;padding:18px;background:#f2f1e9;border-radius:12px}.task-progress h3{font-size:19px;margin:0}.task-progress p{font-size:12px;margin:12px 0}.task-progress .error small{color:inherit}.task-progress button{font-size:12px}.footnote{font-size:12px;color:var(--muted);text-align:center;margin:26px auto 0;max-width:700px;line-height:1.9}@media(max-width:800px){.sample-entry{grid-template-columns:140px minmax(0,1fr);gap:16px;padding:20px}.sample-pictures img{width:57px;height:83px}.sample-link{grid-column:2}.sample-entry h2{font-size:23px}}@media(max-width:600px){.sample-entry{grid-template-columns:100px minmax(0,1fr);gap:12px;padding:16px}.sample-pictures{gap:4px}.sample-pictures img{width:43px;height:69px}.sample-pictures>span{font-size:10px}.sample-entry h2{font-size:21px}.sample-entry p{font-size:11px}.sample-link{font-size:12px}.service-notice{flex-direction:column;align-items:flex-start;gap:10px}.portrait-placeholder{height:185px}.tryon-grid{gap:22px}}
</style>

