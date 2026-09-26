package org.skylark.langur.infrastructure.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H13.7 Provider 熔断器离线确定性测试（无 Mockito）。
 * <p>覆盖：连续失败达阈值跳闸、阈值内不跳闸、成功复位、构造参数钳制。</p>
 */
class ProviderCircuitBreakerTest {

    @Test
    void shouldTripAfterThresholdFailures() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p", 3, 30_000L);

        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.isOpen(), "未达阈值不跳闸");
        breaker.recordFailure();

        assertTrue(breaker.isOpen(), "连续失败达阈值即跳闸");
    }

    @Test
    void shouldResetOnSuccess() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker("p", 3, 30_000L);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertTrue(breaker.isOpen());

        breaker.recordSuccess();

        assertFalse(breaker.isOpen(), "成功即复位");
    }

    @Test
    void shouldClampInvalidConstructorArgs() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(null, 0, -1L);
        // threshold 钳到 3、cooldown 钳到 30s——两次失败不跳闸，三次跳闸
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.isOpen());
        breaker.recordFailure();
        assertTrue(breaker.isOpen());
    }
}
