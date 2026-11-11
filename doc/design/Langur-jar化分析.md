# Langur 通用能力 jar 化分析（不改变现有 DDD 架构）

> 分析对象：`/Users/artisan/Library/Mobile Documents/com~apple~CloudDocs/Documents/Agent/Framework/Langur`
> 目标：把框架通用能力封装成固定的几个 jar 供第三方项目依赖，**且不改变现有 DDD 六边形架构**。
> 依据：Langur 实际代码（模块/端口/依赖图）+ 前文四家 Harness SDK 的 11 维通用能力模型 + AgentScope-Java V2 的工程组织参照（§4）。

---

## 0. 结论速览

1. **Langur 的 DDD 六边形已经是"可 jar 化"的正确形态**——端口（SPI）在 `langur-common`/`langur-domain`，适配器在 `langur-infrastructure`，依赖方向已单向（P2）。jar 化本质是 **Maven 模块粒度的"发布切面"**，不是架构改动。
2. **唯一的结构失衡点**：`langur-infrastructure` 179 个类挤在一个 jar 里，混了 8 类能力（LLM / 向量 / 工具 / 持久化 / 决策 / 安全 / 可观测 / RSI 装配）。jar 化的主要动作 = **把 infra 按能力拆成若干适配器 jar**。
3. **不改架构的红线**（必须逐条守住）：包名不变、Port 接口不动、依赖方向不变、domain 纯度（P1）不变、零外部依赖不变。
4. 拆分只触碰三样东西：**Maven `<module>` 边界、`langur-bom`、ArchUnit 守卫的模块清单**（改测试不改架构），外加**移动 1 个类**（`SecretResolver`，见 §6，是拆包暴露出的既有横向依赖）。
5. **同哲学参照验证**：AgentScope-Java V2（同 Harness 哲学）的"推理内核 + 适配器矩阵 + BOM + SDK/Service 分离"与本文目标 jar 集高度同构（§4），佐证切分合理；增量借鉴 workspace/session/memory 三分、middleware/hook 双拦截、examples/distribution/starter 交付配套。同生态的 BlueWhale（Skylark 记忆/上下文基座）已落地适配器独立模块 + 共享索引库 + 无 Spring 薄 SDK，并以"机制/策略分离 + 渐进式 Maven 化"为划分哲学（§4.5–4.6），是更贴近的活参照。DeepSeek Harness（TS monorepo）以"能力 seam 三角色 + host/client 分离 + 多接入 SDK"补充细粒度解耦与客户端工程参照（§4.7–4.8）。

---

## 1. Langur 现状盘点

### 1.1 六模块与文件量

| 模块 | 职责 | 框架通用? | 文件数 |
|---|---|---|---|
| `langur-common` | SPI 契约、`CacheBackend`/`DistributedLock` 接口、异常 | ✅ 100% | 16 |
| `langur-domain` | 六组件领域模型 + 端口 + RSI 引擎 + 决策平面值对象 | ✅ 100% | 149 |
| `langur-application` | 用例编排（`AgentApplicationService` 等） | ✅ 大部分 | 15 |
| `langur-api` | REST 控制器 + 治理 | ⚠️ 应用入口 | 24 |
| `langur-infrastructure` | LLM/向量/工具/持久化/决策/安全/可观测/RSI 实现 | ✅ 框架默认实现（**过载**） | 179 |
| `langur-start` | Spring 装配 + `application.yml` | ⚠️ 应用启动 | 10 |

### 1.2 已存在的端口（= 天然 jar 边界锚点）

- `domain/port`：`LLMPort`、`DecisionPort`、`ToolProvider`、`FallbackStrategy`、`DefaultFallbackStrategy`
- `common/spi`：`BusinessExecutorSPI`、`ContextEnricherSPI`、`DecisionEngineSPI`、`OutputPostProcessorSPI`、`PromptTemplateSPI`、`SecurityPolicySPI`、`ToolProviderSPI`、`SpiToolSpec`、`BizContext`
- `common/cache`：`CacheBackend`、`DistributedLock`
- `domain/repository`：`AgentRepository`、`AgentRunTaskRepository`、`PlanRepository`
- `domain/harness/spi`：`BizCodeRouter`

**这些接口就是 jar 边界**：每个适配器 jar = 一个端口（或一组同能力端口）的默认实现集合。接口不动，jar 就能自由替换（P3/P5/P10）。

---

## 2. 11 维通用能力 → Langur 端口 → 目标 jar 映射

| # | 通用能力 | Langur 端口 | 现有实现（infra） | 目标 jar |
|---|---|---|---|---|
| 1 | Agent Loop / 执行范式 | `execution`/`workflow` 引擎（ReAct/PlanAndExecute/Workflow/Hybrid，`LoopDetector`/`TerminationGate`） | `infra/harness/execution`(2)、`workflow`(2) | **core**（领域引擎不动） |
| 2 | Provider / 模型网关 | `LLMPort` | `infra/llm`（`LlmGateway`/`LLMRouter`/4 适配器/`ProviderCircuitBreaker`） | `langur-llm` |
| 3 | 工具 + MCP | `ToolProvider`、`ToolProviderSPI`、`Tool` | `infra/tool` + `infra/harness/tool`(71，含 code/mcp/rest/skill/validator) + `infra/skill` | `langur-tools` |
| 4 | 上下文 / 记忆 | `VectorStore`/`EmbeddingPort`/`RerankPort`（`context/vector`） | `infra/harness/context`(16) + `context/vector`(12) | `langur-vector` |
| 5 | 会话 / 状态 | `CacheBackend`、`DistributedLock`、仓储 | `infra/cache` + `persistence/jpa` + `harness/state`(1) | `langur-persistence` |
| 6 | 权限 / 审批 | `SecurityPolicySPI`、`ApprovalPort` | `infra/harness/security`(4：`ApprovalService`/`PromptInjectionGuardHook`/`ContentReviewOutputHook`/`InMemoryApprovalStore`) | `langur-security` |
| 7 | 沙箱 / 隔离 | （Langur 以"四层校验链"替代 OS 沙箱） | `infra/harness/tool/validator`（权限→风险→白名单→限流） | `langur-tools`（校验链并入） |
| 8 | Hooks / 生命周期 | `LifecycleHook`/`HookPoint` | `domain/harness/lifecycle`（契约）+ infra 实现 | **core**（契约）+ 实现随能力散落 |
| 9 | 可观测 / 成本 | `EvaluationPort`、`AlertEvaluator`、`TokenUsage` | `infra/harness/evaluation`(7，alert/audit/tracing + Micrometer) | `langur-observability` |
| 10 | 多智能体 | `multiagent` 消息总线 + 委派 | `domain/harness/multiagent` + `infra/harness/multiagent`(1) | **core**（领域） |
| 11 | 结构化输出 / 配置 | `PromptTemplateSPI`、`OutputPostProcessorSPI` | 分散（common SPI + start 配置） | 契约在 core，配置在 start |
| — | **决策平面**（Langur 独有） | `DecisionPort` | `infra/harness/decision`(10：Jev 后端 + 装饰链) | `langur-decision` |
| — | **RSI**（Langur 独有） | RSI 端口 + 引擎 | 引擎在 `domain/harness/rsi`；装配件在 `infra/harness/rsi`(11) | 引擎在 **core**，装配件 `langur-rsi` |

---

## 3. 目标 jar 集（固定 3 核心 + 8 适配器 + 1 BOM）

沿用 `org.skylark.langur` groupId 与 `langur-*` 命名。**框架 jar（可发布）** 与 **应用 jar（本仓库独有）** 分开：

```
langur-bom                          # 版本锁定（新增，只含 dependencyManagement）

── 框架核心（维持现状，几乎不动，可被第三方直接依赖）─────────────
langur-common                        # SPI 契约 + Cache/Lock 接口 + 异常
langur-domain                        # 领域模型 + 端口 + 六组件引擎 + RSI 引擎 + 决策平面值对象（零外部依赖，P1）
langur-application                   # 用例编排

── 框架适配器（从 langur-infrastructure 按"一个可替换能力 = 一个 jar"拆出）──
langur-llm                           # infra/llm 全部
langur-tools                         # infra/tool + harness/tool(validator 在内) + infra/skill
langur-vector                        # infra/harness/context + context/vector
langur-persistence                   # infra/cache + persistence + harness/state
langur-security                      # infra/harness/security
langur-observability                 # infra/harness/evaluation(alert/audit/tracing)
langur-decision                      # infra/harness/decision
langur-rsi                           # infra/harness/rsi（装配件；引擎留在 domain）

── 应用（不发布）──────────────────────────────────────────────
langur-api                           # REST 入口（保持）
langur-start                         # 组装点 + yml（保持，改为依赖上述适配器 jar）
```

**裁剪说明**：8 个适配器 jar 可按团队偏好合并（如 `langur-security`+`langur-observability` → `langur-ops`）。合并的原则是"两个能力是否总是一起替换"——LLM/向量/决策这三个最常单独替换，**建议不合并**。

**依赖方向（拆后仍严格单向，P2 不变）**：

```
langur-start ──▶ langur-api ──▶ langur-application ──▶ langur-domain ◀── 适配器 jar（llm/vector/persistence/…）
                                        │                    ▲
                                        └──▶ langur-common ◀─┘
```

适配器 jar 之间允许向下依赖（`vector → llm`、`rsi → llm + tools`），但**任何适配器不得被 core 依赖**。

---

## 4. 外部参照与借鉴：AgentScope-Java、BlueWhale 与 DeepSeek Harness

> §0–§3 的依据是"四家 Harness SDK 的 11 维能力模型"（**能力维度**）。本节补两个**工程组织维度**的参照：① AgentScope-Java V2——同用 "Harness" 术语、同主张"LLM 推理内核 + 工程化基础设施"，是 JVM 上最接近的外部组织参照系（§4.1–4.4）；② BlueWhale——Skylark 生态内与 Langur 配套的记忆/上下文基座，已落地本文目标形态，是更贴近的**同生态活参照**（§4.5–4.6）；③ DeepSeek Harness——TypeScript pnpm monorepo（Cordis 插件框架），以"能力 seam 三角色 + host/client 分离 + 多接入 SDK"提供细粒度解耦与客户端工程参照（§4.7–4.8）。来源见 §4.9。

### 4.1 AgentScope-Java 模块组织（事实）

顶层 7 个 Maven 模块：

| 模块 | 职责 |
|---|---|
| `agentscope-core` | ReAct 推理内核 + 原语（单模块，内部按包划分） |
| `agentscope-harness` | 工程化层：`HarnessAgent` 入口，叠加 workspace/记忆/沙箱/会话/子 agent |
| `agentscope-service` | 托管平台 / API（与可嵌入 SDK 分离） |
| `agentscope-extensions` | 可插拔适配器矩阵（~22 子模块，全部可选） |
| `agentscope-dependencies-bom` | 独立 BOM 版本管理 |
| `agentscope-distribution` | 打包 / 分发 |
| `agentscope-examples` | 示例工程 |

`core` 内部按包划分（`agent`/`model`/`tool`/`memory`/`message`/`formatter`/`hook`/`middleware`/`event`/`state`/`session`/`workspace`/`rag`/`skill`/`tracing`/`observation`/`permission`/`credential`/`interruption`/`shutdown`…）；`extensions` 平铺 `model`/`mem`/`judge`/`rag`/`sandbox`/`skills`/`channel`/`controlplane`/`scheduler`/`protocol` + 存储接入（`jdbc`/`mysql`/`postgresql`/`mongodb`/`redis`/`oss`/`cos`/`nacos`/`higress`）+ `spring-boot-starters`。

**组织逻辑**：`core`（最小推理内核）→ `harness`（用 Middleware/HITL/Workspace 把工程能力"叠加"到 ReAct）→ `service`（托管）；所有外部依赖倒置进 `extensions`。

### 4.2 与 Langur 目标 jar 集对照（验证 §3 切分）

| AgentScope-Java | Langur 对应（§3） | 结论 |
|---|---|---|
| `core`（推理内核） | `langur-common` + `langur-domain` + `langur-application` | 同构：内核零适配器依赖 |
| `extensions-model` | `langur-llm` | 同构 |
| `extensions-mem` / `rag` | `langur-vector` | 同构 |
| `extensions-jdbc`/`mysql`/…/`redis` | `langur-persistence` | Langur 合一，AgentScope 按存储引擎细分 |
| `extensions-sandbox` | （H15.5 规划，暂并入 `langur-tools` 校验链） | Langur 尚缺独立沙箱 jar |
| `extensions-judge` | `langur-decision` + `langur-observability` | 同构（判定/评测外置） |
| `dependencies-bom` | `langur-bom` | 同构（§5.6 已规划） |
| `service` | `langur-api` + `langur-start` | 同构（框架/应用分离） |
| `examples` / `distribution` | （Langur 缺，见 §4.3e） | 增量借鉴 |

结论：§3 的"3 核心 + 8 适配器 + 1 BOM"与 AgentScope 的组织**高度同构**，佐证切分合理；差异主要在"工程化能力的显式分层"与"交付配套模块"。

### 4.3 可借鉴的增量设计（本文档此前未覆盖）

a. **推理内核 vs 工程化叠加层的视角**：AgentScope 把最小 ReAct 内核（`core`）与工程化能力（`harness`：workspace/session/sandbox/middleware/HITL）显式分层，后者以组合"叠加"到内核。Langur 的 `domain` 是单一 core——jar 化时建议在命名/文档上区分"内核 jar"与"工程化适配器 jar"，并把 H15.4（workspace）/H15.5（sandbox）作为**可插拔工程层**落地，而非塞进执行循环（与 §2 第 7 行"四层校验链替代 OS 沙箱"的取舍互补：真沙箱到来时独立成 jar）。

b. **workspace / session / memory 三分**：AgentScope `core` 把三者分成独立包。细化 §2 第 4/5 行：workspace=产物/文件系统（H15.4）、session=会话事件日志（已落地的 `SessionEvent`，H15.1/3）、memory=长期记忆（向量）。三分比混在单一 context 更清晰——jar 化时 `langur-vector` 专注 memory，session 状态归 `langur-persistence`，workspace 待 H15.4 独立。

c. **Middleware 与 Hook 双拦截**：AgentScope 同时有 `middleware`（横切拦截链）与 `hook`（生命周期点）。Langur 已有 L 组件 `LifecycleHookEngine`（生命周期）+ `GovernanceFilter`（横切）——建议在文档中显式区分这两类接缝：横切关注点（鉴权/限流/重试/TraceId）走 middleware 链，生命周期点走 hook，二者正交（呼应 P4）。

d. **HITL / interruption / permission 一等公民**：AgentScope 把人工介入、中断、权限独立成包。Langur 现有 `CriticalApprovalValidator` + `TaskState` 续跑已有雏形；H15.5 自验证 + 审批可参考其显式建模，把"中断-恢复-审批"提升为一等工程能力而非散落校验器。

e. **SDK/Service 分离 + examples/distribution + starter 聚合**：§3 已分框架 jar/应用 jar（≈ SDK/Service）。增量：① 补 `langur-examples` 模块，把 §7 P5 的"最小示例工程"固化为仓库内可运行示例；② 补 `langur-distribution` 统一打包分发；③ 借鉴 `spring-boot-starters`，提供一个 `langur-starter` 聚合 jar（curated 依赖一组常用适配器），降低第三方接入成本（对应脚手架设计的 `harness-starter`）。

### 4.4 不照搬的部分（守住 Langur 红线）

AgentScope 按"能力分层"（core/harness/service）组织，Langur 按"DDD 六边形"组织——**不照搬其分层骨架**，只借工程组织手法。Langur 的 domain 纯度（P1）+ ArchUnit 编译期/运行期守卫 + 端口倒置是 AgentScope 未同等强调的，保持不动（§0 红线）。借鉴止于"模块粒度与交付配套"，不触碰领域逻辑。

### 4.5 同生态参照：BlueWhale 模块组织与划分哲学

BlueWhale 是 Skylark 生态中与 Langur 配套的记忆/上下文基座（Langur=Agent 框架消费端，BlueWhale=记忆/知识供给基座），已落地本文 §3 设想的目标形态，是比 AgentScope 更贴近的**活参照**。

8 个 Maven 模块（依赖方向 `starter → interfaces → app → core`，适配器只依赖 core）：

| 模块 | 职责 |
|---|---|
| `bluewhale-core` | domain：ContextUnit 聚合·生命周期·治理·端口（零框架依赖） |
| `bluewhale-app` | application：用例编排 |
| `bluewhale-interfaces` | REST + MCP + 管理端 API |
| `bluewhale-starter` | Spring Boot 装配入口（Server 默认 + embedded） |
| `bluewhale-store-sqlite` | 持久化适配器（ContextUnit/事件/episode + SQLite schema） |
| `bluewhale-index-adapter` | 索引适配器（内存/SQLite，委托共享 `skylark-index`） |
| `bluewhale-extract` | Embedding/抽取/蒸馏/重排适配器 |
| `bluewhale-client` | 不依赖 Spring 的远程薄 SDK |

划分哲学（`CONTEXT-FRAMEWORK-DESIGN.md` §3/§13）：

1. **机制与策略分离**：BlueWhale=机制（加工/存储/生命周期/`resolve()` 供给），Langur=策略（预算/渲染/注入/hook）；二者经 `ContextBundle` 契约耦合，**严禁双写**。
2. **渐进式 Maven 化**：地基阶段先"单模块 + 包内分层 + ArchUnit 层守卫"，仅把需跨项目共享的 `skylark-index` 真拆出，其余后续再拆——明确"避免过早 Maven 化"。
3. **共享库下沉**：Langur 已验证的向量类型（`VectorStore`/`EmbeddingPort`/4 实现）下沉为 `skylark-index` 共享库，两项目复用，"勿重新发明"。
4. **Client/Server 双形态**：同一套 domain/app，两种 `@AutoConfiguration` 装配（Server 默认 + embedded 降级）；`bluewhale-client` 为无 Spring 薄 SDK，Agent runtime 与管理端共用。

### 4.6 BlueWhale 的增量借鉴（对 Langur jar 化的直接启示）

a. **适配器独立成 jar 已被同生态验证**：BlueWhale 的 `store`/`index`/`extract` 三适配器模块 = §3 拆分目标的活样本，佐证"一个可替换能力 = 一个 jar"在 Skylark 工程纪律下可行。

b. **渐进式拆分纪律（修正 §7 节奏）**：不必 P3 一次拆完 8 个适配器——先包内分层 + ArchUnit 守卫，按"是否需跨项目/跨进程共享"驱动拆分优先级。`langur-vector` 因 `skylark-index` 共享需求最先拆，与 §6"llm 因被依赖最多最先拆"互补：llm 按内部扇出先拆，vector 按外部共享先拆。

c. **`langur-vector` → `skylark-index` 共享库**：jar 化时直接把 `langur-vector` 设计为 Skylark 生态共享索引库（`org.skylark.index`），Langur 与 BlueWhale 共用、避免双写（BlueWhale §12 已定决策）。这是 Langur jar 化独有的**跨项目维度**，AgentScope 参照不含。

d. **机制/策略分离强化框架/应用 jar 边界**：§3"框架 jar（机制）vs 应用 jar（策略）"与 BlueWhale/Langur 的机制/策略分工同构——jar 化后第三方可只引机制 jar 自建策略，这正是 BlueWhale 作为独立基座服务多消费方的形态。

e. **无 Spring 薄 SDK（`langur-sdk`）**：借鉴 `bluewhale-client`，jar 化可补一个不依赖 Spring 的 `langur-sdk` 远程薄客户端（区别于 C2 的 React `langur-client` 前端），供非 Spring 消费方接入。

### 4.7 DeepSeek Harness 模块组织与划分哲学（TS monorepo 参照）

DeepSeek Harness 是 TypeScript pnpm monorepo（基于 Cordis 插件框架，`ctx.effect/on/waterfall` 注册服务），**非 Java/Maven**，但工程组织哲学可借鉴。npm scope `@deepseek-ai/dsh-*`。

三层 + 旁路：

```
vendor/      vendored 框架（cosmokit / schemastery）
packages/    能力包层：packages/<group>/<pkg>/，~50 组
apps/        产品装配层：cli（拥有 dsh bin）/ web —— "Product assemblies over the package tier"
native/      landlock-run（沙箱原生启动器）
python/      sdk / sdk-runtime（Python SDK + 运行时）
```

**客户端工程：host / client 两半**（Web GUI 的对称切分）：

| 半侧 | 包组 | 职责 |
|---|---|---|
| 宿主侧 | `packages/host/` | `apiproxy`（传输无关 API 网关）+ `webserver`（HTTP 路由）+ `frontend-static`（占回退席位的 SPA dist 服务器）+ `directory-picker`（seam + native/browse/auto 后端） |
| 浏览器侧 | `packages/client/` | `web`（shell 启动）+ `connection`（浏览器↔宿主 RPC）+ `ui-renderer`（slot→React）+ `runtime`（共享服务）+ `ui-slots`（slot 系统）+ ~30 个 `ui-*` 功能插件 |

二者仅经 RPC 契约耦合（`apiproxy` 传输无关，`client/connection` 提供 HTTP 载体）；`apps/web` 是 Vite 装配，`apps/cli` 主入口内嵌宿主服务器。**多接入形态**：`sdk/{protocol,client,server}`（进程外 JSON-RPC）、`acp`（Agent Client Protocol）、`python/sdk`、`cli`。

划分哲学：

1. **能力 seam 三角色**：每个能力拆 Service Definition / Service Provider / Consumer；铁律"扩展插件依赖 Service Definition，绝不依赖具体提供方"（`dsh-agent-loop` 可替换，UI/钩子/工具只用 `dsh-agent`）。
2. **能力系列化**：shell/terminal/fs/lsp/sandbox/code-runtime 等均为"seam + 本地实现 + 面向模型工具"三件套同组。
3. **产品装配与能力分离**：apps 只是组合包，能力全在 packages；`bundle/` 提供 `dsh --profile` 补丁层。
4. **UI slot 插件化**：`ui-slots` 定义注册/组合扩展点，`ui-renderer` 绑定 React，`ui-*` 插件挂 slot（类微前端）。
5. **依赖图 CI 门禁**：`gen-module-graph` 工具生成依赖图，CI 校验新鲜度。

### 4.8 DeepSeek Harness 的增量借鉴（对 Langur jar 化与 Client 的启示）

a. **能力 seam 三角色细化端口/适配器二分**：§5.2"端口不动"是 Definition/Provider 二分；DeepSeek 再分出 Consumer（面向模型工具/UI），且 Consumer 只依赖 Definition。jar 化时每个适配器 jar 内部可按三角色组织，把"面向模型的消费面"与"提供方实现"解耦。

b. **host/client 两半 + RPC 契约**：C2 的 `langur-client` 已有 `src/api` 契约层（方向一致）；可借鉴把后端流式接入显式建模为 host 半侧（API 网关 + 静态托管），前端为 client 半侧，二者仅经 RPC/SSE 契约耦合（呼应 C3 静态托管 + P3 接入层外置）。

c. **多接入 SDK 矩阵**：借鉴 `sdk`(JSON-RPC)/`acp`/`python`，jar 化除 `langur-sdk`（§4.6e 无 Spring 薄 SDK）外，可规划 MCP/ACP 接入面，让 Langur 服务非 Java 消费方（与 BlueWhale MCP server 同向）。

d. **UI slot 插件化**：C4 工作台 UI 的可演进方向——工具折叠卡/设置区/消息区做成 slot 扩展点（`ui-slots` + `ui-renderer` 模式），避免单体 `App.tsx`。

e. **依赖图 CI 门禁**：借鉴 `gen-module-graph` + 新鲜度门禁，jar 化拆包后把模块依赖图生成纳入 CI（与 §5.5 ArchUnit 互补：ArchUnit 守不变量，依赖图守可视化新鲜度）。

### 4.9 参考来源

AgentScope-Java 仓库（`github.com/agentscope-ai/agentscope-java`）：`README_zh.md`、`docs/v2/zh/intro.md`、`agentscope-core` 与 `agentscope-extensions` 目录树（2026-11 调研）。

BlueWhale（同生态 sibling，`Framework/BlueWhale`）：`README.md`、`CONTEXT-FRAMEWORK-DESIGN.md` §3/§12/§13、`pom.xml`（2026-11 调研）。

DeepSeek Harness（同生态 sibling，`Framework/deepseek-harness`）：`packages/README.zh.md`（层级结构表）、`packages/client/README.zh.md`、`packages/host/README.zh.md`、`pnpm-workspace.yaml`（2026-11 调研）。

---

## 5. 不改 DDD 架构的操作手法（6 步）

### 5.1 纯 Maven 模块拆分，不动包名/类名
把 `langur-infrastructure` 的目录按子包 `git mv` 到新模块，**`org.skylark.langur.infrastructure.xxx` 包名原样保留**。类的 FQN 不变 → 领域层、应用层**零改动**。这就是"打包切面"而非"架构切面"。

### 5.2 端口不动
所有 SPI/Port 接口仍留 `common`/`domain`。适配器 jar 编译期只依赖 `langur-core`（接口），维持 P2/P3。

### 5.3 装配靠 Spring 条件装配（已有先例，零新概念）
Langur 已经在用 `ObjectProvider<DecisionPort>`、`@ConditionalOnProperty`（`RsiConfiguration`）、`@ConditionalOnMissingBean`。每个适配器 jar 自带一个 `*Configuration`，用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 声明；**缺 jar = 缺 Bean = P10 降级自然成立**（与现有"决策平面缺失即降级"完全同构）。

### 5.4 `langur-start` 变成组装点
`langur-start` 从"依赖一个 infra"改为"依赖所需适配器 jar 列表"，yml 配置零改动。`langur-api` 只依赖 application，不直接碰适配器。

### 5.5 ArchUnit 守卫更新（改测试，不改架构）
`ArchitectureGuardTest` 当前守护"六模块无环"。拆包后模块数变多，只需**更新模块清单**，三条不变量原样保留：
① domain 纯度（零 Spring / 零 infrastructure import）→ 改为"零适配器 jar import"；
② 全模块依赖无环（新清单）；
③ common 无状态 Bean。

### 5.6 BOM 锁版本
新增 `langur-bom`（复用根 pom 的 `dependencyManagement`），第三方项目只引 bom + 所需适配器 jar，即可组装最小 agent。

---

## 6. 拆包前必须解决的接缝（实测横向依赖）

对 `langur-infrastructure` 做了交叉 import 扫描，发现两类必须先处理的接缝：

### 6.1 一个真实环：`llm ↔ tool`
- `infra/llm/ModelProviderFactory` → `infra/harness/tool/rest/SecretResolver`
- `infra/harness/tool/skill/DefaultSkillToolGateway` → `infra/llm`

`SecretResolver`（解析 `env:XXX`/`secret:XXX` 的 `api-key-ref`）本质是**凭据解析**，是横切工具，不该住在 `tool/rest` 下。**修法：把 `SecretResolver` 移到 `langur-common`（或 `langur-security`）**，环即解除。这是一次单类移动，是拆包暴露的既有问题，不属架构变更。

### 6.2 单向横向依赖（拆后转成 jar 间依赖即可，无需改代码）
- `llm` 被 5 处依赖：`context/vector/LlmEmbeddingPort`、`rsi/{ReflectionHook, LlmDistillationExtractor}`、`execution/LlmPlanner`、`tool/skill/DefaultSkillToolGateway` → **`langur-llm` 应最先拆出**，其余 jar 依赖它。
- `harness.tool` 被 6 处依赖：`llm/ModelProviderFactory`（环，见上）、`skill/TranslatePrdSkill`、`context/vector/{ES, Milvus, LlmEmbedding}`、`rsi/{SkillSynthesisCandidate, SynthesizedSkillRegistrar, SkillSynthesizer}`。
- `harness.context`、`harness.decision`、`harness.rsi`、`harness.security` 无外部被依赖 → 边界干净。

结论：依赖图天然形成 `llm ← tools/vector/rsi/decision` 的下游扇出，**没有其它隐藏环**，只有 `SecretResolver` 一处需要挪位。

---

## 7. 分阶段落地路线

| 阶段 | 动作 | 验收 |
|---|---|---|
| **P0 静态分析** | 用 jdeps/ArchUnit 生成 infra 内部完整依赖图，锁定全部横向依赖 | 依赖图无未知环 |
| **P1 移类** | `SecretResolver` 移至 `langur-common`（解除 llm↔tool 环） | 全量测试绿 |
| **P2 拆首个适配器** | 拆出 `langur-llm`（边界最清晰、被依赖最多） | `langur-start` 能跑、测试绿 |
| **P3 拆其余适配器** | 依次拆 `langur-vector`/`langur-persistence`/`langur-tools`/`langur-security`/`langur-observability`/`langur-decision`/`langur-rsi` | 每拆一个跑一次全量测试 |
| **P4 治理** | 新增 `langur-bom` + 更新 ArchUnit 模块清单 + 加 auto-configuration imports | ArchUnit 绿、`mvn clean package` 绿 |
| **P5 验证** | 建一个最小示例工程，只引 `langur-bom + langur-common + langur-domain + langur-llm + langur-tools`，跑通一个 ReAct agent | 证明"第三方最小依赖可运行" |

每个阶段都是**纯文件移动 + 配置**，不触碰任何领域逻辑。

> 借鉴 AgentScope 的交付配套（§4.3e）：P5 的最小示例可固化为 `langur-examples` 模块；P4 之后视需要补 `langur-distribution` 与 `langur-starter` 聚合 jar。
>
> 借鉴 BlueWhale 的渐进式 Maven 化（§4.6b）：P3 不必一次拆完，按共享需求驱动——`langur-vector` 因 `skylark-index` 跨项目共享优先拆，其余适配器可先保持包内分层 + ArchUnit 守卫，按需再 Maven 化。

---

## 8. 一句话总结

Langur 不需要"重构架构"——它的 DDD 六边形和端口体系已经是对的。jar 化 = **把过载的 `langur-infrastructure` 按"一个可替换能力 = 一个 jar"切开，端口与包名原封不动，靠 Spring 条件装配与 BOM 组装**。唯一真正的代码改动是挪 `SecretResolver` 一个类。拆完后，第三方项目可以只引 3 个核心 jar + 自选适配器 jar，就能在不动 Langur 领域层的前提下搭建自己的 agent 应用。同哲学的 AgentScope-Java（§4）验证了这一组织方向，并提示在"工程化能力显式分层 + 交付配套模块"上继续补强。同生态的 BlueWhale（§4.5–4.6）已先行验证适配器独立 jar 与共享索引库，其"机制/策略分离 + 渐进式 Maven 化"哲学提示拆分应由共享需求驱动、避免过早 Maven 化。DeepSeek Harness（§4.7–4.8）的能力 seam 三角色与 host/client 分离，则把解耦粒度推进到 Definition/Provider/Consumer 与客户端工程层面。
