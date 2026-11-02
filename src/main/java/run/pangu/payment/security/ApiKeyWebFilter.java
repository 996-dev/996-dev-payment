package run.pangu.payment.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.security.AdditionalWebFilter;
import run.pangu.payment.setting.ApiSetting;
import run.pangu.payment.setting.SecuritySetting;

/**
 * 支付 API 的 API Key 鉴权。
 *
 * <p>Halo 自身只提供用户会话与 PersonalAccessToken 两种身份，
 * 第三方业务系统对接支付时用后者过重。这里用插件级 WebFilter
 * 在 Halo 安全链之前完成校验，规则如下：
 *
 * <ul>
 *   <li>{@code /apis/api.payment.pangu.tax/**} 之外的请求直接放行</li>
 *   <li>渠道回调 {@code /callbacks/**} 放行 —— 渠道无法携带 API Key，
 *       安全性由各渠道的签名验证保障（支付宝 RSA2、微信 APIv3、Stripe 签名）</li>
 *   <li>{@code GET /providers} 放行 —— 只返回渠道元信息，不含任何密钥</li>
 *   <li>其余读写操作必须携带 {@code X-API-Key}</li>
 * </ul>
 *
 * <p>安全默认：未配置任何 API Key 时拒绝写操作（fail closed），
 * 避免插件装好后默认裸奔。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyWebFilter implements AdditionalWebFilter {

    public static final String API_PATH_PREFIX = "/apis/api.payment.pangu.tax/";
    public static final String HEADER = "X-API-Key";
    private static final String SECRET_NAME = "996-dev-payment-secret";
    private static final String KEY_FIELD = "apiKeys";
    private static final String GROUP_API = "api";

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(API_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        if (path.contains("/callbacks/") || isReadOnlyPublic(exchange, path)) {
            return chain.filter(exchange);
        }
        String method = exchange.getRequest().getMethod().name();
        boolean readRequest = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        // 「安全策略 → 允许匿名查询支付状态」开启时，GET 查询放行；
        // 默认关闭，查询也需要 API Key（写操作任何时候都要）。
        if (readRequest && isReadEndpoint(path)) {
            return settingFetcher.fetch("security", SecuritySetting.class)
                .map(SecuritySetting::getAllowAnonymousRead)
                .map(Boolean.TRUE::equals)
                .defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(false))
                .flatMap(allowAnonymous -> allowAnonymous
                    ? chain.filter(exchange) : requireApiKey(exchange, chain, path));
        }

        return requireApiKey(exchange, chain, path);
    }

    /** 查询类端点：会话详情与状态轮询。 */
    private boolean isReadEndpoint(String path) {
        String suffix = path.substring(API_PATH_PREFIX.length());
        return suffix.matches("payments/[^/]+(/status)?");
    }

    private Mono<Void> requireApiKey(ServerWebExchange exchange, WebFilterChain chain,
        String path) {
        String provided = exchange.getRequest().getHeaders().getFirst(HEADER);
        return loadKeys().flatMap(keys -> {
            if (keys.isEmpty()) {
                log.warn("支付 API 未配置 API Key，已拒绝访问: {}", path);
                return reject(exchange, "API_KEY_NOT_CONFIGURED",
                    "尚未配置 API Key，请在后台「支付中心 → 接口密钥」中设置");
            }
            if (provided == null || !keys.contains(provided.trim())) {
                return reject(exchange, "UNAUTHORIZED", "API Key 无效");
            }
            return chain.filter(exchange);
        });
    }

    private boolean isReadOnlyPublic(ServerWebExchange exchange, String path) {
        return "GET".equalsIgnoreCase(exchange.getRequest().getMethod().name())
            && path.endsWith("/providers");
    }

    /** 读取 API Key：优先 Secret，其次插件设置，两者合并去重。读失败按未配置处理。 */
    private Mono<Set<String>> loadKeys() {
        Mono<Set<String>> fromSecret = client.fetch(Secret.class, SECRET_NAME)
            .map(secret -> {
                Map<String, String> data = secret.getStringData();
                return splitKeys(data == null ? null : data.get(KEY_FIELD));
            })
            .defaultIfEmpty(Set.of())
            .onErrorResume(e -> {
                log.warn("读取 Secret 中的 API Key 失败", e);
                return Mono.just(Set.of());
            });

        Mono<Set<String>> fromSetting = settingFetcher.fetch(GROUP_API, ApiSetting.class)
            .map(setting -> splitKeys(setting.getApiKeys()))
            .defaultIfEmpty(Set.of())
            .onErrorResume(e -> {
                log.warn("读取插件设置中的 API Key 失败", e);
                return Mono.just(Set.of());
            });

        return Mono.zip(fromSecret, fromSetting).map(tuple -> {
            Set<String> merged = new LinkedHashSet<>(tuple.getT1());
            merged.addAll(tuple.getT2());
            return merged;
        });
    }

    private Set<String> splitKeys(String raw) {
        Set<String> keys = new LinkedHashSet<>();
        if (raw != null) {
            Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(keys::add);
        }
        return keys;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":{}}")
            .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 早于 Halo 的认证过滤器，晚于日志与追踪
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
