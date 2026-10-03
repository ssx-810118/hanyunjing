export const tryonStages: Record<string, { title: string; detail: string }> = {
  PARSING: { title: '正在准备照片', detail: '核对已授权的人像与衣裳信息。' },
  MATCHING: { title: '正在准备所选服饰', detail: '整理本次照片、颜色和尺码。' },
  PREPARING_IMAGE: { title: '正在整理图片', detail: '优化照片与服饰图片，准备提交换装。' },
  WAITING_SLOT: { title: '正在等待前序任务', detail: '前一个试穿任务尚未结束，本次还没有发送生成请求。' },
  UPLOADING: { title: '正在提交照片与服饰', detail: '向火山引擎提交本次换装请求，不会自动重复提交。' },
  REMOTE_QUEUED: { title: '火山引擎排队中', detail: '服务已接收请求，正在等待生成。页面只查询这一次任务。' },
  RENDERING: { title: '正在生成试穿效果', detail: '火山引擎正在处理照片，完成后会自动展示效果与购买入口。' },
  SELF_CHECK: { title: '正在整理生成结果', detail: '检查图片格式，准备原图与试穿效果对比。' }
}
export const tryonProgress = (stage?: string) => tryonStages[stage || ''] || { title: '正在读取任务状态', detail: '查询本次生成进度，不会重新生成图片。' }
export function waitingTime(seconds: number) { return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` }
