<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { VButton } from '@halo-dev/components'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message: string
    danger?: boolean
    loading?: boolean
    confirmText?: string
    cancelText?: string
    /** 是否显示备注输入框（核销场景用） */
    remark?: boolean
    /** 备注是否必填：必填且为空时确认按钮不可点，避免"点了没反应" */
    remarkRequired?: boolean
    remarkPlaceholder?: string
  }>(),
  {
    danger: false,
    loading: false,
    confirmText: '确认',
    cancelText: '取消',
    remark: false,
    remarkRequired: false,
    remarkPlaceholder: '备注（可选），例如：已线下收款核对',
  }
)

const emit = defineEmits<{
  confirm: [remark: string]
  cancel: []
}>()

const remarkText = ref('')
const remarkEl = ref<HTMLTextAreaElement | null>(null)

/** 必填但没填 → 按钮置灰，比点了弹出 toast 更早一步拦住 */
const blocked = computed(
  () => props.loading || (props.remark && props.remarkRequired && !remarkText.value.trim())
)

watch(
  () => props.open,
  async (open) => {
    if (open) {
      remarkText.value = ''
      document.body.style.overflow = 'hidden'
      await nextTick()
      // 需要填备注时直接给焦点，省一次点击；否则聚焦主按钮便于回车确认
      if (props.remark) {
        remarkEl.value?.focus()
      } else {
        document.querySelector<HTMLButtonElement>('.pp-modal-foot .pp-modal-primary')?.focus()
      }
    } else {
      document.body.style.overflow = ''
    }
  }
)

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open && !props.loading) {
    emit('cancel')
  }
  // Ctrl/Cmd + Enter 提交：填长备注时手不用离开键盘
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && props.open && !blocked.value) {
    onConfirm()
  }
}
window.addEventListener('keydown', onKeydown)
// 组件卸载必须摘掉监听：页面切走后再按 ESC 会往已销毁的组件发事件
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})

function onConfirm() {
  if (blocked.value) {
    return
  }
  emit('confirm', remarkText.value.trim())
}

function onMaskClick() {
  // 提交中不允许点遮罩关掉，否则请求回来时目标行已不存在
  if (!props.loading) {
    emit('cancel')
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="pp-modal-mask" @click.self="onMaskClick">
      <div class="pp-modal" role="alertdialog" aria-modal="true" :aria-label="title">
        <h3>{{ title }}</h3>
        <p class="msg">{{ message }}</p>
        <textarea
          v-if="remark"
          ref="remarkEl"
          v-model="remarkText"
          class="remark"
          :placeholder="remarkPlaceholder"
          :aria-label="remarkPlaceholder"
          :aria-required="remarkRequired"
          rows="3"
        />
        <div class="pp-modal-foot">
          <VButton :disabled="loading" @click="emit('cancel')">{{ cancelText }}</VButton>
          <VButton
            class="pp-modal-primary"
            :type="danger ? 'danger' : 'primary'"
            :disabled="blocked"
            :loading="loading"
            @click="onConfirm"
          >
            {{ loading ? '处理中…' : confirmText }}
          </VButton>
        </div>
      </div>
    </div>
  </Teleport>
</template>
