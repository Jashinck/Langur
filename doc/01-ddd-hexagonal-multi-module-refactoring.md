# 从“能跑”到“可运营”：Langur 最新 MR 的四阶段 Agent 能力升级

> **作者**：Jashinck  
> **标签**：AI Agent / ReAct / DDD / Spring Boot / 工程实践

---

## 这次 MR 的核心，不只是 DDD

上一版解读把重点放在了 DDD 多模块改造上，但最新 MR 的真正主线是：**Agent 能力从单次调用升级为“四阶段闭环”**，DDD 只是支撑这条主线的工程化手段。

这四个阶段分别是：

1. **输入阶段（Input Normalization）**：统一结构化消息输入
2. **规划阶段（Planning）**：把执行过程沉淀为可追踪 Plan
3. **执行阶段（Reason + Act）**：稳定 ReAct 循环并记录每一步
4. **运营阶段（Async + Observability）**：异步任务化运行与状态可观测

---

## 阶段一：输入能力升级（支持 `messageParts`）

在 `RunAgentRequest` 中，输入从单一 `userMessage` 扩展为：

- `userMessage`（纯文本）
- `messageParts`（结构化分片）

结构化类型由 `MessagePartType` 统一约束：

- `TEXT`
- `IMAGE`
- `AUDIO`
- `VIDEO`
- `FILE`
- `STRUCTURED_DATA`

应用层 `AgentApplicationService#resolveUserMessage` 负责兜底合并逻辑：

- 优先使用 `userMessage`
- 当 `userMessage` 为空时，按 `messageParts` 组装可用文本
- 无有效内容直接抛错，避免空输入进入 Agent 循环

**价值**：Agent 的输入边界从“文本参数”升级为“标准化多模态消息入口”。

---

## 阶段二：规划能力升级（Plan 持久化）

在执行前，应用层会通过 `PlanningDomainService#createPlan` 创建 Plan，并在流程结束时持久化到 `PlanRepository`。

执行过程中的每次决策都会落成 `PlanStep`：

- `thought`
- `action`
- `actionInput`
- `observation`
- `status`

并通过 API 支持查询最近一次计划：

- `GET /api/agents/{id}/plan`

**价值**：Agent 从“黑盒调用”升级为“可复盘的思维链 + 行动链”。

---

## 阶段三：执行能力升级（ReAct 闭环更完整）

核心逻辑在 `AgentDomainService#executeReActStep`：

1. 调用 `LLMPort.decide(...)` 获取决策
2. 若为最终答案，直接完成 Agent
3. 若为工具调用，记录思考与动作
4. 执行工具并回填 `observation`
5. 将 Thought/Action/ToolResult 写回会话历史

这一轮设计把关键状态全部显式化：

- Agent 状态：`IDLE/RUNNING/COMPLETED/FAILED`
- 计划步骤状态：`RUNNING/COMPLETED/FAILED/...`
- 最大迭代保护：`maxIterations`

**价值**：Agent 的 Reason-Act 循环具备了可中断、可诊断、可审计的工程属性。

---

## 阶段四：运营能力升级（异步任务化 + 可观测）

新增 `AgentRunTask` 与 `AgentRunTaskApplicationService`，把执行过程任务化：

- `POST /api/agents/{id}/run/async`：异步启动
- `GET /api/agents/runs/{taskId}`：查询任务状态
- `GET /api/agents/{id}/runs`：按 Agent 查看任务列表

任务状态生命周期：

- `PENDING -> RUNNING -> COMPLETED/FAILED/CANCELLED`

并携带 `userId/tenantId/sessionId`，便于多租户和会话级追踪。

**价值**：Agent 从“同步接口能力”升级为“可并发调度的运行单元”，为生产环境接入奠定基础。

---

## DDD 改造在这里扮演什么角色？

本次 DDD 多模块拆分（common/domain/application/api/infrastructure/start）不是目标本身，而是为四阶段能力提供稳定边界：

- Domain 专注 Agent 规则与状态机
- Application 负责编排用例与输入归一
- API 专注协议层输出
- Infrastructure 实现 LLM / Tool / Repository 适配

尤其是 `LLMPort` 回归 `domain.port`，使 Agent 核心流程不再绑定具体 LLM 供应商，保证了后续扩展能力。

---

## 对业务方最直接的收益

从业务视角看，这次 MR 带来的不是“架构更优雅”，而是更实际的四点：

1. **接入成本更低**：输入协议支持结构化内容
2. **排障效率更高**：每次执行都有 Plan 可复盘
3. **稳定性更强**：状态机 + 迭代上限 + 异常路径清晰
4. **生产可用性更好**：异步任务化 + 状态查询 + 列表检索

---

## 总结

如果要用一句话概括这次 MR：

> **Langur 从“有 Agent 功能”升级到了“有 Agent 生命周期管理能力”。**

DDD 多模块拆分是地基，但真正拉开差距的是这套四阶段闭环：

**输入标准化 → 规划可追踪 → 执行可诊断 → 运行可运营**。

这也是 Agent 框架走向生产化最关键的一步。

---

*Langur 项目地址：[https://github.com/Jashinck/Langur](https://github.com/Jashinck/Langur)*
