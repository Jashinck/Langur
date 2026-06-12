package org.skylark.langur.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonValueMapper {

    private final ObjectMapper objectMapper;

    public JsonValueMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }

    public <T> T read(String value, TypeReference<T> typeReference, T defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(value, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read JSON", e);
        }
    }
}
