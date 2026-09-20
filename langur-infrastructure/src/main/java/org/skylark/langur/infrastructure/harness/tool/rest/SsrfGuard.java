package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * SSRF 防护（§7.4）- 禁止 REST API 工具访问内网/保留地址，仅放行公网或显式白名单主机。
 * <p>拦截范围：环回、站点本地（10/172.16/192.168）、链路本地（含 169.254 云元数据）、
 * 任意本地（0.0.0.0）、组播、运营商级 NAT（100.64/10）、IPv6 唯一本地（fc00::/7）。</p>
 */
@Component
public class SsrfGuard {

    /**
     * 校验目标 URI；命中禁止范围且不在 allowedHosts 时抛 {@link SsrfViolationException}。
     */
    public void validate(URI uri, List<String> allowedHosts) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfViolationException("missing host in url: " + uri);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfViolationException("scheme not allowed: " + scheme);
        }
        // 显式可信主机白名单：跳过地址段检查（仍受四层校验链其余环节管控）
        if (allowedHosts != null && allowedHosts.contains(host)) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfViolationException("unresolvable host: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                throw new SsrfViolationException(
                        "address in reserved/internal range: " + addr.getHostAddress());
            }
        }
    }

    private boolean isBlocked(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()
                || isCarrierGradeNat(addr)
                || isIpv6UniqueLocal(addr);
    }

    /** 100.64.0.0/10 运营商级 NAT。 */
    private boolean isCarrierGradeNat(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    /** fc00::/7 IPv6 唯一本地地址。 */
    private boolean isIpv6UniqueLocal(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }
}
