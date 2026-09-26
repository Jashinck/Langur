# Agent-Harness 框架整体架构设计方案 v2.0（As-Built + 演进蓝图）

> **文档性质**: 实现现状快照（As-Built Snapshot）+ 递归自演进（RSI）蓝图
> **版本**: v2.0 | **日期**: 2026-09-25 | **取代**: v1.0（2026-08，纯设计蓝图，保留作历史基线）
> **定位**: 框架级通用架构，不绑定具体业务；v2.0 以**真实代码为准**校准 v1.0 蓝图，并新增 RSI 能力面与演进路线图
> **核心公式**: `Agent = LLM推理能力 + Harness确定性调度能力 (+ RSI 递归自演进能力)`
> **本次刷新（2026-09-25）**: As-Built 矩阵已按 **RoadMap 2.0 H1–H10 全部完成并提交**态校准（此前版本为 H1–H10 落地之前的快照，多处 🟡/⬜ 缺口已闭合）；**320 测试全绿**（domain 101 + infra 202 + start 17）。
> **后续增量（v3.0）**: `Agent-Harness架构设计-v3.0-决策平面与RSI整合.md` 在本基线之上**新增"决策平面（Decision Plane / Jev）"能力面**并与 RSI 整合（配套 `Agent-Harness优化RoadMap-3.0.md`，任务线 J1–J10 + 承接 R0–R5/R-G）；v2.0 六组件拓扑（P4）与 As-Built 结论**继续有效**，v3.0 只增量描述新能力面。
> **同级增量（v2.1）**: `Agent-Harness架构设计-v2.1-统一模型网关与向量库适配.md` 硬化本基线**两处既有接缝**——§6「LLM 供给」升级为**配置驱动的统一模型网关**（国内外主流大模型一处适配，补 GLM），§12「存储分层 L4」升级为**可插拔向量库 + 原生混合检索下推**（补 Elasticsearch/Milvus）；配套 `Agent-Harness优化RoadMap-2.1.md`，任务线 H13/H14。**与 v3.0 正交**（H 类基础设施 vs J/R 决策平面），同坐落本 v2.0 基线，六组件拓扑（P4）不变。

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

> **v3.0 关联**：RSI 的运行期底座 = **决策平面（Jev）**。二者的整合（4 耦合点 C1–C4：路由↔R4 / 回放↔R0 / 反思↔R1 / 安全↔R-G）与分阶段融合路径见 `Agent-Harness架构设计-v3.0-决策平面与RSI整合.md` §4；RSI 任务（R0–R5/R-G）已承接进 `Agent-Harness优化RoadMap-3.0.md`（N3–N5，全部待派发，尊重当前暂停）。

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

> **v3.0 增量（2026-09-25）**：H1–H10 落地后，在其之上新增**决策平面（Jev）能力面**——任务线 **J1–J10**（N1 地基 J1–J3 → 成熟度 D1 advisory；N2 执行集成 J4–J10 → D2 闸门生效）与 R\* 的决策平面耦合（C1–C4）已迁移至 `Agent-Harness优化RoadMap-3.0.md`（依据 `Agent-Harness架构设计-v3.0-决策平面与RSI整合.md`）。上表 H1–H12 与 RSI 演进结论继续有效；**决策平面为运行期判定底座，RSI 为其离线优化器**。

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

> **v3.0 增补原则 P12**（决策平面专属红线）：**判定/生成分离 + 判定可回放可降级 + 对安全闸门恒 advisory（fail-closed）**——详见 `Agent-Harness架构设计-v3.0-决策平面与RSI整合.md` §8；随 RoadMap 3.0 J1–J10 落地。

---

> **文档维护约定**：v2.0 为 as-built 基线，后续每完成一个 H/R 优化项，同步更新对应组件的落地度标注与 §16 路线图状态；重大架构变更升版本号并保留历史。
