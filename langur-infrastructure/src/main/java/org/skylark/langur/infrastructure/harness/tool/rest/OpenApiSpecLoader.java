package org.skylark.langur.infrastructure.harness.tool.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 文档加载器（H7）- 从内联文本 / classpath 资源 / 远端 URL 载入并解析为 {@code Map}。
 * <p>JSON 与 YAML 自动识别（首个非空字符为 <code>{</code> 判为 JSON）；YAML 采用 {@link SafeConstructor}
 * 防止反序列化gadget；远端 URL 经 {@link SsrfGuard} 校验（仅公网/白名单）。</p>
 */
@Slf4j
@Component
public class OpenApiSpecLoader {

    private final ObjectMapper objectMapper;
    private final SsrfGuard ssrfGuard;
    private final WebClient webClient;

    public OpenApiSpecLoader(ObjectMapper objectMapper, SsrfGuard ssrfGuard) {
        this.objectMapper = objectMapper;
        this.ssrfGuard = ssrfGuard;
        this.webClient = WebClient.builder().build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(RestApiToolProperties.OpenApiSource source) {
        String text = readText(source);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("empty OpenAPI document for service [" + source.getServiceName() + "]");
        }
        try {
            Object parsed = isJson(text)
                    ? objectMapper.readValue(text, Map.class)
                    : new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalStateException("OpenAPI document is not a mapping");
            }
            return (Map<String, Object>) map;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse OpenAPI document for service ["
                    + source.getServiceName() + "]: " + e.getMessage(), e);
        }
    }

    private String readText(RestApiToolProperties.OpenApiSource source) {
        if (source.getSpec() != null && !source.getSpec().isBlank()) {
            return source.getSpec();
        }
        if (source.getSpecResource() != null && !source.getSpecResource().isBlank()) {
            try (InputStream in = new ClassPathResource(source.getSpecResource()).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("failed to read OpenAPI classpath resource ["
                        + source.getSpecResource() + "]: " + e.getMessage(), e);
            }
        }
        if (source.getSpecUrl() != null && !source.getSpecUrl().isBlank()) {
            URI uri = URI.create(source.getSpecUrl());
            ssrfGuard.validate(uri, source.getAllowedHosts() != null ? source.getAllowedHosts() : List.of());
            return webClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
        }
        throw new IllegalStateException("OpenAPI source [" + source.getServiceName()
                + "] has no spec/specResource/specUrl");
    }

    private boolean isJson(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("{");
    }
}
