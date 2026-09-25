# Agent-Harness 框架优化 RoadMap 2.0

> **依据**：`design/Agent-Harness框架整体架构设计方案-v2.0.md` §16 后续优化路线图
> **基线**：2026-09-25 状态（T1–T15 + T10a/b/c 全部完成，117 测试全绿，应用启动 ~2.1s，主链路贯通度 ~85%）
> **性质**：执行级路线图（可逐项派发），承接 1.0 TODO 的 `T*` 编号体系，本轮使用 `H*`（Harness 补强）与 `R*`（RSI 演进）编号
> **使用方式**：逐项派发（如"完成 H1"），完成后将 `[ ]` 改为 `[x]` 并在"完成记录"补充日期与说明
> **后续（3.0，2026-09-25）**：H1–H10 已全部完成并提交（见 §11 完成记录）；新增"决策平面（Jev）"能力面，其任务线 J1–J10 与 R* 的决策平面耦合（C1–C4）见 `Agent-Harness优化RoadMap-3.0.md`（依据 `Agent-Harness架构设计-v3.0-决策平面与RSI整合.md`）。本 2.0 的 R0–R5/R-G 承接进 3.0（仍待派发/暂停），H11/H12 保留在 3.0 N5。
> **同级增量（2.1，2026-09-25）**：`H13 统一模型网关`（配置驱动 Provider 工厂 + 补 GLM + 修复降级链）与 `H14 可插拔向量库 + 原生混合检索`（扩展 `VectorStore` 端口 + 补 ES/Milvus）见 `Agent-Harness优化RoadMap-2.1.md`（依据 `Agent-Harness架构设计-v2.1-统一模型网关与向量库适配.md`）。属 H 类基础设施硬化，**与 3.0 正交**，全部待派发。

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
