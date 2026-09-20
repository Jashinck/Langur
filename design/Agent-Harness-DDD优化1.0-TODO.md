# Langur 架构差距弥合 TODO

> 依据：`design/Agent-Harness框架整体架构设计方案.md` v1.0
> 基线：2026-08-31 重构完成态（六组件骨架已落位，18 测试全绿，应用可启动）
> 现状评估：骨架完整度 ~85%，主链路贯通度 ~40%
> 使用方式：逐项派发（如"完成 T1"），完成后将 `[ ]` 改为 `[x]` 并在"完成记录"补充日期与说明。

---

## 派发约定

- **验证闭环**：每项任务完成后必须执行 `mvn clean test` 全绿；涉及启动行为的需打包后实际启动验证。
- **依赖顺序**：P0 必须先于 P1，P1 先于 P2；同阶段内任务基本独立，可按编号顺序推进。
- **原则红线**：任何任务不得破坏 P1（Domain 零外部依赖，仅 common + lombok）与严格单向依赖。

---

## P0 阶段：强制原则纠偏（最高优先级）

### T1. T 组件四层校验链接入主链路
- [x] 已完成（2026-09-01）
- **问题**：`AgentDomainService.executeReActStep` 直调 `tool.execute()`，绕过 `ToolDispatcher`；四层校验链（白名单→Schema→权限→沙箱）是死代码。违反 §1.2"所有工具调用必须经 T 组件校验 + 沙箱执行"。
- **改动要点**：
  - `AgentDomainService` 增加 `ToolDispatcher` 依赖（可选注入，保持向后兼容），工具调用改经 `dispatch(ToolCallRequest)`。
  - domain 侧仅依赖 `ToolDispatcher` 接口（依赖倒置），实现由 infra 提供、start 层装配。
  - 同步触发 `recordToolCall()`（配合 T2）。
- **验收**：单测覆盖——未注册工具被拒（白名单）、非法参数被拒（Schema）、高危越权被拒（权限）、超时被沙箱熔断；`tool.execute` 不再有直调路径。

### T2. 终止闸门四维全部生效
- [x] 已完成（2026-09-01）
- **问题**：`ExecutionTask.addTokens()/recordToolCall()` 零调用；`TerminationGate.maxTimeout` 无判断方法。仅轮次一维生效。
- **改动要点**：
  - `TerminationGate` 增加 `exceedsTimeout(Instant startedAt)`；`gateTripped()` 纳入超时。
  - `ReActExecutionLoop`/`AgentDomainService` 在工具调用处 `recordToolCall()`；LLM 返回后按估算/实际 usage `addTokens()`（`LLMPort` 可扩展返回 token 用量，或用字符数估算并标注）。
- **验收**：单测分别构造超 Token、超单轮调用数、超时三种场景，均能触发闸门终止且 `terminateReason` 语义正确。

### T3. C 组件上下文装配接入执行循环
- [x] 已完成（2026-09-01）
- **问题**：`AgentContext/双画像/记忆/Token 预算` 完全未参与执行；会话历史直用，无脱敏、无 Token 治理（§8.1 要求上下文装配→脱敏→Token 治理）。
- **改动要点**：
  - 新增上下文装配服务（domain 接口 + 实现）：由 `UserProfile/TaskProfile` + 记忆构建 `AgentContext`。
  - `ReActExecutionLoop` 在 BEFORE/AFTER_CONTEXT_ASSEMBLE 钩子之间执行真实装配，并写入 `AgentContextRepository`。
  - 脱敏过滤器做成可插拔接口（先提供默认无操作实现 + 一个示例敏感词实现）。
- **验收**：执行链路产生持久化的 `AgentContext`；超 Token 预算的记忆被拒绝写入（`appendMemory` 返回 false 有测试覆盖）。

---

## P1 阶段：主链路与核心能力贯通

### T4. 输出合规拦截点上线（BEFORE_OUTPUT / AFTER_OUTPUT）
- [x] 已完成（2026-09-20）
- **问题**：12 拦截点中 OUTPUT 两点从未触发（§3.3 要求 12 点全覆盖）。
- **改动要点**：最终答案产出后、返回调用方之前触发 BEFORE_OUTPUT（可 MODIFY 改写/ABORT 拦截），响应完成后触发 AFTER_OUTPUT。
- **验收**：注册一个输出改写钩子的单测，验证最终答案被钩子修改后返回。

### T5. 断点续跑能力
- [x] 已完成（2026-09-20）
- **问题**：快照只写不读；请求入口无"断点恢复检测"（§8.1）。
- **改动要点**：
  - 任务入口按 `taskId/agentId` 查询 `TaskStateRepository`，存在未完成快照时从最近快照恢复轮次与状态。
  - `TaskState.acquireLock` 接入执行入口，防并发重入。
- **验收**：模拟中断后重入同一任务，从最近快照轮次继续而非从 0 开始；并发重入被锁拒绝。

### T6. LayerRouter 默认实现 + 范式路由
- [x] 已完成（2026-09-20）
- **问题**：`LayerRouter` 接口无实现无调用方；`AgentApplicationService` 硬编码 REACT。
- **改动要点**：
  - 提供 `DefaultLayerRouter`（规则：未指定→ReAct；预留 bizCode/任务特征判断扩展点）。
  - 应用层改为经 `LayerRouter.route()` 决定 `RuntimeParadigm`。
  - Workflow / PlanAndExecute 引擎可留占位（路由结果到未实现范式时显式降级为 ReAct 并记录）。
- **验收**：路由决策有单测；显式指定范式的请求按路由结果执行。

### T7. 容错降级与循环检测
- [x] 已完成（2026-09-20）
- **问题**：§3.3/§8.2 要求的 重试→熔断→降级 链路、循环检测（连续 N 轮输出相似度超阈值强制终止）、LLM 超时兜底均缺失。
- **改动要点**：
  - 工具执行失败重试（次数上限 + 退避），失败后降级话术。
  - 循环检测：记录最近 N 轮 action/observation 指纹，重复超阈值触发终止（终止原因 `LOOP_DETECTED`）。
  - LLM 调用超时/异常时的兜底策略接口（默认返回降级话术）。
- **验收**：三类场景单测（工具重试后成功、循环检测终止、LLM 失败降级）。

### T8. SSE 流式双入口
- [x] 已完成（2026-09-20）
- **问题**：§13.1 的 `POST /api/v1/agent/stream/chat`、`POST /api/v1/agent/stream/task` 缺失；LLMPort 无流式能力。
- **改动要点**：
  - `LLMPort` 增加流式能力（`Flux<String>` 或回调式），适配器先行支持 OpenAI 兼容流。
  - api 层新增两个 SSE 端点，事件序列按设计：message → summary → done（task 入口前置 accepted → progress）。
  - 长任务进度总线：按 taskId 的监听器注册表（先注册后执行）。
- **验收**：curl 能收到完整 SSE 事件序列；早期进度事件不丢失。

---

## P2 阶段：扩展体系与基础设施

### T9. SPI 扩展体系（七大 SPI + BizCode 路由）
- [x] 已完成（2026-09-21）
- **问题**：§11 七大 SPI 均不存在；bizCode 硬编码 "default"。
- **改动要点**：
  - common 层定义 SPI 接口契约：BusinessExecutorSPI / PromptTemplateSPI / ToolProviderSPI / SecurityPolicySPI / ContextEnricherSPI / DecisionEngineSPI / OutputPostProcessorSPI。
  - BizCodeRouter：按 bizCode 匹配工具集、Prompt、范式。
  - 现有 `ToolProvider` 收敛为 `ToolProviderSPI` 语义。
- **验收**：提供示例业务域接入（bizCode=demo），注入专属工具与 Prompt 并跑通。

### T10. MCP / REST_API / SKILL 工具源
- [x] T10a 已完成（2026-09-21）；T10b/T10c 保留
- **问题**：`DefaultToolDispatcher` 对三类来源返回"未启用"；§7.3-7.5 能力缺失。
- **建议拆分**（可分别派发）：
  - T10a. REST API as Tool：OpenAPI Spec 解析注册 + HTTP 执行器 + CredentialVault（BEARER/API_KEY 先行）+ SSRF 防护。
  - T10b. MCP 集成：McpClientManager（连接/发现/心跳/重连），工具 ID `mcp:{server}:{tool}`。
  - T10c. Skill 编排：`@SkillDef` + 步骤类型（TOOL_CALL/CONDITION 先行）。
- **验收**：至少一个真实外部工具源（建议 REST API）端到端跑通四层校验链。

### T11. 存储分层落地（S 组件生产化）
- [x] 已完成（2026-09-21）
- **问题**：仅内存实现；§12.3 七张表缺失；乐观锁/分布式锁无持久化校验。
- **改动要点**：
  - MySQL：t_task_state / t_state_snapshot / t_execution_round / t_tool_definition / t_tool_invocation / t_audit_log / t_user_profile。
  - `TaskStateRepository` JPA 实现 + version 乐观锁校验；分布式锁先基于数据库行实现，Redis 版后置。
- **验收**：`langur.repository.type=jpa` 模式下快照持久化并可查询；乐观锁冲突有测试。

### T12. 可观测生产化（V 组件）
- [x] 已完成（2026-09-21）
- **问题**：仅日志降级实现；无 OTel Trace/Span、无工具层/安全层指标采集、审计零调用。
- **改动要点**：
  - 引入 OpenTelemetry：API/上下文/推理/工具/快照/输出六类 Span，traceId 从 API 层生成并透传。
  - 四维指标采集点接入（工具成功率/拦截次数等）；`EvaluationService.audit()` 接入工具调用与钩子拦截事件，checksum 落库。
- **验收**：一次请求可在日志/OTel 中看到完整 Span 链；审计记录含 checksum 且可溯源。

### T13. API 治理链
- [x] 已完成（2026-09-21）
- **问题**：§13.3 治理链仅剩参数校验；鉴权/租户/限流/幂等缺失。
- **改动要点**：
  - Filter 链：鉴权校验 → 租户校验 → 限流 → 幂等拦截 → TraceId 生成（注入 MDC 并透传至 ExecutionTask）。
  - 治理头透传：可信 user/tenant 覆盖业务入参。
- **验收**：无凭证请求 401；越租户请求 403；重复幂等键返回首次结果；全链路日志带同一 traceId。

### T14. 向量记忆（C 组件 L4）
- [x] 已完成（2026-09-21）
- **问题**：§12.2 完全缺失。
- **改动要点**：pgvector 独立数据源；t_vector_knowledge / t_vector_memory；Embedding 写入流水线 + cosine 召回（依赖 LLM 矩阵 Embedding 能力）。
- **验收**：知识写入后可语义召回进入上下文，且受 Token 预算约束。

### T15. LLM 模型矩阵与网关（§6）
- [x] 已完成（2026-09-21）
- **问题**：`LLMRouter` 仅模型名路由；M1-M6 角色分工、统一 `LlmGateway`（同步/流式）、模型降级未建模。
- **改动要点**：按角色（ROUTING/EMBEDDING/RERANK/ACTION/REASONING/LONG_CONTEXT）组织模型配置；网关统一限流/重试/降级。
- **验收**：角色→模型映射配置化；主模型不可用时自动降级备用模型。

---

## 待决策项（开始前需拍板）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| D1 | ORM 选型 | 维持 Spring Data JPA / 迁移 MyBatis-Plus（方案原文） | 维持 JPA（已有实现与测试，迁移收益低） |
| D2 | Redis 引入时机 | T11 同步引入 / 后置独立任务 | 后置（先用数据库锁闭环） |
| D3 | Token 计量 | LLM 返回真实 usage / 本地估算 | 真实 usage 优先，估算兜底 |
| D4 | 流式技术 | Spring MVC SSE（SseEmitter）/ WebFlux | MVC SSE（与现有栈一致） |

---

## 完成记录

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-08-31 | 基线重构 | 六组件骨架 + ReAct 主链路 + T 校验链实现 + API v1（chat/任务控制面/Result/全局异常），18 测试全绿 |
| 2026-09-01 | T1 | `AgentDomainService` 接入 `ToolDispatcher`（可选装配，装配后无直调路径），`HarnessConfiguration` 织入；`DefaultToolDispatcherTest` 6 用例覆盖四层拒绝 + 沙箱超时熔断 + 全链路通过 |
| 2026-09-01 | T2 | `TerminationGate.exceedsTimeout` 补齐，`ExecutionTask.start()` 记录 startedAt，`gateTripped()` 纳入四维；`ReActExecutionLoop` 接入 addTokens/recordToolCall 并修正终止原因归因（闸门优先）；`TerminationGateTest` 6 用例（含三个循环级触发场景） |
| 2026-09-01 | T3 | 新增 `ContextAssembler`/`ContextSanitizer` 端口 + `DefaultContextAssembler`/`KeywordMaskingContextSanitizer` 实现；执行循环在 BEFORE/AFTER_CONTEXT_ASSEMBLE 之间真实装配并写入仓储；`ContextAssemblyLoopTest` 3 用例 + `DefaultContextAssemblerTest` 4 用例（脱敏/预算治理） |
| 2026-09-01 | P0 验收 | 38 测试全绿，`mvn clean package` 成功，应用启动 3.4s，任务端到端 RUNNING→COMPLETED，P0 阶段闭环 |
| 2026-09-20 | T4 | `ReActExecutionLoop` 在最终答案产出后触发 BEFORE_OUTPUT（支持 MODIFY 改写并回写 `Agent.overrideFinalAnswer`、ABORT 阻断终止）与 AFTER_OUTPUT；`OutputComplianceHookTest` 2 用例（改写 + 阻断） |
| 2026-09-20 | T5 | 执行入口接入 `TaskState.acquireLock` 防并发重入 + `isResumable` 断点续跑（从最近快照 round/iteration 恢复），新增 `ExecutionTask.resume/resumeFromRound`、`Agent.resumeIteration`；`ResumeExecutionLoopTest` 2 用例（续跑非从 0、并发被锁拒绝） |
| 2026-09-20 | T6 | 新增领域 `DefaultLayerRouter`（bizCode/任务特征规则路由），start 层装配为 Bean，`AgentApplicationService` 改经 `route()` 决定范式并对未实现引擎显式降级 ReAct；`DefaultLayerRouterTest` 3 用例 |
| 2026-09-20 | T7 | 新增 `RetryPolicy`/`LoopDetector`/`FallbackStrategy`(+默认实现)；`AgentDomainService` 接入工具重试→降级与 LLM 异常兜底，`ReActExecutionLoop` 接入循环检测（终止原因 `LOOP_DETECTED`）；`LoopDetectorTest` 3 + `AgentDomainServiceResilienceTest` 3 + `LoopDetectionExecutionTest` 1 |
| 2026-09-20 | T8 | `LLMPort.streamComplete`（默认降级 + OpenAI 兼容适配器真实 stream/`LLMRouter` 委派）；应用层 `StreamEventHandler`/`TaskProgressBus`/`AgentStreamApplicationService`（先注册后执行）；api 层 `AgentStreamV1Controller` 两个 MVC SSE 端点；`StreamingAnswerTest` 1 用例 + 启动冒烟：`stream/task` 完整事件序列 accepted→progress→summary→done |
| 2026-09-20 | P1 验收 | 53 测试全绿（38→53，新增 15），`mvn clean package` 成功，应用启动 2.6s，SSE 双入口端点已映射、`stream/task` 事件序列端到端验证通过，P1 阶段闭环 |
| 2026-09-21 | T9 | common 层定义七大 SPI 契约（BusinessExecutor/PromptTemplate/ToolProvider/SecurityPolicy/ContextEnricher/DecisionEngine/OutputPostProcessor + SpiToolSpec/BizContext）；domain 新增 `BizCodeRouter` 端口，infra `DefaultBizCodeRouter`（Spring 聚合 List<SPI> + bizCode 索引 + default 兑底）；`AgentApplicationService` 接入 resolveBizCode/buildBizContext + enrichers；`DefaultBizCodeRouterTest` 5 用例 |
| 2026-09-21 | T10a | infra `harness/tool/rest` 包：`RestApiToolSpec`/`RestApiToolCatalog`/`RestApiToolProperties`/`RestApiToolRegistrar`（@PostConstruct 装载）+ `WebClientRestApiToolGateway`（URL 模板占位符 + query/body 路由）+ `SsrfGuard`（字节级 IP 段判定）+ `InMemoryCredentialVault`（BEARER/API_KEY/BASIC）；`DefaultToolDispatcher` 新增 REST_API 路由与泛型 `runGeneric` 沙箱；`SsrfGuardTest` 4 + `CredentialVaultTest` 4 + `RestApiToolRoutingTest` 3 |
| 2026-09-21 | T11 | infra `persistence/jpa/entity` 七张表实体（t_task_state 带 @Version 乐观锁 / t_state_snapshot / t_execution_round / t_tool_definition / t_tool_invocation / t_audit_log / t_user_profile）+ 7 个 Spring Data 仓储；`JpaTaskStateRepository`（@ConditionalOnProperty jpa）映射 TaskState↔DO + JsonValueMapper 序列化快照，`InMemoryTaskStateRepository` 改为 memory 条件装配避免双 Bean；`JpaTaskStateRepositoryTest` 2 用例（快照持久化重建 + 乐观锁冲突） |
| 2026-09-21 | T12 | domain `evaluation/tracing` 新增 `ExecutionTracer` 端口 + NOOP 实现 + `Checksums` 工具；`ReActExecutionLoop` 埋六类 Span（API/上下文/推理/工具/快照/输出）+ 拦截审计 + 四维指标；infra `OtelExecutionTracer`，start `ObservabilityConfiguration` + 自实现 `LoggingSpanExporter`（规避 exporter-logging 1.61.0 缺失）；OTel BOM 1.61.0；`ChecksumsTest` 3 + `ObservabilityExecutionLoopTest` 2 + `OtelExecutionTracerTest` 2 |
| 2026-09-21 | T13 | api `governance` 包：`GovernanceFilter`（@Order(1) OncePerRequestFilter：TraceId→MDC+头→鉴权→租户→限流→幂等回放）+ `RequestContext`（ThreadLocal）+ `GovernanceProperties` + `InMemoryRateLimiter`（固定窗口 CAS）+ `InMemoryIdempotencyStore`（ContentCachingResponseWrapper 捕获）；`AgentV1Controller` 可信身份覆盖入参；`GovernanceFilterTest` 6 用例（401/403/429/幂等回放/traceId） |
| 2026-09-21 | T14 | domain `context/vector` 新增 `EmbeddingPort`/`VectorStore` 端口 + `VectorRecord`/`VectorMemoryService`（cosine Top-K + Token 预算截断 + L4 知识记忆产出）；infra `LexicalEmbeddingPort`（分词哈希词袋 L2 归一化）+ `InMemoryVectorStore`（memory 默认）+ `PgVectorStore`（pgvector 条件装配，`<=>` cosine + HNSW）+ `VectorMath`；start 装配 `VectorMemoryService` Bean；`VectorMemoryServiceTest` 6 用例（相似度排序/namespace 隔离/Token 预算/UPSERT/L4/边界） |
| 2026-09-21 | T15 | infra `llm` 新增 `ModelRole`（M1-M6：ROUTING/EMBEDDING/RERANK/ACTION/REASONING/LONG_CONTEXT）+ `LlmGateway`（角色→模型解析 + 主备降级链 withFallback，同步/决策/流式）；`LlmProperties` 新增 roleModels/fallbackChains/fallbacksOf；`LlmGatewayTest` 4 用例（角色映射 + 主模型不可用自动降级） |
| 2026-09-21 | P2 验收 | 94 测试全绿（domain 39 + infra 44 + start 11），`mvn clean test` BUILD SUCCESS；T9/T10a/T11/T12/T13/T14/T15 均落地（T10b MCP、T10c Skill 编排作为后续独立任务保留），P2 阶段闭环 |
