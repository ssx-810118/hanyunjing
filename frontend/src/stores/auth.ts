import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { invalidateAuthSession, refreshAuthSession, request, SessionChangedError, setCsrfToken, setSessionSnapshotHandler, setUnauthorizedHandler } from '../api'
import type { Account, AuthSession } from '../types'
import { useSession } from './session'

export const useAuth = defineStore('auth', () => {
  const user = ref<Account | null>(null), loaded = ref(false), dialogOpen = ref(false), pendingRoute = ref(''), reason = ref('')
  const authenticated = computed(() => !!user.value)
  const sessions = useSession()
  let loginWaiters: Array<(success: boolean) => void> = []
  let channel: BroadcastChannel | undefined
  let synchronizationStarted = false

  function apply(snapshot: AuthSession, external = false) {
    const nextUser = snapshot.authenticated ? snapshot.user : null
    const changed = user.value?.id !== nextUser?.id
    const wasLoaded = loaded.value
    if (changed) {
      sessions.reset()
      if (external) closeLogin()
    }
    user.value = nextUser
    loaded.value = true
    if (external && changed && wasLoaded) sessions.notice = '登录账号已变化，旧会话与表单已清空。请核对当前账号后重新操作。'
    return changed
  }
  function applyLocal(snapshot: AuthSession) {
    // Invalidate pending reads before accepting the response that rotated the cookie.
    invalidateAuthSession()
    apply(snapshot)
    setCsrfToken(snapshot.csrfToken)
  }
  async function refresh() {
    await refreshAuthSession()
  }
  async function initialize() {
    if (loaded.value) return
    await refresh()
  }
  function discardLocalIdentity() {
    invalidateAuthSession()
    closeLogin()
    user.value = null
    loaded.value = false
    sessions.reset()
  }
  function notifyIdentityChange() { channel?.postMessage({ type: 'identity-changed' }) }
  async function externalIdentityChange() {
    // Clear stale forms immediately, before the new identity is fetched.
    discardLocalIdentity()
    sessions.notice = '另一页面的登录状态已变化，旧会话与表单已清空。'
    try { await refresh() }
    catch (error) { if (!(error instanceof SessionChangedError)) sessions.notice = '登录状态核对失败，请刷新页面后重新登录。' }
  }
  async function checkOnFocus() {
    try { await refresh() }
    catch (error) {
      if (error instanceof SessionChangedError) return
      discardLocalIdentity()
      sessions.notice = '暂时无法核对登录状态，请刷新页面后重新登录。'
    }
  }
  function checkOnVisible() { if (document.visibilityState === 'visible') void checkOnFocus() }
  function startSynchronization() {
    if (synchronizationStarted) return
    synchronizationStarted = true
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel(import.meta.env.VITE_ADMIN_APP === 'true' ? 'hanyunjing-admin-account' : 'hanyunjing-account')
      channel.onmessage = event => { if (event.data?.type === 'identity-changed') void externalIdentityChange() }
    }
    window.addEventListener('focus', checkOnFocus)
    document.addEventListener('visibilitychange', checkOnVisible)
  }
  function stopSynchronization() {
    channel?.close(); channel = undefined; synchronizationStarted = false
    window.removeEventListener('focus', checkOnFocus)
    document.removeEventListener('visibilitychange', checkOnVisible)
  }
  function requestLogin(target = '', message = '登录后可继续问衣、试穿和查看你的购物订单。'): Promise<boolean> {
    if (authenticated.value) return Promise.resolve(true)
    if (target.startsWith('/') && !target.startsWith('//')) pendingRoute.value = target
    reason.value = message
    dialogOpen.value = true
    return new Promise(resolve => { loginWaiters.push(resolve) })
  }
  function closeLogin() {
    dialogOpen.value = false
    pendingRoute.value = ''
    loginWaiters.splice(0).forEach(resolve => resolve(false))
  }
  async function submit(mode: 'login' | 'register', username: string, password: string, displayName: string) {
    await initialize()
    const snapshot = await request<AuthSession>('POST', `/auth/${mode}`, undefined, { username: username.trim(), password, ...(mode === 'register' ? { displayName: displayName.trim() } : {}) })
    applyLocal(snapshot)
    notifyIdentityChange()
    const destination = pendingRoute.value
    pendingRoute.value = ''
    dialogOpen.value = false
    loginWaiters.splice(0).forEach(resolve => resolve(true))
    return destination
  }
  async function logout() {
    applyLocal(await request<AuthSession>('POST', '/auth/logout'))
    notifyIdentityChange()
    closeLogin()
    sessions.reset()
  }
  setSessionSnapshotHandler(snapshot => apply(snapshot, true))
  setUnauthorizedHandler(() => {
    discardLocalIdentity()
    const current = window.location.pathname + window.location.search + window.location.hash
    void requestLogin(current, '登录已失效，请重新登录后继续。')
  })
  return { user, loaded, authenticated, dialogOpen, pendingRoute, reason, initialize, refresh, startSynchronization, stopSynchronization, requestLogin, closeLogin, submit, logout }
})
