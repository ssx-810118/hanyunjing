import { onBeforeUnmount, ref, toValue, watch } from 'vue'
import type { MaybeRefOrGetter } from 'vue'
import { request, errorText } from '../api'
import type { Api, TraceEvent } from '../types'

const labels: Record<string, string> = {
  AGENT_STARTED: '问衣已受理', AGENT_PROGRESS: '整理在线问衣',
  AGENT_CONTEXT_READY: '查询依据已备齐', LOCAL_CHECKS_READY: '本地校验完成',
  LLM_CALL_START: '等待在线模型', LLM_CALL_RESULT: '在线模型已响应',
  TOOL_CALL: '查询或核验依据', TOOL_RESULT: '已取得查询结果', TOOL_FAILURE: '依据校验失败',
  AGENT_ONLINE_DONE: '答复已生成', LLM_FAILURE: '在线问衣失败', LLM_UNAVAILABLE: '在线服务不可用',
  KNOWLEDGE_GAP: '知识证据不足', HANDOFF: '需人工核验', HANDOFF_REQUESTED: '已登记人工核验',
  RECOMMENDATION: '推荐结果', AGENT_CHAT: '问衣记录', KNOWLEDGE_ADDED: '知识已补充',
  SIZE_ADVICE: '商品尺码已核对', BODY_UPDATED: '资料已更新', BODY_DELETED: '资料已删除', CART_CHANGED: '衣囊已更新',
  ORDER_CREATED: '订单已创建', ORDER_DEMO_PAID: '模拟购买成功', ORDER_CANCELLED: '订单已取消',
  PORTRAIT_UPLOADED: '人像已上传', PORTRAIT_DELETED: '人像已删除',
  TRYON_CREATED: '试穿任务已创建', TRYON_QUEUED: '试穿排队中', TRYON_RUNNING: '试穿处理中',
  TRYON_DONE: '试穿已完成', TRYON_FAILED: '试穿失败', TRYON_CANCELLED: '试穿已取消'
}
export const traceLabel = (type: string) => labels[type] || '其他真实记录'

export function useTrace(sessionId?: MaybeRefOrGetter<string | undefined>) {
  const events = ref<TraceEvent[]>([]), state = ref('未连接'), error = ref(''), paused = ref(true)
  let stream: EventSource | undefined, readController: AbortController | undefined
  let handshakeTimer: ReturnType<typeof setTimeout> | undefined, pollTimer: ReturnType<typeof setTimeout> | undefined
  let generation = 0, disposed = false, failures = 0
  const current = () => toValue(sessionId)
  const active = (token: number) => !disposed && !paused.value && token === generation
  function release() {
    generation++
    clearTimeout(handshakeTimer); clearTimeout(pollTimer); handshakeTimer = undefined; pollTimer = undefined
    stream?.close(); stream = undefined
    readController?.abort(); readController = undefined
  }
  function stop() { paused.value = true; release(); state.value = '已暂停追踪'; error.value = '' }
  function merge(incoming: TraceEvent[], sid: string | undefined, replace = false) {
    const all = new Map((replace ? [] : events.value).map(event => [event.id, event]))
    incoming.filter(event => !sid || event.sessionId === sid).forEach(event => all.set(event.id, event))
    events.value = [...all.values()].sort((a, b) => a.id - b.id).slice(-5000)
  }
  function schedulePoll(token: number, sid: string | undefined, delay: number) {
    if (!active(token)) return
    clearTimeout(pollTimer)
    pollTimer = setTimeout(() => { pollTimer = undefined; void poll(token, sid) }, delay)
  }
  async function poll(token: number, sid: string | undefined) {
    if (!active(token)) return
    const controller = new AbortController(); readController = controller
    let delay = 4000
    try {
      const incoming = await request<TraceEvent[]>('GET', '/trace/events', sid, undefined, { after: events.value.at(-1)?.id ?? 0 }, controller.signal)
      if (!active(token)) return
      merge(incoming, sid); failures = 0; error.value = ''; state.value = '定时刷新 · 每4秒读取记录'
    } catch (cause) {
      if (!active(token) || controller.signal.aborted) return
      failures++; delay = Math.min(4000 * 2 ** Math.min(failures, 3), 30000)
      error.value = errorText(cause); state.value = `追踪读取失败 · ${delay / 1000}秒后重试`
    } finally {
      if (readController === controller) readController = undefined
      if (active(token)) schedulePoll(token, sid, delay)
    }
  }
  function usePolling(token: number, sid: string | undefined, detail = '') {
    if (!active(token)) return
    clearTimeout(handshakeTimer); handshakeTimer = undefined
    stream?.close(); stream = undefined
    state.value = '定时刷新 · 每4秒读取记录'; error.value = detail; failures = 0
    schedulePoll(token, sid, 4000)
  }
  async function connect() {
    if (disposed) return
    release(); paused.value = false
    const token = generation, sid = current(), controller = new AbortController()
    readController = controller; state.value = '正在读取记录'; error.value = ''
    try {
      const history = await request<TraceEvent[]>('GET', '/trace/events', sid, undefined, {}, controller.signal)
      if (!active(token)) return
      merge(history, sid, true)
      const params = new URLSearchParams({ after: String(events.value.at(-1)?.id ?? 0) })
      if (sid) params.set('sessionId', sid)
      const opened = new EventSource('/api/trace/stream?' + params)
      stream = opened; state.value = '正在连接实时记录'
      handshakeTimer = setTimeout(() => usePolling(token, sid), 8000)
      opened.onopen = () => {
        if (!active(token) || stream !== opened) return
        clearTimeout(handshakeTimer); handshakeTimer = undefined; state.value = '实时连接'; error.value = ''
      }
      opened.addEventListener('trace', event => {
        if (!active(token) || stream !== opened) return
        try {
          const envelope = JSON.parse((event as MessageEvent).data) as Api<TraceEvent>
          if (envelope.code !== 0 || !envelope.data) throw new Error(envelope.message || '事件内容无效')
          merge([envelope.data], sid)
        } catch { usePolling(token, sid, '实时记录解析失败，已切换为定时读取。') }
      })
      opened.onerror = () => { if (active(token) && stream === opened) usePolling(token, sid) }
    } catch (cause) {
      if (active(token) && !controller.signal.aborted) usePolling(token, sid, errorText(cause))
    } finally { if (readController === controller) readController = undefined }
  }
  watch(current, () => {
    const resume = !paused.value
    release(); events.value = []; error.value = ''; state.value = resume ? '正在切换会话' : '已暂停追踪'
    if (resume) void connect()
  }, { flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; stop() })
  return { events, state, error, paused, connect, stop }
}
