import { fileURLToPath, URL } from 'url'

// 2.27 起该包不再提供 "." 导出，只能按子路径引入（vite / rsbuild 两套构建器）
import { viteConfig } from '@halo-dev/ui-plugin-bundler-kit/vite'

/**
 * Halo Console 插件前端构建配置。
 *
 * <p>产物由 ui-plugin-bundler-kit 决定：输出到插件后端资源目录
 * `src/main/resources/console/main.js` 与 `style.css`，
 * 打包进插件 jar 后由 Halo 在后台加载并注册菜单。
 */
export default viteConfig({
  vite: {
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
  },
})
