export interface RetailRequirements { budget: number | null; size: string | null; color: string | null; quantity: number }
export interface RetailCandidate { sku_id: string; product_id: string; quoted_price: number; size_label: string; color: string; name: string; status: string; deleted_at: string | null }
export interface RetailCase { state: 'OPEN' | 'RESOLVED'; reason: string; reply: string; created_at: string; resolved_at: string | null }
export interface RetailRun {
  id: string; origin: 'LIVE'|'EVALUATION'; status: string; summary: string; budget: number | null; size_label: string | null; color_label: string | null; quantity: number;
  scene: string | null; dynasty: string | null; created_at: string; finished_at: string | null; elapsed_ms: number | null;
  model_calls: number; tool_calls: number; input_tokens: number | null; output_tokens: number | null;
  candidates: RetailCandidate[]; cases: RetailCase[]; selections: {sku_id: string; confirmed_at: string}[];
  steps: {step_number: number; kind: string; detail: string; elapsed_ms: number}[];
  evidence: {product_id: string; title_at_query: string; excerpt_at_query: string; source_at_query: string}[];
}
export interface RetailMetrics { total: number; failed: number; confirmed: number; running: number; openCases: number; resolvedCases: number; averageMs: number | null; p95Ms: number | null; cost: number | null; costNote: string; scope: string }
export const retailStates: Record<string,string> = { RUNNING:'处理中',DONE:'待选购确认',CONFIRMED:'已确认加购',NO_MATCH:'暂无匹配',HANDOFF:'需要商家协助',NEED_SLOT:'待补充需求',FAILED:'处理失败',INTERRUPTED:'运行中断' }
export const sourceLinks = (source: string) => [...new Set(source.match(/https:\/\/[^\s｜|，；）)]+/g) || [])]
