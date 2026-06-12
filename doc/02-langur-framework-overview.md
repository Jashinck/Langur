# Langur 框架全面能力介绍

> **叶猴（Langur）**——轻盈而敏捷的 Agent 框架  
> 基于 DDD 六边形架构，支持 ReAct 推理循环、工具调用、多步规划与多 Agent 协作

---

## 目录

1. [工程背景与定位](#一工程背景与定位)
2. [核心架构设计](#二核心架构设计)
3. [模块详细拆解](#三模块详细拆解)
   - [langur-common 公共基础层](#31-langur-common--公共基础层)
   - [langur-domain 核心领域层](#32-langur-domain--核心领域层)
   - [langur-application 应用编排层](#33-langur-application--应用编排层)
   - [langur-infrastructure 基础设施层](#34-langur-infrastructure--基础设施层)
   - [langur-api 接口暴露层](#35-langur-api--接口暴露层)
   - [langur-start 启动装配层](#36-langur-start--启动装配层)
4. [关键设计模式](#四关键设计模式)
5. [核心执行流程](#五核心执行流程)
6. [扩展能力全景](#六扩展能力全景)
7. [API 接口速览](#七api-接口速览)
8. [后续演进规划](#八后续演进规划)

---

## 一、工程背景与定位

### 1.1 项目起源

Langur 是 [Skylark](https://github.com/Jashinck/Skylark) 生态的核心 Agent 能力框架，与 [BlueWhale 记忆框架](https://github.com/Jashinck/BlueWhale) 配套使用。Skylark 是一套实时 AI 语音对话系统，而 Langur 专注于其中最核心的 **Agent 推理与执行**环节，将复杂的 Agent 编排能力从业务代码中剥离，形成可复用、可扩展的通用框架。

```
Skylark 生态
├── Skylark     — 实时 AI 语音对话系统（端到端对话引擎）
├── Langur      — 通用 Agent 能力框架（推理 + 工具 + 规划）
└── BlueWhale   — 记忆框架（长期记忆 + 上下文管理）
```

### 1.2 命名寓意

叶猴（Langur）是一种轻盈、敏捷的灵长类动物，擅长在复杂环境中快速决策与行动——与 Agent 框架"感知-推理-执行"的核心循环高度契合。

### 1.3 核心能力定位

| 能力维度 | 说明 |
|---------|------|
| 🔄 **ReAct 循环** | Reasoning（推理）+ Acting（行动），最大迭代次数可配置，杜绝无限循环 |
| 🛠️ **工具调用** | 内置 HTTP 工具，支持自定义工具注册，Tools 与 Agent 解耦 |
| 📋 **多步规划** | PlanningDomainService 将复杂任务拆解为可追踪的步骤序列 |
| 🧾 **Plan 持久化** | 每次执行生成可查询的执行计划，支持调试与审计 |
| ⚙️ **异步执行** | 支持异步任务提交、状态轮询、按 Agent 维度查询任务列表 |
| 🤝 **多 Agent 协作** | AgentId 隔离机制，支持跨 Agent 任务分发与状态管理 |
| 📡 **REST API** | 标准 HTTP 接口，便于集成任意前端或上游系统 |
| 🧩 **结构化消息** | 支持 `messageParts`（text/image/audio/video/file/structured-data），为多模态演进预留入口 |

### 1.4 技术栈

- **语言**：Java 17
- **框架**：Spring Boot 3.2
- **构建**：Maven 多模块工程（6 个模块）
- **架构范式**：DDD（领域驱动设计）+ 六边形架构（Hexagonal Architecture）
- **HTTP 客户端**：Spring WebClient（响应式）
- **序列化**：Jackson ObjectMapper

### 1.5 设计文档与工程审计

从本次迭代开始，**技术设计文档与代码变更同步提交作为 PR 的组成部分**，存放在 `/design` 目录下。与既有的 `/doc`（技术分享与总结）分开：

| 目录 | 用途 | 内容类型 |
|------|------|---------|
| `/doc` | 技术分享与总结 | 框架介绍、设计方案回顾、最佳实践 |
| `/design` | PR 对应的设计文档 | 需求背景、方案对比、影响范围、验证策略 |

**设计文档规范**：每份 PR 设计文档应包含以下核心信息：

1. **背景与目标**：需求来源、解决的问题、预期收益
2. **方案对比**：备选方案分析、为什么选择最终方案
3. **影响范围**：涉及的模块、API 变化、性能影响
4. **验证策略**：如何测试、如何验证、风险评估

这种实践让关键决策可追溯，便于后续复盘与知识沉淀。

---

## 二、核心架构设计

### 2.1 整体架构分层

Langur 采用 **DDD 六边形架构**，将系统清晰拆分为 6 层，每层职责单一、依赖方向严格向内：

```
┌──────────────────────────────────────────────────────────────┐
│                        langur-api                            │
│          REST 接口层 · Controller · DTO · Assembler           │
└────────────────────────┬─────────────────────────────────────┘
                         │ 调用
┌────────────────────────▼─────────────────────────────────────┐
│                    langur-application                        │
│         应用编排层 · Use Case · Command · Assembler           │
└────────────────────────┬─────────────────────────────────────┘
                         │ 调用
┌────────────────────────▼─────────────────────────────────────┐
│                     langur-domain                            │
│   核心领域层 · 聚合根 · 领域服务 · 仓储接口 · 领域事件 · Port │
└────────────────────────┬─────────────────────────────────────┘
                         │ 实现（反向依赖）
┌────────────────────────▼─────────────────────────────────────┐
│                  langur-infrastructure                       │
│    基础设施层 · LLM 适配器 · 内置工具 · JPA/内存持久化        │
└──────────────────────────────────────────────────────────────┘

（横向贯穿各层）
┌──────────────────────────────────────────────────────────────┐
│                     langur-common                            │
│              公共基础 · 异常体系 · 基础类型                   │
└──────────────────────────────────────────────────────────────┘

（应用入口）
┌──────────────────────────────────────────────────────────────┐
│                      langur-start                            │
│              Spring Boot 启动器 · 配置装配                    │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 依赖关系图

```
langur-start
    ├── langur-api
    │     └── langur-application
    │           └── langur-domain
    │                 └── langur-common
    └── langur-infrastructure
          └── langur-domain
                └── langur-common
```

> **核心设计原则**：domain 层不依赖任何外部框架（零 Spring 依赖），基础设施层实现 domain 层定义的接口，依赖永远指向领域核心。

### 2.3 六边形架构中的端口与适配器

```
外部系统（HTTP 客户端、OpenAI API 等）
         ↕  
   [ 适配器 Adapter ]          ← infrastructure 层实现
         ↕  
   [ 端口 Port / Interface ]   ← domain 层定义
         ↕  
     领域核心（Domain）
         ↕  
   [ 端口 Port / Interface ]   ← domain 层定义（仓储、事件）
         ↕  
   [ 适配器 Adapter ]          ← infrastructure 层实现
         ↕  
  持久化存储（内存 / DB）
```

**内向端口（Driving Ports）**
- `AgentRepository` — Agent 聚合根持久化接口
- `PlanRepository` — Plan 持久化接口
- `AgentRunTaskRepository` — 异步任务持久化接口

**外向端口（Driven Ports）**
- `LLMPort` — LLM 调用抽象接口
- `ToolProvider` — 工具发现抽象接口

---

## 三、模块详细拆解

### 3.1 langur-common — 公共基础层

**职责**：提供跨模块共用的基础设施，包含异常体系和公共类型，所有模块均可依赖。

#### 异常体系

```
LangurException（基础运行时异常）
    ├── AgentNotFoundException     — Agent 不存在（404）
    ├── PlanNotFoundException      — 执行计划不存在（404）
    └── ToolNotFoundException      — 工具未注册（404）
```

**设计亮点**：自定义异常体系从根部统一封装，便于全局异常处理器（`@ControllerAdvice`）按类型精确拦截，返回标准化错误响应。

---

### 3.2 langur-domain — 核心领域层

**职责**：承载所有业务规则与领域逻辑，不依赖任何框架，是整个系统的心脏。

#### 3.2.1 领域模型（model）

##### Agent 聚合根

```java
// 包路径：domain.model.agent
Agent {
    AgentId id                           // 值对象，UUID 封装
    String name                          // Agent 名称
    String description                   // Agent 描述
    AgentConfig config                   // 配置值对象
    AgentStatus status                   // 状态枚举
    List<Tool> tools                     // 工具列表
    List<Map<String,String>> conversationHistory  // 对话历史
    int currentIteration                 // 当前迭代次数
    String lastError                     // 最后一次错误信息
    Instant createdAt / updatedAt        // 时间戳
}
```

**核心行为方法**：

| 方法 | 说明 |
|------|------|
| `Agent.create(config, tools)` | 工厂方法，创建新 Agent |
| `Agent.restore(...)` | 工厂方法，从存储恢复 Agent |
| `agent.markRunning()` | 状态流转 → RUNNING |
| `agent.markCompleted(answer)` | 状态流转 → COMPLETED，记录最终答案 |
| `agent.markFailed(reason)` | 状态流转 → FAILED，记录错误原因 |
| `agent.incrementIteration()` | 迭代计数 +1 |
| `agent.hasExceededMaxIterations()` | 判断是否超过最大迭代限制 |
| `agent.addConversationMessage(role, content)` | 追加对话历史 |
| `agent.getTools()` | 获取已注册工具列表 |

**AgentConfig 值对象**：

```java
AgentConfig {
    String systemPrompt      // 系统提示词
    String model             // 模型名称（如 gpt-4o）
    double temperature       // 采样温度
    int maxTokens            // 最大 token 数
    int maxIterations        // ReAct 最大迭代次数
}
```

**AgentStatus 枚举**：`IDLE → RUNNING → COMPLETED / FAILED`

##### Plan 与 PlanStep 模型

```java
// 包路径：domain.model.plan
Plan {
    String planId            // Plan ID
    AgentId agentId          // 归属 Agent
    String goal              // 规划目标
    List<PlanStep> steps     // 步骤列表
    Instant createdAt        // 创建时间
}

PlanStep {
    int stepIndex            // 步骤序号
    String description       // 步骤描述
    StepStatus status        // 步骤状态
    String observation       // 执行观测结果
    String failureReason     // 失败原因
}
```

**StepStatus 枚举**：`PENDING → IN_PROGRESS → COMPLETED / FAILED`

**PlanStep 不可变更新方法**：
- `step.withObservation(observation)` — 返回携带观测结果的新实例
- `step.withFailure(reason)` — 返回携带失败原因的新实例

##### AgentRunTask 异步任务模型

```java
// 包路径：domain.model.execution
AgentRunTask {
    String taskId            // 任务 ID（UUID）
    AgentId agentId          // 归属 Agent
    String userMessage       // 用户输入
    RunTaskStatus status     // 任务状态
    String result            // 最终结果
    String lastError         // 错误信息
    Instant createdAt / updatedAt
}
```

**RunTaskStatus 枚举**：`PENDING → RUNNING → COMPLETED / FAILED`

##### Tool 抽象模型

```java
// 包路径：domain.model.tool
abstract Tool {
    ToolDefinition definition    // 工具定义（抽象描述）
    abstract ToolResult execute(Map<String, Object> parameters)
}

ToolDefinition {
    String name              // 工具名称（唯一标识）
    String description       // 工具描述（LLM 感知）
    Map<String, Object> parametersSchema  // JSON Schema 参数描述
}

ToolResult {
    boolean success          // 是否成功
    String content           // 结果内容
    String errorMessage      // 错误信息（失败时）
    // 工厂方法
    static success(content)
    static failure(errorMessage)
}
```

##### MessagePartType 多模态消息类型

```java
// 包路径：domain.model.message
enum MessagePartType {
    TEXT, IMAGE, AUDIO, VIDEO, FILE, STRUCTURED_DATA
}
```

> 多模态消息类型枚举为未来图像、语音、视频输入的扩展预留了完整的类型空间。

#### 3.2.2 领域服务（service）

##### AgentDomainService — ReAct 核心调度

**职责**：封装单步 ReAct 执行逻辑（Reason → Act → Observe），是整个 Agent 推理循环的核心。

```java
AgentDomainService {
    // 执行单步 ReAct：调用 LLM → 解析决策 → 执行工具 → 更新计划
    String executeReActStep(Agent agent, Plan plan)
    
    // 发布领域事件
    void publishAgentCreatedEvent(Agent agent)
    void publishAgentExecutedEvent(Agent agent, String result)
}
```

**ReAct 单步执行内部流程**：

```
1. agent.incrementIteration()
2. LLMPort.decide(systemPrompt, conversationHistory, availableTools)
3. 解析 LLMDecision：
   ├── isFinalAnswer=true → agent.markCompleted(answer), return answer
   └── isFinalAnswer=false（工具调用）
         ├── 从 agent.tools 中查找目标工具
         ├── tool.execute(parameters) → ToolResult
         ├── 更新 PlanStep（withObservation 或 withFailure）
         ├── agent.addConversationMessage("tool", observation)
         └── return null（继续循环）
```

##### PlanningDomainService — 任务拆解规划

**职责**：基于 LLM 的完成能力（completion），将自然语言目标拆解为结构化步骤序列。

```java
PlanningDomainService {
    // 调用 LLM 将 goal 拆解为多步计划
    Plan createPlan(Agent agent, String goal)
}
```

#### 3.2.3 仓储接口（repository）

```java
AgentRepository {
    void save(Agent agent)
    Optional<Agent> findById(AgentId id)
    List<Agent> findAll()
    void delete(AgentId id)
}

PlanRepository {
    void save(Plan plan)
    Optional<Plan> findByAgentId(AgentId agentId)
}

AgentRunTaskRepository {
    void save(AgentRunTask task)
    Optional<AgentRunTask> findById(String taskId)
    List<AgentRunTask> findByAgentId(AgentId agentId)
}
```

#### 3.2.4 LLM 端口（port）

```java
// 包路径：domain.port（或 infrastructure.llm）
interface LLMPort {
    // ReAct 决策：返回工具调用指令或最终答案
    LLMDecision decide(String systemPrompt, 
                       List<Map<String,String>> conversationHistory,
                       List<Tool> availableTools)
    
    // 纯文本完成：用于规划拆解等场景
    String complete(String systemPrompt, String userMessage)
}
```

**LLMDecision 值对象**：

```java
LLMDecision {
    boolean isFinalAnswer    // true=最终答案，false=工具调用
    String answer            // 最终答案（isFinalAnswer=true 时有效）
    String toolName          // 工具名称（isFinalAnswer=false 时有效）
    Map<String,Object> toolParameters  // 工具参数
    
    // 工厂方法
    static finalAnswer(String answer)
    static toolCall(String toolName, Map<String,Object> params)
}
```

#### 3.2.5 领域事件（event）

```java
AgentCreatedEvent {
    AgentId agentId
    String agentName
    Instant occurredAt
}

AgentExecutedEvent {
    AgentId agentId
    String result
    int iterationsUsed
    Instant occurredAt
}
```

---

### 3.3 langur-application — 应用编排层

**职责**：编排领域服务，实现完整业务用例（Use Case），不含任何领域规则，仅做流程调度。

#### 3.3.1 应用服务

##### AgentApplicationService — 核心用例服务

```java
AgentApplicationService {
    // 用例1：创建 Agent
    AgentResult createAgent(CreateAgentCommand command)
    
    // 用例2：同步执行 Agent（ReAct 完整循环）
    AgentResult runAgent(RunAgentCommand command)
    
    // 用例3：查询 Agent 详情
    AgentResult getAgent(String agentId)
    
    // 用例4：查询所有 Agent
    List<AgentResult> listAgents()
    
    // 用例5：查询最近一次执行计划
    Optional<PlanResult> getLatestPlan(String agentId)
    
    // 用例6：删除 Agent
    void deleteAgent(String agentId)
}
```

**runAgent 完整 ReAct 循环实现**：

```java
// 伪代码
while (finalAnswer == null && !agent.hasExceededMaxIterations()) {
    finalAnswer = agentDomainService.executeReActStep(agent, plan);
}
if (finalAnswer == null) agent.markFailed("超过最大迭代次数");
planRepository.save(plan);
agentRepository.save(agent);
```

##### AgentRunTaskApplicationService — 异步任务管理

```java
AgentRunTaskApplicationService {
    // 提交异步执行任务，立即返回 taskId
    RunTaskResult startAsyncRun(RunAgentCommand command)
    
    // 查询任务状态
    RunTaskResult getTaskStatus(String taskId)
    
    // 查询 Agent 下所有任务
    List<RunTaskResult> listTasksByAgent(String agentId)
}
```

**异步执行内部机制**：
- 使用 `ExecutorService`（默认 4 线程池）提交任务
- 任务状态流转：`PENDING → RUNNING → COMPLETED/FAILED`
- 支持 `graceful shutdown`（超时等待线程池结束）

##### ToolRegistryService — 工具注册管理

```java
ToolRegistryService {
    // 根据工具名称列表获取 Tool 对象
    List<Tool> getToolsByNames(List<String> toolNames)
    
    // 获取所有已注册工具
    List<Tool> getAllTools()
}
```

#### 3.3.2 命令对象（Command）

```java
CreateAgentCommand {
    String name
    String description
    String systemPrompt
    String model
    double temperature
    int maxTokens
    int maxIterations
    List<String> toolNames   // 按名称指定工具
}

RunAgentCommand {
    String agentId
    String userMessage                   // 简单文本输入
    String userId / tenantId / sessionId // 多租户上下文
    List<MessagePartInput> messageParts  // 多模态结构化输入
}

MessagePartInput {
    MessagePartType type
    String content       // TEXT 类型内容
    String mediaUrl      // 媒体类型 URL
    Object data          // STRUCTURED_DATA 内容
}
```

#### 3.3.3 结果 DTO（Result）

```java
AgentResult { id, name, description, status, config, lastError, createdAt, updatedAt }
PlanResult  { planId, agentId, goal, steps: List<PlanStepResult> }
PlanStepResult { stepIndex, description, status, observation, failureReason }
RunTaskResult { taskId, agentId, status, result, lastError, createdAt, updatedAt }
```

#### 3.3.4 装配器（Assembler）

```java
AgentAssembler {
    AgentResult toResult(Agent agent)     // Domain → App DTO
}
```

---

### 3.4 langur-infrastructure — 基础设施层

**职责**：实现 domain 层定义的所有接口（Port/Repository），对接外部系统（OpenAI）和存储（内存）。

#### 3.4.1 LLM 适配器

##### OpenAILLMAdapter

```java
// 实现 LLMPort，对接 OpenAI Chat Completions API
OpenAILLMAdapter {
    // 配置项（通过 application.yml 注入）
    String baseUrl        // API Base URL
    String apiKey         // API Key
    String defaultModel   // 默认模型
    WebClient webClient   // 响应式 HTTP 客户端
    
    // decide()：将 Agent 的对话历史 + 工具列表转换为 OpenAI tool_calls 格式
    //           解析响应：function_call → toolCall，content → finalAnswer
    LLMDecision decide(...)
    
    // complete()：直接调用 Chat Completions（无工具），用于规划拆解
    String complete(...)
}
```

**OpenAI 工具调用格式映射**：

```
Domain Tool                    →   OpenAI Tool Format
─────────────────────────────────────────────────────
ToolDefinition.name            →   function.name
ToolDefinition.description     →   function.description
ToolDefinition.parametersSchema →  function.parameters (JSON Schema)
```

#### 3.4.2 内置工具

##### HttpCallTool — HTTP 请求工具

```java
HttpCallTool extends Tool {
    // 工具名称：http_call
    // 描述：执行 HTTP 请求，支持 GET/POST
    
    // 参数 Schema：
    //   url (string, required)     目标 URL
    //   method (string)            HTTP 方法，默认 GET
    //   body (object)              请求体（POST 时使用）
    //   headers (object)           自定义请求头
    
    ToolResult execute(Map<String, Object> parameters)
    // 使用 WebClient 发送请求，返回响应体字符串
    // 失败时返回 ToolResult.failure(errorMessage)
}
```

##### BuiltinToolRegistry — 内置工具注册表

```java
BuiltinToolRegistry implements ToolProvider {
    // 使用 ConcurrentHashMap 存储已注册工具
    // 自动注册 HttpCallTool
    
    List<Tool> getTools()                  // 获取所有内置工具
    Optional<Tool> findByName(String name) // 按名称查找工具
}
```

#### 3.4.3 持久化实现

Langur 提供两种持久化实现，通过 Repository 接口完全解耦，支持灵活切换。

##### 3.4.3.1 内存实现

三个仓储实现均使用 `ConcurrentHashMap` 保证线程安全，适合开发测试场景：

```java
// 按 AgentId 存储 Agent 对象
InMemoryAgentRepository implements AgentRepository {
    ConcurrentHashMap<String, Agent> store
}

// 按 AgentId 存储最新 Plan（一个 Agent 保存最近一次计划）
InMemoryPlanRepository implements PlanRepository {
    ConcurrentHashMap<String, Plan> store
}

// 按 taskId 存储 AgentRunTask，同时维护 agentId → taskIds 的索引
InMemoryAgentRunTaskRepository implements AgentRunTaskRepository {
    ConcurrentHashMap<String, AgentRunTask> taskStore
    ConcurrentHashMap<String, List<String>> agentTaskIndex
}
```

> **特点**：快速启动、零依赖，但服务重启数据丢失。

##### 3.4.3.2 JPA 实现

通过 Spring Data JPA 实现，支持 MySQL/PostgreSQL 等关系型数据库，生产级持久化能力：

```java
// JPA 实体类（自动ORM映射）
AgentDO (id, agentName, createdTime, updatedTime, conversationHistory, iterationCount, status, configuration)
PlanDO (id, agentId, createTime, finishTime, planSteps)
AgentRunTaskDO (id, agentId, status, error, result, createdTime, updatedTime)

// JPA 仓储接口（Spring Data 自动实现）
AgentJpaRepository extends JpaRepository<AgentDO, String>
PlanJpaRepository extends JpaRepository<PlanDO, String> with custom findByAgentId()
AgentRunTaskJpaRepository extends JpaRepository<AgentRunTaskDO, String> with custom findByAgentId()

// 适配器层（自动进行 DO ↔ Domain 转换）
JpaAgentRepository implements AgentRepository {
    // 自动映射 Agent ↔ AgentDO
    AgentEntity.from(agent)  // Domain → DO
    agent.restore(agentDO)   // DO → Domain
}
```

> **特点**：
> - 支持 MySQL、PostgreSQL、H2 等数据库
> - 自动化事务管理，ACID 保证
> - 数据库升级与迁移通过 Liquibase/Flyway
> - 支持查询优化与索引管理
> - 线程安全保障通过 JPA 框架

> **线程安全保障**：内存实现使用 `ConcurrentHashMap`，`AgentRunTask` 的异步更新通过仓储接口统一修改。JPA 实现通过数据库事务隔离级别保证并发安全。

---

### 3.5 langur-api — 接口暴露层

**职责**：将应用层能力以 RESTful HTTP 接口形式暴露，处理 HTTP 协议细节，与应用层通过 DTO 解耦。

#### 3.5.1 AgentController — 核心控制器

```java
@RestController
@RequestMapping("/api/agents")
AgentController {
    POST   /                        createAgent(CreateAgentRequest)
    GET    /                        listAgents()
    GET    /{agentId}               getAgent(agentId)
    DELETE /{agentId}               deleteAgent(agentId)
    POST   /{agentId}/run           runAgent(agentId, RunAgentRequest)
    POST   /{agentId}/run/async     runAgentAsync(agentId, RunAgentRequest)
    GET    /{agentId}/plan          getLatestPlan(agentId)
    GET    /{agentId}/runs          listAgentTasks(agentId)
    GET    /runs/{taskId}           getTaskStatus(taskId)
}
```

#### 3.5.2 请求/响应 DTO

```java
// 请求 DTO
CreateAgentRequest { name, description, systemPrompt, model, temperature, maxTokens, maxIterations, toolNames }
RunAgentRequest    { userMessage, userId, tenantId, sessionId, messageParts: List<MessagePartRequest> }
MessagePartRequest { type, content, mediaUrl, data }

// 响应 DTO
AgentResponse    { id, name, description, status, config, lastError, createdAt, updatedAt }
PlanResponse     { planId, agentId, goal, steps: List<PlanStepResponse> }
PlanStepResponse { stepIndex, description, status, observation, failureReason }
RunTaskResponse  { taskId, agentId, status, result, lastError, createdAt, updatedAt }
```

#### 3.5.3 AgentApiAssembler — API 装配器

```java
AgentApiAssembler {
    AgentResponse   toResponse(AgentResult result)
    PlanResponse    toPlanResponse(PlanResult result)
    RunTaskResponse toRunTaskResponse(RunTaskResult result)
    
    CreateAgentCommand toCommand(CreateAgentRequest request)
    RunAgentCommand    toCommand(String agentId, RunAgentRequest request)
}
```

**双层 Assembler 设计**：

```
API Layer:  Request  →  AgentApiAssembler  →  Command
App Layer:  Domain   →  AgentAssembler     →  Result
API Layer:  Result   →  AgentApiAssembler  →  Response
```

这种双层转换彻底隔离了 HTTP 协议变化（API 层）和业务模型变化（Domain 层），每层只感知相邻层的 DTO。

---

### 3.6 langur-start — 启动装配层

**职责**：Spring Boot 应用启动入口，统一装配所有模块的 Bean。

#### 关键配置项（application.yml）

```yaml
server:
  port: 8081                        # 服务端口

langur:
  llm:
    base-url: https://api.openai.com  # LLM API 地址（可替换）
    api-key: ${LANGUR_LLM_API_KEY}    # API Key（从环境变量注入）
    model: gpt-4o                     # 默认模型
  run-task:
    executor-size: 4                  # 异步任务线程池大小
```

#### 启动命令

```bash
# 构建
mvn clean package -DskipTests

# 运行（需设置 LLM API Key）
export LANGUR_LLM_API_KEY=your_key
java -jar langur-start/target/langur.jar
```

---

## 四、关键设计模式

### 4.1 六边形架构（端口与适配器）

Domain 层只定义 **Port 接口**（LLMPort、ToolProvider、Repository），Infrastructure 层实现这些接口作为 **Adapter**。Domain 层对外部系统（LLM API、数据库）一无所知，完全可测试、可替换。

### 4.2 聚合根模式（Aggregate Root）

`Agent` 是整个框架的核心聚合根，所有与 Agent 相关的状态变更（运行状态、对话历史、迭代计数）都通过 Agent 实体的方法进行，外部不直接修改 Agent 的内部状态。

### 4.3 工厂方法模式（Factory Method）

```java
Agent.create(config, tools)    // 创建新 Agent
Agent.restore(...)             // 从存储恢复
LLMDecision.finalAnswer(...)   // 创建最终答案决策
LLMDecision.toolCall(...)      // 创建工具调用决策
ToolResult.success(...)        // 创建成功结果
ToolResult.failure(...)        // 创建失败结果
```

工厂方法让对象创建语义更清晰，并封装了内部构建细节。

### 4.4 策略模式（Strategy）

- **LLM 策略**：只需实现 `LLMPort` 即可无缝替换 OpenAI → DeepSeek → Qwen 等任意 LLM
- **工具策略**：只需实现 `ToolProvider` 即可无缝扩展工具集

### 4.5 命令模式（Command Pattern）

所有应用层入口通过 `Command` 对象封装请求（`CreateAgentCommand`、`RunAgentCommand`），实现输入验证、审计日志和未来命令总线扩展的能力。

### 4.6 不可变值对象（Immutable Value Object）

`PlanStep` 的状态更新通过 `withObservation()`、`withFailure()` 返回新实例，天然线程安全，避免共享可变状态。

### 4.7 仓储模式（Repository Pattern）

仓储接口定义在 Domain 层，实现在 Infrastructure 层。当前为内存实现，未来可无缝切换为 JPA/MongoDB 实现，Domain 层代码零修改。

### 4.8 工程审计与决策追溯

设计文档与代码变更同步提交在同一 PR 中，确保：

- **决策可追溯**：每次重要变更都伴随设计文档，记录 Why（为什么）而非仅记录 What（做了什么）
- **复盘高效**：后续维护者通过 PR 历史即可理解演进脉络，降低上手成本
- **知识沉淀**：设计文档进入 `/design` 目录，积累成团队知识库

---

## 五、核心执行流程

### 5.1 同步执行完整流程（ReAct 循环）

```
POST /api/agents/{agentId}/run
        │
        ▼
AgentController.runAgent()
        │
        ▼
AgentApiAssembler.toCommand()  →  RunAgentCommand
        │
        ▼
AgentApplicationService.runAgent()
   ├── AgentRepository.findById()  →  Agent
   ├── agent.markRunning()
   ├── PlanningDomainService.createPlan()  →  Plan
   │      └── LLMPort.complete(goal)  →  解析步骤 → Plan
   │
   └── ReAct 循环（while !finalAnswer && !maxIterations）
          │
          ▼
      AgentDomainService.executeReActStep(agent, plan)
          ├── agent.incrementIteration()
          ├── LLMPort.decide(systemPrompt, history, tools)
          │      └── OpenAI Chat Completions API
          │
          ├── [最终答案] → agent.markCompleted(answer) → return answer
          │
          └── [工具调用]
                 ├── 查找 Tool → tool.execute(params) → ToolResult
                 ├── plan.step.withObservation(result)
                 ├── agent.addConversationMessage("tool", result)
                 └── return null（继续循环）
        │
        ▼
   PlanRepository.save(plan)
   AgentRepository.save(agent)
        │
        ▼
AgentAssembler.toResult() → AgentResult
AgentApiAssembler.toResponse() → AgentResponse (200 OK)
```

### 5.2 异步执行流程

```
POST /api/agents/{agentId}/run/async
        │
        ▼
AgentRunTaskApplicationService.startAsyncRun()
   ├── AgentRunTask.create()  →  status=PENDING
   ├── AgentRunTaskRepository.save(task)
   └── ExecutorService.submit(task)
        │  立即返回 202 Accepted + taskId
        │
        ▼ [线程池中异步执行]
   task.markRunning()
   AgentApplicationService.runAgent()  [同同步流程]
   task.markCompleted(result) 或 task.markFailed(error)
   AgentRunTaskRepository.save(task)
        │
        ▼ [客户端轮询]
GET /api/agents/runs/{taskId}
   AgentRunTaskRepository.findById(taskId)  →  RunTaskResponse
```

---

## 六、扩展能力全景

### 6.1 接入自定义 LLM 与多模型支持

Langur 内置支持多种 LLM 提供商，通过实现 `LLMPort` 接口可轻松扩展任意 LLM。

#### 6.1.1 内置 LLM 适配器

| 提供商 | 适配器类 | 特点 | 模型示例 |
|------|---------|------|---------|
| **OpenAI** | `OpenAILLMAdapter` | 业界标准，功能完整 | GPT-4o, GPT-4, GPT-3.5 |
| **Claude** | `ClaudeLLMAdapter` | 推理能力强，上下文长 | Claude 3 Opus/Sonnet/Haiku |
| **Gemini** | `GeminiLLMAdapter` | 多模态支持，性能优异 | Gemini Pro, Gemini Pro Vision |
| **DeepSeek** | `DeepSeekLLMAdapter` | 兼容 OpenAI 协议，国内优化 | DeepSeek LLM |
| **Qwen** | `QwenLLMAdapter` | 阿里云通义千问，中文优化 | Qwen-Max, Qwen-Plus |

#### 6.1.2 配置与切换

```yaml
# application.yml
langur:
  llm:
    default: openai           # 默认 LLM 提供商
    providers:
      openai:
        enabled: true
        api-key: ${OPENAI_API_KEY}
        base-url: https://api.openai.com/v1
        model: gpt-4o
      claude:
        enabled: true
        api-key: ${CLAUDE_API_KEY}
        model: claude-3-opus-20240229
      gemini:
        enabled: true
        api-key: ${GEMINI_API_KEY}
        model: gemini-pro
      deepseek:
        enabled: false
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com/v1
      qwen:
        enabled: false
        api-key: ${QWEN_API_KEY}
```

#### 6.1.3 LLM 路由与动态选择

通过 `LLMRouter` 和 `ModelRoutableLLMPort` 支持按任务自动选择合适的 LLM：

```java
// LLMRouter：统一的 LLM 路由管理器
LLMRouter {
    LLMPort getLLMForTask(String agentId, String taskType)  // 按任务类型路由
    LLMPort getDefaultLLM()                                  // 获取默认 LLM
    Map<String, LLMPort> getAllAvailableLLMs()               // 列举所有可用 LLM
}

// ModelRoutableLLMPort：支持模型路由的接口
ModelRoutableLLMPort extends LLMPort {
    String getModelName()      // 获取模型名称
    String getProviderName()   // 获取提供商名称
}
```

**应用场景示例**：

```java
// 在 Agent 初始化时配置模型路由策略
Agent agent = Agent.create(config, tools);
agent.setModelRoutingStrategy(new ModelRoutingStrategy() {
    @Override
    public LLMPort selectModel(String taskType) {
        return switch(taskType) {
            case "reasoning" -> llmRouter.getLLM("claude");        // 推理用 Claude
            case "coding" -> llmRouter.getLLM("openai");           // 编程用 GPT-4
            case "chinese_understanding" -> llmRouter.getLLM("qwen"); // 中文用通义千问
            case "multimodal" -> llmRouter.getLLM("gemini");       // 多模态用 Gemini
            default -> llmRouter.getDefaultLLM();
        };
    }
});
```

#### 6.1.4 自定义 LLM 适配器

实现 `ModelRoutableLLMPort` 接口，标注 `@Component` 即可无缝集成：

```java
@Component
public class CustomLLMAdapter implements ModelRoutableLLMPort {
    @Override
    public LLMDecision decide(String systemPrompt,
                              List<Map<String, String>> history,
                              List<Tool> tools) {
        // 调用你的 LLM API
        // 返回 LLMDecision（最终答案或工具调用）
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        // 调用你的 LLM API 的完成接口
    }

    @Override
    public String getModelName() {
        return "custom-model-v1";
    }

    @Override
    public String getProviderName() {
        return "custom-provider";
    }
}
```

#### 6.1.5 LLM 路由实现机制

```java
@Component
public class LLMRouter {
    @Autowired
    private Map<String, ModelRoutableLLMPort> llmAdapters;  // 自动注入所有 LLM 适配器
    
    @Autowired
    private LlmProperties config;  // 从配置文件读取默认和启用的 LLM

    public LLMPort getLLMForTask(String agentId, String taskType) {
        // 优先按任务类型查询，找不到则使用默认
        ModelRoutableLLMPort llm = llmAdapters.get(taskType);
        return llm != null ? llm : getDefaultLLM();
    }

    public LLMPort getDefaultLLM() {
        String defaultProvider = config.getDefault();  // 从配置读取
        return llmAdapters.get(defaultProvider);
    }

    public Map<String, LLMPort> getAllAvailableLLMs() {
        return llmAdapters.entrySet().stream()
                .filter(e -> isEnabled(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean isEnabled(String provider) {
        return config.getProviders().get(provider).isEnabled();
    }
}
```

### 6.2 注册自定义工具

继承 `Tool` 抽象类，在 `BuiltinToolRegistry` 中注册：

```java
public class WeatherTool extends Tool {
    public WeatherTool() {
        super(ToolDefinition.of(
            "weather_query",
            "查询指定城市的天气信息",
            Map.of(
                "city", Map.of("type", "string", "description", "城市名称")
            )
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String city = (String) parameters.get("city");
        // 调用天气 API
        return ToolResult.success("北京今天晴，25°C");
    }
}
```

### 6.3 性能优化——批量工具再水合

#### 问题背景

在 Agent 恢复时，需要根据保存的工具名称列表重新加载对应的 Tool 对象。之前的实现在循环中逐个查询工具注册表，导致 O(n*m) 的时间复杂度（n 个工具名，m 次查询）。

#### 优化方案

引入**批量再水合**机制，在单次调用中一次性加载所有工具：

```java
// 优化前：O(n*m) 复杂度
List<Tool> tools = new ArrayList<>();
for (String toolName : toolNames) {
    tools.add(toolRegistry.findByName(toolName));  // 每次都遍历一次
}

// 优化后：O(n+m) 复杂度
List<Tool> tools = toolRegistry.getToolsByNames(toolNames);
// 内部实现：先构建工具名 → Tool 的 HashMap（O(m)），然后批量查询（O(n)）

public List<Tool> getToolsByNames(List<String> toolNames) {
    // 先遍历仓储一次，构建 name → Tool 的 Map
    Map<String, Tool> toolMap = new HashMap<>();
    for (Tool tool : getAllTools()) {
        toolMap.put(tool.getName(), tool);
    }
    
    // 然后按照 toolNames 的顺序批量取值
    return toolNames.stream()
        .map(toolMap::get)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}
```

#### 性能收益

- **Agent 创建**：工具列表越多，收益越明显（10 个工具约快 2-3 倍）
- **Agent 恢复**：从持久化存储恢复时立即生效
- **内存占用**：无额外内存开销，仅优化查询顺序

### 6.4 数据库持久化集成

#### 6.4.1 从内存迁移到 JPA 数据库实现

实现仓储接口，替换内存实现为数据库实现，**无需修改 Domain 层代码**：

```java
// 方式一：通过 @Primary 注解自动切换
@Repository
@Primary  // 覆盖内存实现，Spring 优先注入此实现
public class JpaAgentRepository implements AgentRepository {
    @Autowired
    private AgentJpaRepository agentDataRepository;  // Spring Data JPA 自动实现的 CRUD repository

    @Override
    public void save(Agent agent) {
        // 自动将 Domain 对象转换为数据库实体
        AgentDO agentDO = AgentDO.from(agent);
        agentDataRepository.save(agentDO);
    }

    @Override
    public Optional<Agent> findById(String id) {
        return agentDataRepository.findById(id)
                .map(AgentDO::toDomain);  // DO → Domain
    }
}
```

#### 6.4.2 数据库配置

在 `application.yml` 中配置数据库连接：

```yaml
spring:
  datasource:
    # MySQL 示例
    url: jdbc:mysql://localhost:3306/langur?useSSL=false&serverTimezone=UTC
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
    # 或 PostgreSQL
    # url: jdbc:postgresql://localhost:5432/langur
    # driver-class-name: org.postgresql.Driver
  
  jpa:
    hibernate:
      ddl-auto: validate  # 生产环境使用 validate，开发使用 create-drop
    database-platform: org.hibernate.dialect.MySQL8Dialect  # 或 PostgreSQL10Dialect
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
```

#### 6.4.3 JPA 实体设计

三个核心 JPA 实体自动处理 Domain 模型持久化：

```java
@Entity
@Table(name = "t_agent")
public class AgentDO {
    @Id
    private String id;
    private String agentName;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    
    @Convert(converter = ConversationHistoryConverter.class)
    private List<ConversationMessage> conversationHistory;  // 通过 converter 自动 JSON 序列化
    private Integer iterationCount;
    private String status;
    @Convert(converter = AgentConfigConverter.class)
    private AgentConfig configuration;  // Agent 配置信息
}

@Entity
@Table(name = "t_plan")
public class PlanDO {
    @Id
    private String id;
    @Column(nullable = false)
    private String agentId;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    
    @Convert(converter = PlanStepsConverter.class)
    private List<PlanStep> planSteps;  // 通过 converter 自动序列化步骤列表
}

@Entity
@Table(name = "t_agent_run_task", indexes = {@Index(columnList = "agentId")})
public class AgentRunTaskDO {
    @Id
    private String id;
    @Column(nullable = false)
    private String agentId;  // 通过 @Table 注解中的 indexes 定义索引
    private String status;
    private String error;
    @Column(columnDefinition = "TEXT")
    private String result;  // 支持大文本
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
```

#### 6.4.4 自动映射机制

通过 `AttributeConverter` 实现复杂对象的自动序列化/反序列化，保证 Domain 层与 DO 层的无缝转换：

```java
// 为 ConversationMessage 列表创建转换器
@Converter(autoApply = false)
public class ConversationHistoryConverter implements AttributeConverter<List<ConversationMessage>, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<ConversationMessage> attribute) {
        if (attribute == null) return null;
        try {
            // List<ConversationMessage> → JSON String
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert conversation history to JSON", e);
        }
    }

    @Override
    public List<ConversationMessage> convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            // JSON String → List<ConversationMessage>
            return objectMapper.readValue(dbData, 
                new TypeReference<List<ConversationMessage>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JSON to conversation history", e);
        }
    }
}

// 为 PlanStep 列表创建转换器（类似模式）
@Converter(autoApply = false)
public class PlanStepsConverter implements AttributeConverter<List<PlanStep>, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 实现方式同 ConversationHistoryConverter，使用 TypeReference<List<PlanStep>>
}

// 为 AgentConfig 创建转换器
@Converter(autoApply = false)
public class AgentConfigConverter implements AttributeConverter<AgentConfig, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 实现方式同上，针对单个对象类型而非列表
}
```

> **说明**：
> - 每个 `AttributeConverter` 实现必须有**无参构造方法**（JPA 规范要求）
> - `@Converter(autoApply = false)` 表示不自动应用，需在实体中显式指定 `@Convert(converter = ...)`
> - 使用 `ObjectMapper`（Jackson）实现 JSON 序列化/反序列化
> - `TypeReference` 用于处理泛型类型信息丢失的问题
```

#### 6.4.5 动态切换存储实现

通过配置文件动态选择持久化实现，无需改代码：

```yaml
langur:
  persistence:
    type: jpa  # 可选值: memory, jpa
```

```java
@Configuration
public class PersistenceConfig {
    @Bean
    @ConditionalOnProperty(name = "langur.persistence.type", havingValue = "jpa")
    public AgentRepository jpaAgentRepository(AgentJpaRepository jpaRepo) {
        return new JpaAgentRepository(jpaRepo);
    }

    @Bean
    @ConditionalOnProperty(name = "langur.persistence.type", havingValue = "memory", matchIfMissing = true)
    public AgentRepository inMemoryAgentRepository() {
        return new InMemoryAgentRepository();
    }
}
```

> **生产建议**：使用 Liquibase 或 Flyway 进行版本化的数据库迁移管理，确保数据库 Schema 变更可追踪且可回滚。

### 6.5 多模态消息扩展

`RunAgentRequest` 已支持 `messageParts` 结构化入参，当 LLM 和工具层能力就绪时，可直接在应用层处理图像、音频等多模态内容，**无需修改接口协议**。

---

## 七、API 接口速览

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| `POST` | `/api/agents` | 创建 Agent | `AgentResponse` |
| `GET` | `/api/agents` | 列出所有 Agent | `List<AgentResponse>` |
| `GET` | `/api/agents/{id}` | 获取 Agent 详情 | `AgentResponse` |
| `DELETE` | `/api/agents/{id}` | 删除 Agent | `204 No Content` |
| `POST` | `/api/agents/{id}/run` | 同步执行（阻塞直到完成） | `AgentResponse` |
| `POST` | `/api/agents/{id}/run/async` | 异步提交执行（立即返回） | `RunTaskResponse (202)` |
| `GET` | `/api/agents/{id}/plan` | 查询最近一次执行计划 | `PlanResponse` |
| `GET` | `/api/agents/{id}/runs` | 查询 Agent 的异步任务列表 | `List<RunTaskResponse>` |
| `GET` | `/api/agents/runs/{taskId}` | 查询异步任务状态 | `RunTaskResponse` |

---

## 八、后续演进规划

Langur 当前版本（v1.0）奠定了完整的架构基础，后续演进将沿以下方向推进：

### 8.1 🗄️ 持久化升级（已完成 ✅）

- **已实现**：完整的 JPA 数据库实现，支持 MySQL/PostgreSQL
- **架构**：
  - 三个 JPA 实体（AgentDO、PlanDO、AgentRunTaskDO）自动进行 ORM 映射
  - 三个对应的 JPA Repository 实现，保证 Domain 层零修改
  - 自动的 Domain ↔ DO 双向转换，透明处理序列化
  - 灵活的持久化配置，支持动态切换内存/数据库实现
- **特点**：
  - 生产级 ACID 保证，数据库事务隔离
  - 支持复杂查询优化与索引管理
  - 支持 Liquibase/Flyway 数据库版本管理
  - 内存实现仍可用于开发测试，零依赖
- **迁移**：通过 `@Primary` 注解或配置文件无缝切换，既有业务代码无需修改
- **收益**：服务重启数据不丢失，支持多实例部署与数据共享

### 8.2 🤖 多 LLM 支持（已完成 ✅）

- **已实现**：内置 OpenAI、Claude、Gemini、DeepSeek、Qwen 五大主流模型适配器
- **特点**：统一 `LLMPort` 接口，支持配置切换与模型路由
- **演进**：后续支持更多模型（Mistral、LLaMA 等），完全无缝集成

### 8.3 ⚡ 工程审计与决策追踪（已完成 ✅）

- **已实现**：技术设计文档与代码变更同步提交，存放在 `/design` 目录
- **收益**：
  - 设计决策可追溯，支持快速复盘
  - 新人易理解演进脉络
  - 积累团队知识库
- **后续**：支持基于 PR 的设计文档自动生成、检索、对标对比

### 8.4 ⏱️ 性能优化（已完成 ✅）

- **已实现**：批量 Agent 工具再水合优化（O(n*m) → O(n+m)）
- **收益**：Agent 创建与恢复性能提升 2-3 倍
- **后续**：继续优化 LLM 推理缓存、工具执行并行化

### 8.5 🧠 BlueWhale 记忆集成（中期）

- **目标**：与 BlueWhale 记忆框架深度集成，赋予 Agent 长期记忆能力
- **能力**：跨会话记忆检索（RAG）、用户画像积累、知识沉淀
- **接入方式**：新增 `MemoryPort` 接口，在 ReAct 循环前注入相关记忆上下文

### 8.6 🌊 流式输出支持（中期）

- **目标**：支持 SSE（Server-Sent Events）或 WebSocket 推送 ReAct 中间步骤
- **能力**：前端实时展示 Agent 的思考过程、工具调用详情和中间结果
- **接口**：新增 `POST /api/agents/{id}/run/stream` 流式端点

### 8.7 🔗 多 Agent 协作增强（中期）

- **目标**：支持 Agent 之间的任务委托与结果汇聚
- **能力**：主 Agent 可通过工具调用将子任务委托给专业 Agent，并聚合结果
- **方案**：增加 `AgentCallTool`，将其他 Agent 作为可调用工具

### 8.8 📊 可观测性（中期）

- **目标**：为每次 ReAct 执行提供完整的可观测链路
- **能力**：
  - 执行 Trace（每步耗时、LLM token 消耗）
  - 结构化日志（包含 agentId、taskId、迭代轮次）
  - Metrics 接入（Prometheus + Grafana）
  - 分布式链路追踪（OpenTelemetry）

### 8.9 🌐 多模态能力（远期）

- **目标**：充分利用已预留的 `MessagePartType` 枚举，支持图像/音频/视频输入
- **能力**：视觉理解工具、语音转文字工具、多模态 LLM 接入（如 GPT-4V）
- **接口**：当前 `messageParts` 接口协议已兼容，LLM 层就绪后即可打通

### 8.10 📦 SDK 化（远期）

- **目标**：将 Langur 发布为可独立引入的 Maven 包
- **能力**：其他 Spring Boot 项目通过依赖引入即可获得 Agent 能力
- **方案**：提取 `langur-sdk` 模块，提供 Spring Auto-Configuration 支持

---

## 总结

Langur 框架以 **DDD 六边形架构** 为基础，通过严格的分层设计和端口-适配器模式，构建了一个高度可扩展的 Agent 执行引擎。核心亮点在于：

1. **业务纯粹**：Domain 层零框架依赖，业务规则清晰，可独立测试
2. **扩展友好**：LLM、工具、存储均通过接口抽象，一行注解即可替换
3. **生产基础**：异步任务、状态追踪、线程安全的并发模型
4. **演进预留**：多模态消息格式、流式输出接口均已预留扩展入口

作为 Skylark 生态的 Agent 核心，Langur 将持续演进，逐步具备**记忆、流式、多 Agent 协作与完整可观测性**，成为面向生产环境的企业级 Agent 框架。

---

> 本文基于 Langur v1.0 版本撰写  
> 项目地址：[https://github.com/Jashinck/Langur](https://github.com/Jashinck/Langur)  
> 相关项目：[Skylark](https://github.com/Jashinck/Skylark) · [BlueWhale](https://github.com/Jashinck/BlueWhale)
