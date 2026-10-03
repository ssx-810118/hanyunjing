<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { useSession } from '../stores/session'

const store = useSession()
let timer: ReturnType<typeof setTimeout> | undefined
watch(() => store.cartConfirmation, confirmation => {
  clearTimeout(timer)
  if (confirmation) timer = setTimeout(() => {
    if (store.cartConfirmation?.id === confirmation.id) store.cartConfirmation = null
  }, 2200)
}, { immediate: true })
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <Teleport to="body">
    <div class="cart-confirmation-position" role="status" aria-live="polite" aria-atomic="true">
      <Transition name="cart-confirmation">
        <div v-if="store.cartConfirmation" class="cart-confirmation-card">
          <svg width="23" height="23" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="11" fill="#e8f4eb"/>
            <path d="m6.5 12 3.6 3.6 7.4-7.4" stroke="#2f8750" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>{{ store.cartConfirmation.message }}</span>
          <button type="button" aria-label="关闭加入衣囊提示" @click="store.cartConfirmation = null">×</button>
        </div>
      </Transition>
    </div>
  </Teleport>
</template>

<style scoped>
.cart-confirmation-position{position:fixed;right:22px;top:86px;z-index:65;pointer-events:none;width:max-content;max-width:calc(100vw - 32px)}
.cart-confirmation-card{display:flex;align-items:center;gap:10px;padding:14px 18px;border:1px solid #526f6d;border-radius:14px;background:#3d605f;box-shadow:0 8px 26px #23352b30;color:white;font-size:14px;line-height:1.6;pointer-events:auto;max-width:480px}
.cart-confirmation-card span{overflow-wrap:anywhere}.cart-confirmation-card button{flex-shrink:0;background:transparent;border:1px solid #ffffff55;color:white;border-radius:10px;padding:3px 10px;font-size:23px;margin-left:6px}
.cart-confirmation-card svg{flex-shrink:0}
.cart-confirmation-enter-active{transition:opacity .2s ease,transform .2s ease}
.cart-confirmation-leave-active{transition:opacity .8s ease,transform .8s ease;pointer-events:none}
.cart-confirmation-enter-from,.cart-confirmation-leave-to{opacity:0;transform:translateY(5px)}
@media(prefers-reduced-motion:reduce){.cart-confirmation-enter-active,.cart-confirmation-leave-active{transition:none}}
</style>
