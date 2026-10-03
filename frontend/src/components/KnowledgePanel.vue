<script setup lang="ts">
import { kindNames } from '../types'
import type { KnowledgeResult } from '../types'
defineProps<{ knowledge: KnowledgeResult }>()

function sourceParts(source: string): { text: string; href?: string }[] {
  return source.split(/(https?:\/\/[^\s<>"|，。；、]+)/gi).filter(Boolean).map(text => {
    if (!/^https?:\/\//i.test(text)) return { text }
    try {
      const url = new URL(text)
      return ['http:', 'https:'].includes(url.protocol) ? { text, href: url.href } : { text }
    } catch { return { text } }
  })
}
</script>
<template><section class="knowledge"><p v-if="knowledge.abstained" class="notice">暂不下结论：{{ knowledge.reason }}</p><p v-if="knowledge.humanRequired" class="notice">涉及正式礼仪，请交由人工核验。</p><details v-for="hit in knowledge.hits" :key="hit.article.id"><summary><span class="tag">{{ kindNames[hit.article.kind] }}</span> {{ hit.article.title }}</summary><p>{{ hit.article.content }}</p><small>来源：<template v-for="(part,index) in sourceParts(hit.article.source)" :key="index"><a v-if="part.href" :href="part.href" target="_blank" rel="noopener noreferrer" :aria-label="`查看来源：${hit.article.title}`">查看来源 ↗</a><template v-else>{{ part.text }}</template></template> · 检索得分 {{ hit.score }}</small><p class="muted">本地演示资料标签，不代表经过历史学审核。</p></details></section></template>

<style scoped>
.knowledge small a{text-decoration:underline;text-underline-offset:3px;color:var(--wood)}
</style>
