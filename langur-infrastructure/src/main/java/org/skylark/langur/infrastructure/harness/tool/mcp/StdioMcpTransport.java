package org.skylark.langur.infrastructure.harness.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * {@link McpTransport} 的 STDIO 实现（H6，§7.3）- 拉起本地子进程，按行读写 stdin/stdout 的 JSON-RPC。
 * <p>出站：每条请求序列化为一行写入子进程 stdin；入站：守护读线程逐行读取 stdout 交
 * {@link #deliver} 关联响应/派发通知（含 {@code tools/list_changed}）。stderr 不解析（仅诊断）。
 * 提供流注入构造以便脱离真实进程单测。</p>
 */
public class StdioMcpTransport extends AbstractCorrelatingTransport {

    private final OutputStream stdin;
    private final Process process;
    private final Thread reader;
    private volatile boolean closed;

    /** 流注入构造（单测/自定义进程）：以给定 stdout/stdin 建立传输。 */
    public StdioMcpTransport(InputStream stdout, OutputStream stdin, ObjectMapper mapper, long timeoutMillis) {
        this(stdout, stdin, null, mapper, timeoutMillis);
    }

    private StdioMcpTransport(InputStream stdout, OutputStream stdin, Process process,
                              ObjectMapper mapper, long timeoutMillis) {
        super(mapper, timeoutMillis);
        this.stdin = stdin;
        this.process = process;
        this.reader = new Thread(() -> readLoop(stdout), "mcp-stdio-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** 拉起子进程建立 STDIO 传输；启动失败抛 {@link McpException}。 */
    public static StdioMcpTransport launch(List<String> command, Map<String, String> env,
                                           ObjectMapper mapper, long timeoutMillis) {
        if (command == null || command.isEmpty()) {
            throw new McpException("MCP STDIO transport requires a non-empty command");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (env != null && !env.isEmpty()) {
                builder.environment().putAll(env);
            }
            Process process = builder.start();
            return new StdioMcpTransport(process.getInputStream(), process.getOutputStream(), process,
                    mapper, timeoutMillis);
        } catch (Exception e) {
            throw new McpException("failed to launch MCP stdio process " + command + ": " + e.getMessage(), e);
        }
    }

    @Override
    protected void doSend(String json) throws Exception {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private void readLoop(InputStream stdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    deliver(line);
                }
            }
        } catch (Exception e) {
            if (!closed) {
                // 进程退出/管道关闭属正常生命周期终点，无需惊动主链路
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (process != null) {
            process.destroy();
        }
        reader.interrupt();
    }
}
