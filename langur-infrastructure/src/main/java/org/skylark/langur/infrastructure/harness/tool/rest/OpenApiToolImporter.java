package org.skylark.langur.infrastructure.harness.tool.rest;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 工具导入器（H7，§7.4）- 将 OpenAPI 3.x 文档（已解析为 {@code Map}）转换为 {@link RestApiToolSpec} 列表。
 * <p>纯解析、零 IO/Spring 依赖，便于离线单测。逐 {@code paths → 方法 → 操作} 生成工具：
 * urlTemplate = baseUrl + path（OpenAPI {@code {param}} 占位符与网规模板同构），inputSchema 由
 * path/query 参数与 {@code requestBody}（application/json）合成 JSON Schema，供四层校验链 Schema 层使用。
 * operationId 缺失时以 {@code method_path} 兜底；{@code includeOperations} 白名单生效。</p>
 */
@Component
public class OpenApiToolImporter {

    private static final List<String> METHODS =
            List.of("get", "post", "put", "delete", "patch", "head", "options");

    /** 从解析后的 OpenAPI 文档生成工具规格。 */
    @SuppressWarnings("unchecked")
    public List<RestApiToolSpec> toSpecs(Map<String, Object> document, RestApiToolProperties.OpenApiSource source) {
        List<RestApiToolSpec> specs = new ArrayList<>();
        if (document == null) {
            return specs;
        }
        String baseUrl = resolveBaseUrl(document, source.getBaseUrl());
        Object pathsObj = document.get("paths");
        if (!(pathsObj instanceof Map<?, ?> paths)) {
            return specs;
        }
        List<String> include = source.getIncludeOperations();
        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = String.valueOf(pathEntry.getKey());
            if (!(pathEntry.getValue() instanceof Map<?, ?> operations)) {
                continue;
            }
            for (String method : METHODS) {
                if (!(operations.get(method) instanceof Map<?, ?> operationRaw)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) operationRaw;
                String operationId = operationId(method, path, operation);
                if (include != null && !include.isEmpty() && !include.contains(operationId)) {
                    continue;
                }
                specs.add(RestApiToolSpec.builder()
                        .serviceName(source.getServiceName())
                        .operationId(operationId)
                        .method(method.toUpperCase())
                        .urlTemplate(baseUrl + path)
                        .description(description(operation))
                        .inputSchema(buildInputSchema(document, operation))
                        .credentialRef(source.getCredentialRef())
                        .allowedHosts(source.getAllowedHosts())
                        .permission(source.getPermission())
                        .riskLevel(source.getRiskLevel())
                        .timeout(Duration.ofSeconds(source.getTimeoutSeconds()))
                        .build());
            }
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private String resolveBaseUrl(Map<String, Object> document, String override) {
        if (override != null && !override.isBlank()) {
            return stripTrailingSlash(override);
        }
        if (document.get("servers") instanceof List<?> servers && !servers.isEmpty()
                && servers.get(0) instanceof Map<?, ?> first
                && first.get("url") != null) {
            return stripTrailingSlash(String.valueOf(((Map<String, Object>) first).get("url")));
        }
        return "";
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String operationId(String method, String path, Map<String, Object> operation) {
        Object id = operation.get("operationId");
        if (id != null && !String.valueOf(id).isBlank()) {
            return String.valueOf(id);
        }
        return method + "_" + path.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private String description(Map<String, Object> operation) {
        Object summary = operation.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            return String.valueOf(summary);
        }
        Object desc = operation.get("description");
        return desc == null ? null : String.valueOf(desc);
    }

    /** 由 path/query 参数 + requestBody(application/json) 合成 {type:object, properties, required} JSON Schema。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInputSchema(Map<String, Object> document, Map<String, Object> operation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (operation.get("parameters") instanceof List<?> params) {
            for (Object item : params) {
                if (!(item instanceof Map<?, ?> paramRaw)) {
                    continue;
                }
                Map<String, Object> param = (Map<String, Object>) paramRaw;
                String in = param.get("in") == null ? "" : String.valueOf(param.get("in"));
                if ("header".equalsIgnoreCase(in) || "cookie".equalsIgnoreCase(in)) {
                    continue; // 头/cookie 参数不作为工具入参
                }
                String name = param.get("name") == null ? null : String.valueOf(param.get("name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                Map<String, Object> schema = param.get("schema") instanceof Map<?, ?> s
                        ? resolveRef(document, (Map<String, Object>) s) : new LinkedHashMap<>();
                Map<String, Object> prop = new LinkedHashMap<>(schema);
                if (param.get("description") != null) {
                    prop.put("description", String.valueOf(param.get("description")));
                }
                properties.put(name, prop);
                if (Boolean.TRUE.equals(param.get("required"))) {
                    required.add(name);
                }
            }
        }

        if (operation.get("requestBody") instanceof Map<?, ?> bodyRaw) {
            Map<String, Object> body = resolveRef(document, (Map<String, Object>) bodyRaw);
            Object jsonSchema = jsonContentSchema(document, body);
            if (jsonSchema instanceof Map<?, ?> schemaRaw) {
                Map<String, Object> schema = resolveRef(document, (Map<String, Object>) schemaRaw);
                if (schema.get("properties") instanceof Map<?, ?> bodyProps) {
                    bodyProps.forEach((k, v) -> properties.put(String.valueOf(k), v));
                    if (schema.get("required") instanceof List<?> bodyReq) {
                        bodyReq.forEach(r -> {
                            String name = String.valueOf(r);
                            if (!required.contains(name)) {
                                required.add(name);
                            }
                        });
                    }
                } else {
                    properties.put("body", schema);
                }
            }
        }

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        return inputSchema;
    }

    @SuppressWarnings("unchecked")
    private Object jsonContentSchema(Map<String, Object> document, Map<String, Object> body) {
        if (!(body.get("content") instanceof Map<?, ?> content)) {
            return null;
        }
        Object json = content.get("application/json");
        if (json instanceof Map<?, ?> jsonMap && jsonMap.get("schema") instanceof Map<?, ?> schema) {
            return resolveRef(document, (Map<String, Object>) schema);
        }
        return null;
    }

    /** 极简 {@code #/components/...} 引用解析；非引用或无法解析时原样返回。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRef(Map<String, Object> document, Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (!(ref instanceof String refStr) || !refStr.startsWith("#/")) {
            return node;
        }
        Object cursor = document;
        for (String segment : refStr.substring(2).split("/")) {
            if (!(cursor instanceof Map<?, ?> map)) {
                return node;
            }
            cursor = ((Map<String, Object>) map).get(segment.replace("~1", "/").replace("~0", "~"));
        }
        return cursor instanceof Map<?, ?> resolved ? (Map<String, Object>) resolved : node;
    }
}
