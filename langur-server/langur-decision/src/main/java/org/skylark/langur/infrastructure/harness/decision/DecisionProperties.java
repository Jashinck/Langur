package org.skylark.langur.infrastructure.harness.decision;

import lombok.Data;
import org.skylark.langur.domain.harness.decision.DecisionThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 决策平面配置（J3，{@code langur.decision.*}，P9）。
 * <p>总开关 {@link #enabled} 默认 {@code false}——未开启时 {@code DecisionConfiguration} 不装配，
 * 系统行为与 v2.0 完全一致（P10/P12 红线①）。{@code api-key-ref} 经 H7 {@code SecretResolver} 解析，
 * 绝不落明文（P12 红线⑥）。{@code data-residency.sensitive-namespaces} 命中的 {@code state} 强制 local
 * 后端、禁止发往第三方云（DD10/P12 红线⑤）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.decision")
public class DecisionProperties {

    /** 总开关，默认关（P10/P12①）。开启后装配装饰链；关闭时行为等价 v2.0。 */
    private boolean enabled = false;

    /** 后端类型：{@code typesafe}（托管 Jev，默认）| {@code local}（自部署 Kev/Laya）| {@code off}（规则兜底）。 */
    private String backend = "typesafe";

    /** 后端模型标识；生产建议锁版本（如 {@code jev-1.13.0}，R0 确定性）。 */
    private String model = "jev-latest";

    /** 判定端点 base-url；{@code backend=local} 时指向自部署端点。 */
    private String baseUrl = "https://api.typesafe.ai/v1/systemone";

    /** API key 引用（{@code env:/prop:/kms:}），经 {@code SecretResolver} 解析，绝不明文（P12⑥）。 */
    private String apiKeyRef = "";

    /** 判定往返超时（毫秒）；超时即回退规则（P10/P12③）。 */
    private long timeoutMillis = 800L;

    /** 投机扇出：多问题一次请求（当前适配器恒批量，此项为前向语义占位）。 */
    private boolean batch = true;

    /** 录制进轨迹快照（R0 前向兼容，强烈建议常开，P12④/DD12）。 */
    private boolean record = true;

    /**
     * J9：ReAct 决策平面语义判定的检查点间隔（每 N 轮发起一次批量判定，红线：绝不逐轮网络往返）；
     * ≤0 关闭 ReAct 语义判定（终止仍由既有指纹/闸门决定，P10）。缺省 3 轮。
     */
    private int reactCheckpointRounds = 3;

    private final Cache cache = new Cache();
    private final Thresholds thresholds = new Thresholds();
    private final DataResidency dataResidency = new DataResidency();

    /** 判定缓存（复用 H4 {@code CacheBackend}，多闸门不重复调用）。 */
    @Data
    public static class Cache {
        private boolean enabled = true;
        private long ttlSeconds = 300L;
    }

    /** 各插入点置信阈值（DD11 人工经验缺省，可被 R4 调优）。 */
    @Data
    public static class Thresholds {
        /** 层路由 advisory（J8，低于→回退现有规则）。 */
        private double routing = DecisionThresholds.DEFAULT_ROUTING;
        /** 非 CRITICAL 审批自动放行（J5，低于→人审）。 */
        private double approvalAuto = DecisionThresholds.DEFAULT_APPROVAL_AUTO;
        /** 产物验收（J6，低于→有界重试/打标）。 */
        private double artifactAccept = DecisionThresholds.DEFAULT_ARTIFACT_ACCEPT;
        /** ReAct 完成判定（J9）。 */
        private double completion = DecisionThresholds.DEFAULT_COMPLETION;

        /** 映射为 domain 阈值值对象。 */
        public DecisionThresholds toDomain() {
            return new DecisionThresholds(routing, approvalAuto, artifactAccept, completion);
        }
    }

    /** 数据驻留（DD10/P12⑤）：命中敏感命名空间的 {@code state} 强制 local，禁止出网。 */
    @Data
    public static class DataResidency {
        /**
         * 敏感命名空间标记；{@code state} 含其一即强制 local 后端（无 local 端点则 fail-closed 回退规则，
         * 绝不发往 typesafe）。缺省为空——按 DD10 由运维显式配置（如 {@code [legal-contracts, code]}）。
         */
        private List<String> sensitiveNamespaces = new ArrayList<>();
        /** 敏感数据自部署 local 端点（混合后端 DD10）；空则敏感请求 fail-closed 回退规则。 */
        private String localBaseUrl = "";
        /** local 端点模型（空则复用 {@link DecisionProperties#model}）。 */
        private String localModel = "";
    }
}
