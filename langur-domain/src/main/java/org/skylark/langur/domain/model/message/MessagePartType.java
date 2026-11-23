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
        try {
            return MessagePartType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported message part type: " + value
                            + ". Supported types: text, image, audio, video, file, structured-data (or structured_data)");
        }
    }
}
