package org.skylark.langur.domain.harness.rsi;

/**
 * 蒸馏知识类别（R2）。一条蒸馏产物要么是"成功模式"（可复用的达成路径），要么是"失败教训"
 * （应避免的无效路径）。纯 JDK enum，零外部依赖（P1）。
 */
public enum DistillKind {

    /** 成功模式：从成功轨迹提炼的可复用达成路径。 */
    SUCCESS_PATTERN,

    /** 失败教训：从失败轨迹提炼的应避免路径。 */
    FAILURE_LESSON
}
