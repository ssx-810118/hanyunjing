<script setup lang="ts">
withDefaults(defineProps<{ loading?: boolean; error?: string; empty?: boolean; emptyText?: string }>(), { emptyText: '暂时还没有内容' })
defineEmits<{ retry: [] }>()
</script>
<template><div v-if="loading" class="state" role="status"><span class="loading-mark">❖</span> 正在取来，请稍候…</div><div v-else-if="error" class="state error" role="alert"><p>{{ error }}</p><button @click="$emit('retry')">重新尝试</button></div><div v-else-if="empty" class="state"><span class="seal">待</span><p>{{ emptyText }}</p></div><slot v-else /></template>
