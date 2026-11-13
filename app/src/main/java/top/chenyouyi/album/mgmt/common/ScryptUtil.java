package top.chenyouyi.album.mgmt.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.bouncycastle.crypto.generators.SCrypt;

/**
 * RFC 7914 scrypt 共享口令校验（契约 identity.sharedKey 的本地 fixture 实现）。
 * 参数取本地原型可用的较小 cost；生产共享 key 校验由 port 的另一实现使用更强参数。
 * 不连 SSM、不落任何长期密钥。
 *
 * RFC 7914 scrypt shared-key verification (local fixture implementation of identity.sharedKey).
 * Cost parameters are modest for the local prototype; a production port implementation uses
 * stronger parameters. No SSM call and no long-lived key is stored.
 */
public final class ScryptUtil {

    private static final int N = 4096;
    private static final int R = 8;
    private static final int P = 1;
    private static final int DK_LEN = 32;

    private ScryptUtil() {
    }

    public static byte[] derive(String password, byte[] salt) {
        return SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, N, R, P, DK_LEN);
    }

    /** 常量时间比对 / Constant-time comparison. */
    public static boolean matches(String password, byte[] salt, byte[] expected) {
        return MessageDigest.isEqual(derive(password, salt), expected);
    }
}
