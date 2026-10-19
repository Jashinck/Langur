# Langur 通用能力 jar 化分析（不改变现有 DDD 架构）

> 分析对象：`/Users/artisan/Library/Mobile Documents/com~apple~CloudDocs/Documents/Agent/Framework/Langur`
> 目标：把框架通用能力封装成固定的几个 jar 供第三方项目依赖，**且不改变现有 DDD 六边形架构**。
> 依据：Langur 实际代码（模块/端口/依赖图）+ 前文四家 Harness SDK 的 11 维通用能力模型。

---

## 0. 结论速览

1. **Langur 的 DDD 六边形已经是"可 jar 化"的正确形态**——端口（SPI）在 `langur-common`/`langur-domain`，适配器在 `langur-infrastructure`，依赖方向已单向（P2）。jar 化本质是 **Maven 模块粒度的"发布切面"**，不是架构改动。
2. **唯一的结构失衡点**：`langur-infrastructure` 179 个类挤在一个 jar 里，混了 8 类能力（LLM / 向量 / 工具 / 持久化 / 决策 / 安全 / 可观测 / RSI 装配）。jar 化的主要动作 = **把 infra 按能力拆成若干适配器 jar**。
3. **不改架构的红线**（必须逐条守住）：包名不变、Port 接口不动、依赖方向不变、domain 纯度（P1）不变、零外部依赖不变。
4. 拆分只触碰三样东西：**Maven `<module>` 边界、`langur-bom`、ArchUnit 守卫的模块清单**（改测试不改架构），外加**移动 1 个类**（`SecretResolver`，见 §6，是拆包暴露出的既有横向依赖）。

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

## 4. 不改 DDD 架构的操作手法（6 步）

### 4.1 纯 Maven 模块拆分，不动包名/类名
把 `langur-infrastructure` 的目录按子包 `git mv` 到新模块，**`org.skylark.langur.infrastructure.xxx` 包名原样保留**。类的 FQN 不变 → 领域层、应用层**零改动**。这就是"打包切面"而非"架构切面"。

### 4.2 端口不动
所有 SPI/Port 接口仍留 `common`/`domain`。适配器 jar 编译期只依赖 `langur-core`（接口），维持 P2/P3。

### 4.3 装配靠 Spring 条件装配（已有先例，零新概念）
Langur 已经在用 `ObjectProvider<DecisionPort>`、`@ConditionalOnProperty`（`RsiConfiguration`）、`@ConditionalOnMissingBean`。每个适配器 jar 自带一个 `*Configuration`，用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 声明；**缺 jar = 缺 Bean = P10 降级自然成立**（与现有"决策平面缺失即降级"完全同构）。

### 4.4 `langur-start` 变成组装点
`langur-start` 从"依赖一个 infra"改为"依赖所需适配器 jar 列表"，yml 配置零改动。`langur-api` 只依赖 application，不直接碰适配器。

### 4.5 ArchUnit 守卫更新（改测试，不改架构）
`ArchitectureGuardTest` 当前守护"六模块无环"。拆包后模块数变多，只需**更新模块清单**，三条不变量原样保留：
① domain 纯度（零 Spring / 零 infrastructure import）→ 改为"零适配器 jar import"；
② 全模块依赖无环（新清单）；
③ common 无状态 Bean。

### 4.6 BOM 锁版本
新增 `langur-bom`（复用根 pom 的 `dependencyManagement`），第三方项目只引 bom + 所需适配器 jar，即可组装最小 agent。

---

## 5. 拆包前必须解决的接缝（实测横向依赖）

对 `langur-infrastructure` 做了交叉 import 扫描，发现两类必须先处理的接缝：

### 5.1 一个真实环：`llm ↔ tool`
- `infra/llm/ModelProviderFactory` → `infra/harness/tool/rest/SecretResolver`
- `infra/harness/tool/skill/DefaultSkillToolGateway` → `infra/llm`

`SecretResolver`（解析 `env:XXX`/`secret:XXX` 的 `api-key-ref`）本质是**凭据解析**，是横切工具，不该住在 `tool/rest` 下。**修法：把 `SecretResolver` 移到 `langur-common`（或 `langur-security`）**，环即解除。这是一次单类移动，是拆包暴露的既有问题，不属架构变更。

### 5.2 单向横向依赖（拆后转成 jar 间依赖即可，无需改代码）
- `llm` 被 5 处依赖：`context/vector/LlmEmbeddingPort`、`rsi/{ReflectionHook, LlmDistillationExtractor}`、`execution/LlmPlanner`、`tool/skill/DefaultSkillToolGateway` → **`langur-llm` 应最先拆出**，其余 jar 依赖它。
- `harness.tool` 被 6 处依赖：`llm/ModelProviderFactory`（环，见上）、`skill/TranslatePrdSkill`、`context/vector/{ES, Milvus, LlmEmbedding}`、`rsi/{SkillSynthesisCandidate, SynthesizedSkillRegistrar, SkillSynthesizer}`。
- `harness.context`、`harness.decision`、`harness.rsi`、`harness.security` 无外部被依赖 → 边界干净。

结论：依赖图天然形成 `llm ← tools/vector/rsi/decision` 的下游扇出，**没有其它隐藏环**，只有 `SecretResolver` 一处需要挪位。

---

## 6. 分阶段落地路线

| 阶段 | 动作 | 验收 |
|---|---|---|
| **P0 静态分析** | 用 jdeps/ArchUnit 生成 infra 内部完整依赖图，锁定全部横向依赖 | 依赖图无未知环 |
| **P1 移类** | `SecretResolver` 移至 `langur-common`（解除 llm↔tool 环） | 全量测试绿 |
| **P2 拆首个适配器** | 拆出 `langur-llm`（边界最清晰、被依赖最多） | `langur-start` 能跑、测试绿 |
| **P3 拆其余适配器** | 依次拆 `langur-vector`/`langur-persistence`/`langur-tools`/`langur-security`/`langur-observability`/`langur-decision`/`langur-rsi` | 每拆一个跑一次全量测试 |
| **P4 治理** | 新增 `langur-bom` + 更新 ArchUnit 模块清单 + 加 auto-configuration imports | ArchUnit 绿、`mvn clean package` 绿 |
| **P5 验证** | 建一个最小示例工程，只引 `langur-bom + langur-common + langur-domain + langur-llm + langur-tools`，跑通一个 ReAct agent | 证明"第三方最小依赖可运行" |

每个阶段都是**纯文件移动 + 配置**，不触碰任何领域逻辑。

---

## 7. 一句话总结

Langur 不需要"重构架构"——它的 DDD 六边形和端口体系已经是对的。jar 化 = **把过载的 `langur-infrastructure` 按"一个可替换能力 = 一个 jar"切开，端口与包名原封不动，靠 Spring 条件装配与 BOM 组装**。唯一真正的代码改动是挪 `SecretResolver` 一个类。拆完后，第三方项目可以只引 3 个核心 jar + 自选适配器 jar，就能在不动 Langur 领域层的前提下搭建自己的 agent 应用。
