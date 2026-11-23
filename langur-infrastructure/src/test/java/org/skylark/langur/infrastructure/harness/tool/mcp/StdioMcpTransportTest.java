package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcRequest;
import org.skylark.langur.infrastructure.harness.tool.mcp.jsonrpc.JsonRpcResponse;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6 验收：{@link StdioMcpTransport} 以进程管道按行收发 JSON-RPC——请求经 stdin 写出、
 * 响应经 stdout 守护读线程按 id 关联；服务端通知（{@code tools/list_changed}）实时派发监听器。
 * 以管道流模拟对端子进程，无需真实进程。
 */
class StdioMcpTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCorrelateToolsCallResponseOverPipes() throws Exception {
        PipedOutputStream toTransport = new PipedOutputStream();
        PipedInputStream transportStdout = new PipedInputStream(toTransport);
        PipedOutputStream transportStdin = new PipedOutputStream();
        PipedInputStream peerIn = new PipedInputStream(transportStdin);

        StdioMcpTransport transport = new StdioMcpTransport(transportStdout, transportStdin, mapper, 5_000);

        Thread peer = new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(peerIn, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                JsonRpcRequest req = mapper.readValue(line, JsonRpcRequest.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) req.params();
                String resp = mapper.writeValueAsString(new JsonRpcResponse("2.0", req.id(),
                        Map.of("content", List.of(Map.of("type", "text", "text", "ok:" + params.get("name"))),
                                "isError", false), null));
                toTransport.write((resp + "\n").getBytes(StandardCharsets.UTF_8));
                toTransport.flush();
            } catch (Exception ignored) {
                // 对端异常在测试断言处暴露
            }
        }, "stdio-peer");
        peer.setDaemon(true);
        peer.start();

        JsonRpcResponse response =
                transport.send(JsonRpcRequest.of("1", "tools/call", Map.of("name", "read_file")));

        assertNotNull(response);
        assertFalse(response.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertEquals("ok:read_file", content.get(0).get("text"));
        transport.close();
    }

    @Test
    void shouldDispatchListChangedNotification() throws Exception {
        PipedOutputStream toTransport = new PipedOutputStream();
        PipedInputStream transportStdout = new PipedInputStream(toTransport);
        StdioMcpTransport transport =
                new StdioMcpTransport(transportStdout, new ByteArrayOutputStream(), mapper, 5_000);

        CountDownLatch latch = new CountDownLatch(1);
        List<String> methods = Collections.synchronizedList(new ArrayList<>());
        transport.setNotificationListener((method, params) -> {
            methods.add(method);
            latch.countDown();
        });

        String notification =
                mapper.writeValueAsString(JsonRpcRequest.notification("notifications/tools/list_changed", Map.of()));
        toTransport.write((notification + "\n").getBytes(StandardCharsets.UTF_8));
        toTransport.flush();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "notification listener not fired");
        assertTrue(methods.stream().anyMatch(m -> m.endsWith("tools/list_changed")));
        transport.close();
    }
}
