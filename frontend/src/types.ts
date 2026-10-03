export interface Api<T> { code: number; message: string; data: T }
export interface Account { id: string; username: string; displayName: string }
export interface AuthSession { authenticated: boolean; user: Account | null; csrfToken: string }
export interface Range { min: number; max: number }
export interface SizeRow { size: string; height: Range; chest: Range; waist: Range; hip: Range }
export interface Sku { id: string; color: string; size: string; stock: number; price: number }
export interface Product { id: string; name: string; category: string; dynasty: string; form: string; description: string; scenes: string[]; tags: string[]; colors: string[]; images: string[]; accessoryIds: string[]; sizeChart: SizeRow[]; skus: Sku[] }
export interface Scene { id: string; name: string; dynasty: string; advice: string }
export type Kind = 'FACT' | 'COMMON'
export interface Article { id: string; title: string; topic: string; content: string; kind: Kind; source: string; keywords: string[]; claimKey: string; claimValue: string }
export type KnowledgeAdd = Omit<Article, 'id'>
export interface KnowledgeHit { article: Article; score: number }
export interface KnowledgeResult { abstained: boolean; reason: string; humanRequired: boolean; hits: KnowledgeHit[] }
export interface Body { height: number | null; weightKg: number | null; chest: number | null; waist: number | null; hip: number | null; loose: boolean }
export interface SizeAdvice { size: string | null; confidence: string; algorithm: string; missingFields: string[]; inRange: boolean }
export interface Slots { scene: string | null; dynasty: string | null; style: string | null; firstWear: boolean | null; muted: boolean; slim: boolean }
export interface Recommendation { product: Product; sizeAdvice: SizeAdvice; accessories: Product[]; reasons: string[]; eligibleSkus: Sku[]; evidence: Article[] }
export interface Funnel { total: number; sceneMatched: number; styleMatched: number; available: number; returned: number }
export interface AgentReply { sessionId: string; status: string; message: string; slots: Slots; missingFields: string[]; funnel: Funnel; recommendations: Recommendation[]; knowledge: KnowledgeResult; requirements: import('./retail').RetailRequirements; workflowId: string | null }
export interface TraceEvent { id: number; sessionId: string; type: string; summary: string; timestamp: string }
export interface Portrait { id: string; sessionId: string; width: number; height: number; format: string; expiresAt: string }
export interface TryOn { id: string; sessionId: string; productId: string; skuId: string; status: string; stage: string; attempts: number; demo: boolean; resultUrl: string | null; originalUrl: string | null; error: string | null; checks: string[]; expiresAt: string }
export interface TryOnStatus { ready: boolean; mode: string; message: string; provider: string; comparison: boolean; multiAngle: boolean; visualCheck: boolean }
export interface CartLine { id: string; productId: string; productName: string; skuId: string; color: string; size: string; quantity: number; unitPrice: number; subtotal: number }
export interface Cart { lines: CartLine[]; quantity: number; total: number }
export type PaymentMethod = 'ALIPAY' | 'WECHAT' | 'BALANCE'
export interface Order { id: string; sessionId: string; status: string; demo: boolean; cart: Cart; createdAt: string; orderNumber: string; recipient: string; phone: string; region: string; address: string; paymentMethod?: PaymentMethod | null; paidAt?: string | null; cancelledAt?: string | null }
export const paymentNames: Record<PaymentMethod, string> = { ALIPAY: '支付宝', WECHAT: '微信支付', BALANCE: '余额支付' }
export const orderStatusNames: Record<string, string> = { PENDING_PAYMENT: '待支付', DEMO_PAID: '模拟支付成功', CANCELLED: '已取消' }
export interface Preview { cart: Cart; payable: number; currency: string; demo: boolean }
export interface OpsSummary { events: number; source: string; prebuilt: boolean }
export const kindNames: Record<Kind, string> = { FACT: '史实', COMMON: '通行说法' }
export const fieldNames: Record<string, string> = { scene: '出行场景', firstWear: '是否首次穿着', height: '身高', weightKg: '体重（公斤）', chest: '胸围', waist: '腰围', hip: '臀围', dynasty: '朝代', style: '风格' }
export const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
