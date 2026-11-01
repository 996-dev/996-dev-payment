import { Toast } from '@halo-dev/components'

/**
 * 全局轻提示。
 *
 * <p>直接用控制台的 Toast，不再自己渲染一套：颜色、圆角、进出场动画
 * 与后台其他页面一致，也不用维护定位和层级。
 */
export type ToastType = 'success' | 'error' | 'info'

const DISPATCH = {
  success: Toast.success,
  error: Toast.error,
  info: Toast.info,
} as const

export function toast(type: ToastType, text: string, duration = 2600) {
  DISPATCH[type](text, { duration })
}

/** 复制文本到剪贴板，成功后弹提示。 */
export async function copyText(text: string, label = '内容'): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    toast('success', `${label}已复制`)
    return true
  } catch {
    // 非 HTTPS 或无权限时退回 execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch {
      ok = false
    }
    document.body.removeChild(ta)
    toast(ok ? 'success' : 'error', ok ? `${label}已复制` : '复制失败，请手动选择复制')
    return ok
  }
}
