package org.skylark.langur.infrastructure.harness.tool.rest;

import java.util.Map;
import java.util.Optional;

/**
 * 凭证保险库（§7.4）- 统一托管 REST API 工具凭证。
 * <p>对外仅暴露"应用到请求头"的能力，密钥不出库、不落日志。</p>
 */
public interface CredentialVault {

    Optional<Credential> find(String ref);

    /**
     * 将凭证转换为待追加的请求头（BEARER/API_KEY/BASIC）。
     * ref 为空或凭证不存在时返回空 Map（匿名调用）。
     */
    Map<String, String> resolveHeaders(String ref);
}
