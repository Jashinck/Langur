package org.skylark.langur.application.command;

import lombok.Builder;
import lombok.Getter;
import org.skylark.langur.domain.model.message.MessagePartType;

@Getter
@Builder
public class MessagePartInput {
    private final MessagePartType type;
    private final String content;
    private final String mediaUrl;
}
