/** Authenticated, same-origin download; error responses must never become saved files. */
export async function saveLocalFile(path: string, filename: string, expectedType: string, signal?: AbortSignal) {
  const url = new URL(path, window.location.origin)
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) throw new Error('无效下载地址')
  const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', signal })
  if (!response.ok) {
    if (response.status === 401) throw new Error('登录已失效，请重新登录后下载。')
    if (response.status === 404) throw new Error('结果已到期或已删除，请重新生成。')
    throw new Error('暂时无法下载，请稍后重试。')
  }
  if (response.headers.get('Content-Type')?.split(';')[0]?.trim() !== expectedType) throw new Error('下载内容格式不正确，请刷新结果后重试。')
  const blob = await response.blob()
  if (!blob.size) throw new Error('下载文件为空，请稍后重试。')
  if (signal?.aborted) return
  const local = URL.createObjectURL(blob), anchor = document.createElement('a')
  anchor.href = local; anchor.download = filename; anchor.style.display = 'none'
  document.body.appendChild(anchor)
  try { anchor.click() } finally { anchor.remove(); setTimeout(() => URL.revokeObjectURL(local), 1000) }
}
