<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { request } from '../api'
import { useSession } from '../stores/session'
import { useAsync } from '../composables/useAsync'
import type { Product, Scene } from '../types'
import PalaceArt from '../components/PalaceArt.vue'
import ProductCard from '../components/ProductCard.vue'
import EmptyErrorLoading from '../components/EmptyErrorLoading.vue'
const store = useSession(), products = ref<Product[]>([]), scenes = ref<Scene[]>([])
const { loading, error, run } = useAsync()
const load = () => run(async () => { [products.value, scenes.value] = await Promise.all([request<Product[]>('GET','/products',store.activeId),request<Scene[]>('GET','/scenes',store.activeId)]) })
onMounted(load)
</script>
<template><div class="page"><section class="hero"><div class="hero-copy"><p class="eyebrow">长安有衣 · 为你而选</p><h1>一键试穿，<br>见你着汉家衣裳</h1><p class="hero-sub">从一处长安风景，到一身恰好的衣裳。<br>问衣、识礼、照镜，让初见也从容。</p><div class="actions"><RouterLink class="button primary" to="/tryon">入镜试衣 <span>↗</span></RouterLink><RouterLink class="button" to="/guide">让问衣使为我挑选</RouterLink></div><p class="caption">AI 照片换装预览 · 效果仅供搭配参考</p></div><PalaceArt/><span class="vertical-note">一衣一会 · 镜见长安</span></section><section class="entry-grid"><RouterLink to="/guide" class="entry"><span class="entry-number">壹 / 问衣</span><h2>不知如何选？<br>说说你的长安之行</h2><p>在线模型查询工具后推荐，至多三套；未配置时明确提示，不离线代答。</p><span>与问衣使聊聊 →</span></RouterLink><RouterLink to="/tryon" class="entry"><span class="entry-number">贰 / 照镜</span><h2>衣裳心中有数，<br>镜里再看一眼</h2><p>明确授权、可随时删除的照片试穿。</p><span>打开菱花镜 →</span></RouterLink><RouterLink to="/culture" class="entry"><span class="entry-number">叁 / 识衣</span><h2>知衣冠来处，<br>赴一场千年之约</h2><p>区分史实、通行说法与日常建议。</p><span>翻阅衣冠志 →</span></RouterLink></section><EmptyErrorLoading :loading="loading" :error="error" @retry="load"><section class="section"><div class="section-heading"><div><p class="eyebrow">循景寻衣</p><h2>今日，想去长安何处？</h2></div><RouterLink to="/guide">问一问再出发 ↗</RouterLink></div><div class="scene-grid"><RouterLink v-for="(scene,index) in scenes" :key="scene.id" :to="{path:'/guide',query:{prompt:`我想去${scene.name}，请帮我选衣裳`}}" class="scene-card"><span class="scene-motif" aria-hidden="true">{{ ['亭','阙','宫','巷','塔'][index % 5] }}</span><h3>{{ scene.name }}</h3><p>{{ scene.advice }}</p></RouterLink></div><p v-if="!scenes.length" class="state">暂无场景</p></section><section class="section"><div class="section-heading"><div><p class="eyebrow">衣橱小览</p><h2>从这一袭，走入长安</h2></div><RouterLink to="/culture#products">查看各朝全部衣裳 ↗</RouterLink></div><div class="product-grid"><ProductCard v-for="product in products.filter(p => p.category !== '配饰').slice(0,4)" :key="product.id" :product="product"/></div><p v-if="!products.length" class="state">暂无商品</p></section></EmptyErrorLoading></div></template>
