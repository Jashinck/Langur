package org.skylark.langur.domain.harness.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 审计校验和工具（不可篡改留痕，§10/§12.3）。
 * <p>纯 JDK 实现（SHA-256），保证 Domain 层零外部依赖（P1）。审计记录以 checksum 支撑溯源与防篡改校验。</p>
 */
public final class Checksums {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Checksums() {
    }

    /**
     * 计算审计要素的 SHA-256 十六进制校验和。
     *
     * @param parts 参与校验的字段（按序拼接，null 视作空串）
     * @return 64 位十六进制校验和
     */
    public static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F); // 单元分隔符，避免字段拼接歧义
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX[v >>> 4];
            hex[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(hex);
    }
}
