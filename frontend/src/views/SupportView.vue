<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { useAuth } from '../stores/auth'
import { useSession } from '../stores/session'
import { request, errorText } from '../api'
import type { Product, Order } from '../types'
interface Ticket { id:string; number:string; category:string; productId:string|null; orderId:string|null; message:string; status:string; createdAt:string }
interface SubmissionSnapshot { category:string; productId:string|null; orderId:string|null; message:string }
const route=useRoute(), router=useRouter(), auth=useAuth(), store=useSession()
const replies=ref<Record<string,{reply:string;status:string}>>({})
function readProductId(value: unknown) { const id=typeof value==='string'?value.trim():''; return /^[A-Za-z0-9_-]{1,64}$/.test(id) && !/^(null|undefined)$/i.test(id)?id:'' }
function readOrderId(value: unknown) { const id=typeof value==='string'?value.trim():''; return /^[A-Za-z0-9_-]{1,60}$/.test(id) && !/^(null|undefined)$/i.test(id)?id:'' }
const selectionDialog=ref<HTMLDialogElement>(), selectionMessage=ref(''), checkingTicket=ref('')
const productId=ref(readProductId(route.query.productId))
const orderId=ref(readOrderId(route.query.orderId))
const product=ref<Product>(), order=ref<Order>(), tickets=ref<Ticket[]>([]), message=ref(''), category=ref(orderId.value?'订单售后':productId.value?'商品咨询':'其他问题')
const busy=ref(false), loading=ref(false), error=ref(''), loadError=ref(''), success=ref(''), clientId=ref(crypto.randomUUID()), submittedSnapshot=ref<SubmissionSnapshot|null>(null)
const categories=['商品咨询','尺码选择','试穿问题','订单售后','其他问题']
const sizeLink=computed(()=>productId.value?{path:'/guide',query:{mode:'size',productId:productId.value,prompt:`我想了解${product.value?.name || '这件衣裳'}的尺码`}}:{path:'/'})
let disposed=false
let loadRevision=0
let contextRevision=0
const faqs=computed(()=>[
  {question:'尺码怎么选？',answer:'进入衣裳详情，打开“问尺码助手”，按提示填写身高和围度，助手会核对当前商品的尺码区间。缺少资料时会明确提示，不从照片或体重猜测围度。',link:sizeLink.value,label:productId.value?'打开尺码助手':'先选一件衣裳'},
  {question:'试穿一直在等待，应该怎么办？',answer:'试衣镜会显示上传、远端排队和生成等阶段。等待期间只查询同一个任务，不自动再次生成；可打开结果页稍后查看。停止本地等待不代表远端任务已经取消。',link:'/tryon',label:'查看试衣镜'},
  {question:'在哪里支付和查看订单？',answer:'在衣囊填写收货资料并确认订单后，进入订单支付页，选择支付宝、微信或余额进行模拟支付。订单会保存在当前登录账号的“购物订单”中。本站不扣真实款项，也不实际发货。',link:'/orders',label:'打开购物订单'},
  {question:'商品图、换装图和朝代说明可以直接当作考据吗？',answer:'商品图与换装图为AI设计或视觉预览，不是实拍，也不是文物精确复原。衣冠志提供可查看的史料出处；现代设计、族群背景和文物参考会分开说明。',link:'/culture',label:'查看衣冠出处'},
  {question:'如何删除上传的人像？',answer:'进入个人中心的人像管理删除。本站人像和关联结果为临时保存，删除本站副本不代表外部图像服务的副本也已删除。',link:'/profile',label:'管理我的人像'}
])
async function load(){
  const revision=++loadRevision
  const currentProductId=productId.value
  const currentOrderId=orderId.value
  loading.value=true;loadError.value=''
  try{
    await auth.initialize()
    if(disposed || revision!==loadRevision)return
    const sid=store.current.id
    const values=await Promise.all([auth.authenticated?request<Ticket[]>('GET','/support/tickets',sid):Promise.resolve([]),currentProductId?request<Product>('GET',`/products/${currentProductId}`,sid):Promise.resolve(undefined),currentOrderId&&auth.authenticated?request<Order[]>('GET','/orders',sid):Promise.resolve([])])
    if(disposed || revision!==loadRevision)return
    const replyValues=await Promise.all(values[0].map(async ticket=>[ticket.id,await request<{reply:string;status:string}>('GET',`/support/tickets/${ticket.id}/reply`)] as const))
    if(disposed || revision!==loadRevision)return
    replies.value=Object.fromEntries(replyValues);tickets.value=values[0];product.value=values[1];order.value=values[2].find(item=>item.id===currentOrderId)
    if(currentOrderId&&auth.authenticated&&!order.value)loadError.value='未找到当前账号的关联订单。请从“购物订单”重新进入客服。'
  }catch(e){if(!disposed && revision===loadRevision)loadError.value=errorText(e)}finally{if(!disposed && revision===loadRevision)loading.value=false}
}
function sameSnapshot(left: SubmissionSnapshot, right: SubmissionSnapshot) {
  return left.category===right.category && left.productId===right.productId && left.orderId===right.orderId && left.message===right.message
}
async function submit(){
  if(busy.value||!auth.authenticated)return
  const revision=contextRevision
  busy.value=true;error.value='';success.value=''
  const snapshot: SubmissionSnapshot={category:category.value,productId:productId.value||null,orderId:orderId.value||null,message:message.value.trim()}
  let submittedAsNew=false
  // Keep the same id when a response was lost so a retry remains idempotent.
  // If the user edits the content, use a fresh id and intentionally create a new ticket.
  if(submittedSnapshot.value && !sameSnapshot(submittedSnapshot.value,snapshot)){
    clientId.value=crypto.randomUUID()
    submittedSnapshot.value=null
    submittedAsNew=true
  }
  submittedSnapshot.value=snapshot
  const requestClientId=clientId.value
  try{
    const saved=await request<Ticket>('POST','/support/tickets',store.current.id,{clientId:requestClientId,...snapshot})
    if(disposed || revision!==contextRevision)return
    tickets.value=[saved,...tickets.value.filter(item=>item.id!==saved.id)];message.value='';clientId.value=crypto.randomUUID();submittedSnapshot.value=null
    success.value=`${submittedAsNew?'内容已变化，已按新留言保存。 ':''}留言 ${saved.number} 已保存，可在下方查看。当前尚未接入真人坐席。`
  }catch(e){if(!disposed && revision===contextRevision)error.value=errorText(e)}finally{if(!disposed && revision===contextRevision)busy.value=false}
}
watch(()=>[route.query.productId,route.query.orderId],()=>{
  const nextProductId=readProductId(route.query.productId), nextOrderId=readOrderId(route.query.orderId)
  if(nextProductId===productId.value && nextOrderId===orderId.value)return
  contextRevision+=1
  productId.value=nextProductId;orderId.value=nextOrderId
  product.value=undefined;order.value=undefined;tickets.value=[];message.value='';error.value='';loadError.value='';success.value='';busy.value=false;clientId.value=crypto.randomUUID();submittedSnapshot.value=null
  category.value=nextOrderId?'订单售后':nextProductId?'商品咨询':'其他问题'
  void load()
})
function showSelectionNotice(message: string) {
  selectionMessage.value=message
  if (!selectionDialog.value?.open) selectionDialog.value?.showModal()
}
async function viewRelatedProduct(ticket: Ticket) {
  if (checkingTicket.value) return
  const id=readProductId(ticket.productId)
  if (!id) { showSelectionNotice('你还没有选择汉服'); return }
  const revision=contextRevision
  checkingTicket.value=ticket.id
  try {
    await request<Product>('GET',`/products/${encodeURIComponent(id)}`,store.current.id)
    if (!disposed && revision===contextRevision) await router.push(`/product/${encodeURIComponent(id)}`)
  } catch (error) {
    if (!disposed && revision===contextRevision) showSelectionNotice(isAxiosError(error) && error.response?.status===404 ? '这件汉服已下架或不存在，请重新选择汉服。' : errorText(error))
  } finally {
    if (!disposed) checkingTicket.value=''
  }
}
onMounted(load);onBeforeUnmount(()=>{disposed=true})
</script>

<template>
  <div class="page support-page">
    <header class="support-heading"><p class="eyebrow">衣有所问 · 事有所应</p><h1>客服小馆</h1><p>从选衣到收下心仪的一袭，在这里找到下一步。</p></header>
    <div class="support-layout">
      <section class="support-main" aria-labelledby="faq-title">
        <div class="support-greeting panel"><span class="service-seal" aria-hidden="true">问</span><div><h2>你好，有什么可以帮你？</h2><p>常见问题即刻查看；需要补充说明，也可以留下咨询记录。</p><span class="service-mode">自助客服 · 留言服务</span></div></div>
        <section class="panel faq-panel"><div class="between"><h2 id="faq-title">常见问题</h2><span class="caption">点开查看答复</span></div><details v-for="(faq,index) in faqs" :key="faq.question" :open="index===0"><summary>{{ faq.question }}<span aria-hidden="true">＋</span></summary><div class="faq-answer"><p>{{ faq.answer }}</p><RouterLink :to="faq.link">{{ faq.label }} →</RouterLink></div></details></section>
        <section v-if="auth.authenticated" class="panel inquiry-history"><div class="between"><h2>我的咨询记录</h2><button class="text-button" :disabled="loading" @click="load">刷新记录</button></div><p v-if="loading" class="caption" role="status">正在读取记录…</p><p v-else-if="!tickets.length" class="empty-inquiries">还没有留言。你提交的咨询会保存在这里。</p><details v-for="ticket in tickets" :key="ticket.id" class="ticket"><summary><span>{{ ticket.category }}<small>{{ ticket.number }} · {{ new Date(ticket.createdAt).toLocaleString('zh-CN') }}</small></span><span class="ticket-state">{{ replies[ticket.id]?.status === 'REPLIED' ? '已回复' : '已记录' }}</span></summary><p class="ticket-message">{{ ticket.message }}</p><p v-if="replies[ticket.id]?.reply" class="notice">掌柜回复：{{ replies[ticket.id]?.reply }}</p><p v-else class="caption">留言已保存，等待掌柜回复。</p><RouterLink v-if="readOrderId(ticket.orderId)" :to="{path:'/orders',query:{orderId:readOrderId(ticket.orderId)}}">查看关联订单 →</RouterLink><button v-else type="button" class="related-product-button" :disabled="!!checkingTicket" @click="viewRelatedProduct(ticket)">{{ checkingTicket === ticket.id ? '正在查看…' : '查看关联衣裳 →' }}</button></details></section>
      </section>
      <aside class="panel inquiry-panel" aria-labelledby="inquiry-title">
        <p class="eyebrow">留一张问事笺</p><h2 id="inquiry-title">咨询留言</h2><p class="inquiry-intro">把遇到的问题写下来，稍后仍可在你的账号中查阅。</p>
        <div v-if="product||order" class="inquiry-context"><span>本次咨询</span><strong>{{ order ? order.orderNumber : product?.name }}</strong></div>
        <p v-if="loadError" class="error" role="alert">{{ loadError }}<button @click="load">重新读取</button></p>
        <form v-if="auth.authenticated" @submit.prevent="submit"><fieldset :disabled="busy"><label>问题类型<select v-model="category"><option v-for="item in categories" :key="item">{{ item }}</option></select></label><label>你想咨询什么？<textarea v-model="message" rows="6" minlength="5" maxlength="1000" required placeholder="例如：这件衣裳应如何选择尺码？请勿填写密码、支付验证码等信息。"/></label><span class="message-count">{{ message.length }} / 1000</span><button class="primary full" type="submit" :disabled="busy||message.trim().length<5||!!loadError">{{ busy?'正在保存…':'送出咨询留言' }}</button></fieldset></form>
        <div v-else class="support-login"><p>登录后，咨询记录会保存到你的账号。</p><button class="primary full" @click="auth.requestLogin(route.fullPath,'登录后可提交客服留言，并查看你的咨询记录。')">登录后留言</button></div>
        <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="error && submittedSnapshot" class="caption">可先刷新记录确认是否已保存。保持原内容重试不会重复留言；修改内容后再提交将视为新留言。</p><p v-if="success" class="inquiry-success" role="status">{{ success }}</p>
        <p class="support-disclosure">演示服务：常见问题可即时解答，留言真实保存在本站账号中。当前尚未接入真人坐席，不承诺实时人工回复。</p>
        <div class="support-links"><RouterLink to="/orders">购物订单 →</RouterLink><RouterLink to="/guide">搭配问衣 →</RouterLink></div>
      </aside>
    </div>
    <dialog ref="selectionDialog" class="selection-dialog" aria-labelledby="selection-title" aria-describedby="selection-message" @click="event => { if (event.target === selectionDialog) selectionDialog?.close() }">
      <div class="between"><h2 id="selection-title">衣裳提示</h2><button type="button" aria-label="关闭衣裳提示" @click="selectionDialog?.close()">×</button></div>
      <p id="selection-message">{{ selectionMessage }}</p>
      <div class="actions"><button type="button" autofocus @click="selectionDialog?.close()">我知道了</button><RouterLink class="button primary" to="/culture" @click="selectionDialog?.close()">去选汉服 →</RouterLink></div>
    </dialog>
  </div>
</template>

<style scoped>
.related-product-button{border:0;background:none;padding:0;color:var(--red);font-size:12px;margin-top:4px}.related-product-button:disabled{opacity:.6}.selection-dialog{width:min(420px,calc(100vw - 32px));padding:26px}.selection-dialog h2{margin:0;font-size:23px}.selection-dialog .between>button{border:0;background:none;padding:4px 10px;font-size:23px}.selection-dialog>p{margin:25px 0;line-height:1.8}.selection-dialog .actions{justify-content:flex-end;flex-wrap:wrap}
.support-page{max-width:1160px}.support-heading{text-align:center;margin:26px 0 48px}.support-heading h1{font-size:40px;margin:14px 0}.support-heading>p:last-child{color:var(--muted);font-size:14px}.support-layout{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(310px,1fr);gap:28px;align-items:start}.support-main{display:grid;gap:24px}.support-greeting{display:flex;gap:22px;align-items:center;padding:28px;background:linear-gradient(120deg,#eee7da,#fbfaf5)}.service-seal{border:1px solid var(--gold);color:var(--red);font:30px SimSun,serif;border-radius:40px 40px 8px 8px;padding:17px 11px}.support-greeting h2{font-size:23px;margin:0 0 10px}.support-greeting p{font-size:12px;color:var(--muted);line-height:1.8;margin:0}.service-mode{display:inline-block;font-size:10px;color:var(--wood);margin-top:12px}.faq-panel,.inquiry-history,.inquiry-panel{padding:28px}.support-page h2{font-size:23px}.faq-panel .between h2,.inquiry-history .between h2{margin:0 0 14px}.faq-panel details{border-top:1px solid var(--line)}.faq-panel summary{display:flex;justify-content:space-between;gap:16px;cursor:pointer;padding:20px 0;font-size:14px;list-style:none}.faq-panel summary::-webkit-details-marker,.ticket summary::-webkit-details-marker{display:none}.faq-panel details[open] summary{color:var(--red)}.faq-panel details[open] summary span{transform:rotate(45deg)}.faq-answer{padding:0 0 21px}.faq-answer p{margin:0 0 14px;font-size:13px;line-height:1.95;color:var(--muted)}.faq-answer a,.support-links a,.ticket>a{font-size:12px;color:var(--red)}.inquiry-panel{position:sticky;top:24px}.inquiry-panel h2{font-size:28px;margin:12px 0}.inquiry-intro{color:var(--muted);font-size:12px;line-height:1.8;margin-bottom:24px}.inquiry-panel fieldset{border:0;padding:0;margin:0}.inquiry-panel label{display:block;font-size:12px;color:var(--wood);margin:18px 0 0}.inquiry-panel select,.inquiry-panel textarea{display:block;width:100%;margin-top:9px;background:var(--silk);font-size:13px}.inquiry-panel textarea{resize:vertical;line-height:1.8;min-height:130px}.message-count{display:block;text-align:right;font-size:10px;color:var(--muted);margin:5px 0 18px}.inquiry-context{border-left:2px solid var(--gold);background:#f1ece3;padding:13px 16px;display:grid;gap:6px;font-size:12px}.inquiry-context span{color:var(--muted);font-size:10px}.support-disclosure{font-size:11px;color:var(--muted);line-height:1.8;margin:22px 0}.support-links{display:flex;gap:24px;border-top:1px solid var(--line);padding-top:18px}.inquiry-success{padding:12px;border-radius:8px;background:#eaf0e6;color:#3d654d;font-size:12px;line-height:1.8}.ticket{border-top:1px solid var(--line);padding:17px 0}.ticket summary{list-style:none;display:flex;justify-content:space-between;cursor:pointer;align-items:center;font-size:14px;gap:10px}.ticket summary small{display:block;font-size:10px;font-weight:400;color:var(--muted);margin-top:7px}.ticket-state{font-size:10px;color:var(--wood);white-space:nowrap;border:1px solid var(--line);padding:4px 7px;border-radius:5px}.ticket-message{font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.empty-inquiries,.support-login p{font-size:12px;color:var(--muted);line-height:1.8}.text-button{border:0;padding:4px;background:none;font-size:11px;color:var(--wood)}@media(max-width:850px){.support-layout{grid-template-columns:1fr}.inquiry-panel{position:static}.support-heading{margin-bottom:30px}.support-heading h1{font-size:32px}.support-greeting,.faq-panel,.inquiry-history,.inquiry-panel{padding:22px}}
</style>
