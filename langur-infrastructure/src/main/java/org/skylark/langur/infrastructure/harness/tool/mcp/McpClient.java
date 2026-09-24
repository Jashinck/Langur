package org.skylark.langur.infrastructure.harness.tool.mcp;

import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端（§7.3）- 在给定 {@link McpTransport} 上实现 MCP 生命周期方法：
 * {@code initialize} 握手、{@code tools/list} 发现、{@code tools/call} 调用、{@code ping} 心跳。
 * <p>无状态于连接之外，连接/重连编排由 {@link McpClientManager} 负责。</p>
 */
public class McpClient {

    private final String serverName;
    private final String protocolVersion;
    private final McpTransport transport;
    private final AtomicLong idSeq = new AtomicLong();
    private volatile boolean initialized;

    public McpClient(String serverName, String protocolVersion, McpTransport transport) {
        this.serverName = serverName;
        this.protocolVersion = protocolVersion;
        this.transport = transport;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** initialize 握手 + initialized 通知。失败抛 {@link McpException}。 */
    public void initialize() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", protocolVersion);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "langur", "version", "1.0.0"));

        JsonRpcResponse response = request("initialize", params);
        if (response.isError()) {
            throw new McpException("MCP initialize failed on [" + serverName + "]: "
                    + response.error().message());
        }
        try {
            transport.send(JsonRpcRequest.notification("notifications/initialized", Map.of()));
        } catch (Exception e) {
            // initialized 通知失败不致命，握手结果已确认
        }
        initialized = true;
    }

    /** tools/list 发现远端工具。 */
    @SuppressWarnings("unchecked")
    public List<McpToolDescriptor> listTools() {
        JsonRpcResponse response = request("tools/list", Map.of());
        if (response.isError()) {
            throw new McpException("MCP tools/list failed on [" + serverName + "]: "
                    + response.error().message());
        }
        List<McpToolDescriptor> tools = new ArrayList<>();
        Object result = response.result();
        if (result instanceof Map<?, ?> map && map.get("tools") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> tool) {
                    Map<String, Object> t = (Map<String, Object>) tool;
                    Object schema = t.get("inputSchema");
                    tools.add(new McpToolDescriptor(
                            String.valueOf(t.get("name")),
                            t.get("description") == null ? null : String.valueOf(t.get("description")),
                            schema instanceof Map<?, ?> ? (Map<String, Object>) schema : Map.of()));
                }
            }
        }
        return tools;
    }

    /** tools/call 调用远端工具，抽取 content 中的文本负载拼接返回。 */
    @SuppressWarnings("unchecked")
    public String callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments != null ? arguments : Map.of());

        JsonRpcResponse response = request("tools/call", params);
        if (response.isError()) {
            throw new McpException("MCP tools/call failed on [" + serverName + "." + name + "]: "
                    + response.error().message());
        }
        Object result = response.result();
        if (!(result instanceof Map<?, ?> map)) {
            return result == null ? "" : String.valueOf(result);
        }
        Map<String, Object> resultMap = (Map<String, Object>) map;
        boolean isError = Boolean.TRUE.equals(resultMap.get("isError"));
        String text = extractText(resultMap.get("content"));
        if (isError) {
            throw new McpException("MCP tool [" + serverName + "." + name + "] returned error: " + text);
        }
        return text;
    }

    /** ping 心跳；失败抛异常，由管理器据此触发重连。 */
    public void ping() {
        JsonRpcResponse response = request("ping", Map.of());
        if (response.isError()) {
            throw new McpException("MCP ping failed on [" + serverName + "]: " + response.error().message());
        }
    }

    private String extractText(Object content) {
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> part) {
                    Object type = part.get("type");
                    Object text = part.get("text");
                    if ((type == null || "text".equals(String.valueOf(type))) && text != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return content == null ? "" : String.valueOf(content);
    }

    private JsonRpcResponse request(String method, Map<String, Object> params) {
        try {
            JsonRpcResponse response = transport.send(JsonRpcRequest.of(nextId(), method, params));
            if (response == null) {
                throw new McpException("MCP server [" + serverName + "] returned no response for " + method);
            }
            return response;
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("MCP transport error on [" + serverName + "] " + method + ": "
                    + e.getMessage(), e);
        }
    }

    private String nextId() {
        return serverName + "-" + idSeq.incrementAndGet();
    }
}
