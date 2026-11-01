/**
 * 支付中心管理端点的轻量客户端。
 *
 * <p>直接走 fetch 而不是 @halo-dev/api-client：管理端点挂在
 * `/apis/console.api.payment.pangu.tax/v1alpha1` 下，属于本插件自有路由，
 * 不在 Halo 生成的 API 客户端里。同源请求会自动携带 Console 登录 Cookie。
 */

const BASE = '/apis/console.api.payment.pangu.tax/v1alpha1'

export interface MethodView {
  name: string
  provider: string
  displayName: string
  icon?: string
  scenes: string[]
  enabled: boolean
  priority: number
  remark?: string
  providerDisplayName?: string
  description?: string
  implemented: boolean
  supportedScenes: string[]
  configuredFields: number
  configuredSecrets: number
  requiredSecrets: number
  configured: boolean
}

export interface ConfigField {
  name: string
  label: string
  type: string
  required?: boolean
  secret?: boolean
  placeholder?: string
  help?: string
  options?: Array<{ label: string; value: string }>
}

export interface SettingsView {
  name: string
  schema: ConfigField[]
  values: Record<string, unknown>
}

export interface SessionView {
  name: string
  createdAt: string
  sessionCode: string
  provider: string
  methodName: string
  bizType: string
  bizNo: string
  amount: number
  currency: string
  subject: string
  expiresAt: string
  phase: string
  paidAt: string
  providerTradeNo: string
  message: string
  /** 人工审核状态：NONE / PENDING / APPROVED / REJECTED */
  auditState?: string
  auditRemark?: string
  auditedBy?: string
  auditedAt?: string
  proofSubmittedAt?: string
  /** 付款人提交的凭证，仅人工类渠道存在 */
  paymentProof?: Record<string, string> | null
  /** 该渠道是否需要人工审核 */
  requiresReview?: boolean
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  items: T[]
}

export interface Overview {
  totalSessions: number
  paidSessions: number
  paidAmount: number
  currency: string
  /** 离线渠道待人工审核的会话数（钱已付、待核对） */
  pendingReviewSessions: number
  /** 待审核会话的应付金额合计 */
  pendingReviewAmount: number
}

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    const message =
      body?.error?.message ?? body?.message ?? `请求失败（HTTP ${response.status}）`
    throw new ApiError(response.status, message)
  }
  return body as T
}

export const paymentApi = {
  listMethods: () => request<MethodView[]>('/methods'),

  getSettings: (name: string) =>
    request<SettingsView>(`/methods/${encodeURIComponent(name)}/settings`),

  saveSettings: (name: string, settings: Record<string, unknown>) =>
    request<{ name: string; saved: string[] }>(
      `/methods/${encodeURIComponent(name)}/settings`,
      { method: 'PUT', body: JSON.stringify({ settings }) }
    ),

  toggle: (name: string, enabled: boolean) =>
    request<{ name: string; enabled: boolean }>(
      `/methods/${encodeURIComponent(name)}/toggle`,
      { method: 'POST', body: JSON.stringify({ enabled }) }
    ),

  listSessions: (page: number, size: number, phase?: string) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    if (phase) {
      query.set('phase', phase)
    }
    return request<PageResult<SessionView>>(`/sessions?${query.toString()}`)
  },

  markPaid: (code: string, remark?: string) =>
    request<SessionView>(`/sessions/${encodeURIComponent(code)}/mark-paid`, {
      method: 'POST',
      body: JSON.stringify({ operator: 'console', remark }),
    }),

  /**
   * 人工审核：approve 核销 / reject 驳回重交。
   * 审核人由后端取登录态，这里不传。
   */
  review: (code: string, action: 'approve' | 'reject', remark: string) =>
    request<SessionView>(`/sessions/${encodeURIComponent(code)}/review`, {
      method: 'POST',
      body: JSON.stringify({ action, remark }),
    }),

  overview: () => request<Overview>('/overview'),
}
