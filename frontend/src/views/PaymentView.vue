<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { request } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import { money, orderStatusNames, paymentNames } from '../types'
import type { Order, PaymentMethod, Product } from '../types'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'

const store = useSession(), route = useRoute(), order = ref<Order>(), products = ref<Record<string, Product>>({})
const init = useAsync(), action = useAsync(), method = ref<PaymentMethod>('ALIPAY'), cancelPrompt = ref(false)
const orderId = String(route.params.orderId || '')
const pending = computed(() => order.value?.status === 'PENDING_PAYMENT')
const paid = computed(() => order.value?.status === 'DEMO_PAID')
const methods: Array<{ id: PaymentMethod; description: string; icon: string }> = [
  { id: 'ALIPAY', description: '支付宝模拟通道', icon: '支' },
  { id: 'WECHAT', description: '微信模拟通道', icon: '微' },
  { id: 'BALANCE', description: '余额模拟通道', icon: '囊' }
]
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
async function readOrder() {
  order.value = await request<Order>('GET', `/orders/${encodeURIComponent(orderId)}`, store.current.id)
  if (order.value.paymentMethod) method.value = order.value.paymentMethod
}
const load = () => init.run(async () => {
  await readOrder()
  const ids = [...new Set(order.value!.cart.lines.map(line => line.productId))]
  const results = await Promise.allSettled(ids.map(id => request<Product>('GET', `/products/${encodeURIComponent(id)}`, store.current.id)))
  products.value = Object.fromEntries(results.flatMap(result => result.status === 'fulfilled' ? [[result.value.id, result.value]] : []))
})
const refresh = () => action.run(readOrder)
const pay = () => action.run(async () => {
  if (!pending.value) return
  order.value = await request<Order>('POST', `/orders/${encodeURIComponent(orderId)}/pay`, store.current.id, { paymentMethod: method.value })
  cancelPrompt.value = false
  store.notice = ''
})
const cancel = () => action.run(async () => {
  if (!pending.value) return
  order.value = await request<Order>('POST', `/orders/${encodeURIComponent(orderId)}/cancel`, store.current.id)
  cancelPrompt.value = false
})
onMounted(load)
</script>

<template>
  <div class="page payment-page">
    <div class="payment-breadcrumb"><RouterLink to="/cart">衣囊</RouterLink><span aria-hidden="true">/</span><span>订单支付</span></div>
    <header class="payment-hero">
      <div><p class="eyebrow">汉韵镜 · 长安结衣笺</p><h1>{{ paid ? '心意已落定，好衣有归处' : order?.status === 'CANCELLED' ? '此笺已收起，再寻一袭心意' : '一袭心意，在此落定' }}</h1><p>{{ paid ? '模拟支付已完成，服饰与收货信息已存入你的购物订单。' : order?.status === 'CANCELLED' ? '这笔订单已取消，预留库存已释放。' : '核对衣裳与收货信息，选择一种方式完成模拟支付。' }}</p></div>
      <div class="cashier-seal" aria-hidden="true"><span>衣</span><small>长安</small></div>
    </header>
    <ol class="payment-steps" aria-label="结算进度"><li class="done"><span>一</span>确认衣囊</li><li :class="{done: !!order}"><span>二</span>填写收货</li><li :class="{active:pending,done:paid}"><span>三</span>{{ paid ? '支付完成' : order?.status === 'CANCELLED' ? '订单取消' : '订单支付' }}</li></ol>
    <EmptyErrorLoading :loading="init.loading.value" :error="init.error.value" @retry="load">
      <div v-if="order" class="payment-layout">
        <section class="payment-paper" aria-labelledby="order-detail-heading">
          <header class="paper-heading"><div><p class="eyebrow">ORDER DETAILS</p><h2 id="order-detail-heading">你的结衣笺</h2></div><span class="payment-status" :class="{paid,cancelled:order.status==='CANCELLED'}">{{ orderStatusNames[order.status] || order.status }}</span></header>
          <dl class="order-meta"><div><dt>订单编号</dt><dd class="order-number">{{ order.orderNumber || order.id }}</dd></div><div><dt>下单时间</dt><dd>{{ date(order.createdAt) }}</dd></div></dl>
          <section class="address-letter" aria-label="收货信息"><span class="address-mark" aria-hidden="true">寄</span><div><h3>{{ order.recipient }}<span>{{ order.phone }}</span></h3><p>{{ order.region }}</p><p>{{ order.address }}</p></div></section>
          <div class="payment-items">
            <article v-for="line in order.cart.lines" :key="line.id" class="payment-item">
              <RouterLink class="payment-image" :to="`/product/${line.productId}`"><img v-if="products[line.productId]?.images[0]" :src="products[line.productId].images[0]" :alt="line.productName"><span v-else aria-hidden="true">衣</span></RouterLink>
              <div class="payment-item-copy"><RouterLink :to="`/product/${line.productId}`"><h3>{{ line.productName }}</h3></RouterLink><p>{{ line.color }} · {{ line.size }}</p><span>{{ money(line.unitPrice) }} × {{ line.quantity }}</span></div><strong>{{ money(line.subtotal) }}</strong>
            </article>
          </div>
          <div class="order-breakdown"><p><span>商品合计 · {{ order.cart.quantity }} 件</span><span>{{ money(order.cart.total) }}</span></p><p><span>配送费用</span><span>¥0.00 <small>模拟订单</small></span></p><p class="total-row"><span>订单总额</span><strong>{{ money(order.cart.total) }}</strong></p></div>
          <footer class="paper-footer"><span>衣有来处，美有回响。</span><RouterLink :to="{path:'/support',query:{orderId:order.id}}">咨询客服 →</RouterLink></footer>
        </section>
        <aside class="cashier" aria-label="订单支付">
          <div class="amount-card"><p>{{ paid ? '已完成模拟支付' : order.status === 'CANCELLED' ? '已取消订单金额' : '应付金额' }}</p><strong>{{ money(order.cart.total) }}</strong><span>共 {{ order.cart.quantity }} 件心选衣裳</span></div>
          <section v-if="pending" class="cashier-body">
            <fieldset :disabled="action.loading.value" class="payment-methods"><legend>选择支付方式</legend><label v-for="item in methods" :key="item.id" class="method-option" :class="{chosen:method===item.id}"><input v-model="method" type="radio" name="payment-method" :value="item.id"><span class="method-icon" :class="item.id.toLowerCase()" aria-hidden="true">{{ item.icon }}</span><span class="method-copy"><strong>{{ paymentNames[item.id] }}</strong><small>{{ item.description }}</small></span><span class="radio-mark" aria-hidden="true">{{ method === item.id ? '✓' : '' }}</span></label></fieldset>
            <p class="payment-demo-note"><span aria-hidden="true">◇</span>本页为模拟支付演示。三种方式均不会发起真实扣款，不会跳转外部支付或安排发货。</p>
            <button class="primary confirm-payment" :disabled="action.loading.value" @click="pay">{{ action.loading.value ? '正在核对订单…' : '确认模拟支付' }}<span v-if="!action.loading.value">{{ money(order.cart.total) }}</span></button>
            <div class="cashier-secondary"><RouterLink :to="{path:'/orders',query:{orderId:order.id}}">稍后支付</RouterLink><button :disabled="action.loading.value" @click="cancelPrompt = !cancelPrompt">取消支付</button></div>
            <div v-if="cancelPrompt" class="cancel-confirm"><p>取消后将关闭此订单并释放预留库存，之后可重新选购。</p><div><button :disabled="action.loading.value" @click="cancelPrompt = false">继续支付</button><button :disabled="action.loading.value" @click="cancel">确认取消订单</button></div></div>
          </section>
          <section v-else class="payment-complete cashier-body" role="status"><span class="completion-seal" :class="{cancelled:!paid}" aria-hidden="true">{{ paid ? '成' : '止' }}</span><h2>{{ paid ? '模拟支付成功' : '订单已取消' }}</h2><template v-if="paid"><p v-if="order.paymentMethod">支付方式：{{ paymentNames[order.paymentMethod] }}</p><p v-if="order.paidAt" class="muted">{{ date(order.paidAt) }}</p><p>订单已保存到“我的 → 购物订单”。<br>本次未产生真实付款与配送。</p></template><p v-else>未扣取任何款项，预留库存已释放。</p><RouterLink class="button primary full" :to="{path:'/orders',query:{orderId:order.id}}">查看本次订单</RouterLink><RouterLink class="button full" to="/">继续逛逛</RouterLink></section>
          <div v-if="action.error.value" class="payment-error" role="alert"><p>{{ action.error.value }}</p><p>若网络中断，可重新核对当前订单状态；同一订单不会重复支付。</p><button :disabled="action.loading.value" @click="refresh">重新核对订单</button></div>
        </aside>
      </div>
    </EmptyErrorLoading>
  </div>
</template>

<style scoped>
.payment-page{max-width:1230px;padding-top:26px}.payment-breadcrumb{display:flex;gap:13px;align-items:center;font-size:12px;color:var(--muted);margin-bottom:30px}.payment-breadcrumb span:last-child{color:var(--ink)}.payment-hero{display:flex;align-items:center;justify-content:space-between;gap:30px}.payment-hero .eyebrow{letter-spacing:.2em;margin:0 0 12px}.payment-hero h1{font-size:clamp(29px,3.1vw,39px);margin:0 0 12px}.payment-hero>div>p:last-child{font-size:13px;color:var(--muted);margin:0}.cashier-seal{width:78px;height:88px;border:1px solid #b0433a80;border-radius:38px 38px 5px 5px;color:var(--red);display:flex;align-items:center;justify-content:center;flex-direction:column;transform:rotate(5deg);flex-shrink:0}.cashier-seal span{font-family:SimSun,serif;font-size:32px;line-height:1.2}.cashier-seal small{color:var(--red);letter-spacing:.3em;font-size:10px}.payment-steps{list-style:none;padding:0;display:flex;justify-content:center;gap:64px;margin:36px 0 31px;border-top:1px solid var(--line);border-bottom:1px solid var(--line);padding-block:19px}.payment-steps li{display:flex;gap:12px;align-items:center;font-size:12px;color:var(--muted);margin:0}.payment-steps li>span{width:28px;height:28px;display:grid;place-items:center;border:1px solid var(--line);border-radius:50%;font-family:SimSun,serif}.payment-steps .done{color:var(--green)}.payment-steps .done>span{background:#e7ece5;border-color:#becfc0}.payment-steps .active{color:var(--red);font-weight:600}.payment-steps .active>span{background:var(--red);border-color:var(--red);color:var(--silk)}.payment-layout{display:grid;grid-template-columns:minmax(0,1fr) 355px;gap:28px;align-items:start}.payment-paper,.cashier{border:1px solid var(--line);background:var(--silk);border-radius:18px;overflow:hidden;box-shadow:0 12px 35px #4e392009}.paper-heading{padding:26px 30px 18px;display:flex;align-items:center;justify-content:space-between;gap:12px}.paper-heading .eyebrow{font-size:9px;letter-spacing:.18em;margin:0 0 4px}.paper-heading h2{font-size:27px;margin:0}.payment-status{font-size:11px;padding:5px 11px;border:1px solid #d7bb8c;background:#fbf3e1;border-radius:20px;color:#956a2f;white-space:nowrap}.payment-status.paid{background:#edf2e9;color:var(--green);border-color:#bfcbbb}.payment-status.cancelled{background:#eeeae3;color:var(--muted);border-color:var(--line)}.order-meta{margin:0 30px 24px;font-size:12px}.order-meta>div{display:flex;justify-content:space-between;gap:16px;padding:8px 0;border-bottom:1px dotted var(--line)}.order-meta dt{color:var(--muted);flex-shrink:0}.order-meta dd{margin:0;text-align:right;overflow-wrap:anywhere}.order-number{font-family:Georgia,serif;font-size:13px;letter-spacing:.03em}.address-letter{display:flex;gap:16px;padding:20px;margin:0 30px 3px;background:#f3efe5;border-radius:10px}.address-mark{width:33px;height:37px;display:grid;place-items:center;border:1px solid #c9a06a80;border-radius:6px;color:var(--wood);font-family:SimSun,serif;flex-shrink:0}.address-letter h3{font-family:inherit;font-size:14px;margin:0 0 8px;font-weight:600}.address-letter h3 span{font-size:12px;color:var(--muted);font-weight:400;margin-left:16px}.address-letter p{font-size:12px;line-height:1.8;margin:2px 0;color:#655a4d}.payment-items{padding:4px 30px 0}.payment-item{display:flex;align-items:center;gap:17px;padding:22px 0;border-bottom:1px solid var(--line)}.payment-image{width:83px;height:112px;background:#eee8dc;border-radius:9px;overflow:hidden;flex-shrink:0;display:grid;place-items:center;color:var(--wood);font-family:SimSun,serif;font-size:32px}.payment-image img{width:100%;height:100%;object-fit:contain}.payment-item-copy{flex:1;min-width:0}.payment-item-copy h3{font-size:20px;margin:0 0 7px}.payment-item-copy p{font-size:12px;color:var(--muted);margin:0 0 13px}.payment-item-copy>span{font-size:12px;color:#75604a}.payment-item>strong{font-family:Georgia,serif;font-size:19px;white-space:nowrap}.order-breakdown{padding:16px 30px 5px}.order-breakdown p{display:flex;justify-content:space-between;gap:20px;font-size:12px;margin:10px 0;color:var(--muted)}.order-breakdown small{font-size:10px;margin-left:4px}.order-breakdown .total-row{border-top:1px solid var(--line);padding-top:15px;align-items:center;color:var(--ink)}.total-row strong{font-family:Georgia,serif;font-size:26px;color:var(--red)}.paper-footer{margin:16px 30px 0;padding:18px 0;display:flex;justify-content:space-between;gap:15px;border-top:1px dashed var(--line);font-size:11px;color:var(--muted)}.paper-footer>span{font-family:SimSun,serif;letter-spacing:.12em}.paper-footer a{color:var(--wood)}.cashier{position:sticky;top:22px}.amount-card{position:relative;padding:27px 27px 29px;background:linear-gradient(125deg,#87362f,#b45243);color:#fff6e8;overflow:hidden}.amount-card:after{content:'◇';position:absolute;right:-22px;bottom:-60px;font:210px SimSun,serif;color:#ffffff0c;transform:rotate(15deg);pointer-events:none}.amount-card p{font-size:12px;letter-spacing:.13em;margin:0 0 9px}.amount-card strong{display:block;font-family:Georgia,serif;font-size:45px;font-weight:400;line-height:1.3}.amount-card>span{display:block;font-size:11px;color:#f1d8c7;margin-top:11px}.cashier-body{padding:25px 25px 18px}.payment-methods{padding:0;margin:0}.payment-methods legend{font-size:15px;font-weight:600;margin:0 0 16px}.method-option{display:flex;align-items:center;gap:13px;padding:13px 14px;border:1px solid var(--line);border-radius:11px;margin:0 0 10px;cursor:pointer;background:#fffdf8;position:relative}.method-option.chosen{border-color:var(--red);background:#faf0e8;box-shadow:0 0 0 1px #b0433a10}.method-option input{position:absolute;opacity:0;width:1px;height:1px;min-height:1px}.method-option:focus-within{outline:2px solid var(--green);outline-offset:3px}.method-icon{width:35px;height:35px;border-radius:9px;display:grid;place-items:center;font-size:22px;font-family:SimSun,serif;flex-shrink:0;background:#e6eef9;color:#3c71a8}.method-icon.wechat{background:#e5ede0;color:#52815f}.method-icon.balance{background:#f0e5d5;color:#a4783f}.method-copy{flex:1}.method-copy strong,.method-copy small{display:block}.method-copy strong{font-size:13px}.method-copy small{font-size:10px;color:var(--muted);margin-top:1px}.radio-mark{width:18px;height:18px;border:1px solid #cfc3b6;border-radius:50%;display:grid;place-items:center;font-size:11px;background:white}.chosen .radio-mark{background:var(--red);border-color:var(--red);color:white}.payment-demo-note{display:flex;gap:9px;align-items:flex-start;font-size:10px;line-height:1.9;color:var(--muted);margin:18px 0}.payment-demo-note>span{font-size:18px;line-height:1.1;color:var(--wood)}.confirm-payment{width:100%;justify-content:space-between;padding:14px 18px;font-size:14px;min-height:50px}.confirm-payment>span{font-family:Georgia,serif}.cashier-secondary{display:flex;justify-content:space-between;align-items:center;gap:15px;margin-top:10px;font-size:11px;color:var(--muted)}.cashier-secondary button{font-size:11px;padding:7px 0;border:0;background:none;color:var(--muted);min-height:35px}.cancel-confirm{border-top:1px solid var(--line);margin-top:13px;padding-top:10px}.cancel-confirm p{font-size:12px;color:var(--muted)}.cancel-confirm>div{display:flex;gap:9px;flex-wrap:wrap}.cancel-confirm button{font-size:11px;padding:8px 12px}.payment-error{padding:15px 20px;color:#96372f;background:#fff1eb;font-size:12px}.payment-error p{margin:6px 0}.payment-error button{margin-top:8px;font-size:12px}.payment-complete{text-align:center;padding-top:30px;padding-bottom:25px}.completion-seal{display:grid;place-items:center;width:58px;height:58px;margin:0 auto 16px;border:1px solid var(--green);color:var(--green);border-radius:50%;font-size:30px;font-family:SimSun,serif}.completion-seal.cancelled{color:var(--muted);border-color:var(--line)}.payment-complete h2{font-size:24px}.payment-complete p{font-size:12px;line-height:1.9;margin:8px 0}.payment-complete .muted{font-size:11px}
@media(max-width:950px){.payment-layout{grid-template-columns:minmax(0,1fr) 310px;gap:18px}.paper-heading{padding:23px 22px 16px}.order-meta{margin-inline:22px}.address-letter{margin-inline:22px;padding:16px}.payment-items{padding-inline:22px}.order-breakdown{padding-inline:22px}.paper-footer{margin-inline:22px}.cashier-body{padding-inline:20px}.address-letter h3 span{display:block;margin:4px 0 0}.payment-item{gap:13px}.payment-image{width:70px;height:97px}.payment-item-copy h3{font-size:18px}.payment-item>strong{font-size:17px}}
@media(max-width:760px){.payment-layout{grid-template-columns:1fr}.cashier{position:static}.payment-steps{gap:32px}.cashier-seal{width:57px;height:66px}.cashier-seal span{font-size:26px}.payment-hero{gap:15px}.amount-card{padding:24px}.amount-card strong{font-size:41px}.payment-methods{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.payment-methods legend{float:none}.method-option{flex-direction:column;align-items:center;gap:9px;padding:14px 6px;text-align:center}.method-copy strong{font-size:12px}.method-copy small{font-size:9px}.radio-mark{position:absolute;right:8px;top:8px;width:15px;height:15px;font-size:9px}.cashier-body{padding:23px}.confirm-payment{font-size:15px;min-height:52px}}
@media(max-width:440px){.payment-breadcrumb{margin-bottom:22px}.payment-hero h1{font-size:27px}.payment-hero>div>p:last-child{font-size:12px}.cashier-seal{display:none}.payment-steps{gap:20px;margin-block:26px}.payment-steps li{gap:7px;font-size:11px}.payment-steps li>span{width:24px;height:24px}.paper-heading{padding:21px 18px 15px}.paper-heading h2{font-size:24px}.order-meta{margin-inline:18px;font-size:11px}.order-number{font-size:12px}.address-letter{margin-inline:18px;gap:12px}.payment-items{padding-inline:18px}.payment-item{gap:12px;flex-wrap:wrap}.payment-item-copy h3{font-size:19px}.payment-item>strong{width:100%;text-align:right;font-size:19px;margin-top:-7px}.order-breakdown{padding-inline:18px}.paper-footer{margin-inline:18px;font-size:10px}.cashier-body{padding:20px}.method-copy small{font-size:8px}.method-option{padding-top:18px}}
</style>
