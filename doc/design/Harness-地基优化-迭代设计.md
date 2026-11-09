# Langur Harness 地基优化 · 迭代设计（H15）

> **文档性质**: 迭代设计方案（Iteration Design）——承接 Harness 六组件硬化（H1–H14）之后的地基修复与业界对齐：把上一轮架构 review 发现的「文档标 ✅ 但代码断链」的正确性缺陷修复掉，并对照 2026 业界 Harness 工程共识（Context Engineering / Guides-Sensors / 隔离执行环境 / 事件日志化）补齐 Langur 缺失的能力面。
> **日期**: 2026-10-18 | **定位**: 企业级 Agent-Harness 框架，三大架构哲学（Harness/Jev/RSI）不变，本迭代是 Harness 哲学内部的**工程补课**。
> **一句话立场**: **先让主链路真的跑通、让上下文真的被治理，再谈判定与自演进。**
> **与 Client 迭代的关系**: 本迭代的 H15.1（事件日志化）是 Client 迭代 §4.3「ReAct 轨迹事件流」的正确基座——两者共享"执行轮次结构"这一接缝，建议 H15.1 先行或与 Client-R1 统筹，避免 LLM 消息契约被两条腿各改一遍。

---

## 0. 背景：上一轮 review 的结论摘要

上一轮对 389 个 main 源文件与设计文档逐条对账，得出两层结论：

**A. 地基缺陷（4 条，已标 ✅ 但实际断链）**

| # | 声称 | 代码事实 | 证据 |
|---|------|----------|------|
| A1 | 主链路贯通 ~95% | 多轮 ReAct 对真实 provider 不可用：会话契约是 `List<Map<String,String>>`，出站无 `tool_call_id`、assistant 侧无原生 `tool_calls`，而 `parseDecision` 读的却是原生 `tool_calls[0]` → 第二轮 400 | `LLMPort.java:13-15`、`AbstractOpenAICompatibleLLMAdapter.java:71-85/193-196` |
| A2 | 四级记忆 + 上下文装配 + Token 治理 | C 组件是 **write-only 审计旁路**：装配产物从不进 prompt；全仓无 `compact/elision/summariz/scratchpad`；Token 预算是 char/4 估算 | `ReActExecutionLoop.java:462-466`、`AgentDomainService.java:76-81`、`Agent.java:27` |
| A3 | 快照 + 断点续跑 | 快照 payload 只有 3 个标量（`agentStatus/iteration/planStepsCount`），**消息级续跑不可能** | `ReActExecutionLoop.java:497-503` |
| A4 | H12 ✅ / RSI L1–L5 ✅ / 614 测全绿 | H12 无生产装配（bus 非 `@Component`、无 `@Bean`）；RSI 零 `@Scheduled/@EventListener` 触发；**零 `@SpringBootTest`**，614 全为手搓桩单测 | 全局 grep |

**B. 业界对齐缺口（8 项，见 §2）**

---

## 1. 设计原则与顺序

### 1.1 三条本迭代铁律

| # | 铁律 | 含义 |
|---|------|------|
| T1 | **先正确，后聪明** | A1/A2/A3 是正确性缺陷，不修则其上一切（决策平面/RSI/Client）都是空中楼阁 |
| T2 | **模型无关、预算感知** | 上下文/工具/沙箱策略不得绑定具体模型，按模型能力与成本预算路由（对齐实证研究结论） |
| T3 | **computational 优先，inferential 兜底** | 验证/判定先用确定性、免费的计算型手段（编译/测试/schema/指纹），语义模型只在确有必要时兜底（Böckeler guides-sensors 分层） |

### 1.2 优先级与依赖

```
P0（正确性地基）:  H15.1 事件日志  →  H15.2 上下文工程  →  H15.3 快照完整化  →  H15.8 装配冒烟
                     │
P1（业界对齐）:      ├─ H15.4 workspace 即记忆
                     └─ H15.5 真沙箱 + 自验证回路
P2（差异化增强）:   H15.6 判定/生成/验证   H15.7 RSI 防错累积
```

---

## 2. 任务分解（H15.1–H15.8）

### 2.1 H15.1 — 会话事件日志化 + 原生工具调用契约（地基 A1）

**现状**：会话 = `Agent.conversationHistory`（`List<Map<String,String>>`），每次 `addAssistantMessage` 写入文本 `"Thought:…\nAction:…\nAction Input:…"`（`AgentDomainService.java:129-132`），`overrideFinalAnswer` 靠**倒序扫描**改写最近一条 assistant（`Agent.java:114-123`）；`LLMPort.decide` 契约只有 `role/content` 两列，无 `tool_call_id`、无多 tool_calls。

**目标**：把会话建模为 **append-only 事件日志**，一条 LLM 消息对应一个结构化事件；`LLMPort` 契约升级为原生工具调用消息。

**设计**：

```
现状  Agent.conversationHistory: List<Map<String,String>>   // role/content(/name)，可变、倒序改写、无 id
目标  Agent.sessionEvents: List<SessionEvent>（append-only）
      SessionEvent = UserMessage | AssistantMessage(assistant) | ToolCall(id, name, args)
                   | ToolResult(toolCallId, name, content) | FinalAnswer(content)
      LLMPort.decide(...) 入参 List<SessionEvent> → 适配器按 provider 原生格式序列化
      （assistant tool_calls + role:tool 回指 tool_call_id；并行多 tool_calls 保留，不再取 [0]）
```

**落地接缝**：

| 变更 | 位置 | 说明 |
|------|------|------|
| 新增 `SessionEvent` 值对象（sealed 层级） | `domain/model/message`（已有 message 包） | 纯 JDK，P1 合规 |
| `Agent` 聚合：`conversationHistory` → `sessionEvents`；`overrideFinalAnswer` 改为按 eventId 精确改写（去掉倒序扫描） | `Agent.java` | 事件日志不可变，改写=追加修正事件 |
| `LLMPort.decide` 契约签名升级 | `LLMPort.java` | 破坏性变更，仅一处实现（`AbstractOpenAICompatibleLLMAdapter`） |
| 适配器序列化：assistant `tool_calls[]` + tool `tool_call_id` | `AbstractOpenAICompatibleLLMAdapter.java:71-85` | 修复 A1 的 400 |
| 并行工具调用：`parseDecision` 取全部 `tool_calls`，E 循环按序执行并逐个回填 | 同文件 `:193-196` | 一模型多工具调用一次执行 |

**边界与不变式**：
- 事件日志**只增不改**（append-only）：这是 R0 回放、R2 蒸馏、Client 轨迹时间线的共同地基；`overrideFinalAnswer` 的"改写"语义改为"追加修正事件 + 视图投影取最新"。
- 持久化：v1 事件日志随 Agent 仓储（memory/jpa）序列化；**会话级多用户隔离仍不在本迭代**（属 Client §4.2 声明的边界），但事件结构为它预留 `sessionId` 字段。
- **与 Client-R1 汇合**：事件日志落地后，Client §4.3 的 `tool/observation` 事件 = 事件日志的流式投影；`ToolTraceStreamHook` 退化为日志订阅器。

**验收**：适配器单测断言出站 JSON 含 `tool_calls[].id` 与 `role:tool.tool_call_id`；双 tool_calls 全量保留；`overrideFinalAnswer` 精确命中不再倒序扫描。

---

### 2.2 H15.2 — 上下文工程进入 prompt 路径（地基 A2 + 业界①④）

**现状**：`assembleContext` 产出 `AgentContext` 后只落仓储；真正进 prompt 的是 `agent.getConversationHistory()` 原文（`AgentDomainService.java:76-81`）。无压缩/裁剪；**全部工具全量 schema 每轮入参**（`AbstractOpenAICompatibleLLMAdapter.java:77-85`）；无 prompt cache 稳定前缀。

**目标**：把 C 组件从"治理语义"升级为"工程语义"——一个真正插在 `decide` 之前的 `ContextBuilder`，做三段式预算管理 + 工具瘦身 + 稳定前缀。

**设计（三段流水线，对齐实证"规则化删减先于 LLM 摘要"）**：

```
raw events ─▶ [1] 规则 elision         ─▶ [2] head/tail 保留      ─▶ [3] 有界 LLM 摘要（可选）
             · 去掉纯状态性事件          · 大 tool 输出仅留          · 仅当 1+2 后仍超预算
             · 去掉被回滚/重试的轮次       head/tail 各 N 字符         · 摘要"按原意压缩，不引入新事实"
             · 去重相邻重复观察            · 全量落 workspace(§H15.4)  · 走 M5 或 DecisionPort score 判质
             （确定性、免费、可回放）
```

| 段 | 机制 | 原则 |
|----|------|------|
| 1 规则 elision | 领域规则（无 LLM）：删除重复观察、已回滚尝试、纯状态事件 | 确定性、免费、**可回放**（规则版本化） |
| 2 head/tail 保留 | 大输出（MCP/REST/grep）只保留首尾 + `[共 X 字节已外置]` 占位 | 引用而非内联，配合 H15.4 workspace |
| 3 有界 LLM 摘要 | 仍超预算才调 M5 摘要，摘要本身受 Token 预算约束 | T3：inferential 兜底 |

**工具瘦身 + 稳定前缀（同属本任务）**：

- **工具瘦身**：常驻只发 `name + description`，**参数 schema 按需展开**（模型首次想调用某工具时再补 schema，或按 bizCode/TaskProfile 预筛工具集）。对多工具 Agent，每轮窗口立省一大块（对齐 Deep Agents 的"工具绑定技能、按需加载"）。
- **稳定前缀**：system prompt + 工具元数据 + 事件日志头部保持字节稳定，让 provider 的 prompt cache 命中；动态内容只追加在尾部。

**落地接缝**：

| 变更 | 位置 |
|------|------|
| 新增 domain 端口 `ContextBuilder`（`build(TaskProfile, List<SessionEvent>, budget) → PromptContext`） | `domain/harness/context` |
| `DefaultContextBuilder`（三段流水线，规则 elision 走可插拔 `ContextElisionRule` SPI） | `infrastructure/harness/context` |
| `AgentDomainService.executeReActStep` 改用 `contextBuilder.build(...)` 产物喂 `decide` | `AgentDomainService.java:76-81` |
| 适配器工具数组支持"瘦身模式"（仅 name+desc，schema 惰性） | `AbstractOpenAICompatibleLLMAdapter.java:77-85` |

**边界**：`DefaultContextAssembler`（双画像 + 脱敏）保留为治理层，运行于 ContextBuilder **之前**；Token 预算从 char/4 估算升级为**真实 tokenizer 可选**（provider 提供则用，否则保留估算降级 P10）。摘要在确定性要求下**默认关**（`langur.context.summarize.enabled=false`），先交付 1+2 段。

**验收**：超预算会话经 1+2 段后 token 估算 ≤ 预算；大输出仅留 head/tail 且全量可在 workspace 找到；工具 schema 惰性加载行为可测。

---

### 2.3 H15.3 — 快照完整化 / 消息级续跑（地基 A3）

**现状**：`snapshotPayload` 只有 `agentStatus/iteration/planStepsCount`（`ReActExecutionLoop.java:497-503`）；续跑只恢复轮次计数（`Agent.resumeIteration`）。

**目标**：快照携带**事件日志游标**，实现消息级断点续跑。

**设计**：`StateSnapshot` payload 增 `eventCursor`（事件日志已消费到的序列号 + 最近一条 tool_call_id）；续跑时从游标处重放，而非从空会话重来。H15.1 的事件日志天然提供游标语义（append-only 序列）。

**边界**：快照仍按轮写入（`ReActExecutionLoop.java:246-254` 既有节奏不变）；"崩溃恢复 <5s" 的宣称从此**真实可证**。跨进程快照一致性（Redis 层）不在本迭代，续跑正确性以单进程 + jpa 仓储验证。

**验收**：注入中途失败 → 从快照续跑，`decide` 收到的会话与崩溃前一致（快照测试断言事件游标等价）。

---

### 2.4 H15.4 — workspace 即记忆 + 工具输出外置（业界③）

**现状**：只有 `read_file/list_dir/grep`（`CodeToolProvider`），无 write、无执行、无外置；大工具输出原样进会话（H15.2 之前）。

**目标**：给 Agent 一个 **scratch 工作区**——大输出落盘、窗口只留引用，Agent 可读写中间产物（对齐 LangChain「Filesystems for Durable Storage」与 NOTES.md 模式）。

**设计**：
- 新增 `WorkspacePort`（domain 端口）+ `LocalDirectoryWorkspace`（infra 默认实现）：每 task 一个隔离目录（`langur.workspace.root/{taskId}`）。
- 新工具 `workspace_write(name, content)` / `workspace_read(name)` / `workspace_list()`，经 T 组件四层校验链注册（source=LOCAL，权限按 TaskProfile）。
- H15.2 的 head/tail 保留段把被截断的全量内容 `workspace_write`，占位符携带文件名。

**边界**：路径规范化限制在 workspace root 内（防越权，复用 code-access 的归一化逻辑）；workspace 生命周期随 task 结束清理（可配置保留）。**沙箱隔离不属于本任务**（H15.5）。

**验收**：grep 命中超大结果 → 会话只见 head/tail + 占位符，`workspace_read` 可取回全量；越权路径被拒。

---

### 2.5 H15.5 — 真沙箱 + 通用代码执行工具 + computational sensor 自验证回路（业界⑤）

**现状**：`SandboxToolValidator` 只检查"配了 timeout"（`SandboxToolValidator.java:17-24`）；`runGeneric` = 线程池 + `Future.get`（`DefaultToolDispatcher.java:237-245`），无进程/文件/网络隔离；无任何"跑测试/编译/lint"的验证面。

**目标**：引入**进程级沙箱**作为可插拔后端，新增通用**代码执行工具**，并在 E 循环轮间织入 **computational sensor**（确定性验证），构成"生成 → 执行 → 验证 → 修复"闭环（Böckeler 的 computational sensor）。

**设计**：

| 层 | 机制 | 落地 |
|----|------|------|
| 沙箱端口 | `SandboxPort`（domain）：`SandboxRequest{cmd, files, limits}` → `SandboxResult{exitCode, stdout, stderr, usage}` | 新增端口 |
| 默认实现 | `ProcessSandbox`（infra）：`ProcessBuilder` + 资源限制（超时/内存）+ 只读挂载宿主 + 网络白名单；`gVisor/Docker` 为可选后端（`langur.sandbox.type=process|docker|gvisor`，P9） | 可插拔 |
| 通用工具 | `execute_code(language, code, args)` / `execute_command(cmd)`，走沙箱执行 | 经四层校验链注册 |
| 自验证回路 | 新增 `ComputationalSensor` 钩子（实现 `LifecycleHook`，挂 `AFTER_TOOL_CALL`）：对"写代码→跑测试/编译→看结果"的模式，把 stderr/exitCode 作为确定性信号回填，让 E 循环**带证据**继续而非盲目重试 | 复用 L 组件接缝，零侵入循环 |

**边界与红线**：沙箱默认 `process`（无容器依赖，P10 降级到"超时+守护线程"现状行为）；`docker/gvisor` 为显式开启的生产增强。**computational sensor 只"带证据反馈"，不做安全闸门**——安全仍走四层校验链（P12 advisory 语义不变）。

**验收**：`execute_code` 跑 `python -c "print(1/0)"` 返回 exitCode≠0 + stderr；超时进程被硬杀；sensor 把失败 stderr 追加进会话事件日志。

---

### 2.6 H15.6 — 决策平面「判定/生成/验证」扩展 + 零依赖计算型后端 + 模型/预算感知路由（业界⑥⑧）

**现状**：哲学二 = 判定/生成分离，十插入点全押单一厂商 Jev（`TypeSafeDecisionAdapter`），无可自证的第二个后端；`DefaultLayerRouter` 只路由范式，不路由上下文策略。

**目标**：把哲学二从「判定/生成」**扩展为「判定/生成/验证」**——显式引入 **sensor 面**（验证是一种确定性判定），并给 `DecisionPort` 补一个**零外部依赖的计算型后端**，同时让路由**模型/预算感知**。

**设计**：

```
DecisionPort 新增后端（后端无关，P3/P5 兑现）：
  ┌─ TypeSafeDecisionAdapter    # 既有（Jev 云，非敏感场景）
  ├─ LocalDecisionAdapter        # 既有（自部署 Kev/Laya，敏感场景）
  ├─ ComputationalDecisionAdapter  ← 新增：把 computational sensor 的结果
  │     （exitCode==0? / schema 校验通过? / 指纹命中? / 阈值规则）
  │     包装成 DecisionAnswer{choice|score, confidence=1.0}，零模型调用、零费用、确定性
  └─ RuleFallbackDecisionAdapter # 既有
```

- **sensor 面（验证）**：H15.5 的 computational sensor 产出的确定性信号，经 `ComputationalDecisionAdapter` 成为 `DecisionPort` 的第一优先判定源——先判"它到底跑通了没有"，再谈"它答得好不好"。判定/生成/验证三元闭环落地。
- **模型/预算感知路由**：`DefaultLayerRouter` 增一个可选的 `HarnessPolicyResolver`——按 `model`（是否长上下文/是否支持原生并行工具调用）与 `budget`（成本上限）选择上下文策略（是否启用摘要、工具瘦身激进程度、checkpoint 间隔），把 H15.2 的策略变成**可路由的配置**。

**边界**：`ComputationalDecisionAdapter` 置信度恒为 1.0（确定性），但它**只答"可计算的问题"**，语义判定仍需 Jev/LLM——两个后端分工清晰，不互相替代。路由策略变更仍受 R-G 约束（只收紧不放松）。

**验收**：`ComputationalDecisionAdapter` 无网络依赖下返回确定性判定；sensor 结果先于 Jev 被消费（优先级断言）。

---

### 2.7 H15.7 — RSI 防错累积真环（地基 A4 之 RSI 无触发 + 业界⑦）

**现状**：R0/R2/R3/R4/R5/R-G 是库不是环——零 `@Scheduled/@EventListener/ApplicationRunner`，`InMemoryTrajectoryRepository` 运行期从不被写。仅有 R1 作为钩子活着。

**目标**：先落 **RSI 最便宜、业界最成熟的一条真环**——「防错累积」：每错 → 一条规则 → 持久化 guide → 注入下一会话；并补轨迹生产者/消费者最小链路。

**设计（对齐 Hashimoto「每犯一次错就工程化一个方案」）**：

```
执行中   [V] 轨迹落盘（H15.1 事件日志 + 快照已是生产者）
   │
   ▼（@Scheduled 离线消费，langur.rsi.consumer.cron）
离线环   TrajectoryConsumer（新增）:
   │   1. 归类失败轨迹（terminateReason ∈ {LOOP_DETECTED, TOOL_FAILURE, 闸门触发}）
   │   2. 提炼"防错规则"（确定性模板：失败特征 → 指引/约束）
   │   3. 经 R-G 安全平面（复用 RsiSafetyPlane 提案-验证-应用）产出候选
   │   4. 写入持久化 GuideStore（复用 L4 向量库或文件，namespace=rsi-guide）
   ▼
下一会话  ContextBuilder（H15.2）把命中的 guide 注入 system/工具描述（progressive disclosure：仅相关规则）
```

**落地接缝**：`TrajectoryConsumer` 是 infra `@Component`，`@Scheduled(cron=...)` 且 `@ConditionalOnProperty langur.rsi.enabled=true`；轨迹生产者直接复用 H15.1 事件日志 + 既有快照录制，**不新增采集逻辑**。R2 蒸馏/R3 合成/R4 优化留待本环跑通后再接（仍是候选提案语义，P11 不变）。

**边界**：防错规则**只增不改**、只注入指引不放松闸门（P12）；guide 注入走 H15.2 的 progressive disclosure，避免常驻膨胀。

**验收**：伪造一条 LOOP_DETECTED 轨迹 → consumer 产出防错规则 → 下一会话上下文含该规则；规则经 R-G 拒绝（如触碰安全前缀）则不注入。

---

### 2.8 H15.8 — 评测基座 + 装配冒烟 + claim→evidence CI（地基 A4 + 业界⑧）

**现状**：614 单测全为手搓桩；无 `@SpringBootTest`；无 golden trajectory 回放评测；设计文档多处"声称 ≠ 实现"。

**目标**：建立**可证伪**的工程底座——没有评测，RSI 的"改进"与决策平面的"提准"都不可证。

**设计**：

| 层 | 机制 |
|----|------|
| 装配冒烟 | 新增 `@SpringBootTest` 冒烟（`/actuator/health` UP + 关键 Bean 非空），**堵住"未装配 Bean 标 ✅"**——H12 bus、RSI consumer 等必须有装配断言 |
| golden 回放评测 | `GoldenTrajectoryEval`：录一组真实轨迹（golden set），CI 回放断言"关键事件序列 + 终止原因"稳定；候选策略（阈值/规则）变更必须过此评测 |
| task-success 评测 | 一组端到端任务（如"合同审查产出报告"）的成功率断言，作为 RSI/决策平面变更的回归门 |
| claim→evidence 治理 | 设计文档每项 ✅ 能力附 `file:line` 证据表；新增 CI 校验：**标 ✅ 的组件若无可装配 Bean / 无调用点即失败** |

**边界**：评测集是**人工维护的资产**（首版由本轮 review 的 A1/A2/A3 缺陷驱动补 3 条反例），不是自动生成的；task-success 评测依赖真实 provider 的用例**标记为 @Tag("integration")** 单独跑，不拖慢单测。

**验收**：删掉某个 `@Bean` 装配 → 冒烟失败；把某条"声称 ✅"但无实现的能力从文档删除或改标 → CI 通过。

---

## 3. 与三大哲学 / P1–P12 的关系

| 哲学/原则 | 关系 |
|-----------|------|
| **Harness** | 本迭代全部落在 Harness 哲学内部：C（上下文）从治理升级为工程、S（状态）消息级续跑、T（工具）瘦身+沙箱+执行、E（执行）验证回路。六组件正交位不变（P4） |
| **Jev** | 哲学二措辞由"判定/生成"扩展为"判定/生成/验证"，新增 sensor 面与计算型后端；P12 advisory 语义不变 |
| **RSI** | 从"库"到"最小真环"（防错累积）；P11（产物默认候选）+ R-G（安全平面）不变 |
| **P1/P2/P4** | 事件日志/ContextBuilder/沙箱均以端口倒置落地，domain 零外部依赖，ArchUnit 三不变量不变 |
| **P5 开闭** | 沙箱（process/docker/gvisor）、判定后端（computational/typesafe/local）、摘要器、elision 规则全部端口化可插拔 |
| **P9 配置驱动** | `langur.context.*`/`langur.sandbox.*`/`langur.workspace.*`/`langur.rsi.consumer.*` 全程配置，默认关/默认降级 |
| **P10 降级** | 摘要在 1+2 段不足时才启用；沙箱降级 process；计算型判定后端零依赖常可用 |

---

## 4. 里程碑与验收

| 任务 | 优先级 | 依赖 | 验收要点 |
|------|--------|------|----------|
| H15.1 事件日志 + 原生工具调用 | **P0** | — | 出站含 `tool_call_id`；双 tool_calls 保留；精确改写 |
| H15.2 上下文工程 | **P0** | H15.1 | 三段流水线生效；工具瘦身；token ≤ 预算 |
| H15.3 快照完整化 | **P0** | H15.1 | 事件游标续跑等价 |
| H15.8 评测基座 + 装配冒烟 | **P0** | H15.1 | 冒烟/回放/claim CI 三项就位 |
| H15.4 workspace 即记忆 | P1 | H15.2 | 大输出外置可取回；越权拒绝 |
| H15.5 真沙箱 + 自验证 | P1 | — | 代码执行 + 硬超时 + sensor 带证据 |
| H15.6 判定/生成/验证 + 计算型后端 + 感知路由 | P2 | H15.5/H15.2 | 计算型判定优先；策略可路由 |
| H15.7 RSI 防错累积真环 | P2 | H15.1/H15.8 | 失败轨迹 → 规则 → 注入；R-G 拒绝生效 |

---

## 5. 风险与取舍

| 风险 | 影响 | 缓解 |
|------|------|------|
| `LLMPort.decide` 契约破坏性变更 | 高（一次） | 仅一处实现 + 既有 614 单测兜底；H15.1 先行独立全绿 |
| 摘要/elision 改变 Agent 行为 | 中 | 规则 elision 确定性、版本化、可回放（H15.8 回放评测守护）；摘要默认关 |
| 沙箱引入宿主安全面 | 中 | 默认 process + 只读挂载；docker/gvisor 显式开启；不与四层校验链冲突 |
| 评测集维护成本 | 低 | 首版 3 条反例（对应 A1/A2/A3），随缺陷增补 |
| 与 Client 迭代顺序耦合 | 中 | H15.1 先于 Client-R1；两文档 §4.3/§2.1 已显式汇合 |

---

## 6. 附录 A. 与上一轮 review 结论的映射

| 本迭代任务 | 对应 review 项 |
|-----------|----------------|
| H15.1 | 地基 A1（tool_call_id）+ 业界②（事件日志） |
| H15.2 | 地基 A2（C write-only）+ 业界①④（上下文工程/工具瘦身/prompt cache） |
| H15.3 | 地基 A3（快照 3 标量） |
| H15.4 | 业界③（workspace 即记忆） |
| H15.5 | 业界⑤（真沙箱 + computational sensor） |
| H15.6 | 业界⑥⑧（判定/生成/验证 + 计算型后端 + 模型/预算感知路由） |
| H15.7 | 地基 A4（RSI 无触发）+ 业界⑦（防错累积） |
| H15.8 | 地基 A4（无集成测试）+ 文档治理（claim→evidence） |
