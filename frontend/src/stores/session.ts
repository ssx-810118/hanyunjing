import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AgentReply, Portrait, TryOn } from '../types'
export interface Turn { id: string; message?: string; reply: AgentReply }
export interface Session { id: string; name: string; turns: Turn[]; portraits: Portrait[]; tasks: TryOn[] }
const fresh = (index: number): Session => ({ id: crypto.randomUUID(), name: `长安问答 ${index}`, turns: [], portraits: [], tasks: [] })
export const useSession = defineStore('session', () => {
  const sessions = ref<Session[]>([fresh(1)])
  const activeId = ref(sessions.value[0].id)
  const current = computed(() => sessions.value.find(s => s.id === activeId.value)!)
  const notice = ref('')
  const cartConfirmation = ref<{ id: number; message: string } | null>(null)
  let confirmationId = 0
  function confirmCartAddition(message = '已加入衣囊。') {
    if (notice.value.startsWith('已加入衣囊')) notice.value = ''
    cartConfirmation.value = { id: ++confirmationId, message }
  }
  function create() { const s = fresh(sessions.value.length + 1); sessions.value.push(s); activeId.value = s.id }
  function reset() { const s = fresh(1); sessions.value = [s]; activeId.value = s.id; notice.value = ''; cartConfirmation.value = null }
  function remember(task: TryOn) { const s = sessions.value.find(s => s.id === task.sessionId); if (!s) return; const i = s.tasks.findIndex(t => t.id === task.id); if (i < 0) s.tasks.push(task); else s.tasks[i] = task }
  return { sessions, activeId, current, notice, cartConfirmation, confirmCartAddition, create, remember, reset }
})
