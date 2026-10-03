<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId } from 'vue'
import { useRouter } from 'vue-router'
import { errorText, request, SessionChangedError } from '../api'
import { useAuth } from '../stores/auth'
import { useSession } from '../stores/session'
import type { Cart, Product } from '../types'
import { money } from '../types'

const props = defineProps<{ product: Product; preferredSkuId?: string; allowBuy?: boolean; compact?: boolean; disabled?: boolean }>()
const auth = useAuth(), store = useSession(), router = useRouter(), titleId = useId()
const dialog = ref<HTMLDialogElement>(), intent = ref<'cart' | 'buy'>('cart'), color = ref(''), skuId = ref(''), busy = ref(false), error = ref('')
const colors = computed(() => [...new Set(props.product.skus.map(sku => sku.color))])
const selected = computed(() => props.product.skus.find(sku => sku.id === skuId.value))
const available = computed(() => props.product.skus.some(sku => sku.stock > 0))
let disposed = false

function chooseColor(value: string) { color.value = value; skuId.value = ''; error.value = '' }
function open(mode: 'cart' | 'buy') {
  if (props.disabled || busy.value || !available.value) return
  intent.value = mode; error.value = ''
  const preferred = props.product.skus.find(sku => sku.id === props.preferredSkuId && sku.stock > 0)
  color.value = preferred?.color || colors.value[0] || ''
  skuId.value = preferred?.id || ''
  dialog.value?.showModal()
}
function close() { if (!busy.value) dialog.value?.close() }
async function confirm() {
  if (busy.value || !selected.value || selected.value.stock < 1) return
  const productId = props.product.id, selectedSku = selected.value, mode = intent.value
  busy.value = true; error.value = ''
  try {
    if (!auth.authenticated) {
      dialog.value?.close()
      const loggedIn = await auth.requestLogin('', mode === 'buy' ? '登录后将继续购买你选好的衣裳。' : '登录后将继续把你选好的衣裳加入衣囊。')
      if (!loggedIn) { if (!disposed) dialog.value?.showModal(); return }
    }
    const accountId = auth.user?.id, sessionId = store.current.id
    const cart = await request<Cart>('GET', '/cart', sessionId)
    if (!accountId || auth.user?.id !== accountId) throw new SessionChangedError()
    const existing = cart.lines.find(line => line.skuId === selectedSku.id)
    // Buying an item already in the cart keeps its quantity; it does not silently add another.
    if (mode === 'cart' || !existing) {
      const quantity = (existing?.quantity || 0) + 1
      if (quantity > selectedSku.stock) throw new Error('衣囊中的该规格数量已达到当前库存，请先到衣囊核对。')
      await request('POST', '/cart', sessionId, { productId, skuId: selectedSku.id, quantity })
    }
    dialog.value?.close()
    if (mode === 'buy') store.notice = '衣裳已选好，请核对衣囊并填写收货信息。'
    else store.confirmCartAddition(`已加入衣囊：${props.product.name}（${selectedSku.color} / ${selectedSku.size}）。`)
    if (mode === 'buy') await router.push('/cart')
  } catch (cause) {
    error.value = errorText(cause)
    if (disposed || !dialog.value?.open) store.notice = error.value
  } finally { busy.value = false }
}
onBeforeUnmount(() => { disposed = true; dialog.value?.close() })
</script>

<template>
  <div class="purchase-actions" :class="{ compact, 'with-buy': allowBuy }">
    <button type="button" :disabled="disabled || busy || !available" @click="open('cart')">{{ available ? '加入衣囊' : '暂时缺货' }}</button>
    <button v-if="allowBuy" type="button" class="primary" :disabled="disabled || busy || !available" @click="open('buy')">去购买</button>
  </div>
  <Teleport to="body"><dialog ref="dialog" class="purchase-dialog" :aria-labelledby="titleId" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
    <div class="between"><span class="eyebrow">{{ intent === 'buy' ? '把喜欢的衣裳带回家' : '将这一袭收入衣囊' }}</span><button type="button" class="close-purchase" aria-label="关闭规格选择" :disabled="busy" @click="close">×</button></div>
    <h2 :id="titleId">{{ product.name }}</h2>
    <p class="purchase-help">请确认颜色与尺码。试穿画面仅供搭配参考，请按尺码表选择。</p>
    <fieldset :disabled="busy"><legend>颜色</legend><div class="chips"><button v-for="value in colors" :key="value" type="button" :aria-pressed="color === value" @click="chooseColor(value)">{{ value }}</button></div></fieldset>
    <fieldset :disabled="busy"><legend>尺码</legend><div class="chips"><button v-for="sku in product.skus.filter(item => item.color === color)" :key="sku.id" type="button" :disabled="sku.stock < 1" :aria-pressed="skuId === sku.id" @click="skuId = sku.id; error = ''">{{ sku.size }}{{ sku.stock < 1 ? ' · 缺货' : '' }}</button></div></fieldset>
    <p v-if="selected" class="purchase-selection">{{ selected.color }} / {{ selected.size }}<strong>{{ money(selected.price) }}</strong></p><p v-else class="purchase-help">请选择尺码后继续。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button type="button" class="primary full" :disabled="busy || !selected || selected.stock < 1" @click="confirm">{{ busy ? '正在处理…' : intent === 'buy' ? '确认并去购买' : '确认加入衣囊' }}</button>
    <RouterLink class="purchase-detail" :to="`/product/${product.id}`" @click="close">查看衣裳详情与尺码表 →</RouterLink>
  </dialog></Teleport>
</template>

<style scoped>
.purchase-actions{display:grid;grid-template-columns:minmax(0,1fr);gap:10px;margin:16px 0}.purchase-actions.with-buy{grid-template-columns:repeat(2,minmax(0,1fr))}.purchase-actions button{padding-left:10px;padding-right:10px;font-size:13px;min-width:0}.purchase-actions.compact{margin:14px 0 9px}.purchase-dialog{width:min(470px,calc(100vw - 28px))}.purchase-dialog h2{font-size:26px;margin:8px 0 12px}.purchase-dialog .eyebrow{margin:0}.close-purchase{border:0;background:transparent;padding:0 10px;font-size:25px}.purchase-help{font-size:12px;color:var(--muted);line-height:1.9}.purchase-dialog fieldset{margin:22px 0}.purchase-dialog legend{font-size:13px}.purchase-dialog .chips{margin:0}.purchase-selection{display:flex;justify-content:space-between;align-items:center;gap:12px;font-size:14px;margin:24px 0 10px}.purchase-selection strong{color:var(--red);font-size:23px;font-family:Georgia,serif}.purchase-detail{display:block;text-align:center;font-size:12px;color:var(--wood);margin-top:18px}@media(max-width:480px){.purchase-dialog{padding:22px}.purchase-dialog h2{font-size:24px}}
</style>
