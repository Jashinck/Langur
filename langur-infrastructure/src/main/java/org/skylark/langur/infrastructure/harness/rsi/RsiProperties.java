package org.skylark.langur.infrastructure.harness.rsi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RSI 配置（R0/R1，{@code langur.rsi.*}，P9）。
 * <p>总开关 {@link #enabled} 默认 {@code false}——未开启时 {@code RsiConfiguration} 不装配，不产出任何 RSI Bean，
 * 系统行为与既有版本完全一致（P10/P11 红线：RSI 暂停态）。开启后装配<b>离线回放验证底座</b>
 * （{@code ReplayEngine} + {@code TrajectoryRepository}）+ 可选<b>反思自检 Hook</b>
 * （{@link Reflection}，{@code ReflectionHook} 经 {@code langur.rsi.reflection.enabled} 另行开启）。
 * RSI 自改进产物默认是候选提案，回放通过<b>不等于生效</b>，生效须经 R-G 灰度 + 高危人审（P11）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "langur.rsi")
public class RsiProperties {

    /** 总开关，默认关（P10/P11）。开启后装配 R0 回放底座；关闭时不产出 RSI Bean，行为等价既有版本。 */
    private boolean enabled = false;

    /** R1 反思自检配置（{@code langur.rsi.reflection.*}，RsiProperties/ReflectionHook 共用）。 */
    private Reflection reflection = new Reflection();

    /** R2 记忆自蒸馏配置（{@code langur.rsi.distillation.*}，RsiProperties/RsiConfiguration 共用）。 */
    private Distillation distillation = new Distillation();

    /** R3 技能自合成配置（{@code langur.rsi.synthesis.*}，RsiProperties/RsiConfiguration 共用）。 */
    private Synthesis synthesis = new Synthesis();

    /** R-G 安全平面配置（{@code langur.rsi.governance.*}，RsiProperties/RsiConfiguration 共用）。 */
    private Governance governance = new Governance();

    /**
     * R1 反思自检（RSI L1，{@code langur.rsi.reflection.*}）。
     * <p>{@code enabled} 默认 {@code false}；需与总开关 {@code langur.rsi.enabled=true} 同时成立才装配
     * {@code ReflectionHook}。{@code prescreenThreshold} 为 Jev 廉价初筛的质量/置信阈值——初筛判"足够高"则
     * 跳过 M5 升级以控成本（P10），否则升级 M5 反思改写；{@code critiqueSystemPrompt} 可覆盖默认反思提示词。</p>
     */
    @Data
    public static class Reflection {

        /** 反思 Hook 开关，默认关（P11 暂停态；与总开关 AND 后生效）。 */
        private boolean enabled = false;

        /** Jev 初筛阈值（0..1）≈ 决策平面 completion 维 DD11 经验值；初筛判高质 + 高置信则跳过 M5 省钱。 */
        private double prescreenThreshold = 0.85;

        /** M5 自我批评提示词；留空用缺省中文反思提示词。 */
        private String critiqueSystemPrompt = "";
    }

    /**
     * R2 记忆自蒸馏配置（RSI L2，{@code langur.rsi.distillation.*}）。
     * <p>{@code enabled} 默认 {@code false}；与总开关 {@code langur.rsi.enabled=true} 同时成立才装配
     * {@code MemoryDistiller}。{@code minConfidence} 为 Jev 置信门——轨迹录制判定置信均值低于它则不入蒸馏；
     * {@code dedupThreshold} 为语义去重阈值（cosine）；{@code llmEnabled} 决定用 M5/M6 大模型归纳还是
     * 确定性模板提炼（缺省模板，离线零网络）。</p>
     */
    @Data
    public static class Distillation {

        /** 蒸馏装配开关，默认关（P11 暂停态；与总开关 AND 后生效）。 */
        private boolean enabled = false;

        /** Jev 置信门阈值（0..1）：低置信轨迹不入蒸馏（不污染 L4）。 */
        private double minConfidence = 0.85;

        /** 语义去重阈值（0..1）：目标命名空间近邻相似度达此值判重复拒绝。 */
        private double dedupThreshold = 0.95;

        /** 蒸馏命名空间（缺省与业务 knowledge 隔离）。 */
        private String namespace = "rsi-distilled";

        /** 是否用 M5/M6 大模型归纳（缺省 false=确定性模板，离线零网络）。 */
        private boolean llmEnabled = false;
    }

    /**
     * R3 技能自合成配置（RSI L3，{@code langur.rsi.synthesis.*}）。
     * <p>{@code enabled} 默认 {@code false}；与总开关 {@code langur.rsi.enabled=true} 同时成立才装配
     * {@code SkillSynthesizer}/{@code SkillSynthesisValidator}/{@code SynthesizedSkillRegistrar}。
     * {@code allowedToolIds} 为越权审查白名单——合成技能引用的 TOOL_CALL 工具必须全部在此（P11 红线，
     * 缺省空 = 不放行任何外部工具引用）。</p>
     */
    @Data
    public static class Synthesis {

        /** 合成装配开关，默认关（P11 暂停态；与总开关 AND 后生效）。 */
        private boolean enabled = false;

        /** 越权审查白名单：合成技能可引用的工具 ID 集（缺省空）。 */
        private java.util.List<String> allowedToolIds = new java.util.ArrayList<>();
    }

    /**
     * R-G 安全平面配置（P0，{@code langur.rsi.governance.*}）。
     * <p>{@code enabled} 默认 {@code false}；与总开关 {@code langur.rsi.enabled=true} 同时成立才装配
     * {@code RsiSafetyPlane} + 缺省内存提案仓库。{@code maxDepth} 递归深度上限、{@code maxProposalsPerMinute}
     * 变更频率限流、{@code forbiddenTargetPrefixes} 权限隔离红线（安全策略层/校验链，不可改）。</p>
     */
    @Data
    public static class Governance {

        /** 安全平面装配开关，默认关（P11 暂停态；与总开关 AND 后生效）。 */
        private boolean enabled = false;

        /** 递归深度上限（自改进链深度超此值拒绝）。 */
        private int maxDepth = 3;

        /** 变更频率限流（滑动 60s 窗口内最大提案数）。 */
        private int maxProposalsPerMinute = 10;

        /** 权限隔离红线：禁止改动的目标前缀（SecurityPolicySPI / 四层校验链）。 */
        private java.util.List<String> forbiddenTargetPrefixes =
                new java.util.ArrayList<>(java.util.List.of("security.policy", "validation."));
    }
}
