<script setup lang="ts">
import { computed, ref } from 'vue'
import { traceLabel } from '../composables/useTrace'
import type { TraceEvent } from '../types'
const props = defineProps<{ events: TraceEvent[]; state: string; error?: string; paused?: boolean }>()
defineEmits<{ pause: []; reconnect: [] }>()
const expanded = ref(false)
const visible = computed(() => props.events.slice(expanded.value ? -100 : -8).reverse())
</script>
<template>
  <section class="trace-panel">
    <div class="between"><h3>决策留痕</h3><span class="tag">真实事件</span></div>
    <p role="status">{{ state }}</p>
    <div class="actions"><button v-if="!paused" type="button" @click="$emit('pause')">暂停追踪</button><button type="button" @click="$emit('reconnect')">{{ paused ? '恢复追踪' : '刷新连接' }}</button></div>
    <p v-if="error" role="status" class="trace-error">{{ error }}</p>
    <p v-if="!events.length" class="muted">暂未收到本会话记录；发送需求后显示真实处理步骤。</p>
    <ol v-else class="trace-list" aria-label="最近的真实决策记录">
      <li v-for="event in visible" :key="event.id"><small>{{ new Date(event.timestamp).toLocaleTimeString('zh-CN') }}</small><strong :title="event.type">{{ traceLabel(event.type) }}</strong><p>{{ event.summary }}</p></li>
    </ol>
    <button v-if="events.length > 8" type="button" class="trace-more" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起，只看最近8条' : `查看更多（最近${Math.min(events.length, 100)}条）` }}</button>
    <p class="trace-footnote">仅展示服务端记录。刷新追踪只读取状态，不重新问衣或生成图片。</p>
  </section>
</template>
<style scoped>
.trace-panel h3{font-size:21px;margin:8px 0}.trace-panel>.between{gap:8px}.trace-panel .actions{gap:7px;margin:12px 0}.trace-panel .actions button{font-size:11px;padding:7px 10px;min-height:36px}.trace-panel .trace-list{max-height:360px;overflow-y:auto;padding:0 7px 0 15px;margin:14px 0}.trace-list li{padding-bottom:10px;list-style:none}.trace-list strong{display:block;font-size:12px;margin:3px 0;color:var(--wood)}.trace-list p{font-size:11px;line-height:1.8;margin:5px 0;white-space:pre-wrap}.trace-error{font-size:11px;color:var(--red);overflow-wrap:anywhere}.trace-more{width:100%;font-size:11px;min-height:36px;padding:6px}.trace-footnote{font-size:10px!important;line-height:1.8;color:var(--muted);margin-top:14px}
</style>
