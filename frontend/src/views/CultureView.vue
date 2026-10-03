<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { errorText, request } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import type { Product, KnowledgeResult } from '../types'
import ProductCard from '../components/ProductCard.vue'
import KnowledgePanel from '../components/KnowledgePanel.vue'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
import DynastyReferences from '../components/DynastyReferences.vue'

const store = useSession()
const dynastyOrder = ['汉', '唐', '宋', '元', '明']
const representativeIds: Record<string, string> = { 汉: 'p11', 唐: 'p1', 宋: 'p3', 元: 'p13', 明: 'p8' }
const featuredIds: Record<string, string[]> = { 汉: ['p11','p12','p15','p16'], 唐: ['p1','p2','p7','p10'], 宋: ['p3','p18','p19','p20'], 元: ['p13','p21','p22','p23'], 明: ['p8','p24','p25','p26'] }
const dynasties = ref<string[]>([]), dynasty = ref(''), query = ref('')
const knowledge = ref<KnowledgeResult>(), products = ref<Product[]>([])
const productReferences = ref<Record<string, KnowledgeResult>>({})
const init = useAsync(), searching = ref(false), searchError = ref('')
const failedImages = ref<string[]>([]), imageRetry = ref(0)
// Only products in the public catalogue can supply the banner or its link.
const representative = computed(() => {
  const available = products.value.filter(p => p.images[0] && !failedImages.value.includes(p.images[0]))
  return available.find(p => p.id === representativeIds[dynasty.value]) || available[0]
})
const representativeId = computed(() => representative.value?.id)
const representativeName = computed(() => representative.value?.name)
const representativeImage = computed(() => {
  const source = representative.value?.images[0]
  if (!source || !imageRetry.value) return source
  return source + (source.includes('?') ? '&' : '?') + 'retry=' + imageRetry.value
})
function failImage(event: Event) {
  const source = (event.target as HTMLImageElement).dataset.source
  if (source && !failedImages.value.includes(source)) failedImages.value.push(source)
}
function retryImage() {
  failedImages.value = []
  imageRetry.value = Date.now()
}
const imageAlt = computed(() => `${dynasty.value}代主题服饰 AI 设计图${dynasty.value === '元' ? '，蒙古族服饰设计参考' : ''}，非实拍、非文物复原`)
let searchSequence = 0
let controller: AbortController | undefined

async function search() {
  if (!dynasty.value) return
  const sequence = ++searchSequence
  controller?.abort()
  const currentController = new AbortController()
  controller = currentController
  const selectedDynasty = dynasty.value
  const selectedQuery = query.value.trim() || selectedDynasty
  const sessionId = store.activeId
  searching.value = true
  searchError.value = ''
  knowledge.value = undefined
  products.value = []
  productReferences.value = {}
  failedImages.value = []
  imageRetry.value = 0
  try {
    const [nextKnowledge, nextProducts] = await Promise.all([
      request<KnowledgeResult>('GET', '/knowledge/search', sessionId, undefined, { q: selectedQuery }, currentController.signal),
      request<Product[]>('GET', '/products', sessionId, undefined, { dynasty: selectedDynasty }, currentController.signal)
    ])
    if (sequence !== searchSequence) return
    knowledge.value = nextKnowledge
    const featured = featuredIds[selectedDynasty] || []
    const rank = (id: string) => featured.includes(id) ? featured.indexOf(id) : 99
    products.value = [...nextProducts].sort((a,b) => rank(a.id)-rank(b.id))
    const linked = await Promise.all(products.value.map(async p => [p.id, await request<KnowledgeResult>('GET', `/products/${p.id}/references`, sessionId, undefined, {}, currentController.signal)] as const))
    if (sequence === searchSequence) productReferences.value = Object.fromEntries(linked)
  } catch (error) {
    if (sequence === searchSequence && !currentController.signal.aborted) searchError.value = errorText(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

async function select(value: string) {
  dynasty.value = value
  query.value = value
  await search()
}

const load = () => init.run(async () => {
  const available = await request<string[]>('GET', '/knowledge/dynasties', store.activeId)
  dynasties.value = dynastyOrder.filter(value => available.includes(value))
  if (dynasties.value[0]) await select(dynasties.value[0])
})

onMounted(load)
onBeforeUnmount(() => {
  ++searchSequence
  controller?.abort()
})
</script>

<template>
  <div class="page narrow culture-page">
    <header class="culture-heading center">
      <p class="eyebrow">衣冠志 · 一针一线皆有来处</p>
      <h1>把千年风雅，读进日常</h1>
      <p>循着实物与文献，认识历代衣冠。</p>
    </header>
    <EmptyErrorLoading :loading="init.loading.value" :error="init.error.value" :empty="!dynasties.length" empty-text="暂时没有可查阅的朝代。" @retry="load">
      <nav class="dynasty-axis" aria-label="朝代轴">
        <button v-for="d in dynasties" :key="d" type="button" :class="{ selected: dynasty === d }" :aria-pressed="dynasty === d" :aria-label="`查阅${d}代衣冠`" @click="select(d)">
          <span aria-hidden="true">◇</span>{{ d }}
        </button>
      </nav>
      <section class="culture-banner" :aria-label="`${dynasty}代服饰设计参考`">
        <figure class="culture-art" :key="representativeId">
          <img v-if="representativeImage" :key="representativeImage" :src="representativeImage" :data-source="representative?.images[0]" :alt="imageAlt" width="768" height="1024" @error="failImage">
          <div v-else class="state" role="status">{{ searching ? '正在加载设计图…' : products.length ? '设计图暂不可用' : '此朝代暂无在售衣裳' }}<button v-if="!searching && products.length" type="button" @click="retryImage">重载图片</button></div>
          <figcaption>AI 设计图 · 非实拍 · 非文物复原</figcaption>
        </figure>
        <div class="culture-intro">
          <p class="eyebrow">{{ dynasty }}代 · 衣冠小览</p>
          <h2>识其形，更知其意</h2>
          <p v-if="dynasty === '元'">本组含蒙古族服饰设计参考，不将元代的所有服饰统称为汉族形制。具体文化背景与形制依据，请查阅下方知识及来源。</p>
          <p v-else>本组展示{{ dynasty }}代主题的服饰设计参考。具体文化背景与形制依据，请查阅下方知识及来源。</p>
          <p class="caption">设计图用于浏览配色与搭配，不作为断代、文物复原或正式礼仪的依据。</p>
          <RouterLink v-if="representativeId" class="button" :to="`/product/${representativeId}`">{{ representativeName ? `查看「${representativeName}」` : '查看这套设计' }} ↗</RouterLink>
        </div>
      </section>
      <DynastyReferences :dynasty="dynasty" />
      <form class="search-bar" @submit.prevent="search">
        <label for="knowledge-query">查阅衣冠知识</label>
        <input id="knowledge-query" v-model="query" required placeholder="例如：汉代、唐制、元代、正式礼仪">
        <button class="primary" :disabled="searching">查阅</button>
      </form>
      <div :aria-busy="searching">
        <EmptyErrorLoading :loading="searching" :error="searchError" @retry="search">
          <KnowledgePanel v-if="knowledge" :knowledge="knowledge" />
          <div class="actions">
            <RouterLink class="button" :to="{ path: '/guide', query: { prompt: `请解释${query || dynasty}的文化依据和适用场景` } }">问 AI / 问衣使 ↗</RouterLink>
          </div>
          <section id="products" class="section" :aria-label="`${dynasty}代关联商品`">
            <div class="catalogue-heading">
              <h2>从纸上衣冠，到镜中一袭</h2>
              <span class="tag">全部 {{ products.length }} 款在售衣裳</span>
            </div>
            <p>{{ dynasty }}代主题关联商品 · AI 设计图，非实拍、非文物复原。<template v-if="dynasty === '元'">本组含蒙古族服饰设计参考。</template></p>
            <p class="caption">每款衣裳下方可展开查阅史料出处；配色与组合为现代设计，价格与库存以当前商品信息为准。</p>
            <div class="product-grid">
              <div v-for="p in products" :key="p.id" class="culture-product" :data-product-id="p.id">
                <ProductCard :product="p" />
                <details class="product-source"><summary>查阅这款衣裳的史料出处</summary><KnowledgePanel v-if="productReferences[p.id]" :knowledge="productReferences[p.id]!" /></details>
                <RouterLink class="button full" :to="{ path: '/tryon', query: { productId: p.id, skuId: p.skus.find(s => s.stock > 0)?.id } }">看我穿上</RouterLink>
              </div>
            </div>
            <p v-if="!products.length" class="state">此朝代暂无关联商品。</p>
          </section>
        </EmptyErrorLoading>
      </div>
    </EmptyErrorLoading>
  </div>
</template>

<style scoped>
.culture-page .product-grid{grid-template-columns:repeat(4,minmax(0,1fr));gap:22px}.culture-product{min-width:0}.culture-product :deep(.product-card){height:auto}.product-source{padding:8px 0;margin-top:10px}.product-source>summary{font-size:12px;color:var(--wood)}.product-source :deep(.knowledge small){overflow-wrap:anywhere}.catalogue-more{display:flex;margin:24px auto 0}@media(max-width:1100px){.culture-page .product-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:480px){.culture-page .product-grid{gap:12px}}
.dynasty-axis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));width:100%;max-width:660px;gap:clamp(6px,3vw,30px)}
.dynasty-axis button{min-width:0;width:100%;padding:8px 4px;gap:8px;white-space:nowrap;border-bottom:3px solid transparent;border-radius:8px 8px 0 0}
.dynasty-axis button.selected{border-bottom-color:var(--red)}
.culture-banner{grid-template-columns:minmax(0,.9fr) minmax(0,1fr)}
.culture-art{width:100%;max-width:340px;margin:auto;overflow:hidden;border:1px solid var(--line);border-radius:12px;background:#f7f4ef}
.culture-art img{width:100%;height:auto;aspect-ratio:3/4;object-fit:contain}
.culture-art figcaption{padding:10px 12px;background:var(--silk);font-size:11px;color:var(--wood);text-align:center}
.culture-art .state{min-height:280px;margin:0;border:0}
.culture-intro .button{max-width:100%;font-size:13px}
.catalogue-heading{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}
.catalogue-heading h2{margin:0}
.catalogue-heading .tag{white-space:nowrap}
.forthcoming-styles{display:flex;align-items:center;gap:14px;margin-top:22px;padding:18px 20px;border:1px dashed var(--line);border-radius:12px;background:var(--silk)}
.forthcoming-mark{font-size:28px;color:var(--wood)}
.forthcoming-styles p{margin:0}
.forthcoming-title{font-size:15px;color:var(--wood)}
.forthcoming-styles .caption{margin-top:5px;font-size:12px}
@media(max-width:600px){.dynasty-axis{gap:4px;margin:14px auto 28px}.dynasty-axis button{font-size:24px}.culture-banner{grid-template-columns:minmax(0,1fr);gap:22px}.culture-art{max-width:280px}.culture-intro p{font-size:13px}.culture-intro .caption{font-size:12px}}
</style>
