<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { errorText } from '../api'
import { useAuth } from '../stores/auth'
const auth = useAuth(), router = useRouter(), dialog = ref<HTMLDialogElement>(), usernameInput = ref<HTMLInputElement>()
const mode = ref<'login' | 'register'>('login'), username = ref(''), password = ref(''), confirmPassword = ref(''), displayName = ref(''), busy = ref(false), error = ref('')
function clearSecrets() { password.value = ''; confirmPassword.value = '' }
function close() { if (!busy.value) { clearSecrets(); error.value = ''; auth.closeLogin() } }
function switchMode(value: 'login' | 'register') { if (!busy.value) { mode.value = value; clearSecrets(); error.value = '' } }
async function submit() {
  if (busy.value) return
  error.value = ''
  if (mode.value === 'register' && password.value !== confirmPassword.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    const destination = await auth.submit(mode.value, username.value, password.value, displayName.value)
    clearSecrets()
    if (destination) await router.push(destination)
  } catch (e) { error.value = errorText(e) }
  finally { busy.value = false }
}
watch(() => auth.dialogOpen, async open => {
  if (open) { await nextTick(); if (!dialog.value?.open) dialog.value?.showModal(); await nextTick(); usernameInput.value?.focus() }
  else { dialog.value?.close(); clearSecrets(); error.value = '' }
}, { immediate: true })
onBeforeUnmount(clearSecrets)
</script>

<template>
  <Teleport to="body"><dialog ref="dialog" class="auth-dialog" aria-labelledby="auth-title" aria-describedby="auth-description" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
    <div class="between"><span class="eyebrow">汉韵镜 · 与衣相逢</span><button type="button" class="close-button" :disabled="busy" aria-label="关闭登录" @click="close">×</button></div>
    <h2 id="auth-title">{{ mode === 'login' ? '登录，续一笺长安' : '注册你的汉韵镜账号' }}</h2>
    <p id="auth-description" class="muted">{{ auth.reason }}</p>
    <div class="auth-tabs" aria-label="账号操作"><button type="button" :aria-pressed="mode === 'login'" :disabled="busy" @click="switchMode('login')">登录</button><button type="button" :aria-pressed="mode === 'register'" :disabled="busy" @click="switchMode('register')">注册账号</button></div>
    <form @submit.prevent="submit">
      <label for="auth-username">用户名<input id="auth-username" ref="usernameInput" v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="32" pattern="[A-Za-z0-9_\-]{3,32}" placeholder="3–32 位字母、数字、下划线或短横线" :disabled="busy"></label>
      <label v-if="mode === 'register'" for="auth-display-name">昵称 <span class="optional">选填</span><input id="auth-display-name" v-model="displayName" name="displayName" autocomplete="nickname" maxlength="40" placeholder="希望我们如何称呼你" :disabled="busy"></label>
      <label for="auth-password">密码<input id="auth-password" v-model="password" name="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" required minlength="8" maxlength="72" placeholder="8–72 个字符" :disabled="busy"></label>
      <label v-if="mode === 'register'" for="auth-confirm-password">确认密码<input id="auth-confirm-password" v-model="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" required minlength="8" maxlength="72" placeholder="再次输入密码" :disabled="busy"></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary full" :disabled="busy">{{ busy ? '正在处理…' : mode === 'login' ? '登录并继续' : '注册并登录' }}</button>
      <p class="caption auth-note">登录后，模拟购物订单归入当前账号；退出登录会清空本页的问衣、人像与试穿记录索引。</p>
    </form>
  </dialog></Teleport>
</template>

<style scoped>
.auth-dialog{width:min(460px,calc(100vw - 28px));padding:28px 30px}.auth-dialog h2{font-size:27px;margin-top:10px}.auth-dialog .eyebrow{margin:0}.close-button{padding:3px 12px;font-size:26px;background:transparent;border:0}.auth-tabs{display:flex;gap:8px;margin:20px 0}.auth-tabs button{flex:1}.auth-dialog label{margin-top:16px}.auth-dialog input{margin-top:7px}.optional{font-size:12px;color:var(--muted);font-weight:400}.auth-note{margin:16px 0 0;line-height:1.9}@media(max-width:480px){.auth-dialog{padding:22px}.auth-dialog h2{font-size:25px}}
</style>
