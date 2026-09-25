package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link SecretResolver} 默认实现（H7）- 按 scheme 组合解析：环境变量 / 系统属性 / KMS。
 * <p>{@code kms:} 委托可选的 {@link KmsClient} Bean；未装配 KMS 却使用 {@code kms:} 引用时 fail-closed 抛错，
 * 绝不静默回退明文。解析出的明文从不写日志。</p>
 */
@Component
public class CompositeSecretResolver implements SecretResolver {

    private static final String ENV_PREFIX = "env:";
    private static final String PROP_PREFIX = "prop:";
    private static final String KMS_PREFIX = "kms:";

    private final KmsClient kmsClient;

    @Autowired
    public CompositeSecretResolver(ObjectProvider<KmsClient> kmsClientProvider) {
        this(kmsClientProvider.getIfAvailable());
    }

    public CompositeSecretResolver(KmsClient kmsClient) {
        this.kmsClient = kmsClient;
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        if (reference.startsWith(ENV_PREFIX)) {
            return Optional.ofNullable(System.getenv(reference.substring(ENV_PREFIX.length())));
        }
        if (reference.startsWith(PROP_PREFIX)) {
            return Optional.ofNullable(System.getProperty(reference.substring(PROP_PREFIX.length())));
        }
        if (reference.startsWith(KMS_PREFIX)) {
            if (kmsClient == null) {
                throw new IllegalStateException(
                        "secret reference uses kms: but no KmsClient bean is configured");
            }
            return Optional.ofNullable(kmsClient.decrypt(reference.substring(KMS_PREFIX.length())));
        }
        // 无 scheme：视为字面量密钥（兼容存量配置）
        return Optional.of(reference);
    }
}
