# Agent-Harness 架构设计 v1.0 — 通用能力底座

> **文档性质**: 通用架构设计方案（General Architecture Blueprint）——Harness 确定性调度底座的**能力全集**
> **版本**: v1.0 | **日期**: 2026-09-26 | **定位**: 框架级通用架构，不绑定具体业务场景
> **核心公式**: `Agent = LLM推理能力 + Harness确定性调度能力`
> **一句话立场**: **Harness 是大模型的操作系统与安全沙箱层——模型负责写，Harness 负责管。**
> **关联文档**: [v2.0 — 决策平面与 RSI 整合](Agent-Harness架构设计-v2.0.md)（在本文六组件底座之上新增"判定式决策平面 + 递归自演进"能力面）

---

## 一、背景

### 1.1 大模型生产落地六大缺陷

大模型原生落地生产环境，存在六大致命缺陷。Harness 以六个控制单元逐一对应解决：

| # | 生产缺陷 | 风险表现 | Harness 组件 | 解决策略 |
|---|----------|----------|--------------|----------|
| 1 | 无流程强制管控 | 模型自由发挥、流程不可控 | **E** 执行循环器 | 三大 Runtime 范式 + 终止闸门 |
| 2 | 无工具安全风控 | 越权调用、注入攻击、资损 | **T** 工具注册中心 | 九元组注册 + 四层校验链 |
| 3 | 无结构化记忆治理 | 上下文污染、Token 失控、幻觉 | **C** 上下文管理器 | 四级分层记忆 + 双画像 |
| 4 | 无状态持久化容灾 | 崩溃丢失、无法续跑 | **S** 状态存储库 | 快照 + 断点续跑 + 回滚 |
| 5 | 无全链路安全拦截 | 敏感泄露、合规缺失 | **L** 生命周期钩子 | 12 拦截点 + 四层纵深防御 |
| 6 | 无可观测合规审计 | 黑盒运行、无法溯源 | **V** 评估观测接口 | 四维指标 + Trace + 审计 |

> **问题域的共性**：大模型的输出是**概率性的**，而生产环境的约束是**确定性的**（权限、预算、合规、可回滚）。二者之间缺的不是"更强的模型"，而是一层**确定性的调度底座**——这就是 Harness 的存在意义。

### 1.2 演进脉络

Langur 的 Harness 底座不是一蹴而就，而是沿"**骨架 → 主链 → 硬核 → 硬化**"四阶段演进而来：

| 阶段 | 时间 | 主线 | 关键落地 |
|------|------|------|----------|
| **骨架落位** | 2026-08 | 六组件骨架 + ReAct 主链路 | T1–T8：工具校验链、终止闸门、上下文装配、断点续跑、层路由、容错循环检测、SSE 流式 |
| **扩展体系** | 2026-09 上旬 | SPI + 工具三源 + 持久化 | T9–T15：七大 SPI、REST/MCP/Skill 三源、JPA 七表、OTel Trace、治理链、向量存储、`LlmGateway` |
| **主链贯通** | 2026-09-25 | H1–H10 六组件补强 | 四范式引擎（PlanAndExecute / Workflow+Hybrid）、Redis 热层、语义 Embedding+Rerank、真实 Token 计量、可观测导出+告警、五层防御补输入/输出/审批、MCP 四传输、OpenAPI/KMS、Skill 七步骤 |
| **接缝硬化** | 2026-09-26 | H13/H14 基础设施 | 配置驱动 Provider 工厂（统一模型网关，零代码扩厂）+ 可插拔向量库（ES/Milvus 原生混合检索） |

> 演进原则始终如一（P8 渐进式演进）：**每阶段都是既有架构的增量强化，不推翻重建**——六组件正交拓扑（P4）自骨架落位起从未改变，所有能力都是"组件内加深 + 端口扩展"。

---

## 二、目标

### 2.1 产品定位

Agent-Harness 是一套**企业级 Agent 通用脚手架框架**，作为大模型通往生产环境的**确定性调度底座**，赋能各业务线快速构建安全、可控、可观测的企业级 Agent 应用。

> Harness 是部署在**大模型推理层**与**外部业务环境**（工具/数据库/API/文件系统/用户会话）之间的**确定性调度中间件基础设施**。本质：**大模型的操作系统与安全沙箱层**。

### 2.2 两大强制隔离原则（不可违背）

| 原则 | 定义 | 工程约束 |
|------|------|----------|
| **模型与环境隔离** | LLM 无法直连任何外部资源 | 所有工具调用必须经 T 组件校验 + 沙箱执行 |
| **推理与治理隔离** | 模型仅负责推理决策 | 权限/合规/风控/审计全下沉 Harness，无需改 Prompt |

### 2.3 核心公式与三支柱

```
Agent（六边形 DDD 架构） = LLM（模型矩阵） + Harness（六大控制单元）
```

- **Agent 部分**：API、Application、Domain、Infrastructure、Common、Start 六个 DDD 模块
- **LLM 部分**：按职责分工的模型矩阵（路由/向量化/重排/行动/推理/长上下文）
- **Harness 部分**：Context、Execution、ToolManage、StateMachine、Hooks、Metrics 六大控制单元

### 2.4 设计目标与度量标准

| 目标 | 度量标准 |
|------|----------|
| 通用性 | 业务方通过 SPI 接入，≤3 天完成新业务域对接 |
| 生产级 | 安全/容错/可观测/合规能力出厂内置 |
| 高性能 | 首字延迟 <800ms，上下文构建 <100ms |
| 高可用 | 断点续跑，崩溃恢复 <5s，零任务丢失 |
| 可扩展 | 六组件独立启停，范式动态切换 |
| 可演进 | 单范式 → 混合架构平滑过渡，无破坏性变更 |

---

## 三、全局架构图

### 3.1 全局架构

![Langur 全局架构](../share/images/langur-global-architecture.png)

### 3.2 DDD 六模块分层架构

![Langur DDD 六边形架构](../share/images/langur-ddd-hexagonal.png)

框架采用六边形 DDD 架构，划分为六个独立 Maven Artifact，每个模块拥有独立根包（`org.skylark.langur.*`），通过包路径实现编译期依赖隔离：

| 模块 | Artifact | 根包 | 核心职责 | 禁止事项 |
|------|----------|------|----------|----------|
| **common** | langur-common | `*.common` | 枚举/异常/DTO/SPI 接口/共享端口 | 禁止有状态逻辑、禁止 Spring 依赖 |
| **domain** | langur-domain | `*.domain` | 六组件领域模型/服务/端口/事件 | 禁止依赖任何外层模块及中间件 |
| **application** | langur-application | `*.application` | 用例编排/事务协调/DTO 转换/事件分发 | 禁止包含领域规则 |
| **infrastructure** | langur-infrastructure | `*.infrastructure` | 仓储实现/LLM 适配/中间件/沙箱 | 禁止包含业务规则 |
| **api** | langur-api | `*.api` | REST/SSE 协议适配/参数校验/限流 | 禁止包含编排逻辑 |
| **start** | langur-start | `*.langur` | 启动/自动装配/配置/健康检查 | 禁止包含业务逻辑 |

依赖方向（强制单向）：

```
start → api → application → domain ← infrastructure
                              ↑
                        common (全模块依赖)
```

**依赖治理（已落地 ArchUnit）**：`ArchitectureGuardTest` 编译期守护三条不变量——① domain 纯度（零 Spring / 零 infrastructure import）；② common 无状态 Bean（无 `@Component/@Service/@Repository`）；③ 六模块依赖无环。domain 层仅允许依赖 common + lombok，零外部依赖（P1）。

### 3.3 Harness 六组件标准架构

![Langur Harness 六组件](../share/images/langur-harness-six-components.png)

```
┌───────────────────────────────────────────────────────────────────────┐
│                    L — Lifecycle Hooks (全流程贯穿)                     │
│          12个拦截点 · 四层纵深防御 · 非侵入 · 可动态启停                 │
├───────────────────────────────────────────────────────────────────────┤
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     │
│  │    C     │────▶│    E     │────▶│    T     │────▶│    S     │     │
│  │ Context  │     │Execution │     │   Tool   │     │  State   │     │
│  │ Manager  │◀────│  Loop    │◀────│ Registry │     │  Store   │     │
│  └──────────┘     └──────────┘     └──────────┘     └──────────┘     │
│       └────────────────┴────────────────┴────────────────┘            │
│                                │                                      │
│                         ┌──────▼──────┐                               │
│                         │      V      │                               │
│                         │ Evaluation  │                               │
│                         └─────────────┘                               │
└───────────────────────────────────────────────────────────────────────┘
```

| 组件 | 全称 | 聚合根/核心服务 | 核心能力 |
|------|------|-----------------|----------|
| **E** | Execution Loop 执行循环器 | `ExecutionTask` / `ExecutionLoopService` | 三大范式运行、终止闸门、循环检测、容错降级 |
| **T** | Tool Registry 工具注册中心 | `ToolDefinitionEntity` | 九元组注册、四层校验链、统一路由调度 |
| **C** | Context Manager 上下文管理器 | `AgentContext` | 四级记忆、双画像、Token 治理、脱敏过滤 |
| **S** | State Store 状态存储库 | `TaskState` | 快照、断点续跑、事务回滚、分布式锁 |
| **L** | Lifecycle Hooks 生命周期钩子 | `LifecycleHookEngine` | 12 拦截点、四层防御、Hook 链中断/放行 |
| **V** | Evaluation Interface 评估观测 | `EvaluationService` | 四维指标、全链路 Trace、不可篡改审计 |

六组件协同闭环（标准执行时序）：

```
环境状态采集
→ [C] 上下文加载(记忆+画像+脱敏过滤)
→ [E] 执行循环驱动模型推理决策
→ [T] 工具中心(权限校验+Schema校验+沙箱执行)
→ [S] 状态存储写入本轮执行快照与结果
→ [L] 生命周期钩子全节点安全拦截与合规植入
→ [V] 观测接口全量轨迹/指标/日志留存
→ [E] 判断任务终止条件 → 结束迭代 或 进入下一轮
```

### 3.4 三层混合 Runtime

| 层级 | 范式 | 职责 | 控制权 | 适用场景 |
|------|------|------|--------|----------|
| 顶层 | Workflow | 锁定合规边界/审批/强约束 | Harness 全权 | 强合规流程、审批工单 |
| 中层 | PlanAndExecute | 全局任务拆解/进度管控/动态调优 | LLM 规划 + Harness 校验 | 复杂长链路任务 |
| 底层 | ReAct | 细粒度工具推理/局部动态交互 | LLM 自主 + Harness 兜底 | RAG/开放对话 |
| 混合 | Hybrid | Workflow 锁边界 → Plan 拆解 → ReAct 执行 | 三层分权 | 全场景最优适配 |

```
任务进入 → LayerRouter 判断:
  ├── 强合规/审批类 → 顶层 Workflow
  ├── 多步骤复杂任务 → 中层 PlanAndExecute
  ├── 单轮推理/开放问答 → 底层 ReAct
  └── 默认(未指定) → ReAct
```

### 3.5 五层纵深防御

```
Layer 1: 接入安全 — 身份鉴权/JWT/限流/黑名单/IP白名单
Layer 2: 输入安全 — 敏感词检测/Prompt注入拦截/长度校验
Layer 3: 执行安全 — 工具沙箱/权限校验/高危审批/越权阻断
Layer 4: 输出安全 — 内容审核/资损校验/涉密过滤/合规检测
Layer 5: 审计安全 — 全量留痕/不可篡改/合规归档/溯源查询
```

### 3.6 全链路执行时序

```
═══ 启动阶段(应用初始化时) ═══
[Start] → McpClientManager.init() → 连接MCP Server → tools/list发现 → 注册到[T]
        → SkillRegistrar.register() → 扫描@SkillDef注解 → 注册所有Skill
        → RestApiToolRegistrar.register() → OpenAPI解析/手动配置 → 注册到[T]

═══ 请求处理阶段 ═══
Client → [API] GovernanceFilter(鉴权/限流/参数校验/TraceId生成)
       → [APP] AgentApplicationService.dispatch() → LayerRouter.route() → 范式分发
       → [L] BEFORE_CONTEXT_ASSEMBLE Hook
       → [C] 上下文组装(并行: 画像+记忆+知识+工具列表+Skill列表)
       → [C] 脱敏过滤 + Token治理
       → [L] AFTER_CONTEXT_ASSEMBLE Hook
       → [S] 断点恢复检测(是否有未完成快照)
       → [E] 执行循环启动
           ┌─→ [L] BEFORE_INFERENCE Hook
           │   [E] LLM推理(通过LlmGateway, tools含全部来源工具)
           │   [L] AFTER_INFERENCE Hook
           │   [E] 解析LLM返回的tool_calls → [T]统一路由执行
           │   [L] BEFORE/AFTER_TOOL_CALL Hook
           │   [S] 本轮快照写入
           │   [E] 终止判断(闸门检测)
           └── 未终止 → 下一轮 / 已终止 → 退出循环
       → [V] 指标上报 + Trace结束
       → [L] BEFORE_OUTPUT Hook(输出合规)
       → [API] 响应返回
```

---

## 四、核心模块详细设计

> 每节按 **职责 → 核心机制 → 落地状态 → 剩余缺口** 四段展开。

### 4.1 E — 执行循环器

**职责**：驱动"感知-推理-行动"循环，是 Agent 运行的核心引擎。

**核心机制**：

- **运行范式（四范式全落地）**：`ParadigmDispatchingExecutionLoop` 按 `RuntimeParadigm` 分发到 **ReAct** / **PlanAndExecute**（H3：规划拆解→逐步委派 ReAct 子循环→动态重规划）/ **Workflow**（H9：阶段固化+审批闸门）/ **Hybrid**（H9：Workflow 锁边界→Plan 拆解→ReAct 执行）；未注册范式降级 fallback 并记录。
- **终止闸门**：`TerminationGate` 四维硬约束全部生效——maxRounds / maxTokens（H1 后用真实 token usage）/ maxTimeout / maxCallsPerRound；闸门触发优先归因。
- **容错**：`RetryPolicy`(次数上限+退避) → 熔断 → `FallbackStrategy`(降级话术) → 终止。
- **循环检测**：`LoopDetector` 记录最近 N 轮 action/observation 指纹，重复超阈值触发 `LOOP_DETECTED` 终止。
- **断点续跑 + 分布式锁**：`LockingExecutionLoopService`（H4）外层按 taskId 获取分布式锁防并发重入 + `isResumable` 从最近快照恢复。

**落地状态**：T1/T2/T5/T7（校验接入、闸门、续跑、容错）+ H3/H9（PlanAndExecute/Workflow+Hybrid）+ H4（分布式锁）+ H1（真实 Token 计量）——✅ 95%。

**剩余缺口**：学习型层路由（属 RSI，见 v2.0 §4.5.5）；判定式路由/完成度（决策平面，见 v2.0 §4.4.2/§4.4.3）。

### 4.2 T — 工具注册中心

**职责**：统一注册、校验、调度所有来源的工具，是模型与环境的唯一通道。

**核心机制**：

- **九元组注册**：ID / 描述 / 入参 Schema / 出参 Schema / 权限 / 风险 / 白名单 / 限流 / 超时（缺一不可）。
- **四层校验链**（责任链模式）：`WhitelistToolValidator`(白名单准入) → `SchemaToolValidator`(参数/注入/格式强校验) → `PermissionToolValidator`(动态权限) → `SandboxToolValidator`(沙箱隔离执行)。
- **五源统一路由**（`DefaultToolDispatcher.dispatch` → `switch(source)`）：

| 来源 | toolSource | ID 规范 | 网关 |
|------|-----------|---------|------|
| 本地工具 | `LOCAL` | 工具名 | `ToolProvider`→`Tool` |
| SPI 工具 | `SPI` | 业务自定义 | `ToolProviderSPI`/`BizCodeRouter` |
| MCP 远程工具 | `MCP` | `mcp:{server}:{tool}` | `McpClientManager`（四传输 + 热更新 + 熔断，H6） |
| REST API 工具 | `REST_API` | `api:{service}:{operationId}` | `WebClientRestApiToolGateway`（OpenAPI 自动发现 + OAUTH2 + KMS，H7） |
| Skill 高阶能力 | `SKILL` | `skill:{name}` | `DefaultSkillToolGateway`（七步骤编排，H8） |

- **MCP 集成（H6）**：STDIO / SSE / WebSocket / HTTP JSON-RPC 四传输；启动连接+`tools/list` 发现注册 → 心跳保活+断线重连 → `tools/list_changed` 热更新 → 优雅停机；`ServerCircuitBreaker` 按服务端连续失败跳闸隔离，单 server 故障不影响全局。
- **REST API as Tool（H7）**：OpenAPI Spec 自动发现（推荐）/ YAML 手动配置 / 注解声明；凭证托管 `CredentialVault`（BEARER/API_KEY/BASIC/OAUTH2）；SSRF 防护（字节级 IP 段判定）+ 路径白名单 + Schema 强校验。
- **Skill 编排（H8）**：`@SkillDef` 注解声明；七步骤类型 TOOL_CALL / CONDITION / LLM_CALL / LOOP / PARALLEL / SUB_WORKFLOW / SUB_AGENT；`SkillExpressionResolver` 安全最小集（`${input.x}`/`${step.field}` + 六运算符，**不引入脚本引擎杜绝表达式注入**）；全局步数硬上限 `MAX_STEPS=1000` 防环/防嵌套失控。

**落地状态**：T10a/b/c + H6/H7/H8 全部落地——✅ 98%。

**剩余缺口**：工具定义持久化（`t_tool_definition`）写入链路未全接；rateLimitPerMinute 强制、出参 Schema 校验。

### 4.3 C — 上下文管理器

**职责**：结构化治理上下文，防止污染、Token 失控与幻觉。

**核心机制**：

- **四级记忆**：L1 瞬时（轮次销毁）→ L2 会话（摘要裁剪）→ L3 任务（快照绑定）→ L4 知识（向量检索）。
- **双画像**：`UserProfile`（权限/脱敏/偏好）+ `TaskProfile`（风险/工具链/Prompt 模板）。
- **装配与脱敏**：`DefaultContextAssembler` 在 BEFORE/AFTER_CONTEXT_ASSEMBLE 钩子间真实装配并写仓储；`KeywordMaskingContextSanitizer` 关键词脱敏（可插拔）。
- **语义向量（H2）**：`EmbeddingPort`/`VectorStore`/`RerankPort` 端口 + `LlmEmbeddingPort`（M2 走 provider `/embeddings`）+ `EmbeddingRerankPort`（M3 语义 cosine + 词面覆盖混合打分）；`VectorMemoryService` 提供 L4 cosine Top-K 召回 + Token 预算截断 + 可选重排去噪；失败降级本地同维哈希嵌入（P10）。
- **Token 治理**：预算分配制，防止上下文溢出。

**落地状态**：T3 + T14 + H2 落地——✅ 90%。

**剩余缺口**：L4 知识自动蒸馏（属 RSI，见 v2.0 §4.5.3）；Embedding/Rerank 质量随 provider 模型能力。

### 4.4 S — 状态存储库

**职责**：快照、断点续跑、事务回滚、分布式锁，是容灾的根基。

**核心机制**：

- **快照/续跑/回滚**：`StateSnapshot` 每轮写入，`TaskState` 带 `@Version` 乐观锁；`JpaTaskStateRepository` 持久化重建（`langur.repository.type=jpa`）。
- **L2 热层 + 分布式锁（H4）**：`CacheBackend`（带 TTL 的 kv 读写 + 原子自增）+ `DistributedLock`（holder 可重入 + TTL 租约自动过期）；`RedisCacheBackend`/`RedisDistributedLock`（`langur.cache.type=redis`，SETNX+Lua 原子解锁）与 `MemoryCacheBackend`/`MemoryDistributedLock`（memory 默认/测试兜底），任一操作异常静默降级内存后端（P10）。
- **锁机制**：`LockingExecutionLoopService` 统一入口外层按 taskId 获取分布式锁，并发重入即终止、finally 释放（替代原 DB 行级锁语义）。

**落地状态**：T11 + H4 落地——✅ 90%。

**剩余缺口**：跨存储层一致性策略；Redis 生产运维（连接池/哨兵/集群）。

### 4.5 L — 生命周期钩子

**职责**：全流程非侵入安全拦截，是五层防御的执行载体。

**核心机制**：

- **12 拦截点**：BEFORE/AFTER × 6 阶段（Context/Inference/ToolCall/StateSave/Terminate/Output）。
- **四动作**：`HookAction` CONTINUE / ABORT / SKIP / MODIFY（OUTPUT 点支持 MODIFY 改写最终答案 + ABORT 阻断）。
- **安全钩子（H10）**：`PromptInjectionGuardHook`（BEFORE_INFERENCE，注入命中 ABORT）+ `ContentReviewOutputHook`（BEFORE_OUTPUT，涉密/资损/合规命中 ABORT），`langur.security.*.enabled` 配置驱动、自动汇入 `LifecycleHookEngine`。
- **高危审批流（H10）**：`CriticalApprovalValidator`（四层校验链 order=350）对 CRITICAL 工具无审批单则创建 PENDING 挂起（状态 SUSPENDED 可续跑）→ 批准从快照恢复 / 拒绝中断；审批后端缺失 fail-closed。

**落地状态**：T4 + H10 落地——✅ 92%。

**剩余缺口**：运行时动态启停、12 点↔四层防御显式映射校验。

### 4.6 V — 评估观测

**职责**：四维指标、全链路 Trace、不可篡改审计，是生产可观测的闭环。

**核心机制**：

- **四维指标**：`MetricDimension` 模型层/调度层/工具层/安全层；`ExecutionMetrics` 采集。
- **导出后端（H5）**：`MicrometerEvaluationService`（`evaluation=prometheus` 装配，暴露 `/actuator/prometheus`）+ `LoggingEvaluationService`（条件默认）。
- **告警分级（H5）**：`AlertEvaluator` 纯规则引擎 + `AlertRule`（6 规则）+ `AlertLevel`（P0 阻断 / P1 降级+OnCall / P2 终止 / P3 日报），阈值配置驱动，通道失败静默降级（P10）。
- **Trace**：`OtelExecutionTracer` 六类 Span（API/上下文/推理/工具/快照/输出），traceId 从 API 层透传。
- **审计**：`AuditRecord` + `Checksums.sha256` 不可篡改校验；`AuditSink` 归档抽象（默认 `LoggingAuditSink`，MQ/对象存储为挂载点）。

**落地状态**：T12 + H5 落地——✅ 90%。

**剩余缺口**：MQ 上报为挂载点（RocketMQ 未强依赖）；决策维度指标（见 v2.0 §4.8.1）。

### 4.7 LLM 模型矩阵与统一网关

**职责**：按职责分工组织模型矩阵，并通过配置驱动的统一网关完成"一处适配、零代码扩厂"。

**模型矩阵（M1–M6）**：

| 角色 | 职责定位 | 落地状态 |
|------|----------|----------|
| **M1 ROUTING** | 轻量路由/意图识别 | 🟡 规则路由（学习型属 RSI，判定式属决策平面 v2.0） |
| **M2 EMBEDDING** | 文本向量化/记忆写入 | ✅ `LlmEmbeddingPort`（H2，失败降级本地同维哈希） |
| **M3 RERANK** | 检索去噪/重排序 | ✅ `EmbeddingRerankPort`（H2，cosine + 词面覆盖） |
| **M4 ACTION** | 工具调用/Function Call | ✅ 经 `LlmGateway` |
| **M5 REASONING** | 规划/自检/复杂推理 | ✅ `LlmPlanner`（H3 规划拆解） |
| **M6 LONG_CONTEXT** | 长文档/审计 | ✅ 配置就绪 |

**统一模型网关（H13，2026-09-26）**：

- **配置驱动的 Provider 类型工厂**：`ModelProviderFactory` 遍历 `langur.llm.providers.*` 中 `enabled=true` 的条目，按 `type`（`openai-compatible | anthropic | gemini`）实例化适配器模板，产出 `List<ModelRoutableLLMPort>` 供 `LLMRouter` 注入。**新增 OpenAI 兼容厂商 = 纯 YAML，零 Java 代码**（P5/P9）。
- **国内外主流全覆盖**：GPT / Claude / Gemini（国际）+ Qwen / GLM / DeepSeek（国内）；Moonshot、百川、讯飞、阶跃、MiniMax、零一万物等多为 openai-compatible → 零代码扩厂。
- **降级真正生效（H13.4）**：适配器传输/HTTP/解析失败抛 `ModelProviderException`（不再吞成 `"Error: ..."`），`LlmGateway.withFallback` 捕获 → 按 `fallback-chains` 逐级降级；链耗尽抛 `IllegalStateException`。
- **Provider 健康熔断（H13.7）**：`ProviderCircuitBreaker` 每 provider 独立——连续失败跳闸 → 快速跳到降级链下一个 → 冷却半开试探；暴露 `llm_provider_circuit_state` 指标。
- **计量与密钥一致**：`completeWithUsage` 回传 `CompletionResult{content, usage}`（H13.5）；`api-key-ref` 经 `SecretResolver`（`env:/prop:/kms:`）解析，**绝不落日志**，缺 KMS 后端 fail-closed（H13.6）。

**落地状态**：H13.1–H13.7 全部达成（G1 网关统一 + G3 弹性）。

### 4.8 存储分层与可插拔向量库

**职责**：四级存储分层平衡性能与持久化，L4 向量库可插拔 + 原生混合检索。

**四级存储分层**：

| 层级 | 介质 | 数据特征 | 用途 |
|------|------|----------|------|
| L1 | 内存 | 当前轮次热数据 | 执行循环中的瞬时状态 |
| L2 | Redis | 热状态（带 TTL） | 会话状态、分布式锁、限流计数 |
| L3 | MySQL | 持久归档 | 任务状态、快照、工具定义、审计日志（七表） |
| L4 | 向量库 | 静态可信知识 | 知识库语义检索、长期记忆存取 |

**可插拔向量库（H14，2026-09-26）**：

- **端口扩展**：`VectorStore` 增 default 方法 `delete/upsertAll/search(SearchQuery)/supportsHybrid()/hybridSearch()`——既有 `InMemoryVectorStore`/`PgVectorStore` **零改动即编译**（P5 开闭）。
- **四种实现**：`memory`（默认，行为不变）/ `pgvector`（数据源修复 + JDBC batch）/ `ElasticsearchVectorStore`（dense kNN + BM25）/ `MilvusVectorStore`（dense + sparse，BM25 function）。
- **原生混合检索（下推优先，应用侧兜底）**：ES/Milvus 打开 `supportsHybrid()=true` 走原生 hybridSearch；memory/pgvector 走 `search + EmbeddingRerankPort` 应用侧混合（现状路径）。融合算法默认 **RRF**（Reciprocal Rank Fusion，基于排名、免分数归一化）。
- **关键降级（DD20）**：ES `retriever.rrf` 属 Platinum+ 付费层 → 缺省 `native-rrf=false` 走"两查询 + 客户端 RRF 融合"；Milvus RESTful v2 无服务端 Ranker 下推 → 客户端融合。任何原生失败自动降级客户端融合（P10 不中断）。
- **维度一致性守卫（H14.8）**：启动校验 `langur.vector.dimension == EmbeddingPort.dimensions()`，不一致 **fail-fast**，杜绝硬编码 256 与 llm 1536 冲突导致的静默召回损坏。
- **安全**：ES/Milvus/pgvector `password-ref` 全经 `SecretResolver`；`uris/uri` 经 `SsrfGuard`；`SearchFilter` 必带 `namespace` 隔离，杜绝跨租户召回；store 不可用 **fail-fast + 健康降级，绝不静默转内存**（防数据丢失错觉）。

**落地状态**：H14.1–H14.8 全部达成（G2 向量库可插拔）。

### 4.9 SPI 扩展体系

**职责**：框架对扩展开放、对修改关闭（P5），业务通过 SPI 注入，≤3 天完成新业务域对接。

**七大 SPI 扩展点**：

| # | SPI 接口 | 职责 |
|---|---------|------|
| 1 | `BusinessExecutorSPI` | 业务节点执行逻辑 |
| 2 | `PromptTemplateSPI` | 业务专属 Prompt 构建 |
| 3 | `ToolProviderSPI` | 业务工具注册 + 执行 |
| 4 | `SecurityPolicySPI` | 业务安全规则 |
| 5 | `ContextEnricherSPI` | 业务上下文数据加载 |
| 6 | `DecisionEngineSPI` | 业务决策规则匹配（**决策平面的落地接缝**，见 v2.0 §4.1） |
| 7 | `OutputPostProcessorSPI` | 业务输出格式化 |

**BizCode 路由机制**：`AgentRequest.bizCode → BizCodeRouter` 匹配各 SPI 实现 + NodeConfig + RuntimeParadigm。

**业务三步接入法**：① 定义 bizCode → ② 注入工具（ToolProviderSPI 或 MCP/REST API 工具）→ ③ 调用 API（chat / task / stream 三入口）。

### 4.10 安全合规五层防御

| 原则 | 说明 |
|------|------|
| 零信任 | 所有工具调用默认不可信，必须经四层校验 |
| 最小权限 | 基于用户画像动态授予最小工具集 |
| 纵深防御 | 五层拦截，单点突破不致全局失守 |
| 审计全覆盖 | 所有操作不可篡改留痕 |
| 资损零容忍 | 金额相关操作多层校验 + 阻断机制 |

- **输入安全（H10）**：`PromptInjectionDetector` 中英文注入话术四类正则检测（指令覆盖/角色劫持/护栏绕过/系统提示词泄露）。
- **执行安全**：四层校验链 + `runGeneric` 沙箱（守护线程池 + `future.get(timeout)` 硬超时熔断）。
- **输出安全（H10）**：`OutputContentReviewer` 涉密/资损/合规三类审核。
- **审计安全**：checksum 不可篡改 + `AuditSink` 归档。

### 4.11 设计原则总览（P1–P11）

| # | 原则 | 约束力 | 落地 |
|---|------|--------|------|
| P1 | 领域核心不可侵犯（Domain 零外部依赖） | 强制 | ✅ ArchUnit 守护 |
| P2 | 严格单向依赖 | 强制 | ✅ |
| P3 | 依赖倒置 | 强制 | ✅ |
| P4 | 六组件正交 | 强制 | ✅ |
| P5 | SPI 开闭原则 | 强制 | ✅ |
| P6 | 安全纵深防御 | 强制 | ✅（H10 补输入/输出/审批） |
| P7 | 可观测优先（无观测=未上线） | 强制 | ✅（H5 补 Prometheus + 告警 + 归档） |
| P8 | 渐进式演进 | 推荐 | ✅ |
| P9 | 配置化驱动 | 推荐 | ✅（H13/H14 全程配置驱动） |
| P10 | 降级兜底（外部依赖失败主链路不中断） | 强制 | ✅ |
| P11 | 自演进可控（RSI 产物默认候选，回放+灰度+审批方可生效） | 强制 | 见 v2.0 §4.5 |

> **P12（判定/生成分离 + 判定可回放可降级 + 对安全闸门恒 advisory）** 为决策平面的专属红线，见 v2.0 §4.10。

---

## 五、价值收益

### 5.1 工程价值

| 维度 | 收益 |
|------|------|
| **确定性调度** | 大模型的概率性输出被确定性底座约束（权限/预算/合规/可回滚），生产可托底 |
| **可复用底座** | 六组件 + 三层 Runtime + 五源工具 + 七 SPI，业务方 ≤3 天接入新业务域 |
| **零代码扩厂** | 新增 OpenAI 兼容模型厂商、切换向量库均为纯 YAML 配置（P9） |
| **可插拔架构** | 所有外部依赖（LLM/向量库/缓存/审批后端）经端口倒置，可替换、可降级（P3/P10） |
| **架构红线守护** | ArchUnit 编译期守护 domain 纯度、单向依赖、分层无环（P1/P2/P4） |

### 5.2 业务价值

| 维度 | 收益 |
|------|------|
| **安全可控** | 五层纵深防御 + 四层校验链 + 高危审批，资损/泄密风险出厂即拦截 |
| **可观测可审计** | 四维指标 + 六类 Span Trace + checksum 审计，任何一次执行可溯源"为何走了这条路" |
| **高可用** | 快照 + 断点续跑 + 分布式锁，崩溃恢复 <5s，零任务丢失 |
| **多范式适配** | Workflow（强合规）/ PlanAndExecute（长任务）/ ReAct（开放对话）/ Hybrid（全场景） |

### 5.3 演进价值（RSI 递归自演进指引）

> 本底座之上，Langur 已演进出**第三能力面**：递归自演进（RSI）+ 判定式决策平面。本节仅给出立场与指引，完整设计见 [v2.0](Agent-Harness架构设计-v2.0.md)。

**RSI（Recursive Self-Improvement，递归自演进）**：Agent 系统利用自身运行产生的执行数据（轨迹/指标/审计/记忆），在无需人工重新工程的前提下，持续改进自身的 Prompt、技能、工具集、路由策略与超参，并随改进能力提升而增强"改进能力"本身（递归）。

**核心立场（Langur 的差异化）**：RSI 的最大风险是"自我改进 = 自我失控"。Langur 的独特价值在于——**RSI 运行在 Harness 的确定性调度与五层防御之上**，自我改进的每个产物都是"候选提案"，必须经离线回放验证 + 灰度 + 审批 + 可回滚审计链才能生效。Harness 既是 RSI 的**使能器**（提供感知与执行器官），也是 RSI 的**约束器**（提供安全平面）。

**六阶段元循环**：`Observe（V/S/C 采集）→ Evaluate（四维指标 + 失败模式）→ Propose（M5 生成候选）→ Validate（反事实回放 + 灰度 + 审批）→ Apply（SPI 热插拔）→ Monitor（基线对比 + 自动回滚）`。

**成熟度分级 L0–L6**：人工调优 → 反思自检 → 记忆自蒸馏 → 技能自合成（枢纽）→ 策略自优化 → 工具自扩展 → 架构自演进。当前已落地 R0–R5/R-G（见 v2.0 §4.5）。

### 5.4 落地度总评

| 指标 | 数值 |
|------|------|
| 六组件骨架落位 | **100%** |
| 主链路贯通度 | **~95%**（v1.0 蓝图 ~40% → H1–H10 后 ~95%） |
| 成熟度里程碑 | G1 网关统一 ✅ / G2 向量库可插拔 ✅ / G3 弹性 ✅（2026-09-26） |
| 测试规模 | **320 → 419 全绿**（domain 121 + infra 271 + start 27，H13/H14 后） |
| 应用启动 | ~3.0s，`/actuator/health` UP |

> **下一能力面**：本文交付"让 Agent 安全可控地跑起来"的确定性底座；[v2.0](Agent-Harness架构设计-v2.0.md) 交付"让 Agent 系统用自身数据持续改进自身"的判定式决策平面 + RSI 整合——**模型写、决策判、Harness 管、RSI 进化**。
