package org.skylark.langur.infrastructure.harness.tool.rest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10a 验收：SSRF 防护拦截内网/保留地址，放行公网与显式白名单。
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard();

    @Test
    void shouldBlockInternalAndReservedAddresses() {
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://127.0.0.1/x"), null));
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://10.0.0.5/x"), null));
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://192.168.1.1/x"), null));
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://172.16.0.1/x"), null));
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://169.254.169.254/latest"), null));
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("http://100.64.0.1/x"), null));
    }

    @Test
    void shouldBlockNonHttpScheme() {
        assertThrows(SsrfViolationException.class, () -> guard.validate(URI.create("file:///etc/passwd"), null));
    }

    @Test
    void shouldAllowPublicAddress() {
        assertDoesNotThrow(() -> guard.validate(URI.create("https://8.8.8.8/x"), null));
        assertDoesNotThrow(() -> guard.validate(URI.create("https://1.1.1.1/x"), null));
    }

    @Test
    void shouldBypassCheckForExplicitAllowedHost() {
        // 内网地址但主机在显式白名单中 -> 放行（可信例外）
        assertDoesNotThrow(() -> guard.validate(URI.create("http://127.0.0.1/x"), List.of("127.0.0.1")));
    }
}
