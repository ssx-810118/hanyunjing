<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { request, resourceUrl, errorText } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import type { TryOn, Product } from '../types'
import { money } from '../types'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
import TryOnCompare from '../components/TryOnCompare.vue'
import SaveTryOnImage from '../components/SaveTryOnImage.vue'
import TryOnOrbit from '../components/TryOnOrbit.vue'
import ProductPurchaseActions from '../components/ProductPurchaseActions.vue'
import { tryonProgress, waitingTime } from '../data/tryonProgress'

const store = useSession(), session = store.current, route = useRoute()
const resultSessionId = computed(() => typeof route.query.sessionId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(route.query.sessionId) ? route.query.sessionId : session.id)
const task = ref<TryOn>(), product = ref<Product>(), loading = ref(false), loadError = ref(''), refreshError = ref('')
const now = ref(Date.now()), imageVersion = ref(0), action = useAsync()
const waitStarted = ref(Date.now()), progress = computed(() => tryonProgress(task.value?.stage))
const waited = computed(() => waitingTime(Math.max(0, Math.floor((now.value - waitStarted.value) / 1000))))
const isSample = computed(() => route.params.taskId === 'example')
const expired = computed(() => !isSample.value && !!task.value && new Date(task.value.expiresAt).getTime() <= now.value)
const running = computed(() => !!task.value && ['QUEUED', 'RUNNING'].includes(task.value.status) && !expired.value)
const completed = computed(() => !!task.value && ['DONE', 'DEGRADED'].includes(task.value.status) && !!task.value.resultUrl && !expired.value)
const productId = computed(() => isSample.value ? 'p14' : task.value?.productId || '')
const sku = computed(() => isSample.value ? undefined : product.value?.skus.find(s => s.id === task.value?.skuId))
const productName = computed(() => product.value?.name || (isSample.value ? '明 · 月白袄马面裙' : '本次衣裳'))
const originalUrl = computed(() => isSample.value ? '/images/tryon-example/original.png' : task.value?.originalUrl && !expired.value ? resourceUrl(task.value.originalUrl, resultSessionId.value) : null)
const resultUrl = computed(() => isSample.value ? '/images/tryon-example/result.png' : completed.value && task.value?.resultUrl ? resourceUrl(task.value.resultUrl, resultSessionId.value) : null)
const tryLink = computed(() => ({ path: '/tryon', query: { productId: productId.value, ...(sku.value ? { skuId: sku.value.id } : {}) } }))
let disposed = false, generation = 0, pollTimer: ReturnType<typeof setTimeout> | undefined
const clockTimer = setInterval(() => { now.value = Date.now() }, 1000)

async function load() {
  const version = ++generation
  waitStarted.value = Date.now()
  clearTimeout(pollTimer)
  task.value = undefined; product.value = undefined; loadError.value = ''; refreshError.value = ''; imageVersion.value++
  if (isSample.value) {
    loading.value = false
    try {
      const selected = await request<Product>('GET', '/products/p14', session.id)
      if (!disposed && version === generation) product.value = selected
    } catch { /* The example stays available even when the catalogue cannot be reached. */ }
    return
  }
  loading.value = true
  try {
    const latest = await request<TryOn>('GET', `/tryon/tasks/${encodeURIComponent(String(route.params.taskId))}`, resultSessionId.value)
    if (disposed || version !== generation) return
    task.value = latest; store.remember(latest)
    try {
      const selected = await request<Product>('GET', `/products/${encodeURIComponent(latest.productId)}`, resultSessionId.value)
      if (!disposed && version === generation) product.value = selected
    } catch { /* A temporary catalogue error should not hide a completed image. */ }
    if (!disposed && version === generation && running.value) pollTimer = setTimeout(refresh, 2000)
  } catch (e) {
    if (!disposed && version === generation) loadError.value = errorText(e)
  } finally { if (version === generation) loading.value = false }
}
async function refresh() {
  if (!task.value || disposed || expired.value) return
  clearTimeout(pollTimer); refreshError.value = ''
  const version = generation
  try {
    const latest = await request<TryOn>('GET', `/tryon/tasks/${encodeURIComponent(task.value.id)}`, resultSessionId.value)
    if (disposed || version !== generation) return
    task.value = latest; store.remember(latest)
    if (running.value) pollTimer = setTimeout(refresh, 2000)
  } catch (e) { if (!disposed && version === generation) refreshError.value = errorText(e) }
}
const cancel = () => action.run(async () => {
  if (!task.value) return
  clearTimeout(pollTimer)
  await request('POST', `/tryon/tasks/${encodeURIComponent(task.value.id)}/cancel`, resultSessionId.value)
  await refresh()
})
watch(() => [route.params.taskId, route.query.sessionId], () => { void load() }, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; clearTimeout(pollTimer); clearInterval(clockTimer) })
</script>

<template>
  <div class="page result-page">
    <div class="center page-heading"><p class="eyebrow">镜中衣 · {{ isSample ? '先看换装示例' : '留下这一袭风雅' }}</p><h1>{{ isSample ? '一袭月白，衣冠成景' : '喜欢的衣裳，有了你的模样' }}</h1><p class="result-intro">{{ isSample ? '从日常衣着到月白袄马面裙，切换视图，看看换装前后。' : '切换原图与效果，慢慢看这套衣裳与你的搭配。' }}</p></div>
    <EmptyErrorLoading :loading="loading" :error="loadError" @retry="load">
      <div v-if="isSample || task" class="tryon-result-layout">
        <div class="result-visual">
          <template v-if="isSample || completed">
            <TryOnCompare :key="imageVersion" :original="originalUrl" :result="resultUrl" :sample="isSample"/>
            <SaveTryOnImage v-if="!isSample && completed && task && resultUrl" :key="task.id" :url="resultUrl" :task-id="task.id"/>
          </template>
          <section v-else class="result-state panel" aria-live="polite">
            <template v-if="expired"><span class="state-seal">暂别</span><h2>这次留影已到期</h2><p>人像与关联结果已不再提供。你可以重新上传照片，开始新的试穿。</p><RouterLink class="button primary" :to="tryLink">重新准备照片</RouterLink></template>
            <template v-else-if="running"><span class="loading-mark state-seal">镜</span><h2>{{ progress.title }}</h2><p>{{ progress.detail }}</p><p class="caption">本页已等待 {{ waited }} · 只查询本次任务，不重复生成</p><p>完成后，这里将展示对比效果，以及「加入衣囊 / 去购买」。也可稍后从「个人中心」找回本次任务。</p><button :disabled="action.loading.value" @click="cancel">取消本次生成</button></template>
            <template v-else-if="task?.status === 'FAILED'"><span class="state-seal">待</span><h2>这次没有生成成功</h2><p>{{ task.error || '图像服务暂时未能完成换装。请稍后再试。' }}</p><RouterLink class="button primary" :to="tryLink">返回试衣镜，重新生成</RouterLink><small>重新生成会发起新的请求，可能再次产生调用费用。</small></template>
            <template v-else-if="task?.status === 'CANCELLED'"><span class="state-seal">歇</span><h2>本次生成已取消</h2><p>本次结果已不再提供。若请求已发送，图像服务仍可能完成处理或计费。</p><RouterLink class="button" :to="tryLink">回到试衣镜</RouterLink></template>
            <template v-else><h2>结果暂未就绪</h2><p>你可以重新查询本次任务。</p><button @click="refresh">刷新任务状态</button></template>
          </section>
          <p v-if="refreshError" class="error" role="alert">{{ refreshError }}<button @click="refresh">只刷新任务状态</button></p>
          <TryOnOrbit v-if="!isSample && completed && task && !task.demo" :key="task.id" :task-id="task.id" :session-id="resultSessionId"/>
          <div class="result-disclosure"><span aria-hidden="true">◇</span><p><strong>{{ task?.demo ? '旧版示意结果' : isSample ? 'AI 示例 · 非真实用户照片' : 'AI 换装预览' }}</strong><br>{{ task?.demo ? '这是一份早期服装轮廓示意，不能代表真实人体换装。请重新生成照片式效果。' : '画面仅供风格与搭配参考，人物、纹样或衣服细节可能出现偏差；不代表真实尺码、面料或合身程度。' }}</p></div>
        </div>
        <aside class="panel result-details">
          <p class="eyebrow">这一袭{{ product ? ` · ${product.dynasty} · ${product.form}` : '' }}</p><h2>{{ productName }}</h2>
          <p v-if="sku" class="result-spec">{{ sku.color }} / {{ sku.size }}<span v-if="isSample">示例服饰</span></p>
          <p v-if="product?.skus.length" class="result-price">{{ sku ? money(sku.price) : money(Math.min(...product.skus.map(item => item.price))) + ' 起' }}</p>
          <p v-if="isSample" class="result-story">温润月白，映衬裙褶的起落。用你自己的正面照片，也可以试试这套搭配。</p>
          <p v-else class="result-story">看整体配色，也看领口、肩线与裙摆。喜欢这一套，再去了解它的尺码与细节。</p>
          <div v-if="isSample || completed" class="purchase-next"><h3>{{ isSample ? '喜欢这套衣裳？' : '试穿满意，把它带回家' }}</h3><p>确认尺码后，可加入衣囊或前往购买。购买完成后，在「我的 · 购物订单」查看。</p></div>
          <ProductPurchaseActions v-if="product" :product="product" :preferred-sku-id="isSample ? undefined : task?.skuId" :disabled="!isSample && !completed" allow-buy/>
          <p v-else-if="isSample || completed" class="caption">商品信息暂未加载，可前往衣裳详情确认尺码后购买。</p>
          <RouterLink class="button full" :to="tryLink">{{ isSample ? '用我的照片试这套 →' : '返回试衣镜，再试一套 →' }}</RouterLink>
          <RouterLink class="result-product-link" :to="`/product/${productId}`">衣裳详情与尺码表 →</RouterLink>
          <p v-if="action.error.value" class="error" role="alert">{{ action.error.value }}</p>
          <div class="result-notes"><h3>{{ isSample ? '试穿很简单' : '留住这次相逢' }}</h3><ol v-if="isSample"><li>上传本人或已获授权的人像</li><li>确认服饰与 AI 处理授权</li><li>生成效果，随时切换原图对比</li></ol><template v-else><p v-if="task">{{ expired ? '本次人像与结果已到期。' : `人像及结果保留至 ${new Date(task.expiresAt).toLocaleString('zh-CN')}。` }}</p><p>删除人像会同时删除本站保存的关联试穿结果。</p><RouterLink to="/profile">管理 / 删除我的人像 →</RouterLink></template></div>
          <RouterLink class="culture-link" to="/culture">去衣冠志，看看其他衣裳 →</RouterLink>
        </aside>
      </div>
    </EmptyErrorLoading>
    <div v-if="loadError" class="recovery-actions"><p class="caption">如果照片或任务已经到期、被删除，或刷新后切换了会话，请重新上传照片。</p><RouterLink class="button" to="/tryon">返回试衣镜</RouterLink><RouterLink class="button" to="/result/example">先看换装示例</RouterLink></div>
  </div>
</template>

<style scoped>
.result-price{font-family:Georgia,"Songti SC",serif;font-size:25px;color:var(--red);margin:12px 0}.purchase-next{border-top:1px solid var(--line);padding-top:18px;margin-top:22px}.purchase-next h3{font-size:20px;margin:0 0 9px}.purchase-next p{font-size:12px;color:var(--muted);line-height:1.9;margin:0}.result-product-link{display:block;text-align:center;font-size:12px;color:var(--wood);margin-top:14px}
.result-page{max-width:1380px}.result-intro{color:var(--muted);font-size:14px}.tryon-result-layout{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(270px,.8fr);gap:30px;align-items:start}.result-details{padding:28px;position:sticky;top:24px}.result-details h2{font-size:27px;line-height:1.55;margin:12px 0 15px}.result-spec{color:var(--wood);font-size:13px;display:flex;gap:15px;align-items:center}.result-spec span{font-size:10px;border:1px solid #d9c8ac;border-radius:5px;padding:2px 7px}.result-story{font-size:13px;color:var(--muted);line-height:2;margin:23px 0}.shop-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:13px}.shop-actions>*,.result-details>.full{font-size:13px;padding-left:10px;padding-right:10px}.result-notes{border-top:1px solid var(--line);padding-top:22px;margin-top:28px}.result-notes h3{font-size:20px}.result-notes p,.result-notes li,.result-notes a{font-size:12px;color:var(--muted)}.result-notes li{padding-left:3px;margin:10px 0}.result-notes a{color:var(--red)}.culture-link{display:block;margin-top:25px;padding-top:18px;border-top:1px solid var(--line);font-size:12px;color:var(--wood)}.result-disclosure{display:flex;gap:13px;padding:17px 4px}.result-disclosure>span{font-size:23px;color:var(--gold);line-height:1.4}.result-disclosure p{font-size:11px;color:var(--muted);line-height:1.9;margin:0}.result-disclosure strong{font-weight:500;color:var(--wood);font-size:12px}.result-state{min-height:460px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:40px}.result-state h2{margin-top:24px}.result-state p{max-width:420px;font-size:13px;color:var(--muted)}.state-seal{font-family:SimSun,serif;font-size:30px;color:var(--wood);border:1px solid var(--gold);padding:18px 12px;border-radius:50% 50% 8px 8px}.result-state small{margin-top:16px}.recovery-actions{display:flex;flex-wrap:wrap;justify-content:center;gap:12px}.recovery-actions p{flex-basis:100%;text-align:center}@media(max-width:1000px){.tryon-result-layout{grid-template-columns:minmax(0,1fr);gap:20px}.result-details{position:static}.result-details h2{font-size:25px}.result-details>.full{max-width:440px}.shop-actions{max-width:440px}.result-notes{margin-top:22px}.result-story{margin:16px 0}.result-page{max-width:820px}}@media(max-width:600px){.result-details{padding:22px}.result-state{padding:25px;min-height:360px}.result-intro{font-size:12px}.result-disclosure{padding:15px 2px}}
</style>
