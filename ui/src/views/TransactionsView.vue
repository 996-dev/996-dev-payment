<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VStatusDot,
} from '@halo-dev/components'
import {
  ApiError,
  paymentApi,
  type Overview,
  type SessionView,
} from '@/api'
import { copyText, toast } from '@/lib/toast'
import PpConfirm from '@/components/PpConfirm.vue'

/** 单次最多加载的会话数：全量加载后在本地筛选与分页，量级足够后台管理使用 */
const LOAD_CAP = 500

const PHASES = [
  { label: '全部', value: '' },
  { label: '待支付', value: 'PENDING' },
  { label: '待审核', value: 'PENDING_REVIEW' },
  { label: '已支付', value: 'PAID' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '失败', value: 'FAILED' },
]

const PHASE_TEXT: Record<string, string> = {
  PENDING: '待支付',
  PENDING_REVIEW: '待审核',
  PAID: '已支付',
  CLOSED: '已关闭',
  EXPIRED: '已过期',
  FAILED: '失败',
  REFUNDED: '已退款',
}

const PHASE_PILL: Record<string, string> = {
  PENDING: 'warn',
  PENDING_REVIEW: 'review',
  PAID: 'ok',
  FAILED: 'danger',
  REFUNDED: 'info',
}

const items = ref<SessionView[]>([])
/** 各状态的数量（用于 Tab 角标），key 为 '' 或 PHASES value */
const counts = ref<Record<string, number>>({})
const overview = ref<Overview>({
  totalSessions: 0,
  paidSessions: 0,
  paidAmount: 0,
  currency: 'CNY',
  pendingReviewSessions: 0,
  pendingReviewAmount: 0,
})
const phase = ref('')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const truncated = ref(false)
const busyCode = ref('')

/** 核销确认弹窗 */
const confirmOpen = ref(false)
const confirmTarget = ref<SessionView | null>(null)
const confirmAction = ref<'approve' | 'reject'>('approve')
const confirming = ref(false)

/** 详情抽屉 */
const detailOpen = ref(false)
const detailSession = ref<SessionView | null>(null)

const successRate = computed(() => {
  const t = overview.value.totalSessions
  return t > 0 ? `${Math.round((overview.value.paidSessions / t) * 100)}%` : '—'
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return items.value
  }
  return items.value.filter((row) =>
    [row.sessionCode, row.bizNo, row.subject, row.provider, row.methodName, row.bizType]
      .some((v) => (v || '').toLowerCase().includes(kw))
  )
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / size.value)))

const pagedItems = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    // 主查询：当前状态筛选，全量拉取后本地搜索/分页
    const result = await paymentApi.listSessions(1, LOAD_CAP, phase.value || undefined)
    items.value = result.items
    total.value = result.total
    truncated.value = result.total > LOAD_CAP
    page.value = 1
    // 各状态计数：size=1 只取 total，互不阻塞
    const countEntries = await Promise.all(
      PHASES.map(async (p) => {
        try {
          const r = await paymentApi.listSessions(1, 1, p.value || undefined)
          return [p.value, r.total] as const
        } catch {
          return [p.value, 0] as const
        }
      })
    )
    counts.value = Object.fromEntries(countEntries)
    overview.value = await paymentApi.overview()
  } catch (e) {
    errorMessage.value = describe(e)
  } finally {
    loading.value = false
  }
}

function describe(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status === 403
      ? '当前账号没有支付中心管理权限，请联系超级管理员分配「支付中心管理」权限。'
      : e.message
  }
  return e instanceof Error ? e.message : String(e)
}

function changePhase(value: string) {
  phase.value = value
  load()
}

function goPage(next: number) {
  if (next < 1 || next > totalPages.value) {
    return
  }
  page.value = next
}

function openDetail(row: SessionView) {
  detailSession.value = row
  detailOpen.value = true
  document.body.style.overflow = 'hidden'
}

function closeDetail() {
  detailOpen.value = false
  document.body.style.overflow = ''
}

/** 会话是否处于可审核/可核销的开放态。 */
function canReview(row: SessionView | null | undefined) {
  if (!row) {
    return false
  }
  return row.phase === 'PENDING' || row.phase === 'PENDING_REVIEW'
}

function auditText(state?: string) {
  const map: Record<string, string> = {
    NONE: '无需审核',
    PENDING: '待审核',
    APPROVED: '审核通过',
    REJECTED: '已驳回',
  }
  return map[state || ''] || state || '—'
}

/** 实付金额与应付金额不一致时在后台高亮，核销前必须人工确认。 */
function amountMismatch(row: SessionView) {
  const raw = row.paymentProof?.['paidAmount']
  if (!raw) {
    return false
  }
  const paid = Number(String(raw).replace(/,/g, ''))
  return Number.isFinite(paid) && Math.abs(paid - Number(row.amount)) > 0.001
}

function askMarkPaid(row: SessionView) {
  confirmTarget.value = row
  confirmAction.value = 'approve'
  confirmOpen.value = true
}

function askReject(row: SessionView) {
  confirmTarget.value = row
  confirmAction.value = 'reject'
  confirmOpen.value = true
}

/**
 * 审核提交：通过与驳回共用一次请求，区别只在 action。
 *
 * <p>备注（核销依据 / 驳回原因）为必填——后端会拒绝空备注，
 * 前端先拦截可以少一次往返，也避免用户白填其他内容。
 */
async function doConfirm(rawRemark: string) {
  const target = confirmTarget.value
  if (!target) {
    return
  }
  const remark = (rawRemark || '').trim()
  if (!remark) {
    toast('error', confirmAction.value === 'reject' ? '请填写驳回原因' : '请填写核销依据')
    return
  }
  confirming.value = true
  busyCode.value = target.sessionCode
  try {
    const updated = await paymentApi.review(target.sessionCode, confirmAction.value, remark)
    toast(
      'success',
      confirmAction.value === 'reject'
        ? `已驳回 ${target.sessionCode}，付款人可重新提交凭证`
        : `会话 ${target.sessionCode} 已核销为已支付`
    )
    confirmOpen.value = false
    // 就地更新该行，避免整页刷新
    const idx = items.value.findIndex((r) => r.sessionCode === target.sessionCode)
    if (idx >= 0) {
      items.value.splice(idx, 1, updated)
    }
    if (detailSession.value?.sessionCode === target.sessionCode) {
      detailSession.value = updated
    }
    counts.value = { ...counts.value }
    overview.value = await paymentApi.overview()
    await refreshCounts()
  } catch (e) {
    toast('error', describe(e))
  } finally {
    confirming.value = false
    busyCode.value = ''
  }
}

async function refreshCounts() {
  const countEntries = await Promise.all(
    PHASES.map(async (p) => {
      try {
        const r = await paymentApi.listSessions(1, 1, p.value || undefined)
        return [p.value, r.total] as const
      } catch {
        return [p.value, 0] as const
      }
    })
  )
  counts.value = Object.fromEntries(countEntries)
}

function formatAmount(value: number | string | undefined) {
  const num = Number(value ?? 0)
  if (!Number.isFinite(num)) {
    return '0.00'
  }
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatTime(value: string | undefined) {
  if (!value || value === 'null' || value === 'undefined') {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function phaseText(p: string | undefined) {
  return p ? PHASE_TEXT[p] || p : '—'
}

/**
 * 状态 → VStatusDot 语义色。
 *
 * <p>待审核用 warning 并呼吸闪烁：离线渠道的钱要靠人核对才算到账，
 * 这个状态最需要被运营注意到；已支付用 success（Halo 主色），
 * 关闭/过期是中性终态，不该抢视觉。
 */
function phaseState(p: string | undefined): 'default' | 'success' | 'warning' | 'error' {
  if (p === 'PAID') {
    return 'success'
  }
  if (p === 'PENDING_REVIEW') {
    return 'warning'
  }
  if (p === 'FAILED') {
    return 'error'
  }
  return 'default'
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (confirmOpen.value && !confirming.value) {
      confirmOpen.value = false
    } else if (detailOpen.value) {
      closeDetail()
    }
  }
}
window.addEventListener('keydown', onKeydown)
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})

/**
 * 支持从渠道管理页的「待审核」统计卡跳进来时带上筛选：
 * `/console/payment/sessions?phase=PENDING_REVIEW`。
 *
 * <p>读 query 而不是用 vue-router：插件产物内的 vue-router 与宿主是两份实例，
 * 拿不到宿主的 route 对象。读完顺手把参数抹掉，避免刷新后一直锁在某个筛选。
 */
function applyPhaseFromQuery() {
  const params = new URLSearchParams(window.location.search)
  const target = params.get('phase')
  if (!target) {
    return
  }
  if (PHASES.some((p) => p.value === target)) {
    phase.value = target
  }
  params.delete('phase')
  const qs = params.toString()
  window.history.replaceState(
    {},
    '',
    `${window.location.pathname}${qs ? `?${qs}` : ''}`
  )
}

onMounted(() => {
  applyPhaseFromQuery()
  load()
})
</script>

<template>
  <div class="pp-page">

    <header class="pp-header">
      <div>
        <h1>交易流水</h1>
        <p class="pp-sub">按会话维度查看支付结果，支持对离线渠道手工核销。</p>
      </div>
      <VButton :loading="loading" @click="load">刷新</VButton>
    </header>

    <section class="pp-stat-row">
      <div class="pp-stat">
        <span class="pp-stat-label">会话总数</span>
        <strong>{{ overview.totalSessions }}</strong>
      </div>
      <div class="pp-stat">
        <span class="pp-stat-label">成功笔数</span>
        <strong>{{ overview.paidSessions }}</strong>
      </div>
      <div class="pp-stat">
        <span class="pp-stat-label">成功率</span>
        <strong>{{ successRate }}</strong>
      </div>
      <div class="pp-stat">
        <span class="pp-stat-label">成功金额</span>
        <strong>{{ formatAmount(overview.paidAmount) }}</strong>
        <small>{{ overview.currency }}</small>
      </div>
      <!-- 待审核积压：离线渠道的钱要靠人核对才算到账，这个数字是运营每天先要清掉的 -->
      <div
        :class="['pp-stat', 'is-action', { 'is-attention': overview.pendingReviewSessions > 0 }]"
        role="button"
        tabindex="0"
        title="点击筛选待审核会话"
        @click="changePhase('PENDING_REVIEW')"
        @keydown.enter="changePhase('PENDING_REVIEW')"
      >
        <span class="pp-stat-label">待审核</span>
        <strong>{{ overview.pendingReviewSessions }}</strong>
        <small v-if="overview.pendingReviewSessions > 0">待核对 {{ formatAmount(overview.pendingReviewAmount) }}</small>
        <small v-else>无积压</small>
      </div>
    </section>

    <VAlert v-if="errorMessage" type="error" :title="errorMessage" class="pp-gap" />

    <div class="pp-toolbar">
      <div class="pp-tabs">
        <button
          v-for="item in PHASES"
          :key="item.value"
          :class="['pp-tab', { 'is-active': phase === item.value }]"
          @click="changePhase(item.value)"
        >
          {{ item.label }}<span v-if="counts[item.value] !== undefined" class="cnt">{{ counts[item.value] }}</span>
        </button>
      </div>
      <div class="spacer" />
      <div class="pp-search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="7" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <input v-model="keyword" type="text" placeholder="搜索会话码 / 业务单号 / 标题 / 渠道" />
        <button v-if="keyword" class="clear-btn" title="清空" @click="keyword = ''">×</button>
      </div>
    </div>

    <!-- 骨架屏 -->
    <VCard v-if="loading" :body-class="['pp-skel-body']">
      <div v-for="i in 5" :key="i" class="pp-skel pp-skel-row" style="margin-bottom: 8px" />
    </VCard>

    <!-- 空态 -->
    <VCard v-else-if="!filtered.length">
      <VEmpty
        :title="keyword ? '没有匹配的会话' : '暂无支付会话'"
        :message="
          keyword
            ? '换个关键字试试，或清空搜索框'
            : '业务系统调用开放 API 下单后会话会出现在这里'
        "
      />
    </VCard>

    <div v-else class="pp-table-wrap">
      <table class="pp-table">
        <thead>
          <tr>
            <th>创建时间</th>
            <th>会话码</th>
            <th>渠道</th>
            <th>业务单号</th>
            <th class="num">金额</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in pagedItems"
            :key="row.name"
            tabindex="0"
            :aria-label="`查看 ${row.sessionCode} 详情`"
            @click="openDetail(row)"
            @keydown.enter="openDetail(row)"
            @keydown.space.prevent="openDetail(row)"
          >
            <td class="mono" :title="formatTime(row.createdAt)">{{ formatTime(row.createdAt) }}</td>
            <td class="mono" @click.stop>
              {{ row.sessionCode }}
              <button
                class="pp-copy"
                :aria-label="`复制会话码 ${row.sessionCode}`"
                title="复制会话码"
                @click="copyText(row.sessionCode, '会话码')"
              >
                复制
              </button>
            </td>
            <td>
              <div class="pp-provider">{{ row.provider }}</div>
              <small class="pp-method">{{ row.methodName }}</small>
            </td>
            <td @click.stop>
              <div>{{ row.bizNo || '—' }}</div>
              <small class="pp-method">{{ row.bizType }}</small>
              <button
                v-if="row.bizNo"
                class="pp-copy"
                :aria-label="`复制业务单号 ${row.bizNo}`"
                title="复制业务单号"
                @click="copyText(row.bizNo, '业务单号')"
              >
                复制
              </button>
            </td>
            <td class="num mono">{{ formatAmount(row.amount) }} {{ row.currency }}</td>
            <td @click.stop>
              <VStatusDot
                :state="phaseState(row.phase)"
                :text="phaseText(row.phase)"
                :animate="row.phase === 'PENDING_REVIEW'"
              />
            </td>
            <td @click.stop>
              <!-- 审核的第一步是看凭证，不能在列表里盲核：
                   点"审核"打开详情抽屉（凭证/留痕都在里面），
                   通过与驳回按钮在抽屉底部，与"先看证据再下结论"的顺序一致 -->
              <VButton
                v-if="canReview(row)"
                type="primary"
                size="sm"
                :loading="busyCode === row.sessionCode"
                @click="openDetail(row)"
              >
                {{ row.phase === 'PENDING_REVIEW' ? '审核' : '核销' }}
              </VButton>
              <VButton v-else size="sm" @click="openDetail(row)">详情</VButton>
            </td>
          </tr>
        </tbody>
      </table>

      <footer class="pp-pager">
        <span>
          共 {{ total }} 条<template v-if="keyword">（筛选出 {{ filtered.length }} 条）</template>
          <template v-if="truncated">（最多加载 {{ LOAD_CAP }} 条，请用状态筛选缩小范围）</template>
          · 第 {{ page }} / {{ totalPages }} 页
        </span>
        <div class="pp-pager-btns">
          <select v-model.number="size" class="pp-select" aria-label="每页条数" @change="page = 1">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
          <VButton size="sm" :disabled="page <= 1" @click="goPage(page - 1)">上一页</VButton>
          <VButton size="sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</VButton>
        </div>
      </footer>
    </div>

    <!-- 详情抽屉 -->
    <div v-if="detailOpen" class="pp-drawer-mask" @click.self="closeDetail">
      <aside class="pp-drawer" role="dialog" aria-label="会话详情">
        <header class="pp-drawer-head">
          <div>
            <h2>会话详情</h2>
            <code class="pp-tag mono">
              {{ detailSession?.sessionCode }}
              <button
                class="pp-copy"
                title="复制会话码"
                @click="copyText(detailSession?.sessionCode || '', '会话码')"
              >
                复制
              </button>
            </code>
          </div>
          <VButton size="sm" @click="closeDetail">关闭</VButton>
        </header>

        <div class="pp-drawer-body">
          <!-- 金额不符是核销前最该被看到的信息，放在最上方而不是藏在凭证小字里 -->
          <VAlert
            v-if="detailSession && amountMismatch(detailSession)"
            type="warning"
            title="实付金额与应付金额不一致"
            :description="`凭证登记 ${detailSession.paymentProof?.paidAmount}，应付 ${formatAmount(detailSession.amount)}。核销前请核对银行流水。`"
            class="pp-gap"
          />

          <dl v-if="detailSession" class="pp-detail-grid">
            <dt>状态</dt>
            <dd>
              <VStatusDot
                :state="phaseState(detailSession.phase)"
                :text="phaseText(detailSession.phase)"
                :animate="detailSession.phase === 'PENDING_REVIEW'"
              />
            </dd>
            <dt>金额</dt>
            <dd class="mono">
              {{ formatAmount(detailSession.amount) }} {{ detailSession.currency }}
            </dd>
            <dt>标题</dt>
            <dd>{{ detailSession.subject || '—' }}</dd>
            <dt>渠道</dt>
            <dd>
              {{ detailSession.provider }}
              <small class="pp-method">{{ detailSession.methodName }}</small>
            </dd>
            <dt>业务</dt>
            <dd>
              {{ detailSession.bizNo || '—' }}
              <button
                v-if="detailSession.bizNo"
                class="pp-copy"
                aria-label="复制业务单号"
                @click="copyText(detailSession.bizNo, '业务单号')"
              >
                复制
              </button>
              <small class="pp-method">{{ detailSession.bizType }}</small>
            </dd>
            <dt>创建时间</dt>
            <dd class="mono">{{ formatTime(detailSession.createdAt) }}</dd>
            <dt>支付时间</dt>
            <dd class="mono">{{ formatTime(detailSession.paidAt) }}</dd>
            <dt>过期时间</dt>
            <dd class="mono">{{ formatTime(detailSession.expiresAt) }}</dd>
            <dt>渠道流水号</dt>
            <dd class="mono">
              {{ detailSession.providerTradeNo || '—' }}
              <button
                v-if="detailSession.providerTradeNo"
                class="pp-copy"
                @click="copyText(detailSession.providerTradeNo, '渠道流水号')"
              >
                复制
              </button>
            </dd>
            <dt>备注</dt>
            <dd>{{ detailSession.message || '—' }}</dd>
          </dl>

          <!-- 付款凭证：仅人工类渠道会提交，是核销的唯一依据 -->
          <section v-if="detailSession?.paymentProof" class="pp-block">
            <h3>付款凭证</h3>
            <dl class="pp-detail-grid">
              <dt>付款人</dt>
              <dd>{{ detailSession.paymentProof.payerName || '—' }}</dd>
              <dt>付款账号</dt>
              <dd class="mono">{{ detailSession.paymentProof.payerAccount || '—' }}</dd>
              <dt>银行流水号</dt>
              <dd class="mono">
                {{ detailSession.paymentProof.transferNo || '—' }}
                <button
                  v-if="detailSession.paymentProof.transferNo"
                  class="pp-copy"
                  @click="copyText(detailSession.paymentProof.transferNo, '银行流水号')"
                >
                  复制
                </button>
              </dd>
              <dt>实付金额</dt>
              <dd class="mono">
                {{ detailSession.paymentProof.paidAmount || '—' }}
                <span
                  v-if="amountMismatch(detailSession)"
                  class="pp-pill danger"
                  title="实付金额与应付金额不一致，核销前请核对"
                >
                  <span class="dot" />金额不符
                </span>
              </dd>
              <dt>付款时间</dt>
              <dd class="mono">{{ detailSession.paymentProof.paidAt || '—' }}</dd>
              <dt>凭证截图</dt>
              <dd>
                <a
                  v-if="detailSession.paymentProof.screenshotUrl"
                  :href="detailSession.paymentProof.screenshotUrl"
                  target="_blank"
                  rel="noopener"
                  class="pp-link"
                >
                  查看凭证
                </a>
                <span v-else class="pp-muted">—</span>
              </dd>
              <dt>付款说明</dt>
              <dd>{{ detailSession.paymentProof.remark || '—' }}</dd>
              <dt>提交时间</dt>
              <dd class="mono">{{ formatTime(detailSession.proofSubmittedAt) }}</dd>
            </dl>
          </section>

          <!-- 审核留痕：谁、什么时候、依据什么核销或驳回 -->
          <section
            v-if="detailSession?.auditState && detailSession.auditState !== 'NONE'"
            class="pp-block"
          >
            <h3>审核信息</h3>
            <dl class="pp-detail-grid">
              <dt>审核结果</dt>
              <dd>
                <VStatusDot
                  :state="
                    detailSession.auditState === 'APPROVED'
                      ? 'success'
                      : detailSession.auditState === 'REJECTED'
                        ? 'error'
                        : 'warning'
                  "
                  :text="auditText(detailSession.auditState)"
                />
              </dd>
              <dt>审核人</dt>
              <dd>{{ detailSession.auditedBy || '—' }}</dd>
              <dt>审核时间</dt>
              <dd class="mono">{{ formatTime(detailSession.auditedAt) }}</dd>
              <dt>审核备注</dt>
              <dd>{{ detailSession.auditRemark || '—' }}</dd>
            </dl>
          </section>

          <!-- 流程时间线：离线渠道要把"提交凭证 / 审核"两个节点也画出来，
               否则看起来像用户付完钱就卡住了 -->
          <ul v-if="detailSession" class="pp-timeline">
            <li class="done">
              创建会话<span class="t">{{ formatTime(detailSession.createdAt) }}</span>
            </li>
            <template v-if="detailSession.requiresReview || detailSession.paymentProof">
              <li :class="{ done: detailSession.proofSubmittedAt }">
                提交付款凭证
                <span class="t">{{ detailSession.proofSubmittedAt ? formatTime(detailSession.proofSubmittedAt) : '待提交' }}</span>
              </li>
              <li :class="{ done: detailSession.auditState === 'APPROVED' || detailSession.auditState === 'REJECTED' }">
                运营审核
                <span class="t">
                  {{ detailSession.auditState === 'APPROVED' ? '已通过' : detailSession.auditState === 'REJECTED' ? '已驳回，待重提' : '待审核' }}
                </span>
              </li>
            </template>
            <li :class="{ done: detailSession.paidAt }">
              支付完成<span class="t">{{ detailSession.paidAt ? formatTime(detailSession.paidAt) : '待支付' }}</span>
            </li>
          </ul>
        </div>

        <footer class="pp-drawer-foot">
          <VButton @click="closeDetail">关闭</VButton>
          <template v-if="detailSession && canReview(detailSession)">
            <VButton
              :disabled="busyCode === detailSession.sessionCode"
              @click="askReject(detailSession)"
            >
              驳回
            </VButton>
            <VButton
              type="primary"
              :loading="busyCode === detailSession.sessionCode"
              @click="askMarkPaid(detailSession)"
            >
              通过核销
            </VButton>
          </template>
        </footer>
      </aside>
    </div>

    <!-- 审核 / 核销确认：只有驳回是破坏性操作，核销是正向动作，不要也标红 -->
    <PpConfirm
      :open="confirmOpen"
      :title="confirmAction === 'reject' ? '驳回付款凭证' : '核销为已支付'"
      :message="
        confirmAction === 'reject'
          ? `驳回会话 ${confirmTarget?.sessionCode || ''} 的付款凭证？会话会退回待支付，付款人可重新提交。`
          : `确认将 ${confirmTarget?.sessionCode || ''} 标记为已支付？请先核对银行到账，核销后需走退款流程才能撤销。`
      "
      :danger="confirmAction === 'reject'"
      :confirm-text="confirmAction === 'reject' ? '确认驳回' : '确认核销'"
      remark
      remark-required
      :remark-placeholder="
        confirmAction === 'reject'
          ? '驳回原因（必填），付款人会看到该内容'
          : '核销依据（必填），例如银行流水号或对账说明'
      "
      :loading="confirming"
      @confirm="doConfirm"
      @cancel="confirmOpen = false"
    />
  </div>
</template>
