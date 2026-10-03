<script setup lang="ts">
import { ref, watch } from 'vue'
const props = withDefaults(defineProps<{ original: string | null; result: string | null; sample?: boolean }>(), { sample: false })
const mode = ref<'pair' | 'original' | 'result'>('pair')
const originalFailed = ref(false), resultFailed = ref(false)
watch(() => [props.original, props.result], () => {
  originalFailed.value = false
  resultFailed.value = false
  mode.value = props.original ? 'pair' : 'result'
}, { immediate: true })
const modes = [{ value: 'pair', label: '并排对比' }, { value: 'original', label: '原图' }, { value: 'result', label: '试穿效果' }] as const
</script>
<template>
  <section class="compare" aria-label="原图与试穿效果对比">
    <div class="compare-toolbar">
      <span class="compare-title">{{ sample ? '一眼看见，换装前后' : '这一刻，衣裳上身' }}</span>
      <div class="compare-modes" role="group" aria-label="选择图片查看方式">
        <button v-for="item in modes.filter(item => original || item.value === 'result')" :key="item.value" type="button" :aria-pressed="mode === item.value" @click="mode = item.value">{{ item.label }}</button>
      </div>
    </div>
    <div class="compare-images" :class="{ single: mode !== 'pair' }">
      <figure v-if="mode !== 'result' && original" class="compare-photo">
        <figcaption><span>原图</span><small>{{ sample ? 'AI 生成示例人物' : '本次上传的人像' }}</small></figcaption>
        <img v-if="!originalFailed" :src="original" :alt="sample ? 'AI 生成的示例人物，换装前' : '本次上传的人像原图'" @error="originalFailed = true">
        <div v-else class="state photo-error"><p>原图暂时无法显示，可能已到期或被删除。</p></div>
      </figure>
      <figure v-if="mode !== 'original'" class="compare-photo result-photo">
        <figcaption><span>试穿效果</span><small>{{ sample ? 'AI 换装示例' : 'AI 生成预览' }}</small></figcaption>
        <img v-if="result && !resultFailed" :src="result" :alt="sample ? 'AI 示例人物身穿明代月白袄马面裙的换装效果' : '根据上传人像与所选服饰生成的 AI 换装预览'" @error="resultFailed = true">
        <div v-else class="state photo-error"><p>{{ sample ? '示例换装图尚未生成，目前没有可展示的试穿结果。' : resultFailed ? '结果暂时无法显示，可能已到期或被删除。' : '试穿结果尚未生成。' }}</p></div>
      </figure>
    </div>
    <p class="compare-note">{{ sample ? resultFailed ? '示例人物由 AI 生成；换装结果尚未完成。' : '示例人物与换装效果均由 AI 生成，不对应真实用户。' : '原图与结果仅供本次会话查看，请在到期前查看或删除。' }}</p>
  </section>
</template>
<style scoped>
.compare{border:1px solid var(--line);border-radius:20px;background:var(--silk);overflow:hidden;box-shadow:0 12px 40px #382d1a08}
.compare-toolbar{display:flex;justify-content:space-between;align-items:center;gap:14px;padding:20px 22px;flex-wrap:wrap}
.compare-title{font-family:SimSun,serif;color:var(--wood);font-size:18px;letter-spacing:.08em}
.compare-modes{display:flex;padding:4px;gap:3px;background:#f0ede5;border:1px solid #e4ded1;border-radius:12px}
.compare-modes button{border:0;border-radius:8px;background:transparent;font-size:12px;padding:8px 12px;min-height:38px;white-space:nowrap}
.compare-modes button[aria-pressed=true]{background:var(--silk);color:var(--red);box-shadow:0 2px 7px #45371e0b}
.compare-images{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px;background:#ded7c9}
.compare-images.single{grid-template-columns:minmax(0,1fr)}
.compare-photo{min-width:0;background:#f2eee5;position:relative}
.compare-photo figcaption{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:11px 16px;background:#f8f5ef;font-size:13px;color:#675844}
.compare-photo figcaption span{font-weight:600;white-space:nowrap}
.result-photo figcaption{background:#eaece4;color:var(--green)}
.compare-photo img{width:100%;aspect-ratio:2/3;object-fit:contain;background:#eeebe4}
.single .compare-photo img{max-height:780px;aspect-ratio:auto;min-height:300px}
.compare-note{margin:0;padding:14px 20px;font-size:11px;color:var(--muted);text-align:center}
.photo-error{min-height:360px;border:0;margin:0;font-size:13px}
@media(max-width:600px){.compare-toolbar{padding:16px;gap:10px}.compare-title{font-size:16px}.compare-modes{width:100%}.compare-modes button{flex:1;padding:8px 7px}.compare-photo figcaption{padding:10px 8px;display:block;font-size:12px}.compare-photo figcaption small{display:block;font-size:10px;margin-top:2px}.compare-note{font-size:10px;padding:12px}.photo-error{padding:15px;min-height:220px}.single .compare-photo img{min-height:240px}}
</style>
