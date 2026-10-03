<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { request,errorText } from '../api'
import { useSession } from '../stores/session'
import type { Recommendation,Sku } from '../types'
import { money } from '../types'
import { sourceLinks } from '../retail'
const props=defineProps<{rec:Recommendation;runId:string|null;quantity:number}>()
const store=useSession(),selected=ref(''),busy=ref(false),error=ref(''),confirmed=ref(false),dialog=ref<HTMLDialogElement>()
const sku=computed(()=>props.rec.eligibleSkus.find(s=>s.id===selected.value))
async function choose(){error.value='';if(!sku.value)return;await nextTick();dialog.value?.showModal()}
async function confirm(){
  if(busy.value||!sku.value||!props.runId)return
  busy.value=true;error.value=''
  try{await request('POST','/retail/runs/'+props.runId+'/confirm',undefined,{skuId:sku.value.id});confirmed.value=true;dialog.value?.close();store.confirmCartAddition('已确认选购并加入衣囊。')}
  catch(e){error.value=errorText(e)}finally{busy.value=false}
}
function label(s:Sku){return s.color+' / '+s.size+' · '+money(s.price)+' · 库存 '+s.stock}
</script>
<template>
  <div class="retail-selection">
    <p class="caption">预算范围：本款 {{ quantity }} 件，不含配饰。确认时重新核对价格与库存。</p>
    <label>选择有货规格<select v-model="selected" :disabled="busy||confirmed" aria-label="选择有货规格"><option value="" disabled>请选择颜色与尺码</option><option v-for="s in rec.eligibleSkus" :key="s.id" :value="s.id">{{ label(s) }}</option></select></label>
    <div class="between"><strong v-if="sku">本次合计 {{ money(sku.price*quantity) }}</strong><button v-if="!confirmed" type="button" class="primary" :disabled="!sku||!runId||busy" @click="choose">确认选购</button><RouterLink v-else class="button primary" to="/cart">已加入衣囊 · 去查看</RouterLink></div>
    <details v-if="rec.evidence.length" class="sources"><summary>本款历史依据 · {{ rec.evidence.length }} 条</summary><article v-for="a in rec.evidence" :key="a.id"><strong>{{ a.title }}</strong><p>{{ a.content }}</p><small>{{ a.source }}</small><a v-for="url in sourceLinks(a.source)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">打开来源 ↗</a></article><p class="caption">资料说明历史参考；当前商品的配色、纹样与裁剪不等同于文物复原。</p></details>
    <p v-else class="caption">本款尚未关联可核查史料，不作历史复原承诺。</p>
    <dialog ref="dialog" class="confirm-selection" @cancel.prevent="!busy&&dialog?.close()"><h2>确认加入衣囊</h2><p>{{ rec.product.name }}</p><p>{{ sku?.color }} / {{ sku?.size }} × {{ quantity }}</p><strong>{{ sku?money(sku.price*quantity):'' }}</strong><p class="caption">此操作加入衣囊，不会扣款。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="actions"><button type="button" :disabled="busy" @click="dialog?.close()">取消</button><button type="button" class="primary" :disabled="busy" @click="confirm">{{ busy?'核对中…':'确认加入衣囊' }}</button></div></dialog>
  </div>
</template>
<style scoped>
.retail-selection{border-top:1px solid var(--line);padding-top:12px;margin-top:14px}.retail-selection label{display:block}.retail-selection select{width:100%;margin:7px 0 14px}.sources{margin-top:16px}.sources article{padding:14px 0;border-bottom:1px solid var(--line)}.sources p{font-size:13px;line-height:1.8}.sources small{display:block;overflow-wrap:anywhere}.sources a{display:inline-block;font-size:12px;color:var(--wood);margin-top:8px}.confirm-selection{width:min(450px,90vw);padding:28px}.confirm-selection .actions{margin-top:20px;justify-content:flex-end}
</style>
