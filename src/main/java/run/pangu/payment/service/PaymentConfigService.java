package run.pangu.payment.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.pangu.payment.extension.PaymentMethodConfig;

/**
 * 支付方式配置读取与合并。
 *
 * <p>渠道配置不内嵌在 {@code PaymentMethod} 实体里，否则密钥会以明文进入扩展资源。
 * 这里按 Halo 安全规范拆成两处存储：
 *
 * <ul>
 *   <li>ConfigMap {@value #CONFIGMAP_NAME}：公开配置（appId、商户号、服务器地址等）</li>
 *   <li>Secret {@value #SECRET_NAME}：密钥类字段（私钥、API Key、APIv3 密钥等）</li>
 * </ul>
 *
 * <p>两处都以 {@code PaymentMethod.metadata.name} 作为 data 的键，值为该渠道的
 * JSON 配置对象字符串。读取时先铺 ConfigMap 再以 Secret 覆盖，
 * 保证同名键永远以 Secret 为准，避免公开配置意外泄露密钥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfigService {

    public static final String CONFIGMAP_NAME = "pangu-payment-config";
    public static final String SECRET_NAME = "pangu-payment-secret";

    /**
     * 插件类加载器与 Halo 主容器隔离，注入不到主容器的 {@code ObjectMapper}，
     * 因此插件内自持一个实例，只用来读写配置 JSON（不做多态反序列化）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReactiveExtensionClient client;

    /**
     * 读取指定支付方式的合并配置。
     *
     * <p>任一来源缺失都不视为错误：纯人工渠道可以没有任何密钥。
     * 两个来源都用 {@code defaultIfEmpty} 兜底，保证 zip 一定有产出。
     */
    public Mono<PaymentMethodConfig> get(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return Mono.just(PaymentMethodConfig.of(Map.of()));
        }
        Mono<Map<String, Object>> publicPart = client.fetch(ConfigMap.class, CONFIGMAP_NAME)
            .map(cm -> parse(cm.getData(), methodName))
            .defaultIfEmpty(Map.of());
        Mono<Map<String, Object>> secretPart = client.fetch(Secret.class, SECRET_NAME)
            .map(secret -> parse(secret.getStringData(), methodName))
            .defaultIfEmpty(Map.of());

        return Mono.zip(publicPart, secretPart)
            .map(tuple -> {
                Map<String, Object> merged = new LinkedHashMap<>(tuple.getT1());
                merged.putAll(tuple.getT2());
                return PaymentMethodConfig.of(merged);
            })
            .onErrorResume(e -> {
                log.warn("读取支付配置失败，按空配置处理: method={}", methodName, e);
                return Mono.just(PaymentMethodConfig.of(Map.of()));
            });
    }

    /** 只取公开配置（不含密钥），用于后台回显。 */
    public Mono<Map<String, Object>> getPublicSettings(String methodName) {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
            .map(cm -> parse(cm.getData(), methodName))
            .defaultIfEmpty(Map.of());
    }

    /**
     * 保存渠道配置，按字段名分流到 ConfigMap 与 Secret。
     *
     * <p>调用方传入完整的配置映射与「哪些键属于密钥」的集合；
     * 密钥键写入 Secret，其余写入 ConfigMap。两个资源按需创建，
     * 同一资源内以 {@code methodName} 作为 data 的键，互不覆盖。
     *
     * @param methodName 支付方式资源名
     * @param settings   完整配置（含密钥）
     * @param secretKeys 需要写入 Secret 的字段名
     */
    public Mono<Void> save(String methodName, Map<String, Object> settings,
        java.util.Set<String> secretKeys) {
        if (methodName == null || methodName.isBlank()) {
            return Mono.error(new IllegalArgumentException("methodName 不能为空"));
        }
        Map<String, Object> publicPart = new LinkedHashMap<>();
        Map<String, Object> secretPart = new LinkedHashMap<>();
        (settings == null ? Map.<String, Object>of() : settings).forEach((k, v) -> {
            if (secretKeys != null && secretKeys.contains(k)) {
                secretPart.put(k, v);
            } else {
                publicPart.put(k, v);
            }
        });

        Mono<Void> publicWrite = writeToConfigMap(methodName, publicPart);
        Mono<Void> secretWrite = secretPart.isEmpty()
            ? Mono.empty()
            : writeToSecret(methodName, secretPart);
        return publicWrite.then(secretWrite);
    }

    private Mono<Void> writeToConfigMap(String methodName, Map<String, Object> data) {
        String json = toJson(data);
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
            .defaultIfEmpty(newConfigMap())
            .flatMap(cm -> {
                Map<String, String> map = cm.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(cm.getData());
                map.put(methodName, json);
                cm.setData(map);
                return cm.getMetadata() != null
                    && cm.getMetadata().getCreationTimestamp() != null
                    ? client.update(cm) : client.create(cm);
            })
            .then();
    }

    private Mono<Void> writeToSecret(String methodName, Map<String, Object> data) {
        String json = toJson(data);
        return client.fetch(Secret.class, SECRET_NAME)
            .defaultIfEmpty(newSecret())
            .flatMap(secret -> {
                Map<String, String> map = secret.getStringData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(secret.getStringData());
                map.put(methodName, json);
                secret.setStringData(map);
                return secret.getMetadata() != null
                    && secret.getMetadata().getCreationTimestamp() != null
                    ? client.update(secret) : client.create(secret);
            })
            .then();
    }

    private ConfigMap newConfigMap() {
        ConfigMap cm = new ConfigMap();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName(CONFIGMAP_NAME);
        cm.setMetadata(metadata);
        cm.setData(new LinkedHashMap<>());
        return cm;
    }

    private Secret newSecret() {
        Secret secret = new Secret();
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setName(SECRET_NAME);
        secret.setMetadata(metadata);
        secret.setStringData(new LinkedHashMap<>());
        return secret;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("配置序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(Map<String, String> data, String key) {
        if (data == null) {
            return Map.of();
        }
        String raw = data.get(key);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return (Map<String, Object>) MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            log.warn("支付配置 JSON 解析失败: key={}", key, e);
            return Map.of();
        }
    }
}
