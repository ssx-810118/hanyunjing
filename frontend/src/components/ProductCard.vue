<script setup lang="ts">
import type { Product } from '../types'
import { money } from '../types'
import ProductArt from './ProductArt.vue'
import ProductPurchaseActions from './ProductPurchaseActions.vue'
defineProps<{ product: Product }>()
</script>
<template>
  <article class="product-card">
    <RouterLink :to="`/product/${product.id}`" :aria-label="`查看${product.name}`"><ProductArt :product="product"/></RouterLink>
    <div class="product-copy">
      <RouterLink :to="`/product/${product.id}`"><p class="eyebrow">{{ product.tags.join(' · ') }}</p><h3>{{ product.name }}</h3></RouterLink>
      <p class="card-price">{{ product.skus.length ? money(Math.min(...product.skus.map(s => s.price))) + ' 起' : '暂无可售规格' }}</p>
      <ProductPurchaseActions :product="product" compact/>
      <RouterLink class="view-product" :to="`/product/${product.id}`">查看衣裳 ↗</RouterLink>
    </div>
  </article>
</template>
<style scoped>
.product-card{display:flex;flex-direction:column}.product-copy{display:flex;flex-direction:column;flex:1}.product-copy h3{margin-top:8px}.card-price{font-size:12px;margin:0 0 auto}.view-product{display:block;text-align:center;font-size:12px;color:var(--wood);padding:4px 0}
</style>
