<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { request } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import { money, paymentNames, orderStatusNames } from '../types'
import type { Order, Product } from '../types'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
import OrderShipment from '../components/OrderShipment.vue'
const store = useSession(), route = useRoute(), orders = ref<Order[]>([]), products = ref<Record<string, Product>>({}), state = useAsync()
const load = () => state.run(async () => {
  orders.value = await request<Order[]>('GET', '/orders', store.current.id)
  const ids = [...new Set(orders.value.flatMap(order => order.cart.lines.map(line => line.productId)))]
  const results = await Promise.allSettled(ids.map(id => request<Product>('GET', `/products/${encodeURIComponent(id)}`, store.current.id)))
  products.value = Object.fromEntries(results.flatMap(result => result.status === 'fulfilled' ? [[result.value.id, result.value]] : []))
})
const date = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
onMounted(load)
</script>

<template>
  <div class="page narrow orders-page">
    <div class="section-heading"><div><p class="eyebrow">我的 · 购物订单</p><h1>一衣一笺，有迹可寻</h1><p class="muted">查看当前账号的模拟购物记录和收货信息。</p></div><RouterLink class="button" to="/cart">回到衣囊</RouterLink></div>
    <EmptyErrorLoading :loading="state.loading.value" :error="state.error.value" :empty="!orders.length" empty-text="还没有购物订单。把心仪的衣服加入衣囊，完成模拟购买后即可在这里查看。" @retry="load">
      <article v-for="order in orders" :key="order.id" class="panel order-card" :class="{highlighted: route.query.orderId === order.id}" :aria-label="`订单 ${order.orderNumber || order.id}`">
        <header class="order-heading"><div><p class="eyebrow">订单号</p><h2>{{ order.orderNumber || order.id }}</h2><p class="muted">下单时间：{{ date(order.createdAt) }}</p></div><span class="tag" :class="{pending:order.status==='PENDING_PAYMENT',cancelled:order.status==='CANCELLED'}">{{ orderStatusNames[order.status] || order.status }}</span></header>
        <div v-for="line in order.cart.lines" :key="line.id" class="order-line">
          <RouterLink class="order-image" :to="`/product/${line.productId}`"><img v-if="products[line.productId]?.images[0]" :src="products[line.productId].images[0]" :alt="line.productName" loading="lazy"><span v-else aria-hidden="true">衣</span></RouterLink>
          <div class="order-product"><RouterLink :to="`/product/${line.productId}`"><h3>{{ line.productName }}</h3></RouterLink><p>{{ line.color }} / {{ line.size }}</p><p class="muted">数量：{{ line.quantity }} 件 · 单价 {{ money(line.unitPrice) }}</p></div><strong>{{ money(line.subtotal) }}</strong>
        </div>
        <section class="delivery-summary" aria-label="订单收货信息"><h3>收货信息</h3><dl><div><dt>收货人</dt><dd>{{ order.recipient }}</dd></div><div><dt>联系电话</dt><dd>{{ order.phone }}</dd></div><div><dt>收货地区</dt><dd>{{ order.region }}</dd></div><div class="delivery-address"><dt>详细地址</dt><dd>{{ order.address }}</dd></div></dl></section>
        <section v-if="order.paymentMethod || order.paidAt || order.cancelledAt" class="payment-summary"><span v-if="order.paymentMethod">支付方式：{{ paymentNames[order.paymentMethod] }}（模拟）</span><span v-if="order.paidAt">支付时间：{{ date(order.paidAt) }}</span><span v-if="order.cancelledAt">取消时间：{{ date(order.cancelledAt) }}</span></section>
        <OrderShipment :order-id="order.id"/>
        <footer class="order-total"><span>共 {{ order.cart.quantity }} 件衣物</span><span>{{ order.status === 'PENDING_PAYMENT' ? '待支付金额' : '模拟订单金额' }} <strong>{{ money(order.cart.total) }}</strong></span></footer>
        <div class="order-actions"><RouterLink class="button" :to="{path:'/support',query:{orderId:order.id}}">咨询客服</RouterLink><RouterLink class="button" :class="{primary:order.status==='PENDING_PAYMENT'}" :to="`/payment/${order.id}`">{{ order.status === 'PENDING_PAYMENT' ? '继续支付' : '查看订单详情' }}</RouterLink></div>
      </article>
    </EmptyErrorLoading>
    <div class="actions"><RouterLink class="button" to="/profile">个人中心</RouterLink><RouterLink class="button primary" to="/">继续逛逛</RouterLink></div>
  </div>
</template>

<style scoped>
.order-heading .tag.pending{color:#916728;background:#fff1d7;border-color:#d8b875}.order-heading .tag.cancelled{color:var(--muted);background:#eeeae3;border-color:var(--line)}.payment-summary{display:flex;flex-wrap:wrap;gap:8px 25px;padding:0 28px 20px;font-size:12px;color:var(--muted)}.order-actions{display:flex;justify-content:flex-end;gap:12px;padding:0 28px 22px}.order-actions .button{font-size:12px;min-height:39px;padding:9px 14px}@media(max-width:600px){.payment-summary{padding-inline:20px}.order-actions{padding:0 20px 20px}}
.orders-page>.notice{margin-bottom:28px}.order-card{margin:22px 0;padding:0;overflow:hidden}.order-card.highlighted{border-color:var(--gold)}.order-heading{display:flex;justify-content:space-between;align-items:center;gap:18px;padding:23px 28px;border-bottom:1px solid var(--line);background:#f6f2e9}.order-heading h2{font-family:Georgia,serif;font-size:21px;overflow-wrap:anywhere;margin:3px 0 9px}.order-heading .eyebrow{margin:0}.order-heading p:last-child{font-size:12px;margin:0}.order-heading .tag{flex-shrink:0}.order-line{display:flex;align-items:center;gap:22px;margin:0 28px;padding:24px 0;border-bottom:1px solid var(--line)}.order-image{width:94px;height:118px;flex-shrink:0;border-radius:9px;overflow:hidden;background:#eee8dc;display:flex;align-items:center;justify-content:center;color:var(--wood);font-size:32px}.order-image img{width:100%;height:100%;object-fit:contain}.order-product{flex:1;min-width:0}.order-product h3{margin:0 0 7px}.order-product p{margin:4px 0;font-size:13px}.order-line>strong{white-space:nowrap;color:var(--red)}.delivery-summary{padding:20px 28px}.delivery-summary h3{font-size:20px}.delivery-summary dl{display:grid;grid-template-columns:1fr 1fr;gap:12px 25px;margin:15px 0 5px}.delivery-summary dl>div{display:flex;align-items:baseline;gap:16px;min-width:0}.delivery-summary dt{font-size:13px;min-width:66px;color:var(--muted)}.delivery-summary dd{margin:0;overflow-wrap:anywhere;font-size:14px}.delivery-address{grid-column:1/-1}.order-total{border-top:1px solid var(--line);display:flex;justify-content:space-between;gap:15px;padding:20px 28px;font-size:13px;align-items:center}.order-total strong{color:var(--red);font-size:24px;margin-left:12px;font-family:Georgia,serif}@media(max-width:600px){.order-heading{padding:20px;align-items:flex-start;flex-direction:column;gap:12px}.order-line{margin:0 20px;gap:14px;flex-wrap:wrap}.order-image{width:74px;height:98px}.order-product h3{font-size:20px}.order-line>strong{width:100%;text-align:right}.delivery-summary{padding:18px 20px}.delivery-summary dl{grid-template-columns:1fr}.delivery-address{grid-column:auto}.order-total{padding:18px 20px;flex-wrap:wrap}.order-total strong{font-size:22px}}
</style>
