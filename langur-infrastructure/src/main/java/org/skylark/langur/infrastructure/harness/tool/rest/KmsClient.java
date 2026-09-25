package org.skylark.langur.infrastructure.harness.tool.rest;

/**
 * KMS 解密接缝（H7，§7.4）- 将密文解密为明文密钥，对接云 KMS / Vault / HSM 等外部密钥管理系统。
 * <p>抽象成接口以便生产注入真实 KMS 客户端、单测注入桩，密钥明文不出现在配置与日志中。</p>
 */
@FunctionalInterface
public interface KmsClient {

    /**
     * 解密密文。
     *
     * @param ciphertext {@code kms:} 引用去掉前缀后的密文/密钥标识
     * @return 明文密钥
     */
    String decrypt(String ciphertext);
}
