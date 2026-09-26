package org.skylark.langur.application.command;

import lombok.Builder;
import lombok.Getter;

/**
 * 消息分片输入（应用层命令 VO）。{@code type} 存 API 传入的<b>原始类型串</b>（{@code text/image/...}），
 * 由应用层在构造领域 {@code MessagePart} 时经 {@code MessagePartType.fromValue} 校验转换——
 * 使 api 层不直调 domain 枚举（P2 分层：api → application → domain）。
 */
@Getter
@Builder
public class MessagePartInput {
    private final String type;
    private final String content;
    private final String mediaUrl;
}
