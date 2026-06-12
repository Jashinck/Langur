# 从单模块到六边形架构：Langur Agent 框架的 DDD 多模块拆分实践

> **作者**：Jashinck  
> **标签**：DDD / 六边形架构 / Spring Boot / AI Agent / 工程实践  
> **适合读者**：有一定 Spring Boot 基础、对 DDD 或 AI 应用架构感兴趣的开发者

---

## 背景

Langur 是一个基于 Java / Spring Boot 的**通用 AI Agent 能力框架**，支持 ReAct 推理循环、工具调用、多步规划与多 Agent 协作，底层对接 OpenAI 兼容接口。

项目初始是一个单 Maven 模块，所有代码放在同一 `pom.xml` 下。随着业务功能不断增加，我们遇到了几个典型痛点：

- **依赖方向混乱**：`LLMPort` 接口放在 `infrastructure` 包里，但领域层却要引用它，严重违背了六边形架构的"领域不依赖基础设施"原则。
- **层间耦合**：应用层（Application）的 `AgentAssembler` 直接引用了接口层（Interfaces）的 DTO，形成了逆向依赖。
- **异常语义模糊**：`getLatestPlan` 找不到 Plan 时抛 `AgentNotFoundException`，混淆了两个不同的业务概念。

这次重构的核心目标：**用 DDD 六边形架构对项目进行多模块拆分，彻底厘清各层职责与依赖方向。**

---

## 模块划分：六个模块，一条单向依赖链

重构后，项目被拆分为六个 Maven 子模块：

```
langur-start (fat jar 启动入口)
  ├── langur-api          → 依赖 langur-application
  ├── langur-infrastructure → 依赖 langur-domain
  └── (传递依赖)
        langur-application → langur-domain → langur-common
```

| 模块 | 职责 | Spring 依赖 |
|------|------|-------------|
| `langur-common` | 异常体系、公共值对象，无任何框架依赖 | ❌ |
| `langur-domain` | 聚合根、领域服务、端口接口（SPI） | ❌ |
| `langur-application` | 应用服务、Command、应用层 DTO | ✅ |
| `langur-api` | HTTP Controller、Request/Response DTO、API Assembler | ✅ |
| `langur-infrastructure` | LLM 适配器、Repository 实现、工具注册 | ✅ |
| `langur-start` | 主类、`application.yml`、打包配置 | ✅ |

> **关键原则**：依赖箭头永远指向更内层，内层不感知外层的存在。

---

## 核心设计详解

### 1. 领域层：拥有端口契约（六边形架构精髓）

六边形架构最核心的一条规则是：**端口（Port）属于领域，而不是基础设施**。

在旧代码中，`LLMPort` 定义在 `infrastructure.llm` 包下，这意味着领域层要调用 LLM 时，必须依赖基础设施层——反向依赖。

重构后，`LLMPort` 和 `ToolProvider` 都移入 `langur-domain` 的 `domain.port` 包：

```java
// langur-domain: domain/port/LLMPort.java
public interface LLMPort {
    LLMDecision decide(String systemPrompt,
                       List<Map<String, String>> conversationHistory,
                       List<Tool> availableTools);

    String complete(String systemPrompt, String userMessage);

    // 决策结果：工具调用 or 最终答案
    class LLMDecision {
        public static LLMDecision toolCall(String thought, String toolName, Map<String, Object> args) { ... }
        public static LLMDecision finalAnswer(String answer) { ... }
    }
}
```

基础设施层的 `OpenAILLMAdapter` 实现这个接口，依赖方向变为：

```
infrastructure → domain (✅ 正确)
domain → infrastructure (❌ 已消除)
```

同理，`ToolProvider` 端口由 `BuiltinToolRegistry` 在基础设施层实现，领域层只依赖接口。

---

### 2. 聚合根 Agent：封装状态与领域事件

`Agent` 是整个框架的核心聚合根，代表一个具备自主决策和工具调用能力的智能体：

```java
@Getter
public class Agent {
    private final AgentId id;
    private AgentConfig config;
    private AgentStatus status;
    private final List<Tool> tools;
    private final List<Map<String, String>> conversationHistory;
    private final List<DomainEvent> domainEvents;

    // 工厂方法：新建
    public static Agent create(AgentConfig config) { ... }

    // 工厂方法：从持久化恢复
    public static Agent restore(AgentId id, AgentConfig config, ...) { ... }

    // 状态迁移（含校验）
    public void markRunning() { ... }
    public void markCompleted(String finalAnswer) { ... }
    public void markFailed(String error) { ... }

    // 领域事件（pull 后自动清空）
    public List<DomainEvent> pullDomainEvents() { ... }
}
```

几个值得关注的设计点：

- **私有构造 + 静态工厂**：区分"新建"与"恢复"两种语义，避免将持久化逻辑侵入构造函数。
- **不可变集合暴露**：`getTools()` 返回 `Collections.unmodifiableList`，防止外部篡改聚合根内部状态。
- **领域事件 pull 模式**：调用 `pullDomainEvents()` 一次性取走并清空事件队列，天然支持应用层在事务提交后发布事件。

---

### 3. 应用层：引入独立 DTO，切断逆向依赖

原代码中，应用层 `AgentAssembler` 直接构建并返回了接口层的 HTTP Response DTO（`AgentResponse`），产生了"应用层 → 接口层"的非法向上依赖。

解法是在应用层引入独立的结果 DTO，让转换职责分层：

```
接口层 DTO          应用层 DTO         领域模型
AgentResponse  ←  AgentResult  ←  Agent
RunTaskResponse ← RunTaskResult ← AgentRunTask
PlanResponse   ←  PlanResult   ←  Plan
```

- **`AgentAssembler`**（`langur-application`）：`Agent` → `AgentResult`
- **`AgentApiAssembler`**（`langur-api`）：`AgentResult` → `AgentResponse`

每一层只做本层内的 DTO 转换，严格保持单向依赖。

---

### 4. API 层：职责内聚的 Controller

`AgentController` 只做两件事：**HTTP 协议解析** + **委托给应用服务**，不含任何业务逻辑：

```java
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentApplicationService agentApplicationService;
    private final AgentRunTaskApplicationService runTaskApplicationService;
    private final AgentApiAssembler apiAssembler;

    @PostMapping("/{agentId}/run/async")
    public ResponseEntity<RunTaskResponse> runAgentAsync(
            @PathVariable String agentId,
            @RequestBody RunAgentRequest request) {
        RunAgentCommand command = buildRunCommand(agentId, request);
        return ResponseEntity.accepted().body(
                apiAssembler.toResponse(runTaskApplicationService.startAsyncRun(command)));
    }

    @GetMapping("/{agentId}/plan")
    public ResponseEntity<PlanResponse> getLatestPlan(@PathVariable String agentId) {
        PlanResult plan = agentApplicationService.getLatestPlan(agentId)
                .orElseThrow(() -> new PlanNotFoundException(agentId));
        return ResponseEntity.ok(apiAssembler.toResponse(plan));
    }
    // ...
}
```

注意 `getLatestPlan` 的异常修复：找不到 Plan 时抛 `PlanNotFoundException` 而非 `AgentNotFoundException`，语义更精确，也方便接入全局异常处理返回不同 HTTP 状态码。

---

### 5. 公共模块：无框架依赖的异常体系

`langur-common` 只包含异常层次结构，刻意不引入任何 Spring 依赖，使其成为真正的"零依赖"基础：

```java
// 基础异常
public abstract class LangurException extends RuntimeException { ... }

// 业务语义异常
public class AgentNotFoundException extends LangurException { ... }
public class PlanNotFoundException extends LangurException { ... }
public class ToolNotFoundException extends LangurException { ... }
```

这样，异常类可以在 `domain`、`application`、`api` 任意层引用，而不会带入额外的框架依赖。

---

## 依赖方向全景图

```
┌─────────────────────────────────────────────────────┐
│                   langur-start                      │
│           (主类 + application.yml + 打包)            │
└───────────────────┬─────────────────────────────────┘
                    │ 聚合所有模块
        ┌───────────┴──────────┐
        ▼                      ▼
┌──────────────┐      ┌─────────────────────┐
│  langur-api  │      │ langur-infrastructure│
│ (Controller) │      │  (Adapter/Repo/Tool) │
└──────┬───────┘      └──────────┬───────────┘
       │ 依赖                    │ 依赖
       ▼                         ▼
┌──────────────────────────────────┐
│        langur-application        │
│   (AppService / Command / DTO)   │
└──────────────┬───────────────────┘
               │ 依赖
               ▼
┌──────────────────────────────────┐
│          langur-domain           │
│  (聚合根 / 领域服务 / Port 接口)  │
└──────────────┬───────────────────┘
               │ 依赖
               ▼
┌──────────────────────────────────┐
│          langur-common           │
│       (异常体系 / 值对象)         │
└──────────────────────────────────┘
```

---

## 重构前后对比

| 问题 | 重构前 | 重构后 |
|------|--------|--------|
| `LLMPort` 位置 | `infrastructure.llm`（基础设施层） | `domain.port`（领域层） |
| 应用层 Assembler | 引用 `interfaces.dto.*`（逆向依赖） | 只引用 `application.dto.*` |
| 接口层 Assembler | 不存在，混在 Controller 里 | 独立 `AgentApiAssembler` |
| Plan 未找到异常 | 抛 `AgentNotFoundException` | 抛 `PlanNotFoundException` |
| 模块边界 | 单模块，包名约定，无强制隔离 | Maven 多模块，编译期强制依赖方向 |

---

## 实践总结

### 六边形架构落地的三条准则

1. **端口归领域，适配器归基础设施**：`LLMPort`、`ToolProvider` 等 SPI 接口必须定义在 `domain.port` 包，基础设施只负责实现，永远不拥有契约。

2. **每层有且只有本层 DTO**：领域层用领域对象，应用层用应用结果 DTO，接口层用 HTTP DTO。Assembler 只做相邻层之间的单向转换，禁止跨层引用。

3. **Maven 模块是最好的架构护栏**：包名约定靠人遵守，但 Maven 模块依赖图是编译器强制执行的。错误的依赖在 `mvn compile` 时就会报错，不会等到 Code Review。

### 为什么这对 AI Agent 框架尤其重要？

AI Agent 框架的特殊性在于：**LLM 是一个外部系统**，就像数据库一样，应该被视为基础设施。

如果领域逻辑直接耦合到 OpenAI SDK，以后切换到 Claude、本地 Ollama，甚至 Mock LLM 做单元测试，都会非常痛苦。通过 `LLMPort` 的依赖倒置，领域层的 ReAct 推理循环完全不感知底层调用哪家 LLM，只需关注"给我一个决策（工具调用 or 最终答案）"的业务语义。

---

## 快速体验

```bash
# 克隆项目
git clone https://github.com/Jashinck/Langur.git
cd Langur

# 配置 OpenAI API Key
export OPENAI_API_KEY=sk-...

# 打包（跳过测试）
mvn -DskipTests package

# 启动
java -jar langur-start/target/langur.jar

# 创建一个 Agent
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{"name":"my-agent","systemPrompt":"You are a helpful assistant.","model":"gpt-4o-mini","maxIterations":10}'

# 异步执行任务
curl -X POST http://localhost:8080/api/agents/{agentId}/run/async \
  -H "Content-Type: application/json" \
  -d '{"userMessage":"帮我查询今天的天气"}'
```

---

## 小结

本次重构不是为了重构而重构，而是在项目规模扩大、问题暴露后做出的**架构偿还**。六边形架构的核心价值是**可测试性**和**可替换性**：领域逻辑不依赖任何框架，可以在没有 Spring 容器的纯 Java 环境中单元测试；基础设施实现（LLM、持久化）随时可替换，只需实现对应的 Port 接口。

如果你的项目也正在经历"包名约定守不住、层间依赖越来越乱"的困境，不妨考虑用 **Maven 多模块 + DDD 分层** 来做一次架构守护——让编译器替你把关，而不是靠 Code Review 靠人力。

---

*Langur 项目地址：[https://github.com/Jashinck/Langur](https://github.com/Jashinck/Langur)*  
*欢迎 Star ⭐ 和 Issue 交流！*
