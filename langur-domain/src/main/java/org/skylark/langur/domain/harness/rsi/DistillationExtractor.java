package org.skylark.langur.domain.harness.rsi;

import java.util.List;

/**
 * 蒸馏抽取端口（R2，M5/M6 提炼的依赖倒置接缝）。
 * <p>从一条历史轨迹提炼可入 L4 的知识候选。领域层只依赖本端口（P3）：缺省提供确定性模板实现
 * （{@code TemplateDistillationExtractor}，离线可测）；基础设施层可提供 M5/M6 大模型实现
 * （{@code LlmDistillationExtractor}）在模板之上做语义归纳，二者经 {@code @ConditionalOnMissingBean}
 * / {@code @ConditionalOnProperty} 择一装配（P5 开闭）。</p>
 * <p>返回产物的 {@code confidence} 字段由蒸馏器统一覆盖（轨迹级 Jev 置信信号），抽取器无需填充。</p>
 */
public interface DistillationExtractor {

    /**
     * 从轨迹提炼知识候选（成功模式 / 失败教训）。
     *
     * @param trajectory 历史执行轨迹（R0）
     * @return 可入 L4 的知识候选；无内容可提炼返回空列表
     */
    List<DistilledMemory> extract(Trajectory trajectory);
}
