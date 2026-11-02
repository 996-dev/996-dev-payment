# 与其他插件交互

> 仓库：<https://github.com/996-dev/996-dev-payment>　作者：996-dev　网站：<https://www.996.tax>　协议：GPL-3.0
>
> 本文档面向 Halo 插件开发者：如何在自有插件里调用支付能力、接收支付结果、或接入新的支付渠道。
> 纯 HTTP 对接（非 Java）请看 [API.md](./API.md)。

本插件只负责**收钱**：创建支付会话、驱动渠道、验签回调、回写支付状态、对账。
订单状态、发货、库存、退款审批等交易语义由调用方插件自己维护。

对其他插件暴露的能力有三个：

| 能力 | 形式 | 扩展点类型 | 用途 |
|---|---|---|---|
| 发起支付 / 查单 / 关单 | `PaymentGateway` | SINGLETON | 业务插件下单 |
| 接入新支付渠道 | `PaymentProviderExtension` | MULTI_INSTANCE | 第三方渠道扩展 |
| 支付成功通知 | `PaymentSucceededEvent` | 事件 | 履约（发货、开通会员） |

---

## 一、共享 Java API 模块

稳定契约单独放在 `api/` 子模块，发布坐标为：

```
run.pangu.payment:996-dev-payment-api:1.0.0
```

模块内只有接口与 DTO，不含任何实现类，`build.gradle` 里 Halo API 与 Reactor 均为
`compileOnly`，不会把宿主类夹带进使用方的 jar。

### 使用方依赖配置

```groovy
// 只编译期需要，运行时由 996-dev-payment 插件提供同一份类型
dependencies {
    compileOnly 'run.pangu.payment:996-dev-payment-api:1.0.0'
}
```

```yaml
# plugin.yaml
spec:
  pluginDependencies:
    996-dev-payment: ">=1.0.0"
```

两点硬要求：

1. **必须用 `compileOnly`**。若用 `implementation`，使用方会把接口类重复打进自己 jar，
   Halo 的插件类加载器会得到两份同名类，`ExtensionGetter` 按类型匹配时直接失败。
2. **必须声明 `pluginDependencies`**。Halo 据此把两个插件的类加载器组合起来，
   使用方才能解析到 `run.pangu.payment.api` 包；未声明时运行期会 `NoClassDefFoundError`。

版本范围与 API 版本一一对应：破坏二进制兼容（改方法签名、删字段）时主版本 +1，
同时收紧依赖上界。

---

## 二、发起支付

```java
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.pangu.payment.api.*;

@Component
@RequiredArgsConstructor
public class OrderPayService {

    private final ExtensionGetter extensionGetter;

    public Mono<PaymentCreateResult> pay(Order order, String provider) {
        PaymentCreateCommand command = new PaymentCreateCommand(
            provider,                      // alipay / wechatpay / manual ...
            null,                          // methodName，null 时取该渠道第一个启用的配置
            "order",                       // bizType
            order.getId(),                 // bizNo
            order.getPayableAmount(),      // amount
            "CNY",
            "订单 " + order.getSn(),        // subject
            null,                          // description
            "https://example.com/pay/result?order=" + order.getId(), // returnUrl
            null,                          // notifyUrl，本插件已统一回调，一般不需要
            "order:" + order.getId(),      // idempotencyKey
            null                           // clientIp
        );
        return extensionGetter.getEnabledExtension(PaymentGateway.class)
            .switchIfEmpty(Mono.error(new IllegalStateException("支付插件未启用")))
            .flatMap(gateway -> gateway.create(command));
    }
}
```

`PaymentCreateResult` 的处理方式由 `responseType` 决定：

| responseType | 前端动作 |
|---|---|
| `REDIRECT` | 跳转 `url` |
| `QRCODE_URL` | 把 `url` 当图片展示 |
| `QRCODE_CONTENT` | 用 `payload.qrCodeContent` 自行渲染二维码 |
| `JSAPI` | 用 `payload` 调起微信/支付宝 JSSDK |
| `MANUAL` | 展示 `payload` 里的收款账户与 `message` 指引 |

`reused=true` 表示命中幂等（同一 `idempotencyKey` 的重复调用），可直接复用返回的会话。

### 查单与关单

```java
Mono<PaymentSessionView> status = gateway.get(sessionCode);
Mono<PaymentSessionView> closed = gateway.close(sessionCode, "用户取消");
```

`get` 返回的是本地状态快照，不触发渠道查单；需要拉取渠道最新状态时走管理端
`POST /apis/console.api.payment.pangu.tax/v1alpha1/sessions/{code}/sync`。

### 离线渠道：凭证提交与人工核销

`qrcode`（静态收款码）、`bank-transfer`（银行转账）、`manual`（人工确认）三个渠道
没有异步回调，钱是否到账只能靠人看。本插件把这段流程收敛成固定四步，
**业务方不需要自己实现审核，也不需要自己判断什么时候算支付成功**：

```
① 下单        create() → responseType=MANUAL，payload 里带收款账户/收款码
② 付款人付款  ← 业务方页面展示收款信息，用户线下转账
③ 提交凭证    POST /payments/{sessionCode}/proof  → phase=PENDING_REVIEW
④ 运营核销    ← 后台「交易记录」页点"通过"，phase=PAID 并发布 PaymentSucceededEvent
```

业务方只需要做两件事：展示 `payload` 里的收款信息；把付款凭证 POST 上去。

```java
// 付款人填完凭证后调用（Java 走 HTTP，凭证接口没有共享 API 方法）
// POST /apis/api.payment.pangu.tax/v1alpha1/payments/{sessionCode}/proof
// {
//   "payerName": "张三", "payerAccount": "6222****8888",
//   "transferNo": "ICBC20261101001",   // 银行流水号，核销时作为交易号
//   "paidAmount": "12.34", "paidAt": "2026-11-01 11:20",
//   "screenshotUrl": "https://.../receipt.png", "remark": "已转账"
// }
```

关键约定：

| 事项 | 行为 |
|---|---|
| 幂等 | 同一会话重复提交凭证视为补充材料，覆盖旧凭证，不新建会话 |
| 审核人 | 取自后台登录态，前端传了也会被忽略，防止伪造核销 |
| 驳回 | `phase` 退回 `PENDING`，`auditState=REJECTED`，`auditRemark` 即驳回原因 |
| 核销单号 | 取凭证里的 `transferNo`，写进 `providerTradeNo`，对账时用它匹配银行流水 |
| 非离线渠道 | 对 `alipay`/`wechatpay` 等提交凭证直接 400，避免误用 |
| 有效期 | 离线渠道默认 24 小时（在线渠道 30 分钟），下单时可用 `expireMinutes` 覆盖 |
| 审核期 | 提交凭证后有效期自动顺延到 72 小时，避免"钱已到账但会话被超时关掉" |

**驳回原因是给付款人看的**，`PaymentSessionView.auditRemark` 会原样返回，
业务方应把它展示在收银台，否则用户不知道该改什么。

核销通过后照常触发 `PaymentSucceededEvent`，所以监听方代码对离线渠道
和在线渠道完全一致——这是把三条确认路径收敛到一个事件的意义。

---

## 三、接收支付成功事件

```java
@Component
public class OrderPaidListener {

    @EventListener
    public void onPaid(PaymentSucceededEvent event) {
        if (!"order".equals(event.bizType())) {
            return;
        }
        orderService.markPaid(event.bizNo(), event.amount(), event.providerTradeNo());
    }
}
```

事件在会话**首次**置为 `PAID` 时发布一次，重复回调/重复核销不会重复发。
三条确认路径（渠道回调、主动查单、人工核销）最终都收敛到这一个事件，
订阅方不需要关心钱是怎么到账的。

事件是同步派发：监听方法里不要做长耗时操作，也不要 `block()`。

---

## 四、接入新的支付渠道（扩展点实现）

```java
import org.springframework.stereotype.Component;
import run.pangu.payment.api.*;

@Component
public class CustomPayProvider implements PaymentProviderExtension {

    @Override
    public String getCode() {
        return "custompay";                 // 全局唯一，不能与内置渠道同名
    }

    @Override
    public String getDisplayName() {
        return "CustomPay";
    }

    @Override
    public List<String> getSupportedScenes() {
        return List.of("PC_WEB", "MOBILE_WEB");
    }

    @Override
    public List<PaymentConfigField> getConfigFormSchema() {
        return List.of(
            PaymentConfigField.text("merchantId", "商户号", true),
            PaymentConfigField.secret("apiKey", "API Key", true)
        );
    }

    @Override
    public Mono<ProviderInitiateResult> initiate(ProviderInitiateRequest request) {
        String apiKey = request.settings().get("apiKey");
        // 同步 SDK 必须切线程，不能占用 Netty 事件循环
        return Mono.fromCallable(() -> customPayClient.createOrder(
                request.sessionCode(), request.amount(), apiKey))
            .subscribeOn(Schedulers.boundedElastic())
            .map(ProviderInitiateResult::redirect);
    }

    @Override
    public Mono<ProviderCallbackResult> handleCallback(ProviderCallbackRequest request) {
        if (!customPaySigner.verify(request.headers(), request.body(),
                request.settings().get("apiKey"))) {
            return Mono.just(ProviderCallbackResult.rejected(400, "bad signature"));
        }
        return Mono.just(ProviderCallbackResult.paid(
            request.sessionCode(), "CUSTOM-TRADE-NO", null, "success"));
    }
}
```

落库后即可在后台「渠道管理」看到该渠道，配置项按 `getConfigFormSchema()` 自动渲染，
密钥字段自动写入 Secret 并在回显时打码。

契约要点：

- `initiate` 必须可重入，同一 `sessionCode` 重复调用不得产生两笔渠道订单。
- 回调验签由实现方负责，本插件只负责根据 `paid` 结果改状态。
- `amount` 返回非空时本插件会与本地金额比对，不一致直接拒绝入账。
- 与内置渠道同 code 时以内置为准，扩展会被忽略并打警告日志。
- 实现类抛出的异常不会击穿主链路，会转成下单失败并标记会话 `FAILED`。

---

## 五、HTTP 接入（不引入 Java 依赖时）

不愿引入 Maven 依赖的调用方（如外部系统、非 Java 插件）直接用开放 API：

```
POST /apis/api.payment.pangu.tax/v1alpha1/payments
X-API-Key: <后台「插件 → 支付中心 → 设置 → 接口对接」中配置的 Key>
```

完整端点与示例见 [API.md](./API.md)。鉴权头、错误码、回调格式三处与 Java 路径完全一致。

---

## 六、扩展点注册说明

本插件在 `src/main/resources/extensions/extension-points.yaml` 中声明了两个
`ExtensionPointDefinition`：

```yaml
apiVersion: plugin.halo.run/v1alpha1
kind: ExtensionPointDefinition
metadata:
  name: 996-dev-payment-gateway
spec:
  className: run.pangu.payment.api.PaymentGateway
  type: SINGLETON
---
apiVersion: plugin.halo.run/v1alpha1
kind: ExtensionPointDefinition
metadata:
  name: 996-dev-payment-provider
spec:
  className: run.pangu.payment.api.PaymentProviderExtension
  type: MULTI_INSTANCE
```

`className` 指向 api 模块中的接口全限定名；两个接口都 `extends org.pf4j.ExtensionPoint`，
这是 Halo 识别扩展点的标志。本插件用 `ExtensionGetter.getEnabledExtensions(...)`
收集渠道扩展，`getEnabledExtension(...)` 获取网关实现。
