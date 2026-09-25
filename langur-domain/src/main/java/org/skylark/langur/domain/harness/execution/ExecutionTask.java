package org.skylark.langur.domain.harness.execution;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * E 组件聚合根 - 执行任务。
 * <p>承载一次 Agent 执行的运行范式、终止闸门与轮次/Token 计量。</p>
 */
@Getter
public class ExecutionTask {

    private final String taskId;
    private final String agentId;
    private final String bizCode;
    private final String traceId;
    private final RuntimeParadigm paradigm;
    private final TerminationGate gate;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant updatedAt;
    private ExecutionStatus status;
    private int currentRound;
    private long consumedTokens;
    private int callsInCurrentRound;
    private String terminateReason;

    /** 具名产物集合（多产物输出，如合同审查报告 + 特批项报告）；随任务生命周期累积。 */
    private final List<Artifact> artifacts = new ArrayList<>();

    private ExecutionTask(String taskId, String agentId, String bizCode, String traceId,
                          RuntimeParadigm paradigm, TerminationGate gate,
                          Instant createdAt, Instant updatedAt, ExecutionStatus status,
                          int currentRound, long consumedTokens, int callsInCurrentRound,
                          String terminateReason) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.bizCode = bizCode;
        this.traceId = traceId;
        this.paradigm = paradigm;
        this.gate = gate;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.status = status;
        this.currentRound = currentRound;
        this.consumedTokens = consumedTokens;
        this.callsInCurrentRound = callsInCurrentRound;
        this.terminateReason = terminateReason;
    }

    public static ExecutionTask create(String agentId, String bizCode,
                                       RuntimeParadigm paradigm, TerminationGate gate) {
        Instant now = Instant.now();
        return new ExecutionTask(UUID.randomUUID().toString(), agentId, bizCode,
                UUID.randomUUID().toString(), paradigm,
                gate != null ? gate : TerminationGate.defaults(),
                now, now, ExecutionStatus.PENDING, 0, 0L, 0, null);
    }

    /**
     * 断点续跑入口（T5）：以既有 taskId 重入执行，配合 {@code TaskStateRepository} 中未完成快照恢复轮次。
     */
    public static ExecutionTask resume(String taskId, String agentId, String bizCode,
                                       RuntimeParadigm paradigm, TerminationGate gate) {
        Instant now = Instant.now();
        return new ExecutionTask(taskId, agentId, bizCode, UUID.randomUUID().toString(), paradigm,
                gate != null ? gate : TerminationGate.defaults(),
                now, now, ExecutionStatus.PENDING, 0, 0L, 0, null);
    }

    /**
     * [S] 从最近快照轮次恢复计数（不从 0 开始）。
     */
    public void resumeFromRound(int round) {
        this.currentRound = round;
        this.updatedAt = Instant.now();
    }

    public void start() {
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void nextRound() {
        this.currentRound++;
        this.callsInCurrentRound = 0;
        this.updatedAt = Instant.now();
    }

    public void recordToolCall() {
        this.callsInCurrentRound++;
    }

    public void addTokens(long tokens) {
        this.consumedTokens += tokens;
        this.updatedAt = Instant.now();
    }

    public void complete() {
        this.status = ExecutionStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    /** 追加一份具名产物（多产物输出）；同名产物允许并存，由消费方按 name/type 取用。 */
    public void addArtifact(String name, String type, String content) {
        if (name == null || name.isBlank()) {
            return;
        }
        this.artifacts.add(Artifact.of(name, type, content));
        this.updatedAt = Instant.now();
    }

    /** 只读产物视图。 */
    public List<Artifact> getArtifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    public void fail(String reason) {
        this.status = ExecutionStatus.FAILED;
        this.terminateReason = reason;
        this.updatedAt = Instant.now();
    }

    public void terminate(String reason) {
        this.status = ExecutionStatus.TERMINATED;
        this.terminateReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * 终止闸门检测：轮次 / Token / 单轮调用数 / 超时 任一超限即触发（四维硬约束全部生效）
     */
    public boolean gateTripped() {
        return gate.exceedsRounds(currentRound)
                || gate.exceedsTokens(consumedTokens)
                || gate.exceedsCallsPerRound(callsInCurrentRound)
                || gate.exceedsTimeout(startedAt);
    }
}
