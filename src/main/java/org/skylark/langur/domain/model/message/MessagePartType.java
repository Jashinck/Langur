package org.skylark.langur.domain.model.message;

public enum MessagePartType {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
    STRUCTURED_DATA;

    public static MessagePartType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        String normalized = value.trim().toUpperCase().replace('-', '_');
        return MessagePartType.valueOf(normalized);
    }
}
