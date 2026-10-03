<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from './stores/session'
import { useAuth } from './stores/auth'
import { errorText } from './api'
import AuthDialog from './components/AuthDialog.vue'
import CartAddedNotice from './components/CartAddedNotice.vue'
const store = useSession(), auth = useAuth(), router = useRouter(), route = useRoute()
const menuOpen = ref(false), menu = ref<HTMLElement>(), menuButton = ref<HTMLButtonElement>(), signingOut = ref(false)
const nav = computed(() => [{ to: '/', label: '首页' }, { to: '/tryon', label: '试穿' }, { to: '/guide', label: '问答' }, { to: '/cart', label: '衣囊' }, { to: '/profile', label: auth.authenticated ? '我的' : '登录' }])
function login() { void auth.requestLogin() }
function dismiss(event: MouseEvent) { if (!menu.value?.contains(event.target as Node)) menuOpen.value = false }
function keyboard(event: KeyboardEvent) { if (event.key === 'Escape' && menuOpen.value) { menuOpen.value = false; menuButton.value?.focus() } }
async function logout() {
  if (signingOut.value) return
  signingOut.value = true
  try { await auth.logout(); menuOpen.value = false; await router.push('/'); store.notice = '已退出登录。' }
  catch (error) { store.notice = errorText(error) }
  finally { signingOut.value = false }
}
watch(() => route.fullPath, () => { menuOpen.value = false })
onMounted(() => {
  document.addEventListener('click', dismiss); document.addEventListener('keydown', keyboard)
  auth.startSynchronization()
  auth.initialize().catch(error => { store.notice = errorText(error) })
})
onBeforeUnmount(() => { document.removeEventListener('click', dismiss); document.removeEventListener('keydown', keyboard); auth.stopSynchronization() })
</script>

<template>
  <a class="skip-link" href="#main">跳至正文</a>
  <header class="site-header">
    <RouterLink to="/" class="brand"><span class="brand-seal">汉</span><span>汉韵镜<small>一衣入长安</small></span></RouterLink>
    <nav aria-label="主导航"><RouterLink to="/">宫门</RouterLink><RouterLink to="/culture">衣冠志</RouterLink><RouterLink to="/guide">问衣</RouterLink><RouterLink to="/tryon">试衣镜</RouterLink></nav>
    <div class="header-end">
      <RouterLink to="/cart">衣囊</RouterLink>
      <button v-if="!auth.authenticated" class="account-trigger login-trigger" @click="login">登录</button>
      <div v-else ref="menu" class="account-menu">
        <button ref="menuButton" class="account-trigger" aria-haspopup="menu" :aria-expanded="menuOpen" aria-controls="account-dropdown" @click="menuOpen = !menuOpen">我的 <span class="menu-chevron" :class="{opened:menuOpen}" aria-hidden="true">▾</span></button>
        <div v-if="menuOpen" id="account-dropdown" class="account-dropdown" role="menu" aria-label="我的账号">
          <p class="account-name">{{ auth.user?.displayName || auth.user?.username }}</p>
          <RouterLink to="/profile" role="menuitem" @click="menuOpen = false"><span aria-hidden="true">○</span>个人中心</RouterLink>
          <RouterLink to="/orders" role="menuitem" @click="menuOpen = false"><span aria-hidden="true">▤</span>购物订单</RouterLink>
          <RouterLink to="/reception" role="menuitem" @click="menuOpen = false"><span aria-hidden="true">◇</span>选购记录与商家回复</RouterLink>
          <RouterLink to="/support" role="menuitem" @click="menuOpen = false"><span aria-hidden="true">◇</span>咨询客服</RouterLink>
          <button role="menuitem" class="logout-item" :disabled="signingOut" @click="logout"><span aria-hidden="true">⏻</span>{{ signingOut ? '正在退出…' : '退出登录' }}</button>
        </div>
      </div>
    </div>
  </header>
  <div class="session-bar"><span>长安相逢 · {{ auth.user?.displayName || auth.user?.username || '欢迎来访' }}</span></div>
  <div v-if="store.notice" class="toast" role="status">{{ store.notice }}<button @click="store.notice = ''" aria-label="关闭提示">×</button></div>
  <main id="main" tabindex="-1"><RouterView v-slot="{ Component, route: viewRoute }"><component :is="Component" v-if="!viewRoute.meta.requiresAuth || auth.authenticated" :key="`${store.activeId}:${viewRoute.path}`"/><div v-else class="page narrow center"><div class="panel"><h1>登录后继续</h1><p>问衣、试穿与账号资料需要登录。</p><button class="primary" @click="auth.requestLogin(route.fullPath)">登录</button></div></div></RouterView></main>
  <footer class="site-footer"><div><strong>汉韵镜</strong><p>衣有来处，美有回响。</p></div><div class="footer-links"><RouterLink to="/support">咨询客服 →</RouterLink><RouterLink to="/profile">资料与人像管理 →</RouterLink></div></footer>
  <RouterLink v-if="route.path !== '/support'" class="support-float" to="/support" aria-label="咨询客服"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 17 3 21l6-2a9 9 0 1 0-4-2Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M8 10h8M8 14h5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg><span>咨询客服</span></RouterLink>
  <nav class="bottom-nav" aria-label="底部导航"><template v-for="(item,index) in nav" :key="item.to"><button v-if="index === 4 && !auth.authenticated" class="bottom-login" @click="login"><span aria-hidden="true">○</span>登录</button><RouterLink v-else :to="item.to"><span aria-hidden="true">{{ ['⌂','◇','☷','♧','○'][index] }}</span>{{ item.label }}</RouterLink></template></nav>
  <AuthDialog/>
  <CartAddedNotice/>
</template>

<style scoped>
.footer-links{display:grid;gap:12px}.support-float{position:fixed;right:22px;bottom:88px;z-index:35;display:flex;align-items:center;gap:7px;padding:12px 16px;background:var(--silk);color:var(--red);border:1px solid var(--line);border-radius:28px;box-shadow:0 6px 22px #30261914;font-size:12px}.support-float:hover{background:#f0e7da}@media(max-width:600px){.support-float{right:12px;bottom:80px;padding:10px}.support-float span{font-size:11px}}
.toast{position:relative;top:auto;right:auto;width:fit-content;margin:12px 20px 0 auto}
.account-trigger{background:none;border:0;padding:7px 3px;border-radius:5px;gap:7px;white-space:nowrap}.login-trigger{color:var(--red)}.account-menu{position:relative}.menu-chevron{font-size:14px;transition:transform .15s}.menu-chevron.opened{transform:rotate(180deg)}.account-dropdown{position:absolute;top:calc(100% + 10px);right:-10px;z-index:45;min-width:172px;padding:8px;background:var(--silk);border:1px solid var(--line);border-radius:12px;box-shadow:0 10px 30px #30261920}.account-dropdown:before{content:'';position:absolute;right:27px;top:-6px;width:10px;height:10px;background:var(--silk);border-top:1px solid var(--line);border-left:1px solid var(--line);transform:rotate(45deg)}.account-name{margin:3px 8px 7px;font-size:12px;color:var(--muted);max-width:180px;overflow-wrap:anywhere}.account-dropdown a,.account-dropdown button{width:100%;border:0;border-radius:6px;justify-content:flex-start;gap:10px;padding:10px 12px;font-size:14px;text-align:left;background:transparent}.account-dropdown a:hover,.account-dropdown button:hover{background:#f1e8db;color:var(--red)}.account-dropdown .logout-item{margin-top:6px;border-top:1px solid var(--line);border-radius:0 0 6px 6px}.account-dropdown span{font-size:18px;color:var(--wood)}.bottom-login{background:none;border:0;display:flex;flex-direction:column;align-items:center;min-width:60px;min-height:48px;justify-content:center;font-size:11px;gap:0;padding:0;color:var(--red)}@media(max-width:600px){.bottom-login{min-width:52px;flex:1}.account-dropdown{right:0}}
</style>

