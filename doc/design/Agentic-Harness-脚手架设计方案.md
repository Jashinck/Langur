# Agentic Harness 脚手架设计方案

> 基于 Claude Agent SDK / OpenAI Codex SDK / DeepSeek Harness / AgentScope 四家 Harness SDK 的能力调研，
> 针对你现有的 **DDD + 六边形架构 Agentic Harness 框架**，回答三个问题：
> 1. 哪些能力是脚手架的**通用能力**；
> 2. 如何把它们映射到六边形的 port/adapter；
> 3. 如何**提供一个脚手架**并把通用能力封装进**固定的几个 jar**。

---

## 0. 结论速览（TL;DR）

- **通用能力 = 11 个维度**（见 §2），是四家 SDK 高度重合、任何 agent 脚手架都必须覆盖的能力面。
- **六边形切法**：领域核心只保留「消息/会话/Turn 类型体系 + AgentLoop 状态机 + 端口接口」，其余全部下沉为 outbound adapter。
- **固定 jar = 6 个**（`harness-core` / `harness-runtime` / `harness-models` / `harness-tools` / `harness-persistence` / `harness-infra`）+ 1 个 `harness-bom`。
- **脚手架 = Maven Archetype（生成六边形骨架）+ BOM（锁版本）+ 一个 `harness-starter`（SPI 自动装配）**，参照 Spring Boot starter 思路，业务方只依赖接口 jar、通过 ServiceLoader 插拔实现。

---

## 1. 四家 SDK 能力矩阵

| 能力维度 | Claude Agent SDK | OpenAI Codex SDK | DeepSeek Harness (`dsh`) | AgentScope 1.0 |
|---|---|---|---|---|
| **架构形态** | 瘦客户端，驱动 bundled `claude` CLI 子进程（stdio + JSON control protocol） | 驱动 `codex-rs` Rust 核心（`codex exec` / `@openai/codex-sdk`） | 全功能内核，事件日志 + "Everything is a Plugin"（Cordis，45+ packages） | in-process 异步库（`asyncio`），Runtime/Studio 配套 |
| **Agent Loop** | 循环在子进程内，`max_turns`/`max_budget_usd`/`interrupt` | 循环在 core 内，`resumeThread`/fork | Turn→step 为**可重放的持久事件日志** | `AgentBase`/`ReActAgent`，`_reasoning`/`_acting` 拆分 |
| **模型/Provider 抽象** | ❌ 不可插拔（仅 model 别名 + fallback 链 + gateway env） | `config.toml` profiles + 自定义 `base_url`/`wire_api`/`env_key` | **三协议多 adapter**（OpenAI Chat / Anthropic / Responses）+ `BlockAssembler` | `ChatModelBase` + `Formatter`（DashScope/OpenAI/Anthropic/Gemini/Ollama） |
| **工具系统** | 内置工具在子进程；自定义 = in-process SDK MCP server | `shell`/`apply_patch`/`web_search`/MCP | ~50 内置工具 + MCP + plugin 体系 | `ServiceToolkit` + `register_tool_function` + MCP |
| **上下文/记忆** | CLAUDE.md/`.claude/rules` 文件即记忆；auto-compact 摘要 | AGENTS.md 发现/合并 + auto-compact | `context`/`compaction`/`skill`/`todo`/`plan`/`goal` | `Memory` + long-term（Mem0）+ `StateModule` |
| **会话/状态** | `SessionStore` port + transcript jsonl + resume/fork | thread 落盘 + resume/fork | session 持久化 + **事件重放** | session history 由 Runtime 托管 |
| **权限/审批** | 6 步决策链 + `canUseTool` 回调 + 权限模式 | `ask_for_approval` 4 档（untrusted/on-failure/on-request/never） | `guard`（审批）+ permission preset + fail-closed | 靠 hook 自实现 |
| **沙箱/执行隔离** | bubblewrap / sandbox-exec + 网络代理 | Seatbelt(macOS) / Landlock+seccomp(Linux)，3 档 sandbox mode | 每会话 policy + `ConfinedArgv`，fail-closed | Runtime sandbox（browser/fs/training） |
| **Hooks/生命周期** | ~35 个事件（Pre/PostToolUse、SessionStart…） | 弱（靠事件流） | `interaction`/`feedback`/guard | `pre_reply`/`post_reply` + 自定义 hook |
| **可观测性/成本** | OTLP 由子进程直出；`total_cost_usd` 客户端估算 | OTel + token 计费 | OTLP + `token-meter`（归一化 token 计数） | Studio tracing + token usage |
| **多智能体** | subagent + teams + 并行工具 | 单线程 + 工具（SDK 弱） | subagent + Agent Teams + task DAG | `MsgHub` + pipeline（sequential/fanout/while） |
| **HITL** | `canUseTool` 审批 + `interrupt()` + steering | approval + interrupt | `ask_user_question` + `exit_plan_mode` | user agent / hook |
| **结构化输出** | `outputFormat: json_schema` | 靠工具/弱 | 靠工具 | `structured_output` |
| **配置分层** | settings 分层（managed>flag>local>project>user） | `config.toml` + profiles | `preset`/`boot`/`settings` | `agentscope.init` |

**一句话对比结论**：四家在实现哲学上分两派——
- **子进程瘦客户端派**（Claude、Codex）：loop/工具/权限/sandbox 都在子进程里，SDK 只留消息类型 + options DSL + 回调端口。`SessionStore`、`canUseTool`、hooks 是它俩都显式暴露的 **port**。
- **全功能内核派**（DeepSeek Harness、AgentScope）：loop 在进程内，靠「事件日志可重放」或「async 循环」驱动，provider/工具/记忆都是可插拔 adapter。

这恰好对应你六边形框架的两种极端，**通用能力就藏在两派的重合处**。

---

## 2. 脚手架的通用能力清单（11 维）

下面 11 项是「agent 脚手架」这一品类的最小完备能力集——缺了任何一项，换一个模型供应商或接入一种新工具就会破坏抽象。每项标注了四家的佐证。

### 2.1 Agent Loop / Turn 编排（核心引擎）
- **必做**：一个与模型供应商无关的循环状态机 `reason → act(工具) → observe(结果) → repeat`，直到 `finish`/达到 `max_turns`/超预算/被 `interrupt`。
- **必须流式**：单发（blocking）与流式（streaming input）两种驱动模式；流式下可 mid-run 注入消息（steering）。
- **可重放**：DeepSeek Harness 的关键教训——把每一轮建模为**持久领域事件流**（`TurnStarted → StepStarted → AssistantMessage → ToolCall → ToolResult → StepEnded → TurnEnded`），状态可从日志完整重建。这是 session 恢复、审计、追踪的共同底座。

### 2.2 模型/Provider 抽象 + Formatter（出站 Port）
- 统一 `ModelRequest`（消息列表 + 工具 schema + 采样/推理参数）与 `ModelResponse`（content block 流：`text/reasoning/tool_use`）。
- **按协议而非按厂商适配**（DeepSeek 教训）：一个供应商可能同时暴露 OpenAI Chat / Anthropic Messages / Responses 三种线协议，Port 应 key 在 protocol 上。
- **Formatter 独立成层**（AgentScope 教训）：把"领域消息 → 某协议请求体"的 prompt 格式化单独抽象，供应商差异不外泄到领域层。

### 2.3 工具系统 + MCP 客户端（出站 Port）
- 统一工具契约：`name/description/JSON-Schema/readOnlyHint` + `ToolResult{content[], isError}`。
- 内置工具（fs/exec/web/search/todo）+ 自定义工具注册 + **MCP 客户端适配器**（stdio/SSE/streamable-http），自定义工具最终统一收敛为"工具注册表"。
- 并行工具调用：只读并行、写串行（Claude 语义）。

### 2.4 上下文/记忆管理（出站 Port）
- 指令分层注入（system prompt vs 注入 conversation 的 CLAUDE.md/AGENTS.md 类文件，**每请求重注入**抗 compaction）。
- **compaction/摘要**：接近窗口自动摘要 + `compact_boundary` 标记；token 预算可视化。
- 长期记忆（Mem0 风格）与文件即记忆两条腿。

### 2.5 会话与状态持久化（出站 Port）
- `sessionId` + resume/fork/截断续跑；transcript 落盘。
- **`SessionStore` 是独立 Port**（Claude 显式暴露：`append/load/list/delete`），本地文件为默认实现，可镜像到 Redis/JDBC。

### 2.6 权限/审批（出站 Port + 领域策略）
- 权限决策链（hooks → deny 规则 → ask 规则 → 模式 → allow 规则 → 回调）与 `ALLOW/DENY/ASK` 三态决策。
- **`canUseTool` 回调即编程化审批入口**；规则可 scoped（`Bash(npm *)`、`Edit(//secrets/**)`）。
- 这是**领域对象**（PermissionDecision 是纯领域概念），审批的"人/系统"是 adapter。

### 2.7 沙箱/执行隔离（出站 Port）
- 沙箱模式分级（read-only / workspace-write / danger-full-access）；文件系统 + 网络策略；fail-closed。
- 底层实现是 adapter（macOS Seatbelt / Linux Landlock+seccomp / Docker / 进程降权），Port 只暴露 `SandboxPolicy` 与 `exec()`。

### 2.8 Hooks / 生命周期拦截（入站回叫 Port）
- 事件集：`PreToolUse/PostToolUse/SessionStart/SessionEnd/PreCompact/SubagentStart/Stop…`。
- 这是**护栏（guardrails）与策略的主入口**：hook 可 `allow/deny/ask`、改输入、注入上下文。领域层发事件，hook 实现由使用方注册。

### 2.9 可观测性与成本计量（出站 Port）
- Trace（OTel）+ 结构化日志 + **token/成本归一化**。
- **归一化是关键契约**（DeepSeek 教训）：输入 token 中缓存命中要拆出、`reasoning_tokens` 不得重复计入 `completion_tokens`。成本是估算值，**禁用于财务**。

### 2.10 多智能体编排（领域 + 编排原语）
- subagent 定义（prompt/tools/model/maxTurns）+ 隔离上下文 + 只回传最终消息。
- 编排原语：pipeline（sequential/fanout/while）、MsgHub/消息路由、handoff。

### 2.11 结构化输出 + 配置分层
- `outputFormat: json_schema` 与重试上限。
- 配置分层：`managed > programmatic > local > project > user`，credentials 与 provider base_url 可注入。

> **不属于通用能力的**（留给使用方，脚手架不碰）：账单级计费、交互式 OAuth 浏览器流程、跨主机会话一致性锁、UI 渲染、具体业务工具。

---

## 3. 映射到六边形架构

```
                      ┌─────────────────────────────────────────────┐
  Driving Adapters    │            DOMAIN（harness-core）            │  Driven Adapters
  (入站)              │   Agent · Session · Turn · Message           │  (出站)
                      │   ToolCall · ToolResult · ModelRequest/Resp  │
  HTTP/SSE ─────────▶ │   PermissionDecision · DomainEvent           │ ─────────▶ ModelPort
  CLI      ─────────▶ │                                             │ ─────────▶ ToolPort
  MCP srv  ─────────▶ │   AgentLoop（turn 状态机，可重放事件流）       │ ─────────▶ SessionStorePort
  ACP      ─────────▶ │   （Port 接口全部定义在此，领域不依赖实现）      │ ─────────▶ PolicyPort
                      └─────────────────────────────────────────────┘ ─────────▶ HookPort
                                                                      ─────────▶ SandboxPort
                                                                      ─────────▶ TelemetryPort
```

| 六边形位置 | 内容 | 对应 §2 能力 |
|---|---|---|
| **领域核心（无外部依赖）** | Agent/Session/Turn/Message/ToolCall/ToolResult、PermissionDecision、DomainEvent、AgentLoop 状态机 | 2.1、2.10（编排模型）、2.6（决策模型） |
| **Port 接口（出站 SPI）** | `ModelPort` `ToolPort` `SessionStorePort` `PolicyPort` `HookPort` `SandboxPort` `TelemetryPort` | 2.2/2.3/2.5/2.6/2.8/2.7/2.9 |
| **入站 Adapter（Driving）** | HTTP/SSE、CLI、MCP server、ACP server | 驱动形态 |
| **出站 Adapter（Driven）** | OpenAI/Anthropic/DeepSeek/Ollama 适配器、MCP client、file/redis/jdbc 存储、OTel、沙箱实现 | 2.2/2.3/2.5/2.7/2.9 |

**依赖方向（不可违背）**：`bootstrap → adapters → application → domain`。领域层与 Port 接口零依赖；所有实现 jar 反向依赖 `harness-core`。

---

## 4. 固定 jar 拆分方案（6 + 1）

groupId 示例：`com.agentic.harness`。jar 之间只允许沿下方向依赖。

```
harness-bom                          # 版本管理（dependencyManagement），锁定下面全部版本
│
├─ harness-core        (领域 + Port 接口 + AgentLoop 引擎 + 领域事件)
│     依赖：仅 JSON 处理（如 Jackson），无框架
│
├─ harness-runtime     (应用层：RunAgentUseCase/ResumeUseCase、内存默认实现、装配 DSL、ServiceLoader 引导)
│     依赖：core
│
├─ harness-models      (出站：LLM 适配器全家桶 + Formatter)
│     openai-chat / anthropic-messages / openai-responses / deepseek / ollama / openai-compatible
│     依赖：core
│
├─ harness-tools       (出站：工具注册表 + 内置工具 + MCP 客户端适配器)
│     依赖：core（可选 models）
│
├─ harness-persistence (出站：SessionStore/Memory 适配器：file / redis / jdbc)
│     依赖：core
│
└─ harness-infra       (出站：沙箱实现 + OTel 遥测 + 配置分层加载)
      依赖：core
```

**为什么是 6 个而不是 10 个**：入站适配器（HTTP/CLI/MCP-server）差异太大、与业务耦合深，**不进固定 jar**，由脚手架模板生成（见 §5）。这 6 个 jar 覆盖的是"换供应商、换存储、换沙箱、换工具"四个最频繁的替换点。

**依赖倒置的关键**：业务模块永远只 `compile` `harness-core`（拿接口）+ 可选 `harness-runtime`（拿装配）；具体实现 jar 通过 **ServiceLoader**（`META-INF/services`）或 DI 容器条件装配注入，编译期不产生硬依赖。这样业务方可以整体替换 `harness-persistence` 而无需改一行领域代码。

---

## 5. 脚手架如何提供

推荐三件套，参照 Spring Boot starter + Initializr 的组合：

### 5.1 `harness-bom`（版本锁定）
`dependencyManagement` 统一 6 个 jar 的版本，业务方只声明一个 bom 版本。

### 5.2 Maven Archetype（生成六边形骨架）
`mvn archetype:generate -DarchetypeGroupId=com.agentic.harness -DarchetypeArtifactId=harness-archetype -DarchetypeVersion=1.0.0` 生成：

```
my-agent/
├── pom.xml                       # 依赖 bom + core + runtime；适配器按需引入
├── bootstrap/                    # 装配模块：组装依赖（唯一知道实现细节的模块）
│   └── src/main/java/.../Bootstrap.java      # 加载 ServiceLoader / 构建 ApplicationContext
├── domain/                       # 业务领域：自定义 Agent、Policy、领域事件
│   └── src/main/java/.../agent/MyAgent.java  # 继承 core 的 Agent，实现业务编排
├── application/                  # 用例：编排 domain + port
│   └── src/main/java/.../usecase/MyUseCase.java
├── adapters/
│   ├── in-http/                  # 入站：REST/SSE（由模板生成，可删）
│   ├── in-cli/                   # 入站：CLI（picocli）
│   └── out-my-tools/             # 出站：业务自定义工具（实现 ToolPort）
│   └── out-my-model/             # 出站：自定义 provider（实现 ModelPort，可选）
├── config/
│   └── application.yaml          # provider/tool/session/sandbox/telemetry 配置
└── src/main/resources/
    └── META-INF/services/...     # 注册自定义 ToolPort/PolicyPort 实现
```

### 5.3 `harness-starter`（SPI 自动装配，可选增强）
一个薄 starter，职责只有：扫 `META-INF/services`，把 `harness-models/tools/persistence/infra` 里的默认实现按配置装配成可运行的 `Harness`。等价于 Spring Boot 的 `@AutoConfiguration`，但保持框架无关（可用纯 ServiceLoader 或适配 Spring/Quarkus 两版）。

**给使用方的三条铁律**（写进 README/AGENTS.md）：
1. 只依赖 `harness-core` 的接口，不 import 任何 `*Adapter` 具体类。
2. 新增能力 = 实现一个 Port 接口 + 在 `META-INF/services` 注册，不改框架。
3. 领域层禁止出现 `HttpServletRequest`、JDBC、OTel、供应商 SDK 等符号。

---

## 6. 关键 Port 契约（Java 示例，框架无关）

```java
// ---- 领域消息（core）----
public sealed interface ContentBlock permits TextBlock, ReasoningBlock, ToolUseBlock {}
public record TextBlock(String text) implements ContentBlock {}
public record ReasoningBlock(String reasoning) implements ContentBlock {}   // 一等公民：DeepSeek reasoning_content 必须回传
public record ToolUseBlock(String id, String name, String rawArguments) implements ContentBlock {} // 参数保持原始 JSON

// ---- ModelPort：按协议而非厂商 ----
public interface ModelPort {
    String protocol();                                  // "openai-chat" | "anthropic-messages" | "openai-responses" | ...
    ModelResponse complete(ModelRequest request, RunContext ctx);
    Stream<ModelChunk> stream(ModelRequest request, RunContext ctx);
}
public record ModelResponse(
    List<ContentBlock> content,                         // text / reasoning / tool_use 混合
    Usage usage,                                        // 归一化：不重复计 reasoning/cached token
    FinishReason finishReason                           // 含 truncation：截断时丢弃所有 tool_use
) {}
public record Usage(long inputTokens, long cachedInputTokens, long reasoningTokens, long outputTokens) {}

// ---- ToolPort ----
public interface ToolPort {
    String name();
    String description();
    JsonNode inputSchema();
    boolean readOnly();                                 // 只读可并行
    ToolResult invoke(String toolCallId, JsonNode args, RunContext ctx);
}
public record ToolResult(List<ContentPart> content, boolean isError) {}

// ---- SessionStorePort（可重放事件流是唯一事实源）----
public interface SessionStorePort {
    void append(String sessionId, List<DomainEvent> events);
    List<DomainEvent> load(String sessionId);           // 重放 → 重建会话状态
    List<SessionSummary> listSessions();
    void delete(String sessionId);
}

// ---- PolicyPort：审批三态 ----
public enum Decision { ALLOW, DENY, ASK }
public interface PolicyPort {
    Decision decide(ToolCall call, RunContext ctx);
}

// ---- HookPort：护栏主入口 ----
public interface HookPort {
    HookResult beforeTool(ToolCall call, RunContext ctx);   // 可 ALLOW/DENY/ASK、改输入、注入上下文
    void onEvent(DomainEvent event, RunContext ctx);        // SessionStart/PostToolUse/PreCompact/...
}

// ---- SandboxPort ----
public interface SandboxPort {
    SandboxHandle create(SandboxPolicy policy);             // 文件系统 + 网络策略，fail-closed
    ExecResult exec(SandboxHandle h, List<String> argv, Map<String, String> env);
}

// ---- TelemetryPort ----
public interface TelemetryPort {
    void trace(Span span);
    void recordMetric(Metric metric);
    void log(LogRecord record);
}
```

**DeepSeek 适配层三条硬约束**（直接编码进 `ModelPort` 契约，避免换供应商时踩坑）：
1. `reasoning_content` 是 `content` 的兄弟字段、可空、流式增量到达——模型化为一等 `ReasoningBlock`，**带 tools 的请求必须把历史 reasoning 拼回**。
2. 缓存计数是折叠关系（`prompt_tokens = cache_hit + cache_miss`）——`Usage` 一律归一化为互斥计数，禁止把 `reasoningTokens`/`cachedTokens` 加进总数。
3. tool-call 参数全程保持原始 JSON；`max_tokens` 触顶结束必须**丢弃所有 tool_use**（截断的调用不可执行）。

---

## 7. 落地路线图

1. **里程碑 1（骨架）**：`harness-core`（领域 + 7 个 Port + AgentLoop 状态机 + 事件流）+ `harness-bom`。这是不可变地基，先冻结接口。
2. **里程碑 2（单供应商跑通）**：`harness-runtime` + `harness-models`（先只做 OpenAI-compatible + DeepSeek 两个适配器验证 Formatter 抽象）+ `harness-tools`（内置 fs/exec + MCP client）。
3. **里程碑 3（可持久化/可观测）**：`harness-persistence`（file 默认 + redis）+ `harness-infra`（OTel + 沙箱 process/docker）。
4. **里程碑 4（脚手架）**：`harness-archetype` + `harness-starter` + 一个端到端示例工程（`examples/`），CI 验证 `archetype:generate` 生成即运行。
5. **里程碑 5（多智能体）**：在 `harness-runtime` 补 pipeline/MsgHub 编排原语，subagent 定义下沉为领域对象。

---

## 参考来源

- Claude Agent SDK 官方文档：https://code.claude.com/docs/en/agent-sdk/overview （overview / agent-loop / configuration / hooks / permissions / sessions / session-storage / structured-outputs / observability / subagents）
- OpenAI Codex：https://developers.openai.com/codex/ ・ 沙箱与审批 https://learn.chatgpt.com/docs/sandboxing ・ Codex SDK 用法示例 https://developers.openai.com/cookbook/examples/codex/build_code_review_with_codex_sdk ・ 审批策略变更 https://blakecrosley.com/blog/codex-untrusted-approval-policy-retired
- DeepSeek Harness：https://github.com/deepseek-ai/deepseek-harness ・ https://www.deepseek.com/harness/ ・ https://deepseek-harness.github.io/deepseek-harness/ ・ API 文档 https://api-docs.deepseek.com
- AgentScope 1.0：论文 https://arxiv.org/html/2508.16279v1 ・ 官网 https://agentscope.io/ ・ Runtime 文档 https://runtime.agentscope.io/zh/CHANGELOG.html
