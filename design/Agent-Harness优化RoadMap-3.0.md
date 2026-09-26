# Agent-Harness 框架优化 RoadMap 3.0 — 决策平面（Jev）与 RSI 整合

> **依据**：`design/Agent-Harness架构设计-v3.0-决策平面与RSI整合.md`（决策平面增量设计 + RSI 整合）
> **基线**：RoadMap 2.0 全部 H1–H10 已完成并提交（2026-09-25），六组件 As-Built ~85%，320 测试全绿；v2.0 六组件拓扑继续有效，本轮**只增量**新增"决策平面"能力面并承接 RSI（R\*）
> **性质**：执行级路线图（可逐项派发）。本轮使用 **`J*`（决策平面 / Jev 接入）** 编号 + **承接 `R*`（RSI 演进，来自 RoadMap 2.0）** + **保留 `H11/H12`（治理/扩展，待派发）**
> **使用方式**：逐项派发（如"完成 J1"），完成后将 `[ ]` 改为 `[x]` 并在 §11"完成记录"补日期与说明；每完成一项同步更新 v3.0 架构文档 §3/§4 落点标注与 §9 成熟度
> **状态**：**N1（J1–J3）已完成（2026-09-26），达成熟度 D1（Jev advisory）**；**N2（J4–J10）已完成（2026-09-26），达成熟度 D2（决策闸门生效）**——Workflow 三插入点 ①②③ + Hybrid ④⑤ + 层路由 advisory ⑥ + ReAct ⑦⑧ + Skill ⑨⑩ 全部落地。决策平面 Phase 0（N1–N2）为纯 H 类能力接入，不触发 P11。**N3（RSI 起步）已完成（2026-09-26）：R0 回放验证引擎 + R1 反思自检 + R2 记忆自蒸馏 全部落地**（回放通过≠生效、反思只 MODIFY 不 ABORT、蒸馏 Jev 置信门+namespace 隔离，默认关灰度）。**N4（RSI 枢纽）已完成（2026-09-26）：R3 技能自合成落地**（确定性模板归纳 + task-complete→DECISION 完成门 + 离线越权/降本校验 + 版本化可回滚注册，默认关灰度）。**N5（RSI 进阶）已启动（2026-09-26）：R-G 安全平面已完成**（提案-验证-应用三段式 + 权限隔离红线 + 高危人审 + 深度/频率限流 + 一键回滚，默认关）；R4（策略自优化）/R5（工具自扩展）/H11（依赖治理）/H12（多 Agent）待派发。缺省 `langur.rsi.enabled=false` 不装配、行为等价既有版本

---

## 1. 总览

### 1.1 里程碑划分（依赖驱动，非日历驱动）

```
N1 决策平面地基 ──▶ N2 执行集成 ──▶ N3 RSI起步 ──▶ N4 RSI枢纽 ──▶ N5 RSI进阶
 (端口/后端/录制)     (W/H/ReAct/Skill)  (回放+反思)     (技能自合成)    (策略调优/安全平面)
 J1 J2 J3            J4–J10             R0 R1 R2        R3             R-G R4 R5 + H11 H12
   ↓                   ↓                 (Phase 1)       (Phase 2)        ↓
 达 D1 advisory      达 D2 闸门生效                                       达 D3 自优化
 └────── Phase 0（H 类，不碰 RSI）──────┘
```

> **核心逻辑**：决策平面是 **RSI 的运行期底座**，RSI 是 **决策平面的离线优化器**（v3.0 §4.1）。因此先立地基（J1–J3：端口 + Jev 后端 + 录制/降级），达 **D1 advisory**；再逐模式接入执行（J4–J10：Workflow/Hybrid/ReAct/Skill），达 **D2 闸门生效**；RSI 解锁后（N3–N5）经 R0 回放录制判定、R4 离线调优阈值/prompt/路由，把决策平面推进到 **D3 自我优化**。
>
> **Phase 映射（对齐 v3.0 §4.4）**：`N1+N2 = Phase 0`（H 类，不碰 RSI，不需 R0/R-G）；`N3 = Phase 1`（RSI 解锁）；`N4+N5 = Phase 2`（RSI 枢纽与进阶）。**成熟度映射（对齐 v3.0 §9）**：`J1–J3 → D1`；`J4–J10 → D2`；`R0+R-G+R4 → D3`。

### 1.2 任务总表

| ID | 任务 | 里程碑 | 优先级 | 依赖 | 估算 | 类型 |
|----|------|--------|--------|------|------|------|
| **J1** | `DecisionPort` 契约 + Jev 适配器（TypeSafe REST）+ 规则兜底 | N1 | P0 | — | M | 决策平面 |
| **J2** | `RecordingDecisionPort` 录制 + 决策维度指标（接 H5） | N1 | P0 | J1 | M | 决策平面 |
| **J3** | `DecisionConfiguration` 装配 + `ThresholdRouter`/`Caching`/`Local` + 数据驻留 | N1 | P0 | J1 | M | 决策平面 |
| **J4** | Workflow 阶段决策闸门（插入点 ①） | N2 | P1 | J1 J2 J3 | M | 执行集成 |
| **J5** | Workflow 审批风险分级（②，非 CRITICAL；CRITICAL 恒人审） | N2 | P1 | J3 H10 | M | 执行集成 |
| **J6** | Workflow 产物验收闸门（③） | N2 | P1 | J3 | S | 执行集成 |
| **J7** | Hybrid 执行器择优 + 升/降级触发（④⑤） | N2 | P1 | J3 H3 H9 | M | 执行集成 |
| **J8** | 层路由 advisory（⑥，`DecisionEngineSPI` 接缝）⚠️与 R4 重叠 | N2 | P2 | J3 | M | 执行集成 |
| **J9** | ReAct 完成判定 + 卡死检测（⑦⑧） | N2 | P2 | J3 | M | 执行集成 |
| **J10** | Skill `DECISION` 步骤 + 转译 PRD 质量门（⑨⑩） | N2 | P2 | J3 H8 | M | 执行集成 |
| **R0** | 回放验证引擎（+ 录制回放 `DecisionPort`，C2） | N3 | P0 | H1 H5 J2 | L | RSI 前提（待派发） |
| **R1** | 反思自检（+ Jev 廉价初筛，C3） | N3 | P1 | M5 J1 | M | RSI L1（待派发） |
| **R2** | 记忆自蒸馏（+ Jev 置信信号） | N3 | P1 | H2 H5 J2 | M | RSI L2（待派发） |
| **R3** | 技能自合成（+ Jev 支撑 `DECISION` 步骤） | N4 | P1 | H8 R0 J10 | L | RSI L3 枢纽（待派发） |
| **R-G** | RSI 安全平面（统辖 Jev advisory，C4） | N5 | P0 | R0 J3 | M | RSI 护栏（待派发） |
| **R4** | 策略自优化（调 Jev 阈值/prompt/路由 → D3，C1） | N5 | P2 | R0 R-G J8 | L | RSI L4（待派发） |
| **R5** | 工具自扩展（缺口检测→自动接入 + 强审批） | N5 | P2 | H6 H7 R0 R-G | L | RSI L5（待派发） |
| **H11** | 依赖治理（ArchUnit + JDK/Lombok toolchain 固定）承接 2.0 | N5 | P1 | — | S | 治理（待派发） |
| **H12** | 多 Agent 协作编排（SUB_AGENT + 消息总线）承接 2.0 | N5 | P3 | H8 | L | 补强（待派发） |

> 估算：S≈0.5–1 人日，M≈2–4 人日，L≈5–10 人日（单人熟手，含测试）。R\*/H11/H12 的基础规格见 RoadMap 2.0 §5–§7，本表只列其在 v3.0 的决策平面耦合增量（详见 §5–§7）。

### 1.3 参考时间线（假设 2 名后端 + 1 名平台，仅供排期参考）

```
周次:  1   2   3   4   5   6   7   8   9   10  11  12
N1    [J1 ][J2 ][J3 ]                          ← Phase 0（可即刻派发）
N2            [J4 ][J5][J6][J7 ][J8 ][J9 ][J10] ← Phase 0（Workflow/Hybrid 优先）
N3                            [R0         ][R1 ][R2 ]   ← Phase 1（RSI 解锁后）
N4                                            [R3      ]← Phase 2
N5                                                    [R-G][R4/R5/H11/H12...]
```

> N1–N2（Phase 0）为纯能力接入，不依赖 RSI，可独立排期落地；N3–N5（Phase 1/2）以 **RSI 解锁** 为前置门，当前暂停，时间线仅示意。

---

## 2. 派发约定（承接 2.0，新增决策平面红线 P12）

- **验证闭环**：每项完成后 `mvn clean test` 全绿（**须用 JDK 17**：`export JAVA_HOME=…/temurin-17…`，Lombok 在 JDK 26 注解处理失效，见 H11）；涉及启动/装配行为的打包后实际启动冒烟；涉及新配置项的补 `application.yml` 注释样例。
- **依赖顺序**：里程碑间严格串行（N1→N5）；N1 内 `J1 → (J2, J3)`；N2 内 J4–J10 均依赖 J1–J3，可按优先级并行。
- **原则红线**：不得破坏 P1（Domain 零外部依赖，仅 common+lombok）、P2（严格单向依赖）、P4（六组件正交，决策平面是**端口**非第七组件）。
- **决策平面红线（P12，新增，强制）**：
  1. **默认关闭**：`langur.decision.enabled=false`；未开启时系统行为与 v2.0 完全一致，所有插入点走规则/LLM 兜底（P10）。
  2. **对安全闸门恒 advisory**：对安全/审批/合规判定**只能收紧**（升级人审 / 中断 / 追加拦截），**绝不放松**——不得自动放行 CRITICAL、不得绕过四层校验链、不得修改 `SecurityPolicySPI`（与 P11/R-G 一致）。
  3. **低置信 / 超时 fail-closed**：置信不足或后端超时，一律走最保守分支（人审 / 中断 / 回退现有规则）。
  4. **判定必须可录制**：`record=true` 常开，每次 `decide` 的 request/response 落轨迹快照，为 R0 回放确定性前向兼容（C2 / DD12）。
  5. **数据驻留**：`state` 命中 `sensitive-namespaces`（如 legal-contracts / code）**强制 local 后端**，禁止发往第三方云（DD10）。
  6. **密钥不落明文**：`api-key-ref` 经 H7 `CompositeSecretResolver`/KMS 解析，绝不写日志或明文配置。
- **RSI 红线（P11，承接 2.0）**：R\* 任务当前 **待派发**（RSI 暂停）；任何"自改进"产物默认是**候选提案**，禁止直接生效，须经 R0 回放 + 灰度 +（高危）人工审批；不得修改安全策略层与四层校验链。**Jev 阈值/prompt/路由的变更本身即 RSI 提案**，受 R-G 统辖（C4）。
- **DoD（完成定义）**：代码 + 单测（纯 JUnit5，domain 无 Mockito，infra/start 可用；匿名/内部类桩；`shouldXxx` 命名；Javadoc 标注 J\*/R\* 任务号）+ 文档同步（更新 v3.0 §3/§4 落点标注、§9 成熟度、本 RoadMap §11 完成记录）。

---

## 3. N1 — 决策平面地基（端口 / 后端 / 录制）

> **里程碑退出标准**：`DecisionPort` 契约落 domain 且零外部依赖；Jev（TypeSafe REST）适配器可发起判定并解析 `choice/noul/score + confidence`；`record=true` 时判定录制进轨迹快照；决策维度指标可查询；关闭时行为与 v2.0 一致；敏感命名空间强制 local、密钥不出明文。达成 **成熟度 D1（Jev advisory）**。

### J1. `DecisionPort` 契约 + Jev 适配器 + 规则兜底
- [x] 已完成（2026-09-25）
- **现状/问题**：系统无统一"判定"端口，所有判定散落在正则/规则/昂贵 M5（v3.0 §1.2）；无快、廉、校准、可批量的 System-1 判定器。
- **改动要点**：
  - **domain（P1，纯 JDK）**：新增 `port/DecisionPort`（`DecisionResponse decide(DecisionRequest)`）+ `harness/decision/{DecisionRequest, DecisionQuestion, DecisionType(CHOICE|PROBABILITY|SCORE), DecisionResponse, DecisionAnswer(choice/value/confidence/distribution), DecisionThresholds}` 值对象；`DecisionType` 对齐 Jev `choice/noul/score`；`DecisionResponse.usage` 复用 H1 `TokenUsage`。
  - **infra**：`TypeSafeDecisionAdapter implements DecisionPort`——用既有 `WebClient`（H6/H7 已在用）`POST` 到 `base-url`，请求 `{state, model, questions}`，支持 **batch（投机扇出）**：一次请求多问题、`state` 只发一次；解析 `answers{value/distribution, confidence, usage}`。
  - **infra**：`RuleFallbackDecisionAdapter`——无后端/关闭时的规则·正则兜底（复用现有 `OutputContentReviewer`/`SkillExpressionResolver` 语义），保证离线可测、缺失即降级（P10）。
  - **common（可选，DD13）**：若 `DecisionEngineSPI` 尚未独立成形，在此定型契约；决策平面作为其**首个具体实现**（v2.0 §15.4 既有映射）。
- **验收**：单测——桩 `WebClient`/`ExchangeFunction` 返回 Jev 样例响应时，`decide` 正确解析 choice/noul/score + confidence + distribution；batch 多问题一次往返；后端异常/超时回退 `RuleFallbackDecisionAdapter` 不抛出；domain 契约无 Spring import（可加 ArchUnit 断言，接 H11）。
- **依赖**：无 | **优先级**：P0 | **估算**：M

### J2. `RecordingDecisionPort` 录制 + 决策维度指标
- [x] 已完成（2026-09-26）
- **现状/问题**：Jev 是外部模型，若不录制则 R0 反事实回放失真（C2）；决策行为无指标，无法标定阈值/核算成本。
- **改动要点**：
  - **infra**：`RecordingDecisionPort`（装饰器）——`record=true` 时把每次 `decide` 的 request+response 写入**轨迹快照**（与 LLM 录制同通道，复用 S 组件 `StateSnapshot`/轨迹仓库埋点），**R0 前向兼容**（现在埋点，R0 落地即可直接回放，v3.0 §11）。
  - **infra**：决策维度指标接 H5 `MicrometerEvaluationService`：`decision_latency`（往返延迟分布）、`decision_confidence`（各插入点置信分布）、`decision_fallback_rate`（回退/超时占比）、`decision_cost`（输入 token，复用 H1 `TokenUsage`）；`decision_route_counts`（分流计数）由 J3 `ThresholdRouter` 补齐。
  - **审计**：判定（含 confidence/distribution）写入审计链（checksum），可溯源"为何走了这条分支"。
- **验收**：单测——`record=true` 时录制通道收到 request/response（桩快照仓储断言）；`record=false` 时不录制；判定后 latency/confidence/fallback/cost 指标可读（桩 MeterRegistry）；审计记录含判定 checksum。
- **依赖**：J1 | **优先级**：P0 | **估算**：M

### J3. `DecisionConfiguration` 装配 + 阈值路由 + 缓存 + 自部署后端 + 数据驻留
- [x] 已完成（2026-09-26）
- **现状/问题**：需把"录制→缓存→阈值→后端→兜底"组装为装饰链并默认关闭；安全/审批判定须 fail-closed；敏感数据须驻留本地。
- **改动要点**：
  - **start**：`DecisionConfiguration`——经 `ObjectProvider<DecisionPort>` 松耦合组装装饰链（v3.0 §5.2），注入到 E/T/C/L 各消费点；`langur.decision.enabled=false` 时不装配、行为与 v2.0 一致（P10）。
  - **infra**：`ThresholdRouter`（装饰器）——`confidence ≥ 阈值` → 程序化分流（run/skip/branch/approve/terminate）；`< 阈值` → **fail-closed**（升级/人审/回退规则）；发 `decision_route_counts` 指标。
  - **infra**：`CachingDecisionPort`（装饰器）——按 `state` 哈希 + 问题签名缓存判定，复用 H4 `CacheBackend`（多闸门不重复调用）。
  - **infra**：`LocalDecisionAdapter`——自部署后端（Kev/Laya）；**Kev 兼容 TypeSafe SDK，仅换 `base-url`**，故可复用 `TypeSafeDecisionAdapter` 逻辑 + 本地端点 + 免鉴权（v3.0 §2.2）。
  - **infra**：`DecisionProperties`（`langur.decision.*`，P9）——`enabled/backend(typesafe|local|off)/model/base-url/api-key-ref/timeout-millis/batch/cache/record/thresholds{routing,approval-auto,artifact-accept,completion}/data-residency{sensitive-namespaces}`；命中 `sensitive-namespaces` 的 `state` **强制 local**、禁止出网（DD10）。
- **验收**：单测——装饰链组装顺序正确（录制包裹缓存包裹阈值包裹后端）；高置信走程序化分流、低置信 fail-closed（回退/人审）；缓存命中不重复调后端；敏感命名空间强制 local（桩断言不走 typesafe base-url）；`enabled=false` 时 `DecisionConfiguration` 不产出 Bean、消费点降级规则；`api-key-ref` 经 resolver 解析、日志无明文。
- **依赖**：J1 | **优先级**：P0 | **估算**：M

---

## 4. N2 — 执行集成（Workflow / Hybrid / ReAct / Skill）

> **里程碑退出标准**：决策平面在四种执行模式下按插入点 ①–⑩ 生效，全部带规则/LLM 兜底与置信阈值、默认关灰度开；**Workflow 阶段闸门可跳过不必要昂贵阶段**、审批分级提升长任务吞吐（CRITICAL 恒人审）、产物验收提升质量；Hybrid 每阶段择优执行器；所有判定可录制、可回放、可观测。达成 **成熟度 D2（Jev 闸门生效）**。
>
> **总原则（v3.0 §3）**：决策平面**只改"判定"，不改"生成"**；每个插入点都带兜底与阈值；对安全闸门恒 advisory（P12）。用户重点关注 **Workflow（J4–J6）与 Hybrid（J7）**，故列为 P1。

### J4. Workflow 阶段决策闸门（插入点 ①）
- [x] 已完成（2026-09-26）
- **现状/问题**：`WorkflowExecutionLoop`/`WorkflowStage` 是固定阶段序列，只有 `requiresApproval` 闸门，阶段间无条件分支——简单任务也跑完全部昂贵 LLM 阶段。
- **改动要点**：
  - `WorkflowStage` 增可选 `decisionGate`（一个 `DecisionQuestion` + 阈值 + 路由动作：`run-next / skip / branch-to-stage / abort / require-approval`）；阶段产出后经 `DecisionPort` 判定走向（`choice`/`noul`）。
  - 配置驱动（P9）：`decisionGate` 从 `WorkflowDefinition` 配置装载（承接场景增强的 config-driven workflow 注册）。
  - **兜底**：低置信 / 后端缺失 → 走默认顺序（原固定序列），不改变既有行为（P10）。
- **验收**：单测——高置信 `skip` 判定跳过下一阶段（昂贵 LLM 阶段未被调用，桩断言）；高置信 `branch-to-stage` 跳转正确；低置信回退默认顺序；`decisionGate` 未配置时与原 Workflow 行为一致；判定被 J2 录制。
- **依赖**：J1 J2 J3 | **优先级**：P1 | **估算**：M

### J5. Workflow 审批风险分级（插入点 ②，非 CRITICAL）
- [x] 已完成（2026-09-26）
- **现状/问题**：`CriticalApprovalValidator`（H10）对 CRITICAL 一律挂起人审——**一刀切**，低风险也排队，长任务（合同审查特批项）吞吐受限。
- **改动要点**：
  - 对**非 CRITICAL** 审批闸门，用 `score` 做风险三分流：低风险 → 策略内自动放行快路；中 → 人审；高 / 低置信 → 人审或中断。
  - **⚠️ 红线（P11/P12/R-G）**：**CRITICAL 恒人审**——Jev 只产出"风险摘要 + 建议"加速人工，**绝不自动放行**（只能收紧不能放松）。
  - 复用 H10 `ApprovalPort`/审批流；阈值 `approval-auto`（默认 0.90，可被 R4 调优）。
- **验收**：单测——非 CRITICAL 低风险高分自动放行、中分转人审、低置信转人审（fail-closed）；**CRITICAL 用例无论 Jev 评分多低都挂起人审**（断言绝不自动放行）；分流计数进 `decision_route_counts`。
- **依赖**：J3 H10 | **优先级**：P1 | **估算**：M

### J6. Workflow 产物验收闸门（插入点 ③）
- [x] 已完成（2026-09-26）
- **现状/问题**：`task.addArtifact(...)` 无质量门，多产物（审查报告/特批项/PRD）质量参差。
- **改动要点**：
  - `addArtifact` 前用 `score` 判完整/合规，低于阈值（`artifact-accept`，默认 0.80）→ **有界重试**该阶段或打标（`accepted=false` + 原因）。
  - 重试受步数/Token 终止闸门约束（不无限重试）。
- **验收**：单测——高分产物直接接受；低分触发一次有界重试后仍低分则打标不阻断；重试次数不超上限；判定被录制。
- **依赖**：J3 | **优先级**：P1 | **估算**：S

### J7. Hybrid 执行器择优 + 升/降级触发（插入点 ④⑤）
- [x] 已完成（2026-09-26）
- **现状/问题**：Hybrid（H9：Workflow→Plan→ReAct 三层分权）中每阶段委派哪层是固定规则；ReAct 阶段完成/卡死判定只靠 `TerminationGate`/`LoopDetector`（数轮次/比指纹）。
- **改动要点**：
  - **④ 执行器择优**：hybrid 每个 Workflow 阶段用 `choice` 选"够用的最便宜执行器"——简单抽取 → ReAct，复杂多步 → Plan（不为简单阶段烧规划）。
  - **⑤ 升/降级触发**：ReAct 阶段用 `noul`("是否已完成 / 是否在重复") 做**廉价**完成判定与卡死检测，接 `TerminationGate`/`LoopDetector`；该升 Plan 就升（接既有 replan-on-failure）、该终止就终止。
  - **兜底**：低置信 → 用现有规则/指纹判定（P10）。
- **验收**：单测——简单阶段被路由到 ReAct、复杂阶段到 Plan（桩执行器断言调用）；`noul` 判"已完成"触发终止、判"重复"触发升级/终止；低置信回退既有 `LoopDetector` 指纹逻辑。
- **依赖**：J3 H3 H9 | **优先级**：P1 | **估算**：M

### J8. 层路由 advisory（插入点 ⑥，`DecisionEngineSPI` 接缝）⚠️ 与 R4 重叠
- [x] 已完成（2026-09-26）
- **现状/问题**：`DefaultLayerRouter` 靠 bizCode 查表 + 字符串标记（workflow/approval/compliance）+ 规划特征，**脆弱**、语义模糊任务误判；学习型路由属 **R4（RSI，当前排除）**。
- **改动要点**：
  - `DefaultLayerRouter` 增**可选** `DecisionPort`（经 `DecisionEngineSPI` 接缝注入，**不硬编进路由类**，C1）：`choice` 选 paradigm，阈值 `routing`（默认 0.75）。
  - **低置信 / 缺失 → 回退现有规则**（P10）；**v3.0 阶段仅 advisory、非自主自改进**——自动调优路由规则留待 R4（N5）。
  - **⚠️ 范围红线**：J8 只提供"Jev 辅助路由判定"这一运行期接缝；**"离线回放调优路由"属 R4，不在 J8 内实现**（尊重 RSI 暂停）。
- **验收**：单测——高置信 Jev 路由覆盖规则选择；低置信回退规则；`DecisionPort` 缺失时路由行为与 v2.0 一致；断言 J8 不含任何"自修改路由规则"逻辑（该逻辑属 R4）。
- **依赖**：J3 | **优先级**：P2 | **估算**：M

### J9. ReAct 完成判定 + 卡死检测（插入点 ⑦⑧）
- [x] 已完成（2026-09-26）
- **现状/问题**：`ReActExecutionLoop` 完成判定只有四维硬约束（maxRounds/maxTokens/timeout/maxCallsPerRound），`LoopDetector` 只会比 action/observation 指纹——**不懂"任务是否真的达成"**。
- **改动要点**：
  - **⑦ 完成判定**：`noul`("给定轨迹，任务是否已达成") 作为 `TerminationGate` 的**附加信号**（不替代四维硬约束）。
  - **⑧ 卡死检测**：`choice`("是否在重复同一无效动作") 补 `LoopDetector` 指纹去重之外的语义判定。
  - **红线**：网络判定**只在检查点**调用（如每 N 轮），避免每轮往返反噬延迟；低置信 → 以既有指纹/闸门为准（P10）。
- **验收**：单测——`noul` 判"已达成"在检查点触发提前终止（早于 maxRounds）；`choice` 判"重复无效"触发 `LOOP_DETECTED`；判定仅在配置的 N 轮检查点发起（桩断言调用次数）；低置信不改变既有终止行为。
- **依赖**：J3 | **优先级**：P2 | **估算**：M
- **落地说明（2026-09-26）**：`ReActExecutionLoop` 新增可选 `attachDecisionPlane(DecisionPort, DecisionThresholds, checkpointInterval)`（经 `DecisionPort` 接缝注入，P2 不破）；主循环在 `LoopDetector` 指纹判定之后、快照之前插入**检查点语义判定**——`isDecisionCheckpoint(round)` 仅当 `round % N == 0`（缺省 N=3，配置项 `langur.decision.react-checkpoint-rounds`）才发起**单次批量** `decide`（`task-complete` noul + `trajectory-stuck` choice）。⑦ 值+置信均 ≥ `completion`（0.85）→ 合成最终答案提前**成功**终止（`react_early_complete`）；⑧ choice 高置信判 `stuck` → `LOOP_DETECTED`（`react_stuck`）；低置信/缺失/异常 → `PROCEED`（`react_proceed`），终止仍由既有指纹 + 四维闸门决定（P10/P12③，**不替代**硬约束）。决策平面缺失（缺省 enabled=false）→ 不织入，行为与 v2.0 一致（P12①）。分流计数进 `decision_route_counts`（DECISION 维度）。`HarnessConfiguration.reActExecutionLoop` 经 `ObjectProvider<DecisionPort>` 松耦合织入。新增 `ReActExecutionLoopDecisionTest`（4 测，纯 JUnit5 无 Mockito）：检查点早停、语义卡死、检查点次数 < 总轮次（红线）、缺判定后端保持 v2.0 终止。

### J10. Skill `DECISION` 步骤 + 转译 PRD 质量门（插入点 ⑨⑩）
- [x] 已完成（2026-09-26）
- **现状/问题**：`SkillExpressionResolver` 的 CONDITION 只能判结构化字段（`${x.field}` + 6 运算符），**无法判"这段 PRD 是否完整 / 代码上下文是否足以转译"**。
- **改动要点**：
  - **⑨ `DECISION` 步骤**：新增 `StepType.DECISION`——由 `DecisionPort` 求值的**语义条件**（如"这段代码上下文是否足以转译 PRD？"），与既有 `CONDITION`（确定性表达式）**并存**、各取所长；沿用 `SkillExecutor` 步数硬上限防环。
  - **⑩ 转译 PRD 质量门**：`TranslatePrdSkill` 的 `draft` 步骤后加 `DECISION` 验收（`score` 完整性），低分触发**有界重 draft**。
  - **兜底**：`DecisionPort` 缺失 → `DECISION` 步骤降级为 `CONDITION`（若可表达）或按配置默认分支（P10）。
- **验收**：单测——`DECISION` 步骤按 Jev 判定走 then/else 分支；`TranslatePrdSkill` 低分触发一次重 draft、高分直接产出；步数超上限抛 `SkillExecutionException`；`DecisionPort` 缺失时降级不中断。
- **依赖**：J3 H8 | **优先级**：P2 | **估算**：M
- **落地说明（2026-09-26）**：`StepType` 新增 `DECISION`；`SkillStep` 新增 `decisionKey/decisionType(score|noul)/decisionInstructions/decisionThreshold` 字段（复用 `arguments` 承载被判定材料、`onTrue/onFalse` 承载分支）+ 工厂 `decisionScore/decisionNoul`。`SkillExecutor` 把 `DECISION` 与 `CONDITION` 同列为**分支步**（`nextIndexOf` 统一解析跳转目标，沿用 `MAX_STEPS=1000` 硬上限防环）：`evaluateDecision` 经注入的 `DecisionPort`（新增 6 参构造，`DefaultSkillToolGateway` 以 `ObjectProvider<DecisionPort>` 松耦合织入）构造 score/noul 问题，值+置信均 ≥ 阈值 → `onTrue` 否则 `onFalse`（低置信 fail-closed，P12③）；`DecisionPort` 缺失/后端异常/无答案 → **P10 降级**：有 `condition` 则按其求值（"降级为 CONDITION"），否则默认放行（`onTrue`，等价 v2.0 无质量门），绝不中断技能。⑩ `TranslatePrdSkill` 在 `draft` 后加 `quality-gate`（`decisionScore` 阈值 0.8，`onTrue=END` 直接产出、`onFalse=redraft`），`redraft` 为**有界一次**重写（其后无第二道门，顺序结束）。新增 `SkillExecutorDecisionTest`（10 测）：score 达/未达阈值分流、低置信 fail-closed、缺后端默认放行、缺后端降级 CONDITION、后端异常降级不中断、DECISION 死循环触发步数上限、TranslatePrd 高分直出/低分重 draft 一次/缺后端等价 v2.0；同步更新 `TranslatePrdSkillTest` 结构断言（2 步 → 4 步）。

---

## 5. N3 — RSI 起步（回放 + 反思 + 蒸馏）｜Phase 1（待派发）

> **前置门**：RSI 当前**暂停**，N3–N5 全部 **待派发**，解锁前不启动。基础规格见 RoadMap 2.0 §5；本节只列 v3.0 决策平面耦合增量（C2/C3）。
> **里程碑退出标准**：R0 能用历史快照重放候选策略（**含录制的 Jev 判定**）并输出基线对比；R1 反思用 Jev 廉价初筛降低 M5 调用；R2 蒸馏纳入 Jev 置信信号。

### R0. 回放验证引擎（反事实重放）— RSI 安全前提
- [x] 已完成（2026-09-26）
- **承接 2.0**：`ReplayEngine` 加载 `StateSnapshot` 轨迹 → 注入候选 → 沙箱重放 → 采集 V 指标 → 基线对比；轨迹仓库结构化存储执行轨迹。
- **v3.0 增量（C2）**：回放时**优先重放 J2 `RecordingDecisionPort` 录制的 Jev 判定**，不重新联网 → 反事实重放确定（DD5 录制回放扩展到决策平面）；托管锁版本（`jev-1.13.0`）、自部署固定权重确定性更佳。
- **验收**：重放"原策略"复现原结果（**含决策分流路径一致**）；劣化候选被拒绝；优化候选指标改善；Jev 判定走录制而非实时调用。
- **依赖**：H1 H5 J2 | **优先级**：P0 | **估算**：L

### R1. 反思自检（Reflexion Hook）— RSI L1
- [x] 已完成（2026-09-26）
- **承接 2.0**：`ReflectionHook` 挂 BEFORE_OUTPUT（改写型钩子），调 M5 self-critique；受终止闸门约束。绑定点由 AFTER_INFERENCE 调整为 BEFORE_OUTPUT——ReAct 循环对 AFTER_INFERENCE 的 MODIFY 载荷弃置（仅 ABORT 生效），只有 BEFORE_OUTPUT 的 MODIFY 经 `applyBeforeOutput` 回写 `agent.overrideFinalAnswer`。
- **v3.0 增量（C3）**：**Jev 当廉价初筛**——先用 `score`/`noul` 判"是否完整/重复/违规"，**只有低质/低置信才升级 M5 反思或 MODIFY 改写**，省昂贵 M5 调用。
- **验收**：易错任务开启反思后重复错误率下降；Jev 初筛过滤掉大部分无需 M5 的轮次（M5 调用次数对比基线下降）；反思不突破终止闸门；默认关灰度。
- **依赖**：M5 J1 | **优先级**：P1 | **估算**：M

### R2. 记忆自蒸馏（轨迹→L4 知识）— RSI L2
- [x] 已完成（2026-09-26）
- **承接 2.0**：离线消费轨迹仓库 → M5/M6 提炼成功模式/失败教训 → 经 H2 语义 Embedding 写入 `VectorMemoryService`（L4，namespace 隔离 + 衰减）；`DefaultContextAssembler` 语义召回。
- **v3.0 增量**：蒸馏质量门纳入 **Jev 置信信号**（录制的 confidence/distribution 作为轨迹可信度权重，低置信轨迹降权/不入蒸馏）。
- **验收**：同类任务二次执行召回首次蒸馏知识；低质/重复蒸馏被去重拒绝；低置信轨迹不污染 L4。
- **依赖**：H2 H5 J2 | **优先级**：P1 | **估算**：M

---

## 6. N4 — RSI 枢纽（技能自合成）｜Phase 2（待派发）

> **前置门**：RSI 暂停，待派发。基础规格见 RoadMap 2.0 §6。
> **里程碑退出标准**：从高频成功轨迹自动归纳候选 Skill（**可含 Jev 支撑的 `DECISION` 步骤**），经 R0 回放校验后注册为 `skill:{name}`，同类任务路由命中、LLM 轮次与成本下降（可量化）。

### R3. 技能自合成（轨迹→Skill）— RSI L3（枢纽）
- [x] 已完成（2026-09-26）
- **承接 2.0**：`SkillSynthesizer` 挖掘高频成功轨迹 → M5 归纳 `SkillStep` 序列 → 候选 `SkillSpec` → **R0 回放**校验（不劣于原 ReAct 路径）→ `SkillRegistrar` 注册（版本化 + 可回滚）；护栏（P11）：不含越权工具、过四层校验链元数据审查。
- **v3.0 增量**：合成的 Skill **可包含 J10 `DECISION` 步骤**——把"临场语义判定"固化为确定性编排里的判定节点（如"上下文足够则直接转译，否则先补读代码"），进一步降本增稳。
- **验收**：重复性任务轨迹集自动合成等价 Skill（含 DECISION 步骤）；回放校验通过；同类任务路由命中、LLM 轮次下降；劣化候选被拒绝；合成 Skill 无越权工具。
- **依赖**：H8 R0 J10 | **优先级**：P1 | **估算**：L

---

## 7. N5 — RSI 进阶 + 承接治理/扩展｜Phase 2（待派发）

> **前置门**：RSI 暂停，待派发。基础规格见 RoadMap 2.0 §7。
> **里程碑退出标准**：R-G 安全平面生效（**统辖 Jev：对安全恒 advisory，阈值变更即提案**）；R4 能离线调优 Jev 阈值/prompt/路由并灰度（达 **D3**）；R5 强审批下自动接入新工具；H11/H12 承接项按基建现状推进。

### R-G. RSI 安全平面（护栏）— P0，统辖决策平面
- [x] 已完成（2026-09-26）
- **承接 2.0**：提案-验证-应用三段式；版本化提案仓库（DD6 `t_rsi_proposal`）+ checksum 审计链 + 一键回滚；变更频率限流 + 递归深度上限；权限隔离红线（不可改 `SecurityPolicySPI`/校验链）；灰度发布框架。
- **v3.0 增量（C4）**：**Jev 同受 R-G 统辖**——决策平面对安全/审批恒 advisory（只收紧不放松）；**Jev 阈值/prompt/路由的变更本身是 RSI 提案**，须经 R-G 灰度 + 基线对比 + 回滚；`DecisionEngineSPI` 热插拔生效（v2.0 §15.4）。
- **验收**：未经验证的提案（含 Jev 阈值变更）无法生效；劣化上线自动回滚；尝试放松 CRITICAL 人审 / 修改校验链的提案被拒绝并告警；变更频率超限被限流。
- **依赖**：R0 J3 | **优先级**：P0 | **估算**：M

### R4. 策略自优化（调 Jev 阈值/prompt/路由）— RSI L4 → 达 D3
- [ ] 待派发
- **承接 2.0**：从轨迹挖掘低效模式 → M5 生成四类提案（Prompt 模板 / `LayerRouter` 规则 / 闸门·重试·Token·温度超参 / `DecisionEngineSPI` 策略）→ R0 回放调优（bandit/贝叶斯）→ R-G 灰度 + 基线对比 → 生效/回滚。
- **v3.0 增量（C1）**：R4 的 `DecisionEngineSPI` 提案**直接调优 Jev 阈值/prompt/路由**（经 J8 预留的接缝，非硬编路由）——**D2→D3 的跃迁 = 决策平面从"人工标定"进化为"自我优化"**（v3.0 §9）。
- **验收**：候选 Jev 阈值/prompt 经回放优于基线后灰度上线；线上判定回退率/置信分布劣化自动回滚；全程审计可溯源；路由调优经 J8 接缝生效而非改路由类源码。
- **依赖**：R0 R-G J8 | **优先级**：P2 | **估算**：L

### R5. 工具自扩展 — RSI L5
- [ ] 待派发
- **承接 2.0**：能力缺口检测 → 自动检索候选（MCP 注册表 / OpenAPI 目录）→ 沙箱回归 + **强制人工审批** → 注册为 MCP/REST 工具 → 灰度。
- **v3.0 关联**：缺口检测可借决策平面 `noul`("当前工具集是否足以完成任务") 做廉价归因初筛。
- **验收**：缺口被识别并给候选；未审批工具不生效；审批通过后经四层校验链可用；越权/内网候选被 SSRF/权限层拒绝。
- **依赖**：H6 H7 R0 R-G | **优先级**：P2 | **估算**：L

### H11. 依赖治理（ArchUnit + JDK/Lombok toolchain 固定）— 承接 2.0
- [ ] 待派发
- **承接 2.0**：ArchUnit 断言 domain 不 import Spring/infrastructure、api 不直调 domain、common 无状态 Bean、无循环依赖；`maven-toolchains-plugin` 固定 JDK 17；CI 显式 `JAVA_HOME` 校验。
- **v3.0 关联**：J1 的 `DecisionPort` 契约落 domain，**ArchUnit 可守护其零外部依赖**（决策平面值对象不得引入 Spring/Jackson 注解）。
- **依赖**：无 | **优先级**：P1 | **估算**：S

### H12. 多 Agent 协作编排 — 承接 2.0
- [ ] 待派发
- **承接 2.0**：SUB_AGENT 步骤（H8 铺路）+ Agent 间消息总线（Spring Event / RocketMQ）+ AgentId 隔离与跨 Agent 任务分发。
- **v3.0 关联**：多 Agent 的"委派/汇聚/仲裁"判定可由决策平面承载（`choice` 选子 Agent、`score` 仲裁汇聚结果）。
- **依赖**：H8 | **优先级**：P3 | **估算**：L

---

## 8. 待决策项（DD10–DD13，开始前需拍板；承接 2.0 DD1–DD9）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| DD10 | Jev 后端选型 | 托管 API / 自部署 Laya(421M) / 自部署 Kev(0.8B) / 混合 | **混合**：非敏感走托管（省事、输出免费），敏感命名空间（legal-contracts/code）**强制自部署**（数据驻留，J3） |
| DD11 | 置信阈值标定 | 人工经验值 / 离线用历史轨迹标定 / R4 自动调 | 先人工经验值（D1/D2：routing 0.75 / approval-auto 0.90 / artifact-accept 0.80 / completion 0.85），R4 就绪后自动调（D3） |
| DD12 | 录制粒度 | 全量录制 / 仅闸门录制 / 采样 | **全量录制**（R0 确定性前提，成本低；J2 `record=true` 常开） |
| DD13 | `DecisionEngineSPI` 复用边界 | 决策平面直接实现该 SPI / 新端口 + SPI 适配 | domain 新端口 **`DecisionPort`**，infra 适配到既有 `DecisionEngineSPI`（不破坏 v2.0 SPI 契约，J1/J8） |

> DD5（回放确定性）、DD6（提案存储）、DD8（自治边界）承接自 RoadMap 2.0，对决策平面同样适用（C2/C4）。

---

## 9. 度量与验收（里程碑 KPI）

| 里程碑 | 关键 KPI | 目标 |
|--------|----------|------|
| N1 | 契约纯度 / Jev 往返延迟 / 录制覆盖 / 指标可查 / 关闭等价 v2.0 | domain 零外部依赖 / `decision_latency` ≪ 生成延迟（~百毫秒级）/ 100% 判定录制 / 5 维决策指标可查 / 关闭时行为 diff 为 0 |
| N2 | Workflow 提效 / 长任务吞吐 / 产物质量 / 兜底率 | 决策闸门跳过不必要昂贵阶段（LLM 轮次↓可量化）/ 非 CRITICAL 自动放行占比↑且 CRITICAL 0 漏审 / 产物验收合格率↑ / `decision_fallback_rate` 可控 |
| N3 | 回放确定性（含判定）/ 反思降本 / 蒸馏质量 | 原策略重放复现（决策分流一致）/ M5 调用次数↓ / 低置信轨迹不污染 L4 |
| N4 | 自合成 Skill（含 DECISION）通过率 / 成本下降 | 回放校验通过 / 同类任务 LLM 轮次↓（可量化） |
| N5 | RSI 受控性 / Jev 自优化增益 / 自扩展安全 | 100% 提案（含 Jev 阈值变更）可回滚 / R4 灰度增益为正（回退率↓/置信↑）/ 越权候选 0 生效 |

---

## 10. 风险登记

| 风险 | 影响 | 缓解 |
|------|------|------|
| Jev 判定被误用于放松安全闸门 | 安全/资损 | P12 红线：对安全/审批恒 advisory、只收紧不放松；CRITICAL 恒人审（J5）；R-G 统辖（C4）；低置信 fail-closed |
| 敏感数据（合同/代码）出网到第三方 Jev | 合规/泄密 | `data-residency.sensitive-namespaces` 强制 local 后端（J3/DD10）；禁止出网；密钥经 KMS 不明文 |
| Jev 非确定 / 版本漂移致回放失真 | R0 验证失真 | `RecordingDecisionPort` 录制回放（C2/J2）；托管锁版本 `jev-1.13.0`；自部署固定权重（DD5/DD12） |
| 每轮网络判定反噬延迟 | 执行变慢 | 判定只在检查点调用（J9 每 N 轮）；`CachingDecisionPort` 缓存（J3）；`timeout-millis` 超时即回退规则 |
| 决策平面与六组件耦合破坏正交 | 架构回归 | 决策平面是 `ObjectProvider` 注入的**横切端口**非第七组件（P4）；缺失即降级（P10）；ArchUnit 守护 domain 纯度（H11） |
| J8 层路由与 R4 学习型路由边界混淆 | 越界实现 RSI | J8 **仅 advisory 运行期判定**，离线调优路由规则明确划归 R4（N5，待派发）；J8 验收断言不含自修改逻辑 |
| RSI 自我改进失控（承接 2.0） | 安全/资损 | P11 红线 + R-G 安全平面 + R0 回放 + 人工审批门（DD8）；Jev 阈值变更即提案 |
| 无官方 Java SDK | 接入成本 | Jev 是纯 REST/JSON，复用既有 `WebClient`（H6/H7）直接 POST；无 SDK 依赖（J1） |

---

## 11. 完成记录

> 每完成一项，在此补日期与说明，并将对应任务 `[ ]` 改为 `[x]`、同步更新 v3.0 架构文档 §3/§4 落点与 §9 成熟度。

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-09-25 | — | RoadMap 3.0 创建：依据 v3.0 架构文档规划 J1–J10（决策平面）+ 承接 R0–R5/R-G（RSI，待派发）+ 保留 H11/H12；全部任务待派发，Phase 0（N1–N2）可独立排期，N3–N5 以 RSI 解锁为前置门 |
| 2026-09-25 | J1 | `DecisionPort` 契约落 domain（`port/DecisionPort` + `harness/decision/{DecisionRequest,DecisionQuestion,DecisionType(choice/noul/score),DecisionResponse,DecisionAnswer,DecisionThresholds}`，纯 JDK 零外部依赖，P1 已断言无 Spring/Jackson import）；usage 复用 H1 `LLMPort.TokenUsage`；infra `TypeSafeDecisionAdapter`（WebClient POST `{state,model,questions{noul\|choice\|score,instructions,criteria}}`，批量投机扇出 state 只发一次，解析 choice/value+confidence+distribution+usage，异常/超时/无问题委派兜底不抛出）+ `RuleFallbackDecisionAdapter`（复用 H10 `OutputContentReviewer`，confidence 恒 0 → J3 ThresholdRouter fail-closed）；DD13 domain 新端口、infra 适配既有 `DecisionEngineSPI`（J3 装配）；适配器不带 `@Component`，J3 条件装配。新增 17 测（domain 7 + infra 10，HttpServer 离线桩），`mvn clean test` 全绿（infra 271→281）。未改装配，无需冒烟 |
| 2026-09-26 | J2 | `RecordingDecisionPort` 装饰器（包裹后端，判定原样透传）：`record=true` 时把 request/response/latencyMillis 封为 S 组件 `StateSnapshot` 交 `DecisionTrajectoryRecorder` 落轨迹（R0 前向兼容 C2/DD12；缺省实现 `LoggingDecisionTrajectoryRecorder`，J3 装配）；决策维度指标经 H5 `EvaluationService`→`MicrometerEvaluationService` 落 MeterRegistry（新增 `MetricDimension.DECISION`，v3.0 §6.1）：`decision_latency`/`decision_confidence`(均值)/`decision_fallback_rate`(依 RuleFallback confidence 恒 0 契约推断降级)/`decision_cost`(输入 token，复用 H1)；`decision_route_counts` 留 J3；判定（含 confidence/distribution）经 `Checksums.sha256` 写审计链。录制/指标/审计失败静默降级（P10）。新增 7 测（SimpleMeterRegistry + 捕获桩），`mvn clean test` 全绿（domain 121→128、infra 281→288）。未改装配，无需冒烟 |
| 2026-09-26 | J3 | **N1 完成，达成熟度 D1（Jev advisory）**。start `DecisionConfiguration`（`@ConditionalOnProperty langur.decision.enabled=true`，默认关闭不产 Bean、行为与 v2.0 一致，P12①）经 `ObjectProvider` 组装装饰链 `Recording ⊃ Caching ⊃ ThresholdRouter ⊃ backend`，`decisionPort` 标 `@Primary` 消除双 `DecisionPort` Bean 歧义；infra `ThresholdRouter`（decide 透传保留 confidence + `route()` 供 J4–J10 分流：高置信 CHOICE→RUN/SKIP/BRANCH/APPROVE/TERMINATE，低置信/缺失→FAIL_CLOSED，P12③；每次分流发 `decision_route_counts` 事件计数，补齐 J2 留口）、`CachingDecisionPort`（键=`langur:decision:`+sha256(state,model,问题签名)，复用 H4 `CacheBackend`，仅缓存 answers JSON——命中零成本空 usage，缓存/序列化异常静默直连下层，P10）、`LocalDecisionAdapter`（继承 TypeSafe 适配器换 base-url 免鉴权，Kev 兼容 v3.0 §2.2）、`DataResidencyDecisionPort`（命中 `sensitive-namespaces` 的 state 强制 local；无 local 端点 fail-closed 规则兜底，绝不发往第三方，DD10/P12⑤；缺省空列表不启用）、`DecisionProperties`（`langur.decision.*` P9，DD11 阈值 0.75/0.90/0.80/0.85 经 `toDomain()` 注入）；`api-key-ref` 经 H7 `SecretResolver` 解析、失败降级空串（后端拒绝→规则兜底即 fail-closed，绝不明文，P12⑥）；application.yml 补全量注释样例。新增 19 测（infra 13：Caching 6/Threshold 6/DataResidency 5 中合并计 + start 6），`mvn clean test` 全绿（infra 288→305、start 27→33）。装配变更已冒烟：默认关 UP、`enabled=true backend=off` UP，无 Bean 歧义/异常 |
| 2026-09-26 | J4–J6 | **Workflow 三插入点（①②③）全部落地**。domain 新增 `GateRoute`（与 infra `ThresholdRouter.RouteAction` 语义对齐但落 domain，遵 P2 单向依赖：`of(DecisionAnswer,threshold)` 低置信/非 CHOICE→FAIL_CLOSED/RUN_NEXT，choice 小写映射 skip/branch/abort/approve）、`StageDecisionGate`（record，防御式紧凑构造器：空 key 抛错、type 缺省 CHOICE、threshold≤0→DEFAULT_ROUTING 0.75，`toQuestion()`）；`WorkflowStage` 加 `@Builder(toBuilder)` + `critical`/`decisionGate` 字段与 `withCritical`/`withDecisionGate`；`Artifact` 加 `accepted`/`reviewNote`（`of`→accepted=true 保持 v2.0、`reviewed` 供 J6 打标）。`WorkflowExecutionLoop` 重构为带索引 while 循环：J4 阶段产出后 `evaluateStageGate`（state=阶段指令+截断产出）经 `attachDecisionPlane` 注入的 `DecisionPort` 判定，SKIP=`completed.add(next)`+即时快照（续跑确定性）、BRANCH 带 `branchBudget=stages.size()` 防环、ABORT 终止、REQUIRE_APPROVAL 建 `workflow-gate:<stageId>` 审批单挂起且续跑前 `findBlockingGateApproval` 阻断 DENIED/PENDING（只收紧）；J5 `checkApproval` 仅对**非 CRITICAL** 且无既有单时 `score("approval-auto")` 三分流——`value∧confidence≥0.90` 自动放行（建 APPROVED 单、decisionBy=`decision-plane` 留痕）否则转人审，**CRITICAL 恒人审、决策平面零调用**（桩断言 requests.size()==0，P12②）；J6 `reviewArtifact` 对产物 `score("artifact-accept")`，`value∧confidence≥0.80` 接受否则 1 次有界重试（受 `MAX_ARTIFACT_RETRIES`+`gateTripped` 约束），仍低分则 `accepted=false`+reviewNote 打标**不阻断**。所有分流发 `decision_route_counts`（GateRoute 名小写）；`decideQuietly` 吞异常降级（P10），端口缺失=完全 v2.0 行为。infra `WorkflowProperties`+`critical`/`decisionGate`（DecisionGateProps）、`WorkflowDefinitionRegistrar.toGate`（key/instructions 空→闸门失效，threshold null→0→DEFAULT_ROUTING）；start `HarnessConfiguration` 对 workflow+hybrid 两 Bean `attachDecisionPlane(ObjectProvider<DecisionPort> 尊重 @Primary, ObjectProvider<DecisionProperties>)`；application.yml 补 workflow 决策闸门/critical/J6 注释样例。新增 14 测（domain `WorkflowExecutionLoopDecisionGateTest` 12 + infra registrar +2），`mvn clean test` 全绿（domain 128→140、infra 305→307、start 33）。装配变更已双冒烟：默认关 UP、`enabled=true backend=off` UP（决策平面装配日志确认），无 Bean 歧义/异常 |
| 2026-09-26 | J7–J8 | **Hybrid 执行器择优/升降级（④⑤）+ 层路由 advisory（⑥）落地**。J7：`WorkflowExecutionLoop` 增可选每阶段执行器池 `attachStageExecutors(Map<范式,执行循环>)`（生产装配 {REACT,PLAN_AND_EXECUTE}），`executeStage` 在 HYBRID+决策平面在场时——④ 经 `choice("stage-executor")` 为每阶段择优"够用的最便宜执行器"（简单→ReAct、复杂→Plan，阈值 routing 0.75），低置信/缺失/非 HYBRID 回退构造期 `stageParadigm`（v2.0 固定中层 Plan，P10）；⑤ 仅在走了廉价 ReAct 路径时经一次批量 `noul("stage-complete"/"stage-stuck")`（值+置信均 ≥ completion 0.85 才采信）判：已达成且阶段成功→提前成功终止跳过剩余阶段（提效）、重复卡死→升级 ReAct→Plan 重跑（接既有 replan-on-failure，无升级路径则终止）、低置信→PROCEED 回退既有 LoopDetector/TerminationGate（P12③）；分流发 `decision_route_counts`（stage_react/stage_plan/stage_executor_fallback/early_complete/upgrade_plan/stuck_terminate）。J8：`DefaultLayerRouter` 增可选 `attachDecisionPlane(DecisionPort,阈值)`（经 DecisionPort 接缝注入、后端由 J3 装配适配 DecisionEngineSPI/Jev，不硬编进路由类，C1），`route()` 先规则后 advisory——高置信 `choice("layer-route")` 覆盖规则，低置信/不可识别/异常回退规则（P10/P12③）；**合规守卫**：规则判 WORKFLOW/HYBRID 时 advisory 绝不降级到无审批的 REACT/PLAN（P12②只收紧）；**advisory 无状态、不自修改路由规则**（自修改/离线调优属 R4/RSI，当前排除）。start `DomainServiceConfiguration.layerRouter` + `HarnessConfiguration.hybridExecutionLoop` 经 ObjectProvider 织入（决策关闭→不织入，行为同 v2.0，P12①）。新增 14 测（domain `WorkflowExecutionLoopHybridTest` 6 + `DefaultLayerRouterDecisionTest` 8），`mvn clean test` 全绿（domain 140→154、infra 307、start 33）。装配变更已双冒烟：默认关 UP、`enabled=true backend=off` UP（决策平面装配日志确认），无 Bean 歧义/异常 |
| 2026-09-26 | J9–J10 | **N2 完成，达成熟度 D2（决策闸门生效）——ReAct ⑦⑧ + Skill ⑨⑩ 落地**。J9：`ReActExecutionLoop` 增可选 `attachDecisionPlane(DecisionPort,阈值,检查点间隔)`（经 DecisionPort 接缝注入，P2 不破），主循环在 LoopDetector 指纹判定后、快照前插入**检查点语义判定**——`isDecisionCheckpoint(round)` 仅当 `round % N == 0`（缺省 N=3，新配置 `langur.decision.react-checkpoint-rounds`）才发起**单次批量** `decide`（`task-complete` noul + `trajectory-stuck` choice）：⑦ 值+置信均 ≥ completion 0.85→合成最终答案提前**成功**终止（`react_early_complete`，**附加信号不替代四维硬约束**）、⑧ choice 高置信判 stuck→`LOOP_DETECTED`（`react_stuck`，补指纹之外语义判定）、低置信/缺失/异常→PROCEED（`react_proceed`）回退既有指纹+闸门（P10/P12③）；决策平面缺失（缺省 enabled=false）→不织入，行为同 v2.0（P12①）；`HarnessConfiguration.reActExecutionLoop` 经 ObjectProvider 织入。J10：`StepType` 增 `DECISION`，`SkillStep` 增 `decisionKey/decisionType(score\|noul)/decisionInstructions/decisionThreshold`（复用 `arguments` 承载被判定材料、`onTrue/onFalse` 承载分支）+ 工厂 `decisionScore/decisionNoul`；`SkillExecutor` 把 DECISION 与 CONDITION 同列**分支步**（`nextIndexOf` 统一解析跳转，沿用 `MAX_STEPS=1000` 防环），`evaluateDecision` 经注入 `DecisionPort`（新 6 参构造，`DefaultSkillToolGateway` 以 ObjectProvider 织入）求值 score/noul，值+置信均 ≥ 阈值→onTrue 否则 onFalse（低置信 fail-closed P12③），端口缺失/后端异常/无答案→**P10 降级**（有 condition 按其求值，否则默认放行=等价 v2.0 无质量门，绝不中断）；⑩ `TranslatePrdSkill` 在 draft 后加 `quality-gate`（score 阈值 0.8，onTrue=END 直出、onFalse=redraft），redraft **有界一次**（其后无第二道门）。新增 14 测（domain `ReActExecutionLoopDecisionTest` 4 无 Mockito + infra `SkillExecutorDecisionTest` 10）+ 同步 `TranslatePrdSkillTest`（2→4 步），`mvn clean test` 全绿（domain 154→158、infra 307→317、start 33）。装配变更已双冒烟：默认关 UP、`enabled=true backend=off` UP（决策平面装配日志确认），无 Bean 歧义/异常 |
| 2026-09-26 | R0 | **N3 起步首项落地——回放验证引擎（反事实重放，RSI 安全前提 P0）**。domain 新增纯 JDK `harness/rsi` 包（零外部依赖 P1）：`Trajectory`（taskId+success+baselineThresholds+按轮 `TrajectoryStep` 成本步骤+录制 `RecordedDecision`，构造防御性拷贝、判定按轮次稳定排序保证确定）、`RecordedDecision`（key/round/录制 `DecisionAnswer`/`ThresholdCategory`/`baselineRoute`/latency）、`ReplayRoute`（域内纯实现镜像 infra `ThresholdRouter.route` 分流映射，遵 P2 不 import infra）、`ThresholdCategory`（ROUTING/APPROVAL_AUTO/ARTIFACT_ACCEPT/COMPLETION → 从 `DecisionThresholds` 选档）、`ReplayCandidate`（BASELINE/THRESHOLD/ROUTE/PROMPT/SKILL/PARAMS + 阈值覆盖 + 按 key 强制路由）、`ReplayMetrics`（success/rounds/tokens/latency/interceptions/routePath）、`BaselineComparison`（IMPROVED/NEUTRAL/DEGRADED + deltas + rejected + reason）、`TrajectoryRepository` 端口、`ReplayEngine`（无状态、无副作用、**不持有 DecisionPort**——结构性排除实时判定调用）。**确定性反事实重放**：只重放录制的 Jev 判定（C2/DD12，绝不联网），以候选阈值/强制路由重算分流并与 `baselineRoute` 比对；**诚实成本模型**——轨迹 steps 是原执行实际跑过的轮次，候选只能移除成本（SKIP 避免该轮 / TERMINATE 截断其后并丢失成功），不凭空展开未录制轮次（RUN 覆盖基线 SKIP → 标注"轨迹不足"），故生成级候选（PROMPT/SKILL/PARAMS）离线重放录制响应复现基线 → NEUTRAL。**RSI 红线（P11/P12）编码进裁定**：放松审批闸门（APPROVAL_AUTO 由保守转非保守）→ DEGRADED 拒绝（只收紧 P12②）；丢失成功 / 抬高成本 → DEGRADED 拒绝；降本无回归 → IMPROVED；**回放通过≠生效**（候选仅提案，生效须待 R-G 灰度+高危人审）。infra `InMemoryTrajectoryRepository`（ConcurrentHashMap，缺省装配、可被 JPA/快照级覆盖 P5）+ `RsiProperties`（`langur.rsi.enabled` 默认 false，P11 暂停态）；start `RsiConfiguration`（`@ConditionalOnProperty` 无 matchIfMissing → 默认 back-off 不产 Bean，行为等价既有版本；开启装配 ReplayEngine + 缺省内存轨迹仓库）；application.yml 补 `langur.rsi` 全量注释样例。新增 14 测（domain `ReplayEngineTest` 9 无 Mockito：基线复现/确定性/SKIP 降本 IMPROVED/阈值重算 fail-closed→skip/TERMINATE 丢失成功拒绝/放松审批拒绝/生成级 NEUTRAL/轨迹不足标注/入参守卫 + infra `InMemoryTrajectoryRepositoryTest` 3 + start `RsiConfigurationTest` 2），`mvn clean test` 全绿（domain 158→167、infra 317→320、start 33→35）。装配变更已双冒烟：默认关 UP（0 条 `[RSI]` 日志，back-off 确认）、`rsi.enabled=true` UP（`[RSI] assembling R0 replay engine` 日志确认），无 Bean 歧义/异常。**注**：将 J2 live 录制按真实 taskId 落轨迹仓库（当前 `RecordingDecisionPort` 以常量 `decision-plane` 为快照 taskId）属 C2 集成增量，需小幅 J2 录制器增强，划为 R0 后续/R2 衔接项，未在 R0 内改 committed J2 代码 |
| 2026-09-26 | R1 | **反思自检（Reflexion Hook，RSI L1）落地**。infra `ReflectionHook implements LifecycleHook`：挂 **BEFORE_OUTPUT**（改写型，`order()=5` 先于 H10 `ContentReviewOutputHook` 的 10——反思改写必须发生在内容审核之前，改写后答案仍被 H10 复查，反思不能绕过安全层 P11/P12）；两级反思（C3 增量）：① Jev 廉价初筛 `score("answer-quality")`——**质量+置信双达阈（0.85）→ 跳过 M5 省钱（P10）**，否则升级；② M5 自我批评 `LlmGateway.complete(ModelRole.REASONING, 反思提示词, draft)` 改写，命中改写即 `MODIFY`、无改动/失败即 `CONTINUE`。**P10 全线降级**：DecisionPort 缺失/异常、M5 失败一律放行原始答案；钩子只 MODIFY 绝不 ABORT（阻断交安全闸门）。配置驱动（P9）：`langur.rsi.reflection.enabled` 默认 false，与总开关 `langur.rsi.enabled` AND 生效（`@ConditionalOnProperty` 无 matchIfMissing）；`enabled()` 亦做 AND 守卫。`RsiProperties` 增嵌套 `Reflection`（enabled/prescreenThreshold 0.85/critiqueSystemPrompt）。新增 10 测（infra `ReflectionHookTest`，匿名桩 DecisionPort/LlmGateway 无 Mockito：高质高置信跳过 M5、低质升级 M5 改写 MODIFY、M5 无改动 CONTINUE、order<10 先于 H10、总/分开关关 enabled=false、端口缺失/异常/低置信升级、M5 失败降级放行、空载荷守卫），`mvn clean test` 全绿（infra 320→330；domain/start 不变）。装配变更未做运行时冒烟（用户拒绝后台起服务）。**注**：绑定点为 BEFORE_OUTPUT 而非 AFTER_INFERENCE——ReAct 循环丢弃 AFTER_INFERENCE 的 MODIFY 载荷，仅 BEFORE_OUTPUT MODIFY 被 `applyBeforeOutput` 应用 |
| 2026-09-26 | R2 | **N3 完成——记忆自蒸馏（轨迹→L4 知识，RSI L2）落地**。domain 新增 `harness/rsi` 纯 JDK（P1）：`DistillKind`（SUCCESS_PATTERN/FAILURE_LESSON）、`DistilledMemory`（namespace/id/content/confidence/sourceTaskId/kind，构造收敛：namespace 缺省 `rsi-distilled`、confidence 钳 [0,1]、blank 拒绝，`withConfidence`/`withNamespace` 供蒸馏器统一注入）、`DistillationExtractor` 端口（M5/M6 提炼依赖倒置接缝 P3/P5）、`TemplateDistillationExtractor`（确定性模板兜底：从轨迹已录制信号组装成功模式/失败教训，稳定 id=sha256 前 16 位幂等，零网络）、`DistillationResult`（taskId/confidence/gated/memories/rejects）、`MemoryDistiller`（**四段流水线**：① Jev 置信门——轨迹录制判定置信均值 < minConfidence(0.85) 整条不入蒸馏，低置信轨迹不污染 L4（v3.0 增量）；② M5/M6 提炼经端口；③ 语义去重——写前对目标命名空间 recall top-1，cosine ≥ dedupThreshold(0.95) 判 DUPLICATE 拒绝，去重异常 P10 降级放行；④ 落 L4——`VectorMemoryService.remember` UPSERT，namespace 隔离，metadata 携 confidence/source/taskId/kind 供召回降权）。infra `LlmDistillationExtractor`（M5/M6 经 `LlmGateway` REASONING 归纳，异常/空/空网关一律回退模板 P10）；`RsiProperties` 增嵌套 `Distillation`（enabled=false/minConfidence 0.85/dedupThreshold 0.95/namespace rsi-distilled/llmEnabled=false）；start `RsiConfiguration` 增 `distillationExtractor`（llmEnabled→M5/M6，否则模板；P5 开闭）+ `memoryDistiller`（阈值经配置注入），均 `@ConditionalOnProperty langur.rsi.distillation.enabled=true`（默认关，P11）；application.yml 补 distillation 全量注释样例。新增 17 测（domain `MemoryDistillerTest` 9 无 Mockito：成功模式/失败教训/低置信门拦/无判定置信 1.0/空轨迹/空入参/语义去重/命名空间/空抽取器守卫 + infra `LlmDistillationExtractorTest` 5 匿名桩 + start `RsiConfigurationTest` 2→5），`mvn clean test` 全绿（domain 167→176、infra 330→335、start 35→38）。装配变更未做运行时冒烟（用户拒绝后台起服务，同 R1）。**注**：① 蒸馏轨迹仅含 R0 `Trajectory` 已录制信号（action 标识 + 判定分流），不含观测全文——更富内容的蒸馏需 R0 轨迹扩字段（携 observation 文本），划为 N4 前衔接项；② "衰减"逻辑未落地（`VectorMemoryService` 尚无 decay），namespace 隔离为 R2 交付、衰减留 L4 后续；③ 离线消费调度（定时/触发驱动 `distillAndStore`）未建——R2 交付组件与测试，编排入口留 N4/N5 |
| 2026-09-26 | R3 | **N4 完成——技能自合成（轨迹→Skill，RSI L3 枢纽）落地**。infra `harness/rsi` 新增（Skill 基础设施在 infra，遵 P2 domain 不 import infra）：`SkillSynthesisCandidate`（name/description/permission/riskLevel/steps/sourceTaskIds + 诚实成本模型 llmRounds vs baselineLlmRounds，`toolIds()` 抽取 TOOL_CALL 引用供越权审查，`toSkillSpec()` 转注册单元；P11 候选默认不生效）、`SkillSynthesisVerdict`（ACCEPTED/REJECTED_EMPTY/REJECTED_UNAUTHORIZED_TOOL/REJECTED_DEGRADED）、`SkillSynthesizer`（确定性模板归纳：首条成功轨迹动作序列连续去重→TOOL_CALL 步骤，task-complete 录制判定→J10 DECISION 完成门（noul 0.85），llmRounds=DECISION 数、baselineLlmRounds=各轨迹轮次和；无成功轨迹返回 empty）、`SkillSynthesisValidator`（离线确定性三道护栏：空候选/越权工具（引用 ∉ 白名单）/劣化（llmRounds>baseline 只降本）→ 拒绝，P11/P12）、`SynthesizedSkillRegistrar`（版本化 + 一键回滚：按 toolId 版本栈注册进 SkillCatalog + ToolRegistry（source=SKILL 四层校验元数据），rollback 恢复上一版本、栈空则注销）。`SkillCatalog` 补 `unregister`（R3 回滚到空所需，3 行 additive 非破坏）。`RsiProperties` 增嵌套 `Synthesis`（enabled=false/allowedToolIds 缺省空=P11 缺省收紧不放行任何工具）；start `RsiConfiguration` 增 `skillSynthesizer`/`skillSynthesisValidator`/`synthesizedSkillRegistrar`（`@ConditionalOnProperty langur.rsi.synthesis.enabled=true` 默认关）；application.yml 补 synthesis 注释样例。新增 16 测（infra `SkillSynthesizerTest` 5 + `SkillSynthesisValidatorTest` 5 + `SynthesizedSkillRegistrarTest` 4 匿名桩 ToolRegistry 无 Mockito + start `RsiConfigurationTest` 38→40），`mvn clean test` 全绿（infra 335→349、start 38→40；domain 不变）。**注**：① M5 归纳（语义化 SkillStep）为后续增强，R3 交付确定性模板归纳基线（P10 离线零网络）；② 合成"编排入口"（挖掘→合成→校验→注册的离线 job）未建，R3 交付组件与测试，编排留 N5 后；③ 运行期"同类任务路由命中"依赖既有 `skill:{name}` 路由（H8），离线验证合成等价与降本，端到端路由增益量化留 N5 收尾 |
| 2026-09-26 | R-G | **RSI 安全平面（护栏，P0，统辖决策平面）落地**。domain `harness/rsi` 纯 JDK（P1）：`RsiProposalStatus`（PROPOSED→VALIDATED→APPLIED + APPROVED/REJECTED/ROLLED_BACK）、`RsiProposal`（id/kind（复用 R0 `CandidateKind`）/target/payload/depth/status/checksum/createdAtMillis；构造收敛缺省、checksum 经 `Checksums.sha256` 派生、`withStatus` 状态迁移保持审计校验和不变）、`RsiProposalRepository` 端口（DD6 `t_rsi_proposal`，P3）、`SafetyGateResult`（allowed/reason 供告警审计）、`RsiSafetyPlane`（**提案-验证-应用三段式护栏，红线编码进状态机**：① 权限隔离——target 命中 `security.policy`/`validation.` 前缀提交即拒绝（不可自改安全层，P11/P12）；② 只收紧不放松——放松审批闸门在验证阶段被 R0 DEGRADED 裁定拒绝；③ 高危人审——`CandidateKind.isPolicyLevel()`（THRESHOLD/ROUTE）或 target 含 `critical` 的提案验证通过后仍须 `humanApproved=true` 方可应用；④ 递归深度上限（maxDepth=3）；⑤ 变更频率限流（滑动 60s 窗口 maxProposalsPerMinute=10）；⑥ 未经验证不得生效——非 VALIDATED 状态不得 apply）。infra `InMemoryRsiProposalRepository`（ConcurrentHashMap，缺省装配可被 JPA 覆盖 P5）；`RsiProperties` 增嵌套 `Governance`（enabled=false/maxDepth 3/maxProposalsPerMinute 10/forbiddenTargetPrefixes [security.policy, validation.]）；start `RsiConfiguration` 增 `rsiProposalRepository`（`@ConditionalOnMissingBean`）+ `rsiSafetyPlane`（`@ConditionalOnProperty langur.rsi.governance.enabled=true` 默认关）；application.yml 补 governance 注释样例。新增 16 测（domain `RsiSafetyPlaneTest` 11 无 Mockito：红线目标拒绝/深度超限/频率限流/happy path/高危人审/劣化拒绝/未验证不得应用/回滚/空守卫/校验和跨状态不变 + infra `InMemoryRsiProposalRepositoryTest` 3 + start `RsiConfigurationTest` 40→42），`mvn clean test` 全绿（domain 176→187、infra 349→352、start 40→42）。**注**：① "劣化上线自动回滚"的在线监控闭环（应用后持续回放、劣化触发 rollback）属 R4 编排，R-G 交付回滚机制与状态机；② 灰度发布框架（canary 分桶 + 基线对比）为 R4/R5 复用 R-G 三段式时的前端，R-G 交付提案仓库 + 人审门 + 回滚底座；③ 告警由 `SafetyGateResult.reason()` 承载，经调用方 H5 导出（R-G 组件保持 P1 纯领域零日志框架） |

