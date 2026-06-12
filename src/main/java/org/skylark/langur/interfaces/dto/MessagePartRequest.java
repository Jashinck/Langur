package org.skylark.langur.interfaces.dto;

import lombok.Data;

@Data
public class MessagePartRequest {
    private String type;
    private String content;
    private String mediaUrl;
}
