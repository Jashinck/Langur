# Agent-Harness 架构设计 v2.0

---

# Agent-Harness 框架整体架构设计方案 v2.0（As-Built + 演进蓝图）

> **文档性质**: 实现现状快照（As-Built Snapshot）+ 递归自演进（RSI）蓝图
> **版本**: v2.0 | **日期**: 2026-09-25 | **取代**: v1.0（2026-08，纯设计蓝图，保留作历史基线）
> **定位**: 框架级通用架构，不绑定具体业务；v2.0 以**真实代码为准**校准 v1.0 蓝图，并新增 RSI 能力面与演进路线图
> **核心公式**: `Agent = LLM推理能力 + Harness确定性调度能力 (+ RSI 递归自演进能力)`
> **本次刷新（2026-09-25）**: As-Built 矩阵已按 **RoadMap 2.0 H1–H10 全部完成并提交**态校准（此前版本为 H1–H10 落地之前的快照，多处 🟡/⬜ 缺口已闭合）；**320 测试全绿**（domain 101 + infra 202 + start 17）。
> **后续增量（v3.0）**: 在 v2.0 As-Built 基线之上**新增"决策平面（Decision Plane / Jev）"能力面**并与 RSI 整合（任务线 J1–J10 + 承接 R0–R5/R-G）；v2.0 六组件拓扑（P4）与 As-Built 结论**继续有效**，v3.0 只增量描述新能力面。
> **同级增量（v2.1）**: 硬化本基线**两处既有接缝**——§6「LLM 供给」升级为**配置驱动的统一模型网关**（国内外主流大模型一处适配，补 GLM），§12「存储分层 L4」升级为**可插拔向量库 + 原生混合检索下推**（补 Elasticsearch/Milvus）；任务线 H13/H14。**与 v3.0 正交**（H 类基础设施 vs J/R 决策平面），同坐落本 v2.0 基线，六组件拓扑（P4）不变。

---

## 0. v2.0 变更摘要（相对 v1.0）

| 变更类型 | 说明 |
|----------|------|
| **现状校准** | 六组件逐条标注 as-built 状态（✅ 已实现 / 🟡 部分实现 / ⬜ 未实现），修正 v1.0 与代码不符处 |
| **技术选型修正** | ORM 实为 **Spring Data JPA**（非 MyBatis-Plus，见 ADR D1 复核）；服务端口 **8081**；LLM 网关 `LlmGateway` 落地 |
| **工具体系补全** | 五类工具源（LOCAL/SPI/REST_API/**MCP**/**SKILL**）全部落地并接入四层校验链（T10a/b/c 完成） |
| **新增 §15 RSI** | 递归自演进能力架构：Observe→Evaluate→Propose→Validate→Apply→Monitor 闭环 + 成熟度分级 + 安全护栏 |
| **新增 §16 路线图** | Harness 补强 12 项 + RSI 演进 6 级，按优先级/依赖排序 |
| **新增原则 P11** | 自演进可控（RSI 产物默认候选，经回放+灰度+审批方可生效，可回滚） |
| **H1–H10 落地刷新（2026-09-25）** | 本版 As-Built 按 RoadMap 2.0 **H1–H10 全部完成**态校准：多范式引擎（H3 PlanAndExecute / H9 Workflow+Hybrid）、Redis 热层+分布式锁（H4）、语义 Embedding+Rerank（H2）、真实 Token 计量（H1）、可观测导出+告警分级（H5）、五层防御输入/输出/高危审批（H10）、MCP 四传输（H6）、REST OpenAPI/OAUTH2/KMS（H7）、Skill 七步骤（H8）均已落地 |

**实现完成度总评**：六组件骨架 **100%** 落位；主链路贯通度 v1.0 ~40% → v2.0 初评 ~85% → **H1–H10 完成后 ~95%**；**320 测试全绿**（domain 101 + infra 202 + start 17），应用启动 ~3.0s。剩余缺口集中在：**H11 依赖治理、H12 多 Agent、RSI（R0–R5/R-G，暂停）、决策平面（Jev，见 v3.0 / RoadMap 3.0）**。

---

## 1. 框架定位与愿景

### 1.1 产品定位（沿用 v1.0）

Agent-Harness 是企业级 Agent 通用脚手架，作为大模型通往生产环境的**确定性调度底座**。Harness 是部署在**大模型推理层**与**外部业务环境**之间的确定性调度中间件——本质是大模型的操作系统与安全沙箱层。

### 1.2 两大强制隔离原则（不可违背）

| 原则 | 定义 | 工程约束 | as-built |
|------|------|----------|----------|
| **模型与环境隔离** | LLM 无法直连任何外部资源 | 所有工具调用经 T 组件校验 + 沙箱执行 | ✅ `DefaultToolDispatcher` 无直调路径 |
| **推理与治理隔离** | 模型仅负责推理决策 | 权限/合规/风控/审计下沉 Harness | ✅ 四层校验链 + 治理 Filter + 审计 |

### 1.3 v2.0 愿景扩展：从"确定性调度"到"可控自演进"

v1.0 解决"让 Agent 安全可控地跑起来"；v2.0 引入第三支柱——**在 Harness 的安全平面之上，让 Agent 系统用自身产生的执行数据持续改进自身（RSI）**。关键立场：**RSI 不是脱离 Harness 的自由自我修改，而是 Harness 治理下的受控闭环**——自我改进的每一个产物（Prompt/Skill/工具/参数/路由策略）都必须经确定性调度底座的验证与审批才能生效。详见 §15。

---

## 2. 实现现状总览（As-Built）

### 2.1 六组件落地矩阵

| 组件 | 设计目标 | 落地度 | 已实现核心类 | 主要缺口 |
|------|----------|--------|--------------|----------|
| **E** 执行循环 | 三范式 + 终止闸门 + 容错 + 循环检测 | ✅ 95% | `ReActExecutionLoop` `PlanAndExecuteExecutionLoop`(H3) `WorkflowExecutionLoop`+hybrid(H9) `ParadigmDispatchingExecutionLoop` `TerminationGate`(四维) `LoopDetector` `RetryPolicy` `DefaultLayerRouter` | 学习型层路由（R4，RSI 暂停）；判定式路由/完成度（决策平面 Jev，v3.0） |
| **T** 工具中心 | 九元组 + 四层校验 + 五源统一路由 | ✅ 98% | `ToolDefinitionEntity` `DefaultToolDispatcher` 4×`ToolValidator` `InMemoryToolRegistry` + rest/mcp/skill 三网关（MCP 四传输 H6 / OpenAPI 自动发现+OAUTH2+KMS H7 / Skill 七步骤 H8） | 工具定义持久化(t_tool_definition)写入链路未全接；rateLimitPerMinute 强制、出参 Schema 校验 |
| **C** 上下文 | 四级记忆 + 双画像 + Token 治理 + 脱敏 + 向量 | ✅ 90% | `AgentContext` `UserProfile`/`TaskProfile` `MemoryEntry`/`MemoryLevel` `DefaultContextAssembler` `KeywordMaskingContextSanitizer` `VectorMemoryService` `LlmEmbeddingPort`(M2)+`EmbeddingRerankPort`(M3)+`RerankPort`(H2) | L4 知识自动蒸馏属 R2（RSI，暂停）；Embedding/Rerank 质量随 provider 模型能力 |
| **S** 状态存储 | 快照 + 断点续跑 + 回滚 + 分布式锁 | ✅ 90% | `TaskState`(乐观锁 version) `StateSnapshot` `JpaTaskStateRepository` `InMemoryTaskStateRepository` 断点续跑+acquireLock `CacheBackend`/`DistributedLock`+`Redis*`/`Memory*`实现+`LockingExecutionLoopService`(H4) | 跨存储层一致性策略；Redis 生产运维（条件装配 memory 兜底 P10） |
| **L** 生命周期钩子 | 12 拦截点 + 四动作 + 四层防御 | ✅ 92% | `LifecycleHookEngine` `HookPoint`(12) `HookAction`(CONTINUE/ABORT/SKIP/MODIFY) `LifecycleHook` `HookContext`/`HookResult` + 安全钩子 `PromptInjectionGuardHook`/`ContentReviewOutputHook`(H10) | 运行时动态启停、12 点↔四层防御显式映射校验待补 |
| **V** 评估观测 | 四维指标 + Trace + 不可篡改审计 | ✅ 90% | `EvaluationService`(`MicrometerEvaluationService`+`LoggingEvaluationService`) `ExecutionMetrics`/`MetricDimension` `AuditRecord` `Checksums`(SHA-256) `OtelExecutionTracer`(六类 Span) `AlertEvaluator`/`AlertRule`(P0-P3)+`AuditSink`(H5) | MQ 上报为 `AuditSink` 挂载点（RocketMQ 未强依赖 DD4）；决策维度指标（Jev，v3.0） |

### 2.2 支撑能力落地矩阵

| 能力 | 落地度 | 说明 |
|------|--------|------|
| SPI 七大扩展点 + BizCode 路由 | ✅ | common 定义 7 SPI 契约；`DefaultBizCodeRouter` 按 bizCode 索引 + default 兜底 |
| LLM 模型矩阵 M1-M6 + 网关 | ✅ | `ModelRole` `LlmGateway`(角色→模型 + 主备降级链)；**真实 token usage 已回传**（H1，`TokenUsage`）；M2 Embedding/M3 Rerank 已接（H2）；M1 路由仍规则式（学习型属 R4） |
| SSE 流式双入口 | ✅ | `stream/chat` `stream/task`（H3 后 `stream/task` 真实走 PlanAndExecute 拆解）；`LLMPort.streamComplete` 回调式 + `TaskProgressBus` 先注册后执行 |
| API 治理链 | ✅ | `GovernanceFilter`：鉴权→租户→限流→幂等→TraceId(MDC)；`CacheRateLimiter`/`CacheIdempotencyStore`（H4，多实例共享，memory/redis 条件装配） |
| 存储分层（MySQL 七表 + pgvector + Redis） | ✅ | JPA 七表实体齐全；`PgVectorStore`(HNSW+cosine)/`InMemoryVectorStore`；**Redis L2 热层 + 分布式锁**（H4，`langur.cache.type=redis\|memory`） |
| 断点续跑 + 并发锁 | ✅ | `TaskState.acquireLock` 防重入 + `isResumable` 从最近快照 round/iteration 恢复；`LockingExecutionLoopService` 外层分布式锁（H4） |

---

## 3. Harness 六组件 As-Built 详解

### 3.1 标准架构 H=(E,T,C,S,L,V)（沿用 v1.0 拓扑）

```
┌───────────────────────────────────────────────────────────────────────┐
│                    L — Lifecycle Hooks (12 拦截点全流程贯穿)             │  ✅
├───────────────────────────────────────────────────────────────────────┤
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     │
│  │    C     │────▶│    E     │────▶│    T     │────▶│    S     │     │
│  │ Context  │✅   │Execution │✅   │   Tool   │✅   │  State   │✅   │
│  │ Manager  │◀────│  Loop    │◀────│ Registry │     │  Store   │     │
│  └──────────┘     └──────────┘     └──────────┘     └──────────┘     │
│                         │                                              │
│                  ┌──────▼──────┐        ┌─────────────────────────┐   │
│                  │      V      │✅      │  RSI 自演进闭环 (§15)    │⬜  │
│                  │ Evaluation  │───────▶│  Observe→Evaluate→Propose│   │
│                  └─────────────┘  反馈  │  →Validate→Apply→Monitor │   │
│                                          └─────────────────────────┘   │
└───────────────────────────────────────────────────────────────────────┘
```

> RSI 闭环（虚线，规划中）以 V/S/C 为感知器官、以 SPI 为执行器官，是六组件之上的**元循环**，而非第七个正交组件——遵守 P4 六组件正交原则。

### 3.2 E 执行循环器（✅）

- **运行范式（四范式全落地）**：`ParadigmDispatchingExecutionLoop` 按 `RuntimeParadigm` 分发到 **ReAct**（`ReActExecutionLoop`）/ **PlanAndExecute**（`PlanAndExecuteExecutionLoop`，H3：规划拆解→逐步委派 ReAct 子循环→动态重规划）/ **Workflow**（`WorkflowExecutionLoop`，H9：阶段固化+审批闸门）/ **Hybrid**（hybrid 循环，H9：Workflow 锁边界→Plan 拆解→ReAct 执行）；未注册范式仍降级 fallback 并记录（T6）。
- **终止闸门**：`TerminationGate` 四维硬约束全部生效——maxRounds / maxTokens（**H1 后用真实 token usage**）/ maxTimeout(`exceedsTimeout`) / maxCallsPerRound；闸门触发优先归因（T2）。
- **容错**：`RetryPolicy`(次数上限+退避) → 熔断 → `FallbackStrategy`/`DefaultFallbackStrategy`(降级话术) → 终止（T7）。
- **循环检测**：`LoopDetector` 记录最近 N 轮 action/observation 指纹，重复超阈值触发 `LOOP_DETECTED` 终止（T7）。
- **断点续跑 + 分布式锁**：`LockingExecutionLoopService`（H4）外层按 taskId 获取分布式锁防并发重入 + `isResumable` 从最近快照恢复（T5）。
- **缺口**：学习型层路由（R4，属 RSI，暂停）；判定式路由/完成度/卡死检测（决策平面 Jev，见 v3.0 §3.2/§3.3）。

### 3.3 T 工具注册中心（✅）

- **九元组**：`ToolDefinitionEntity`（ID/描述/入参 Schema/出参 Schema/权限/风险/白名单/限流/超时 + source）。
- **四层校验链**（责任链，按 `order()` 排序）：`WhitelistToolValidator`(100) → `SchemaToolValidator`(200) → `PermissionToolValidator`(300) → `SandboxToolValidator`(400)。
- **五源统一路由**（`DefaultToolDispatcher.dispatch` → `switch(source)`）：
  - `LOCAL`/`SPI` → 既有 `Tool` 执行器（`ToolProvider`）
  - `REST_API` → `WebClientRestApiToolGateway`（URL 模板 + 凭证注入 + SSRF 防护）
  - `MCP` → `McpClientManager`（连接/发现/心跳/重连）
  - `SKILL` → `DefaultSkillToolGateway`（多步编排，内部回派调度器形成嵌套校验）
- **沙箱**：`runGeneric` 基于守护线程池 + `future.get(timeout)` 硬超时熔断。
- **可选注入**：三网关 + tracer + evaluationService 均 `@Autowired(required=false)`，未装配时对应源安全兜底。
- **缺口**：t_tool_definition 持久化写入链路、rateLimitPerMinute 强制、出参 Schema 校验。

### 3.4 C 上下文管理器（✅）

- **四级记忆**：`MemoryLevel` L1 瞬时 / L2 会话 / L3 任务 / L4 知识；`VectorMemoryService` 提供 L4 cosine Top-K 召回 + Token 预算截断。
- **双画像**：`UserProfile`(权限/脱敏/偏好) + `TaskProfile`(风险/工具链/Prompt)。
- **装配与脱敏**：`DefaultContextAssembler` 在 BEFORE/AFTER_CONTEXT_ASSEMBLE 钩子间真实装配并写仓储；`KeywordMaskingContextSanitizer` 关键词脱敏（可插拔）。
- **向量存储 + 语义化（H2）**：`EmbeddingPort`/`VectorStore`/`RerankPort` 端口 + `PgVectorStore`(pgvector HNSW)/`InMemoryVectorStore`；`LlmEmbeddingPort`(M2，走 provider `/embeddings`，维度配置化 DD7) + `EmbeddingRerankPort`(M3，语义 cosine + 词面覆盖混合打分)，`VectorMemoryService` 召回后可选重排去噪；失败降级本地同维哈希嵌入（P10）。
- **缺口**：L4 知识自动蒸馏属 R2（RSI，暂停）；Embedding/Rerank 质量随 provider 模型能力；`LexicalEmbeddingPort` 保留为无外部依赖的测试/降级实现。

### 3.5 S 状态存储库（✅）

- **快照/续跑/回滚**：`StateSnapshot` 每轮写入，`TaskState` 带 `@Version` 乐观锁；`JpaTaskStateRepository` 持久化重建（`langur.repository.type=jpa`）。
- **L2 热层 + 分布式锁（H4）**：`CacheBackend`（带 TTL 的 kv 读写 + 原子自增，承载 L2 会话热状态/限流计数/幂等键）+ `DistributedLock`（holder 可重入 + TTL 租约自动过期）；`RedisCacheBackend`/`RedisDistributedLock`（`langur.cache.type=redis`，SETNX+Lua 原子解锁）与 `MemoryCacheBackend`/`MemoryDistributedLock`（memory 默认/测试兜底），任一操作在模板缺失/异常时静默降级内存后端（P10）。
- **锁**：`LockingExecutionLoopService` 统一入口外层按 taskId 获取分布式锁，并发重入即终止、finally 释放（替代原 DB 行级锁语义）。
- **缺口**：跨存储层一致性策略；Redis 生产运维（连接池/哨兵/集群）。

### 3.6 L 生命周期钩子（✅）

- **12 拦截点**：BEFORE/AFTER × {CONTEXT_ASSEMBLE, INFERENCE, TOOL_CALL, STATE_SAVE, TERMINATE, OUTPUT}（`HookPoint` 已确认 12 项）。
- **四动作**：`HookAction` CONTINUE / ABORT / SKIP / MODIFY（OUTPUT 点支持 MODIFY 改写最终答案 + ABORT 阻断，T4）。
- **安全钩子（H10）**：`PromptInjectionGuardHook`（BEFORE_INFERENCE，注入命中 ABORT）+ `ContentReviewOutputHook`（BEFORE_OUTPUT，涉密/资损/合规命中 ABORT），`langur.security.*.enabled` 配置驱动、自动汇入 `LifecycleHookEngine`——五层防御的输入/输出层由此落到钩子点。
- **缺口**：运行时动态启停、四层纵深防御 ↔ 12 点的显式覆盖校验。

### 3.7 V 评估观测（✅）

- **四维指标**：`MetricDimension` 模型层/调度层/工具层/安全层；`ExecutionMetrics` 采集。
- **导出后端（H5）**：`MicrometerEvaluationService`（`evaluation=prometheus` 装配，四维数值→DistributionSummary、枚举/字符串→Counter）暴露 `/actuator/prometheus`；`LoggingEvaluationService` 为条件默认（`evaluation=logging`）。
- **告警分级（H5）**：domain 纯规则引擎 `AlertEvaluator` + `AlertRule`（6 规则）+ `AlertLevel`(P0 阻断/P1 降级+OnCall/P2 终止/P3 日报) + `AlertChannel`（通道失败静默降级 P10），阈值由 `langur.observability.alert.*` 配置驱动。
- **Trace**：`OtelExecutionTracer` 六类 Span（API/上下文/推理/工具/快照/输出），traceId 从 API 层透传（T12）。
- **审计**：`AuditRecord` + `Checksums.sha256` 不可篡改校验，接入工具调用与钩子拦截；`AuditSink` 归档抽象（默认 `LoggingAuditSink`，MQ/对象存储为挂载点）。
- **缺口**：MQ 上报为 `AuditSink` 挂载点（RocketMQ 未强依赖 DD4）；审计长周期分区/冷存策略；决策维度指标（Jev，见 v3.0 §6.1）。

---

## 4. DDD 六模块 As-Built

### 4.1 模块与真实包结构（Maven Artifact `org.skylark.langur:*`，Spring Boot 3.2.0 / Java 17）

| 模块 | 根包 | 依赖约束 | as-built |
|------|------|----------|----------|
| langur-common | `*.common`(`spi`/`exception`) | 仅 lombok，零 Spring | ✅ 7 SPI + BizContext + SpiToolSpec + 异常体系 |
| langur-domain | `*.domain`(`harness.*`/`model.*`/`port.*`) | 仅 common + lombok | ✅ 六组件领域模型 + 端口 + `ReActExecutionLoop` |
| langur-application | `*.application`(`service`/`stream`/`command`/`dto`/`assembler`) | 依赖 domain | ✅ 用例编排 + 流式应用服务 + 进度总线 |
| langur-api | `*.api`(`rest`/`dto`/`governance`/`assembler`) | 依赖 application | ✅ 三入口 + 治理链 + 全局异常 |
| langur-infrastructure | `*.infrastructure`(`harness.*`/`llm`/`persistence`/`spi`) | 实现 domain 端口 | ✅ 工具三源网关 + 向量 + JPA + OTel + LLM 网关 |
| langur-start | `*.langur`(`config`/`LangurApplication`) | 装配 | ✅ `HarnessConfiguration` Bean 织入，端口 8081 |

### 4.2 依赖方向（强制单向，已验证）

```
start → api → application → domain ← infrastructure
                              ↑
                        common (全模块依赖)
```

> **CI 治理提示（v2.0 新增）**：domain 零外部依赖红线当前靠约定维持，建议引入 ArchUnit 编译期断言（见 §16 H11）。

---

## 5. 三层混合 Runtime As-Built（✅ 四范式全落地）

| 层级 | 范式 | 设计职责 | as-built |
|------|------|----------|----------|
| 顶层 | Workflow | 强合规/审批，Harness 全权 | ✅ `WorkflowExecutionLoop`（H9：阶段序列固化、审批闸门挂起-批准-续跑、阶段失败即中断无重规划） |
| 中层 | PlanAndExecute | 全局拆解/进度管控 | ✅ `PlanAndExecuteExecutionLoop`（H3：`Planner` 拆解→逐步委派 ReAct 子循环→进度回报→动态重规划；`stream/task` 名副其实） |
| 底层 | ReAct | 细粒度工具推理 | ✅ `ReActExecutionLoop` |
| 混合 | Hybrid | Workflow 锁边界→Plan 拆解→ReAct 执行 | ✅ hybrid 循环（H9：Workflow 阶段委派 PlanAndExecute，三层分权；`RuntimeLayer.HYBRID_LAYER`） |

**分发**：`ParadigmDispatchingExecutionLoop`（`@Primary`）按 `RuntimeParadigm` 分发到对应循环，未注册范式降级 fallback；`DefaultLayerRouter` 规则路由（强合规 bizCode / 多步特征 → WORKFLOW/HYBRID/PLAN，学习型路由属 R4）。**演进三阶段（纯 ReAct → PlanAndExecute → Workflow+Hybrid）已全部落地**。

---

## 6. LLM 模型矩阵与网关 As-Built（✅）

| 角色 | 职责 | as-built |
|------|------|----------|
| M1 ROUTING | 轻量路由/意图 | 🟡 配置就绪，`DefaultLayerRouter` 为规则路由（学习型属 R4；判定式属决策平面 Jev/v3.0） |
| M2 EMBEDDING | 向量化 | ✅ `LlmEmbeddingPort` 走 provider `/embeddings`（H2，维度配置化 DD7，失败降级本地同维哈希） |
| M3 RERANK | 检索重排 | ✅ `EmbeddingRerankPort`（H2，语义 cosine + 词面覆盖混合打分，确定性可离线测） |
| M4 ACTION | 工具调用/Function Call | ✅ 经 `LlmGateway`；Skill LLM_CALL 步骤亦复用（H8） |
| M5 REASONING | 规划/自检 | ✅ `LlmPlanner`（H3 规划拆解）；**RSI 反思/提案的关键角色**（见 §15） |
| M6 LONG_CONTEXT | 长文档/审计 | ✅ 配置就绪 |

- **网关**：`LlmGateway` 统一角色→模型解析 + 主备降级链（`fallbackChains`），同步/决策/流式三模式。
- **真实 token usage（H1）**：`LLMDecision` 增 `TokenUsage(prompt/completion/total)` 值对象，各适配器解析响应 `usage` 回填；`ReActExecutionLoop` 优先用真实 usage 调 `addTokens`，缺失降级字符估算并标注来源（realTokenRounds/estimatedTokenRounds）。
- **缺口**：M1 学习型路由（R4，RSI 暂停）；判定式决策（路由/完成度/评分）由决策平面 Jev 承载（v3.0，非 LLM 生成）。

---

## 7. 统一工具体系 As-Built（✅ 五源全落地）

### 7.1 五类来源与工具 ID 规范

| 来源 | toolSource | ID 规范 | 网关 | as-built |
|------|-----------|---------|------|----------|
| 本地 | `LOCAL` | 工具名 | `ToolProvider`→`Tool` | ✅ |
| SPI | `SPI` | 业务自定义 | `ToolProviderSPI`/`BizCodeRouter` | ✅ |
| REST API | `REST_API` | `api:{service}:{operationId}` | `WebClientRestApiToolGateway` | ✅ YAML 手动配置 |
| MCP | `MCP` | `mcp:{server}:{tool}` | `McpClientManager` | ✅ HTTP JSON-RPC |
| Skill | `SKILL` | `skill:{name}` | `DefaultSkillToolGateway` | ✅ TOOL_CALL/CONDITION |

### 7.2 MCP 集成（T10b ✅ + H6 传输补全 ✅）

- **生命周期**：`@PostConstruct` 连接 + `tools/list` 发现注册 → 心跳 `ping` 保活 → 断线懒重连 → `@PreDestroy` 停机。
- **四传输（H6）**：`McpTransport` 抽象 + `HttpMcpTransport`(JSON-RPC) / `StdioMcpTransport`(进程管道) / `SseMcpTransport`(真流式 text/event-stream) / `WebSocketMcpTransport`(JDK `java.net.http`)，均复用 `SsrfGuard` + `CredentialVault`；`AbstractCorrelatingTransport` 按请求 id 关联出入站。
- **热更新 + 熔断（H6）**：订阅 `tools/list_changed` 差量注册/注销（`McpToolCatalog.unregister`/`byServer`）；`ServerCircuitBreaker` 按服务端连续失败跳闸 + 冷却 + 半开试探，单 server 故障隔离不影响全局。
- **缺口**：远端高级鉴权（OAuth 设备流等）；跨 server 工具命名冲突治理细化。

### 7.3 REST API as Tool（T10a ✅ + H7 自动发现/OAUTH2/KMS ✅）

- YAML 手动配置注册 + `WebClientRestApiToolGateway`（URL 模板占位符 + query/body 路由）+ `SsrfGuard`（字节级 IP 段判定）+ `InMemoryCredentialVault`（BEARER/API_KEY/BASIC/**OAUTH2**）。
- **OpenAPI 自动发现（H7）**：`OpenApiToolImporter`（遍历 paths→方法→操作合成 `RestApiToolSpec` + JSON Schema）+ `OpenApiSpecLoader`（内联/classpath/远端三来源，JSON/YAML 自动识别，YAML `SafeConstructor` 防 gadget，远端经 `SsrfGuard`），经 `RestApiToolRegistrar` 编排（单来源失败降级不阻断 P10）。
- **OAUTH2 + KMS（H7）**：`OAuth2TokenManager`（client_credentials 令牌缓存 + skew 提前刷新）+ `CompositeSecretResolver`（`env:`/`prop:`/`kms:` 三 scheme，kms 缺失 fail-closed 绝不静默回退明文）；`Credential` 不生成 toString 防日志泄露。
- **缺口**：OpenAPI 3.1 复杂 schema（组合/多态）解析；凭证轮换的自动失效重取。

### 7.4 Skill 编排（T10c ✅ + H8 步骤补全 ✅）

- `@SkillDef` 注解 + `Skill` 契约；`SkillExecutor` 递归 `runSteps`，**TOOL_CALL 回派 `ToolDispatcher`（嵌套工具同受四层校验链）**，CONDITION 支持跳转/END，**全局步数硬上限（MAX_STEPS=1000）防环/防嵌套失控**。
- **七步骤类型（H8）**：`StepType` 由 2 类扩至 7 类——TOOL_CALL / CONDITION / **LLM_CALL**（经 `SkillLlmPort` 调 M4/M5）/ **LOOP**（退出条件 + `maxIterations` 双约束）/ **PARALLEL**（守护线程池并发 + 按序汇聚，`${par.0}` 下标引用）/ **SUB_WORKFLOW**（回派 `skill:<ref>` 嵌套，保留校验链）/ **SUB_AGENT**（经 `SubAgentInvoker` 委派，为 H12 铺路）；接缝缺失时对应步骤抛明确 `SkillExecutionException`，其余步骤仍可执行。
- `SkillExpressionResolver`：安全最小集——`${input.x}`/`${step.field}`（含 JSON 字段/List 下标导航）+ 六种比较运算符，**不引入脚本引擎，杜绝表达式注入**。
- **缺口**：语义条件判定（如"这段上下文是否足以转译 PRD"）——由决策平面 `DECISION` 步骤补齐（Jev，见 v3.0 §3.4）。

---

## 8. 全链路执行时序 As-Built（✅ 主链路贯通）

```
═══ 启动阶段 ═══
[Start] McpClientManager.init() → 连接/发现 → 注册[T]（默认关闭空转）
        SkillRegistrar.register() → 扫描 @SkillDef → 注册[T]
        RestApiToolRegistrar.register() → YAML 装载 → 注册[T]

═══ 请求阶段 ═══
Client → [API] GovernanceFilter(鉴权/租户/限流/幂等/TraceId→MDC)
       → [APP] AgentApplicationService.dispatch() → LayerRouter.route() → ParadigmDispatchingExecutionLoop 按范式分发（ReAct / PlanAndExecute(H3) / Workflow / Hybrid(H9)，未注册范式降级 fallback）
       → [L] BEFORE_CONTEXT_ASSEMBLE
       → [C] DefaultContextAssembler(画像+记忆+向量召回) → 脱敏 → Token 治理
       → [L] AFTER_CONTEXT_ASSEMBLE
       → [S] 断点恢复检测(acquireLock + isResumable)
       → [E] ReActExecutionLoop:
           ┌─→ [L] BEFORE_INFERENCE → LlmGateway 推理 → [L] AFTER_INFERENCE
           │   解析 tool_calls → [T] dispatch(四层校验 + 沙箱 + 五源路由)
           │   [L] BEFORE/AFTER_TOOL_CALL → 重试/降级/循环检测
           │   [S] 本轮快照 → [V] TOOL Span + checksum 审计
           │   [E] TerminationGate 四维判断
           └── 未终止→下一轮 / 终止→退出
       → [V] 指标 + Trace 结束
       → [L] BEFORE_OUTPUT(MODIFY/ABORT) → AFTER_OUTPUT
       → [API] Result<T> 响应（流式入口：message/progress → summary → done）
```

---

## 9. 安全合规 As-Built（✅）

- **五层纵深防御（H10 补齐输入/输出/审批）**：接入(`GovernanceFilter` ✅) / 输入(脱敏 ✅、**Prompt 注入拦截 ✅** `PromptInjectionDetector`+`PromptInjectionGuardHook`) / 执行(四层校验链+沙箱 ✅) / 输出(BEFORE_OUTPUT 合规钩子 ✅、**内容审核 ✅** `OutputContentReviewer`+`ContentReviewOutputHook` 涉密/资损/合规) / 审计(checksum ✅、**归档 ✅** `AuditSink` H5)。
- **高危审批流（H10）**：`CriticalApprovalValidator`（四层校验链 order=350）对 CRITICAL 工具无审批单则创建 PENDING 挂起（状态 SUSPENDED 可续跑）→ 批准从快照恢复 / 拒绝中断；审批后端缺失 fail-closed；以 taskId 为关联键跨 resume 稳定。
- **SSRF 防护**：`SsrfGuard` 覆盖 REST + MCP + OpenAPI 远端 spec（H7）+ 决策平面 base-url（v3.0）。
- **缺口**：审计归档 MQ/对象存储后端为挂载点（DD4）；决策平面语义审核补充信号（Jev，v3.0 §3.5，恒 advisory 只收紧不放松）。

---

## 10. 可观测 As-Built（✅）

- 四维指标模型 + OTel 六类 Span + checksum 审计已落地；**导出后端已补齐（H5）**：Prometheus `/actuator/prometheus`（`MicrometerEvaluationService`）+ 告警分级 `AlertEvaluator`(P0-P3) → `AlertChannel`（`LoggingAlertChannel` 默认）+ 审计归档 `AuditSink`。MQ 上报为 `AuditSink`/`AlertChannel` 挂载点（RocketMQ 未强依赖 DD4）。决策维度指标（Jev）见 v3.0 §6.1。

---

## 11. SPI 扩展体系 As-Built（✅）

- 七大 SPI 契约（common）+ `BizCodeRouter` 端口 + `DefaultBizCodeRouter`（Spring 聚合 List<SPI> + bizCode 索引 + default 兜底）。
- **RSI 关联**：SPI 是 RSI 的**执行器官**——自演进产物通过 `PromptTemplateSPI`/`DecisionEngineSPI`/`ToolProviderSPI`/`ContextEnricherSPI`/`OutputPostProcessorSPI` 热插拔生效（见 §15.4）。
- **v3.0 关联**：`DecisionEngineSPI` 正是**决策平面（Jev）**的落地接缝——v3.0 将其具体化为 domain 新端口 `DecisionPort` + Jev 后端（首个实现），既是运行期 System-1 判定器，也是 RSI（R4）离线调优阈值/prompt/路由的热插拔点（见 v3.0 §2/§4，耦合点 C1）。

---

## 12. 存储分层 As-Built（✅）

| 层级 | 介质 | as-built |
|------|------|----------|
| L1 内存 | `InMemory*Repository` / `MemoryCacheBackend` | ✅（默认/测试兜底） |
| L2 Redis | 热状态/分布式锁/限流计数 | ✅ `RedisCacheBackend`/`RedisDistributedLock`（H4，`langur.cache.type=redis`；模板缺失/异常静默降级内存 P10） |
| L3 MySQL | JPA 七表(t_task_state 等) | ✅ |
| L4 向量 | `PgVectorStore`(pgvector HNSW cosine) | ✅ 存储就绪 + **语义 Embedding（H2，M2）** + Rerank（M3）；`LexicalEmbeddingPort` 保留为降级 |

---

## 13. API 接入 As-Built（✅）

- 三入口：`POST /api/v1/agent/chat`（同步）、`/stream/chat`（SSE message→summary→done）、`/stream/task`（SSE accepted→progress→summary→done）。
- 任务控制面 + `GovernanceFilter` 治理链 + `Result<T>` 全局异常。
- **注**：README 描述的旧 `/api/agents/*` 为历史接口，v1 治理入口以 `/api/v1/agent/*` 为准。

---

## 14. 技术选型（v2.0 修正）

| 层面 | v1.0 蓝图 | **v2.0 实现** |
|------|-----------|---------------|
| 语言/框架 | Java 17 / Boot 3.2.x | ✅ 一致（**构建须用 JDK 17**：Lombok 1.18.30 在 JDK 22+ 注解处理失效） |
| ORM | MyBatis-Plus | **Spring Data JPA + Hibernate**（D1 复核：维持 JPA，迁移收益低） |
| 缓存 | Redis 7 | ✅ Spring Data Redis（H4，`langur.cache.type=redis\|memory` 条件装配，memory 兜底 P10） |
| 业务库 | MySQL 8 | ✅（+ H2 测试） |
| 向量库 | PG + pgvector | ✅（+ 语义 Embedding/Rerank H2） |
| HTTP 客户端 | — | **WebClient(WebFlux)**（REST/MCP/OAuth2/OpenAPI 远端统一） |
| 流式 | SSE | ✅ Spring MVC `SseEmitter`（D4） |
| 可观测 | OTel + Prometheus | ✅ OTel 六类 Span + Prometheus 导出（H5 Micrometer）+ 告警分级 |
| MQ | RocketMQ | ⬜ 未强引入（审计/告警经 `AuditSink`/`AlertChannel` 挂载点，DD4） |

---

## 15. RSI 递归自演进能力架构（v2.0 新增 ⬜ 规划，承接 RoadMap 3.0 仍待派发）

> **v3.0 关联**：RSI 的运行期底座 = **决策平面（Jev）**。二者的整合（4 耦合点 C1–C4：路由↔R4 / 回放↔R0 / 反思↔R1 / 安全↔R-G）与分阶段融合路径见 v3.0 阶段文档 §4；RSI 任务（R0–R5/R-G）已承接进 v3.0 阶段文档（N3–N5，全部待派发，尊重当前暂停）。

### 15.1 定义与立场

**RSI（Recursive Self-Improvement，递归自演进）**：Agent 系统利用自身运行产生的执行数据（轨迹/指标/审计/记忆），在**无需人工重新工程**的前提下，持续改进自身的 Prompt、技能、工具集、路由策略与超参，并随改进能力提升而增强"改进能力"本身（递归）。

**核心立场（Langur 的差异化）**：RSI 的最大风险是"自我改进 = 自我失控"。Langur 的独特价值在于——**RSI 运行在 Harness 的确定性调度与五层防御之上**，自我改进的每个产物都是"候选提案"，必须经离线回放验证 + 灰度 + 审批 + 可回滚审计链才能生效。Harness 既是 RSI 的**使能器**（提供感知与执行器官），也是 RSI 的**约束器**（提供安全平面）。

### 15.2 RSI 闭环（六阶段元循环）

```
┌─────────┐   ┌──────────┐   ┌─────────┐   ┌──────────┐   ┌─────────┐   ┌─────────┐
│Observe  │──▶│Evaluate  │──▶│Propose  │──▶│Validate  │──▶│Apply    │──▶│Monitor  │──┐
│感知     │   │评估      │   │提案     │   │验证      │   │应用     │   │监控     │  │
└─────────┘   └──────────┘   └─────────┘   └──────────┘   └─────────┘   └─────────┘  │
 V/S/C 采集    V 四维指标     M5 Reasoning   反事实回放      SPI 热插拔     V 对比基线   │
 轨迹/快照/    + 失败模式     + DecisionSPI  (S 快照重放)    + 版本化       + 自动回滚   │
 记忆/审计     挖掘           生成候选       + 灰度 + 审批   + checksum                 │
                                                                                       │
└──────────────────────────────── 递归：改进后的系统产生更优数据 ◀──────────────────────┘
```

| 阶段 | 复用的现有能力 | 需新增 |
|------|----------------|--------|
| **Observe** | V(Trace/指标/审计) + S(快照) + C(记忆) 已产生全量信号 | 轨迹结构化采集管道（轨迹仓库） |
| **Evaluate** | `EvaluationService` 四维指标 + checksum | 失败/低效模式挖掘器、成功轨迹评分器 |
| **Propose** | M5 REASONING + `DecisionEngineSPI` | 提案生成器（Prompt/Skill/参数/路由四类提案） |
| **Validate** | S 快照（可做反事实回放）+ 四层校验链 | **回放引擎**（用历史快照重放候选）+ 灰度框架 + 审批流 |
| **Apply** | SPI 热插拔（7 扩展点）+ 配置化驱动 | 版本化提案仓库 + 一键回滚 |
| **Monitor** | V 指标对比 | 基线对比 + 劣化自动回滚 |

### 15.3 RSI 成熟度分级（演进路径）

| 级别 | 能力 | 说明 | 依赖 | 风险 |
|------|------|------|------|------|
| **L0** | 人工调优 | 现状：工程师手动改 Prompt/参数 | — | 低 |
| **L1** | 反思自检 | 单任务内 M5 self-critique，AFTER_INFERENCE/BEFORE_OUTPUT 注入反思（Reflexion） | M5 + L 钩子 | 低 |
| **L2** | 记忆自蒸馏 | 跨任务：成功/失败轨迹蒸馏为 L4 知识，衰减/遗忘策略 | C 向量 + 语义 Embedding | 低-中 |
| **L3** | 技能自合成 | 高频成功 ReAct 轨迹 → 归纳为 Skill（TOOL_CALL 序列）→ 校验后注册为 SKILL 工具 | T10c Skill + 回放验证 | 中 |
| **L4** | 策略自优化 | Prompt/路由规则/超参（闸门阈值、重试、Token 预算、温度）离线回放调优 + 灰度 | 回放引擎 + V 基线 | 中-高 |
| **L5** | 工具自扩展 | 检测能力缺口 → 自动检索/接入新 MCP server 或 REST API（强审批 + 沙箱回归） | MCP/REST 注册 + 审批流 | 高 |
| **L6** | 架构自演进 | 受限的范式/组件编排自调整（长期，强约束） | 全部 | 极高 |

> **L3 技能自合成是 RSI 的枢纽**：把"临场推理"固化为"确定性编排"，直接降本（减少 LLM 调用）、增稳（确定性执行）、可审计——与 Harness 的确定性调度哲学同构。

### 15.4 RSI 执行器官：SPI 热插拔映射

| RSI 提案类型 | 生效 SPI | 回滚方式 |
|--------------|----------|----------|
| Prompt 优化 | `PromptTemplateSPI`（按 bizCode 版本化） | 切回上一版本模板 |
| 路由/决策策略 | `DecisionEngineSPI` + `LayerRouter` | 规则版本回退 |
| 新技能 | `SkillRegistrar` 注册 `skill:{name}` | 注销 + 从注册中心移除 |
| 新工具 | `ToolProviderSPI` / MCP / REST 注册 | 注销 + 白名单移除 |
| 上下文增强 | `ContextEnricherSPI`（按 order） | 移除增强器 |
| 输出后处理 | `OutputPostProcessorSPI` | 移除处理器 |
| 超参（闸门/重试/预算） | 配置化驱动（`@ConfigurationProperties`） | 配置回滚 |

> **v3.0 落地**：上表"路由/决策策略"行的 `DecisionEngineSPI` 已在 v3.0 具体化为 **决策平面 `DecisionPort` + Jev 后端**（首个实现）；RSI（R4）日后经回放调优其阈值/prompt/路由即通过此接缝热插拔生效（耦合点 C1，详见 v3.0 §4.2）。

### 15.5 RSI 安全护栏（强制，对应原则 P11）

1. **提案-验证-应用三段式**：任何自改进默认是"候选"，**禁止直接生效**；必须经回放验证 + 灰度 + （高危）人工审批。
2. **反事实回放**：用 S 组件历史快照重放候选策略，对比基线指标，劣化即拒绝。
3. **版本化 + 可回滚**：所有提案版本化，checksum 审计链保证可溯源，一键回滚。
4. **改进回路自身受控**：RSI 元循环受终止闸门 + 变更频率限流约束，防止无限自我修改。
5. **权限隔离（红线）**：RSI **不可修改**安全策略层（`SecurityPolicySPI`）与四层校验链本身；安全平面的变更需最高权限 + 强制人工。
6. **沙箱验证**：新 Skill/工具先在隔离环境跑回归集，通过方可进入灰度。
7. **递归深度限制**：L4+ 的"改进改进能力"设显式深度上限与人工检查点。

---

## 16. 后续优化路线图

### 16.1 Harness 补强（按优先级）— H1–H10 已完成（2026-09-25）

| # | 优化点 | 优先级 | 依赖 | 价值 |
|---|--------|--------|------|------|
| **H1** ✅ | LLMPort 回传真实 token usage | P0 | — | 终止闸门精度 + 成本核算 + RSI 评估基线（D3） |
| **H2** ✅ | 真实语义 Embedding(M2) + Rerank(M3) 适配器 | P0 | H1 | C 组件 L4 召回质量，RSI L2 前置 |
| **H3** ✅ | PlanAndExecute 引擎落地 | P1 | — | 长任务真实拆解，`stream/task` 名副其实 |
| **H4** ✅ | Redis L2 热层 + 分布式锁 + 限流/幂等持久化 | P1 | — | 多实例水平扩展（D2） |
| **H5** ✅ | V 导出后端：Prometheus + MQ 审计上报 + 归档存储 | P1 | — | 生产可观测闭环，RSI Observe 管道 |
| **H6** ✅ | MCP 传输补全：STDIO/SSE 流式/WebSocket + 热更新 | P2 | — | 工具生态兼容 |
| **H7** ✅ | REST OpenAPI 自动发现 + OAUTH2 + 持久化/KMS 凭证库 | P2 | — | 存量 API 规模化接入 |
| **H8** ✅ | Skill 步骤补全：SUB_WORKFLOW/SUB_AGENT/LLM_CALL/LOOP/PARALLEL | P2 | H3 | RSI L3 技能自合成的表达力基础 |
| **H9** ✅ | Workflow 引擎 + Hybrid 分层调度 | P2 | H3 | 强合规场景 |
| **H10** ✅ | 安全补强：Prompt 注入检测 + 输出内容审核 + 高危审批流 | P1 | — | 五层防御补齐 |
| **H11** ⬜ | 依赖治理：ArchUnit 编译期断言 + Lombok/JDK 版本矩阵固定（toolchain） | P1 | — | 守护 P1/P2 红线（待派发，承接 RoadMap 3.0 N5） |
| **H12** ⬜ | 多 Agent 协作编排（SUB_AGENT + Agent 间消息总线） | P3 | H8 | 复杂任务分治（待派发，承接 RoadMap 3.0 N5） |

### 16.2 RSI 演进（依赖 Harness 补强）— 承接 RoadMap 3.0（N3–N5），全部待派发（暂停）

| 级别 | 优化点 | 前置依赖 | 交付物 |
|------|--------|----------|--------|
| **R-L1** | 反思自检：M5 self-critique 注入 AFTER_INFERENCE/BEFORE_OUTPUT | M5(就绪) | `ReflectionHook` + Reflexion 记忆 |
| **R-L2** | 记忆自蒸馏：轨迹→L4 知识蒸馏 + 衰减策略 | H2 H5 | 轨迹仓库 + 蒸馏管道 |
| **R-核心** | **回放验证引擎**：用 S 快照反事实重放候选 | H1 H5 | `ReplayEngine` + 基线对比（RSI 所有级别的安全前提） |
| **R-L3** | 技能自合成：成功轨迹→Skill 归纳→回放校验→注册 | H8 R-核心 | `SkillSynthesizer`（RSI 枢纽） |
| **R-L4** | 策略自优化：Prompt/路由/超参回放调优 + 灰度 | R-核心 | 提案仓库 + 灰度框架 + 自动回滚 |
| **R-L5** | 工具自扩展：能力缺口检测→自动接入（强审批） | H6 H7 R-核心 | 缺口检测器 + 审批流 |
| **R-护栏** | RSI 安全平面：权限隔离 + 变更限流 + 审计链 | 全部 | P11 落地校验 |

> **编号映射（→ RoadMap 3.0）**：R-核心=R0（+录制回放 DecisionPort C2）、R-L1=R1（+Jev 廉价初筛 C3）、R-L2=R2（+Jev 置信信号）、R-L3=R3（+Jev DECISION 步骤）、R-L4=R4（调 Jev 阈值/prompt/路由 C1 → 决策平面 D3）、R-L5=R5、R-护栏=R-G（统辖 Jev advisory C4）。全部**待派发**（RSI 暂停）。

### 16.3 建议推进顺序（第一/二步已落地）

```
第一步（夯实地基）：H1 真实 usage → H2 语义 Embedding → H5 可观测导出 → H11 依赖治理   [H1/H2/H5 ✅；H11 待派发]
第二步（补齐主链）：H3 PlanAndExecute → H4 Redis → H10 安全补强                        [✅ 全部完成]
第三步（RSI 起步）：R-核心 回放引擎 → R-L1 反思 → R-L2 记忆蒸馏                        [待派发/暂停 → 3.0 N3]
第四步（RSI 枢纽）：H8 Skill 步骤补全 → R-L3 技能自合成                                [H8 ✅；R3 待派发 → 3.0 N4]
第五步（RSI 进阶）：R-L4 策略自优化 → R-L5 工具自扩展（全程 R-护栏 伴随）              [待派发/暂停 → 3.0 N5]
```

> **v3.0 增量（2026-09-25）**：H1–H10 落地后，在其之上新增**决策平面（Jev）能力面**——任务线 **J1–J10**（N1 地基 J1–J3 → 成熟度 D1 advisory；N2 执行集成 J4–J10 → D2 闸门生效）与 R\* 的决策平面耦合（C1–C4）已迁移至 v3.0 阶段文档。上表 H1–H12 与 RSI 演进结论继续有效；**决策平面为运行期判定底座，RSI 为其离线优化器**。

---

## 17. 设计原则总览（v2.0）

| # | 原则 | 约束力 | as-built |
|---|------|--------|----------|
| P1 | 领域核心不可侵犯（Domain 零外部依赖） | 强制 | ✅（建议 H11 ArchUnit 固化） |
| P2 | 严格单向依赖 | 强制 | ✅ |
| P3 | 依赖倒置 | 强制 | ✅ |
| P4 | 六组件正交 | 强制 | ✅（RSI 为元循环，不破坏正交） |
| P5 | SPI 开闭原则 | 强制 | ✅ |
| P6 | 安全纵深防御 | 强制 | ✅（H10 补齐输入/输出/高危审批三层） |
| P7 | 可观测优先 | 强制 | ✅（H5 补齐 Prometheus 导出 + 告警分级 + 审计归档） |
| P8 | 渐进式演进 | 推荐 | ✅ |
| P9 | 配置化驱动 | 推荐 | ✅ |
| P10 | 降级兜底 | 强制 | ✅ |
| **P11** | **自演进可控（RSI 产物默认候选，回放+灰度+审批方可生效，可回滚；安全平面不可被 RSI 修改）** | **强制** | ⬜（§15.5，RSI 暂停；承接 RoadMap 3.0 R-G） |

> **v3.0 增补原则 P12**（决策平面专属红线）：**判定/生成分离 + 判定可回放可降级 + 对安全闸门恒 advisory（fail-closed）**——详见 v3.0 阶段文档 §8；随 RoadMap 3.0 J1–J10 落地。

---

> **文档维护约定**：v2.0 为 as-built 基线，后续每完成一个 H/R 优化项，同步更新对应组件的落地度标注与 §16 路线图状态；重大架构变更升版本号并保留历史。


---


# 落地路线图与完成记录 · RoadMap 2.0（H1–H12 / R0–R5 / R-G）

> **基线**：2026-09-25 状态（T1–T15 + T10a/b/c 全部完成，117 测试全绿，应用启动 ~2.1s，主链路贯通度 ~85%）
> **性质**：执行级路线图（可逐项派发），承接 1.0 TODO 的 `T*` 编号体系，本轮使用 `H*`（Harness 补强）与 `R*`（RSI 演进）编号
> **使用方式**：逐项派发（如"完成 H1"），完成后将 `[ ]` 改为 `[x]` 并在"完成记录"补充日期与说明
> **后续（3.0，2026-09-25）**：H1–H10 已全部完成并提交（见 §11 完成记录）；新增"决策平面（Jev）"能力面，其任务线 J1–J10 与 R* 的决策平面耦合（C1–C4）见 v3.0 阶段文档。本 2.0 的 R0–R5/R-G 承接进 3.0（仍待派发/暂停），H11/H12 保留在 3.0 N5。
> **同级增量（2.1，2026-09-25）**：`H13 统一模型网关`（配置驱动 Provider 工厂 + 补 GLM + 修复降级链）与 `H14 可插拔向量库 + 原生混合检索`（扩展 `VectorStore` 端口 + 补 ES/Milvus）见 v2.0 阶段文档。属 H 类基础设施硬化，**与 3.0 正交**，全部待派发。

---

## 1. 总览

### 1.1 里程碑划分（依赖驱动，非日历驱动）

```
M1 夯实地基 ──▶ M2 补齐主链 ──▶ M3 RSI 起步 ──▶ M4 RSI 枢纽 ──▶ M5 RSI 进阶
 (感知/计量)     (范式/扩展/安全)   (回放+反思)      (技能自合成)     (策略/工具自演进)
 H1 H2 H5 H11    H3 H4 H10         R0 R1 R2         H8 R3            R4 R5 R-G + H6 H7 H9 H12
```

> **核心逻辑**：RSI 的一切建立在"可观测 + 可计量 + 可回放"之上。因此 M1 先把 V/C 的感知与计量做实（真实 token、语义向量、指标导出），M3 才可能做反事实回放。跳过 M1 直接做 RSI = 无反馈信号的盲改。

### 1.2 任务总表

| ID | 任务 | 里程碑 | 优先级 | 依赖 | 估算 | 类型 |
|----|------|--------|--------|------|------|------|
| **H1** | LLMPort 回传真实 token usage | M1 | P0 | — | M | 补强 |
| **H2** | 语义 Embedding(M2) + Rerank(M3) 适配器 | M1 | P0 | H1 | M | 补强 |
| **H5** | 可观测导出后端（Prometheus + MQ 审计 + 归档） | M1 | P1 | — | L | 补强 |
| **H11** | 依赖治理（ArchUnit + JDK/Lombok toolchain 固定） | M1 | P1 | — | S | 治理 |
| **H3** | PlanAndExecute 引擎落地 | M2 | P1 | — | L | 补强 |
| **H4** | Redis L2 热层 + 分布式锁 + 限流/幂等持久化 | M2 | P1 | — | L | 补强 |
| **H10** | 安全补强（Prompt 注入 + 输出审核 + 高危审批） | M2 | P1 | — | L | 补强 |
| **R0** | 回放验证引擎（反事实重放） | M3 | P0 | H1 H5 | L | RSI 前提 |
| **R1** | 反思自检（Reflexion Hook） | M3 | P1 | M5 | M | RSI L1 |
| **R2** | 记忆自蒸馏（轨迹→L4 知识） | M3 | P1 | H2 H5 | M | RSI L2 |
| **H8** | Skill 步骤补全（SUB_WORKFLOW/SUB_AGENT/LLM_CALL/LOOP/PARALLEL） | M4 | P1 | H3 | L | 补强 |
| **R3** | 技能自合成（轨迹→Skill→回放校验→注册） | M4 | P1 | H8 R0 | L | RSI L3（枢纽） |
| **R4** | 策略自优化（Prompt/路由/超参回放调优 + 灰度） | M5 | P2 | R0 | L | RSI L4 |
| **R5** | 工具自扩展（缺口检测→自动接入 + 强审批） | M5 | P2 | H6 H7 R0 | L | RSI L5 |
| **R-G** | RSI 安全平面（权限隔离 + 变更限流 + 审计链） | M5 | P0 | R0 | M | RSI 护栏 |
| **H6** | MCP 传输补全（STDIO/SSE 流式/WS + 热更新） | M5 | P2 | — | M | 补强 |
| **H7** | REST OpenAPI 自动发现 + OAUTH2 + KMS 凭证库 | M5 | P2 | — | M | 补强 |
| **H9** | Workflow 引擎 + Hybrid 分层调度 | M5 | P3 | H3 | L | 补强 |
| **H12** | 多 Agent 协作编排（SUB_AGENT + 消息总线） | M5 | P3 | H8 | L | 补强 |

> 估算：S≈0.5–1 人日，M≈2–4 人日，L≈5–10 人日（单人熟手，含测试）。

### 1.3 参考时间线（假设 2 名后端 + 1 名平台，仅供排期参考）

```
周次:  1   2   3   4   5   6   7   8   9   10  11  12
M1    [H11][H1 ][H2 ][H5        ]
M2                [H3       ][H4      ][H10     ]
M3                            [R0         ][R1 ][R2 ]
M4                                        [H8      ][R3        ]
M5                                                    [R-G][R4/R5/H6/H7...]
```

---

## 2. 派发约定（沿用 1.0，新增 RSI 红线）

- **验证闭环**：每项完成后 `mvn clean test` 全绿（**须用 JDK 17**，见 H11）；涉及启动行为的打包后实际启动验证；涉及新配置项的补 application.yml 注释样例。
- **依赖顺序**：里程碑间严格串行（M1→M5）；里程碑内任务基本独立，可按优先级并行。
- **原则红线**：不得破坏 P1（Domain 零外部依赖，仅 common+lombok）、P2（严格单向依赖）、P4（六组件正交）。
- **RSI 红线（P11，新增）**：任何 R 类任务产出的"自改进"默认是**候选提案**，禁止直接生效；必须经 R0 回放验证 + 灰度 +（高危）人工审批；**RSI 不得修改安全策略层（`SecurityPolicySPI`）与四层校验链本身**。
- **DoD（完成定义）**：代码 + 单测（纯 JUnit5，无 Spring 上下文优先）+ 文档同步（更新 v2.0 落地度标注与本 RoadMap 完成记录）。

---

## 3. M1 — 夯实地基（感知与计量）

> **里程碑退出标准**：Token 计量误差 <5%（对比 provider 账单）；L4 向量召回 Recall@5 相对词袋基线提升可量化；四维指标可在 Prometheus 查询；CI 阻断 domain 层违规 import 与错误 JDK 构建。

### H1. LLMPort 回传真实 token usage
- [x] 已完成（2026-09-25）
- **现状/问题**：`LLMPort.LLMDecision` 无 usage 字段，`TerminationGate` 的 maxTokens 维靠字符数估算（D3 未落地），成本无法核算，RSI 评估缺基线。
- **改动要点**：
  - `LLMDecision` 增加 `TokenUsage(promptTokens/completionTokens/totalTokens)` 值对象（domain，零外部依赖）。
  - 各 LLM 适配器（OpenAI 兼容优先）解析响应 `usage` 字段回填；流式 `streamComplete` 从末块 usage 或估算兜底。
  - `ReActExecutionLoop` 用真实 usage 调 `ExecutionTask.addTokens()`，估算仅作 fallback 并标注来源。
- **验收**：单测——mock 响应含 usage 时 `addTokens` 收到真实值；无 usage 时降级估算且不抛异常；超 Token 闸门用真实值触发。
- **依赖**：无 | **优先级**：P0 | **估算**：M

### H2. 语义 Embedding(M2) + Rerank(M3) 适配器
- [x] 已完成（2026-09-25）
- **现状/问题**：C 组件仅 `LexicalEmbeddingPort`（分词哈希词袋），`VectorMemoryService` 召回非语义，L4 知识记忆质量受限；M3 Rerank 未接。
- **改动要点**：
  - infra `llm` 增 `LlmEmbeddingPort implements EmbeddingPort`，经 `LlmGateway` 走 EMBEDDING 角色（M2），批量向量化 + 维度对齐（替换 1536 硬编码为配置）。
  - 增 `RerankPort`（domain 端口）+ LLM/交叉编码实现（M3），`VectorMemoryService` 召回后可选重排去噪。
  - `LexicalEmbeddingPort` 保留为无外部依赖的测试/降级实现（条件装配）。
- **验收**：单测——语义相近文本召回排序优于词袋基线（构造正负样例）；Rerank 开启后 Top-K 顺序变化有断言；M2 不可用时降级 Lexical 不中断。
- **依赖**：H1（共用网关/计量） | **优先级**：P0 | **估算**：M

### H5. 可观测导出后端（Prometheus + MQ 审计 + 归档）
- [x] 已完成（2026-09-25）
- **现状/问题**：V 组件仅 `LoggingEvaluationService` + `OtelExecutionTracer`（六类 Span），四维指标无导出、审计无长周期归档、告警分级(P0-P3)未接通道。
- **改动要点**：
  - 引入 `micrometer-registry-prometheus`，`ExecutionMetrics` 四维指标 → Prometheus `/actuator/prometheus`。
  - `EvaluationService` 增 MQ 上报实现（RocketMQ，D9）+ 审计归档存储（t_audit_log 落库已具备，补长周期分区/冷存策略）。
  - 告警分级规则引擎（P0 阻断、P1 降级+OnCall、P2 终止、P3 日报），接通知通道。
- **验收**：一次请求后 Prometheus 可查工具成功率/拦截次数等四维指标；审计记录含 checksum 且可溯源查询；模拟 P1 触发降级+通知。
- **依赖**：无 | **优先级**：P1 | **估算**：L

### H11. 依赖治理（ArchUnit + JDK/Lombok toolchain 固定）
- [ ] 待派发
- **现状/问题**：domain 零依赖红线靠约定；**Maven 默认走 Homebrew JDK 26 时 Lombok 1.18.30 注解处理静默失效**（本轮已踩，`builder()` 找不到），构建环境不确定。
- **改动要点**：
  - 引入 ArchUnit 测试：断言 domain 不 import Spring/infrastructure、api 不直调 domain、common 无状态 Bean、无循环依赖（编译期守护 P1/P2/P4）。
  - `maven-toolchains-plugin` 固定 JDK 17；或升级 Lombok 至支持 JDK 21+ 版本并复测。
  - CI 脚本显式 `JAVA_HOME` 校验 + 失败提示。
- **验收**：故意在 domain 引入 Spring import 时 ArchUnit 测试失败；错误 JDK 下构建有明确报错而非静默 Lombok 失效；`mvn clean test` 在 JDK 17 稳定全绿。
- **依赖**：无 | **优先级**：P1 | **估算**：S

---

## 4. M2 — 补齐主链（范式 / 扩展 / 安全）

> **里程碑退出标准**：`stream/task` 真实走 PlanAndExecute（可拆解多步并逐步回报进度）；多实例部署下分布式锁/限流/幂等一致；五层防御的输入层与输出层补齐。

### H3. PlanAndExecute 引擎落地
- [x] 已完成（2026-09-25）
- **现状/问题**：仅 `ReActExecutionLoop` 真实运行；`DefaultLayerRouter` 路由到 PLAN_AND_EXECUTE 时**降级 ReAct**，`stream/task` 名不副实。
- **改动要点**：
  - domain `harness/execution` 增 `PlanAndExecuteExecutionLoop`：M5 规划（任务拆解为 `PlanStep`）→ 逐步执行（每步可委派 ReAct 子循环）→ 进度回报（接 `TaskProgressBus`）→ 动态重规划。
  - `ExecutionLoopService`/`LayerRouter` 按范式分发到对应循环；复用现有 `TerminationGate`/`LoopDetector`/快照/钩子。
  - 复用既有 `Plan`/`PlanStep`/`PlanningDomainService`（README 提及的历史规划模型）。
- **验收**：单测——多步任务被拆解并按序执行，进度事件序列完整；某步失败触发重规划或降级；终止闸门对总轮次/Token 生效。
- **依赖**：无 | **优先级**：P1 | **估算**：L

### H4. Redis L2 热层 + 分布式锁 + 限流/幂等持久化
- [x] 已完成（2026-09-25）
- **现状/问题**：S 组件无 Redis L2；分布式锁为 DB 行级；`InMemoryRateLimiter`/`InMemoryIdempotencyStore` 为单机内存，多实例不一致（D2 后置项）。
- **改动要点**：
  - 引入 Redis（Spring Data Redis）：会话热状态(L2, TTL)、Redisson/SETNX 分布式锁替换 DB 锁、限流计数(Redis 令牌桶)、幂等键持久化。
  - 条件装配：`langur.cache.type=redis|memory`，memory 实现保留为默认/测试兜底。
  - 锁续期（watchdog）+ 可重入，兼容现有 `TaskState.acquireLock` 语义。
- **验收**：并发重入被 Redis 锁拒绝；两实例共享限流计数（超阈值 429）；幂等键跨实例回放首次结果；Redis 不可用时降级 memory 不中断（P10）。
- **依赖**：无 | **优先级**：P1 | **估算**：L

### H10. 安全补强（Prompt 注入 + 输出审核 + 高危审批）
- [x] 已完成（2026-09-25）
- **现状/问题**：五层防御中输入层仅有关键词脱敏（无 Prompt 注入检测），输出层仅 BEFORE_OUTPUT 钩子（无内容审核/资损校验），高危操作无人工审批流。
- **改动要点**：
  - 输入安全：`ContextSanitizer` 扩展 Prompt 注入检测（规则 + 可选 M5 分类），BEFORE_INFERENCE 拦截。
  - 输出安全：`OutputPostProcessorSPI`/BEFORE_OUTPUT 接内容审核（涉密/资损/合规），命中 ABORT + 告警。
  - 高危审批：`PermissionToolValidator` 对 CRITICAL 风险工具触发异步审批流（挂起任务 → 审批回调 → 恢复），接 S 快照。
- **验收**：注入样例被拦截并审计；资损输出被 ABORT；CRITICAL 工具调用挂起等待审批，批准后从快照恢复执行。
- **依赖**：无 | **优先级**：P1 | **估算**：L

---

## 5. M3 — RSI 起步（回放 + 反思 + 蒸馏）

> **里程碑退出标准**：R0 回放引擎能用历史快照重放候选策略并输出基线对比报告；R1 反思在失败任务上降低重复错误率（可量化）；R2 蒸馏出的 L4 知识在后续任务被召回并提升成功率。

### R0. 回放验证引擎（反事实重放）— RSI 安全前提
- [ ] 待派发
- **现状/问题**：S 组件有快照但只用于断点续跑，未用于"候选策略离线验证"。无回放则 RSI 的一切自改进无法安全验证（P11 落不了地）。
- **改动要点**：
  - 新增 `ReplayEngine`（application 或独立 rsi 包）：加载 `StateSnapshot` 轨迹 → 注入候选（Prompt/Skill/参数/路由）→ 在**沙箱模式**重放（工具调用可 mock/录制回放，LLM 可确定性 seed 或录制响应）→ 采集 V 指标 → 与基线对比。
  - 轨迹仓库：结构化存储执行轨迹（复用 t_execution_round + 快照），供 Observe 管道消费。
  - 对比报告：成功率/轮次/Token/延迟/拦截次数的基线 vs 候选差值，劣化即拒绝。
- **验收**：给定历史任务快照，重放"原策略"复现原结果（确定性）；重放"劣化候选"被报告标记为拒绝；重放"优化候选"显示指标改善。
- **依赖**：H1（真实计量）H5（指标） | **优先级**：P0 | **估算**：L

### R1. 反思自检（Reflexion Hook）— RSI L1
- [ ] 待派发
- **现状/问题**：无单任务内自我纠错；失败/低质输出直接进入下一轮或返回。
- **改动要点**：
  - `ReflectionHook`（LifecycleHook）挂 AFTER_INFERENCE / BEFORE_OUTPUT：调 M5 对当前轮决策/草稿做 self-critique，产出反思注入下一轮上下文或触发 MODIFY 改写。
  - Reflexion 记忆：反思结论写入 C 组件 L2/L3，同任务内可引用，避免重复错误。
  - 受终止闸门约束（反思轮次计入 maxRounds，防无限反思）。
- **验收**：构造易错任务，开启反思后重复错误率下降（对比基线）；反思不突破终止闸门；可配置开关（默认关，灰度）。
- **依赖**：M5（就绪） | **优先级**：P1 | **估算**：M

### R2. 记忆自蒸馏（轨迹→L4 知识）— RSI L2
- [ ] 待派发
- **现状/问题**：L4 向量记忆靠人工写入；成功/失败轨迹未沉淀为可复用知识，跨任务无经验积累。
- **改动要点**：
  - 蒸馏管道：离线消费轨迹仓库 → M5/M6 提炼"成功模式/失败教训/可复用结论" → 经 H2 语义 Embedding 写入 `VectorMemoryService`（L4，namespace 隔离 + 衰减/遗忘策略）。
  - 召回接入：`DefaultContextAssembler` 装配时按当前任务语义召回蒸馏知识，受 Token 预算约束。
  - 质量门：蒸馏知识入库前经轻量校验（去重/置信度/可选人工审核队列）。
- **验收**：同类任务二次执行时召回首次蒸馏知识；召回知识进入上下文且不超 Token 预算；低质/重复蒸馏被去重拒绝。
- **依赖**：H2 H5 | **优先级**：P1 | **估算**：M

---

## 6. M4 — RSI 枢纽（技能自合成）

> **里程碑退出标准**：系统能从高频成功 ReAct 轨迹自动归纳出候选 Skill，经 R0 回放校验通过后注册为 `skill:{name}`，并在后续同类任务中被路由命中、降低 LLM 调用轮次与成本（可量化）。

### H8. Skill 步骤补全
- [x] 已完成（2026-09-25）
- **现状/问题**：T10c 仅 TOOL_CALL / CONDITION 两类步骤；v1.0 §7.5 设计的 SUB_WORKFLOW / SUB_AGENT / LLM_CALL / LOOP / PARALLEL 缺失，表达力不足以承载自合成的复杂技能。
- **改动要点**：
  - `StepType` 扩展 + `SkillExecutor` 支持：LLM_CALL（调 M4/M5）、LOOP（带退出条件 + 次数上限）、PARALLEL（并发子步骤 + 汇聚）、SUB_WORKFLOW（嵌套技能/流程）、SUB_AGENT（委派子 Agent，为 H12 铺路）。
  - 沿用 `SkillExpressionResolver` 安全最小集；LOOP/PARALLEL 受步数硬上限 + 终止闸门约束。
  - 嵌套步骤仍回派 `ToolDispatcher`，保持四层校验链。
- **验收**：各步骤类型单测（LOOP 退出、PARALLEL 汇聚、SUB_WORKFLOW 嵌套、LLM_CALL 调用）；超步数上限触发 `SkillExecutionException`；嵌套工具走校验链。
- **依赖**：H3（SUB_WORKFLOW 复用范式引擎） | **优先级**：P1 | **估算**：L

### R3. 技能自合成（轨迹→Skill）— RSI L3（枢纽）
- [ ] 待派发
- **现状/问题**：Skill 全靠人工 `@SkillDef` 编写；高频成功轨迹未固化为确定性编排，重复消耗 LLM 推理、稳定性差、成本高。
- **改动要点**：
  - `SkillSynthesizer`：挖掘高频成功轨迹（轨迹仓库 + 聚类）→ M5 归纳为 `SkillStep` 序列（TOOL_CALL/CONDITION/…）→ 生成候选 `SkillSpec`。
  - 验证：候选 Skill 经 **R0 回放**在原轨迹样本上重放，指标不劣于原 ReAct 路径（轮次/成本/成功率）方可通过。
  - 应用：经 `SkillRegistrar` 注册为 `skill:{name}`（source=SKILL），版本化 + 可回滚；高危/低置信候选进人工审批队列。
  - 护栏（P11）：合成 Skill 不得包含越权工具；注册前过四层校验链元数据审查。
- **验收**：给定重复性任务轨迹集，自动合成出等价 Skill；回放校验通过；后续同类任务路由命中该 Skill，LLM 调用轮次下降（对比基线）；劣化候选被拒绝。
- **依赖**：H8 R0 | **优先级**：P1 | **估算**：L

---

## 7. M5 — RSI 进阶 + 生态补强

> **里程碑退出标准**：R-G 安全平面生效（RSI 全程受控可回滚）；R4 能离线调优 Prompt/路由/超参并灰度上线；R5 能在强审批下自动接入新工具；MCP/REST 生态传输与发现补全。

### R-G. RSI 安全平面（护栏）— P0，与 R3–R5 并行
- [ ] 待派发
- **改动要点**：提案-验证-应用三段式框架；版本化提案仓库 + checksum 审计链 + 一键回滚；变更频率限流 + 递归深度上限；**权限隔离红线**（RSI 不可改 `SecurityPolicySPI`/校验链，安全平面变更强制最高权限+人工）；灰度发布框架（按 bizCode/租户/比例）。
- **验收**：未经验证的提案无法生效；劣化上线后自动回滚；尝试修改安全策略层的 RSI 提案被拒绝并告警；变更频率超限被限流。
- **依赖**：R0 | **优先级**：P0 | **估算**：M

### R4. 策略自优化（Prompt/路由/超参）— RSI L4
- [ ] 待派发
- **改动要点**：从轨迹挖掘低效模式 → M5 生成四类提案（Prompt 模板 / `LayerRouter` 规则 / 闸门·重试·Token 预算·温度超参 / `DecisionEngineSPI` 策略）→ R0 回放调优（可选 bandit/贝叶斯搜索）→ R-G 灰度 + 基线对比 → 生效/回滚。
- **验收**：候选 Prompt/超参经回放优于基线后灰度上线；线上指标劣化自动回滚；全程审计可溯源。
- **依赖**：R0 R-G | **优先级**：P2 | **估算**：L

### R5. 工具自扩展 — RSI L5
- [ ] 待派发
- **改动要点**：能力缺口检测（任务失败/无可用工具时归因）→ 自动检索候选（MCP server 注册表 / OpenAPI 目录）→ 沙箱回归 + **强制人工审批**（高危红线）→ 注册为 MCP/REST 工具 → 灰度。
- **验收**：缺口被识别并给出候选工具；未审批工具不生效；审批通过后工具经四层校验链可用；越权/内网候选被 SSRF/权限层拒绝。
- **依赖**：H6 H7 R0 R-G | **优先级**：P2 | **估算**：L

### H6. MCP 传输补全
- [x] 已完成（2026-09-25）
- **改动要点**：`McpTransport` 增 STDIO（进程管道）/ 真流式 SSE / WebSocket 实现；订阅 `tools/list_changed` 热更新；多 server 熔断隔离细化（单 server 故障降级不影响全局）。
- **验收**：STDIO/SSE/WS 三种传输各跑通一次 tools/call；远端工具变更触发热更新注册；单 server 宕机其余正常。
- **依赖**：无 | **优先级**：P2 | **估算**：M

### H7. REST OpenAPI 自动发现 + OAUTH2 + KMS 凭证库
- [x] 已完成（2026-09-25）
- **改动要点**：OpenAPI Spec 解析器自动注册工具（补 T10a 的 YAML 手动方式）；`CredentialType` 增 OAUTH2（client_credentials 刷新）；`CredentialVault` 增持久化/KMS 后端（密钥不落明文）。
- **验收**：给定 OpenAPI 文档自动发现并注册工具，经四层校验链调用成功；OAUTH2 token 自动获取/刷新；密钥从 KMS 读取不落日志。
- **依赖**：无 | **优先级**：P2 | **估算**：M

### H9. Workflow 引擎 + Hybrid 分层调度
- [x] 已完成（2026-09-25）
- **改动要点**：顶层 Workflow 引擎（强合规/审批，Harness 全权）；Hybrid 分层调度（顶层 Workflow 锁边界 → 中层 PlanAndExecute 拆解 → 底层 ReAct 执行）；`LayerRouter` 升级为学习型路由（接 R4）。
- **验收**：强合规任务走 Workflow 全权控制；Hybrid 任务分层分权执行；路由决策有指标支撑。
- **依赖**：H3 | **优先级**：P3 | **估算**：L

### H12. 多 Agent 协作编排
- [ ] 待派发
- **改动要点**：SUB_AGENT 步骤（H8 铺路）+ Agent 间消息总线（Spring Event / RocketMQ，D9）+ AgentId 隔离与跨 Agent 任务分发（README 既有概念 harness 化）。
- **验收**：主 Agent 委派子 Agent 完成子任务并汇聚结果；Agent 间隔离无越权；协作链路 Trace 贯通。
- **依赖**：H8 | **优先级**：P3 | **估算**：L

---

## 8. 待决策项（开始前需拍板）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| DD1 | JDK/Lombok 治理方式 | toolchain 固定 JDK17 / 升级 Lombok 支持新 JDK | 升级 Lombok + toolchain 双保险（H11） |
| DD2 | Redis 客户端 | Redisson / Lettuce 原生 | Redisson（分布式锁 watchdog 成熟，H4） |
| DD3 | 指标导出 | Micrometer+Prometheus / OTel Metrics 统一 | Micrometer（生态成熟，与现有 OTel Trace 并存，H5） |
| DD4 | MQ 选型 | RocketMQ（v1.0 D9）/ Kafka | 维持 RocketMQ 设计，落地时按基建现状定（H5/H12） |
| DD5 | 回放确定性 | LLM 录制回放 / 固定 seed / 仅工具 mock | 录制回放优先（确定性最强，R0） |
| DD6 | RSI 提案存储 | 复用 t_audit_log / 新增 t_rsi_proposal 表 | 新增提案表（版本化+状态机，R-G） |
| DD7 | Embedding 维度 | 固定 1536 / 按模型配置化 | 配置化（H2，解耦 pgvector 维度） |
| DD8 | RSI 自治边界 | 全自动 / 人工审批门（分级） | 分级：L1-L2 自动，L3 抽样审批，L4-L5 强制审批（R-G） |

---

## 9. 度量与验收（里程碑 KPI）

| 里程碑 | 关键 KPI | 目标 |
|--------|----------|------|
| M1 | Token 计量误差 / L4 Recall@5 提升 / 指标可查询 / CI 红线守护 | <5% / 可量化提升 / 100% 四维导出 / 违规构建阻断 |
| M2 | 长任务真实拆解率 / 多实例一致性 / 五层防御覆盖 | stream/task 走 P&E / 锁·限流·幂等跨实例一致 / 输入+输出层补齐 |
| M3 | 回放确定性 / 反思降错率 / 蒸馏知识召回命中 | 原策略重放复现 / 重复错误↓ / 命中且有增益 |
| M4 | 自合成 Skill 通过率 / 成本下降 | 回放校验通过 / 同类任务 LLM 轮次↓（可量化） |
| M5 | RSI 受控性 / 自优化增益 / 自扩展安全 | 100% 提案可回滚 / 灰度增益为正 / 越权候选 0 生效 |

---

## 10. 风险登记

| 风险 | 影响 | 缓解 |
|------|------|------|
| RSI 自我改进失控 | 安全/资损 | P11 红线 + R-G 安全平面 + 回放验证 + 人工审批门（DD8） |
| 回放确定性不足（LLM 非确定） | R0 验证失真 | 录制回放 / 固定 seed / 工具 mock（DD5） |
| 语义 Embedding 成本/延迟 | C 组件性能 | 批量向量化 + 缓存 + M2 轻量模型 + 降级 Lexical |
| Redis 引入增加运维复杂度 | M2 交付 | 条件装配，memory 兜底，灰度切换（P10） |
| 多范式引擎与现有 ReAct 耦合 | E 组件回归 | 复用统一闸门/钩子/快照，增量新增循环实现，充分单测 |
| 构建环境 JDK 漂移 | 全项目 | H11 优先落地（toolchain + CI 校验） |

---

## 11. 完成记录

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-09-25 | RoadMap 2.0 制定 | 依据 v2.0 §16，拆解为 M1–M5 里程碑、19 项任务（H1–H12 + R0–R5/R-G）、8 待决策项、KPI 与风险登记 |
| 2026-09-25 | H1 完成 | `LLMPort.TokenUsage` 值对象 + `LLMDecision` 回填 usage；OpenAI 兼容/Claude/Gemini 适配器解析各自 usage 字段；`Agent` 累积本轮真实计量，`ReActExecutionLoop` 优先用真实 usage 调 `addTokens`，缺失降级字符估算并在指标标注来源（realTokenRounds/estimatedTokenRounds）；新增 7 测试（domain TokenUsageExecutionLoopTest×3 + infra TokenUsageParsingTest×4），全量 124 测试绿。附带清理 iCloud 冲突副本 `ReActExecutionLoop 2.java`（重复类导致 Lombok 处理中断） |
| 2026-09-25 | H2 完成 | domain 新增 `RerankPort` 端口，`VectorMemoryService` 支持可选重排（3 参构造，2 参向后兼容）；infra 新增 `LlmEmbeddingPort`（M2，走 provider `/embeddings`，维度配置化 DD7，失败降级本地同维哈希嵌入 P10）与 `EmbeddingRerankPort`（M3，语义 cosine + 词面覆盖混合打分，确定性可离线测）；`LexicalEmbeddingPort` 改为条件默认（`langur.embedding.type=lexical`），`LlmEmbeddingPort` 走 `type=llm`；`HarnessConfiguration` 经 `ObjectProvider<RerankPort>` 注入；application.yml 增 `langur.vector/embedding/rerank` 配置块。新增 12 测试，全量 136 测试绿，应用 1.87s 启动通过 |
| 2026-09-25 | H5 完成 | 引入 `micrometer-registry-prometheus`（start）+ `micrometer-core`（infra）+ actuator，暴露 `/actuator/prometheus`（application.yml `management.endpoints.web.exposure.include: health,prometheus`）。domain 新增告警分级规则引擎（§10.3）：`AlertLevel`(P0-P3 绑定处置动作)/`Alert`/`AlertChannel`/`AlertThresholds`/`AlertRule`/`ThresholdAlertRules`(6 规则：安全拦截 P0、延迟 P1、工具成功率 P1、非正常终止 P1、Token 预警 P2、循环检测 P2)/`AlertEvaluator`(通道失败静默降级 P10)，纯领域零依赖。infra 新增 `MicrometerEvaluationService`（`evaluation=prometheus` 时装配，四维数值→DistributionSummary、枚举/字符串→Counter，report 联动告警、audit 扇出归档）与 `LoggingEvaluationService` 改条件默认（`evaluation=logging`, matchIfMissing）并同样联动告警+归档；审计归档抽象 `AuditSink`（默认 `LoggingAuditSink`，MQ/对象存储挂载点，规避 RocketMQ 重依赖 DD4/P10）+ `LoggingAlertChannel`（按级映射日志级别）。start `ObservabilityConfiguration` 装配 `AlertEvaluator`（阈值由 `langur.observability.alert.*` 配置驱动，`enabled=false` 时空规则集）。新增 17 测试（domain AlertEvaluatorTest×11 + infra MicrometerEvaluationServiceTest×6），全量 153 测试绿；打包实际启动验证：`evaluation=prometheus`+`observability.enabled=true` 下 3.8s 启动，`/actuator/health` UP、`/actuator/prometheus` 正常输出 JVM/进程指标（四维业务指标首次执行后惰性注册） |
| 2026-09-25 | H3 完成 | domain `harness/execution` 新增 `PlanAndExecuteExecutionLoop`（中层范式）：`Planner` 规划拆解 → 逐步委派底层 `ReActExecutionLoop` 子循环执行 → 每步快照/进度回报（`ExecutionProgressPort`）→ 步骤失败触发 `Planner.replan` 动态重规划（默认 1 次），重规划耗尽则降级跳过剩余步骤；子步 Token/轮次上卷主任务使 `TerminationGate` 对总轮次/Token 全局生效。新增 `Planner` 端口 + `HeuristicPlanner`（零依赖默认，序列标记拆解、上限合并、空目标兜底）、`ExecutionProgressPort`（NOOP 兜底）、`ParadigmDispatchingExecutionLoop`（按 paradigm 分发，未注册范式降级 fallback）。infra 新增 `LlmPlanner`（M5 REASONING 角色，`langur.planner.type=llm` 条件装配，JSON 解析容错 + 失败降级启发式 P10）。start `HarnessConfiguration` 拆出 `reActExecutionLoop`/`planAndExecuteExecutionLoop`/`planner`/`executionProgressPort`（适配 `TaskProgressBus::publish`）Bean，`executionLoopService` 改为 `@Primary` 范式分发器；`AgentApplicationService.resolveEffectiveParadigm` 放行 PLAN_AND_EXECUTE（仅 WORKFLOW/HYBRID 待 H9 降级）。application.yml 增 `langur.planner.type`。新增 22 测试（domain HeuristicPlannerTest×6 + PlanAndExecuteExecutionLoopTest×7 + ParadigmDispatchingExecutionLoopTest×3 + infra LlmPlannerTest×6），全量 175 测试绿；打包实际启动验证 3.2s 启动、`/actuator/health` UP、`@Primary` 分发器无歧义装配、无异常日志 |
| 2026-09-25 | H10 完成 | 五层防御补强输入/输出/高危审批三道关。domain `harness/security` 新增纯领域规则引擎：`PromptInjectionDetector`（中英文注入话术四类正则：指令覆盖/角色劫持/护栏绕过/系统提示词泄露，`InjectionFinding` 分级 HIGH/MEDIUM 阻断）+ `OutputContentReviewer`（涉密/资损/合规三类审核，`ContentViolation`）+ 高危审批契约 `ApprovalPort`/`ApprovalRequest`（PENDING→APPROVED/DENIED 幂等状态机）/`ApprovalStatus`。infra 新增 `PromptInjectionGuardHook`（BEFORE_INFERENCE，命中 ABORT）与 `ContentReviewOutputHook`（BEFORE_OUTPUT，命中 ABORT）两个 `@Component` 生命周期钩子（`langur.security.*.enabled` 配置驱动，缺省启用，自动汇入 `LifecycleHookEngine`）；`CriticalApprovalValidator`（四层校验链 order=350，CRITICAL 工具无审批单则创建并挂起、PENDING 续挂、DENIED 拒绝、APPROVED 放行，审批后端缺失时 fail-closed）+ `InMemoryApprovalStore`（默认存储，可替换持久化/分布式）+ `ApprovalService`（审批回调 approve/deny/status）。`ReActExecutionLoop` BEFORE_INFERENCE 载荷由系统提示词改为最新 user 消息（`inferencePayload`，无则降级系统提示词），使注入检测真正扫描用户输入；命中经既有链路记 `interceptions`+`INFERENCE_REJECTED`/`OUTPUT_REJECTED` 审计 → H5 P0 告警，挂起的 CRITICAL 调用批准后由既有快照/断点续跑从挂起点恢复。application.yml 增 `langur.security` 配置块。新增 34 测试（domain PromptInjectionDetectorTest×6 + OutputContentReviewerTest×6 + ApprovalRequestTest×4 + infra PromptInjectionGuardHookTest×4 + ContentReviewOutputHookTest×5 + CriticalApprovalValidatorTest×6 + ApprovalServiceTest×3），全量 209 测试绿；打包实际启动验证 3.0s 启动、`/actuator/health` UP、新增 Bean 无歧义装配、无异常日志 |
| 2026-09-25 | H4 完成 | 引入 Redis L2 热层 + 分布式锁 + 限流/幂等多实例持久化，条件装配 `langur.cache.type=redis\|memory`（memory 默认/测试兜底）。common 新增两个共享端口：`CacheBackend`（带 TTL 的 kv 读写 + 原子自增，承载 L2 会话热状态/限流计数/幂等键）与 `DistributedLock`（holder 可重入 + TTL 租约自动过期，替代 DB 行级锁）。infra 新增 `CacheProperties`（`langur.cache.*`：type/ttl-seconds/lock-ttl-seconds/key-prefix）+ `MemoryCacheBackend`/`MemoryDistributedLock`（`cache.type=memory` matchIfMissing 默认，惰性 TTL 过期）+ `RedisCacheBackend`/`RedisDistributedLock`（`cache.type=redis`，基于 `StringRedisTemplate`：increment+窗口 TTL、SETNX+Lua 比较持有者原子解锁、同 holder 续租可重入；**任一操作在模板缺失或抛异常时静默降级内部内存后端，主链路不中断 P10**）+ `LockingExecutionLoopService`（E 组件统一入口外层分布式锁装饰器，执行前按 taskId 获取锁、并发重入即终止、finally 释放，透明委派不改范式分发）。api 治理层抽出 `RateLimiter`/`IdempotencyStore` 端口（Entry/StoredResponse 记录上移接口），新增 `CacheRateLimiter`（分钟窗口键原子自增，多实例共享配额）与 `CacheIdempotencyStore`（幂等键 + 首次响应 JSON/Base64 落缓存后端带 TTL，跨实例回放），`InMemory*` 实现改为 memory 模式条件装配并 implement 新端口，`GovernanceFilter` 改注入端口类型。start `HarnessConfiguration.executionLoopService` 用 `LockingExecutionLoopService` 包裹 `@Primary` 范式分发器（注入 `DistributedLock` + 锁 TTL）；application.yml 增 `langur.cache` 配置块 + `management.health.redis.enabled=false`（默认 memory 模式无 Redis 服务，避免健康探活误报 DOWN）；infra pom 增 `spring-boot-starter-data-redis`（BOM 管控版本）。新增 24 测试（infra MemoryCacheBackendTest×5 + MemoryDistributedLockTest×6 + RedisCacheBackendTest×2 + RedisDistributedLockTest×2 + LockingExecutionLoopServiceTest×3 + start CacheRateLimiterTest×3 + CacheIdempotencyStoreTest×3），覆盖：两实例共享后端→限流合计超阈值拒绝、幂等跨实例回放首次响应、锁互斥/可重入/异 holder 拒绝、Redis 模板缺失或抛异常降级内存不中断；全量 233 测试绿（domain 85 + infra 131 + start 17）；打包实际启动验证（默认 memory 模式）3.7s 启动、`/actuator/health` UP、`LockingExecutionLoopService`+`DistributedLock` 无歧义装配、无异常日志 |
| 2026-09-25 | H8 完成 | Skill 编排表达力补全，`StepType` 由 2 类扩至 7 类（新增 LLM_CALL/LOOP/PARALLEL/SUB_WORKFLOW/SUB_AGENT），`SkillStep` 增对应字段与工厂方法。`SkillExecutor` 重构为递归 `runSteps`（switch 分派），**所有步骤共享一个全局 `AtomicInteger` 步数硬上限（MAX_STEPS=1000）作终止闸门**，LOOP/PARALLEL 递归消耗同一预算，杜绝嵌套/循环失控：LLM_CALL 经新增 `SkillLlmPort` 接缝调模型（ACTION=M4/REASONING=M5）；LOOP 支持退出条件（沿用 `SkillExpressionResolver.evaluateCondition`）+ `maxIterations` 次数上限（缺省兜底 100）双约束；PARALLEL 用守护线程池并发执行各分支（分支持上下文副本互不干扰）后按序汇聚为列表写入 `outputVar`（可经 `${par.0}` 下标引用）；SUB_WORKFLOW 回派 `ToolDispatcher` 到 `skill:<ref>`，**保留四层校验链**；SUB_AGENT 经新增 `SubAgentInvoker` 接缝委派子 Agent（为 H12 铺路）。LLM/子 Agent 接缝缺失时对应步骤抛明确 `SkillExecutionException`，其余步骤类型仍可执行。`SkillExpressionResolver.navigate` 泛化支持真实 `List` 对象下标导航（原仅解析 JSON 字符串列表）。`DefaultSkillToolGateway` 经 `ObjectProvider<LlmGateway>`（适配为 `SkillLlmPort`，角色名→`ModelRole`，未知回退 ACTION）+ `ObjectProvider<SubAgentInvoker>` 松耦合注入，保留 2 参向后兼容构造。新增 10 测试（SkillExecutorTest 6→16）：LLM_CALL 提示词解析调用 + 无后端抛错、LOOP 次数上限/退出条件转假/初始即假不进循环、PARALLEL 并发汇聚+下标引用、SUB_WORKFLOW 经调度器嵌套、SUB_AGENT 委派+指令解析+无 invoker 抛错、条件死循环触发步数硬上限；全量 243 测试绿（domain 85 + infra 141 + start 17）；打包实际启动验证 3.5s 启动、`/actuator/health` UP、`DefaultSkillToolGateway` 五参构造无歧义装配、`SkillRegistrar` 正常、无异常日志 |
| 2026-09-25 | H9 完成 | 顶层 Workflow 引擎 + Hybrid 分层调度落地，补齐三层混合 Runtime 的 WORKFLOW/HYBRID 两范式（此前降级 ReAct）。domain 新增 `harness/workflow`：`WorkflowStage`（id/instruction/requiresApproval，审批闸门阶段）+ `WorkflowDefinition`（绑定 bizCode 的确定性阶段序列，含 `passthrough` 兜底工厂）+ `WorkflowRepository` 端口 + `DefaultWorkflowRepository`（内存态注册表，零依赖 P1/P9）。`harness/execution` 新增 `WorkflowExecutionLoop`（Harness 全权：阶段顺序由定义固化、LLM 不参与控制流；逐阶段先过审批闸门再委派下层 `stageExecutor`，每阶段 [S] 快照记录 `completedStages` 支撑挂起-批准-续跑跳过已完成阶段、[V] 进度回报、[L] 钩子拦截；**阶段失败即中断无重规划**，体现强合规确定性边界；子阶段 Token 上卷主任务使 `gateTripped` 全局生效）。审批复用 H10 `ApprovalPort`/`ApprovalRequest`，**以 `taskId` 为关联键（跨 resume 稳定，避免续跑换 traceId 重复挂起）**：无单则建 PENDING 挂起（状态 SUSPENDED，可续跑）、APPROVED 放行、DENIED 拒绝、需审批却无审批后端 fail-closed 中断。`RuntimeLayer` 增 `HYBRID_LAYER`，`LayerRouter.paradigmOf` 映射 HYBRID，`DefaultLayerRouter` 增确定性规则（强合规 bizCode + 多步骤特征 → HYBRID_LAYER，纯合规 → WORKFLOW，纯多步 → PLAN；**学习型路由接 R4 属 RSI 范畴暂不实现，当前为规则路由 + 执行引擎埋点支撑决策**）。app `resolveEffectiveParadigm` 放行 WORKFLOW/HYBRID。start `HarnessConfiguration` 增 `workflowRepository` Bean + `workflowExecutionLoop`（阶段委派 ReAct）+ `hybridExecutionLoop`（阶段委派 PlanAndExecute，即 Workflow→Plan→ReAct 三层分权）两个同类 Bean（`@Qualifier` 按名注入消歧），均经 `ObjectProvider<ApprovalPort>` 松耦合注入审批后端，并注册进 `ParadigmDispatchingExecutionLoop`（现覆盖全部四范式）。`WorkflowExecutionLoop` 上报 SCHEDULING 指标 paradigm/layer=WORKFLOW_LAYER/stageExecutor/workflowStages/completedStages/approvals/suspended，路由与分层决策有指标支撑。新增 10 测试（domain WorkflowExecutionLoopTest×9 + DefaultLayerRouterTest 增 hybrid 路由×1）：阶段按序完成、按 stageParadigm 分层委派（WORKFLOW→REACT / HYBRID→PLAN_AND_EXECUTE）、审批挂起→批准→续跑跳过完成、无审批后端 fail-closed、审批 DENIED 中断、阶段失败即中断、Token 闸门跨阶段生效、无定义合成兜底直通、路由分层指标断言；全量 253 测试绿（domain 95 + infra 141 + start 17）；打包实际启动验证 3.3s 启动、`/actuator/health` UP、双 `WorkflowExecutionLoop` Bean `@Qualifier` 无歧义装配、四范式分发器就绪、无异常日志 |
| 2026-09-25 | H6 完成 | MCP 传输补全：由单一 HTTP(JSON-RPC) 扩至 STDIO/真流式 SSE/WebSocket 四种传输，并落地 `tools/list_changed` 热更新与按服务端维度的熔断隔离。infra `harness/tool/mcp` 新增 `McpTransportType`(HTTP/STDIO/SSE/WS) + `AbstractCorrelatingTransport`（双向/流式传输基类：出站按请求 id 注册 `CompletableFuture` 限时等待、入站 `deliver` 关联响应或派发服务端通知，相关性逻辑与真实网络解耦可纯单测，监听器异常静默降级 P10）+ `StdioMcpTransport`（`ProcessBuilder` 拉起子进程，逐行写 stdin、守护读线程读 stdout，流注入构造脱进程单测）+ `WebSocketMcpTransport`（JDK `java.net.http` WebSocket，`onText` 累积完整帧后 deliver，无额外依赖）+ `SseMcpTransport`（`WebClient` 持久订阅 `text/event-stream` 入站 + POST 出站）+ `McpNotificationListener`/`McpSender` 函数式接缝 + `ServerCircuitBreaker`（连续失败达阈值跳闸、冷却期快速失败、半开试探、成功复位，每连接独立）。`McpTransport` 增 `setNotificationListener`/`close` 默认方法；`McpToolCatalog` 增 `unregister`/`byServer`、`ToolRegistry` 增 `unregister`（`InMemoryToolRegistry` 实现）供热更新差量注销；`McpToolProperties` 增 `failureThreshold`/`circuitCooldownSeconds` 及 ServerProps 的 `transport`/`sseUrl`/`command`/`env`。`McpClientManager` 重写：`newTransport` 按 `McpTransportType` 分派（SSE/WS 复用 `SsrfGuard` 校验 + `resolveHeaders` 注入静态头与凭证库头，WS 走 http→ws scheme 转换），`connect` 装配通知监听器→`onNotification`（`tools/list_changed` 触发 `refresh` 差量注册/注销，失败保留现状 P10），`execute` 前置 `breaker.checkOpen` 快速失败、成功 `recordSuccess`、异常 `recordFailure`，`reconnect`/`shutdown` 关闭传输句柄释放进程/连接/订阅。附带修复 `JsonRpcResponse.isError()` 未标 `@JsonIgnore` 导致被 Jackson 当作 boolean `error` 属性、与 record 组件冲突使响应帧反序列化失败的隐患。新增 8 测试（infra StdioMcpTransportTest×2 + WebSocketMcpTransportTest×2 + SseMcpTransportTest×2 + McpClientManagerTest 4→6：热更新差量注册/注销、单 server 故障熔断跳闸+快速失败+隔离不影响正常 server）：STDIO 管道跑通 tools/call + list_changed 通知派发、WS/SSE 出站关联入站响应 + 通知派发；全量 261 测试绿（domain 95 + infra 149 + start 17）；打包实际启动验证 3.6s 启动、`/actuator/health` UP（200）、`McpClientManager` 默认关闭优雅降级、无异常日志 |
| 2026-09-25 | H7 完成 | REST 工具源三项补强：OpenAPI 自动发现 + OAUTH2 凭证 + KMS/env/prop 密钥解析。infra `harness/tool/rest` 新增 `OpenApiToolImporter`（纯解析零依赖：遍历 `paths→方法→操作` 生成 `RestApiToolSpec`，urlTemplate=baseUrl+path、operationId 缺失以 `method_path` 兜底、inputSchema 由 path/query 参数 + requestBody(application/json，含极简 `#/components` `$ref` 解析) 合成 JSON Schema 供四层校验链 Schema 层、summary 作描述、`includeOperations` 白名单、servers[0].url 或 `baseUrl` 覆盖基址）+ `OpenApiSpecLoader`（内联 `spec`/classpath `specResource`/远端 `specUrl` 三来源，JSON/YAML 自动识别，YAML 用 snakeyaml `SafeConstructor` 防反序列化 gadget，远端 URL 经 `SsrfGuard` 校验）+ 二者经 `RestApiToolRegistrar` 编排（抽出 `registerSpec` 供手动/自动共用，逐来源发现注册，单来源失败告警降级不阻断启动 P10）。`CredentialType` 增 `OAUTH2`，`Credential` 增 tokenUrl/clientId/clientSecret/scope + `tokenCacheKey`；`OAuth2TokenClient`（函数式接缝）+ `OAuth2TokenManager`（client_credentials 令牌按 key 缓存、提前 skew 双重检查加锁刷新、expires_in<=0 不缓存、时钟可注入离线单测）+ `WebClientOAuth2TokenClient`（表单 POST 换令牌，令牌端点 SSRF 校验，secret/token 不落日志）。`SecretResolver` 端口 + `CompositeSecretResolver`（`env:`/`prop:`/`kms:` 三 scheme，`kms:` 委托可选 `KmsClient` Bean、缺失即 fail-closed 绝不静默回退明文）+ `KmsClient` 接缝；`InMemoryCredentialVault` 注入可选 `SecretResolver`/`OAuth2TokenManager`（保留无参构造兼容纯单测），密钥字段按需解析、OAUTH2 走令牌管理器换 Bearer，`Credential` 不生成 toString 防日志泄露。`RestApiToolProperties` 增 `openapi` 来源列表与 OAUTH2 凭证字段。application.yml 补 OAUTH2/kms 引用/openapi 自动发现与 H6 MCP 传输/熔断配置注释。新增 26 测试（OpenApiToolImporterTest×6 + OpenApiSpecLoaderTest×5 + OAuth2TokenManagerTest×5 + CredentialVaultSecretsTest×6 + RestApiToolRegistrarOpenApiTest×4）：OpenAPI 全操作发现 + 基址拼接/覆盖 + 参数/`$ref` body 合成 schema + 白名单、JSON/YAML 解析 + 缺来源/空文档 fail-fast、令牌缓存命中/到期刷新/无 expires_in 不缓存/按 key 隔离/无客户端 fail-fast、OAUTH2 Bearer 头 + kms/prop 密钥解析 + kms 缺失 fail-closed + toString 不泄露、注册器端到端自动发现（source=REST_API 入注册中心）+ OAUTH2 凭证装载 + 坏来源降级 + 关闭无副作用；全量 287 测试绿（domain 95 + infra 175 + start 17）；打包实际启动验证 3.7s 启动、`/actuator/health` UP（200）、`OpenApiToolImporter`/`OpenApiSpecLoader`/`OAuth2TokenManager`/`CompositeSecretResolver` 等新 Bean 无歧义装配、REST/MCP 工具源默认关闭优雅降级、无异常日志 |
| 2026-09-25 | 长任务场景增强（合同审查）完成 | 面向"通用 Agent 长任务：合同审查（读合同+尽调 → 结合审核 Skill 与法律知识库 → 输出审查报告+特批项报告）"补齐混合编排落地缺口（H1–H10 之外的场景增强，A/B/C/E 四项）。**A 配置驱动工作流注册（P9）**：infra 新增 `WorkflowProperties`（`langur.workflow.definitions[]`：bizCode/name/stages[id/instruction/requiresApproval/artifactName/artifactType]）+ `WorkflowDefinitionRegistrar`（纯映射，按阶段声明分派 普通/审批/产物/审批+产物 四类 `WorkflowStage`，空 bizCode/无阶段/无 id 阶段跳过降级 P10，填充 `DefaultWorkflowRepository`）；start `HarnessConfiguration.workflowRepository` 改为按配置注册（此前仓储恒空 → 仅走兜底单阶段，多阶段编排运行期不可用）；`DefaultLayerRouter` 增可选 `WorkflowRepository` 协作（凡已注册定义的 bizCode 一律视为 Workflow 信号，无需 workflow/approval/compliance 命名约定；叠加规划特征升 HYBRID），`DomainServiceConfiguration.layerRouter` 注入仓储。**B 知识库接通**：此前 `VectorMemoryService` 为孤儿 Bean（无任何主代码调用，RAG 路径运行期死代码）；infra `tool` 新增 `KnowledgeRetrievalTool`（`knowledge_retrieve`：query/namespace/topK 语义召回，返回带 score 的知识片段）+ `KnowledgeIngestTool`（`knowledge_ingest`：content/namespace/id 向量化 UPSERT 补充语料）+ `KnowledgeToolProvider`（`ObjectProvider<VectorMemoryService>` 松耦合，可用时暴露两工具、缺失降级空列表 P10），经既有 `LocalToolRegistrar` 自动注册为 LOCAL 工具纳入四层校验链，无需改调度器。**C 多产物输出**：此前各阶段 observation 被 `aggregate` 拼成单一 answer，无法承载两份独立报告；domain 新增 `Artifact`（name/type/content），`ExecutionTask` 持 artifacts + `addArtifact/getArtifacts`，`WorkflowStage` 增可选 artifactName/artifactType + `artifact()/approvalArtifact()` 工厂 + `producesArtifact()`，`WorkflowExecutionLoop` 阶段成功且声明产物时记录具名产物；app 新增 `ArtifactResult` DTO + `AgentResult.artifacts`，`AgentApplicationService.doRunAgent` 执行后回填（审查报告 + 特批项报告分离返回）。**E 指标修正**：`WorkflowExecutionLoop.reportMetrics` 的 layer 由硬编码 `WORKFLOW_LAYER` 改为 `layerOf(task)` 按范式派生（HYBRID→HYBRID_LAYER），可观测区分顶层/混合。application.yml 增 `langur.workflow` 合同审查示例定义（ingest→kb-retrieve→skill-review→special-approval[审批+特批项产物]→report[审查报告产物]）与知识库工具说明。新增 14 测试（domain WorkflowExecutionLoopTest +2：HYBRID layer 指标、多产物记录；DefaultLayerRouterWorkflowAwareTest×4：注册定义→WORKFLOW、叠加规划→HYBRID、未注册非标记→REACT、无仓储保留命名约定；infra WorkflowDefinitionRegistrarTest×4 + KnowledgeToolTest×4）；全量 301 测试绿（domain 101 + infra 183 + start 17）；打包实际启动验证 3.0s 启动、`/actuator/health` UP（200）、`contract_review` 工作流定义注册日志、`knowledge_retrieve`/`knowledge_ingest` 注册进统一注册中心、无异常日志 |
| 2026-09-25 | 长任务场景增强（Coding Agent 读码转译 PRD）完成 | 面向"长任务 Coding Agent：读代码 → 结合转译 PRD 的 Skill → 产出 PRD 文档"补齐受控代码读取与转译编排能力（合同审查增强之后的第二项场景增强）。**代码访问工具源（默认关闭 P10）**：infra 新增 `harness/tool/code` 包——`CodeAccessProperties`（`langur.code-access`：enabled/roots 白名单/maxFileBytes/maxResults/excludeDirs）+ `CodeAccessGuard`（纯 JDK 安全护栏：路径归一化后须 `startsWith` 某 root，杜绝 `../` 遍历与绝对路径越权；任一路径段命中 excludeDirs（.git/target/node_modules/build/dist）即拒绝或遍历跳过；`available()`=启用且非空 roots）+ 三个 LOCAL 工具 `ReadFileTool`（`read_file`：按行区间切片、超 maxFileBytes 截断防撑爆上下文）/`ListDirTool`（`list_dir`：目录以 `/` 标记、跳过排除目录）/`GrepTool`（`grep`：正则逐行检索返回 `相对路径:行号: 内容`，`Files.walkFileTree` 有界遍历——命中 maxResults 即 TERMINATE、SKIP_SUBTREE 剪排除目录、跳过超大文件，防大仓失控）+ `CodeToolProvider`（`@Component`，未启用/无 roots 降级空列表并记 "code tools idle"，启用则暴露三工具）。经既有 `LocalToolRegistrar` 自动注册为 LOCAL 工具纳入 T 组件四层校验链，无需改调度器。**转译 PRD 技能**：infra `skill` 新增 `TranslatePrdSkill`（`@SkillDef(name="translate-prd", riskLevel=LOW, timeoutSeconds=180)`），两步 LLM 编排——REASONING 角色先从 `${input.codeContext}`+`${input.feature}` 抽取功能/接口/约束/缺口结构化分析（输出 `analysis`），ACTION 角色再据 `${analysis}` 产出 Markdown PRD（背景/目标/范围/功能需求含验收/接口数据/非功能/风险），返回末步 PRD 正文；入参 schema 要求 feature+codeContext。经 `SkillRegistrar` 注册为 `skill:translate-prd`。**工作流配置**：application.yml 增 `code_to_prd` 定义（scan-code→analyze→translate→deliver[artifact-name=prd-document, artifact-type=prd]）与 `langur.code-access` 配置块（默认 enabled:false、roots 空）。新增 19 测试（infra CodeAccessGuardTest×8：available 门控/相对解析/空路径/遍历越权/绝对越权/排除目录拒绝/排除段检测/root 归一化；CodeToolTest×8：read_file 全文+行区间+缺失/越权失败、list_dir 跳过排除目录、grep 命中+无命中+非法正则+排除目录不命中、provider 启用降级；TranslatePrdSkillTest×3：@SkillDef 元数据/入参 schema 必填/两步 REASONING→ACTION 占位符串联）；全量 320 测试绿（domain 101 + infra 202 + start 17）；打包实际启动双路验证：默认配置 3.0s 启动、`/actuator/health` UP（200）、日志 "code tools idle"（优雅降级）、`skill:translate-prd (2 step(s))` 与 `contract_review`/`code_to_prd` 两工作流定义注册；`--langur.code-access.enabled=true --langur.code-access.roots=<temp>` 覆盖启动、`/actuator/health` UP（200）、日志 "code-access enabled, exposing read_file/list_dir/grep over 1 root(s)" 且三工具经 `LocalToolRegistrar` 注册进统一注册中心、无异常日志 |


---


# Agent-Harness 架构设计 v2.1 — 统一模型网关与可插拔向量库（原生混合检索）

> **文档性质**: 架构增量设计（Delta Design）——在 v2.0 As-Built 基线（六组件 + H1–H10 全部完成，320 测试）之上，硬化两条既有接缝：**模型接入网关**与**向量存储端口**。
> **版本**: v2.1 | **日期**: 2026-09-25 | **承接**: v2.0 阶段文档（六组件 As-Built 权威基线，继续有效）
> **与 v3.0 的关系**: **正交、同级**。v3.0 是"决策平面（Jev）+ RSI"能力面（J\*/R\* 线）；v2.1 是"模型接入 + 向量存储"基础设施硬化（H\* 线）。二者都坐落在 v2.0 As-Built 基线上，互不依赖——v2.1 让 M2/M3（Embedding/Rerank）与 LLM 供给更稳更省，恰好为 v3.0 决策平面提供更可靠的运行期底座。
> **一句话立场**: **网关统一供给（国内外主流大模型一处适配），向量库可插拔（ES/Milvus 原生混合检索），接缝已就位，只做硬化不做重构。**

---

## 0. v2.1 变更摘要（相对 v2.0）

| 变更类型 | 说明 |
|----------|------|
| **A. 统一模型网关硬化** | 现状已有 `LlmGateway → LLMRouter → ModelRoutableLLMPort` 三层，且 OpenAI/Claude/Gemini + **Qwen/DeepSeek 已实现**。v2.1 补：**配置驱动的 Provider 类型工厂**（`type: openai-compatible\|anthropic\|gemini`，新增 OpenAI 兼容厂商零代码）、**GLM/智谱补齐**、**可配置 model-prefixes**、**修复错误传播使 fallback-chains 真正生效**、**`complete()` 回传 usage**、**API Key 经 `SecretResolver`（env/prop/kms）**、（P2）**Provider 健康熔断** |
| **B. 可插拔向量库 + 原生混合检索** | 现状已有 domain `VectorStore` 端口 + `InMemory`/`PgVector` 两实现，且 `EmbeddingRerankPort` 已提供**应用侧混合检索**（cosine + 词面覆盖）。v2.1 补：**扩展 `VectorStore` 端口**（delete / 批量 upsert / metadata 过滤 / **原生 `hybridSearch`**，均以 default 方法向后兼容）、**`ElasticsearchVectorStore`（dense kNN + BM25 + RRF）**、**`MilvusVectorStore`（dense + sparse + Ranker）**、**`VectorProperties` 配置类**、**修复 pgvector 数据源装配**、**维度一致性守卫** |
| **复用既有资产** | `SecretResolver`/`CompositeSecretResolver`（H7）、`SsrfGuard`、`ServerCircuitBreaker` 模式（H6）、`TokenUsage`（H1）、`MicrometerEvaluationService`（H5）、`CacheBackend`（H4）、`EmbeddingRerankPort`/`VectorMath`（H2） |
| **新增决策 DD14–DD21** | Provider 工厂形态、ES/Milvus 客户端选型、融合算法、命名空间映射、维度策略、**ES RRF 授权核实**、`complete()` usage VO 形态 |
| **原则契合** | 不新增原则；强化 **P3（依赖倒置）/P5（SPI 开闭）/P9（配置驱动）/P10（降级兜底）**；domain 仍零外部依赖（**P1**） |

**与 v2.0 的关系**：v2.0 仍是六组件 As-Built 权威基线。v2.1 **不改动六组件正交拓扑（P4）**——模型网关属 §6「LLM 供给」的硬化，向量库属 C 组件 §12「存储分层 L4」的硬化，均为既有端口的扩展，非新组件。

---

## 1. 现状（As-Is）与痛点

### 1.1 模型接入现状

```
调用方（AgentDomainService / LlmPlanner / DefaultSkillToolGateway / LlmEmbeddingPort）
   │  按 ModelRole（M1–M6）
   ▼
LlmGateway（infra）  ── resolveModel(role) ──▶ role-models.<ROLE> → DEFAULT → provider.model
   │                    withFallback(model)  ──▶ [primary] + fallback-chains[primary] 按序重试
   ▼
LLMRouter（@Primary LLMPort）  ── route(model) ──▶ 首个 supportsModel(model) 命中的 provider，否则 default-provider
   │  注入 List<ModelRoutableLLMPort>（固定 @ConditionalOnProperty Bean 集合）
   ▼
┌─ AbstractOpenAICompatibleLLMAdapter（通用 OpenAI 兼容基类：/chat/completions, Bearer, stream, tools, usage）
│    ├─ OpenAILLMAdapter   （matchIfMissing=true，前缀 gpt-/o1-/o3-，api.openai.com/v1）
│    ├─ QwenLLMAdapter     （前缀 qwen-，dashscope.aliyuncs.com/compatible-mode/v1）  ← 国内已有
│    └─ DeepSeekLLMAdapter （前缀 deepseek-，api.deepseek.com/v1）                    ← 国内已有
├─ ClaudeLLMAdapter（Anthropic Messages API：x-api-key + anthropic-version，/v1/messages，前缀 claude-）
└─ GeminiLLMAdapter（Google REST：?key= 查询参，:generateContent，前缀 gemini-）
```

**已具备**：角色→模型解析、降级链数据结构、OpenAI 兼容通用基类、国内外 5 家（OpenAI/Claude/Gemini/Qwen/DeepSeek）。

**痛点**：

| # | 痛点 | 现状 | 影响 |
|---|------|------|------|
| A1 | **无配置驱动的 Provider 工厂** | 每家 = 一个 `@ConditionalOnProperty` 硬编码类；`ProviderProperties` 仅 `enabled/baseUrl/apiKey/model` | 新增 OpenAI 兼容厂商（GLM/Moonshot/百川/讯飞/阶跃…）都要写新类，违反 P5/P9 |
| A2 | **缺 GLM/智谱** | 无 `glm-` 适配器 | 国内主流缺一家 |
| A3 | **provider 匹配前缀硬编码** | `supportsModel` 前缀写死在各类 | 同厂多模型系列/改名需改码 |
| A4 | **错误被吞，fallback 形同虚设** | 适配器把异常吞成 `"Error: ..."` 字符串返回，`withFallback` 收不到 throw | `fallback-chains` 几乎永不触发（P10 打折） |
| A5 | **`complete()` 丢弃 usage** | 返回 `String`，不回传 token | 补全路径无法纳入 H1 真实计量 |
| A6 | **LLM Key 未过 SecretResolver** | `apiKey` 为明文属性（env 占位），未走 `env:/prop:/kms:` | 与 H7 工具凭证不一致；无 KMS 托管 |

### 1.2 向量库现状

```
KnowledgeRetrievalTool / KnowledgeIngestTool（infra.tool，经 LocalToolRegistrar 注册为 LOCAL 工具）
   ▼
VectorMemoryService（domain，纯领域）
   │  remember(ns,id,content,meta): embeddingPort.embed → vectorStore.upsert
   │  recall(ns,query,topK,budget): embed → vectorStore.search → [rerankPort.rerank] → token 预算截断
   ▼
VectorStore（domain 端口，P3）              EmbeddingPort（domain 端口）        RerankPort（domain 端口）
   ├─ InMemoryVectorStore（默认 store=memory）  ├─ LexicalEmbeddingPort（256 维，默认）  └─ EmbeddingRerankPort
   └─ PgVectorStore（store=pgvector）           └─ LlmEmbeddingPort（1536 维，/embeddings）    score=α·cosine+(1−α)·词面覆盖
```

**已具备**：干净的 `VectorStore` 依赖倒置端口、内存/pgvector 两实现、语义 Embedding（M2）、**应用侧混合检索**（`EmbeddingRerankPort`：向量 cosine + 词面覆盖加权）、知识库工具接入统一工具链。

**痛点**：

| # | 痛点 | 现状 | 影响 |
|---|------|------|------|
| B1 | **端口过薄** | `VectorStore` 仅 `upsert(record)` + `search(ns,float[],topK)` | 无删除/批量/元数据过滤/**原生混合检索**；ES/Milvus 的服务端 BM25+ANN 能力无法表达 |
| B2 | **缺 ES/Milvus 适配** | 仅 memory + pgvector | 生产级向量库（全文+向量混合、水平扩展）缺位 |
| B3 | **混合检索仅在应用侧** | `EmbeddingRerankPort` 拉全量候选再本地打分 | 大规模下网络/内存开销高，无法用 ES BM25 / Milvus 稀疏向量的服务端检索 |
| B4 | **pgvector 数据源未装配** | `PgVectorStore` 依赖 `vectorJdbcTemplate` Bean，但 start 无该 Bean、yml 无 datasource | `store=pgvector` 当前会装配失败 |
| B5 | **无 VectorProperties** | `langur.vector.*` 仅靠 `@ConditionalOnProperty` 切 store，无连接配置类 | ES/Milvus 的 uris/index/collection/凭证/维度无处安放 |
| B6 | **维度不一致隐患** | lexical=256、llm=1536；`PgVectorStore` DDL 硬编码 `vector(256)` | Embedding 维度与索引 schema 不匹配会静默损坏召回 |

### 1.3 结论

**两条接缝都已存在，v2.1 是"硬化 + 补齐适配器"，不是"新建子系统"。** 模型侧把"每厂一类"升级为"配置驱动工厂"，向量侧把"薄端口 + 应用侧混合"升级为"厚端口 + 原生混合下推（应用侧兜底）"。

---

## 2. Stream A — 统一模型网关

### 2.1 目标与原则

- **一处适配、配置扩厂**：新增 OpenAI 兼容厂商 = 纯 YAML（P5/P9），零 Java 代码。
- **国内外主流全覆盖**：GPT / Claude / Gemini（国际）+ Qwen / GLM / DeepSeek（国内），并预留 Moonshot、百川、讯飞、阶跃、MiniMax、零一万物等（多数有 OpenAI 兼容端点 → 零代码）。
- **降级真正生效**：适配器失败必须 throw，`withFallback` 才能按 `fallback-chains` 逐级降级（P10）。
- **计量与密钥一致**：补全路径回传 usage（H1）；Key 经 `SecretResolver`（H7）。
- **domain 契约稳定**：`LLMPort` 保持零外部依赖（P1），仅以 default 方法增量扩展。

### 2.2 增量设计

#### ① 配置驱动的 Provider 类型工厂（核心）✅ 已落地（H13.1，2026-09-25）

`ProviderProperties` 扩展两字段：

```
ProviderProperties {
  boolean enabled;
  String  type;              // 新增：openai-compatible | anthropic | gemini（缺省 openai-compatible）
  String  baseUrl;
  String  apiKey;            // 保留（明文字面量，向后兼容，不推荐）
  String  apiKeyRef;         // 新增：env:/prop:/kms: → 经 SecretResolver 解析（优先于 apiKey）
  String  model;
  List<String> modelPrefixes;// 新增：可配置匹配前缀（替代硬编码 supportsModel）
}
```

新增 infra `ModelProviderFactory`：遍历 `langur.llm.providers.*` 中 `enabled=true` 的条目，按 `type` 实例化对应适配器模板（`openai-compatible` → 复用 `AbstractOpenAICompatibleLLMAdapter` 的**可配置子类**；`anthropic`/`gemini` → 复用 `ClaudeLLMAdapter`/`GeminiLLMAdapter` 逻辑，但改为**按配置构造**而非按厂硬编码），产出 `List<ModelRoutableLLMPort>` 供 `LLMRouter` 注入。

> **注册范式转变**：从"一家一个 `@ConditionalOnProperty` `@Component`"→"一个工厂读配置动态建 Bean"。`LLMRouter` 的构造注入（`List<ModelRoutableLLMPort>`）不变，只是列表来源由工厂产出。既有 5 家适配器逻辑**全部复用**，仅注册方式改为配置驱动 + 内置 `openai` 默认（matchIfMissing 语义保留）。

#### ② GLM/智谱补齐（零代码验证工厂）✅ 已落地（H13.2，2026-09-25）

```yaml
glm:
  type: openai-compatible
  enabled: false
  base-url: https://open.bigmodel.cn/api/paas/v4
  model: glm-4-plus
  model-prefixes: [glm-, chatglm-]
  api-key-ref: env:GLM_API_KEY
```

#### ③ 可配置 model-prefixes ✅ 已落地（H13.3，2026-09-25）

`ModelRoutableLLMPort.supportsModel(model)` 改为匹配 `modelPrefixes` 任一前缀；未配置前缀时回退按 `provider.model` 精确/前缀匹配。`LLMRouter.route` 逻辑不变。

#### ④ 错误传播修复（使 fallback 生效）✅ 已落地（H13.4，2026-09-25）

- 适配器在**传输/HTTP/解析失败**时抛 infra `ModelProviderException`（不再吞成 `"Error: ..."`）。
- `LlmGateway.withFallback` 捕获 `ModelProviderException` → 试链中下一个；链耗尽 → 抛 `IllegalStateException`（现状语义保留）。
- **边界**：模型正常返回的"内容里含 error 文本"**不算失败**（只有传输层/非 2xx/反序列化异常才 throw），避免误降级。

#### ⑤ `complete()` 回传 usage ✅ 已落地（H13.5，2026-09-25）

domain `LLMPort` 增量（default 方法，向后兼容）：

```
final class CompletionResult { String content; TokenUsage usage; }         // 新 VO（domain，纯 JDK）
default CompletionResult completeWithUsage(sys, model, userMessage) {       // 默认委派旧方法
    return new CompletionResult(complete(sys, model, userMessage), TokenUsage.empty());
}
String complete(...)  // 保留
```

OpenAI 兼容基类 override `completeWithUsage` 解析 `usage`（复用 H1 `parseUsage`）。补全路径由此纳入真实计量。

#### ⑥ API Key 经 SecretResolver ✅ 已落地（H13.6，2026-09-25）

`ProviderProperties.apiKeyRef`（`env:/prop:/kms:`）优先于 `apiKey`，经 H7 `CompositeSecretResolver` 解析；解析结果**只注入请求头，绝不落日志**（复用 `Credential` 不生成 toString 的防泄露约定）。缺失 `kms:` 后端时 fail-closed（与 H7 一致）。

#### ⑦（P2）Provider 健康熔断

复用 H6 `ServerCircuitBreaker` 模式，每 provider 独立：连续失败达阈值跳闸 → `LLMRouter`/`withFallback` 快速跳到链中下一个 → 冷却半开试探。指标暴露熔断态。

### 2.3 配置 Schema（`langur.llm.*`，P9）

```yaml
langur:
  llm:
    default-provider: openai
    providers:
      openai:   { type: openai-compatible, enabled: true,  base-url: https://api.openai.com/v1,                        model: gpt-4o,          model-prefixes: [gpt-, o1-, o3-], api-key-ref: env:OPENAI_API_KEY }
      claude:   { type: anthropic,         enabled: false, base-url: https://api.anthropic.com,                        model: claude-3-5-sonnet, model-prefixes: [claude-],       api-key-ref: env:CLAUDE_API_KEY }
      gemini:   { type: gemini,            enabled: false, base-url: https://generativelanguage.googleapis.com,        model: gemini-1.5-pro,  model-prefixes: [gemini-],       api-key-ref: env:GEMINI_API_KEY }
      # —— 国内主流（多为 OpenAI 兼容，零代码）——
      qwen:     { type: openai-compatible, enabled: false, base-url: https://dashscope.aliyuncs.com/compatible-mode/v1, model: qwen-max,        model-prefixes: [qwen-],         api-key-ref: env:QWEN_API_KEY }
      glm:      { type: openai-compatible, enabled: false, base-url: https://open.bigmodel.cn/api/paas/v4,              model: glm-4-plus,      model-prefixes: [glm-, chatglm-],api-key-ref: env:GLM_API_KEY }
      deepseek: { type: openai-compatible, enabled: false, base-url: https://api.deepseek.com/v1,                       model: deepseek-chat,   model-prefixes: [deepseek-],     api-key-ref: env:DEEPSEEK_API_KEY }
      # 预留（纯配置即可启用）：moonshot / baichuan / spark / stepfun / minimax / yi …
    role-models:      { ROUTING: gpt-4o, EMBEDDING: text-embedding-3-small, RERANK: gpt-4o, ACTION: gpt-4o, REASONING: gpt-4o, LONG_CONTEXT: gemini-1.5-pro }
    routing-rules:    { fast: deepseek-chat, powerful: gpt-4o, cheap: qwen-max }
    fallback-chains:  { gpt-4o: [deepseek-chat, qwen-max], gemini-1.5-pro: [gpt-4o] }
```

### 2.4 国内外主流模型能力矩阵

| 模型系列 | 厂商 | 线格式 / type | 端点 | 接入方式 |
|----------|------|---------------|------|----------|
| GPT-4o / o1 / o3 | OpenAI（国际） | openai-compatible | api.openai.com/v1 | 内置默认 |
| Claude 3.5/4 | Anthropic（国际） | anthropic（Messages API） | api.anthropic.com | 专用模板 |
| Gemini 1.5/2 | Google（国际） | gemini（Generative REST） | generativelanguage.googleapis.com | 专用模板 |
| 通义千问 Qwen | 阿里（国内） | openai-compatible | dashscope.aliyuncs.com/compatible-mode/v1 | **已实现** |
| 智谱 GLM-4 | 智谱（国内） | openai-compatible | open.bigmodel.cn/api/paas/v4 | **v2.1 补（纯配置）** |
| DeepSeek | 深度求索（国内） | openai-compatible | api.deepseek.com/v1 | **已实现** |
| Kimi/Moonshot、百川、讯飞星火、阶跃、MiniMax、零一万物 | 国内 | 多为 openai-compatible | 各家兼容端点 | **纯配置扩展（零代码）** |

> **工厂价值**：除 Anthropic/Google 两种非 OpenAI 线格式需专用模板外，**绝大多数国内外厂商都落在 `openai-compatible`**——一次工厂，长期零代码扩厂。

### 2.5 DDD 落点

| 模块 | 变更 | 依赖约束 |
|------|------|----------|
| **langur-domain** | `LLMPort` 增 `CompletionResult` VO + `completeWithUsage` default 方法 | 仅 common+lombok（P1）✅ |
| **langur-infrastructure** | `ProviderProperties` 扩 `type/apiKeyRef/modelPrefixes`；新增 `ModelProviderFactory`、`ModelProviderException`；适配器改可配置构造 + throw 语义 + prefix 配置化；`LlmGateway.withFallback` 捕获异常降级；Key 经 `CompositeSecretResolver`；（P2）provider 熔断 | 实现 domain 端口（P3） |
| **langur-start** | 工厂产出 `List<ModelRoutableLLMPort>` Bean；`SecretResolver` 注入 LLM 侧 | 装配 |
| **langur-common** | （可选，DD14）若定型 `ModelProviderSPI` 契约则在此 | 零 Spring |

---

## 3. Stream B — 可插拔向量库 + 原生混合检索（下推）

> **策略（已定）**：**原生下推优先，应用侧兜底**。ES/Milvus 在服务端做 BM25/稀疏 + ANN 融合；不支持原生的 store（memory/pgvector）或原生不可用时，回退既有 `EmbeddingRerankPort` 应用侧混合。

### 3.1 `VectorStore` 端口扩展（default 方法，向后兼容 P5）✅ 已落地（H14.1/H14.2，2026-09-25）

```
public interface VectorStore {

    // —— 既有（保留）——
    void upsert(VectorRecord record);
    List<VectorRecord> search(String namespace, float[] queryVector, int topK);   // 转调 search(SearchQuery)

    // —— v2.1 增量（均 default，既有实现零改动即编译通过）——
    default void upsertAll(String namespace, List<VectorRecord> records) { records.forEach(this::upsert); }
    default void delete(String namespace, String id) { throw new UnsupportedOperationException(); }
    default List<VectorRecord> search(SearchQuery query) { return search(query.namespace(), query.queryVector(), query.topK()); }

    default boolean supportsHybrid() { return false; }                             // 能力位：VectorMemoryService 据此分支
    default List<VectorRecord> hybridSearch(HybridQuery query) {                   // 原生混合检索（ES/Milvus override）
        throw new UnsupportedOperationException("native hybrid not supported");
    }
}
```

新增 domain 值对象（纯 JDK，P1）：

```
SearchQuery  { String namespace; float[] queryVector; int topK; SearchFilter filter; double minScore; }
HybridQuery  { String namespace; String queryText; float[] queryVector; int topK;
               SearchFilter filter; FusionMode fusion; int rrfK; double lexicalWeight; }
SearchFilter { List<Predicate> predicates; }   // metadata 过滤：EQ / IN / GT / LT / GTE / LTE / EXISTS
FusionMode   { RRF, WEIGHTED }                 // 融合算法（默认 RRF：基于排名、免分数归一化）
```

### 3.2 `VectorMemoryService.recall` 增强（domain）✅ 已落地（H14.3，2026-09-25）

```
recall(ns, query, topK, budget):
  vec = embeddingPort.embed(query)
  if (hybridEnabled && vectorStore.supportsHybrid()):
      matches = vectorStore.hybridSearch(HybridQuery.of(ns, query, vec, topK, fusion, rrfK, weight))   // 原生下推
  else:
      matches = vectorStore.search(ns, vec, topK)                                                        // 纯向量
      if (rerankPort != null) matches = rerankPort.rerank(query, matches, topK)                          // 应用侧混合兜底（现状路径）
  return truncateByTokenBudget(matches, budget)
```

> 现有 memory/pgvector 路径**完全不变**（`supportsHybrid()=false` → 走 search + app-side rerank）。ES/Milvus 打开 `supportsHybrid()=true` 才走原生。

### 3.3 `ElasticsearchVectorStore`（`store=elasticsearch`）✅ 已落地（H14.5，2026-09-25）

- **索引 mapping**：`content` → `text`（BM25，中文可选 `ik_max_word`/`smartcn` 分词器）；`embedding` → `dense_vector(dims=配置维度, similarity=cosine, index=true)`；`namespace` → `keyword`；`metadata.*` → `keyword`/数值。
- **纯向量**：`knn` 查询（`field=embedding, query_vector, k, num_candidates, filter`）。
- **原生混合**：`retriever` 框架的 **`rrf`** 融合 `standard`(BM25 `match`) + `knn`——服务端一次往返完成融合（下推）。
- ⚠️ **授权核实（DD20，强制）**：ES 的 **RRF retriever 历史上属付费授权层（Platinum/Enterprise）**。**落地前必须核实当前版本授权**。若未授权 → **降级方案**：分别发 BM25 查询 + kNN 查询，客户端做 **RRF 融合**（基于排名、免分数归一化，~20 行）或加权归一化。既保留"混合"语义，又不被授权卡死（P10）。
  - ✅ **已核实并落地**：RRF retriever 确属 Platinum+ 付费层 → 缺省 `native-rrf=false` 走"两查询 + 客户端 RRF 融合"（纯静态函数，RRF/WEIGHTED 双模式）；授权环境可开启原生 `retriever.rrf`，任何原生失败自动降级客户端融合。
- **客户端（DD15）**：官方 `co.elastic.clients:elasticsearch-java`（贴合既有 WebClient 风格、避免 Spring Data 锁定）vs `spring-data-elasticsearch`。倾向官方客户端。
  - ⚠️ **落地偏差**：实际以 **WebClient REST 直连**替代官方 SDK——pom 无该依赖，重依赖易冲突且难以离线确定性单测（验收硬约束）；REST 契约与 SDK 等价且更贴合工程既有 WebClient 风格（详见 RoadMap 2.1 §10）。
- **命名空间映射（DD18）**：单索引 + `namespace` keyword 字段过滤（默认，运维简单）vs 每命名空间一索引（隔离强）。✅ 采用单索引 + `namespace` 强制过滤，`_id = namespace::id` 幂等 UPSERT。
- **凭证**：`username` + `password-ref`（经 `SecretResolver`）；`uris` 经 `SsrfGuard` 校验（自部署内网走白名单）；建议 TLS。✅ 已落地：解析为空/无 resolver 均 fail-closed；明文仅注入 Basic 认证头，不落日志/异常/请求体。

### 3.4 `MilvusVectorStore`（`store=milvus`）✅ 已落地（H14.6，2026-09-25）

- **Collection schema**：`id`(PK) / `namespace`(varchar，**partition-key** 高效过滤) / `content`(varchar) / `embedding`(`FLOAT_VECTOR`，HNSW 或 IVF_FLAT，metric=COSINE/IP) / `sparse`(`SPARSE_FLOAT_VECTOR`)。✅ 首写 best-effort 自建（含索引参数），已存在则忽略交由预置 schema。
- **稀疏向量来源**：Milvus 2.5+ 内置 **`BM25` function**（insert 时由 `content` 文本自动生成稀疏向量）→ 原生全文 + 混合；或外接稀疏 Embedding（BGE-M3/SPLADE）。✅ 采用 BM25 function（schema 内声明），sparse 通道检索直传查询文本。
- **原生混合**：`hybrid_search` 传两个 `AnnSearchRequest`（dense + sparse）+ **`RRFRanker`** 或 `WeightedRanker`——服务端融合（下推）。
  - ⚠️ **落地偏差**：RESTful v2 未暴露服务端 `hybrid_search`（Ranker 下推属 SDK 能力）→ 实际为 dense + sparse 两通道 `entities/search` + **客户端融合**（`ClientSideFusion` 纯函数，与 ES DD20 缺省路径同构，RRF/WEIGHTED）；sparse 通道不可用 → 异常上抛由 domain 回退应用侧 `EmbeddingRerankPort`（P10）。
- **客户端（DD16）**：官方 `io.milvus:milvus-sdk-java`（v2 SDK）。
  - ⚠️ **落地偏差**：以 **WebClient REST（RESTful v2）直连**替代官方 SDK——pom 无该依赖，重依赖易冲突且难离线确定性单测（验收硬约束）；详见 RoadMap 2.1 §10。
- **命名空间映射（DD18）**：`namespace` 作 partition-key（默认）vs 每命名空间一 collection。✅ 采用 partition-key + 布尔表达式强制过滤。
- **凭证**：`uri` + `username`/`password-ref`（经 `SecretResolver`）；`SsrfGuard` 校验；建议 TLS。✅ 已落地：解析为空/无 resolver fail-closed；明文仅注入 `Bearer` 认证头。

### 3.5 `VectorProperties` 配置类（`langur.vector.*`，补 B5）✅ 已落地（H14.4，2026-09-25）

```yaml
langur:
  vector:
    store: memory                 # memory | pgvector | elasticsearch | milvus（默认 memory，行为不变）
    dimension: 1536               # 必须与 EmbeddingPort.dimensions() 一致（启动守卫，见 3.7）
    hybrid:
      enabled: true               # 打开后：supportsHybrid 的 store 走原生下推，否则应用侧兜底
      mode: native                # native | app（app = 强制走 EmbeddingRerankPort）
      fusion: rrf                 # rrf | weighted（DD17）
      rrf-k: 60                   # RRF 常数 k
      lexical-weight: 0.3         # weighted 模式下词面权重
    elasticsearch:
      uris: http://localhost:9200
      index: langur_vectors
      username: ""
      password-ref: env:ES_PASSWORD
    milvus:
      uri: http://localhost:19530
      collection: langur_vectors
      username: ""
      password-ref: env:MILVUS_PASSWORD
    pgvector:
      # 补 B4：定义 vectorJdbcTemplate 数据源（url/username/password-ref）
      url: jdbc:postgresql://localhost:5432/langur
      username: postgres
      password-ref: env:PG_PASSWORD
```

### 3.6 混合检索策略（下推 vs 兜底，已定"下推优先"）

| 场景 | 路径 | 说明 |
|------|------|------|
| ES/Milvus + `hybrid.mode=native` | **原生下推** hybridSearch（服务端 BM25/稀疏 + ANN 融合） | 一次往返、可扩展、用得上服务端全文能力 |
| memory / pgvector | **应用侧** search + `EmbeddingRerankPort`（cosine + 词面覆盖） | 现状路径，`supportsHybrid()=false` 自动走此 |
| ES RRF 未授权（DD20） | ES 两查询 + **客户端 RRF 融合** | 降级不丢混合语义（P10） |
| 原生 hybrid 抛异常 | 捕获 → 回退纯向量 search + app-side rerank | 兜底不中断（P10） |
| 二次精排（可选） | 原生 hybrid 取 top-N 后再过 `EmbeddingRerankPort` | 两级检索，精度可选增强 |

> **融合算法（DD17）**：默认 **RRF**（Reciprocal Rank Fusion，基于排名、免分数归一化、跨检索器稳健）；`weighted` 需先归一化 BM25 与 cosine 分数（尺度不同，需谨慎）。
>
> **落地状态（H14.5/H14.6，2026-09-25）**：ES 缺省（`native-rrf=false`，DD20 已核实 RRF retriever 属付费层）与 Milvus（RESTful v2 无服务端 Ranker 下推）均走 **store 级两通道 + 客户端融合**（共用 `ClientSideFusion` 纯函数）；ES 原生 `retriever.rrf` 仅在授权环境显式开启，失败自动降级。对 domain 而言两 store 均 `supportsHybrid()=true`，`hybrid.mode=native` 语义不变。

### 3.7 维度一致性守卫（补 B6）✅ 已落地（H14.8，2026-09-25）

启动时校验 `langur.vector.dimension` == `EmbeddingPort.dimensions()`（lexical=256 / llm=1536）；不一致 → **fail-fast** 明确报错（或按 embedding 维度自动建索引/collection）。ES `dense_vector.dims`、Milvus `FLOAT_VECTOR.dim`、pgvector `vector(n)` 均由该维度驱动，杜绝硬编码 256 与 llm 1536 冲突导致的静默召回损坏。

### 3.8 pgvector 数据源修复（补 B4）✅ 已落地（H14.7，2026-09-25）

`store=pgvector` 时，start 按 `langur.vector.pgvector.*` 条件装配独立 `DataSource` + `vectorJdbcTemplate` Bean（当前缺失 → 装配失败）；`PgVectorStore` 同步实现新端口方法（delete/batch/filter/hybrid 视能力，pgvector 可 `supportsHybrid()=false` 走应用侧，或用 SQL 全文 `ts_rank` + 向量做 DB 侧融合，列为后续可选）。

> **落地说明**：`PgVectorDataSourceConfiguration` 刻意不暴露 `DataSource` 类型 Bean（避免 Boot `DataSourceAutoConfiguration` 的 `@ConditionalOnMissingBean` 退避顶掉业务库），内部构建 Hikari 池并经 `DisposableBean` 托管生命周期；启动即取连接 fail-fast——不可达时明确报错退出，绝不静默、绝不落回内存。`password-ref` 经 `SecretResolver`（fail-closed）。`PgVectorStore` 补齐 delete/JDBC batch/`search(SearchQuery)`（候选放大 + 谓词/minScore 后过滤，语义同内存实现）；`supportsHybrid()=false` 走应用侧兜底；postgresql 驱动加入 start（runtime）。SQL 全文 `ts_rank` DB 侧融合列为后续可选（未实施）。

### 3.9 DDD 落点

| 模块 | 变更 | 依赖约束 |
|------|------|----------|
| **langur-domain** | `VectorStore` 扩 default 方法（delete/upsertAll/search(SearchQuery)/supportsHybrid/hybridSearch）；新增 `SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode` VO；`VectorMemoryService.recall` 增原生分支 | 仅 common+lombok（P1）✅ |
| **langur-infrastructure** | `ElasticsearchVectorStore`、`MilvusVectorStore`；`VectorProperties`；ES/Milvus 客户端封装；凭证经 `SecretResolver`；`InMemory`/`PgVector` 补齐新 default 方法 | 实现 domain 端口（P3） |
| **langur-start** | 按 `store` 条件装配 store Bean；pgvector 数据源；启动维度守卫 | 装配 |

---

## 4. 与既有能力/v3.0 的关系（复用地图）

| 既有资产 | v2.1 复用点 |
|----------|-------------|
| `CompositeSecretResolver`/`KmsClient`（H7） | LLM `api-key-ref` + ES/Milvus `password-ref` 统一解析（env/prop/kms，fail-closed） |
| `SsrfGuard`（H7） | LLM `base-url`、ES `uris`、Milvus `uri` 远端校验 |
| `ServerCircuitBreaker` 模式（H6） | Provider 健康熔断（⑦）/ 向量库健康（G3） |
| `TokenUsage`（H1） | `complete()` usage 回传；Embedding 调用计量 |
| `MicrometerEvaluationService`（H5） | 新增网关/向量库指标（§5） |
| `CacheBackend`（H4） | （可选）Embedding 结果缓存 / 检索结果缓存 |
| `EmbeddingRerankPort`/`VectorMath`（H2） | 应用侧混合兜底 + 二次精排 |
| `KnowledgeRetrieval/IngestTool` | 直接受益于原生混合检索（召回质量↑），无需改工具 |
| **v3.0 决策平面（Jev）** | 正交：M2 EMBEDDING / M3 RERANK 供给更稳更省 → 决策平面运行期底座更可靠；无直接依赖 |

---

## 5. 可观测（接 H5 Micrometer）

| 指标 | 含义 |
|------|------|
| `llm_provider_latency` | 各 provider 往返延迟分布 |
| `llm_provider_errors` | 各 provider 失败计数（按 type/异常类） |
| `llm_fallback_count` | `fallback-chains` 实际触发次数（验证 A4 修复生效） |
| `llm_provider_circuit_state` | provider 熔断态（closed/open/half-open） |
| `vector_store_latency` | 向量库读写延迟 |
| `vector_search_mode` | 计数：`native-hybrid` vs `vector+app-rerank`（观测下推命中率） |
| `vector_store_docs` | 各命名空间文档量 |

---

## 6. 安全与合规

| 关注点 | 措施 |
|--------|------|
| **密钥** | LLM `api-key-ref`、ES/Milvus `password-ref`、pgvector `password-ref` 全经 `SecretResolver`（env/prop/kms），**绝不落日志/明文**；缺 KMS 后端 fail-closed |
| **SSRF** | 所有可配远端地址（base-url/uris/uri）经 `SsrfGuard`；自部署内网走白名单 |
| **数据驻留** | 敏感命名空间（合同/代码）→ **强制自部署 ES/Milvus**，禁发第三方云；LLM 侧同理（敏感场景走自部署/国内合规端点） |
| **传输** | ES/Milvus 建议 TLS；LLM 全 HTTPS |
| **越权** | 向量库 `SearchFilter` 必带 `namespace` 隔离，杜绝跨租户召回 |

---

## 7. 降级与兜底（P10）

| 失败点 | 降级 |
|--------|------|
| 某 provider 不可用 | `fallback-chains` 逐级 → default-provider → 明确报错（修复后真正生效） |
| provider 熔断跳闸 | 快速跳到链中下一个，不阻塞 |
| ES RRF 未授权 | 客户端 RRF/加权融合（DD20 降级） |
| 原生 hybrid 异常 | 回退纯向量 search + app-side rerank |
| ES/Milvus 不可用 | **fail-fast + 健康降级**（不静默转内存，避免数据丢失错觉）；启动期不可达可配置为"惰性重试" |
| Embedding 失败 | 本地哈希嵌入兜底（既有 P10） |

---

## 8. 设计原则契合

| 原则 | v2.1 体现 |
|------|-----------|
| **P1** domain 零依赖 | `VectorStore` 扩展 + 新 VO、`LLMPort.CompletionResult` 均纯 JDK |
| **P3** 依赖倒置 | domain 端口（`VectorStore`/`LLMPort`），infra 实现（ES/Milvus/工厂适配器） |
| **P5** SPI 开闭 | 配置驱动 Provider 工厂（零代码扩厂）；`VectorStore` default 方法（既有实现零改动） |
| **P9** 配置驱动 | `langur.llm.providers.*.type/model-prefixes/api-key-ref`、`langur.vector.{store,hybrid,elasticsearch,milvus,pgvector}` |
| **P10** 降级兜底 | fallback 修复、原生→应用侧混合兜底、RRF 授权降级、store fail-fast |

> **不新增原则**：v2.1 是既有原则在"模型供给 + 向量存储"两处的落地强化。（v3.0 的 P12 决策平面红线与此正交，不受影响。）

---

## 9. 成熟度分级（演进路径）

| 级别 | 能力 | 依赖 | 风险 |
|------|------|------|------|
| **G0** | 现状：固定 vendor Bean、memory/pgvector、应用侧混合 | — | 低（但扩厂改码、混合不下推） |
| **G1** | **网关统一**：配置工厂 + GLM + 前缀配置化 + 错误传播/fallback 修复 + usage + SecretResolver | H13.1–H13.6 | 低（**G1 达成**：H13.1–H13.6 ✅ 2026-09-25） |
| **G2** | **向量库可插拔**：端口扩展 + ES + Milvus + 原生混合下推 + VectorProperties + pgvector 修复 + 维度守卫 | H14.1–H14.8 | **G2 达成**：H14.1–H14.8 ✅ 2026-09-25（DD20 已核实：ES RRF retriever 属付费层，缺省客户端融合；DD15/DD16 偏差：REST/WebClient 直连替代官方 SDK，详见 RoadMap 2.1 §10） |
| **G3** | **弹性**：provider/store 熔断 + 健康探测 + 自动故障转移 + 检索缓存 | H13.7 / G2 | 中 |

---

## 10. 待决策项（DD14–DD21，详见 RoadMap 2.1 §8）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| DD14 | Provider 工厂形态 | 纯配置类型工厂 / 保留每厂类 + 配置补充 / 定型 `ModelProviderSPI` | **类型工厂**（openai-compatible 配置化，anthropic/gemini 专用模板），SPI 可选后置 |
| DD15 | ES 客户端 | 官方 `elasticsearch-java` / `spring-data-elasticsearch` | **官方客户端**（避免 Spring Data 锁定，贴合 WebClient 风格） |
| DD16 | Milvus 客户端 | 官方 `milvus-sdk-java` / REST | **官方 SDK** |
| DD17 | 融合算法 | RRF / 加权归一化 | **RRF**（免归一化、稳健） |
| DD18 | 命名空间映射 | 单索引/collection + namespace 字段（过滤/partition-key） / 每命名空间独立 | **单索引 + namespace**（ES keyword 过滤 / Milvus partition-key） |
| DD19 | 维度策略 | 全局单维 / 每命名空间维度 | **全局单维 + 启动守卫**（简化索引 schema） |
| DD20 | **ES RRF 授权** | 授权层内置 RRF retriever / 客户端融合降级 | **落地前核实当前版本授权**；未授权则客户端 RRF 融合（不阻断） |
| DD21 | `complete()` usage VO | 新增 `CompletionResult` default 方法 / 全量改走 `decide()` | **新增 `CompletionResult`**（向后兼容，改动小） |

---

## 11. 与 v2.0 / v3.0 的兼容与演进

- **不破坏 As-Built**：`store=memory` + 既有 provider 配置 → 行为与 v2.0 完全一致；所有端口扩展走 default 方法，既有 `InMemory`/`PgVector`/5 家适配器零改动即编译。
- **默认关闭**：GLM/ES/Milvus 均 `enabled=false`/需显式 `store=` 切换，未启用即降级现状路径（P10）。
- **前向兼容 v3.0**：M2/M3（Embedding/Rerank）供给更稳更省，为决策平面（Jev）运行期底座增益；两线正交，可独立推进。
- **文档维护约定**：每完成一个 H13.x/H14.x 子项，同步更新本文 §2/§3 落点标注与 §9 成熟度，并在 RoadMap 2.1 §11 完成记录补日期与说明；重大变更升版本号并保留历史。

---

> **附：术语对照**
> - **统一模型网关**：`LlmGateway`（角色→模型 + 降级）+ `LLMRouter`（前缀路由）+ 配置驱动 `ModelProviderFactory`（按 type 建适配器）。
> - **原生混合检索（下推）**：ES（BM25 + kNN，RRF）/ Milvus（稀疏 + dense，Ranker）在**服务端**融合全文与向量检索。
> - **应用侧混合（兜底）**：`EmbeddingRerankPort` 在进程内对纯向量召回结果做 `α·cosine + (1−α)·词面覆盖` 重排。
> - **RRF**：Reciprocal Rank Fusion，基于排名倒数融合多路检索结果，免分数归一化。


---


# 落地路线图与完成记录 · RoadMap 2.1（统一模型网关 + 可插拔向量库）

> **基线**：RoadMap 2.0 全部 H1–H10 已完成并提交（2026-09-25），六组件 As-Built ~95%，320 测试全绿；v2.0 六组件拓扑继续有效，本轮**只硬化两条既有接缝**（模型接入网关 + 向量存储端口），不新建子系统、不改 P4 正交拓扑
> **性质**：执行级路线图（可逐项派发）。本轮使用 **`H13`（统一模型网关）** + **`H14`（可插拔向量库与原生混合检索）** 编号，承接 2.0 的 `H*` 序列；子项以 `H13.x`/`H14.x` 计
> **使用方式**：逐项派发（如"完成 H13.1"），完成后将 `[ ]` 改为 `[x]` 并在 §10"完成记录"补日期与说明；每完成一项同步更新 v2.1 架构文档 §2/§3 落点标注与 §9 成熟度
> **状态**：本轮全部任务 **已完成（2026-09-26）——G1/G2/G3 全部达成**：H13.1–H13.6（网关统一 → G1，2026-09-25）+ H14.1–H14.8（向量库可插拔 + 原生混合 → G2，2026-09-25）+ **H13.7 provider 熔断（弹性硬化 → G3，2026-09-26）**；store 健康的 fail-fast 语义已由 H14.5/14.6/14.7（store 不可用 fail-fast，绝不静默兜底内存）落地。**与 RoadMap 3.0（J\*/R\* 决策平面 + RSI）正交**——v2.1 是 H 类基础设施硬化，不依赖也不触发 P11/RSI（3.0 亦已 2026-09-26 全量收官）
> **前置门**：**DD20（ES RRF 授权核实）必须在 H14.5 落地前解决**

---

## 1. 总览

### 1.1 里程碑划分（依赖驱动，非日历驱动）

```
N-G1 网关统一 ──────▶ N-G2 向量库适配 ──────▶ N-G3 弹性硬化（可选）
 (配置工厂/降级/密钥)    (端口扩展/ES/Milvus/原生混合)   (熔断/健康/故障转移)
 H13.1–H13.6           H14.1–H14.8                  H13.7 + store 健康
   ↓                     ↓                            ↓
 达 G1 网关统一         达 G2 向量库可插拔             达 G3 弹性
```

> **核心逻辑**：两条接缝（`LlmGateway→LLMRouter→ModelRoutableLLMPort` 与 `VectorStore`）**已就位**，本轮是"配置驱动化 + 补齐适配器 + 修复降级"。**先统一网关（N-G1）**——把"每厂一类"升级为"配置工厂"、补齐 GLM、修复错误传播使 `fallback-chains` 真正生效、Key 过 `SecretResolver`；**再插拔向量库（N-G2）**——扩展 `VectorStore` 端口、加 ES/Milvus 两实现、落地**原生混合检索下推**（应用侧 `EmbeddingRerankPort` 兜底）。**N-G3 弹性**为可选增强。
>
> **成熟度映射（对齐 v2.1 §9）**：`H13.1–H13.6 → G1`；`H14.1–H14.8 → G2`；`H13.7 + store 健康 → G3`。

### 1.2 任务总表

| ID | 任务 | 里程碑 | 优先级 | 依赖 | 估算 | 类型 |
|----|------|--------|--------|------|------|------|
| **H13.1** | 配置驱动 Provider 类型工厂（`ProviderProperties` 扩 `type/modelPrefixes/apiKeyRef` + `ModelProviderFactory`，既有 5 家迁移为配置构造） | N-G1 | P0 | — | M | 模型网关 |
| **H13.2** | GLM/智谱补齐（纯配置验证工厂，零代码扩厂） | N-G1 | P1 | H13.1 | S | 模型网关 |
| **H13.3** | 可配置 `model-prefixes`（`supportsModel` 前缀配置化，替代硬编码） | N-G1 | P1 | H13.1 | S | 模型网关 |
| **H13.4** | 错误传播修复（`ModelProviderException` + `withFallback` 捕获降级，使 `fallback-chains` 生效） | N-G1 | P0 | — | M | 模型网关 |
| **H13.5** | `complete()` 回传 usage（`CompletionResult` VO + `completeWithUsage` default，接 H1 计量） | N-G1 | P1 | H1 | S | 模型网关 |
| **H13.6** | API Key 经 `SecretResolver`（`api-key-ref` env/prop/kms，绝不落日志，接 H7） | N-G1 | P0 | H7 | S | 模型网关 |
| **H13.7** | Provider 健康熔断（复用 H6 `ServerCircuitBreaker`，每 provider 独立，快速跳到降级链下一个） | N-G3 | P2 | H13.4 H6 | M | 弹性（可选） |
| **H14.1** | `VectorStore` 端口扩展（`delete`/`upsertAll`/`search(SearchQuery)`/`supportsHybrid`/`hybridSearch`，均 default 方法向后兼容） | N-G2 | P0 | — | M | 向量库 |
| **H14.2** | domain 值对象（`SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode`，纯 JDK） | N-G2 | P0 | — | S | 向量库 |
| **H14.3** | `VectorMemoryService.recall` 原生分支（`supportsHybrid()` → 原生下推；否则 search + 应用侧 rerank 兜底） | N-G2 | P0 | H14.1 H14.2 | S | 向量库 |
| **H14.4** | `VectorProperties` 配置类（`langur.vector.{store,dimension,hybrid,elasticsearch,milvus,pgvector}`） | N-G2 | P0 | — | S | 向量库 |
| **H14.5** | `ElasticsearchVectorStore`（dense kNN + BM25 + RRF 原生混合；DD15 客户端 / **DD20 授权核实**） | N-G2 | P1 | H14.1 H14.2 H14.4 | L | 向量库 |
| **H14.6** | `MilvusVectorStore`（dense + sparse + `RRFRanker`/`WeightedRanker` 原生混合；DD16 SDK） | N-G2 | P1 | H14.1 H14.2 H14.4 | L | 向量库 |
| **H14.7** | pgvector 数据源修复（条件装配 `vectorJdbcTemplate`）+ 端口补齐 | N-G2 | P2 | H14.1 | S | 向量库 |
| **H14.8** | 维度一致性守卫（启动校验 `vector.dimension == EmbeddingPort.dimensions()`，不一致 fail-fast） | N-G2 | P1 | H14.4 | S | 向量库 |

> 估算：S≈0.5–1 人日，M≈2–4 人日，L≈5–10 人日（单人熟手，含测试）。H14.5/H14.6 的 ES/Milvus 融合逻辑须可**离线确定性单测**（stub/testcontainers），不依赖真实服务。

### 1.3 参考时间线（假设 1–2 名后端，仅供排期参考）

```
周次:  1     2     3     4     5     6     7     8
N-G1  [H13.1][H13.4][H13.6][H13.2/3][H13.5]              ← G1 网关统一（可即刻派发）
N-G2        [H14.1/2][H14.3/4][H14.5      ][H14.6      ][H14.7/8]  ← G2 向量库（DD20 先决）
N-G3                                                  [H13.7 + store 健康]  ← G3 弹性（可选）
```

> N-G1、N-G2 相互独立，可并行；N-G2 的 H14.5 以 **DD20（ES RRF 授权）** 为前置门。

---

## 2. 派发约定（承接 2.0，强化 P1/P3/P5/P9/P10）

- **构建验证**：Maven 必须跑在 **JDK 17**（`export JAVA_HOME=/Users/cn-artisanzhou/Library/Java/JavaVirtualMachines/temurin-17.0.19/Contents/Home`，Lombok 在 JDK 22+/26 中断）；`mvn clean test` 全绿；**装配变更需打包实际启动冒烟**（`/actuator/health` UP、新 Bean 无歧义、无异常日志）。
- **架构红线**：
  - **P1**：domain 新增（`VectorStore` 扩展、`SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode`、`LLMPort.CompletionResult`）**纯 JDK，零外部依赖**。
  - **P3**：ES/Milvus/工厂适配器等实现放 infra，domain 仅依赖端口。
  - **P5**：`VectorStore` 扩展**全走 default 方法**，既有 `InMemoryVectorStore`/`PgVectorStore` **零改动即编译**；Provider 工厂对既有 5 家适配器**复用逻辑、仅改注册方式**。
  - **P9**：新增 OpenAI 兼容厂商 = **纯 YAML**（`providers.<name>.type: openai-compatible`），零 Java；向量库切换 = `langur.vector.store`。
  - **P10**：`fallback-chains` 修复后**实测可触发**；原生混合不支持/异常 → **应用侧 `EmbeddingRerankPort` 兜底**；ES RRF 未授权 → **客户端融合降级**；store 不可用 → **fail-fast + 健康降级，绝不静默转内存**（防数据丢失错觉）。
- **密钥红线**：LLM `api-key-ref`、ES/Milvus/pgvector `password-ref` 一律经 `SecretResolver`（env/prop/kms），**绝不落日志/明文**；缺 KMS 后端 fail-closed（与 H7 一致）。
- **DoD（每项）**：代码 + 纯 JUnit5 测试（domain **禁用 Mockito**，用匿名/内部类 stub；`shouldXxx` 命名；Javadoc 标任务 ID）+ 文档同步（v2.1 §2/§3 落点 + §9 成熟度 + 本 §10 完成记录）；`mvn clean test` 全绿；涉装配则启动冒烟。
- **前置门**：**DD20（ES RRF 授权）在 H14.5 落地前核实**——确认目标 ES 版本 RRF retriever 授权层；未授权则实现"两查询 + 客户端 RRF 融合"降级路径。

---

## 3. N-G1：统一模型网关（H13）

> 目标：把"每厂一个硬编码 `@ConditionalOnProperty` 类"升级为"**配置驱动的 Provider 类型工厂**"，补齐 GLM，修复降级链，统一密钥与计量。**达成 G1。**

### H13.1 配置驱动 Provider 类型工厂（P0，—，M）
- `ProviderProperties` 扩 `type`（`openai-compatible|anthropic|gemini`，缺省 openai-compatible）、`modelPrefixes`、`apiKeyRef`。
- 新增 infra `ModelProviderFactory`：遍历 `enabled=true` 的 `providers.*`，按 `type` 实例化适配器模板，产出 `List<ModelRoutableLLMPort>` 供 `LLMRouter` 注入。
- 既有 5 家（OpenAI/Qwen/DeepSeek/Claude/Gemini）迁移为**配置构造**；保留内置 `openai` 默认（matchIfMissing 语义）。
- **验收**：新增一个 OpenAI 兼容厂商**仅改 YAML** 即被 `LLMRouter` 识别路由；既有 5 家行为回归不变。

### H13.2 GLM/智谱补齐（P1，H13.1，S）
- 纯配置启用 `glm`（`type: openai-compatible`, `base-url: https://open.bigmodel.cn/api/paas/v4`, `model-prefixes: [glm-, chatglm-]`, `api-key-ref: env:GLM_API_KEY`）。
- **验收**：`glm-4-plus` 经工厂路由命中，零 Java 代码——验证 H13.1 工厂的"零代码扩厂"。

### H13.3 可配置 model-prefixes（P1，H13.1，S）
- `ModelRoutableLLMPort.supportsModel` 改匹配配置的 `modelPrefixes` 任一前缀；未配置回退按 `provider.model` 前缀。
- **验收**：改 YAML 前缀即改路由，无需改码；多前缀（`glm-`/`chatglm-`）生效。

### H13.4 错误传播修复 + fallback 生效（P0，—，M）
- 适配器传输/HTTP/反序列化失败 → 抛 infra `ModelProviderException`（不再吞成 `"Error: ..."`）。
- `LlmGateway.withFallback` 捕获 → 试 `fallback-chains` 下一个；链耗尽抛 `IllegalStateException`。
- **边界**：模型正常返回内容含 "error" 文本**不算失败**（只传输层异常才 throw）。
- **验收**：主模型模拟失败 → 实测降级到备用模型（修复前 `llm_fallback_count` 恒 0）。

### H13.5 `complete()` 回传 usage（P1，H1，S）
- domain `LLMPort` 增 `CompletionResult{content,usage}` VO + `completeWithUsage` default（默认委派 `complete`，usage 空）。
- OpenAI 兼容基类 override 解析 `usage`（复用 H1 `parseUsage`）；补全路径纳入真实计量。
- **验收**：`completeWithUsage` 回传非空 usage（provider 提供时），指标区分 real/estimated（承接 H1）。

### H13.6 API Key 经 SecretResolver（P0，H7，S）
- `ProviderProperties.apiKeyRef`（`env:/prop:/kms:`）优先于明文 `apiKey`，经 H7 `CompositeSecretResolver` 解析；结果只注入请求头，**绝不落日志**；缺 kms 后端 fail-closed。
- **验收**：`api-key-ref: env:X` / `kms:Y` 正确解析；日志无密钥；kms 缺失时明确 fail-closed。

### H13.7 Provider 健康熔断（P2，H13.4 H6，M — N-G3 可选）
- 复用 H6 `ServerCircuitBreaker`，每 provider 独立：连续失败跳闸 → 快速跳到降级链下一个 → 冷却半开试探；暴露 `llm_provider_circuit_state`。
- **验收**：provider 持续失败触发熔断，后续请求快速降级不阻塞；冷却后试探恢复。

---

## 4. N-G2：可插拔向量库 + 原生混合检索（H14）

> 目标：把"薄端口 + 应用侧混合"升级为"**厚端口 + 原生混合下推**（应用侧兜底）"，补齐 ES/Milvus。**达成 G2。**

### H14.1 `VectorStore` 端口扩展（P0，—，M）
- default 方法：`upsertAll`、`delete`、`search(SearchQuery)`、`supportsHybrid()`（默认 false）、`hybridSearch(HybridQuery)`（默认抛 UnsupportedOperation）。
- 既有 `search(ns,float[],topK)` 保留并转调 `search(SearchQuery)`。
- **验收**：`InMemoryVectorStore`/`PgVectorStore` **零改动即编译**（P5）；新能力默认关闭不影响现状。

### H14.2 domain 值对象（P0，—，S）
- `SearchQuery`/`HybridQuery`/`SearchFilter`(EQ/IN/GT/LT/GTE/LTE/EXISTS)/`FusionMode`(RRF/WEIGHTED)，纯 JDK（P1）。
- **验收**：VO 不可变、可离线单测；`SearchFilter` 必带 namespace 隔离语义。

### H14.3 `VectorMemoryService.recall` 原生分支（P0，H14.1 H14.2，S）
- `hybrid.enabled && supportsHybrid()` → `hybridSearch`（原生下推）；否则 search + `EmbeddingRerankPort`（应用侧兜底，现状路径）；原生异常 → 回退纯向量 + app rerank。
- **验收**：memory/pgvector 路径**完全不变**；mock 一个 `supportsHybrid()=true` 的 store 验证走原生分支 + 异常兜底。

### H14.4 `VectorProperties` 配置类（P0，—，S）
- `@ConfigurationProperties("langur.vector")`：`store`/`dimension`/`hybrid.{enabled,mode,fusion,rrf-k,lexical-weight}`/`elasticsearch.*`/`milvus.*`/`pgvector.*`。
- **验收**：配置绑定正确；缺省 `store=memory` 行为不变。

### H14.5 `ElasticsearchVectorStore`（P1，H14.1 H14.2 H14.4，L）
- mapping：`content`(text/BM25，中文可选 ik/smartcn) + `embedding`(dense_vector, cosine, dims=配置) + `namespace`(keyword)。
- 纯向量 `knn`；原生混合 `retriever.rrf`(standard BM25 + knn)。**DD20**：RRF 未授权 → 两查询 + 客户端 RRF 融合降级。
- 客户端 **DD15**（倾向官方 `co.elastic.clients:elasticsearch-java`）；`uris` 经 `SsrfGuard`；`password-ref` 经 `SecretResolver`。
- **验收**：离线（stub/testcontainers）验证 upsert/knn/混合融合/namespace 过滤/delete；RRF 授权降级路径可切换。

### H14.6 `MilvusVectorStore`（P1，H14.1 H14.2 H14.4，L）
- schema：`id`/`namespace`(partition-key)/`content`/`embedding`(FLOAT_VECTOR,HNSW,COSINE)/`sparse`(SPARSE_FLOAT_VECTOR，Milvus 2.5+ BM25 function 或外接稀疏)。
- 原生混合 `hybrid_search`（dense + sparse 两 `AnnSearchRequest` + `RRFRanker`/`WeightedRanker`）。
- 客户端 **DD16**（官方 `io.milvus:milvus-sdk-java`）；`uri` 经 `SsrfGuard`；`password-ref` 经 `SecretResolver`。
- **验收**：离线（stub/testcontainers）验证 upsert/dense 检索/稀疏混合/namespace partition 过滤/delete。

### H14.7 pgvector 数据源修复 + 端口补齐（P2，H14.1，S）
- start 按 `langur.vector.pgvector.*` 条件装配 `DataSource` + `vectorJdbcTemplate`（补 B4，当前 `store=pgvector` 装配失败）；`PgVectorStore` 实现新 default 方法（可 `supportsHybrid()=false` 走应用侧）。
- **验收**：`store=pgvector` 可装配启动（有数据源时）；无数据源时明确报错不静默。

### H14.8 维度一致性守卫（P1，H14.4，S）
- 启动校验 `langur.vector.dimension == EmbeddingPort.dimensions()`（lexical 256 / llm 1536），不一致 fail-fast（或按 embedding 维度自动建索引）；ES/Milvus/pgvector 索引维度均由此驱动。
- **验收**：维度不匹配启动即明确报错；匹配时索引/collection schema 维度正确。

---

## 5. N-G3：弹性硬化（可选，G3）

- **H13.7** Provider 健康熔断（见 §3）。
- **向量库健康/故障转移**：ES/Milvus 健康探测 + 不可用 fail-fast + 可选检索结果缓存（复用 H4 `CacheBackend`）。
- **自动故障转移**：多 store/多 provider 主备切换（承接 `fallback-chains` 思路到存储侧）。
- > N-G3 为增强项，非必需；G1/G2 达成即满足"统一网关 + 可插拔向量库"核心诉求。

---

## 6. 待决策项（DD14–DD21）

| # | 决策点 | 选项 | 倾向 | 影响任务 |
|---|--------|------|------|----------|
| DD14 | Provider 工厂形态 | 纯配置类型工厂 / 每厂类 + 配置补充 / 定型 `ModelProviderSPI` | **类型工厂**（openai-compatible 配置化，anthropic/gemini 专用模板），SPI 可后置 | H13.1 |
| DD15 | ES 客户端 | 官方 `elasticsearch-java` / `spring-data-elasticsearch` | **官方客户端**（避免 Spring Data 锁定） | H14.5 |
| DD16 | Milvus 客户端 | 官方 `milvus-sdk-java` / REST | **官方 SDK** | H14.6 |
| DD17 | 融合算法 | RRF / 加权归一化 | **RRF**（免归一化、跨检索器稳健） | H14.5 H14.6 |
| DD18 | 命名空间映射 | 单索引/collection + namespace 字段 / 每命名空间独立 | **单索引 + namespace**（ES keyword / Milvus partition-key） | H14.5 H14.6 |
| DD19 | 维度策略 | 全局单维 / 每命名空间维度 | **全局单维 + 启动守卫** | H14.4 H14.8 |
| **DD20** | **ES RRF 授权** | 授权层内置 RRF retriever / 客户端融合降级 | **落地前核实当前版本授权**；未授权则客户端 RRF 融合（不阻断） | **H14.5（前置门）** |
| DD21 | `complete()` usage VO | 新增 `CompletionResult` default / 全量改走 `decide()` | **新增 `CompletionResult`**（向后兼容，改动小） | H13.5 |

---

## 7. KPI（验收基线）

| 维度 | 指标 | 目标 |
|------|------|------|
| **扩厂成本** | 新增 OpenAI 兼容厂商所需 Java 代码 | **0 行**（纯配置） |
| **模型覆盖** | 国内外主流模型适配家数 | ≥ 6（OpenAI/Claude/Gemini/Qwen/GLM/DeepSeek） |
| **降级有效性** | `llm_fallback_count` 实测触发 | > 0（修复前恒 0） |
| **混合下推** | `vector_search_mode=native-hybrid` 占比（ES/Milvus 启用时） | 主流路径走原生 |
| **召回质量** | 原生混合 vs 纯向量 top-k 命中提升（离线评测集） | 正向提升 |
| **维度安全** | 启动期维度不一致拦截率 | 100% fail-fast |
| **密钥安全** | 日志/明文出现密钥次数 | 0 |
| **构建** | `mvn clean test`（JDK 17）+ 启动冒烟 | 全绿 + `/actuator/health` UP |

---

## 8. 风险登记

| 风险 | 影响面 | 缓解 |
|------|--------|------|
| **ES RRF 授权不确定** | H14.5 | DD20 前置核实 + 客户端 RRF 融合降级（不阻断） |
| ES/Milvus 版本漂移 | H14.5 H14.6 | 锁定客户端版本 + 契约测试 + testcontainers |
| 中文分词插件（ik/smartcn）部署依赖 | H14.5 | analyzer 可选配置，缺省 standard + 应用侧词面覆盖兜底 |
| 维度不一致静默损坏召回 | 全向量库 | H14.8 启动守卫 fail-fast |
| store 不可用误转内存丢数据 | H14.x | fail-fast + 健康降级，**绝不静默兜底内存** |
| Provider 工厂迁移回归既有 5 家 | H13.1 | 充分单测 + 保留内置 openai 默认 + 行为回归 |
| 构建环境 JDK 漂移 | 全项目 | 承接 H11（RoadMap 3.0 N5）toolchain 固定 + CI 校验 |

---

## 9. 与 RoadMap 2.0 / 3.0 的关系

- **承接 v2.0 As-Built**：本轮硬化 v2.0 §6「LLM 供给」与 §12「存储分层 L4」两处既有接缝，**不改六组件正交拓扑（P4）**。
- **与 RoadMap 3.0 正交**：3.0 是 `J*`（决策平面/Jev）+ `R*`（RSI）线；v2.1 是 `H*`（基础设施硬化）线。二者独立排期，v2.1 **不触发 P11/RSI**。M2 EMBEDDING / M3 RERANK 供给更稳更省，为 v3.0 决策平面运行期底座增益。
- **H11/H12**（依赖治理 / 多 Agent）仍保留在 **RoadMap 3.0 N5**（待派发），不在本轮。

---

## 10. 完成记录

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-09-25 | RoadMap 2.1 制定 | 依据 v2.1 架构设计，拆解为 N-G1/N-G2/N-G3 里程碑、H13（统一模型网关，7 子项）+ H14（可插拔向量库与原生混合检索，8 子项）、DD14–DD21、KPI 与风险登记；全部**待派发**；DD20（ES RRF 授权）设为 H14.5 前置门 |
| 2026-09-25 | H13.1 完成 | 配置驱动 Provider 类型工厂落地：`ProviderProperties` 扩 `type/apiKeyRef/modelPrefixes`（`enabled` 改 `Boolean` 保留 openai matchIfMissing 语义）；新增 `ModelProviderFactory`（内置 6 家模板默认 + 按 type 实例化）、`ConfigurableOpenAICompatibleLLMAdapter`、`ModelPrefixes`；Claude/Gemini 改配置构造模板，删除 OpenAI/Qwen/DeepSeek 每厂硬编码类；start 新增 `ModelProviderConfiguration` 产出 `List<ModelRoutableLLMPort>` Bean。8 项工厂单测 + 既有回归全绿（227 测试）；打包启动冒烟 `/actuator/health` UP |
| 2026-09-25 | H13.4 完成 | 错误传播修复：新增 `ModelProviderException`，三类适配器（OpenAI 兼容基类/Claude/Gemini）传输/非 2xx/反序列化失败一律 throw（不再吞成 "Error: ..."）；边界——正常返回内容含 "error" 文本不算失败；异常消息不拼底层文本防 Gemini 查询参 key 泄露；`LlmGateway.withFallback` 捕获降级并新增 `langur.harness.llm_fallback_count` 计数（role/model/error 标签，接 H5）。6 项离线确定性单测（JDK HttpServer 桩 + 拒绝连接端点）实测主失败→降级备用、链耗尽抛 IllegalStateException；233 测试全绿；启动冒烟 UP |
| 2026-09-25 | H13.6 完成 | LLM API Key 经 H7 `SecretResolver` 解析：`ModelProviderFactory.create` 新增 3 参重载，`api-key-ref`（env:/prop:/kms:）解析成功时注入配置副本（原配置不改写），优先于明文 `api-key`；解析为空回退明文（向后兼容）；`kms:` 引用缺失 KMS 后端时 fail-closed 抛 `IllegalStateException`（绝不静默回退）。`LlmEmbeddingPort` 同链路接入（`@Autowired ObjectProvider<SecretResolver>` 构造 + 4 参测试兼容构造）；start `ModelProviderConfiguration` 传入 resolver；application.yml openai 增 `api-key-ref: env:OPENAI_API_KEY` 示例。6 项离线单测（HttpServer 桩捕获 Authorization 头）验证注入/优先级/回退/fail-closed/密钥不落异常消息；239 测试全绿；启动冒烟 UP（factory created 1 adapter） |
| 2026-09-25 | H13.2/H13.3 完成 | GLM 纯配置接入 + 可配置 model-prefixes：application.yml 新增 `glm` provider（`type: openai-compatible`、`base-url: open.bigmodel.cn/api/paas/v4`、`api-key-ref: env:GLM_API_KEY`、前缀 `[glm-, chatglm-]`，默认 enabled:false 与其他厂商一致），既有 5 家补显式 `model-prefixes`；零 Java 代码。4 项离线单测（HttpServer 桩）验证 `glm-4-plus`/`chatglm-6` 纯配置路由命中、非配置前缀不命中、YAML 前缀覆盖 provider.model 派生、未配置前缀回退按 model 首段派生、无命中回退 default-provider；243 测试全绿；启动冒烟 UP |
| 2026-09-25 | H13.5 完成 | `complete()` 回传 usage：domain `LLMPort` 增 `CompletionResult{content,usage}` VO（null usage 归一空计量）+ `completeWithUsage` default（委派旧 complete，向后兼容）+ `TokenUsage.empty()`；OpenAI 兼容基类 override 解析响应 usage（复用 H1 `parseUsage`，complete() 改为委派）；`LLMRouter`/`LlmGateway` 透传（降级链语义一致），补全路径纳入 H1 real/estimated 计量。3 项 domain 单测（内部类 stub，禁 Mockito）+ 4 项 infra 离线单测（HttpServer 桩）验证真实/空 usage、旧契约回归、主失败→备用仍透传 usage 且 fallback 计数触发；351 测试全绿（domain 104/infra 230/api+app 17）。**G1（H13.1–H13.6）达成** |
| 2026-09-25 | H14.1/H14.2 完成 | `VectorStore` 端口扩展 + domain VO（P5 开闭）：新增 default `upsertAll(ns,records)`/`delete(ns,id)`/`search(SearchQuery)`/`supportsHybrid()`（false）/`hybridSearch(HybridQuery)`（抛 UnsupportedOperation），既有 `search(ns,float[],topK)` 保留；新增纯 JDK VO `SearchQuery`/`HybridQuery`（防御性副本 + withXxx 派生，不可变）/`SearchFilter`（EQ/IN/GT/LT/GTE/LTE/EXISTS，AND 语义，跨数值类型比较，null metadata 安全）/`FusionMode`（RRF/WEIGHTED，默认 RRF、rrf-k=60、lexical-weight=0.3）；namespace 为一等隔离字段不走 filter（越权红线）。`InMemoryVectorStore` 覆写 `search(SearchQuery)`（filter+minScore，minScore≤0 无阈值保持既有路径不变）与 `delete`。9 项 domain VO 单测 + 7 项 infra 单测；`InMemoryVectorStore`/`PgVectorStore` 零改动即编译（P5 验证）；367 测试全绿（domain 113/infra 237/api+app 17） |
| 2026-09-25 | H14.3/H14.4 完成 | `VectorMemoryService.recall` 原生混合分支 + `VectorProperties` 配置类：domain 新增 `HybridSearchOptions`（纯 JDK，缺省 disabled），recall 仅当 `enabled && mode=native && store.supportsHybrid()` 走 `hybridSearch` 下推，原生异常 try/catch 回退 search+应用侧 rerank（P10 不中断），否则完全走既有路径（memory/pgvector 不变）；融合参数（RRF/WEIGHTED、rrf-k、lexical-weight）透传进 `HybridQuery`。infra 新增 `@ConfigurationProperties("langur.vector")` `VectorProperties`（store/dimension/hybrid.*/elasticsearch.*/milvus.*/pgvector.*，password-ref 引用不落明文）；start `HarnessConfiguration` 将 `hybrid.*` 映射为 `HybridSearchOptions` 注入 bean；application.yml 补 hybrid（默认 enabled:false）与 ES/Milvus/pgvector 注释示例。8 项 domain recall 分支单测（内部类 stub，含原生/异常兜底/mode=app/缺省/无能力/预算截断/参数透传）+ 3 项 infra 绑定单测（Binder 离线）；378 测试全绿（domain 121/infra 240/api+app 17）；打包启动冒烟 UP（默认 memory + hybrid off 无 bean 错误） |
| 2026-09-25 | H14.8 完成 | 维度一致性守卫（DD19 全局单维，补 B6）：start 新增 `@Component VectorDimensionGuard`（`@PostConstruct` fail-fast），校验 `langur.vector.dimension` 与 `EmbeddingPort.dimensions()`（lexical=256/llm=embedding.dimensions）一致；不一致抛 `IllegalStateException`（消息含两侧维度，杜绝静默召回损坏），配置留空（≤0）则由 embedding 驱动不校验（向后兼容）。ES/Milvus/pgvector 索引维度均以 `EmbeddingPort.dimensions()` 为准。因 start 主源不含 lombok，改用 `org.slf4j` 直记日志（与既有 `LoggingSpanExporter` 一致）。6 项离线单测（纯 stub）验证未配置派生/一致通过/不一致 fail-fast/PostConstruct 双路径；384 测试全绿（domain 121/infra 240/start 23）；启动冒烟 UP，守卫日志 `effective=256 (configured=0, embedding=256)` |
| 2026-09-25 | H14.5 完成（DD20 前置门已核实关闭） | **DD20 核实结论：ES `retriever.rrf` 属 Platinum+ 付费授权层**（Elastic 官方 Search Labs 与社区多源证实），故缺省 `native-rrf=false`，混合检索走"BM25 match + kNN 两查询 + 客户端 RRF 融合"（免授权，DD17）；授权环境可显式开启原生 `retriever.rrf`，任何原生失败（400/403/版本不支持）自动降级客户端融合（P10 不中断）。infra 新增 `ElasticsearchVectorStore`（`@ConditionalOnProperty store=elasticsearch`，默认 memory 行为不变）：单索引 + `namespace` keyword 强制过滤（DD18 越权红线）、`embedding` dense_vector cosine（首写 best-effort 建索引，dims 由 `vector.dimension`>0 或首条记录驱动）、`_id=namespace::id`（URL 编码）幂等 UPSERT、`_bulk` 批量、`delete` 幂等（404 容忍）、knn `_score=(1+cos)/2` 还原裸 cosine、`SearchFilter` 谓词翻译（term/terms/range/exists）、minScore 客户端过滤；RRF/WEIGHTED 融合为纯静态函数可离线单测。安全：`password-ref` 经 `SecretResolver` 仅注入 Basic 认证头（解析为空/无 resolver 均 fail-closed，绝不静默匿名；明文不落日志/异常/请求体），端点经 `SsrfGuard`（scheme 限 http/https；管理侧配置 host 即显式白名单——URI 来自受信配置非模型输出，不属 SSRF 攻击面）。**DD15 偏差**：以 WebClient REST 直连替代官方 `elasticsearch-java` SDK——pom 无该依赖，重依赖易冲突且难离线确定性单测（验收硬约束），REST 契约与 SDK 等价且贴合工程既有 WebClient 风格。14 项离线单测（JDK HttpServer ES 桩：upsert/建索引映射/认证头/密码不落体/knn+namespace 过滤/谓词翻译+minScore/客户端 RRF/WEIGHTED/native 授权单请求/native 400 自动降级/delete 幂等/_bulk/融合纯函数×2）；398 测试全绿（domain 121/infra 254/start 23）；打包启动冒烟 UP（默认 memory，新条件 bean 未装配，无 bean 错误） |
| 2026-09-25 | H14.6 完成 | infra 新增 `MilvusVectorStore`（`@ConditionalOnProperty store=milvus`，默认 memory 行为不变）：**DD16 偏差**——以 Milvus RESTful v2（WebClient）直连替代官方 `milvus-sdk-java`（理由同 DD15：pom 无依赖、重依赖难离线单测；且 RESTful v2 未暴露服务端 `hybrid_search`/RRFRanker 下推，该能力属 SDK）。schema best-effort 自建：`id`(VarChar PK)/`namespace`(VarChar **partition-key**，DD18)/`content`(VarChar enable_match)/`metadata`(JSON)/`embedding`(FloatVector HNSW COSINE，dims 由 `vector.dimension`>0 或首条记录驱动)/`sparse`(SparseFloatVector) + **BM25 function**（2.5+ 由 content 自动生成稀疏向量）。混合检索：dense + sparse 两通道 `entities/search`（sparse 通道直传查询文本走 BM25 全文）+ **客户端融合**（与 ES DD20 缺省路径同构，融合逻辑抽为共用 `ClientSideFusion` 纯函数，RRF/WEIGHTED）；sparse 通道不可用（无 BM25 function/旧版本）→ 异常上抛由 domain 回退 search+应用侧 rerank（P10 不中断）；store 不可用 fail-fast 绝不静默转内存。upsert/upsertAll 走原生批量、delete 按 `namespace && id` 布尔表达式、COSINE distance 即裸 cosine 同内存语义、minScore 客户端过滤、谓词翻译 `metadata["f"]` 表达式（引号/反斜杠转义防表达式注入）。安全：`password-ref` 经 `SecretResolver` 仅注入 `Bearer user:pass` 头（解析为空/无 resolver fail-closed），端点经 `SsrfGuard`。10 项离线单测（JDK HttpServer Milvus 桩：upsert+schema+认证/密码不落体/fail-closed/批量单请求/delete 表达式/dense+namespace 过滤+minScore/谓词翻译/引号转义/双通道 RRF 融合/sparse 失败上抛）+ 3 项 `ClientSideFusion` 纯函数单测（自 ES 测试迁移共用）；409 测试全绿（domain 121/infra 265/start 23）；打包启动冒烟 UP（默认 memory，新条件 bean 未装配，无 bean 错误） |
| 2026-09-25 | H14.7 完成（G2 全部达成：H14.1–H14.8） | pgvector 数据源修复（补 B4）：start 新增 `PgVectorDataSourceConfiguration`（`@ConditionalOnProperty store=pgvector`），按 `langur.vector.pgvector.*` 构建独立 Hikari 数据源 + `vectorJdbcTemplate` Bean——刻意不暴露 `DataSource` 类型 Bean（避免 Boot `DataSourceAutoConfiguration` 的 `@ConditionalOnMissingBean` 退避顶掉业务库），池生命周期经 `DisposableBean` 托管；启动即取连接 **fail-fast**（不可达明确报错退出，绝不静默、绝不落回内存，P10）；`password-ref` 经 `SecretResolver`（解析为空/无 resolver 均 fail-closed，明文仅注入连接池）；start pom 补 `org.postgresql:postgresql`（runtime）。`PgVectorStore` 补齐 v2.1 端口：`delete`（namespace+id）、`upsertAll`（JDBC batch）、`search(SearchQuery)`（候选放大 max(topK*5,50) + `SearchFilter` 谓词/minScore store 层后过滤，jsonb 谓词不下推避免方言差异，语义与内存实现一致不静默丢弃）；`supportsHybrid()=false` 走应用侧 rerank 兜底；构造参数加 `@Qualifier("vectorJdbcTemplate")`。SQL 全文 `ts_rank` DB 侧融合列为后续可选（未实施）。6 项 infra 单测（Mockito mock JdbcTemplate：batch/delete SQL/后过滤+放大/无过滤不放大/空入参/能力位）+ 4 项 start 单测（H2 内存库代打 PG 验证装配可查询、不可达 fail-fast、password-ref fail-closed×2）；419 测试全绿（domain 121/infra 271/start 27）；三重冒烟：默认 memory UP、`store=pgvector`（H2 代打）UP 且日志 `pgvector datasource assembled`、`store=pgvector` 指向不可达 PG 时启动 exit=1 且报 `pgvector datasource unavailable`（未 Started） |
| 2026-09-26 | H13.7 完成（G3 弹性硬化达成） | **Provider 健康熔断落地**（复用 H6 `ServerCircuitBreaker` 状态机语义、通用化解耦 MCP）：infra 新增 `ProviderCircuitBreaker`（name/threshold/cooldownMillis，`isOpen()`/`recordFailure()`/`recordSuccess()`，纯 JDK 零外部依赖）；`LlmProperties` 增嵌套 `CircuitBreaker`（enabled=true/threshold=3/cooldownSeconds=30，`langur.llm.circuit-breaker.*`）+ `providerOf(model)`（精确匹配 provider.model → model-prefixes 前缀 → 模型名自身作隔离键）；`LlmGateway.withFallback` 集成——每 provider 懒建熔断器，跳闸冷却期内**快速跳过**（不再反复触发慢超时，直接走降级链下一个），成功复位、失败重新跳闸；全链熔断跳闸时抛"All providers circuit-open"（区别于既有 exhausted）；新增指标 `llm_provider_circuit_open_count`（跳闸事件）+ `llm_provider_circuit_skip_count`（快速跳过，provider/model 标签，接 H5）。缺省启用（无失败时零影响，与 H13.4 纯降级语义兼容；`enabled=false` 关闭回退纯降级）。新增 5 测（infra `ProviderCircuitBreakerTest` 3 无 Mockito：阈值跳闸/阈值内不跳闸/成功复位/参数钳制 + `LlmGatewayTest` 4→6：熔断后主模型快速跳过仅调备用、熔断关闭仍逐次重试），`mvn clean test` 全绿（infra 360→365；其余模块不变）。无装配变更（LlmGateway 既有 Bean 内聚熔断），启动冒烟默认 UP。**注**："store 健康"的 fail-fast 语义已由 H14.5/14.6/14.7 覆盖（store 不可用 fail-fast 绝不静默兜底内存），故 G3 以 H13.7 + 既有 store fail-fast 计达成 |
