package org.skylark.langur.domain.harness.execution;

import lombok.Getter;

/**
 * 执行产物（H9+）- 一次执行产出的具名、带类型的交付物。
 * <p>长任务常需输出多份相互独立的报告（如合同审查报告 + 特批项报告）。单一 answer 字符串无法承载
 * 多产物语义，故以 {@code (name, type, content)} 三元组建模：name 用于定位/去重，type 标注产物类别，
 * content 为正文。由 {@code WorkflowExecutionLoop} 在阶段声明产物时写入 {@code ExecutionTask}。</p>
 */
@Getter
public class Artifact {

    private final String name;
    private final String type;
    private final String content;

    private Artifact(String name, String type, String content) {
        this.name = name;
        this.type = type;
        this.content = content;
    }

    public static Artifact of(String name, String type, String content) {
        return new Artifact(name, type != null ? type : "text", content);
    }
}
