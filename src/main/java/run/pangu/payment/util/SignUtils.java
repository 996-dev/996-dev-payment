package run.pangu.payment.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 签名工具集。
 *
 * <p>聚合了各渠道对接中真正会被用到的三类算法：MD5（易支付）、
 * RSA2 与 HMAC-SHA256（支付宝、微信支付 v3 credential 校验）。
 * 全部为无状态静态方法，便于单测与复现。
 */
public final class SignUtils {

    private SignUtils() {
    }

    /**
     * 易支付协议签名：参数名 ASCII 升序，过滤空值与 sign/sign_type，
     * 拼成 {@code a=1&b=2} 后直接拼接商户密钥，取 32 位小写 MD5。
     */
    public static String easyPaySign(Map<String, String> params, String merchantKey) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach((k, v) -> {
            if (k == null || "sign".equals(k) || "sign_type".equals(k)) {
                return;
            }
            if (v != null && !v.isEmpty()) {
                sorted.put(k, v);
            }
        });
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        String raw = sb.toString();
        if (!raw.isEmpty()) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return md5(raw + merchantKey);
    }

    /**
     * 支付宝签名源串：参数名升序，过滤空值与 sign，拼成 {@code k=v&k=v}。
     * 用于 RSA2 签名与异步通知验签前的规范化。
     */
    public static String alipaySignContent(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> {
            if ("sign".equals(k) || v == null || v.isEmpty()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    /** 32 位小写 MD5。 */
    public static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    /** 十六进制小写编码。 */
    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 按 form 规则拼接并 URL 编码，用于构造跳转地址。 */
    public static String buildQuery(Map<String, String> params, boolean encode) {
        List<String> parts = new ArrayList<>();
        params.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            String value = encode
                ? URLEncoder.encode(v, StandardCharsets.UTF_8)
                : v;
            parts.add(k + "=" + value);
        });
        return String.join("&", parts);
    }
}
