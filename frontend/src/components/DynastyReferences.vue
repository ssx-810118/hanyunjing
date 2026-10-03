<script setup lang="ts">
import { computed } from 'vue'
import { dynastyReferences } from '../data/dynastyReferences'

const props = defineProps<{ dynasty: string }>()
const references = computed(() => dynastyReferences[props.dynasty] ?? [])
</script>

<template>
  <section v-if="references.length" class="dynasty-references" aria-labelledby="dynasty-references-heading">
    <header class="reference-heading">
      <div>
        <p class="eyebrow">有据可循 · 从实物与图像读起</p>
        <h2 id="dynasty-references-heading">{{ dynasty }}代代表服饰</h2>
      </div>
      <span class="tag reference-count">{{ references.length }} 项实例</span>
    </header>
    <p class="reference-intro">以下为有出处的代表实例，并非朝代独有形制清单，历史服饰与现代商品分别展示。<template v-if="dynasty === '元'">元代部分包含蒙古服饰背景，不将各民族衣冠混为一种形制。</template></p>
    <div class="reference-grid" :key="dynasty">
      <article v-for="(item, index) in references" :key="item.id" class="reference-card">
        <div class="reference-topline">
          <p class="eyebrow">{{ item.evidence }}</p>
          <span class="reference-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
        </div>
        <h3>{{ item.title }}</h3>
        <p class="reference-description">{{ item.description }}</p>
        <aside class="reference-note" aria-label="辨识提示">
          <p class="reference-note-label">辨识提示</p>
          <p>{{ item.note }}</p>
        </aside>
        <footer class="reference-source">
          <a :href="item.sourceUrl" target="_blank" rel="noopener noreferrer" :aria-label="`查看${item.title}的出处：${item.source}，在新窗口打开`">出处：{{ item.source }} <span aria-hidden="true">↗</span><span class="source-window">（新窗口）</span></a>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.dynasty-references{margin:38px 0 30px}
.reference-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:20px}
.reference-heading .eyebrow{margin:0 0 12px}
.reference-heading h2{margin:0;font-size:clamp(22px,2.4vw,30px)}
.reference-count{flex-shrink:0;margin-top:10px}
.reference-intro{margin:0 0 20px;color:var(--muted);font-size:14px;line-height:1.8}
.reference-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:22px}
.reference-card{display:flex;flex-direction:column;min-width:0;padding:26px;border:1px solid var(--line);border-radius:14px;background:var(--silk)}
.reference-topline{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:24px}
.reference-topline .eyebrow{margin:0;font-size:11px;line-height:1.8}
.reference-number{color:var(--gold);font-family:Georgia,serif;font-size:28px;line-height:1}
.reference-card h3{margin:0 0 18px;font-size:clamp(19px,2vw,25px);line-height:1.55;font-weight:400}
.reference-description{margin:0 0 24px;font-size:14px;line-height:1.9}
.reference-note{padding-left:13px;margin-top:auto;border-left:2px solid var(--gold);color:var(--muted);font-size:13px;line-height:1.85}
.reference-note p{margin:0}
.reference-note .reference-note-label{margin-bottom:5px;color:var(--wood)}
.reference-source{margin-top:20px;padding-top:15px;border-top:1px solid var(--line);font-size:12px;line-height:1.85;overflow-wrap:anywhere}
.reference-source a{color:var(--wood);text-decoration:underline;text-underline-offset:4px}
.reference-source a:hover{color:var(--red)}
.reference-source a:focus-visible{outline:2px solid var(--red);outline-offset:4px;border-radius:2px}
.source-window{font-size:11px;white-space:nowrap}
@media(max-width:680px){.dynasty-references{margin-top:28px}.reference-grid{grid-template-columns:minmax(0,1fr);gap:16px}.reference-card{padding:22px}.reference-topline{margin-bottom:18px}.reference-heading{gap:8px}.reference-heading h2{font-size:24px}.reference-count{font-size:11px}.reference-intro{font-size:13px}.reference-card h3{font-size:22px}}
</style>
