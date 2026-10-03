<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Product } from '../types'
const props = defineProps<{ product: Product }>()
const failed = ref(false)
watch(() => [props.product.id, props.product.images[0]], () => { failed.value = false })
</script>

<template>
  <figure class="product-art">
    <img v-if="product.images[0] && !failed" :src="product.images[0]" :alt="`${product.name}，AI 设计图，非实拍、非文物复原`" loading="lazy" @error="failed = true">
    <div v-else class="state">商品图暂不可用<button v-if="product.images[0]" type="button" @click.prevent="failed = false">重载图片</button></div>
    <figcaption><span>{{ product.dynasty }} · {{ product.form }}</span><span class="art-kind">AI 设计图</span><small>非实拍 · 非文物复原</small></figcaption>
  </figure>
</template>

<style scoped>
.product-art img{object-fit:contain;mix-blend-mode:normal;background:#f7f4ef}
.product-art figcaption{position:static;display:flex;flex-wrap:wrap;align-items:center;gap:4px 8px}
.product-art figcaption>span:first-child{flex:1;min-width:0}
.product-art .art-kind{white-space:nowrap}
.product-art figcaption small{flex-basis:100%;font-size:10px;line-height:1.5}
</style>
