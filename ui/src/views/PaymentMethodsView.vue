<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VSwitch,
  VTag,
} from '@halo-dev/components'
import {
  ApiError,
  paymentApi,
  type ConfigField,
  type MethodView,
  type Overview,
} from '@/api'
import { toast } from '@/lib/toast'
import PpConfirm from '@/components/PpConfirm.vue'

const methods = ref<MethodView[]>([])
const overview = ref<Overview>({
  totalSessions: 0,
  paidSessions: 0,
  paidAmount: 0,
  currency: 'CNY',
  pendingReviewSessions: 0,
  pendingReviewAmount: 0,
})
const loading = ref(true)
const errorMessage = ref('')

/** 工具条：关键字 + 状态筛选 */
const keyword = ref('')
const statusFilter = ref<'all' | 'enabled' | 'disabled' | 'unconfigured'>('all')

const drawerOpen = ref(false)
const activeMethod = ref<MethodView | null>(null)
const schema = ref<ConfigField[]>([])
const formValues = ref<Record<string, unknown>>({})
const saving = ref(false)
const formError = ref('')
/** 配置项正在拉取：与「该渠道确实没有配置项」区分开，否则会一直显示"加载中" */
const schemaLoading = ref(false)
/** 打开抽屉时的原始值，用于判断是否有未保存的修改 */
const baseline = ref<Record<string, unknown>>({})
/** 有未保存修改时点关闭 / 取消 → 二次确认 */
const discardOpen = ref(false)
/** 各字段「从文件读取」的结果提示 */
const fileHints = ref<Record<string, string>>({})
/** 密钥输入框明文显示开关：字段名 -> boolean */
const revealSecrets = ref<Record<string, boolean>>({})
/** 开关操作进行中的渠道 */
const busyToggle = ref('')

/**
 * 是否已改动但未保存。
 *
 * <p>凭据类字段一旦填错就白填，误点遮罩关闭会直接丢失，所以关闭前拦一道；
 * 用 JSON 比较而不是逐字段 diff，字段数量级很小，够用且不会漏掉数组/对象值。
 */
const dirty = computed(
  () => drawerOpen.value && JSON.stringify(formValues.value) !== JSON.stringify(baseline.value)
)

const filteredMethods = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return methods.value.filter((m) => {
    if (statusFilter.value === 'enabled' && !m.enabled) {
      return false
    }
    if (statusFilter.value === 'disabled' && m.enabled) {
      return false
    }
    if (statusFilter.value === 'unconfigured' && m.configured) {
      return false
    }
    if (!kw) {
      return true
    }
    return [m.displayName, m.provider, m.description, m.name]
      .some((v) => (v || '').toLowerCase().includes(kw))
  })
})

const enabledCount = computed(() => methods.value.filter((m) => m.enabled).length)
const configuredCount = computed(() => methods.value.filter((m) => m.configured).length)

/** 渠道头像底色：按名称稳定散列到一组柔和配色 */
const AVATAR_COLORS = ['#2563eb', '#0891b2', '#7c3aed', '#059669', '#d97706', '#dc2626', '#4f46e5']
function avatarColor(name: string) {
  let h = 0
  for (const ch of name) {
    h = (h * 31 + ch.charCodeAt(0)) | 0
  }
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length]
}

const SCENE_TEXT: Record<string, string> = {
  PC_WEB: '网页',
  MOBILE_WEB: '移动端',
  MINI_PROGRAM: '小程序',
}

function sceneText(scene: string) {
  return SCENE_TEXT[scene] || scene
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [list, ov] = await Promise.all([paymentApi.listMethods(), paymentApi.overview()])
    methods.value = list
    overview.value = ov
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

async function openConfig(method: MethodView) {
  if (!method.implemented) {
    toast('error', `渠道 ${method.displayName} 尚未实现配置表单`)
    return
  }
  activeMethod.value = method
  formError.value = ''
  drawerOpen.value = true
  schema.value = []
  formValues.value = {}
  baseline.value = {}
  fileHints.value = {}
  revealSecrets.value = {}
  schemaLoading.value = true
  document.body.style.overflow = 'hidden'
  try {
    const data = await paymentApi.getSettings(method.name)
    schema.value = data.schema
    // 缺省值补齐空串：v-model 绑到 undefined 时输入框会是"半受控"，
    // 首字符输入可能丢字（Vue 对 undefined -> string 的切换会重置光标）。
    const values: Record<string, unknown> = {}
    for (const field of data.schema) {
      values[field.name] = data.values[field.name] ?? ''
    }
    formValues.value = values
    baseline.value = { ...values }
  } catch (e) {
    formError.value = describe(e)
  } finally {
    schemaLoading.value = false
  }
}

/** 真正关闭抽屉（已确认丢弃修改，或本来就没有修改） */
function forceClose() {
  discardOpen.value = false
  drawerOpen.value = false
  document.body.style.overflow = ''
}

function closeDrawer() {
  if (saving.value) {
    return
  }
  if (dirty.value) {
    discardOpen.value = true
    return
  }
  forceClose()
}

async function submit() {
  if (!activeMethod.value || saving.value) {
    return
  }
  // 必填校验：前端先拦一层，避免整串后端报错
  const missing = schema.value
    .filter((f) => f.required && !String(formValues.value[f.name] ?? '').trim())
    .map((f) => f.label)
  if (missing.length) {
    formError.value = `请填写必填项：${missing.join('、')}`
    return
  }
  saving.value = true
  formError.value = ''
  try {
    // 首尾空白一律去掉：从聊天工具复制的密钥常带尾随空格或换行，
    // 这类脏数据会让渠道侧签名校验失败，且报错信息完全不指向这里。
    const payload: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(formValues.value)) {
      payload[key] = typeof value === 'string' ? value.trim() : value
    }
    await paymentApi.saveSettings(activeMethod.value.name, payload)
    toast('success', `${activeMethod.value.displayName} 配置已保存`)
    forceClose()
    await load()
  } catch (e) {
    formError.value = describe(e)
  } finally {
    saving.value = false
  }
}

/**
 * 跳到交易流水的待审核列表。
 *
 * <p>不用 vue-router 的 useRouter：插件产物会把 vue-router 打进自己的 chunk，
 * 与宿主是两份实例，provide/inject 的 key 对不上，注入会失败。
 * 直接改地址栏 + 目标页读 query 最稳，代价只是一次整页加载。
 */
function goReviewQueue() {
  const base = window.location.pathname.split('/payment/')[0] || ''
  window.location.assign(`${base}/payment/sessions?phase=PENDING_REVIEW`)
}

async function toggle(method: MethodView) {  if (busyToggle.value) {
    return
  }
  busyToggle.value = method.name
  try {
    await paymentApi.toggle(method.name, !method.enabled)
    method.enabled = !method.enabled
    toast('success', `${method.displayName} 已${method.enabled ? '启用' : '停用'}`)
  } catch (e) {
    toast('error', describe(e))
  } finally {
    busyToggle.value = ''
  }
}

/**
 * 选择一个本地文本文件（.pem / .crt / .txt 等）并把内容回填到字段。
 * 私钥、公钥、证书都以字符串形式存入配置，所以这里读文本即可，无需后端上传接口。
 */
function pickFile(fieldName: string) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.pem,.key,.crt,.cer,.pub,.txt,.json'
  input.style.display = 'none'
  document.body.appendChild(input)
  input.onchange = () => {
    const file = input.files?.[0]
    document.body.removeChild(input)
    if (!file) {
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      // 去掉 BOM，PEM 首行必须是 -----BEGIN ...-----
      formValues.value[fieldName] = String(reader.result ?? '').replace(/^\uFEFF/, '')
      fileHints.value[fieldName] = `已读取 ${file.name}（${file.size} 字节）`
    }
    reader.onerror = () => {
      fileHints.value[fieldName] = ''
      formError.value = `读取文件失败：${file.name}`
    }
    reader.readAsText(file)
  }
  input.click()
}

/** 用户手动改动后清掉「已读取」提示 */
function clearFileHint(fieldName: string) {
  if (fileHints.value[fieldName]) {
    delete fileHints.value[fieldName]
  }
}

function formatAmount(value: number | string | undefined) {
  const num = Number(value ?? 0)
  if (!Number.isFinite(num)) {
    return '0.00'
  }
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function onDrawerKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') {
    return
  }
  // ESC 的层级：先关确认弹窗，再关抽屉，符合"越靠前越优先"的直觉
  if (discardOpen.value) {
    discardOpen.value = false
    return
  }
  if (drawerOpen.value && !saving.value) {
    closeDrawer()
  }
}
window.addEventListener('keydown', onDrawerKeydown)
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onDrawerKeydown)
  document.body.style.overflow = ''
})

watch(drawerOpen, (open) => {
  if (!open) {
    document.body.style.overflow = ''
  }
})

onMounted(load)
</script>

<template>
  <div class="pp-page">

    <header class="pp-header">
      <div>
        <h1>渠道管理</h1>
        <p class="pp-sub">配置各支付渠道参数、控制启用状态，并查看资金概况。</p>
      </div>
      <VButton :loading="loading" @click="load">刷新</VButton>
    </header>

    <section class="pp-stat-row">
      <div class="pp-stat">
        <span class="pp-stat-label">渠道总数</span>
        <strong>{{ methods.length }}</strong>
        <small>{{ configuredCount }} 个已配置完整</small>
      </div>
      <div class="pp-stat">
        <span class="pp-stat-label">已启用</span>
        <strong>{{ enabledCount }}</strong>
        <small>{{ methods.length - enabledCount }} 个停用中</small>
      </div>
      <div class="pp-stat">
        <span class="pp-stat-label">累计成交</span>
        <strong>{{ formatAmount(overview.paidAmount) }}</strong>
        <small>{{ overview.currency }} · {{ overview.paidSessions }}/{{ overview.totalSessions }} 笔</small>
      </div>
      <!-- 与交易流水页口径一致：待审核是离线渠道的钱，运营每天先清这个数 -->
      <div
        :class="['pp-stat', 'is-action', { 'is-attention': overview.pendingReviewSessions > 0 }]"
        role="button"
        tabindex="0"
        title="查看待审核会话"
        @click="goReviewQueue"
        @keydown.enter="goReviewQueue"
      >
        <span class="pp-stat-label">待审核</span>
        <strong>{{ overview.pendingReviewSessions }}</strong>
        <small v-if="overview.pendingReviewSessions > 0">待核对 {{ formatAmount(overview.pendingReviewAmount) }}</small>
        <small v-else>无积压</small>
      </div>
    </section>

    <VAlert v-if="errorMessage" type="error" :title="errorMessage" class="pp-gap" />

    <!-- 工具条 -->
    <div class="pp-toolbar">
      <div class="pp-search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="7" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <input v-model="keyword" type="text" placeholder="搜索渠道名称 / 标识 / 说明" />
        <button v-if="keyword" class="clear-btn" title="清空" @click="keyword = ''">×</button>
      </div>
      <select v-model="statusFilter" class="pp-select">
        <option value="all">全部状态</option>
        <option value="enabled">已启用</option>
        <option value="disabled">已停用</option>
        <option value="unconfigured">待配置</option>
      </select>
      <span class="pp-toolbar-note">
        {{ loading ? '' : `共 ${filteredMethods.length} / ${methods.length} 个渠道` }}
      </span>
    </div>

    <!-- 骨架屏 -->
    <section v-if="loading" class="pp-grid">
      <VCard v-for="i in 6" :key="i" :body-class="['pp-card-body']">
        <div class="pp-skel" style="height: 34px; width: 55%" />
        <div class="pp-skel" style="height: 12px" />
        <div class="pp-skel" style="height: 12px; width: 70%" />
      </VCard>
    </section>

    <!-- 空态 -->
    <VCard v-else-if="!filteredMethods.length">
      <VEmpty
        :title="keyword ? '没有符合条件的渠道' : '暂无支付渠道'"
        :message="keyword ? '换个关键字或筛选条件试试' : '插件内置渠道未加载，请检查插件状态'"
      />
    </VCard>

    <section v-else class="pp-grid">
      <VCard
        v-for="method in filteredMethods"
        :key="method.name"
        class="pp-card"
        :body-class="['pp-card-body']"
        :class="{ 'is-disabled': !method.enabled }"
      >
        <div class="pp-card-head">
          <span class="pp-avatar" :style="{ background: avatarColor(method.name) }">
            {{ method.displayName.slice(0, 1) }}
          </span>
          <div class="pp-card-title">
            <span class="pp-card-name">{{ method.displayName }}</span>
            <code class="pp-tag">{{ method.provider }}</code>
          </div>
          <VSwitch
            :model-value="method.enabled"
            :disabled="busyToggle === method.name"
            :loading="busyToggle === method.name"
            :aria-label="`${method.displayName}：${method.enabled ? '已启用，点击停用' : '已停用，点击启用'}`"
            @change="toggle(method)"
          />
        </div>

        <p class="pp-card-desc">{{ method.description || '暂无说明' }}</p>

        <div class="pp-card-meta">
          <VTag :theme="method.configured ? 'primary' : 'default'">
            {{ method.configured ? '配置完整' : '待配置' }}
          </VTag>
          <VTag v-if="method.requiredSecrets > 0" theme="secondary">
            密钥 {{ method.configuredSecrets }}/{{ method.requiredSecrets }}
          </VTag>
          <VTag v-if="!method.implemented" theme="danger">未实现</VTag>
          <VTag v-for="s in method.scenes" :key="s" theme="default">{{ sceneText(s) }}</VTag>
        </div>

        <!-- 密钥配置进度 -->
        <div
          v-if="method.requiredSecrets > 0"
          class="pp-progress"
          :title="`${method.configuredSecrets}/${method.requiredSecrets}`"
        >
          <i :style="{ width: `${Math.round((method.configuredSecrets / method.requiredSecrets) * 100)}%` }" />
        </div>

        <div class="pp-card-actions">
          <VButton
            size="sm"
            :disabled="!method.implemented"
            :title="method.implemented ? `配置 ${method.displayName}` : '该渠道尚未实现配置表单'"
            @click="openConfig(method)"
          >
            配置
          </VButton>
        </div>
      </VCard>
    </section>

    <!-- 配置抽屉 -->
    <div v-if="drawerOpen" class="pp-drawer-mask" @click.self="closeDrawer">
      <aside class="pp-drawer" role="dialog" :aria-label="activeMethod?.displayName">
        <header class="pp-drawer-head">
          <div>
            <h2>{{ activeMethod?.displayName }}</h2>
            <code class="pp-tag">{{ activeMethod?.name }}</code>
          </div>
          <VButton size="sm" @click="closeDrawer">关闭</VButton>
        </header>

        <div class="pp-drawer-body">
          <VAlert v-if="formError" type="error" :title="formError" />

          <!-- 加载中与「确实没有配置项」必须区分：否则空 schema 会一直显示"加载中" -->
          <div v-if="schemaLoading" class="pp-form-skel">
            <div v-for="i in 3" :key="i" class="pp-skel" style="height: 62px" />
          </div>
          <p v-else-if="!schema.length && !formError" class="pp-hint">
            该渠道暂无可配置项，启用后即可在收银台使用。
          </p>

          <form v-else class="pp-form" @submit.prevent="submit">
            <label v-for="field in schema" :key="field.name" class="pp-field">
              <span class="pp-field-label">
                {{ field.label }}
                <em v-if="field.required">*</em>
                <code v-if="field.secret" class="pp-tag secret">密钥</code>
                <span
                  v-if="field.required && !String(formValues[field.name] ?? '').trim()"
                  class="pp-field-miss"
                >
                  待填写
                </span>
              </span>

              <select
                v-if="field.type === 'select' && field.options?.length"
                v-model="formValues[field.name]"
                class="pp-input"
              >
                <option value="">请选择</option>
                <option v-for="opt in field.options" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </option>
              </select>

              <template v-else-if="field.type === 'textarea'">
                <textarea
                  v-model="formValues[field.name]"
                  class="pp-input pp-textarea"
                  :placeholder="field.placeholder"
                  rows="4"
                  @input="clearFileHint(field.name)"
                />
                <button
                  type="button"
                  class="pp-file-btn"
                  @click.prevent.stop="pickFile(field.name)"
                >
                  从文件读取（.pem / .crt / .txt）
                </button>
                <small v-if="fileHints[field.name]" class="pp-file-hint">
                  {{ fileHints[field.name] }}
                </small>
              </template>

              <input
                v-else-if="field.type === 'number'"
                v-model="formValues[field.name]"
                class="pp-input"
                type="number"
                inputmode="numeric"
                :placeholder="field.placeholder"
              />

              <div v-else-if="field.secret || field.type === 'password'" class="pp-field-wrap">
                <input
                  v-model="formValues[field.name]"
                  class="pp-input"
                  :type="revealSecrets[field.name] ? 'text' : 'password'"
                  :placeholder="field.placeholder"
                  autocomplete="new-password"
                  :style="revealSecrets[field.name] ? 'padding-right: 40px' : ''"
                />
                <button
                  type="button"
                  class="eye"
                  :title="revealSecrets[field.name] ? '隐藏' : '显示'"
                  @click="revealSecrets[field.name] = !revealSecrets[field.name]"
                >
                  {{ revealSecrets[field.name] ? '隐藏' : '显示' }}
                </button>
              </div>

              <input
                v-else
                v-model="formValues[field.name]"
                class="pp-input"
                type="text"
                :placeholder="field.placeholder"
              />

              <small v-if="field.help" class="pp-field-help">{{ field.help }}</small>
              <small v-if="field.secret && formValues[field.name] === '******'" class="pp-field-help">
                当前值为掩码，提交后表示「保持原密钥不变」。
              </small>
            </label>
          </form>
        </div>

        <footer class="pp-drawer-foot">
          <!-- 未保存提示放在按钮旁，用户不必猜"关了会不会丢" -->
          <span v-if="dirty" class="pp-unsaved">有未保存的修改</span>
          <VButton :disabled="saving" @click="closeDrawer">取消</VButton>
          <VButton
            type="primary"
            :disabled="saving || schemaLoading || !schema.length"
            :loading="saving"
            @click="submit"
          >
            {{ saving ? '保存中…' : '保存' }}
          </VButton>
        </footer>
      </aside>
    </div>

    <!-- 有未保存修改时关闭抽屉的二次确认 -->
    <PpConfirm
      :open="discardOpen"
      title="放弃未保存的修改？"
      message="渠道配置尚未保存，关闭后本次填写的内容会丢失。"
      danger
      confirm-text="放弃并关闭"
      @confirm="forceClose()"
      @cancel="discardOpen = false"
    />
  </div>
</template>
