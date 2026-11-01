# 支付中心 · 开发者对接文档

> 插件：`pangu-payment` 1.0.0　适配 Halo `>= 2.27.0`　API 版本 `v1alpha1`
>
> 仓库：<https://github.com/996-dev/996-dev-payment>　作者：996-dev　网站：<https://www.996.tax>　协议：GPL-3.0
>
> 本文档面向接入方（业务系统 / 前端 / 第三方服务）。所有接口均为 HTTP + JSON，
> 开放接口统一前缀 `/apis/api.payment.pangu.tax/v1alpha1`。

---

## 1. 能力概览

插件把「发起支付 → 等待付款 → 确认到账」抽象成一条与业务无关的链路：

```
业务系统 ──创建支付──> 支付中心 ──下单──> 渠道（支付宝/微信/Stripe/易支付…）
                          │
                          ├──回调验签──< 渠道异步通知
                          └──业务通知──> 业务系统 notifyUrl
```

关键设计：

| 能力 | 说明 |
|---|---|
| 业务解耦 | 用 `bizType + bizNo` 关联业务单，不依赖任何订单模型 |
| 幂等 | 相同业务单在 `PENDING` 状态重复下单返回同一会话；也可用 `idempotencyKey` 显式指定 |
| 状态机 | `PENDING → PENDING_REVIEW → PAID`，以及 `CLOSED / EXPIRED / FAILED` 终态 |
| 流水留痕 | 下单、查单、回调、关单、凭证提交、审核各写一条 `PaymentTransaction`，只追加 |
| 渠道路由 | 7 个渠道可插拔，按场景（PC / 移动 / 小程序）过滤 |
| 密钥隔离 | 密钥字段落 Secret，公开配置落 ConfigMap，查询接口只回掩码 |

支持的渠道：

| code | 名称 | 形态 | 到账确认方式 |
|---|---|---|---|
| `alipay` | 支付宝 | 二维码（当面付预下单） | 回调（RSA2 验签）+ 主动查单 |
| `wechatpay` | 微信支付 | 二维码（APIv3 Native） | 回调（平台公钥验签）+ 主动查单 |
| `stripe` | Stripe | 跳转托管收银台 | 回调（Webhook 签名）+ 主动查单 |
| `easypay` | 易支付 | 跳转上游收银台 | 回调（MD5 验签）+ 主动查单 |
| `qrcode` | 静态收款码 | 展示固定二维码 | 人工核销 |
| `bank-transfer` | 银行转账 | 展示对公账户 | 人工核销 |
| `manual` | 人工确认 | 线下付款指引 | 人工核销 |

> `paypal` 仅保留枚举位，未提供实现，接入时返回 `CHANNEL_NOT_IMPLEMENTED`。

### 1.1 会话状态

| phase | 含义 | 谁写入 |
|---|---|---|
| `PENDING` | 等待付款 | 创建会话 |
| `PENDING_REVIEW` | 付款人已提交凭证，等待运营核对 | `POST /payments/{code}/proof` |
| `PAID` | 已确认到账（终态） | 渠道回调 / 主动查单 / 后台核销 |
| `CLOSED` | 主动关闭（终态） | `POST /payments/{code}/close` |
| `EXPIRED` | 超时未支付（终态） | 定时任务 |
| `FAILED` | 渠道下单失败（终态） | 下单异常 |

`PENDING` 与 `PENDING_REVIEW` 属于开放状态，可继续操作；进入终态后不可逆。

### 1.2 审核状态

离线渠道额外维护一组审核字段（其余渠道为 `NONE`）：

| auditState | 含义 |
|---|---|
| `NONE` | 无需审核 |
| `PENDING` | 已提交凭证，待审核 |
| `APPROVED` | 核销通过 |
| `REJECTED` | 已驳回，会话退回 `PENDING` |

---

## 2. 鉴权

### 2.1 API Key

除下列两类请求外，开放接口都必须携带请求头：

```
X-API-Key: <你的密钥>
```

- **放行**：`GET /providers`（只返回渠道元信息，不含任何密钥）
- **放行**：`/callbacks/**`（渠道服务器无法携带自定义头，安全性由渠道签名保障）

API Key 在 Halo 后台「插件 → 支付中心 → 设置 → 接口对接」配置，多个用英文逗号分隔。
**未配置时所有写操作一律拒绝**（返回 `API_KEY_NOT_CONFIGURED`），这是安全默认。

### 2.2 后台接口鉴权

`/apis/console.api.payment.pangu.tax/v1alpha1/**` 走 Halo 登录态 + RBAC，
与 API Key 完全隔离：业务系统的 Key 无法调用后台接口，运营账号也不能代替业务系统下单。

### 2.3 错误响应

```json
{ "code": "UNAUTHORIZED", "message": "API Key 无效", "data": {} }
```

HTTP 状态码见第 8 节。成功响应的 `code` 固定为 `0`。

---

## 3. 接口清单

### 3.1 开放接口（业务系统调用）

| 方法 | 路径 | 说明 | 需 Key |
|---|---|---|---|
| GET | `/providers` | 渠道列表与配置表单结构 | 否 |
| POST | `/payments` | 创建支付 | 是 |
| GET | `/payments/{sessionCode}` | 查询会话详情 | 是 |
| GET | `/payments/{sessionCode}/status` | 查询支付状态（轻量，轮询用） | 是 |
| POST | `/payments/{sessionCode}/sync` | 主动同步渠道状态 | 是 |
| POST | `/payments/{sessionCode}/close` | 关闭会话 | 是 |
| POST | `/payments/{sessionCode}/proof` | 提交付款凭证（仅离线渠道） | 是 |
| GET | `/methods/{methodName}/config` | 读取支付方式公开配置 | 是 |
| PUT | `/methods/{methodName}/config` | 写入支付方式配置 | 是 |
| POST | `/methods/{methodName}/toggle` | 启停支付方式 | 是 |
| GET/POST | `/callbacks/{provider}/{sessionCode}` | 渠道异步通知 | 否 |

### 3.2 后台接口（Console UI 调用，登录态）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/methods` | 渠道列表（含配置完成度） |
| GET | `/methods/{name}/settings` | 配置回显（密钥打码） |
| PUT | `/methods/{name}/settings` | 保存配置 |
| POST | `/methods/{name}/toggle` | 启停渠道 |
| GET | `/sessions` | 会话列表 |
| GET | `/sessions/{code}` | 会话详情 |
| POST | `/sessions/{code}/mark-paid` | 核销为已支付 |
| POST | `/sessions/{code}/review` | 审核（`approve` / `reject`） |
| GET | `/overview` | 概览统计（含待审核积压） |

---

## 4. 创建支付

### 4.1 请求

```
POST /apis/api.payment.pangu.tax/v1alpha1/payments
Content-Type: application/json
X-API-Key: <key>
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `provider` | string | 是 | 渠道 code，见第 1 节表格 |
| `methodName` | string | 否 | 指定支付方式资源名；不填则取该渠道下第一个启用的方式 |
| `amount` | number | 是 | 金额，必须 > 0，保留 2 位小数 |
| `currency` | string | 否 | 币种，默认 `CNY` |
| `subject` | string | 否 | 支付标题，展示在渠道收银台 |
| `description` | string | 否 | 支付描述 |
| `bizType` | string | 否 | 业务类型，默认 `default` |
| `bizNo` | string | 是 | 业务单号，用于幂等与对账 |
| `scene` | string | 否 | `pc-web` / `mobile-web` / `mini-program` |
| `returnUrl` | string | 否 | 支付完成后回跳地址 |
| `notifyUrl` | string | 否 | 业务方异步通知地址，支付成功后 POST 到该地址 |
| `idempotencyKey` | string | 否 | 幂等键，相同键返回同一会话 |
| `bizData` | object | 否 | 业务附加数据，原样透传给渠道实现 |
| `expireMinutes` | number | 否 | 会话有效期；不传时在线渠道 30 分钟、离线渠道 24 小时 |

### 4.2 响应

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "sessionCode": "pm8x2k9f3a1c7e5d0b4a",
    "provider": "alipay",
    "amount": 99.00,
    "currency": "CNY",
    "subject": "年度会员",
    "bizType": "order",
    "bizNo": "ORDER-20261031-001",
    "phase": "PENDING",
    "responseType": "QRCODE_URL",
    "expiresAt": "2026-10-31T23:00:00Z",
    "requiresReview": false,
    "providerTradeNo": "",
    "payment": {
      "responseType": "QRCODE_URL",
      "url": "https://qr.alipay.com/bax0example",
      "params": { "qrCode": "https://qr.alipay.com/bax0example", "outTradeNo": "..." },
      "message": "请使用支付宝扫码支付"
    }
  }
}
```

`responseType` 顶层与 `payment` 内各给一份：顶层供收银台快速分支，`payment` 内是渠道原始载荷。

| 值 | 前端处理方式 |
|---|---|
| `QRCODE_URL` | 把 `url` 转成二维码展示 |
| `REDIRECT_URL` | `location.href = url` |
| `JSAPI` | 用 `params` 调起微信/支付宝 JSSDK |
| `MINI_PROGRAM` | 用 `params` 调起小程序支付 |
| `APP` | 用 `params` 调起原生 App |
| `FORM` | 把 `params` 渲染成隐藏表单并 POST 到 `url` |
| `DIRECT` | 渠道已直连完成，无需前端二次动作 |
| `MANUAL` | 展示 `params` 里的收款信息与 `message` 指引 |

### 4.3 幂等行为

- 相同 `bizType + bizNo` 且状态为 `PENDING` / `PENDING_REVIEW` → 直接返回既有会话，**不会**向渠道重复下单
- 传入 `idempotencyKey` → 以该键为准，忽略 `bizNo` 匹配
- 既有会话已过期或已关闭 → 新建会话

---

## 5. 查询与操作

### 5.1 查询会话

```
GET /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}
```

返回结构与创建接口的 `data` 一致。

### 5.2 查询状态（轮询推荐）

```
GET /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}/status
```

```json
{
  "code": 0, "message": "OK",
  "data": {
    "sessionCode": "pm8x2k9f3a1c7e5d0b4a",
    "phase": "PENDING_REVIEW",
    "paidAt": "",
    "amount": 99.00,
    "currency": "CNY",
    "requiresReview": true,
    "auditState": "PENDING",
    "auditRemark": "",
    "proofSubmittedAt": "2026-11-01T13:20:11Z",
    "expiresAt": "2026-11-04T13:20:11Z"
  }
}
```

前端建议 2–3 秒轮询一次，`phase` 变为 `PAID` / `CLOSED` / `EXPIRED` / `FAILED` 即停止。
`auditRemark` 是给付款人看的驳回原因，务必在收银台展示。

### 5.3 主动同步

```
POST /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}/sync
```

调渠道查单接口回写本地状态，用于**回调丢失**的补偿。对人工类渠道为无操作（直接返回当前状态）。

### 5.4 关闭会话

```
POST /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}/close
```

先调渠道关单，再把会话置为 `CLOSED`。已支付的会话返回 `409 SESSION_ALREADY_PAID`。

---

## 6. 离线渠道：凭证与人工核销

`qrcode` / `bank-transfer` / `manual` 三个渠道没有异步回调，到账靠人工核对。
这段流程由插件内置，业务方只需要「展示收款信息」和「提交凭证」两件事。

```
① 下单        responseType=MANUAL，payment.params 里带收款账户/收款码
② 付款人付款  ← 业务方页面展示收款信息，用户线下转账
③ 提交凭证    POST /payments/{code}/proof  → phase=PENDING_REVIEW
④ 运营核销    ← 后台「交易记录」审阅凭证后通过，phase=PAID 并发布 PaymentSucceededEvent
```

### 6.1 提交凭证

```
POST /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}/proof
X-API-Key: <key>
Content-Type: application/json

{
  "payerName": "张三",
  "payerAccount": "6222****8888",
  "transferNo": "ICBC20261101001",
  "paidAmount": "99.00",
  "paidAt": "2026-11-01 13:20",
  "screenshotUrl": "https://example.com/receipt.png",
  "remark": "已转账，请核对"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `payerName` | 否 | 付款人姓名 |
| `payerAccount` | 否 | 付款账号（建议由调用方自行打码） |
| `transferNo` | 否 | 银行流水号，核销时写入 `providerTradeNo`，对账用 |
| `paidAmount` | 否 | 实付金额；与应付不一致时后台会高亮提示，不阻断提交 |
| `paidAt` | 否 | 付款时间（字符串，原样留档） |
| `screenshotUrl` | 否 | 凭证截图地址，仅接受 `http(s)` 或站内绝对路径 |
| `remark` | 否 | 附言 |

响应结构与查询会话一致，此时 `phase=PENDING_REVIEW`、`auditState=PENDING`，
`paymentProof` 回显已提交的内容。

### 6.2 审核行为

| 事项 | 行为 |
|---|---|
| 幂等 | 同一会话重复提交凭证视为补充材料，覆盖旧凭证，不新建会话 |
| 审核人 | 取自后台登录态，前端传了也会被忽略，防止伪造核销 |
| 驳回 | `phase` 退回 `PENDING`，`auditState=REJECTED`，`auditRemark` 为驳回原因（必填） |
| 核销单号 | 取凭证里的 `transferNo` 写入 `providerTradeNo` |
| 非离线渠道 | 对 `alipay`/`wechatpay` 等提交凭证返回 400，避免误用 |
| 有效期 | 离线渠道下单默认 24 小时；提交凭证后自动顺延至 72 小时审核保留期 |

核销通过后照常触发 `PaymentSucceededEvent`，监听方代码对离线渠道与在线渠道完全一致。

---

## 7. 回调机制

### 7.1 渠道 → 支付中心

渠道异步通知统一入口（渠道侧配置的回调地址）：

```
{站点地址}/apis/api.payment.pangu.tax/v1alpha1/callbacks/{provider}/{sessionCode}
```

插件按 `provider` 分发到对应实现验签，验签失败一律拒绝，金额不一致同样拒绝入账。
各渠道返回体要求：

| 渠道 | 成功响应 | 说明 |
|---|---|---|
| 支付宝 | `success`（纯文本） | 非此内容会持续重试 |
| 微信支付 | `{"code":"SUCCESS","message":"成功"}` | 验签用平台公钥 |
| Stripe | `{"received":true}` | 只处理 `checkout.session.completed` |
| 易支付 | `success`（纯文本） | MD5 验签 |

> **无需手工配置回调地址**：下单时插件按请求 Host 自动推导并传给渠道。
> 若站点在反向代理后面，请确保 `X-Forwarded-Proto` / `X-Forwarded-Host` 正确传递。

### 7.2 支付中心 → 业务系统

会话变为 `PAID` 后，插件向创建时传入的 `notifyUrl` 发起：

```
POST {notifyUrl}
Content-Type: application/json

{
  "sessionCode": "pm8x2k9f3a1c7e5d0b4a",
  "bizType": "order",
  "bizNo": "ORDER-20261031-001",
  "provider": "alipay",
  "amount": 99.00,
  "currency": "CNY",
  "paidAt": "2026-10-31T22:31:05Z",
  "providerTradeNo": "202610312200example"
}
```

业务方返回任意 `2xx` 视为成功；非 `2xx` 会按后台配置的重试次数重试。
可在后台设置「业务通知附加请求头」加一条自定义头（如 `X-Sign-Source:pangu-payment`）便于校验来源。

**验签建议**：业务方收到通知后，务必再用 `GET /payments/{sessionCode}/status`
主动确认一次，不要仅凭通知内容发货。

---

## 8. 错误码

| code | HTTP | 含义 | 处理建议 |
|---|---|---|---|
| `UNAUTHORIZED` | 401 | API Key 无效或缺失 | 检查请求头 |
| `API_KEY_NOT_CONFIGURED` | 401 | 插件未配置 API Key | 后台设置里填写 |
| `INVALID_REQUEST` | 400 | 请求体为空或格式错误 | 检查 body |
| `INVALID_AMOUNT` | 400 | 金额 ≤ 0 | 修正金额 |
| `INVALID_PROVIDER` | 400 | 渠道 code 不存在 | 对照渠道表 |
| `INVALID_BIZ_NO` | 400 | 缺业务单号 | 补 `bizNo` |
| `METHOD_NOT_FOUND` | 404 | 该渠道没有启用的支付方式 | 后台启用或填 `methodName` |
| `CHANNEL_NOT_IMPLEMENTED` | 404 | 渠道未实现（如 paypal） | 换渠道 |
| `CHANNEL_NOT_FOUND` | 404 | 渠道 code 未注册 | 检查 `provider` |
| `SESSION_NOT_FOUND` | 404 | 会话不存在 | 检查 sessionCode |
| `SESSION_ALREADY_PAID` | 409 | 已支付会话不能关闭 | 忽略 |
| `INITIATE_FAILED` | 500 | 渠道下单失败 | 看 `message` 与插件日志 |
| `INTERNAL_ERROR` | 500 | 未预期异常 | 查 Halo 日志 |

---

## 9. 对接示例

### 9.1 curl

```bash
curl -X POST https://www.996.tax/apis/api.payment.pangu.tax/v1alpha1/payments \
  -H "Content-Type: application/json" \
  -H "X-API-Key: 8f3c1d2e9a7b4c60" \
  -d '{
    "provider": "alipay",
    "amount": 99.00,
    "subject": "年度会员",
    "bizType": "order",
    "bizNo": "ORDER-20261031-001",
    "returnUrl": "https://example.com/pay/result",
    "notifyUrl": "https://example.com/api/pay/notify"
  }'
```

### 9.2 Java（Spring RestTemplate）

```java
Map<String, Object> body = Map.of(
    "provider", "wechatpay",
    "amount", new BigDecimal("99.00"),
    "subject", "年度会员",
    "bizType", "order",
    "bizNo", "ORDER-20261031-001",
    "notifyUrl", "https://example.com/api/pay/notify"
);
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);
headers.set("X-API-Key", API_KEY);
ResponseEntity<Map> resp = restTemplate.postForEntity(
    "https://www.996.tax/apis/api.payment.pangu.tax/v1alpha1/payments",
    new HttpEntity<>(body, headers), Map.class);
```

### 9.3 Node.js（fetch）

```javascript
const res = await fetch('https://www.996.tax/apis/api.payment.pangu.tax/v1alpha1/payments', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-API-Key': process.env.PAY_API_KEY },
  body: JSON.stringify({
    provider: 'stripe',
    amount: 99.0,
    subject: '年度会员',
    bizType: 'order',
    bizNo: 'ORDER-20261031-001',
    notifyUrl: 'https://example.com/api/pay/notify',
  }),
});
const { data } = await res.json();
if (data.responseType === 'REDIRECT_URL') location.href = data.payment.url;
```

### 9.4 PHP

```php
$payload = json_encode([
  'provider' => 'alipay',
  'amount'   => 99.00,
  'subject'  => '年度会员',
  'bizType'  => 'order',
  'bizNo'    => 'ORDER-20261031-001',
  'notifyUrl'=> 'https://example.com/api/pay/notify',
]);
$ch = curl_init('https://www.996.tax/apis/api.payment.pangu.tax/v1alpha1/payments');
curl_setopt_array($ch, [
  CURLOPT_POST => true,
  CURLOPT_POSTFIELDS => $payload,
  CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'X-API-Key: ' . getenv('PAY_API_KEY')],
  CURLOPT_RETURNTRANSFER => true,
]);
$result = json_decode(curl_exec($ch), true);
```

---

## 10. 渠道配置

渠道参数通过后台「渠道管理」写入，分为**公开配置**（ConfigMap）与**密钥配置**（Secret）两部分。
密钥字段永远不会随查询接口返回，回显统一替换为 `******`；提交时回传 `******` 表示保持原值。

### 10.1 各渠道字段

**支付宝 `alipay`**

| 字段 | 必填 | 密钥 | 说明 |
|---|---|---|---|
| `appId` | 是 | 否 | 应用 AppID |
| `merchantPrivateKey` | 是 | **是** | 应用私钥（RSA2，可带 PEM 头尾，插件会自动清洗） |
| `alipayPublicKey` | 是 | 否 | 支付宝公钥 |
| `encryptKey` | 否 | **是** | 接口内容加密密钥（AES） |
| `serverUrl` | 否 | 否 | 网关地址，默认 `https://openapi.alipay.com/gateway.do` |

**微信支付 `wechatpay`**

| 字段 | 必填 | 密钥 | 说明 |
|---|---|---|---|
| `appId` | 是 | 否 | 应用 AppID |
| `merchantId` | 是 | 否 | 商户号 |
| `merchantSerialNumber` | 是 | 否 | 商户证书序列号 |
| `merchantPrivateKey` | 是 | **是** | 商户私钥（apiclient_key.pem 内容） |
| `apiV3Key` | 是 | **是** | APIv3 密钥（32 位） |
| `platformPublicKey` | 是 | 否 | 微信支付平台公钥 |
| `platformPublicKeyId` | 是 | 否 | 平台公钥 ID |

**Stripe `stripe`**

| 字段 | 必填 | 密钥 | 说明 |
|---|---|---|---|
| `apiKey` | 是 | **是** | Secret Key，`sk_` 开头 |
| `webhookSecret` | 是 | **是** | Webhook 签名密钥，`whsec_` 开头 |

**易支付 `easypay`**

| 字段 | 必填 | 密钥 | 说明 |
|---|---|---|---|
| `serverUrl` | 是 | 否 | 上游接口地址 |
| `merchantId` | 是 | 否 | 商户 ID（pid） |
| `merchantPrivateKey` | 是 | **是** | 商户密钥 |
| `type` | 是 | 否 | 支付类型，`alipay` 或 `wxpay` |
| `cid` | 否 | 否 | 通道 ID |

**静态码 `qrcode`**

| 字段 | 必填 | 说明 |
|---|---|---|
| `qrcodeImageUrl` | 是 | 收款码图片地址，须为 http(s) 或站内绝对路径 |
| `instructions` | 否 | 付款指引 |

**银行转账 `bank-transfer` / 人工确认 `manual`**

| 字段 | 必填 | 说明 |
|---|---|---|
| `bankName` | 是 | 银行名称 |
| `bankAddress` | 否 | 开户行支行 |
| `accountName` | 是 | 收款户名 |
| `accountNumber` | 是 | 收款账号 |
| `instructions` | 否 | 付款指引 |

### 10.2 配置接口

一般用后台界面即可；需要脚本化时走后台接口（登录态）：

```
PUT /apis/console.api.payment.pangu.tax/v1alpha1/methods/{methodName}/settings
Content-Type: application/json

{ "appId": "2021000000000000", "merchantPrivateKey": "MIIEv...", "alipayPublicKey": "MIIBIj..." }
```

插件按各渠道 schema 中的 `secret` 标记自动分流：标为密钥的字段写入 Secret，其余写入 ConfigMap。
保存前会先跑渠道自身的校验，缺必填项直接拒绝，避免写入半成品配置。

---

## 11. 运维

### 11.1 数据资源

| Kind | 存储位置 | 说明 |
|---|---|---|
| `PaymentSession` | `extensions` 表 | 支付会话 |
| `PaymentTransaction` | `extensions` 表 | 交易流水，只追加 |
| `PaymentMethod` | `extensions` 表 | 支付方式实例 |
| ConfigMap `pangu-payment-config` | `extensions` 表 | 渠道公开配置 |
| Secret `pangu-payment-secret` | `extensions` 表 | 渠道密钥 |

### 11.2 对账建议

```sql
-- 按业务单查支付记录
SELECT name, JSON_EXTRACT(DATA,'$.spec.sessionCode'),
       JSON_EXTRACT(DATA,'$.spec.bizNo'),
       CONVERT(JSON_EXTRACT(DATA,'$.status.phase') USING utf8mb4)
FROM extensions
WHERE name LIKE '/registry/payment.pangu.tax/paymentsessions/%'
  AND JSON_EXTRACT(DATA,'$.spec.bizNo') = 'ORDER-20261031-001';
```

离线渠道还可以按 `spec.providerTradeNo`（即银行流水号）与银行对账单逐笔勾兑。

### 11.3 定时任务

插件启动后按固定频率跑超时扫描：`PENDING` 会话超过 `expiresAt` 置为 `EXPIRED`，
`PENDING_REVIEW` 超过审核保留期同样关闭。无需额外配置 Halo 的定时任务。

### 11.4 常见问题

**回调收不到**

1. 站点必须能被渠道服务器访问（`localhost` 不行，需公网地址或内网穿透）
2. 反向代理需透传 `X-Forwarded-Proto` 与 `X-Forwarded-Host`
3. 渠道后台配置的回调域名要与站点域名一致

**回调验签失败**

- 支付宝：确认用的是「支付宝公钥」而非「应用公钥」
- 微信：确认使用的是平台公钥模式，且 `platformPublicKeyId` 与证书匹配
- Stripe：`webhookSecret` 必须与 Stripe Dashboard 里该端点的签名密钥一致

**下单返回 `METHOD_NOT_FOUND`**

- 该渠道没有启用的 `PaymentMethod`，先到后台启用，或显式传 `methodName`

**提交凭证返回 400**

- 只有 `qrcode` / `bank-transfer` / `manual` 接受凭证；在线渠道请等待回调，或调用 `/sync` 主动查单

---

## 12. 设计说明

几处与常见支付集成的取舍，便于接入方判断是否适配：

| 项 | 本插件做法 | 原因 |
|---|---|---|
| 业务关联 | `bizType + bizNo + bizData` | 支付内核不绑定订单模型，会员、商城、内容付费、打赏都能直接用 |
| 配置存储 | ConfigMap + Secret 分流 | 密钥不以明文进扩展资源，查询接口只回掩码 |
| 开放鉴权 | `X-API-Key` | 面向服务端对接，不依赖用户登录态 |
| 回调入口 | 统一 `/callbacks/{provider}/{code}` | 一个入口按渠道分发，新增渠道不用加路由 |
| 场景选择 | 调用方显式传 `scene` | 不再根据 User-Agent 猜，行为可预测 |
| 配置读取 | 每次下单实时读 ConfigMap/Secret | 改配置立即生效，不需要缓存失效通知 |
| 到账确认 | 回调 / 查单 / 人工核销三路收敛到一个事件 | 业务方只需监听 `PaymentSucceededEvent` |
