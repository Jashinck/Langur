# Langur 脚手架化架构规范

> 本文档是 Langur Agent-Harness 框架 **jar 化 + 脚手架交付** 的权威规范。
> 适用范围：框架维护者（拆包/发布）与业务侧开发者（用脚手架建 agent 应用）。
> 前置调研：[Agentic-Harness-脚手架设计方案](Agentic-Harness-脚手架设计方案.md)（四家 SDK 能力对比）与 [Langur-jar化分析](Langur-jar化分析.md)（现有代码盘点）。

---

## 1. 目标与约束

**目标**：把 Langur 的框架通用能力萃取为固定的若干 jar，并提供脚手架供业务侧快速搭建 agent 应用。

**不可违背的约束（红线）**：
1. **不改变现有 DDD 六边形架构**——只做"打包切面"，不做"架构切面"。
2. 包名（FQN）不变、端口（Port/SPI）接口不变、依赖方向不变。
3. `langur-domain` 纯度不变（零 Spring / 零 infrastructure import，P1）。
4. 端口接口继续驻留在 `langur-common` / `langur-domain`，适配器只反向依赖接口（P3）。

---

## 2. 现状基线

### 2.1 现有 6 Maven 模块

| 模块 | 职责 | 框架通用? | 文件数 |
|---|---|---|---|
| `langur-common` | SPI 契约、`CacheBackend`/`DistributedLock` 接口、异常 | ✅ | 16 |
| `langur-domain` | 六组件领域模型 + 端口 + RSI 引擎 + 决策平面值对象 | ✅ | 149 |
| `langur-application` | 用例编排（`AgentApplicationService` 等） | ✅ | 15 |
| `langur-api` | REST 控制器 + 治理 | ⚠️ 应用入口 | 24 |
| `langur-infrastructure` | LLM/向量/工具/持久化/决策/安全/可观测/RSI 实现 | ✅（**过载**） | 179 |
| `langur-start` | Spring 装配 + `application.yml` | ⚠️ 应用启动 | 10 |

依赖方向（P2 单向无环）：`start → api → application → domain ← infrastructure`；`domain` 仅依赖 `common`。

### 2.2 端口清单（= jar 边界锚点，冻结不动）

- `domain/port`：`LLMPort`、`DecisionPort`、`ToolProvider`、`FallbackStrategy`
- `common/spi`：`BusinessExecutorSPI`、`ContextEnricherSPI`、`DecisionEngineSPI`、`OutputPostProcessorSPI`、`PromptTemplateSPI`、`SecurityPolicySPI`、`ToolProviderSPI`、`SpiToolSpec`、`BizContext`
- `common/cache`：`CacheBackend`、`DistributedLock`
- `domain/repository`：`AgentRepository`、`AgentRunTaskRepository`、`PlanRepository`
- `domain/harness/spi`：`BizCodeRouter`

### 2.3 领域内部组织（六组件正交 + 决策平面 + RSI）

- **六组件**：`execution`（执行范式）/ `tool`（工具体系）/ `context`（上下文）/ `state`（状态）/ `lifecycle`（生命周期）/ `evaluation`（评估）
- **决策平面**：`DecisionPort`（横切端口，非第七组件，P4）
- **RSI**：`rsi` 包（回放/反思/蒸馏/合成/治理，默认关）

### 2.4 设计原则（P1–P12）

| 原则 | 内容 | 守护 |
|---|---|---|
| P1 | 领域核心不可侵犯（domain 零外部依赖） | ArchUnit |
| P2 | 严格单向依赖 | ArchUnit |
| P3 | 依赖倒置（实现依赖接口） | — |
| P4 | 六组件正交（决策平面是横切端口） | ArchUnit |
| P5 | SPI 开闭（新增实现不改既有） | — |
| P10 | 降级兜底（外部依赖失败主链路不中断） | — |
| P12 | 判定/生成分离，对安全闸门恒 advisory、只收紧不放松 | — |

---

## 3. 通用能力模型（11 维）

萃取出的框架 jar 必须覆盖以下 11 维通用能力（源自 Claude Agent SDK / OpenAI Codex SDK / DeepSeek Harness / AgentScope 四家能力对比的公共交集）：

| # | 能力 | Langur 落点 |
|---|---|---|
| 1 | Agent Loop / 执行范式 | `domain/harness/execution` + `workflow`（ReAct/PlanAndExecute/Workflow/Hybrid） |
| 2 | Provider / 模型网关 | `LLMPort` → `infra/llm` |
| 3 | 工具 + MCP | `ToolProvider`/`ToolProviderSPI` → `infra/tool` + `harness/tool` |
| 4 | 上下文 / 记忆 | `VectorStore`/`EmbeddingPort`/`RerankPort` → `infra/harness/context` |
| 5 | 会话 / 状态 | `CacheBackend`/`DistributedLock`/仓储 → `infra/cache`+`persistence`+`state` |
| 6 | 权限 / 审批 | `SecurityPolicySPI` → `infra/harness/security` |
| 7 | 沙箱 / 隔离 | 四层校验链（权限→风险→白名单→限流）→ `harness/tool/validator` |
| 8 | Hooks / 生命周期 | `LifecycleHook`/`HookPoint` → `domain/harness/lifecycle` |
| 9 | 可观测 / 成本 | `EvaluationPort`/`AlertEvaluator`/`TokenUsage` → `harness/evaluation` |
| 10 | 多智能体 | 消息总线 + 委派 → `domain/harness/multiagent` |
| 11 | 结构化输出 / 配置 | `PromptTemplateSPI`/`OutputPostProcessorSPI` + yml |

> 不属于通用能力、留给使用方的：账单级计费、交互式 OAuth 浏览器流程、跨主机会话一致性锁、UI 渲染、具体业务工具。

---

## 4. 目标 jar 集（12 框架 jar + 1 BOM）

### 4.1 结构

```
langur-bom                          # 版本锁定（dependencyManagement）

── 框架核心（维持现状，可被第三方直接依赖）──────────
langur-common                        # SPI 契约 + Cache/Lock 接口 + 异常
langur-domain                        # 领域模型 + 端口 + 六组件引擎 + RSI 引擎 + 决策平面值对象
langur-application                   # 用例编排

── 运行时（★新增，从 infrastructure 拆出）──────────
langur-runtime                       # 六组件进程内装配 + 内存默认实现

── 适配器（从 infrastructure 按能力拆出，8 个）──────
langur-llm                           # LlmGateway/LLMRouter/适配器/熔断
langur-tools                         # tool + mcp/rest/code/skill/validator
langur-vector                        # 向量存储 + Embedding/Rerank
langur-persistence                   # jpa + cache + redis
langur-security                      # 审批 + 注入防护 + 内容审核
langur-observability                 # alert/audit/tracing + Micrometer
langur-decision                      # Jev 后端 + 装饰链
langur-rsi                           # RSI 装配件（引擎留在 domain）

── 应用（不发布，本仓库独有）───────────────────────
langur-api                           # REST 入口（保持）
langur-start                         # 组装点（改为依赖上述适配器 jar）
```

### 4.2 `langur-runtime` 边界（钉死）

内容 = `infra/harness/{execution, workflow, multiagent, state}` + `context` 非 vector 部分：

- `execution/`：`LlmPlanner`、`LockingExecutionLoopService`
- `workflow/`：`WorkflowDefinitionRegistrar`、`WorkflowProperties`
- `multiagent/`：`InMemoryAgentMessageBus`
- `state/`：`InMemoryTaskStateRepository`
- `context/`（非 vector）：`DefaultContextAssembler`、`DefaultContextBuilder`、`KeywordMaskingContextSanitizer`、`InMemoryAgentContextRepository`

判据：**"外部系统适配器" 进 8 个适配器 jar；"进程内装配 + 内存默认实现" 进 `langur-runtime`。**

### 4.3 依赖关系（拆后仍严格单向，P2 不变）

```
langur-start ──▶ langur-api ──▶ langur-application ──▶ langur-domain
                                 │           ▲              ▲
                                 └▶ langur-common ──────────┤
                                                            │
            langur-runtime ────────────────▶ (domain + llm) │
            langur-llm      ───────────────▶ domain ────────┘
            langur-tools    ──▶ (domain + llm)
            langur-vector   ──▶ (domain + llm)
            langur-rsi      ──▶ (domain + llm + tools)
            langur-persistence / security / observability / decision ──▶ domain
```

**铁律**：任何适配器只依赖 `langur-domain`/`langur-common` 的接口，不得反向；`domain` 零外部依赖（P1）。

### 4.4 已识别的横向依赖（拆包前必须处理）

- **真实环 llm↔tool**：`infra/llm/ModelProviderFactory` → `infra/harness/tool/rest/SecretResolver`，同时 `infra/harness/tool/skill/DefaultSkillToolGateway` → `infra/llm`。**修法：`SecretResolver` 移至 `langur-common`**（单类移动，凭据解析是横切工具）。
- 单向扇出（转成 jar 间依赖即可，无需改代码）：`llm` 被 `vector`/`rsi`/`execution`/`tool` 依赖；`harness.tool` 被 `llm`/`skill`/`vector`/`rsi` 依赖。
- `harness.context`、`harness.decision`、`harness.rsi`、`harness.security` 无外部被依赖 → 边界干净。

---

## 5. 拆包操作规范（不改变 DDD 架构）

1. **纯 Maven 模块拆分**：目录按子包 `git mv` 到新模块，包名 `org.skylark.langur.infrastructure.*` 原样保留；类 FQN 不变 → 领域层/应用层零改动。
2. **端口不动**：SPI/Port 接口仍留 `common`/`domain`，适配器 jar 编译期只依赖接口。
3. **装配靠 Spring 条件装配**：每个适配器 jar 自带 `*AutoConfiguration`（`@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`），复用 `ObjectProvider`/`@ConditionalOnProperty`/`@ConditionalOnMissingBean` 既有模式；缺 jar = 缺 Bean = P10 降级。
4. **`langur-start` 变组装点**：从"依赖一个 infra"改为"依赖所需适配器 jar 列表"，yml 零改动。
5. **ArchUnit 更新（改测试不改架构）**：`ArchitectureGuardTest` 更新模块清单，三条不变量原样保留——① domain 纯度（零适配器 jar import）② 全模块无环 ③ common 无状态 Bean。
6. **BOM 锁版本**：`langur-bom` 复用根 pom `dependencyManagement`。

---

## 6. 脚手架交付（两种形态，共用同一批 jar）

### 6.1 交付物清单

| 交付物 | 作用 |
|---|---|
| 12 个框架 jar + `langur-bom` | 框架能力本体 |
| `langur-starter` | 聚合依赖（runtime+llm+tools+persistence + 默认 auto-config），业务一键引入 |
| `langur-archetype`（扁平） | 生成"快速上手"型单模块项目 |
| `langur-hexagonal-archetype`（DDD） | 生成"业务六边形"型多模块项目 |
| 脚手架 README + 示例 | 写一个工具、跑通一个 agent 的 hello-world |

### 6.2 形态 A：扁平 starter（快速上手）

```
my-agent-app/
├── pom.xml                              # 引 langur-starter（或按需 jar）
├── README.md                            # 脚手架自带说明
└── src/main/
    ├── java/com/acme/myagent/
    │   ├── MyAgentApplication.java      # @SpringBootApplication（模板给好）
    │   └── tool/CrmQueryTool.java       # ★业务唯一必写：实现 ToolProvider
    └── resources/application.yml        # ★业务唯一必配：provider/model/开关
```

业务侧只写三样：yml 配置、一个工具类、空壳 main。其余（循环/校验链/记忆/快照/钩子/观测/决策/RSI）全部继承自 jar。

### 6.3 形态 B：DDD 六边形脚手架（业务域继续用六边形纪律）

```
my-agent-app/
├── pom.xml                              # langur-bom + 按需 jar
├── bootstrap/                           # 唯一知道所有实现的模块（main + 装配）
├── business-domain/                     # 业务聚合 + 业务端口(CrmPort) + 业务领域事件
├── business-application/                # 业务用例（编排 business-domain + Langur 端口）
└── adapters/
    ├── in-http/                         # 业务 REST（薄封装或复用 langur-api）
    ├── out-crm/                         # 实现业务 CrmPort（调真实 CRM）
    └── out-tools/                       # ★实现 Langur 的 ToolProvider（包装业务用例为工具）
```

依赖方向（与 Langur 同构）：`bootstrap → adapters → business-application → business-domain`。

**两套端口并存**：
- Langur 端口（`ToolProvider`/`Tool`/`SecurityPolicySPI`/`DecisionEngineSPI`）→ 业务"实现"它，接入框架运行时；
- 业务端口（`CrmPort → CrmAdapter`）→ 业务内部依赖倒置，框架不感知。

**耦合边界**：业务 domain 只 import `langur-domain` 的端口接口 + 值对象，绝不 import 任何 `langur-*` 的 infrastructure 实现；业务 domain 同样保持纯度（不 import Spring/infra，可复用 Langur 的 ArchUnit 规则）。

### 6.4 最小可运行示例（两形态通用）

`application.yml`：

```yaml
langur:
  llm:
    default-provider: openai
    providers:
      openai: { type: openai-compatible, base-url: https://api.openai.com/v1,
                api-key-ref: env:OPENAI_API_KEY, model: gpt-4o }
    role-models: { REASONING: gpt-4o }
  decision: { enabled: false }   # 决策平面/RSI 默认关，按需开
  rsi:     { enabled: false }
```

业务工具：

```java
@Component
public class CrmQueryTool implements ToolProvider {
    @Override public List<Tool> getTools() {
        return List.of(new Tool("crm_query", "按客户名查 CRM 档案",
            Map.of("name", "string"), this::query));
    }
    private String query(Map<String, Object> args) { /* 调业务 CRM */ }
}
```

运行入口（复用框架 REST 面）：

```http
POST /api/agents               → 创建 agent，toolNames: ["crm_query"]
POST /api/agents/{id}/run      → 同步执行
POST /api/v1/agent/stream      → SSE 流式
```

---

## 7. 落地路线

| 阶段 | 动作 | 验收 |
|---|---|---|
| P0 静态分析 | jdeps/ArchUnit 生成 infra 内部完整依赖图 | 依赖图无未知环 |
| P1 移类 | `SecretResolver` 移至 `langur-common`（解除 llm↔tool 环） | 全量测试绿 |
| P2 拆首个适配器 | 拆出 `langur-llm`（边界最清、被依赖最多） | `langur-start` 可跑、测试绿 |
| P3 拆其余 | 依次拆 `runtime`/`vector`/`persistence`/`tools`/`security`/`observability`/`decision`/`rsi` | 每拆一个跑一次全量测试 |
| P4 治理 | 新增 `langur-bom` + 更新 ArchUnit 模块清单 + 补 auto-config imports | ArchUnit 绿、`mvn clean package` 绿 |
| P5 脚手架 | 产出 `langur-starter` + 双 archetype + `examples/hello-agent` 蓝本 | 第三方最小依赖可运行 |

---

## 8. 命名与版本规范

- groupId：`org.skylark.langur`；版本沿用 `${project.version}`（当前 `1.0.0`）。
- jar 命名前缀 `langur-`；适配器 jar = `langur-<能力域>`（llm/tools/vector/persistence/security/observability/decision/rsi）。
- 适配器 jar 内新增的唯一新类：`<能力域>AutoConfiguration`（`@AutoConfiguration`）。
- 拆包 = 纯 `git mv`，保留文件历史；禁止重命名包、禁止改公开接口签名。
