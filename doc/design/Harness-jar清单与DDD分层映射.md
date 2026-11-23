# Langur Harness jar 清单与 DDD 分层映射

> **文档定位**：面向架构评审与业务接入的"最终形态速查"，回答三个问题：
> ① 改动后有几个 jar、各自封装什么能力；② 脚手架是什么架构；③ 如何保持既有 DDD 分层。
>
> **权威关系**：[ARCHITECTURE.md](ARCHITECTURE.md) 是 jar 化与脚手架交付的权威规范；
> [Langur-jar化分析.md](Langur-jar化分析.md) 是现状盘点；本文聚焦"jar 清单 + 能力矩阵 + DDD 映射 + 接入方式"。
>
> **基线**：`c95e6c1`（2026-10-19），6 个 Maven 模块。
> main-only Java 文件数：common 16 / domain 146 / application 15 / api 24 / infrastructure 178 / start 10。
>
> **前提**：12 个框架 jar 是**目标发布形态**，当前仓库仍是 6 模块。拆包是"Maven 打包切面"，不是架构重构。

---

## 0. 结论速览

1. **对外发布 13 个 jar + 1 个 BOM**：12 个框架能力 jar（4 核心 + 8 适配器）+ 1 个聚合 `langur-starter`。
2. **参考应用保留 2 个 artifact**：`langur-api`（REST/SSE 驱动适配）与 `langur-start`（Boot 启动/装配），**不对外发布**。
3. **业务最小依赖**：`langur-bom` + `langur-starter`；需要裁剪时用 `bom + domain + runtime + 按需适配器`。
4. **DDD 分层完整保留**：13 个 jar 中，12 个各归属一个既有 DDD 层；`starter` 是组合根（start 关注点），不新增业务层。
5. **唯一的代码移动**：`SecretResolver` 从 `infra/harness/tool/rest` 移到 `langur-common`，解除 `llm ↔ tool` 环。

### 0.1 一张图

```text
业务代码（只写 ToolProvider / yml / main）
        │
        ▼
langur-starter（组合根：依赖聚合 + 自动装配 + 默认配置）
        │
        ├─▶ langur-runtime ──▶ langur-llm ──▶ langur-domain ──▶ langur-common
        ├─▶ langur-tools ────▶ langur-llm
        ├─▶ langur-vector ───▶ langur-llm
        ├─▶ langur-persistence
        ├─▶ langur-security
        ├─▶ langur-observability
        ├─▶ langur-decision
        └─▶ langur-rsi ──────▶ langur-tools

langur-api ──▶ langur-application ──▶ langur-domain
langur-start ──▶ langur-api + langur-starter（参考应用装配/部署）
```

> 所有适配器箭头最终都指向 `langur-domain`（实现其端口）；`domain` 不反向依赖任何适配器。

---

## 1. 现状基线（为什么要拆）

### 1.1 六个 DDD 模块

| 模块 | DDD 层 | 核心职责 | main Java 文件 |
|---|---|---|---|
| `langur-common` | 公共基础 | SPI 契约、Cache/Lock 接口、异常、共享值对象 | 16 |
| `langur-domain` | 领域层 | 六组件领域模型、端口、仓储接口、领域服务、RSI 引擎、决策值对象 | 146 |
| `langur-application` | 应用层 | 用例编排、流式服务、命令/结果/Assembler | 15 |
| `langur-infrastructure` | 基础设施层 | LLM/向量/工具/持久化/决策/安全/可观测/RSI 实现 | 178 |
| `langur-api` | 接口适配（入站） | REST/SSE 控制器、DTO、API 治理 | 24 |
| `langur-start` | 启动/装配 | main、9 个配置类、application.yml | 10 |

依赖方向：`start → api → application → domain ← infrastructure`；`common` 被各层依赖；
`domain` 仅依赖 `common`（+ lombok，测试用 starter-test）。

### 1.2 已经正确的部分（不要动）

- **端口在领域侧**：`domain/port`（`LLMPort`、`DecisionPort`、`ToolProvider`、`FallbackStrategy`）、
  `domain/repository`（3 个仓储接口）、`domain/harness/spi`（`BizCodeRouter`）、
  `common/spi`（8 个 SPI）、`common/cache`（`CacheBackend`/`DistributedLock`）。
- **领域层已零 Spring**：`langur-domain` 的 pom 只有 common + lombok；main 代码 0 处 `org.springframework` import。
- **ArchUnit 已在守护**：`ArchitectureGuardTest` 四条不变量——domain 不依赖 Spring/infrastructure；
  api 不直连 domain；common 无状态 Bean；模块切片无环。

结论：**jar 化只需切 Maven 模块边界，不需要改包名、改端口、改依赖方向。**

---

## 2. 目标 jar 清单（13 + BOM）

### 2.1 计数口径

| 口径 | 数量 | 说明 |
|---|---|---|
| 对外发布 jar | **13** | 12 个框架能力 jar + 1 个 `langur-starter` |
| BOM | 1 | `langur-bom`，只含 `dependencyManagement` |
| 参考应用 artifact | 2 | `langur-api`、`langur-start`，不发布 |
| 脚手架模板 | 2 | `langur-archetype`（扁平）、`langur-hexagonal-archetype`（DDD） |
| 当前模块 → 目标 artifact | 6 → 16 | infra 1 拆 9；start 1 拆 2；新增 BOM/starter/2 个 archetype |

### 2.2 12 个框架 jar（可发布）

| # | jar | DDD 层 | 封装能力 | 关键包/类 |
|---|---|---|---|---|
| 1 | `langur-common` | 公共基础 | SPI 契约、缓存/锁接口、异常、共享值对象 | `common/spi`：`BusinessExecutorSPI`/`ContextEnricherSPI`/`DecisionEngineSPI`/`OutputPostProcessorSPI`/`PromptTemplateSPI`/`SecurityPolicySPI`/`ToolProviderSPI`/`SpiToolSpec`/`BizContext`；`common/cache`：`CacheBackend`/`DistributedLock` |
| 2 | `langur-domain` | 领域层 | 六组件领域模型 + 端口 + 仓储接口 + 领域服务 + RSI 引擎 + 决策值对象 | `model`（`Agent`/`Plan`/`AgentRunTask`/`Tool`…）、`port`、`repository`、`service`（`AgentDomainService`/`PlanningDomainService`）、`harness/**`（execution/tool/context/state/lifecycle/evaluation/security/multiagent/rsi/workflow/decision） |
| 3 | `langur-application` | 应用层 | 用例编排、异步任务、流式事件、工具注册 | `AgentApplicationService`、`AgentRunTaskApplicationService`、`AgentStreamApplicationService`、`ToolRegistryService`、`TaskProgressBus`、command/result/assembler |
| 4 | `langur-runtime` | 基础设施层（进程内运行时） | 六组件进程内装配 + 内存默认实现 + 执行循环装饰 | `LlmPlanner`、`LockingExecutionLoopService`、`WorkflowDefinitionRegistrar`、`WorkflowProperties`、`InMemoryAgentMessageBus`、`InMemoryTaskStateRepository`、`DefaultContextAssembler`、`InMemoryAgentContextRepository`、`KeywordMaskingContextSanitizer`、`DefaultBizCodeRouter` |
| 5 | `langur-llm` | 基础设施层（出站适配器） | 多 provider 模型网关、路由、降级、熔断 | `LlmGateway`、`LLMRouter`、`ModelProviderFactory`、`AbstractOpenAICompatibleLLMAdapter`、`OpenAI/Claude/Gemini/DeepSeek/Qwen` 适配器、`ConfigurableOpenAICompatibleLLMAdapter`、`ProviderCircuitBreaker`、`ModelRole`、`LlmProperties` |
| 6 | `langur-tools` | 基础设施层（出站适配器） | 工具注册/调度 + 内置工具 + MCP + REST API + Code + Skill + 四层校验链 | `InMemoryToolRegistry`、`DefaultToolDispatcher`、`LocalToolRegistrar`、`HttpCallTool`、`Knowledge*`、`mcp/**`、`rest/**`、`code/**`、`skill/**`、`validator/**` |
| 7 | `langur-vector` | 基础设施层（出站适配器） | 向量存储、Embedding、Rerank、混合检索 | `InMemoryVectorStore`、`PgVectorStore`、`ElasticsearchVectorStore`、`MilvusVectorStore`、`LexicalEmbeddingPort`、`LlmEmbeddingPort`、`EmbeddingRerankPort`、`VectorProperties`、`ClientSideFusion` |
| 8 | `langur-persistence` | 基础设施层（出站适配器） | JPA/内存仓储、缓存、分布式锁 | `persistence/jpa/**`（Agent/Plan/RunTask/TaskState/ToolDefinition/ToolInvocation/AuditLog/UserProfile/ExecutionRound）、`InMemory*Repository`、`Memory/RedisCacheBackend`、`Memory/RedisDistributedLock`、`CacheProperties` |
| 9 | `langur-security` | 基础设施层（出站适配器） | 审批、Prompt 注入防护、内容审核 | `ApprovalService`、`InMemoryApprovalStore`、`PromptInjectionGuardHook`、`ContentReviewOutputHook` |
| 10 | `langur-observability` | 基础设施层（出站适配器） | 评估、告警、审计、OTel Trace、Micrometer 指标 | `LoggingEvaluationService`、`MicrometerEvaluationService`、`OtelExecutionTracer`、`AuditSink`/`LoggingAuditSink`、`LoggingAlertChannel`、`ObservabilityProperties` |
| 11 | `langur-decision` | 基础设施层（出站适配器） | 判定式决策平面装饰链 + 远程/本地/规则后端 | `TypeSafeDecisionAdapter`、`LocalDecisionAdapter`、`RuleFallbackDecisionAdapter`、`CachingDecisionPort`、`RecordingDecisionPort`、`ThresholdRouter`、`DataResidencyDecisionPort`、`DecisionTrajectoryRecorder`、`DecisionProperties` |
| 12 | `langur-rsi` | 基础设施层（出站适配器） | 反思、蒸馏、技能合成、工具扩展、提案/轨迹仓储 | `ReflectionHook`、`LlmDistillationExtractor`、`SkillSynthesizer`/`SkillSynthesisValidator`、`SynthesizedSkillRegistrar`、`ToolExtensionRegistrar`、`InMemoryRsiProposalRepository`、`InMemoryTrajectoryRepository`、`RsiProperties` |

> 判据（来自 ARCHITECTURE.md §4.2）：**"外部系统适配器"进 8 个适配器 jar；
> "进程内装配 + 内存默认实现"进 `langur-runtime`。**

### 2.3 聚合 starter 与参考应用

| artifact | 类型 | 封装能力 | 是否发布 |
|---|---|---|---|
| `langur-starter` | 聚合 jar（第 13 个） | 聚合 `application + runtime + llm + tools + persistence`，提供默认 AutoConfiguration 与配置绑定；业务一键引入 | ✅ |
| `langur-api` | 应用 artifact | `AgentController`、`AgentV1Controller`、`AgentStreamV1Controller`、`GlobalExceptionHandler`、DTO、`GovernanceFilter`（鉴权/租户/限流/幂等/TraceId） | ❌ 参考应用 |
| `langur-start` | 应用 artifact | `LangurApplication`、`application.yml`、领域服务/Harness/模型/决策/可观测/RSI/向量数据源装配 | ❌ 参考应用 |

> 注：ARCHITECTURE.md §6.1 的 starter 聚合清单为 `runtime+llm+tools+persistence`；
> 嵌入式场景必须装配用例层，故本文按"可运行最小集"把 `application` 一并纳入 starter 的传递依赖。

### 2.4 BOM

`langur-bom`：`dependencyManagement` 锁定 13 个 jar 的版本（沿用根 pom `${project.version}`，当前 `1.0.0`），
业务只声明一个 BOM 版本。

---

## 3. 11 维 Harness 能力 → jar/端口

| # | 能力 | 端口/契约 | 领域/运行时落点 | 适配器 jar |
|---|---|---|---|---|
| 1 | Agent Loop / 执行范式 | `ExecutionLoopService`/`LayerRouter`/`TerminationGate` | `domain/harness/execution`、`workflow`（ReAct/PlanAndExecute/Workflow/Hybrid） | `runtime`（`LlmPlanner`/`LockingExecutionLoopService`） |
| 2 | Provider / 模型网关 | `LLMPort` | `domain/port` | `llm` |
| 3 | 工具 + MCP | `ToolProvider`/`ToolProviderSPI`/`ToolRegistry`/`ToolDispatcher`/`ToolValidator` | `domain/harness/tool` | `tools` |
| 4 | 上下文 / 记忆 | `ContextAssembler`/`AgentContextRepository`/`VectorStore`/`EmbeddingPort`/`RerankPort` | `domain/harness/context` | `vector` + `runtime`（非 vector 上下文） |
| 5 | 会话 / 状态 | `CacheBackend`/`DistributedLock`/`TaskStateRepository`/3 个业务仓储 | `common/cache`、`domain/repository`、`domain/harness/state` | `persistence` |
| 6 | 权限 / 审批 | `SecurityPolicySPI`/`ApprovalPort` | `domain/harness/security`、`common/spi` | `security` |
| 7 | 沙箱 / 隔离 | 四层校验链（白名单→Schema→权限→沙箱） | `domain/harness/tool` | `tools/validator` |
| 8 | Hooks / 生命周期 | `LifecycleHook`/`HookPoint` | `domain/harness/lifecycle` | `security`/`rsi` 等钩子实现 |
| 9 | 可观测 / 成本 | `EvaluationService`/`ExecutionTracer`/`TokenUsage` | `domain/harness/evaluation` | `observability` |
| 10 | 多智能体 | `AgentMessageBus`/`AgentDispatcher` | `domain/harness/multiagent` | `runtime`（`InMemoryAgentMessageBus`） |
| 11 | 结构化输出 / 配置 | `PromptTemplateSPI`/`OutputPostProcessorSPI` | `common/spi` + yml | `starter`/`start` 配置绑定 |

---

## 4. 如何保持既有 DDD 分层

### 4.1 七条规则

1. **一个 jar 只归属一个 DDD 层**：9 个 infra 衍生 jar 全部是"基础设施层"的子包化；`runtime` 不是新层。
2. **端口不动**：Port/SPI 接口继续留在 `common`/`domain`，适配器只实现、不拥有契约。
3. **依赖方向不变**：`domain → common`；`application → domain`；`api → application`；`infra-* → domain`；
   `starter/start → 各层`。适配器永不被 domain 依赖。
4. **包名/FQN 不变**：拆包 = `git mv` + 新建 pom，`org.skylark.langur.infrastructure.*` 原样保留；
   禁止重命名包、禁止改公开接口签名。
5. **runtime 是基础设施的"进程内装配 + 内存默认实现"子集**，不是新的业务层，也不是 application 的替代。
6. **starter 是组合根**：只做依赖聚合、AutoConfiguration、`@ConfigurationProperties`、
   `@ConditionalOnMissingBean`，不含业务规则。
7. **api/start 不发布**：它们是参考应用（入站适配 + 部署），业务方不依赖；
   业务只面对 starter + domain 端口。

### 4.2 jar × DDD 层映射

| DDD 层 | 对应 jar | 是否新增 jar |
|---|---|---|
| 公共基础 | `langur-common` | 否 |
| 领域层 | `langur-domain` | 否 |
| 应用层 | `langur-application` | 否 |
| 基础设施层 | `langur-runtime` + `llm/tools/vector/persistence/security/observability/decision/rsi` | 是（1 → 9，均为同层拆分） |
| 接口适配（入站） | `langur-api` | 否（不发布） |
| 启动/装配 | `langur-starter`（组合根）+ `langur-start`（参考应用） | 是（1 → 2，均为装配/部署关注点） |

### 4.3 依赖矩阵（直接依赖）

| 依赖方 ↓ | common | domain | application | runtime | llm | tools | vector | persistence | security | observability | decision | rsi | starter |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| domain | ✓ | | | | | | | | | | | | |
| application | ✓ | ✓ | | | | | | | | | | | |
| runtime | ✓ | ✓ | | | ✓ | | | | | | | | |
| llm | ✓ | ✓ | | | | | | | | | | | |
| tools | ✓ | ✓ | | | ✓ | | | | | | | | |
| vector | ✓ | ✓ | | | ✓ | | | | | | | | |
| persistence | ✓ | ✓ | | | | | | | | | | | |
| security | ✓ | ✓ | | | | | | | | | | | |
| observability | ✓ | ✓ | | | | | | | | | | | |
| decision | ✓ | ✓ | | | | | | | | | | | |
| rsi | ✓ | ✓ | | | ✓ | ✓ | | | | | | | |
| api | ✓ | | ✓ | | | | | | | | | | |
| starter | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | | ✓ | 可选 | 可选 | 可选 | 可选 | |
| start | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

> `api` 不直接依赖 `domain`（ArchUnit 已守护）；`starter` 对 security/observability/decision/rsi 为"可选引入"，
> 缺 jar = 缺 Bean = P10 降级。

### 4.4 为什么这是"打包切面"而不是"架构切面"

| 维度 | 拆分前 | 拆分后 | 是否改变 |
|---|---|---|---|
| DDD 层职责 | 六层 | 六层 | ❌ |
| 端口位置 | common/domain | common/domain | ❌ |
| 包名/FQN | `org.skylark.langur.*` | 原样 | ❌ |
| 依赖方向 | 单向向内 | 单向向内 | ❌ |
| Maven 模块数 | 6 | 15 + BOM | ✅ 仅物理粒度 |
| 装配位置 | `langur-start/config` | 各 jar 的 AutoConfiguration + starter | ✅ 仅装配方式 |

---

## 5. 两种接入形态

### 5.1 嵌入式（推荐）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.skylark.langur</groupId>
      <artifactId>langur-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>org.skylark.langur</groupId>
    <artifactId>langur-starter</artifactId>
  </dependency>
</dependencies>
```

业务只写三样：`application.yml`、一个工具类、空壳 main。

```java
@Component
public class CrmQueryTool implements ToolProvider {
    @Override
    public List<Tool> getTools() {
        return List.of(new Tool("crm_query", "按客户名查 CRM 档案",
                Map.of("name", "string"), this::query));
    }

    private String query(Map<String, Object> args) {
        // 调业务 CRM
    }
}
```

```yaml
langur:
  llm:
    default-provider: openai
    providers:
      openai:
        type: openai-compatible
        base-url: https://api.openai.com/v1
        api-key-ref: env:OPENAI_API_KEY
        model: gpt-4o
    role-models:
      REASONING: gpt-4o
  decision:
    enabled: false
  rsi:
    enabled: false
```

### 5.2 托管 HTTP

部署 `langur-start` 胖 jar，业务通过 REST/SSE 调用：

```http
POST /api/agents               → 创建 agent，toolNames: ["crm_query"]
POST /api/agents/{id}/run      → 同步执行
POST /api/v1/agent/stream      → SSE 流式
```

业务工具仍以实现 `ToolProvider` 的方式打包进自定义 boot 模块；`langur-api` 只依赖 `application`，不直接碰适配器。

### 5.3 DDD 六边形业务工程（archetype 生成）

```text
my-agent-app/
├── pom.xml                    # langur-bom + 按需 jar
├── bootstrap/                 # 唯一知道所有实现的模块（main + 装配）
├── business-domain/           # 业务聚合 + 业务端口 + 业务领域事件
├── business-application/      # 业务用例（编排 business-domain + Langur 端口）
└── adapters/
    ├── in-http/               # 业务 REST（薄封装或复用 langur-api）
    ├── out-crm/               # 实现业务 CrmPort（调真实 CRM）
    └── out-tools/             # 实现 Langur ToolProvider（包装业务用例为工具）
```

两套端口并存：Langur 端口（`ToolProvider`/`Tool`/`SecurityPolicySPI`/`DecisionEngineSPI`）由业务实现；
业务端口（`CrmPort → CrmAdapter`）由业务内部依赖倒置，框架不感知。

---

## 6. 迁移路线（P0–P5）

| 阶段 | 动作 | 验收 |
|---|---|---|
| **P0 静态分析** | 用 jdeps/ArchUnit 生成 infra 内部完整依赖图，锁定全部横向依赖 | 依赖图无未知环 |
| **P1 移类** | `SecretResolver` 从 `infra/harness/tool/rest` 移到 `langur-common`（解除 llm↔tool 环） | 全量测试绿 |
| **P2 拆首个适配器** | 拆出 `langur-llm`（边界最清、被依赖最多） | `langur-start` 可跑、测试绿 |
| **P3 拆其余** | 依次拆 `runtime`/`vector`/`persistence`/`tools`/`security`/`observability`/`decision`/`rsi` | 每拆一个跑一次全量测试 |
| **P4 治理** | 新增 `langur-bom` + 每个适配器补 `@AutoConfiguration` 与 `META-INF/spring/...imports` + 更新 ArchUnit 模块清单 | `mvn clean package` 绿、ArchUnit 绿 |
| **P5 脚手架** | 产出 `langur-starter` + 双 archetype + `examples/hello-agent` 蓝本 | 第三方最小依赖可运行 |

每个阶段都是**纯文件移动 + 配置**，不触碰领域逻辑。

---

## 7. 验证与守卫

```bash
# 全量构建
mvn -q clean package

# 架构守卫（现有四条不变量）
mvn -pl langur-start -Dtest=ArchitectureGuardTest test

# 领域层零 Spring（应无输出）
grep -r "org.springframework" langur-domain/src/main/java || true
```

拆包后新增/保持的不变量：

1. `domain` 不得依赖任何 `langur-*` 适配器或 Spring（P1）。
2. 适配器只依赖 `domain`/`common`（以及允许的 `llm`/`tools` 扇出），不得依赖 `application`/`api`/`start`。
3. `api` 不得直接依赖 `domain`（P2）。
4. 13 个模块依赖无环（Maven reactor + ArchUnit slices）。
5. `starter` 只装配、不含业务类；业务 domain 不得 import 任何 `infra-*` 实现。
6. 拆包前后类 FQN 完全一致（`git mv` 校验）。

---

## 8. 风险与回滚

| 风险 | 处置 |
|---|---|
| `SecretResolver` 跨包环 | P1 先移到 `langur-common`；这是唯一代码移动 |
| 适配器自动装配缺失 | 每个 jar 自带 `@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；`@ConditionalOnMissingBean`/`@ConditionalOnProperty`；缺 jar 即降级（P10） |
| 拆包导致启动失败 | 保留 `langur-start` 作为回归基线；每拆一个跑全量测试 |
| 版本漂移 | `langur-bom` 统一版本；适配器不单独改版本 |
| 业务误依赖实现 | 文档 + ArchUnit + starter 只暴露端口；业务 domain 禁止 import infra |
| 需要回滚 | 保留 6 模块聚合构建；任一阶段失败可回退到上一阶段，已拆 jar 不影响既有 `langur-start` |

---

## 9. 附录

### 9.1 端口清单（冻结）

- `domain/port`：`LLMPort`、`DecisionPort`、`ToolProvider`、`FallbackStrategy`
- `common/spi`：`BusinessExecutorSPI`、`ContextEnricherSPI`、`DecisionEngineSPI`、`OutputPostProcessorSPI`、
  `PromptTemplateSPI`、`SecurityPolicySPI`、`ToolProviderSPI`、`SpiToolSpec`、`BizContext`
- `common/cache`：`CacheBackend`、`DistributedLock`
- `domain/repository`：`AgentRepository`、`AgentRunTaskRepository`、`PlanRepository`
- `domain/harness/spi`：`BizCodeRouter`

### 9.2 配置前缀（沿用现有 `application.yml`）

`langur.repository`、`langur.cache`、`langur.llm`、`langur.vector`、`langur.embedding`、`langur.rerank`、
`langur.run-task`、`langur.planner`、`langur.workflow`、`langur.decision`、`langur.rsi`、`langur.security`、
`langur.observability`、`langur.governance`、`langur.code-access`、`langur.rest-api-tools`、`langur.mcp-tools`。

### 9.3 基线核对命令

```bash
# 当前模块
grep -A7 '<modules>' pom.xml

# main-only 文件数
for d in langur-common langur-domain langur-application langur-api langur-infrastructure langur-start; do
  printf "%s: " "$d"; find "$d/src/main" -name '*.java' | wc -l
done
```
