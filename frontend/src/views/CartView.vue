<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import { request, SessionChangedError } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import type { Cart, CartLine, Product, Preview, Order } from '../types'
import { money } from '../types'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
const store = useSession(), router = useRouter(), session = store.current, cart = ref<Cart>(), products = ref<Record<string, Product>>({}), preview = ref<Preview>(), order = ref<Order>(), choices = ref<Record<string, string>>({}), uncertain = ref(false)
const shipping = reactive({ recipient: '', phone: '', region: '', address: '' })
const init = useAsync(), action = useAsync()
let pendingOrder: (typeof shipping & { idempotencyKey: string }) | undefined
function invalidatePreview() { if (!uncertain.value) { preview.value = undefined; pendingOrder = undefined } }
watch(shipping, invalidatePreview)
async function refresh() {
  cart.value = await request<Cart>('GET', '/cart', session.id)
  const items = await Promise.all([...new Set(cart.value.lines.map(line => line.productId))].map(id => request<Product>('GET', '/products/' + id, session.id)))
  products.value = Object.fromEntries(items.map(product => [product.id, product]))
  choices.value = Object.fromEntries(cart.value.lines.map(line => [line.id, line.skuId]))
  preview.value = undefined
}
const load = () => init.run(refresh)
const update = (line: CartLine, quantity: number) => action.run(async () => { invalidatePreview(); await request('PUT', '/cart/' + line.id, session.id, { quantity }); await refresh() })
const remove = (line: CartLine) => action.run(async () => { invalidatePreview(); await request('DELETE', '/cart/' + line.id, session.id); await refresh() })
const change = (line: CartLine) => action.run(async () => {
  const target = choices.value[line.id]
  if (target === line.skuId) return
  invalidatePreview()
  const existing = cart.value?.lines.find(item => item.skuId === target)
  await request('POST', '/cart', session.id, { productId: line.productId, skuId: target, quantity: line.quantity + (existing?.quantity || 0) })
  try { await request('DELETE', '/cart/' + line.id, session.id) }
  catch { await refresh(); throw new Error('新规格已加入，但旧规格删除失败，请核对衣囊并手动移除旧行，不要再次换规格。') }
  await refresh()
})
const getPreview = () => action.run(async () => {
  if (Object.values(shipping).some(value => !value.trim())) throw new Error('请完整填写收货人、电话、收货地区和详细地址。')
  preview.value = await request<Preview>('POST', '/orders/preview', session.id)
})
const checkout = () => action.run(async () => {
  if (!preview.value && !pendingOrder) return
  if (!pendingOrder) pendingOrder = { recipient: shipping.recipient.trim(), phone: shipping.phone.trim(), region: shipping.region.trim(), address: shipping.address.trim(), idempotencyKey: crypto.randomUUID() }
  uncertain.value = true
  try { order.value = await request<Order>('POST', '/orders', session.id, pendingOrder) }
  catch (error) {
    if (error instanceof SessionChangedError || (axios.isAxiosError(error) && error.response && [400, 401, 403, 409, 422].includes(error.response.status))) {
      uncertain.value = false
      pendingOrder = undefined
      preview.value = undefined
    }
    throw error
  }
  store.notice = ''
  preview.value = undefined
  cart.value = undefined
  pendingOrder = undefined
  Object.assign(shipping, { recipient: '', phone: '', region: '', address: '' })
  await router.push(`/payment/${order.value!.id}`)
})
const lastTask = (line: CartLine) => [...session.tasks].reverse().find(task => task.productId === line.productId && task.skuId === line.skuId && ['DONE', 'DEGRADED'].includes(task.status))
onMounted(load)
onBeforeUnmount(() => { Object.assign(shipping, { recipient: '', phone: '', region: '', address: '' }); pendingOrder = undefined })
</script>

<template>
  <div class="page narrow">
    <div class="section-heading"><div><p class="eyebrow">衣囊 · 所爱皆在此</p><h1>把长安的风雅，收入衣囊</h1></div><RouterLink class="button" to="/orders">查看购物订单</RouterLink></div>
    <section v-if="order" class="panel order-success center" role="status">
      <span class="seal">笺</span><h2>订单已建立，待你落定心意</h2><p>下一步选择支付方式并完成模拟支付。</p><p>订单号：<strong>{{ order.orderNumber || order.id }}</strong></p><p>订单金额：{{ money(order.cart.total) }}</p><p>订单已保存，稍后也可从“我的 → 购物订单”继续支付。</p>
      <div class="actions success-actions"><RouterLink class="button primary" :to="`/payment/${order.id}`">前往订单支付</RouterLink><RouterLink class="button" :to="{path:'/orders',query:{orderId:order.id}}">查看本次订单</RouterLink></div>
    </section>
    <EmptyErrorLoading v-else :loading="init.loading.value" :error="init.error.value" :empty="!!cart && !cart.lines.length" empty-text="衣囊尚空，去问衣使那里寻一袭心意。" @retry="load">
      <div class="cart-layout">
        <section>
          <article v-for="line in cart?.lines" :key="line.id" class="panel cart-line">
            <div class="cart-product-top"><RouterLink v-if="products[line.productId]?.images[0]" class="cart-thumbnail" :to="'/product/' + line.productId"><img :src="products[line.productId].images[0]" :alt="line.productName"></RouterLink><div><RouterLink :to="'/product/' + line.productId"><h2>{{ line.productName }}</h2></RouterLink><p>{{ line.color }} / {{ line.size }} · 单价 {{ money(line.unitPrice) }}</p><strong>{{ money(line.subtotal) }}</strong></div></div>
            <div class="cart-controls"><label>数量<input type="number" :value="line.quantity" min="1" max="99" :disabled="action.loading.value || uncertain" @change="update(line,Number(($event.target as HTMLInputElement).value))"></label><label>换规格<select v-model="choices[line.id]" :disabled="action.loading.value || uncertain"><option v-for="sku in products[line.productId]?.skus" :key="sku.id" :value="sku.id" :disabled="sku.stock < 1">{{ sku.color }} / {{ sku.size }} · {{ money(sku.price) }}</option></select></label><button :disabled="action.loading.value || uncertain || choices[line.id] === line.skuId" @click="change(line)">确认换规格</button><button :disabled="action.loading.value || uncertain" @click="remove(line)">移除</button></div>
            <RouterLink v-if="lastTask(line)" :to="{path:'/result/' + lastTask(line)!.id,query:{sessionId:session.id}}">回看本会话试穿 →</RouterLink><RouterLink v-else :to="{path:'/tryon',query:{productId:line.productId,skuId:line.skuId}}">这套还未试穿，去照镜 →</RouterLink>
          </article>
          <section class="panel shipping-panel"><h2>收货信息</h2><p class="muted">用于本次模拟订单记录，不会实际寄送商品。</p>
            <form id="shipping-form" @submit.prevent="getPreview">
              <div class="form-grid">
                <label for="order-recipient">收货人<input id="order-recipient" v-model="shipping.recipient" autocomplete="shipping name" required maxlength="40" placeholder="请输入收货人姓名" :disabled="action.loading.value || uncertain"></label>
                <label for="order-phone">联系电话<input id="order-phone" v-model="shipping.phone" type="tel" autocomplete="shipping tel" required minlength="6" maxlength="24" pattern="[+0-9\(\) \-]{6,24}" placeholder="请输入联系电话" :disabled="action.loading.value || uncertain"></label>
              </div>
              <label for="order-region">收货地区（地点）<input id="order-region" v-model="shipping.region" autocomplete="shipping address-level1" required maxlength="100" placeholder="例如：陕西省 西安市 雁塔区" :disabled="action.loading.value || uncertain"></label>
              <label for="order-address">详细地址<textarea id="order-address" v-model="shipping.address" autocomplete="shipping street-address" required maxlength="200" rows="3" placeholder="街道、门牌号、楼栋及房间号" :disabled="action.loading.value || uncertain"/></label>
            </form>
          </section>
        </section>
        <aside class="panel checkout">
          <h2>衣囊小计</h2><p>{{ cart?.quantity }} 件衣物</p><p class="price">{{ money(cart?.total || 0) }}</p><span class="demo">模拟下单 · 无真实支付</span><p class="caption">填写收货信息后确认订单。金额与库存由后端再次核验。</p>
          <button class="primary full" type="submit" form="shipping-form" :disabled="action.loading.value || uncertain">核对订单</button>
          <div v-if="preview && !uncertain" class="notice"><h3>确认结衣笺</h3><p>应付 {{ money(preview.payable) }} · {{ preview.currency }}</p><p>{{ shipping.recipient }} · {{ shipping.phone }}<br>{{ shipping.region }} {{ shipping.address }}</p><p>下一步可选择支付宝、微信支付或余额支付，均为模拟演示。</p><button class="primary full" :disabled="action.loading.value" @click="checkout">提交订单，去支付</button></div>
          <div v-if="uncertain && !order" class="notice order-pending"><p>{{ action.loading.value ? '正在提交订单，请稍候。' : '订单提交结果尚未确认。可先查看购物订单，或使用同一请求编号重试，避免重复生成订单。' }}</p><RouterLink class="button full" to="/orders">查看购物订单</RouterLink><button v-if="!action.loading.value" class="full" @click="checkout">重试确认本次订单</button></div>
        </aside>
      </div>
    </EmptyErrorLoading>
    <p v-if="action.error.value" class="error" role="alert">{{ action.error.value }}</p><div v-if="!order" class="actions"><RouterLink class="button" to="/guide">继续问衣</RouterLink><RouterLink class="button" to="/">再逛逛</RouterLink></div>
  </div>
</template>

<style scoped>
.cart-product-top{display:flex;gap:20px;align-items:center;margin-bottom:14px}.cart-product-top h2{margin:0 0 8px}.cart-product-top p{font-size:13px;margin:5px 0}.cart-product-top strong{color:var(--red)}.cart-thumbnail{width:82px;height:108px;flex-shrink:0;overflow:hidden;border-radius:9px;background:#eee8dc}.cart-thumbnail img{width:100%;height:100%;object-fit:contain}.shipping-panel{margin-top:26px}.shipping-panel input,.shipping-panel textarea{margin-top:7px}.shipping-panel .muted{font-size:13px}.checkout .caption{margin-top:16px}.checkout>.notice{margin-top:22px}.success-actions{justify-content:center}.order-pending{margin-top:20px}@media(max-width:600px){.cart-product-top{gap:14px}.cart-thumbnail{width:70px;height:95px}.shipping-panel .form-grid{grid-template-columns:1fr}}
</style>

