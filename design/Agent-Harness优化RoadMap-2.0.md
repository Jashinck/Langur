# Agent-Harness 框架优化 RoadMap 2.0

> **依据**：`design/Agent-Harness框架整体架构设计方案-v2.0.md` §16 后续优化路线图
> **基线**：2026-09-25 状态（T1–T15 + T10a/b/c 全部完成，117 测试全绿，应用启动 ~2.1s，主链路贯通度 ~85%）
> **性质**：执行级路线图（可逐项派发），承接 1.0 TODO 的 `T*` 编号体系，本轮使用 `H*`（Harness 补强）与 `R*`（RSI 演进）编号
> **使用方式**：逐项派发（如"完成 H1"），完成后将 `[ ]` 改为 `[x]` 并在"完成记录"补充日期与说明

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
- [ ] 待派发
- **现状/问题**：`LLMPort.LLMDecision` 无 usage 字段，`TerminationGate` 的 maxTokens 维靠字符数估算（D3 未落地），成本无法核算，RSI 评估缺基线。
- **改动要点**：
  - `LLMDecision` 增加 `TokenUsage(promptTokens/completionTokens/totalTokens)` 值对象（domain，零外部依赖）。
  - 各 LLM 适配器（OpenAI 兼容优先）解析响应 `usage` 字段回填；流式 `streamComplete` 从末块 usage 或估算兜底。
  - `ReActExecutionLoop` 用真实 usage 调 `ExecutionTask.addTokens()`，估算仅作 fallback 并标注来源。
- **验收**：单测——mock 响应含 usage 时 `addTokens` 收到真实值；无 usage 时降级估算且不抛异常；超 Token 闸门用真实值触发。
- **依赖**：无 | **优先级**：P0 | **估算**：M

### H2. 语义 Embedding(M2) + Rerank(M3) 适配器
- [ ] 待派发
- **现状/问题**：C 组件仅 `LexicalEmbeddingPort`（分词哈希词袋），`VectorMemoryService` 召回非语义，L4 知识记忆质量受限；M3 Rerank 未接。
- **改动要点**：
  - infra `llm` 增 `LlmEmbeddingPort implements EmbeddingPort`，经 `LlmGateway` 走 EMBEDDING 角色（M2），批量向量化 + 维度对齐（替换 1536 硬编码为配置）。
  - 增 `RerankPort`（domain 端口）+ LLM/交叉编码实现（M3），`VectorMemoryService` 召回后可选重排去噪。
  - `LexicalEmbeddingPort` 保留为无外部依赖的测试/降级实现（条件装配）。
- **验收**：单测——语义相近文本召回排序优于词袋基线（构造正负样例）；Rerank 开启后 Top-K 顺序变化有断言；M2 不可用时降级 Lexical 不中断。
- **依赖**：H1（共用网关/计量） | **优先级**：P0 | **估算**：M

### H5. 可观测导出后端（Prometheus + MQ 审计 + 归档）
- [ ] 待派发
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
- [ ] 待派发
- **现状/问题**：仅 `ReActExecutionLoop` 真实运行；`DefaultLayerRouter` 路由到 PLAN_AND_EXECUTE 时**降级 ReAct**，`stream/task` 名不副实。
- **改动要点**：
  - domain `harness/execution` 增 `PlanAndExecuteExecutionLoop`：M5 规划（任务拆解为 `PlanStep`）→ 逐步执行（每步可委派 ReAct 子循环）→ 进度回报（接 `TaskProgressBus`）→ 动态重规划。
  - `ExecutionLoopService`/`LayerRouter` 按范式分发到对应循环；复用现有 `TerminationGate`/`LoopDetector`/快照/钩子。
  - 复用既有 `Plan`/`PlanStep`/`PlanningDomainService`（README 提及的历史规划模型）。
- **验收**：单测——多步任务被拆解并按序执行，进度事件序列完整；某步失败触发重规划或降级；终止闸门对总轮次/Token 生效。
- **依赖**：无 | **优先级**：P1 | **估算**：L

### H4. Redis L2 热层 + 分布式锁 + 限流/幂等持久化
- [ ] 待派发
- **现状/问题**：S 组件无 Redis L2；分布式锁为 DB 行级；`InMemoryRateLimiter`/`InMemoryIdempotencyStore` 为单机内存，多实例不一致（D2 后置项）。
- **改动要点**：
  - 引入 Redis（Spring Data Redis）：会话热状态(L2, TTL)、Redisson/SETNX 分布式锁替换 DB 锁、限流计数(Redis 令牌桶)、幂等键持久化。
  - 条件装配：`langur.cache.type=redis|memory`，memory 实现保留为默认/测试兜底。
  - 锁续期（watchdog）+ 可重入，兼容现有 `TaskState.acquireLock` 语义。
- **验收**：并发重入被 Redis 锁拒绝；两实例共享限流计数（超阈值 429）；幂等键跨实例回放首次结果；Redis 不可用时降级 memory 不中断（P10）。
- **依赖**：无 | **优先级**：P1 | **估算**：L

### H10. 安全补强（Prompt 注入 + 输出审核 + 高危审批）
- [ ] 待派发
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
- [ ] 待派发
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
- [ ] 待派发
- **改动要点**：`McpTransport` 增 STDIO（进程管道）/ 真流式 SSE / WebSocket 实现；订阅 `tools/list_changed` 热更新；多 server 熔断隔离细化（单 server 故障降级不影响全局）。
- **验收**：STDIO/SSE/WS 三种传输各跑通一次 tools/call；远端工具变更触发热更新注册；单 server 宕机其余正常。
- **依赖**：无 | **优先级**：P2 | **估算**：M

### H7. REST OpenAPI 自动发现 + OAUTH2 + KMS 凭证库
- [ ] 待派发
- **改动要点**：OpenAPI Spec 解析器自动注册工具（补 T10a 的 YAML 手动方式）；`CredentialType` 增 OAUTH2（client_credentials 刷新）；`CredentialVault` 增持久化/KMS 后端（密钥不落明文）。
- **验收**：给定 OpenAPI 文档自动发现并注册工具，经四层校验链调用成功；OAUTH2 token 自动获取/刷新；密钥从 KMS 读取不落日志。
- **依赖**：无 | **优先级**：P2 | **估算**：M

### H9. Workflow 引擎 + Hybrid 分层调度
- [ ] 待派发
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
