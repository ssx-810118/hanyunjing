import axios from 'axios'
import type { Api, AuthSession } from './types'
const adminApp = import.meta.env.VITE_ADMIN_APP === 'true'
const client = axios.create({ baseURL: '/api', timeout: 20000, withCredentials: true, headers: adminApp ? { 'X-HYJ-Client': 'admin' } : {} })
let csrfToken = ''
let sessionRevision = 0
let sessionRequest: { revision: number; promise: Promise<{ snapshot: AuthSession; changed: boolean }> } | undefined
let onSessionSnapshot: ((snapshot: AuthSession) => boolean) | undefined
let onUnauthorized: (() => void) | undefined
export class SessionChangedError extends Error {
  constructor() { super('登录状态已变化，本次操作已停止。请核对当前账号后重新操作。'); this.name = 'SessionChangedError' }
}
export function setCsrfToken(value: string) { csrfToken = value }
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler }
export function setSessionSnapshotHandler(handler: (snapshot: AuthSession) => boolean) { onSessionSnapshot = handler }
export function invalidateAuthSession() { sessionRevision += 1; csrfToken = ''; sessionRequest = undefined }
export async function refreshAuthSession(): Promise<{ snapshot: AuthSession; changed: boolean }> {
  if (sessionRequest?.revision === sessionRevision) return sessionRequest.promise
  const revision = sessionRevision
  const pending = client.get<Api<AuthSession>>('/auth/session').then(response => {
    if (revision !== sessionRevision) throw new SessionChangedError()
    if (response.data.code !== 0 || !response.data.data?.csrfToken) throw new Error('无法建立登录会话，请刷新后重试。')
    const snapshot = response.data.data
    const changed = onSessionSnapshot?.(snapshot) ?? false
    if (changed) sessionRevision += 1
    csrfToken = snapshot.csrfToken
    return { snapshot, changed }
  }).finally(() => { if (sessionRequest?.promise === pending) sessionRequest = undefined })
  sessionRequest = { revision, promise: pending }
  return pending
}
export async function request<T>(method: string, path: string, sessionId?: string, data?: unknown, params: Record<string, unknown> = {}, signal?: AbortSignal): Promise<T> {
  const modifying = !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())
  const requestRevision = sessionRevision
  if (modifying) {
    // Read the cookie-backed identity before writes; never replay an old action for a new account.
    const checked = await refreshAuthSession()
    if (checked.changed || requestRevision !== sessionRevision) throw new SessionChangedError()
  }
  try {
    const response = await client.request<Api<T>>({ method, url: path, data, params: { ...(sessionId ? { sessionId } : {}), ...params }, headers: modifying ? { 'X-CSRF-Token': csrfToken } : undefined, signal, timeout: path === "/agent/chat" ? 135000 : 20000 })
    if (requestRevision !== sessionRevision) throw new SessionChangedError()
    if (response.data.code !== 0) throw new Error(response.data.message)
    return response.data.data
  } catch (error) {
    if (requestRevision !== sessionRevision) throw new SessionChangedError()
    if (axios.isAxiosError(error) && error.response?.status === 403) csrfToken = ''
    if (axios.isAxiosError(error) && error.response?.status === 401 && !path.startsWith('/auth/')) {
      csrfToken = ''
      onUnauthorized?.()
    }
    throw error
  }
}
export function errorText(error: unknown): string {
  if (axios.isCancel(error)) return '已停止等待。请求可能已在服务端执行，请先刷新状态。'
  if (axios.isAxiosError(error) && error.code === "ECONNABORTED") return "等待超时；服务端可能仍在处理，请稍后重试，不会切换到离线推荐。"
  if (axios.isAxiosError<Api<unknown>>(error)) {
    const response = error.response
    if (response?.data?.message) return response.data.message
    if (response?.status === 401) return '登录已失效，请重新登录后继续。'
    if (response?.status === 403) return '会话校验失败，请刷新页面后重新操作。'
    if (response) return `后端返回 HTTP ${response.status}，请稍后重试或检查服务日志。`
    return '请求未收到响应，请检查网络、前端代理和后端服务；浏览器跨域限制也可能导致此问题。'
  }
  return error instanceof Error ? error.message : '操作失败，请重试。'
}
export function resourceUrl(path: string, sessionId: string): string {
  const url = new URL(path, window.location.origin)
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) throw new Error('无效资源地址')
  url.searchParams.set('sessionId', sessionId)
  return url.pathname + url.search
}
