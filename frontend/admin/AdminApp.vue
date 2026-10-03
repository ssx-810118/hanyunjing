<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useAuth } from '../src/stores/auth'
import { errorText } from '../src/api'
const auth=useAuth(),error=ref('')
async function logout(){try{await auth.logout()}catch(e){error.value=errorText(e)}}
onMounted(()=>{auth.startSynchronization();auth.initialize().catch(e=>error.value=errorText(e))})
onBeforeUnmount(()=>auth.stopSynchronization())
</script>
<template><header class="admin-header"><a href="/" class="brand"><span class="brand-seal">汉</span><span>汉韵镜<small>商家管理工作台</small></span></a><div><span v-if="auth.authenticated">{{ auth.user?.displayName }}</span><button v-if="auth.authenticated" @click="logout">退出管理账号</button><a class="button" href="http://127.0.0.1:5173" target="_blank" rel="noopener">查看商城 ↗</a></div></header><p v-if="error" role="alert" class="error">{{ error }}</p><RouterView :key="auth.user?.id || 'admin-login'"/></template>
<style scoped>.admin-header{display:flex;justify-content:space-between;align-items:center;padding:22px max(24px,calc((100vw - 1340px)/2));background:var(--silk);border-bottom:1px solid var(--line);gap:20px}.admin-header>div{display:flex;gap:18px;align-items:center;font-size:14px}@media(max-width:650px){.admin-header{flex-wrap:wrap;padding:18px}.brand{font-size:25px}}</style>
