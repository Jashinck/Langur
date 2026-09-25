package org.skylark.langur.domain.harness.execution;

import lombok.Getter;

/**
 * 执行产物（H9+）- 一次执行产出的具名、带类型的交付物。
 * <p>长任务常需输出多份相互独立的报告（如合同审查报告 + 特批项报告）。单一 answer 字符串无法承载
 * 多产物语义，故以 {@code (name, type, content)} 三元组建模：name 用于定位/去重，type 标注产物类别，
 * content 为正文。由 {@code WorkflowExecutionLoop} 在阶段声明产物时写入 {@code ExecutionTask}。</p>
 * <p>J6（插入点 ③）：经决策平面验收的产物携带 {@code accepted} 标记与 {@code reviewNote} 说明；
 * 低于验收阈值且有界重试仍不达标 → {@code accepted=false} + 原因（打标不阻断）。未接决策平面时
 * 恒 {@code accepted=true}，与 v2.0 行为一致（P10）。</p>
 */
@Getter
public class Artifact {

    private final String name;
    private final String type;
    private final String content;
    private final boolean accepted;
    private final String reviewNote;

    private Artifact(String name, String type, String content, boolean accepted, String reviewNote) {
        this.name = name;
        this.type = type != null ? type : "text";
        this.content = content;
        this.accepted = accepted;
        this.reviewNote = reviewNote;
    }

    public static Artifact of(String name, String type, String content) {
        return new Artifact(name, type, content, true, null);
    }

    /** J6：带验收结论的产物（accepted=false 时 reviewNote 载原因，打标不阻断）。 */
    public static Artifact reviewed(String name, String type, String content,
                                    boolean accepted, String reviewNote) {
        return new Artifact(name, type, content, accepted, reviewNote);
    }
}
