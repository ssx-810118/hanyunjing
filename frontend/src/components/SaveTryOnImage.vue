<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { saveLocalFile } from '../download'
const props = defineProps<{url: string; taskId: string}>()
const busy = ref(false), error = ref(''), notice = ref('')
let controller: AbortController | undefined
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''; controller = new AbortController()
  try {
    await saveLocalFile(props.url, `汉韵镜-试穿-${props.taskId.replace(/[^A-Za-z0-9_-]/g, '')}.png`, 'image/png', controller.signal)
    if (!controller.signal.aborted) notice.value = '已发起下载，请在浏览器下载列表查看。'
  } catch (e) { if (!controller.signal.aborted) error.value = e instanceof Error ? e.message : '下载失败，请重试。' }
  finally { busy.value = false }
}
onBeforeUnmount(() => controller?.abort())
</script>
<template>
  <div class="save-tryon">
    <button type="button" class="primary" :disabled="busy" @click="save">{{ busy ? '正在保存…' : '保存图片到本地' }}</button>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="notice" class="caption" role="status">{{ notice }}</p>
  </div>
</template>
<style scoped>.save-tryon{margin:14px 0}.save-tryon p{margin:8px 0;font-size:12px}</style>
