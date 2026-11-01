# 支付中心

简体中文 | [English](./README_EN.md)

为 Halo 2.27+ 提供与业务无关的统一支付能力：多渠道收银台、支付会话状态机、离线凭证人工审核，以及面向外部系统的开放 API 与面向插件的 Java 扩展点。

![](./images/preview-channels.png)

## 功能特性

- **七大内置渠道**：支付宝（当面付）、微信支付（APIv3 Native）、Stripe（Checkout）、易支付（聚合协议）、静态收款码、银行转账、人工确认
- **统一支付会话**：以 `bizType + bizNo` 关联业务单，幂等下单，重复请求返回同一会话，不会重复收款
- **显式状态机**：`PENDING`、`PENDING_REVIEW`、`PAID`、`CLOSED`、`EXPIRED`、`FAILED` 的流转集中在状态服务里，禁止外部直改
- **三路确认收敛为一个事件**：渠道回调验签、主动查单补偿、离线凭证人工核销，最终只发出一次 `PaymentSucceededEvent`
- **离线渠道审核闭环**：付款人提交凭证 → 待审核 → 后台核销或驳回，记录审核人、时间与依据；提交凭证后有效期自动顺延，不会出现「钱到了单却过期」
- **密钥分级存储**：密钥字段落 Halo `Secret`，公开配置落 `ConfigMap`，所有读接口只返回掩码
- **开放 API**：`X-API-Key` 鉴权配合 HMAC 签名，任意语言的外部系统均可接入
- **Java 扩展点**：依赖 `api` 模块直接调用 `PaymentGateway`，或实现 `PaymentProviderExtension` 接入自建渠道
- **只追加流水**：下单、查单、回调、关单、凭证、审核各写一条交易记录，可对账可追溯
- **控制台界面**：基于 `@halo-dev/components` 与 Halo 设计令牌实现，自动跟随主题明暗

## 界面预览

![](./images/preview-channels.png)

![](./images/preview-channel-settings.png)

![](./images/preview-transactions.png)

![](./images/preview-review.png)

## 快速开始

1. 在 [Releases](https://github.com/996-dev/996-dev-payment/releases) 下载 `996-dev-payment-<version>.zip`，解压取出其中的插件 jar
2. Halo 后台「插件 → 安装」上传该 jar 并启用
3. 进入「支付中心 → 渠道管理」，启用并配置需要使用的渠道（私钥、证书等字段支持从 `.pem`、`.crt`、`.txt` 文件直接读取）
4. 在「插件 → 支付中心 → 设置 → 接口对接」生成 API Key，业务系统凭 `X-API-Key` 调用支付接口

一条命令创建支付：

```bash
curl -X POST https://your-site/apis/api.payment.pangu.tax/v1alpha1/payments \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <你的密钥>" \
  -d '{"provider":"alipay","amount":99.00,"subject":"年度会员","bizType":"order","bizNo":"ORDER-001"}'
```

## 对接方式

| 方式 | 适用场景 | 文档 |
| --- | --- | --- |
| HTTP 开放 API | 任意语言的外部系统、前端收银台 | [docs/API.md](./docs/API.md) |
| Java 扩展点 `PaymentGateway` | Halo 插件（商城、会员、打赏等） | [docs/INTEGRATION.md](./docs/INTEGRATION.md) |
| `PaymentSucceededEvent` 事件 | 需要在支付成功后执行逻辑的插件 | [docs/INTEGRATION.md](./docs/INTEGRATION.md) |
| `PaymentProviderExtension` 扩展点 | 需要接入新渠道的插件作者 | [docs/INTEGRATION.md](./docs/INTEGRATION.md) |

## 项目结构

本项目为多模块 Gradle 项目：

| 模块 | 说明 |
| --- | --- |
| `api/` | 对外发布的 Java SDK（`run.pangu.payment:api`）。其他 Halo 插件依赖此模块即可调用支付能力 |
| `src/` | 插件实现模块。包含 Extension 定义、渠道实现、Endpoint、Service 与 RBAC 配置 |
| `ui/` | 基于 Vue 3 + Vite 的控制台界面，用于渠道配置与交易流水管理 |
| `docs/` | 开放接口与插件集成文档 |
| `images/` | README 界面预览图 |

## 开发环境

- Java 21
- Node.js 18+
- npm
- Gradle 9.6.1（仓库内置 wrapper，无需单独安装）

## 开发

```bash
# 1. 构建控制台前端产物（输出到 ui/build/dist）
cd ui && npm install && npm run build && cd ..

# 2. 构建插件
./gradlew build

# 仅编译检查
./gradlew compileJava

# 运行测试
./gradlew test
```

前端产物会在 `processResources` 之后被复制进 `build/resources/main/console`，随 jar 一起交付；未执行前端构建时该步骤为空操作，不影响后端编译。

构建完成后，插件 jar 位于 `build/libs/` 目录。修改代码后，在 Halo 后台「插件 → 支付中心 → 卸载/安装」重新上传即可生效，配置数据保留。

## 许可证

[GPL-3.0](./LICENSE) © 996-dev
