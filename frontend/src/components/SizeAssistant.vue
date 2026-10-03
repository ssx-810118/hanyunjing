<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { request, errorText } from '../api'
import { useSession } from '../stores/session'
import { fieldNames } from '../types'
import type { Body, Product, SizeAdvice } from '../types'
import ProductArt from './ProductArt.vue'

const props = defineProps<{ productId: string; skuId?: string }>()
const store = useSession(), product = ref<Product>(), advice = ref<SizeAdvice>()
const loading = ref(false), calculating = ref(false), error = ref(''), resultError = ref('')
const source = ref<'manual' | 'saved'>('manual'), color = ref('')
const fields = [
  { key: 'height', label: '身高', min: 80, max: 230 },
  { key: 'chest', label: '胸围', min: 40, max: 180 },
  { key: 'waist', label: '腰围', min: 35, max: 180 },
  { key: 'hip', label: '臀围', min: 40, max: 200 }
] as const
const values = reactive({ height: '', chest: '', waist: '', hip: '', loose: false })
const suggestedSku = computed(() => { if (!advice.value?.size || !advice.value.inRange) return undefined; return product.value?.skus.find(item => item.size === advice.value!.size && item.color === color.value && item.stock > 0) })
const productLink = computed(() => ({ path: '/product/' + props.productId, query: suggestedSku.value ? { skuId: suggestedSku.value.id } : {} }))
let disposed = false, revision = 0, reader: AbortController | undefined, calculation: AbortController | undefined
function clearValues() { fields.forEach(field => { values[field.key] = '' }); values.loose = false }
function invalidate() { advice.value = undefined; resultError.value = '' }
async function load() {
  const token = ++revision
  reader?.abort(); calculation?.abort(); reader = new AbortController()
  product.value = undefined; advice.value = undefined; error.value = ''; resultError.value = ''; calculating.value = false
  loading.value = true; clearValues(); source.value = 'manual'
  try {
    if (!/^p[0-9]+$/.test(props.productId)) throw new Error('未找到所选衣裳，请从商品详情重新打开尺码助手。')
    const value = await request<Product>('GET', '/products/' + encodeURIComponent(props.productId), store.activeId, undefined, {}, reader.signal)
    if (disposed || token !== revision) return
    product.value = value
    color.value = value.skus.find(item => item.id === props.skuId)?.color || value.colors[0] || ''
  } catch (cause) { if (!disposed && token === revision) error.value = errorText(cause) }
  finally { if (!disposed && token === revision) loading.value = false }
}
async function calculate() {
  if (!product.value || calculating.value || disposed) return
  invalidate()
  if (source.value === 'manual' && fields.every(field => values[field.key] === '')) { resultError.value = '请至少填写一项已知尺寸；只填写部分资料时会提示缺失信息。'; return }
  const measurements: Body = { height: null, weightKg: null, chest: null, waist: null, hip: null, loose: values.loose }
  for (const field of fields) {
    if (source.value === 'saved' || values[field.key] === '') continue
    const value = Number(values[field.key])
    if (!Number.isFinite(value) || value < field.min || value > field.max) { resultError.value = `${field.label}请填写 ${field.min}–${field.max} 厘米之间的数字。`; return }
    measurements[field.key] = value
  }
  const token = revision, sessionId = store.activeId, productId = product.value.id
  calculation = new AbortController(); calculating.value = true
  try {
    const result = await request<SizeAdvice>('POST', '/products/' + encodeURIComponent(productId) + '/size-advice', sessionId, { useSavedProfile: source.value === 'saved', measurements: source.value === 'saved' ? null : measurements }, {}, calculation.signal)
    if (!disposed && token === revision && sessionId === store.activeId) advice.value = result
  } catch (cause) { if (!disposed && token === revision) resultError.value = errorText(cause) }
  finally { if (!disposed && token === revision) calculating.value = false }
}
watch(() => `${props.productId}:${props.skuId || ''}:${store.activeId}`, load, { immediate: true })
onBeforeUnmount(() => { disposed = true; revision++; reader?.abort(); calculation?.abort(); clearValues() })
</script>

<template>
  <section class="panel size-assistant" aria-labelledby="size-assistant-title">
    <p class="eyebrow">量体小笺 · 商品尺码核对</p>
    <div class="between size-heading"><h2 id="size-assistant-title">问尺码助手</h2><span class="tag">本站计算 · 即填即核对</span></div>
    <p v-if="loading" class="muted" role="status">正在读取这件衣裳的尺码表…</p>
    <div v-else-if="error" class="error" role="alert"><p>{{ error }}</p><button @click="load">重新读取商品</button></div>
    <template v-else-if="product">
      <div class="size-product"><ProductArt :product="product"/><div><h3>{{ product.name }}</h3><p>{{ product.dynasty }} · {{ product.form }}</p><p>按这件衣裳的实际目录尺码表核对，不会跳成其他商品。</p><RouterLink :to="`/product/${product.id}`">返回商品详情 →</RouterLink></div></div>
      <p class="size-privacy">身材数据只交给本站的尺码区间算法，不发送给在线问衣模型或图片服务；本次填写不会自动保存。</p>
      <form @submit.prevent="calculate">
        <fieldset :disabled="calculating" class="size-source"><legend>使用哪份尺寸资料</legend><label class="check"><input v-model="source" type="radio" value="manual" name="size-source" @change="invalidate">本次填写</label><label class="check"><input v-model="source" type="radio" value="saved" name="size-source" @change="invalidate">使用个人中心已保存的资料</label></fieldset>
        <fieldset v-if="source === 'manual'" :disabled="calculating" class="size-measurements"><legend>已知尺寸（厘米）</legend><div class="form-grid"><label v-for="field in fields" :key="field.key" :for="`size-${field.key}`">{{ field.label }}<input :id="`size-${field.key}`" v-model="values[field.key]" type="number" :min="field.min" :max="field.max" step="0.1" inputmode="decimal" autocomplete="off" placeholder="可留空" @input="invalidate"></label></div><label class="check"><input v-model="values.loose" type="checkbox" @change="invalidate">偏好宽松（按原尺码规则上调一级）</label></fieldset>
        <p v-else class="notice">仅使用当前登录账号已保存的资料，不回显原始数值。尚未保存时，<RouterLink to="/profile">先到个人中心填写</RouterLink>，或切回“本次填写”。</p>
        <div class="size-submit"><button class="primary" type="submit" :disabled="calculating || !product.sizeChart.length">{{ calculating ? '正在核对尺码…' : '核对我的尺码' }}</button><small>缺少围度时不会从体重推算。</small></div>
      </form>
      <p v-if="resultError" class="error" role="alert">{{ resultError }}</p>
      <section v-if="advice" class="size-result" role="status" aria-live="polite"><p class="eyebrow">这件衣裳的核对结果</p><h3>{{ advice.size && advice.inRange ? `建议核对 ${advice.size} 码` : '暂时没有可验证的匹配尺码' }}</h3><p v-if="advice.size && advice.inRange">资料完整度：{{ advice.confidence === '高' ? '已填写四项尺寸' : '部分尺寸缺失，请补充后复核' }}。</p><p v-if="advice.missingFields.length">待补充：{{ advice.missingFields.map(field => fieldNames[field] || field).join('、') }}。</p><p>{{ advice.algorithm }}</p><template v-if="advice.size && advice.inRange"><label v-if="product.colors.length > 1" for="size-color">选择颜色<select id="size-color" v-model="color"><option v-for="item in product.colors" :key="item" :value="item">{{ item }}</option></select></label><p v-else>颜色：{{ color }}</p><div v-if="suggestedSku" class="actions"><RouterLink class="button primary" :to="productLink">带着 {{ suggestedSku.size }} 码返回商品</RouterLink><RouterLink class="button" :to="{path:'/tryon',query:{productId:product.id,skuId:suggestedSku.id}}">用此规格去试穿</RouterLink></div><p v-else class="notice">{{ color }} / {{ advice.size }} 码当前无库存，不会替你换成其他尺码。可返回商品查看可售规格。</p></template><p class="caption">此结果来自商品尺码区间，不是人体识别，也不保证实际合身。特殊版型或尺寸不匹配时请咨询客服。</p><RouterLink :to="{path:'/support',query:{productId:product.id}}">咨询客服，核对版型 →</RouterLink></section>
      <details class="size-chart"><summary>查看这件衣裳的尺码表与量体方法</summary><div class="table-scroll"><table><thead><tr><th>尺码</th><th>身高</th><th>胸围</th><th>腰围</th><th>臀围</th></tr></thead><tbody><tr v-for="row in product.sizeChart" :key="row.size"><td>{{ row.size }}</td><td v-for="key in (['height','chest','waist','hip'] as const)" :key="key">{{ row[key].min }}–{{ row[key].max }}</td></tr></tbody></table></div><p v-if="!product.sizeChart.length">此商品暂无尺码表，请咨询客服。</p><p>用软尺水平绕身体一周，分别记录胸部最丰满处、自然腰部与臀部最丰满处；自然站立，不勒紧软尺。单位统一为厘米。</p></details>
    </template>
  </section>
</template>

<style scoped>
.size-assistant{margin-bottom:20px;border-color:#c6b28f;background:linear-gradient(135deg,#fffdf8,#f2eddf)}.size-heading{margin-bottom:18px}.size-heading h2{margin:0}.size-product{display:grid;grid-template-columns:86px 1fr;gap:17px;align-items:start}.size-product h3{font-size:20px;margin:0 0 8px}.size-product p{font-size:12px;margin:6px 0;color:var(--muted)}.size-product a{font-size:12px;color:var(--red)}.size-product :deep(figcaption){display:none}.size-privacy{font-size:12px;color:var(--green);line-height:1.9;padding:12px 14px;background:#eaf0e955;border-left:2px solid #7e9988;margin-top:20px}.size-source{display:flex;flex-wrap:wrap;gap:0 18px;margin:20px 0 5px}.size-source legend,.size-measurements legend{font-size:13px;font-weight:600}.size-source .check{font-size:12px;margin:0;gap:7px}.size-source input[type=radio]{width:16px;height:16px;min-height:16px;accent-color:var(--red)}.size-measurements{margin-top:14px}.size-measurements label{font-size:13px}.size-measurements .check{font-size:12px}.size-submit{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.size-submit small{font-size:11px}.size-result{margin-top:22px;background:#fffdf8;padding:20px;border:1px solid #c7b598;border-radius:12px}.size-result .eyebrow{margin:0 0 7px}.size-result h3{color:var(--green);font-size:24px}.size-result p{font-size:12px;line-height:1.9}.size-result .actions{gap:8px}.size-result .button{font-size:12px;padding:9px 12px}.size-result>a{font-size:12px;color:var(--red)}.size-chart{margin-top:22px}.size-chart table{font-size:12px}.size-chart th,.size-chart td{padding:9px}.size-chart p{line-height:1.9;color:var(--muted)}
</style>
