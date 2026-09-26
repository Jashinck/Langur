# Agent-Harness 架构设计 v2.0 — 决策平面与 RSI 整合

> **文档性质**: 架构增量设计（Delta Design）——在 v1.0 通用能力底座之上，新增"决策平面"能力面，并将其与 RSI 递归自演进闭环整合
> **版本**: v2.0 | **日期**: 2026-09-26 | **承接**: [v1.0 — 通用能力底座](Agent-Harness架构设计-v1.0.md)（六组件确定性调度底座，继续有效，本文不重复）
> **核心公式**: `Agent = LLM生成能力(System-2) + 决策平面判定能力(System-1) + Harness确定性调度 + RSI可控自演进`
> **一句话立场**: **大模型写，决策平面判，Harness 管，RSI 进化。** 决策平面是 RSI 的运行期底座，RSI 是决策平面的离线优化器——二者正交、互不替代。
> **关联文档**: [v1.0 — 通用能力底座](Agent-Harness架构设计-v1.0.md)（本文在 v1.0 六组件底座之上新增"判定式决策平面 + 递归自演进"能力面）

---

## 一、背景

### 1.1 本版增量一览（相对 v1.0）

| 变更类型 | 说明 |
|----------|------|
| **新增能力面：决策平面** | 在"生成式 LLM"之外引入**判定式决策模型**（首个后端 = TypeSafe **Jev** / System One），承载路由/闸门/评分/分类等"判定"职责，快（~33ms–百毫秒级）、廉（输入 $0.042/M、**输出免费**）、**校准带置信度** |
| **复用既有 SPI** | 决策平面落地为 v1.0 §4.9 已预留的 **`DecisionEngineSPI`** 的首个具体实现 + domain 新端口 **`DecisionPort`**（与 `LLMPort`/`RerankPort`/`ApprovalPort` 同构） |
| **双系统决策模型** | 显式区分 **System-2 生成**（M4/M5/M6 产文本）与 **System-1 判定**（决策平面产类型化决策），并给出二者在执行模块的分工边界 |
| **执行模块集成** | 决策平面接入 **Workflow（阶段决策闸门/审批风险分级/产物验收）、Hybrid（执行器择优/升降级/层路由 advisory）、ReAct（完成判定/卡死检测）、Skill（DECISION 语义步骤）** |
| **RSI 整合** | 明确 Jev（H 类运行期底座）与 RSI（R 类离线元循环）**不冲突**；给出 **4 个耦合点**（R0 回放确定性 / R1 反思初筛 / R4 阈值·路由调优 / R-G 安全红线）与分阶段融合路径 |
| **新增原则 P12** | **判定/生成分离 + 判定可回放可降级 + 对安全闸门恒为 advisory（fail-closed）** |
| **新增决策 DD10–DD13** | Jev 后端选型（托管 vs 自部署）、置信阈值标定、录制粒度、`DecisionEngineSPI` 复用边界 |

**与 v1.0 的关系**：v1.0 仍是六组件 As-Built 权威基线；v2.0 只增量描述"决策平面"这一新能力面及其与 RSI 的整合，**不改动六组件正交拓扑（P4）**——决策平面是 E/T/C/L 消费的一个**端口**，不是第七个组件。

### 1.2 为什么现在需要决策平面（现状痛点）

v1.0 落地后，系统里所有"判定"动作都落在三类不理想的实现上：

| 判定点 | 现状实现 | 问题 |
|--------|----------|------|
| 层/范式路由（`DefaultLayerRouter`） | bizCode 查表 + 字符串标记（workflow/approval/compliance）+ 规划特征 | **脆弱**：依赖命名约定，语义模糊任务误判；学习型路由属 R4（RSI，未落地） |
| 输出合规审核（`OutputContentReviewer`） | 关键词/正则（涉密/资损/合规） | **召回有限**：换个说法就漏；无语义理解 |
| Skill 条件（`SkillExpressionResolver` CONDITION） | 安全最小集表达式（`${x.field}` + 6 运算符） | **只能判结构化字段**，无法判"这段 PRD 是否完整" |
| 审批触发（`CriticalApprovalValidator`） | CRITICAL 风险一律挂起人审 | **一刀切**：低风险也排队，长任务吞吐受限 |
| 完成/卡死判定（`TerminationGate`/`LoopDetector`） | 四维硬约束 + action/observation 指纹去重 | **只会数轮次/比指纹**，不懂"任务是否真的达成" |
| 反思自检（R1 规划） | 计划用 M5(REASONING) self-critique | **贵**：每次判定都烧一次大模型推理 |

**共性**：这些都需要一个**快、便宜、校准、可批量**的"判定器"，而不是生成器。决策平面（Jev）正是为此而生——它**不生成 token，只返回类型化判定 + 置信度**。

### 1.3 双系统决策模型（Kahneman 映射到 Harness）

```
                        ┌──────────────────────────────────────────────┐
   任务/上下文 ───────▶ │  System-1  决策平面（Jev / DecisionPort）      │  快·廉·校准·可批量
                        │  判定：路由 / 闸门 / 评分 / 分类 / 完成度        │  输出：choice|noul|score + confidence
                        └───────────────┬──────────────────────────────┘
                                        │ 高置信 → 程序化分流（run/skip/branch/approve/terminate）
                                        │ 低置信 → fail-closed（升级 / 人审 / 回退规则）
                                        ▼
                        ┌──────────────────────────────────────────────┐
                        │  System-2  生成式 LLM（M4/M5/M6 + LlmGateway） │  慢·贵·强表达
                        │  生成：草稿 / 改写 / 规划文本 / 工具调用参数     │  输出：自然语言 / 结构化生成
                        └──────────────────────────────────────────────┘

   原则：能用 System-1 判定的，绝不烧 System-2；System-2 产出的文本，再由 System-1 验收。
```

---

## 二、目标

### 2.1 定位与愿景：从"两支柱"到"三支柱 + 元循环"

| 支柱 | v1.0（通用能力底座） | **v2.0（本文）** |
|------|----------------------|------------------|
| 生成 | LLM 推理（M1–M6 + 网关） | **System-2 生成**（M4/M5/M6 产文本/规划/改写） |
| 调度 | Harness 确定性调度（六组件） | Harness 确定性调度（六组件，不变） |
| 判定 | —（散落在正则/规则/M5） | **System-1 决策平面**（Jev：路由/闸门/评分/分类）✅ 新增 |
| 演进 | RSI 可控自演进（蓝图） | RSI 元循环（**消费决策平面**，见 §4.5） |

### 2.2 决策平面的设计目标（四诉求 + 一条铁律）

| 诉求 | 含义 | 度量 |
|------|------|------|
| **快** | 判定远快于生成 | `decision_latency` ~33ms–百毫秒级，≪ 生成延迟 |
| **廉** | 不烧大模型推理 | 输入 $0.042/M、**输出免费**（Jev）；近零边际成本（自部署） |
| **校准** | 输出带置信度 | `confidence` + `distribution` 可标定、可调优、可回放 |
| **可批量** | 多问题一次请求 | "speculative fan-out"：state 只发一次 |
| **铁律（P12）** | 判定/生成分离，对安全闸门恒 advisory | 只收紧不放松、低置信 fail-closed |

### 2.3 核心公式

```
Agent = LLM生成能力(System-2) + 决策平面判定能力(System-1) + Harness确定性调度 + RSI可控自演进
```

- **System-2 生成**（LLM）：产文本、做规划、写工具参数——**负责"写"**。
- **System-1 判定**（Jev/DecisionPort）：做路由、闸门、评分、分类——**负责"判"**。
- **Harness 调度**（六组件）：权限、沙箱、审计、可观测——**负责"管"**。
- **RSI 演进**（元循环）：用自身数据优化阈值/Prompt/路由/工具——**负责"进化"**。

### 2.4 成熟度演进目标（D0 → D3）

| 级别 | 能力 | 说明 | 依赖 |
|------|------|------|------|
| **D0** | 规则判定（现状） | 正则/字符串/固定规则 + 昂贵 M5 判定 | — |
| **D1** | Jev advisory | `DecisionPort` 接入，仅非安全闸门做**建议** + 规则兜底，默认关、可录制 | J1–J3 |
| **D2** | Jev 闸门生效 | Workflow 决策闸门 / 审批分级(非CRITICAL) / 产物验收 / 执行器择优 上线，批量+缓存+录制 | J4–J10 |
| **D3** | RSI 调优 Jev | R4 经回放+灰度自动调阈值/prompt/路由（`DecisionEngineSPI` 热插拔） | R0 R-G R4 |

> **D2→D3 的跃迁 = 决策平面从"人工标定的判定器"进化为"自我优化的判定器"**，这正是 Jev 与 RSI 整合的终局价值，也是 P11/P12/R-G 必须全程在场的原因。当前 **D0–D3 全部达成**（见 §5.4 落地度总评）。

---

## 三、全局架构图

### 3.1 决策平面在六组件中的位置（横切端口，不破 P4 正交）

```
┌───────────────────────────────────────────────────────────────────────┐
│                    L — Lifecycle Hooks（决策平面可在钩子处触发判定）      │
├───────────────────────────────────────────────────────────────────────┤
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     │
│  │    C     │────▶│    E     │────▶│    T     │────▶│    S     │     │
│  │ Context  │     │Execution │     │   Tool    │     │  State    │     │
│  └────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘     │
│       │ 召回判定         │ 路由/闸门/完成   │ 审批分级         │ 录制判定  │
│       ▼                 ▼                 ▼                 ▼          │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │   决策平面 DecisionPort / DecisionEngineSPI（System-1 判定端口）   │ │  ← 新增（端口，非组件）
│  │   后端：Jev 托管 / Kev·Laya 自部署 / 规则兜底（条件装配，默认关）   │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│       │                 │                                             │
│  ┌────▼─────┐    ┌──────▼──────┐    ┌─────────────────────────────┐  │
│  │    V     │    │ 生成式 LLM   │    │  RSI 元循环（§3.2，消费决策平面）│  │
│  │ 决策指标  │    │ M4/M5/M6    │    │  Observe→…→Apply→Monitor     │  │
│  └──────────┘    └─────────────┘    └─────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
```

![Jev 决策平面](../share/images/langur-jev-decision-plane.png)

> 决策平面像 `LLMPort`/`RerankPort` 一样，是被 E/T/C/L/V **消费的端口**，经 `ObjectProvider<DecisionPort>` 松耦合注入；缺失即降级（P10）。它是**横切端口**，不占六组件正交位（P4 不破）。

### 3.2 RSI 六阶段元循环

```
        ┌────────────────────────────────────────────────────────────┐
        │                    RSI 递归自演进元循环                       │
        │                                                            │
        │   Observe ──▶ Evaluate ──▶ Propose ──▶ Validate ──▶ Apply   │
        │      ▲                                          │         │
        │      │              Monitor ◀───────────────────┘         │
        │      └─────────────────────────────────────────────────────┘
        │                                                            │
        │   每个产物都是"候选提案"：经回放验证 + 灰度 + 审批 + 可回滚   │
        └────────────────────────────────────────────────────────────┘
```

![RSI 递归自我改进](../share/images/langur-rsi-self-improvement.png)

| RSI 阶段 | 职责 | 对应任务 |
|----------|------|----------|
| **Observe** | 录制轨迹/判定/指标为观测信号 | R0（回放引擎的录制底座）、J2（决策录制） |
| **Evaluate** | 用判定/评分替代昂贵 M5 评估 | R0（回放基线对比）、R2（置信门） |
| **Propose** | M5 生成候选提案（廉价预判过滤噪声） | R3（技能合成）、R4（策略提案）、R5（工具提案） |
| **Validate** | 离线回放 + 基线对比 | R0（确定性反事实重放）、R-G（安全平面验证） |
| **Apply** | `DecisionEngineSPI`/SkillCatalog/ToolRegistry 热插拔 | R3（注册）、R4（阈值生效）、R5（工具注册） |
| **Monitor** | V 组件对比基线，劣化自动回滚 | R-G（回滚机制）、R4（rollbackIfDegraded） |

### 3.3 决策平面 × RSI 整合拓扑（底座 ↔ 元循环）

| | 决策平面（Jev） | RSI（R0–R5/R-G） |
|---|----------------|-------------------|
| 类别 | **H 类**：运行期能力接入 | **R 类**：离线/灰度自我改进 |
| 改什么 | **不改自身**——每请求做一次判定 | **改系统自身**——Prompt/Skill/路由/超参/工具 |
| 产物 | 类型化判定（瞬时，不落策略） | **候选提案**（经回放+灰度+审批才生效，P11） |
| 触发 P11？ | **否**（不是自改进） | 是（全程受 P11/R-G 约束） |
| 关系 | **RSI 的运行期底座** | **决策平面的离线优化器** |

> **结论**：Jev 与 RSI **正交、互补、无硬冲突**。Jev 让 RSI 更便宜（省 M5 调用）、更确定（判定可回放）；RSI 让 Jev 的阈值/prompt/路由持续进化。

---

## 四、核心模块详细设计

### 4.1 决策平面契约（domain，零外部依赖 P1）✅ J1 落地

新增 domain 端口 `DecisionPort`，与 `LLMPort`/`RerankPort` 同构；值对象纯 JDK：

```
DecisionPort
  DecisionResponse decide(DecisionRequest request)          // 单请求可含多问题（批量/投机扇出）

DecisionRequest   { String state;                           // 原始上下文（串/JSON）
                    String model;                           // 后端模型标识（可空→用配置默认）
                    List<DecisionQuestion> questions; }     // 一次多问，state 只发一次

DecisionQuestion  { String key;                             // 答案映射键
                    DecisionType type;                      // CHOICE | PROBABILITY | SCORE
                    String instructions;                    // 判定指令（显式自然语言）
                    Map<String,String> criteria; }          // CHOICE 的候选枚举（可选）

DecisionType      { CHOICE, PROBABILITY, SCORE }            // 对齐 Jev: choice / noul / score

DecisionResponse  { Map<String,DecisionAnswer> answers;
                    TokenUsage usage; }                     // 复用 H1 TokenUsage 计量

DecisionAnswer    { DecisionType type;
                    String  choice;                         // CHOICE：选中枚举
                    double  value;                          // PROBABILITY：0..1；SCORE：分档连续值
                    double  confidence;                     // 置信度（阈值分流依据）
                    Map<String,Double> distribution; }      // CHOICE 完整分布（可观测/调优用）
```

> **落地说明（J1，2026-09-25）**：`port/DecisionPort` + `harness/decision/{DecisionRequest, DecisionQuestion, DecisionType(CHOICE→choice / PROBABILITY→noul / SCORE→score), DecisionResponse, DecisionAnswer, DecisionThresholds(DD11 缺省 0.75/0.90/0.80/0.85)}` 均为纯 JDK record/enum（已断言无 Spring/Jackson import）；`DecisionResponse.usage` 复用 H1 `LLMPort.TokenUsage`。infra `TypeSafeDecisionAdapter`（WebClient REST、批量投机扇出 state 只发一次、后端异常/超时委派兜底不抛出）+ `RuleFallbackDecisionAdapter`（复用 H10 `OutputContentReviewer`，confidence 恒 0 → fail-closed）已落地；适配器不带 `@Component`，由 §4.3 `DecisionConfiguration` 条件装配。17 测全绿（domain 7 + infra 10，HttpServer 离线桩）。

> **后端无关**：契约只描述"类型化判定 + 置信度"，不绑定 Jev。托管 Jev、自部署 Kev/Laya、乃至规则兜底，都是 `DecisionPort` 的不同实现（P3 依赖倒置 / P5 开闭）。

### 4.2 首个后端：Jev（TypeSafe System One）

| 维度 | 托管 API（官方） | 自部署（社区平替，官方权重闭源） |
|------|------------------|----------------------------------|
| 端点 | `POST https://api.typesafe.ai/v1/systemone` | 本地端点（如 `openjev-sglang` / Kev 本地服务） |
| 鉴权 | `TYPESAFE_API_KEY`（`console.typesafe.ai` 生成） | 无/本地 |
| 模型 | `jev-latest`，生产**锁版本**（如 `jev-1.13.0`） | **Kev** 0.8B/4B/9B、**Laya** 421M（ModernBERT 编码器，~33ms）、**Nimble** 9B |
| 价格 | 输入 $0.042/M，**输出免费** | 自建算力，近零边际成本 |
| 请求/响应 | `{state, model, questions{noul\|choice\|score, instructions, criteria}}` → `answers{value/distribution, confidence, usage}` | Kev 号称**兼容 TypeSafe SDK**——仅换 base-url |
| SDK | **仅官方 Python**（无 Java/Spring） | 多为 Python/自有服务 |
| 批量 | "speculative fan-out"：多问题一次请求、state 只发一次 | 视实现 |

> **对 Java 栈的结论**：无官方 Java SDK，但它是**纯 REST/JSON**——用既有 `WebClient`（H6 SSE / H7 OAuth2 已在用）直接 POST 即可；API key 经 H7 `CompositeSecretResolver`/KMS 解析，**绝不明文**。另有 Jev MCP server，可经 H6 MCP 工具源零代码接入（但 MCP 给对话式语义，不如 REST 适配器贴合 `DecisionPort`）。

### 4.3 装饰器链（工程约束）✅ J2/J3 落地

| 装饰器 | 职责 | 对应原则/RSI |
|--------|------|--------------|
| `RecordingDecisionPort` ✅（J2，2026-09-26） | 把每次 `decide` 的 request/response **录制进轨迹快照**（与 LLM 录制同通道，复用 S 组件 `StateSnapshot` + `DecisionTrajectoryRecorder`）；并经 H5 上报决策维度指标 + 审计 checksum | **R0 回放确定性（DD5 扩展）**——否则反事实重放失真 |
| `ThresholdRouter` ✅（J3，2026-09-26） | `confidence ≥ 阈值` → 程序化分流；`< 阈值` → fail-closed（升级/人审/回退规则）；每次分流发 `decision_route_counts` | P10 降级 + P12 advisory |
| `CachingDecisionPort` ✅（J3，2026-09-26） | 按 `state` 哈希 + 问题签名缓存判定（复用 H4 `CacheBackend`，键 `langur:decision:<sha256>`） | 效率（多闸门不重复调用） |
| `DataResidencyDecisionPort` ✅（J3，2026-09-26） | 后端选择器（DD10/P12⑤）——命中 `sensitive-namespaces` 的 `state` 强制走 `LocalDecisionAdapter`；无 local 端点则 fail-closed 规则兜底，绝不发往第三方托管 | 数据驻留 |

```
DecisionPort（注入到 E/T/C/L 各处，均 ObjectProvider 可选）
  = RecordingDecisionPort            // 录制进快照（R0 前向兼容）
    └ CachingDecisionPort            // H4 CacheBackend 按 state 哈希缓存
      └ ThresholdRouter              // 置信阈值分流 + fail-closed
        └ TypeSafeDecisionAdapter    // langur.decision.backend=typesafe（托管 Jev）
        | LocalDecisionAdapter       // =local（自部署 Kev/Laya，敏感数据）
        | RuleFallbackDecisionAdapter// =off/不可用 → 回退现有规则/正则（P10）
```

> **落地说明（J3，2026-09-26）**：`DecisionConfiguration`（start，`@ConditionalOnProperty langur.decision.enabled=true`，默认关不产 Bean、行为与 v1.0 一致）按上图组装；`decisionPort` 标 `@Primary`（`ThresholdRouter` 亦暴露为 Bean 供 J4–J10 注入 `route()`），消除双 `DecisionPort` Bean 歧义。backend=typesafe 且配置了 `sensitive-namespaces` 时，最内层再包 `DataResidencyDecisionPort`（DD10）。J2/J3 共 26 测全绿，默认关/开启双冒烟 UP。

### 4.4 决策平面 × 执行模块集成（十插入点 ①–⑩）

> 总原则：**决策平面只改"判定"，不改"生成"**；所有插入点都带**规则/LLM 兜底**与**置信阈值**，默认关闭、灰度开启（P9/P10）。

#### 4.4.1 Workflow 模式（`WorkflowExecutionLoop` / `WorkflowStage`）✅ J4–J6

现状：Workflow 是**固定阶段序列**，只有 `requiresApproval` 闸门，阶段间无条件分支。决策平面补三处：

| 插入点 | 机制 | 类型 | 收益 | 红线 |
|--------|------|------|------|------|
| **① 阶段决策闸门** | `WorkflowStage` 增可选 `decisionGate`（一个 `DecisionQuestion` + 阈值 + 路由动作：run-next / skip / branch-to-stage / abort / require-approval）。阶段产出后由决策平面判定走向 | `choice`/`noul` | **跳过不必要的昂贵 LLM 阶段**（提效）；确定性（结果是带置信度、被记录的判定，非 LLM 自由发挥） | 低置信 → 走默认顺序（兜底） |
| **② 审批风险分级** | 对**非 CRITICAL** 审批闸门，用 `score` 风险三分流：低风险→策略内自动放行快路；中→人审；高/低置信→人审或中断 | `score` | 长任务（合同审查特批项）**吞吐提升** | ⚠️ **CRITICAL 恒人审**：Jev 只产出"风险摘要 + 建议"加速人工，**绝不自动放行**（只能收紧不能放松，P11/P12/R-G） |
| **③ 产物验收闸门** | `task.addArtifact(...)` 前用 `score` 判完整/合规，低于阈值→有界重试该阶段或打标 | `score` | 多产物（审查报告/特批项/PRD）**质量提升**（提准） | 重试受步数/Token 闸门约束 |

> **落地说明（J4–J6，2026-09-26）**：domain 新增 `StageDecisionGate`（闸门定义，配置驱动 P9：`langur.workflow.definitions[].stages[].decision-gate.{key,type,instructions,criteria,threshold,branch-to}`，经 `WorkflowDefinitionRegistrar` 装载）与 `GateRoute.of(answer, threshold)`（domain 侧分流，P2：不依赖 infra `ThresholdRouter`，语义对齐）；`WorkflowExecutionLoop` 织入可选 `DecisionPort`（start `HarnessConfiguration.attachDecisionPlane`，缺省不织入=行为与 v1.0 一致）。①：skip 跳过下一阶段并计入 completed（恢复续跑确定性）、branch 带防环预算跳转、abort 中断、require-approval 建 `workflow-gate:<stageId>` 人审单挂起（恢复时 DENIED/PENDING 不得越过，只收紧）；低置信/异常/缺失 → 默认顺序（P12③）。②：`WorkflowStage.critical` 标记（配置 `critical: true`），CRITICAL 恒人审且**决策平面不被调用**；非 CRITICAL 安全分与置信均 ≥ approval-auto(0.90) → 自动放行并留 APPROVED 审批单（decisionBy=decision-plane 可溯源）。③：`Artifact` 增 `accepted/reviewNote`，低于 artifact-accept(0.80) → 至多 1 次有界重试（受终止闸门约束）→ 仍不达标打标不阻断。三处分流均发 `decision_route_counts`。14 测全绿（domain `WorkflowExecutionLoopDecisionGateTest` 12 + infra registrar 2）。

#### 4.4.2 Hybrid 模式（Workflow → Plan → ReAct 三层分权）✅ J7–J8

| 插入点 | 机制 | 类型 | 收益 |
|--------|------|------|------|
| **④ 每阶段执行器择优** | hybrid 中每个 Workflow 阶段委派 Plan 或 ReAct；用 `choice` 选"够用的最便宜执行器"——简单抽取→ReAct，复杂多步→Plan | `choice` | 提效（不为简单阶段烧规划） |
| **⑤ 升级/降级触发** | ReAct 阶段用 `noul`("是否已完成/是否在重复") 做廉价完成判定与卡死检测，接 `TerminationGate`/`LoopDetector`；该升 Plan 就升、该终止就终止 | `noul` | 提效 + 提准（接既有 replan-on-failure） |
| **⑥ 层路由（advisory）** | `DefaultLayerRouter` 增可选 `DecisionPort`：`choice` 选 paradigm，**低置信回退现有规则** | `choice` | 路由更准 ⚠️ **与 R4 学习型路由重叠（RSI）——v2.0 阶段仅 advisory、非自主自改进**；自动调优留待 R4 |

> **落地说明（J7–J8，2026-09-26）**：④⑤ 落在 `WorkflowExecutionLoop.executeStage`——HYBRID 范式 + 决策平面在场时，④ 经 `choice("stage-executor")`（阈值 `routing` 0.75）从 `attachStageExecutors` 注入的执行器池 {REACT, PLAN_AND_EXECUTE} 中择优，低置信/缺失/非 HYBRID 回退构造期 `stageParadigm`（v1.0 固定中层 Plan，P10）；⑤ 仅在走了廉价 ReAct 路径时经**一次批量** `noul("stage-complete"/"stage-stuck")`（值+置信均 ≥ `completion` 0.85 才采信，阶段边界即检查点）判：已达成且阶段成功→提前成功终止跳过剩余阶段、重复卡死→升级 ReAct→Plan 重跑（无升级路径则终止）、低置信→PROCEED 回退既有 `LoopDetector`/`TerminationGate`（P12③）。⑥ 落在 `DefaultLayerRouter.route`——先规则后 advisory，`attachDecisionPlane(DecisionPort,阈值)` 经 DecisionPort 接缝注入（后端由 J3 装配、适配 `DecisionEngineSPI`/Jev，**不硬编进路由类**，C1）；高置信 `choice("layer-route")` 覆盖规则，低置信/不可识别/异常回退规则。**合规守卫**：规则判 WORKFLOW/HYBRID 时 advisory 绝不降级到无审批的 REACT/PLAN（P12②只收紧）；**advisory 无状态、不自修改路由规则**。14 测全绿（domain `WorkflowExecutionLoopHybridTest` 6 + `DefaultLayerRouterDecisionTest` 8）+ 双冒烟 UP。

#### 4.4.3 ReAct 模式（`ReActExecutionLoop`）✅ J9

| 插入点 | 机制 | 红线 |
|--------|------|------|
| **⑦ 完成判定** | `noul`("给定轨迹，任务是否已达成") 作为 `TerminationGate` 的**附加信号**（不替代四维硬约束） | 网络判定**只在检查点**调用（如每 N 轮），避免每轮往返反噬延迟 |
| **⑧ 卡死检测** | `choice`("是否在重复同一无效动作") 补 `LoopDetector` 指纹去重之外的语义判定 | 低置信 → 以既有指纹检测为准 |

> **落地说明（J9，2026-09-26）**：⑦⑧ 落在 `ReActExecutionLoop` 主循环——`LoopDetector` 指纹判定之后、快照写入之前插入**检查点语义判定**：`isDecisionCheckpoint(round)` 仅当 `round % N == 0`（缺省 N=3，配置 `langur.decision.react-checkpoint-rounds`）才发起**单次批量** `decide`（`task-complete` noul + `trajectory-stuck` choice，红线：绝不逐轮往返）。⑦ 值+置信均 ≥ `completion`（0.85）→ 合成最终答案提前**成功**终止（`react_early_complete`，**附加信号，不替代四维硬约束**）；⑧ choice 高置信判 `stuck` → `LOOP_DETECTED`（`react_stuck`）；低置信/缺失/异常 → PROCEED（`react_proceed`）回退既有指纹 + 闸门（P10/P12③）。`attachDecisionPlane(DecisionPort,阈值,检查点间隔)` 经 DecisionPort 接缝注入（P2 不破），`HarnessConfiguration.reActExecutionLoop` 以 `ObjectProvider` 织入；决策平面缺失（缺省 enabled=false）→ 不织入、行为同 v1.0（P12①）。domain 4 测全绿（`ReActExecutionLoopDecisionTest`，无 Mockito）+ 双冒烟 UP。

#### 4.4.4 Skill 子系统（`SkillExecutor` / `StepType`）✅ J10

| 插入点 | 机制 |
|--------|------|
| **⑨ DECISION 步骤** | 新增 `StepType.DECISION`：由 `DecisionPort` 求值的**语义条件**（"这段代码上下文是否足以转译 PRD？"），补 `SkillExpressionResolver` 只能判结构化字段的短板；与既有 `CONDITION`（确定性表达式）并存，各取所长 |
| **⑩ 转译 PRD 质量门** | `TranslatePrdSkill` 的 `draft` 步骤后加 DECISION 验收（`score` 完整性），低分触发有界重draft |

> **落地说明（J10，2026-09-26）**：⑨ `StepType` 增 `DECISION`，`SkillStep` 增 `decisionKey/decisionType(score\|noul)/decisionInstructions/decisionThreshold`（复用 `arguments` 承载被判定材料、`onTrue/onFalse` 承载分支）+ 工厂 `decisionScore/decisionNoul`；`SkillExecutor` 把 DECISION 与 CONDITION 同列**分支步**（`nextIndexOf` 统一解析跳转目标，沿用 `MAX_STEPS=1000` 硬上限防环）。`evaluateDecision` 经注入的 `DecisionPort`（`DefaultSkillToolGateway` 以 `ObjectProvider` 织入，infra 依赖 domain 端口合 P2）构造 score/noul 问题，值+置信均 ≥ 阈值 → `onTrue` 否则 `onFalse`（低置信 fail-closed，P12③）；**兜底**：`DecisionPort` 缺失/后端异常/无答案 → P10 降级（有 `condition` 表达式则按其求值＝"降级为 CONDITION"，否则默认放行 `onTrue`＝等价 v1.0 无质量门），**绝不中断技能**。⑩ `TranslatePrdSkill` 在 `draft` 后加 `quality-gate`（`decisionScore` 阈值 0.8，`onTrue=END` 直接产出、`onFalse=redraft`），`redraft` 为**有界一次**重写。infra 10 测全绿（`SkillExecutorDecisionTest`）+ 同步 `TranslatePrdSkillTest`（2→4 步）。

#### 4.4.5 与五层防御 / 安全平面的关系（强制红线）

1. **决策平面对安全闸门恒为 advisory**：可**收紧**（升级人审/中断），**绝不放松**（不得自动放行 CRITICAL、不得绕过四层校验链）。
2. **不可覆盖 `SecurityPolicySPI` 与四层校验链**（与 P11/R-G 一致）。`PromptInjectionDetector`/`OutputContentReviewer` 命中即阻断的语义**不变**；决策平面至多作为**补充语义审核信号**（如 `noul`("是否含注入意图")）叠加，命中阈值**只增不减**拦截。
3. **低置信 fail-closed**：安全/审批/合规相关判定，置信不足一律走最保守分支（人审/中断/回退规则）。
4. **数据驻留**：`state` 可能含合同正文/代码上下文等敏感数据 → 敏感场景**强制自部署后端**（Kev/Laya 本地），禁止发往第三方云（见 §4.9、DD10）。

### 4.5 RSI 六阶段元循环落地（R0–R5 / R-G）

> **定位**：RSI 是决策平面的**离线优化器**（R 类），全程受 P11（产物默认候选）+ R-G（安全平面）+ R0（回放验证）约束；**回放通过 ≠ 生效**（候选仅提案，生效待灰度 + 高危人审）。缺省 `langur.rsi.enabled=false` 不装配，行为等价 v1.0。

#### 4.5.1 R0 回放验证引擎（反事实重放）✅ 2026-09-26

domain 纯 JDK 新增 `harness/rsi` 包：`ReplayEngine`（无状态、无副作用、**不持有 `DecisionPort`**——结构性排除实时判定调用，兑现 C2"回放走录制而非联网"）在 `Trajectory`（按轮 `TrajectoryStep` 成本 + 录制 `RecordedDecision`，含 `DecisionAnswer`/`ThresholdCategory`/`baselineRoute`）上做**确定性反事实重放**：以候选 `ReplayCandidate`（阈值覆盖 / 按 key 强制路由）经域内 `ReplayRoute.fromAnswer`（镜像 infra `ThresholdRouter.route`，遵 P2 不 import infra）重算分流，与基线对比产出 `BaselineComparison`（IMPROVED/NEUTRAL/DEGRADED + deltas + rejected）。**诚实成本模型**：候选只能移除成本（SKIP/TERMINATE），不展开未录制轮次，故生成级候选（PROMPT/SKILL/PARAMS）离线复现基线 → NEUTRAL。**P11/P12 红线编码进裁定**：放松审批闸门（APPROVAL_AUTO 保守→非保守）/ 丢失成功 / 抬高成本 → DEGRADED 拒绝（只收紧不放松）。infra `InMemoryTrajectoryRepository` + `RsiProperties`；start `RsiConfiguration`（`@ConditionalOnProperty` 无 matchIfMissing → 默认 back-off）。14 测全绿（domain `ReplayEngineTest` 9 无 Mockito + infra 3 + start 2）。

#### 4.5.2 R1 反思自检（Reflexion Hook）✅ 2026-09-26

infra `ReflectionHook implements LifecycleHook` 挂 **BEFORE_OUTPUT**（改写型，`order()=5` 先于 H10 内容审核的 10——反思改写先于安全审核，改写后答案仍被 H10 复查，不能绕过安全层 P11/P12），两级反思兑现 C3：① Jev 廉价初筛 `score("answer-quality")`，**质量+置信双达阈（0.85）→ 跳过 M5**；② 仅低质/低置信升级 M5 自我批评（`LlmGateway.complete(ModelRole.REASONING)`）改写 → `MODIFY`，无改动/失败 → `CONTINUE`。**P10 全线降级**：DecisionPort 缺失/异常、M5 失败一律放行原始答案；钩子只 MODIFY 绝不 ABORT（阻断交安全闸门）。配置驱动（P9）：`langur.rsi.reflection.enabled` 默认 false，与总开关 AND 生效。10 测全绿（infra `ReflectionHookTest`，匿名桩无 Mockito）。**注**：绑定点为 BEFORE_OUTPUT 而非 AFTER_INFERENCE——ReAct 循环丢弃 AFTER_INFERENCE 的 MODIFY 载荷，仅 BEFORE_OUTPUT 的 MODIFY 被 `applyBeforeOutput` 应用。

#### 4.5.3 R2 记忆自蒸馏（轨迹→L4 知识）✅ 2026-09-26

domain `MemoryDistiller`（纯 JDK）四段流水线：① **Jev 置信门**——轨迹录制判定置信均值 < 0.85 整条不入蒸馏（低置信轨迹不污染 L4）；② **M5/M6 提炼**经 `DistillationExtractor` 端口（`TemplateDistillationExtractor` 确定性模板兜底 / infra `LlmDistillationExtractor` 大模型归纳，P3/P5）；③ **语义去重**——写前对目标命名空间 recall top-1，cosine ≥ 0.95 判 DUPLICATE 拒绝；④ **落 L4**——`VectorMemoryService.remember`，namespace 隔离（缺省 `rsi-distilled`，与业务 `knowledge` 隔离），metadata 携 `confidence` 供召回降权。产物仍是候选提案语义，不直接改执行策略（P11）。17 测全绿（domain 9 无 Mockito + infra 5 + start 3）。

#### 4.5.4 R3 技能自合成（轨迹→Skill）✅ 2026-09-26

infra `SkillSynthesizer`（确定性模板归纳，离线零网络）从高频成功轨迹归纳候选 `SkillSynthesisCandidate`：动作序列连续去重 → `TOOL_CALL` 步骤，`task-complete` 录制判定 → **J10 `DECISION` 完成门**（noul 0.85，把"临场语义判定"固化为编排节点）；诚实成本模型 `llmRounds` ≤ `baselineLlmRounds`。`SkillSynthesisValidator`（离线确定性护栏）三道审查：空候选 / **越权工具**（引用 ∉ 白名单，P11 红线）/ **劣化**（只降本）→ 拒绝。`SynthesizedSkillRegistrar` **版本化 + 一键回滚**：注册进 `SkillCatalog` + `ToolRegistry`（source=SKILL 四层校验元数据），回滚恢复上一版本、栈空注销（`SkillCatalog` 补 `unregister`）。候选默认不生效（P11），须经 R0 回放 + R-G 灰度 + 高危人审。16 测全绿（infra 14 + start 2）。

#### 4.5.5 R4 策略自优化（调 Jev 阈值/prompt/路由 → D3）✅ 2026-09-26

domain `StrategyOptimizer` 三能力：① **回放择优（贪婪 bandit）**——候选集离线重放，只保留 `IMPROVED`（劣化/无差异淘汰，宁缺毋滥），选 Token 最低；② **阈值调优（C1）**——从候选阈值组选出回放最优者，产出 `THRESHOLD` 提案（决策平面从"人工标定"进化为"自我优化"的 D3 跃迁，经 J8 预留接缝而非改路由源码）；③ **劣化自动回滚**——应用后提案对新轨迹重放，劣化即经 R-G 一键回滚。配置 `langur.rsi.optimization.*` 默认关。9 测全绿（domain 8 无 Mockito + start 1）。

#### 4.5.6 R5 工具自扩展（缺口检测→自动接入）✅ 2026-09-26

domain `ToolGapDetector`（确定性能力缺口检测）+ `ToolExtensionCandidate` + `ToolExtensionValidator`（SSRF 内网/环回纯 JDK 拒绝 + 高危强制人审）+ infra `ToolExtensionRegistrar`（放行即注册进 `ToolRegistry`，source=MCP/REST_API，可注销）。未审批工具不生效、越权/内网候选被拒绝（P11/P12）。配置 `langur.rsi.extension.*` 默认关。13 测全绿（domain 8 无 Mockito + infra 4 + start 1）。

#### 4.5.7 R-G RSI 安全平面（护栏，P0，统辖决策平面）✅ 2026-09-26

domain `RsiSafetyPlane` 提案-验证-应用三段式 + `RsiProposal`（checksum 审计链）+ `RsiProposalRepository`（DD6）：红线编码进状态机——① **权限隔离**（`security.policy`/`validation.` 前缀提交即拒绝，不可自改安全层）；② **只收紧不放松**（放松审批闸门在验证阶段被 R0 DEGRADED 裁定拒绝）；③ **高危人审**（THRESHOLD/ROUTE 或 `critical` 目标须 `humanApproved=true`）；④ **递归深度上限**（3）；⑤ **变更频率限流**（60s 窗口 ≤10）；⑥ **未经验证不得生效**。infra `InMemoryRsiProposalRepository`；配置 `langur.rsi.governance.*` 默认关。16 测全绿（domain 11 无 Mockito + infra 3 + start 2）。**注**：灰度发布前端与劣化自动回滚闭环属 R4 编排，R-G 交付回滚机制 + 状态机底座。

### 4.6 决策平面 × RSI 四耦合点（融合的关键，必须刻意设计）

| # | 耦合点 | 关系 | 融合做法 | 落点 |
|---|--------|------|----------|------|
| **C1** | **路由面 ↔ R4** | R4 要离线调 `LayerRouter` 规则 / `DecisionEngineSPI` 策略 | Jev 放进 **`DecisionEngineSPI` 接缝**（不硬编进 `DefaultLayerRouter`）；R4 可经回放调其**阈值/prompt/路由** | §4.4.2⑥ / R4 |
| **C2** | **R0 回放确定性 ↔ Jev** | R0/DD5 要求确定性重放；Jev 是外部模型 | **`RecordingDecisionPort` 把 Jev 调用录制进轨迹快照并可回放**（同 LLM 录制）；托管锁版本，自部署固定权重确定性更佳 | §4.3 / R0 |
| **C3** | **R1 反思 ↔ Jev** | R1 用 M5 self-critique（贵） | **Jev 当廉价初筛**：先判"是否完整/重复/违规"，**只有低质/低置信才升级 M5 反思或改写** | R1 |
| **C4** | **R-G 安全红线 ↔ Jev** | R-G：RSI 不可改安全策略层 | Jev **同受约束**：安全/审批低置信 fail-closed，只收紧不放松；**Jev 阈值/prompt 的变更本身是 RSI 提案**，须经 R-G 灰度+回滚 | §4.4.5 / R-G |

> **分阶段融合路径**（全部已落地）：
> - **Phase 0**（H 类，不碰 RSI）：`DecisionPort` + Jev 适配器（默认关、规则兜底、调用可录制），接非安全闸门（审批风险分级(非CRITICAL) / 产物验收 / 反思初筛）——纯能力接入，不触发 P11。**= J1–J10，达 D1/D2** ✅
> - **Phase 1**（RSI 解锁）：R0 `ReplayEngine` 录制+回放（C2）、R1 `ReflectionHook` 廉价初筛（C3）、R2 蒸馏纳入 Jev 置信信号。**= R0/R1/R2** ✅
> - **Phase 2**（RSI 枢纽与进阶）：R3 `SkillSynthesizer` 合成 Jev 支撑的 DECISION 步骤、R4 经回放+灰度调 Jev 阈值/prompt/路由（C1）、R-G 统辖（C4）、R5 工具自扩展。**= R3/R4/R5/R-G** ✅

### 4.7 架构落地（DDD 映射，遵循 P1/P2/P9/P10）

#### 4.7.1 模块与包

| 模块 | 新增 | 依赖约束 |
|------|------|----------|
| **langur-domain** | `port/DecisionPort`；`harness/decision/{DecisionRequest, DecisionQuestion, DecisionType, DecisionResponse, DecisionAnswer, DecisionThresholds}`（纯值对象）；`harness/rsi/{ReplayEngine, Trajectory, TrajectoryStep, RecordedDecision, ReplayRoute, ThresholdCategory, ReplayCandidate, CandidateKind, ReplayMetrics, BaselineComparison, TrajectoryRepository(port)}`（R0）；`harness/rsi/{MemoryDistiller, DistillationExtractor(port), TemplateDistillationExtractor, DistilledMemory, DistillKind, DistillationResult}`（R2）；`harness/rsi/{RsiSafetyPlane, RsiProposal, RsiProposalStatus, RsiProposalRepository(port), SafetyGateResult}`（R-G）；`harness/rsi/StrategyOptimizer`（R4）；`harness/rsi/{ToolGapDetector, ToolExtensionCandidate, ToolExtensionVerdict, ToolExtensionValidator}`（R5）；`harness/multiagent/{AgentMessage, AgentMessageBus(port), AgentDispatcher}`（H12） | 仅 common+lombok（P1）✅ |
| **langur-infrastructure** | `harness/decision/{TypeSafeDecisionAdapter(WebClient), LocalDecisionAdapter(Kev/Laya), RuleFallbackDecisionAdapter}`；`{RecordingDecisionPort, CachingDecisionPort, ThresholdRouter, DataResidencyDecisionPort}`；`DecisionProperties`；`harness/rsi/{InMemoryTrajectoryRepository, RsiProperties}`（R0）；`harness/rsi/ReflectionHook`（R1）；`harness/rsi/LlmDistillationExtractor`（R2）；`harness/rsi/{SkillSynthesizer, SkillSynthesisCandidate, SkillSynthesisVerdict, SkillSynthesisValidator, SynthesizedSkillRegistrar}`（R3）；`harness/rsi/InMemoryRsiProposalRepository`（R-G）；`harness/rsi/ToolExtensionRegistrar`（R5）；`harness/multiagent/InMemoryAgentMessageBus`（H12） | 实现 domain 端口（P3）✅ |
| **langur-start** | `DecisionConfiguration`：经 `ObjectProvider` 组装"录制→缓存→阈值→后端→兜底"装饰链；默认关；`RsiConfiguration`：装配 `ReplayEngine`/`memoryDistiller`/`skillSynthesizer`/`rsiSafetyPlane`/`strategyOptimizer`/`toolExtensionRegistrar`（各 `@ConditionalOnProperty` 默认关）；`ArchitectureGuardTest`（ArchUnit 守护 domain 纯度/common 无状态 Bean/分层无环） | 装配 ✅ |
| **langur-common** | （可选）`spi/DecisionEngineSPI` 若尚未独立成形，在此定型契约 | 零 Spring |

#### 4.7.2 配置（`langur.decision.*` / `langur.rsi.*`，P9）

```yaml
langur:
  decision:
    enabled: false                 # 总开关，默认关（P10）
    backend: typesafe              # typesafe | local | off
    model: jev-latest              # 生产建议锁版本，如 jev-1.13.0（R0 确定性）
    base-url: https://api.typesafe.ai/v1/systemone   # local 时指向自部署端点
    api-key-ref: env:TYPESAFE_API_KEY   # 经 CompositeSecretResolver/KMS，绝不明文
    timeout-millis: 800            # 判定须快；超时即回退规则
    batch: true                    # 投机扇出：多问题一次请求
    cache: { enabled: true, ttl-seconds: 300 }
    record: true                   # 录制进轨迹快照（R0 前向兼容，强烈建议常开）
    thresholds:                    # 各插入点置信阈值（可被 R4 调优）
      routing: 0.75                # 层路由 advisory（低于→回退规则）
      approval-auto: 0.90          # 非CRITICAL 审批自动放行（低于→人审）
      artifact-accept: 0.80        # 产物验收（低于→有界重试/打标）
      completion: 0.85             # ReAct 完成判定
    data-residency:
      sensitive-namespaces: [legal-contracts, code]   # 命中→强制 local 后端
  rsi:
    enabled: false                 # RSI 总开关，默认关（P11 暂停态）
    reflection: { enabled: false }     # R1
    distillation: { enabled: false }   # R2
    synthesis: { enabled: false }      # R3
    optimization: { enabled: false }   # R4
    extension: { enabled: false }      # R5
    governance: { enabled: false }     # R-G
```

> **实现约定**：① `sensitive-namespaces` **缺省为空**（不启用驻留路由，避免 `code` 等子串误伤），命中但缺 `local-base-url` 时 fail-closed 规则兜底，绝不发往第三方。② `api-key-ref` 解析失败降级空串（后端拒绝 → 规则兜底，即 fail-closed），绝不明文/落日志（P12⑥）。

### 4.8 可观测与确定性

#### 4.8.1 指标（接 H5，新增"决策维度"）✅ J2/J3 已落地

| 指标 | 含义 | 用途 |
|------|------|------|
| `decision_latency` | 判定往返延迟分布 | SLO（判定须远快于生成） |
| `decision_confidence` | 各插入点置信分布 | 阈值标定 / R4 调优输入 |
| `decision_fallback_rate` | 回退规则/超时占比 | 后端健康度 |
| `decision_route_counts` | 按阈值分流计数（run/skip/branch/approve/abort） | 行为审计 |
| `decision_cost` | 输入 token 计量（复用 H1 `TokenUsage`） | 成本核算 |

> 五维决策指标经 `RecordingDecisionPort` → H5 `MicrometerEvaluationService` 落 `MeterRegistry`（新增 `MetricDimension.DECISION`，指标名 `langur.harness.decision_*`，`/actuator/prometheus` 可暴露）；`decision_route_counts` 由 `ThresholdRouter.route()` 补齐。

#### 4.8.2 确定性（R0 前提，C2）

- **录制**：`record: true` 时，每次 `decide` 的 request+response 落轨迹快照（与 LLM 录制同通道）。
- **回放**：R0 `ReplayEngine` 重放时**优先用录制的判定**，不重新联网 → 反事实重放确定。
- **版本锁定**：托管锁 `jev-1.13.0`（`jev-latest` 会漂移）；自部署固定权重（Laya 编码器式确定性最佳）。
- **阈值即策略**：阈值变更会改变分流 → 阈值是**可回放、需版本化**的策略面（R4 调优对象、R-G 回滚对象）。

### 4.9 安全与合规

| 关注点 | 措施 |
|--------|------|
| **密钥** | `api-key-ref` 经 H7 `CompositeSecretResolver`（env/prop/kms）解析，**不落日志/明文**（复用 `Credential` 不生成 toString 的防泄露约定） |
| **SSRF** | `base-url` 若可配置为远端，经 `SsrfGuard` 校验（自部署内网端点走白名单） |
| **数据驻留** | 敏感 `state`（合同/代码）命中 `sensitive-namespaces` → **强制 local 后端**，禁止出网（DD10） |
| **fail-closed** | 安全/审批/合规判定低置信或超时 → 最保守分支（人审/中断/回退规则），**绝不因判定缺失而放松** |
| **不可越权** | 决策平面**不得**修改/绕过 `SecurityPolicySPI` 与四层校验链（P11/P12/R-G 红线） |
| **审计** | 判定（含置信/分布）写入审计链（checksum），可溯源"为何走了这条分支" |

### 4.10 设计原则增补（P12）

| # | 原则 | 约束力 | 说明 |
|---|------|--------|------|
| P1–P11 | （见 v1.0 §4.11） | — | 不变 |
| **P12** | **判定/生成分离 + 判定可回放可降级 + 对安全闸门恒 advisory** | **强制** | 判定走决策平面（快/廉/校准/可批量/可回放），生成走 LLM；决策平面缺失或低置信一律降级规则/LLM（P10 协同）；对安全/审批/合规闸门**只能收紧不能放松**，不得覆盖校验链（P11 协同） |

### 4.11 待决策项（DD10–DD13）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| DD10 | Jev 后端选型 | 托管 API / 自部署 Laya(421M) / 自部署 Kev(0.8B) / 混合 | **混合**：非敏感走托管（省事），敏感命名空间强制自部署（数据驻留） |
| DD11 | 置信阈值标定 | 人工经验值 / 离线用历史轨迹标定 / R4 自动调 | 先人工经验值（D1/D2），R4 就绪后自动调（D3） |
| DD12 | 录制粒度 | 全量录制 / 仅闸门录制 / 采样 | **全量录制**（R0 确定性前提，成本低） |
| DD13 | `DecisionEngineSPI` 复用边界 | 决策平面直接实现该 SPI / 新端口 + SPI 适配 | domain 新端口 `DecisionPort`，infra 适配到既有 `DecisionEngineSPI`（不破坏 v1.0 SPI 契约） |

> DD5（回放确定性）、DD6（提案存储）、DD8（自治边界）承接自 v1.0 演进规划，对决策平面同样适用（C2/C4）。

---

## 五、价值收益

### 5.1 工程价值（快·廉·校准·可批量）

| 维度 | 收益 |
|------|------|
| **降本** | 能用 System-1 判定的绝不烧 System-2——路由/闸门/评分/分类从"每次烧一次 M5 推理"变为"~33ms 的判定调用"，且 Jev **输出免费**；R1 反思廉价初筛省掉大部分 M5 自我批评调用 |
| **提效** | 判定远快于生成，可批量（投机扇出 state 只发一次）；Workflow 阶段决策闸门跳过不必要的昂贵 LLM 阶段；Hybrid 执行器择优"不为简单阶段烧规划" |
| **提准** | 语义判定补关键词/正则/指纹的短板——层路由更准、产物验收提升质量、完成/卡死判定懂"任务是否真的达成" |
| **确定性** | 判定带置信度、可录制、可回放、可审计——反事实重放不失真，阈值即版本化策略 |
| **可演进** | `DecisionEngineSPI` 接缝 + 装饰链使后端可插拔（托管 Jev ↔ 自部署 Kev/Laya ↔ 规则兜底），零代码扩后端 |

### 5.2 业务价值

| 场景 | 收益 |
|------|------|
| **长任务（合同审查特批项）** | 非 CRITICAL 审批风险分级 → 低风险自动放行快路，吞吐提升；**CRITICAL 恒人审**，0 漏审 |
| **多产物（审查报告/PRD）** | 产物验收闸门 → 合格率提升，不合格有界重试/打标不阻断 |
| **重复性任务** | R3 技能自合成 → 高频成功轨迹固化为 Skill（含 DECISION 完成门），LLM 轮次与成本下降 |
| **路由误判** | 层路由 advisory → 语义模糊任务不再依赖命名约定，高置信覆盖规则 |

### 5.3 演进价值（RSI 自我改进闭环）

RSI 让 Agent 系统用**自身运行产生的执行数据**持续改进自身——Prompt、技能、工具集、路由策略与超参，且随改进能力提升而增强"改进能力"本身（递归）。**Langur 的差异化**：RSI 运行在 Harness 的确定性调度与五层防御之上，每个产物都是"候选提案"，经 **R0 回放验证 → R-G 安全平面 → 灰度 → 高危人审 → 可回滚审计链**才生效——**自我改进 ≠ 自我失控**。

- **R0** 反事实回放 = RSI 的安全前提（不联网、确定性、只收紧不放松）。
- **R1–R5** = 从"反思"到"工具自扩展"的渐进演进（L1–L5）。
- **R-G** = 统辖一切自改进的安全平面（权限隔离 / 只收紧 / 人审 / 深度·频率限流 / 未验证不生效）。
- **决策平面 × RSI** = 底座 ↔ 元循环：Jev 让 RSI 更便宜更确定，RSI 让 Jev 阈值/prompt/路由持续进化（D2→D3）。

### 5.4 落地度总评

| 里程碑 | 任务 | 状态 |
|--------|------|------|
| **N1 决策平面地基** | J1–J3（契约 / Jev 后端 / 录制·缓存·阈值·驻留装饰链） | ✅ 达成熟度 **D1（Jev advisory）** |
| **N2 执行集成** | J4–J10（十插入点 ①–⑩：Workflow ①②③ / Hybrid ④⑤⑥ / ReAct ⑦⑧ / Skill ⑨⑩） | ✅ 达成熟度 **D2（Jev 闸门生效）** |
| **N3 RSI 起步** | R0（回放）+ R1（反思）+ R2（蒸馏） | ✅ Phase 1 闭环 |
| **N4 RSI 枢纽** | R3（技能自合成） | ✅ Phase 2 首项 |
| **N5 RSI 进阶** | R-G（安全平面）+ R4（策略自优化）+ R5（工具自扩展）+ H11（依赖治理）+ H12（多 Agent 协作） | ✅ 达成熟度 **D3（RSI 调优 Jev）底座** |

> **全部落地（2026-09-26）**：domain 207 + infra 360 + start 47 测全绿，`mvn clean test` BUILD SUCCESS；决策平面与 RSI 各组件**默认关闭**（`langur.decision.enabled=false` / `langur.rsi.enabled=false`），不装配时行为等价 v1.0（P10/P12①）。**剩余增量**（编排层）：M5 语义化归纳、canary 灰度前端、在线劣化监控闭环、离线消费调度、Spring Event/RocketMQ 总线——均为 R\* 组件的编排接入项，组件与底座已就绪。

---

> **附：术语对照**
> - **决策平面 / Decision Plane**：System-1 判定能力面（本文新增），契约 = `DecisionPort`，首个后端 = Jev。
> - **Jev / System One**：TypeSafe AI 的决策模型，返回 choice/noul/score + confidence，不生成 token。
> - **生成式 LLM / System-2**：M4/M5/M6，产文本/规划/改写。
> - **RSI**：递归自演进元循环（R0–R5/R-G），消费决策平面、优化其阈值/prompt/路由。
