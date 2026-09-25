package org.skylark.langur.infrastructure.harness.rsi;

import org.skylark.langur.domain.harness.rsi.Trajectory;
import org.skylark.langur.domain.harness.rsi.TrajectoryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轨迹仓库内存实现（R0）。{@link TrajectoryRepository} 的缺省装配——离线确定性、无外部依赖，
 * 供回放引擎加载历史轨迹与测试导入。生产可替换为 JPA/快照级实现（同端口契约，P5 开闭）。
 * <p>不带 {@code @Component}，由 start {@code RsiConfiguration} 在 {@code langur.rsi.enabled=true} 时装配。
 * 线程安全（{@link ConcurrentHashMap}）；仅进程内留存，重启即失（R0 为离线验证底座，非持久化承诺）。</p>
 */
public class InMemoryTrajectoryRepository implements TrajectoryRepository {

    private final Map<String, Trajectory> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Trajectory> load(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public void save(Trajectory trajectory) {
        if (trajectory == null) {
            return;
        }
        store.put(trajectory.taskId(), trajectory);
    }

    @Override
    public List<String> listTaskIds() {
        return new ArrayList<>(store.keySet());
    }
}
