import { definePlugin } from '@halo-dev/console-shared'
import { defineComponent, h, markRaw } from 'vue'
import './styles/payment.css'
import PaymentMethodsView from './views/PaymentMethodsView.vue'
import TransactionsView from './views/TransactionsView.vue'

/**
 * 菜单图标：内联 SVG，不依赖 @halo-dev/components。
 *
 * <p>原因是插件编译期使用的 components 版本（2.21）与后台运行时提供的版本
 * （2.27）不一致，bundler-kit 会给出 best-effort 警告；
 * 菜单图标属高频渲染项，直接内联可彻底避开图标导出名变更的风险。
 *
 * <p>统一走 {@link icon} 工厂：两个菜单必须共用同一套描边参数（viewBox / 线宽 /
 * 端点样式），否则左侧菜单里会出现一粗一细、大小不一的图标。
 */
type SvgChild = [tag: string, attrs: Record<string, string | number>]

function icon(name: string, children: SvgChild[]) {
  return markRaw(
    defineComponent({
      name,
      render: () =>
        h(
          'svg',
          {
            viewBox: '0 0 24 24',
            width: '1em',
            height: '1em',
            fill: 'none',
            stroke: 'currentColor',
            'stroke-width': 1.7,
            'stroke-linecap': 'round',
            'stroke-linejoin': 'round',
            'aria-hidden': 'true',
          },
          children.map(([tag, attrs]) => h(tag, attrs))
        ),
    })
  )
}

/** 渠道管理：银行卡 */
const IconPayment = icon('PanguPaymentIcon', [
  ['rect', { x: 2, y: 5, width: 20, height: 14, rx: 2.5 }],
  ['path', { d: 'M2 10h20' }],
  ['path', { d: 'M6 15h4' }],
])

/** 交易流水：三条账目线 + 金额节点，与银行卡图标区分明显 */
const IconTransactions = icon('PanguTransactionsIcon', [
  ['path', { d: 'M4 6h9' }],
  ['path', { d: 'M4 12h13' }],
  ['path', { d: 'M4 18h7' }],
  ['circle', { cx: 18, cy: 6, r: 2.2 }],
  ['circle', { cx: 15, cy: 18, r: 2.2 }],
])

/**
 * 支付中心 Console 插件入口。
 *
 * <p>注册左侧菜单「支付中心」，下辖两个页面：渠道管理（配置参数与启停）
 * 与交易流水（查询会话、手工核销）。两者 group 相同，会被合并为同一个一级分组。
 *
 * <p>不声明 meta.permissions：UI 权限点需要先在 Role 的 ui-permissions 注解里注册，
 * 未注册就声明会导致菜单被静默过滤。访问控制由后端端点完成——
 * `/apis/console.api.payment.pangu.tax` 已通过 Role 聚合限制为超管，
 * 无权限用户进入页面会看到明确的 403 提示。
 */
export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/payment/methods',
        name: 'PaymentMethods',
        component: PaymentMethodsView,
        meta: {
          title: '渠道管理',
          searchable: true,
          menu: {
            name: '渠道管理',
            group: '支付中心',
            icon: IconPayment,
            priority: 10,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/payment/sessions',
        name: 'PaymentTransactions',
        component: TransactionsView,
        meta: {
          title: '交易流水',
          searchable: true,
          menu: {
            name: '交易流水',
            group: '支付中心',
            icon: IconTransactions,
            priority: 11,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
