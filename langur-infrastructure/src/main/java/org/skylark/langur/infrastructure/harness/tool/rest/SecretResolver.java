package org.skylark.langur.infrastructure.harness.tool.rest;

import java.util.Optional;

/**
 * 密钥解析器（H7，§7.4）- 将配置中的密钥"引用"解析为真实明文，使密钥不落配置文件。
 * <p>支持带 scheme 的引用：{@code env:VAR}（环境变量）、{@code prop:name}（系统属性）、
 * {@code kms:ciphertext}（委托 {@link KmsClient} 解密）。非引用（无 scheme）视为字面量，原样返回。</p>
 */
public interface SecretResolver {

    /**
     * 解析密钥引用；返回真实明文。
     *
     * @param reference 密钥引用或字面量；{@code null} 返回空
     * @return 解析后的明文（可能为空字符串），从不返回 {@code null} 以外的包装
     */
    Optional<String> resolve(String reference);
}
