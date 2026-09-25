package org.skylark.langur.domain.harness.rsi;

import java.util.List;
import java.util.Optional;

/**
 * 轨迹仓库端口（R0）。加载历史执行轨迹供 {@link ReplayEngine} 反事实重放；结构化存储执行轨迹
 * （复用 S 组件 {@code StateSnapshot} / {@code t_execution_round} + J2 录制的 Jev 判定）。
 * <p>domain 端口（P3 依赖倒置）；infra 提供内存/JPA 实现。R0 缺省装配内存实现，离线确定性、无外部依赖。</p>
 */
public interface TrajectoryRepository {

    /** 按任务标识加载轨迹；不存在返回 {@link Optional#empty()}。 */
    Optional<Trajectory> load(String taskId);

    /** 保存/覆盖一条轨迹（供离线导入与测试）。 */
    void save(Trajectory trajectory);

    /** 列出已存轨迹的任务标识（快照式，供批量回放选取）。 */
    List<String> listTaskIds();
}
