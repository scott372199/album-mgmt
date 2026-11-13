package top.chenyouyi.album.mgmt.common;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 契约 correlationId：格式 corr_[0-9a-f]{16}，头 X-Correlation-Id。
 * 客户端传入合法值则透传，否则服务端生成。
 *
 * Contract correlationId: format corr_[0-9a-f]{16}, header X-Correlation-Id.
 * A format-valid client header is propagated; otherwise the server generates one.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ATTRIBUTE = "mgmt.correlationId";

    private static final Pattern VALID = Pattern.compile("^corr_[0-9a-f]{16}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private CorrelationId() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** 生成 corr_ + 16 位小写十六进制 / Generate corr_ + 16 lowercase hex chars. */
    public static String generate() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("corr_");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** 透传合法客户端值，否则生成新值 / Propagate a valid client value or generate a new one. */
    public static String resolve(String headerValue) {
        return isValid(headerValue) ? headerValue : generate();
    }
}
