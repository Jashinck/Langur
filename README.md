# Langur Agent-Harness — 叶猴 Agent 能力框架

> **Langur** 是一套企业级 Agent-Harness 框架——大模型的操作系统与安全沙箱层，以叶猴（Langur）命名，轻盈而敏捷。
> 基于 DDD 六边形架构（6 Maven 模块）+ 六组件正交设计，支持 ReAct / PlanAndExecute / Workflow / Hybrid 多范式执行。

## 三大架构哲学

> **大模型写，决策平面判，Harness 管，RSI 进化。**

| 哲学 | 一句话内核 | 解决什么 |
|------|-----------|----------|
| **Harness** · 确定性调度底座 | 模型负责写，Harness 负责管 | 概率性模型输出如何被确定性约束（权限/预算/合规/可回滚） |
| **Jev** · 判定式决策平面 | 大模型写，决策平面判（判定/生成分离，P12） | 路由/闸门/评分/分类等"判定"如何快、廉、校准、可批量 |
| **RSI** · 递归自我演进 | 自我改进 ≠ 自我失控（P11） | Agent 如何用自身运行数据持续改进自身且不失控 |

---

## 能力全景

| 领域 | 能力 |
|------|------|
| **执行范式** | ReAct 循环、PlanAndExecute、Workflow 编排、Hybrid 分层调度（层路由 → Workflow → PlanAndExecute → ReAct） |
| **工具体系** | 统一工具链（内置 HTTP / 代码访问 / 知识库 / Skill / MCP / REST OpenAPI），四层校验链（权限 → 风险 → 白名单 → 限流） |
| **上下文** | 四级分层记忆（L1 瞬时 / L2 会话 / L3 任务 / L4 向量知识）+ 语义召回 + Token 预算治理 |
| **状态** | 快照断点续跑、分布式锁防重入、幂等键、Redis 热层 |
| **生命周期** | 钩子引擎（HookPoint 拦截 + ABORT/SKIP/MODIFY），安全注入/输出审核/高危人审挂点 |
| **可观测** | 五维指标（SCHEDULING/TOOL/MODEL/SECURITY/DECISION）→ Prometheus + MQ 审计 + 分级告警 |
| **模型网关** | 统一 `LlmGateway`（角色→模型 + 主备降级 + Provider 熔断），配置化接入 OpenAI/Claude/Gemini/DeepSeek/Qwen/GLM |
| **向量库** | 可插拔 `VectorStore`（memory/pgvector/Elasticsearch/Milvus），原生混合检索（RRF/加权）+ 应用侧兜底 |
| **决策平面** | `DecisionPort`（Jev 判定模型：choice/probability/score + 置信度），装饰链（录制 ⊃ 缓存 ⊃ 阈值 ⊃ 后端 ⊃ 兜底），10 个执行插入点 |
| **RSI 自改进** | 回放验证引擎（R0）→ 反思自检（R1）→ 记忆自蒸馏（R2）→ 技能自合成（R3）→ 安全平面（R-G）→ 策略自优化（R4）→ 工具自扩展（R5） |
| **多 Agent** | AgentId 隔离消息总线 + 跨 Agent 委派 + SUB_AGENT 步骤执行接缝 |
| **工程治理** | ArchUnit 架构守卫（domain 纯度 / 分层无环）、P1–P12 设计原则、设计文档与代码同步提交 |

---

## 架构

### 全局架构

![Langur 全局架构](doc/share/images/langur-global-architecture.png)

### 六组件正交 + 决策平面横切

```
                ┌─────────────────────────────────────────────┐
                │  决策平面（DecisionPort，System-1 判定层）      │
                │  Jev 后端 + 录制/缓存/阈值/数据驻留装饰链        │
                └─────────────────────────────────────────────┘
                                   ▲ ObjectProvider 横切注入（非第七组件）
┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐
│  E   │ → │  T   │ → │  C   │ → │  S   │ → │  L   │ → │  V   │
│执行  │   │工具  │   │上下文│   │状态  │   │生命周期│  │可观测│
└──────┘   └──────┘   └──────┘   └──────┘   └──────┘   └──────┘
```

![Harness 六要素（正交组件）](doc/share/images/langur-harness-six-components.png)

- **E（Execution）**：ReAct / PlanAndExecute / Workflow / Hybrid 四范式，终止闸门 + 循环检测
- **T（Tool）**：统一工具注册中心 + 四层校验链（权限/风险/白名单/限流）
- **C（Context）**：双画像 + 四级记忆 + 脱敏过滤 + Token 预算
- **S（State）**：任务状态快照、断点续跑、分布式锁、幂等
- **L（Lifecycle）**：钩子引擎，BEFORE/AFTER 各拦截点
- **V（Evaluation）**：五维指标上报 + 审计留痕
- **决策平面**：J1–J10 落地，对安全/审批闸门恒 advisory、只收紧不放松（P12）

### DDD 六边形分层（6 Maven 模块）

```
langur/
├── langur-common/           # 公共基础：SPI 契约（SecurityPolicySPI/DecisionEngineSPI 等）
├── langur-domain/           # 领域层：六组件 + 决策平面值对象 + RSI 引擎（零外部依赖，P1）
├── langur-application/      # 应用编排层：用例编排（AgentApplicationService 等）
├── langur-api/              # 接口层：REST 控制器（/api/agents、/api/v1/agent、流式）
├── langur-infrastructure/   # 基础设施层：LLM 网关、向量库、MCP/REST 工具、持久化、RSI 装配件
└── langur-start/            # 启动装配层：Spring 配置、application.yml
```

![DDD 六边形分层（6 Maven 模块）](doc/share/images/langur-ddd-hexagonal.png)

依赖方向（P2 单向无环）：`start → api → application → domain ← infrastructure`；`domain` 不 import Spring/infrastructure（ArchUnit 守护）。

### 决策平面（System-1 判定层）

![Jev 决策平面（System-1 判定层）](doc/share/images/langur-jev-decision-plane.png)

> 判定/生成分离（P12）：Jev 只产类型化判定（choice/probability/score + 置信度），对安全/审批闸门恒 advisory、只收紧不放松；10 个执行插入点详见 [架构设计](doc/design/Agent-Harness架构设计.md)。

### RSI 递归自我改进（R0–R5/R-G）

![RSI 递归自我改进（安全平面统辖）](doc/share/images/langur-rsi-self-improvement.png)

> Observe→Evaluate→Propose→Validate→Apply→Monitor 元循环；每个产物都是"候选提案"，经回放验证 + R-G 安全平面 + 灰度 + 高危人审才生效（P11/P12）。

---

## 里程碑

| 哲学 | 里程碑 | 内容 | 状态 |
|------|--------|------|------|
| **Harness** | H1–H14 + H11 / H12 | 六组件补全（Token 计量 / 语义 Embedding / PlanAndExecute / Redis 热层 / 可观测 / MCP / REST 工具 / Skill 编排 / Workflow+Hybrid / 安全补强）、统一模型网关（配置工厂 + GLM + 降级修复 + SecretResolver + Provider 熔断）、可插拔向量库（端口扩展 + ES/Milvus/pgvector + 原生混合检索）、依赖治理、多 Agent | ✅ 已完成 |
| **Jev** | J1–J10 | 决策平面（DecisionPort 契约 / Jev 后端 / 录制·缓存·阈值·驻留装饰链 / 十插入点） | ✅ 已完成 |
| **RSI** | R0–R5 / R-G | 回放验证 / 反思自检 / 记忆自蒸馏 / 技能自合成 / 安全平面 / 策略自优化 / 工具自扩展 | ✅ 已完成 |

完整设计见 [架构设计](doc/design/Agent-Harness架构设计.md)：三大架构哲学 + 实践方法论，RoadMap 已并入。

---

## 快速开始

### 环境要求

- **Java 17**（Lombok 在 JDK 22+ 会失效，须固定 JDK 17；`maven-compiler-plugin` 已加 `<release>17</release>` 守卫）
- Maven 3.8+

### 构建 & 运行

```bash
# 完整构建（6 模块）
mvn clean package -DskipTests

# 运行完整测试套件（JDK 17）
export JAVA_HOME=/path/to/jdk-17
mvn clean test

# 启动服务（默认 8081）
java -jar langur-start/target/langur.jar
```

服务默认启动在 `http://localhost:8081`，健康检查 `/actuator/health`，指标 `/actuator/prometheus`。

---

## API 接口

### 创建 Agent

```http
POST /api/agents
Content-Type: application/json

{
  "name": "my-agent",
  "description": "通用助手",
  "systemPrompt": "你是一个有用的助手",
  "model": "gpt-4o",
  "temperature": 0.7,
  "maxIterations": 10,
  "toolNames": ["http_call"]
}
```

### 执行 Agent（同步 / 异步 / 结构化消息）

```http
POST /api/agents/{agentId}/run
POST /api/agents/{agentId}/run/async
POST /api/v1/agent
POST /api/v1/agent/stream        # SSE 流式
```

结构化消息（多模态兼容入口）：

```json
{
  "userId": "u-001", "tenantId": "t-001", "sessionId": "s-001",
  "messageParts": [
    {"type": "text", "content": "请分析这张图"},
    {"type": "image", "mediaUrl": "https://example.com/demo.png"}
  ]
}
```

其他接口：`GET /api/agents`、`GET /api/agents/{id}`、`GET /api/agents/{id}/plan`、`GET /api/agents/{id}/runs`、`GET /api/agents/runs/{taskId}`、`DELETE /api/agents/{id}`。

---

## 扩展与集成

### 模型网关（H13）

配置化接入，新增 OpenAI 兼容厂商零 Java 代码：

```yaml
langur:
  llm:
    default-provider: openai
    providers:
      openai: { type: openai-compatible, base-url: https://api.openai.com/v1,
                api-key-ref: env:OPENAI_API_KEY, model: gpt-4o, model-prefixes: [gpt-, o1-, o3-] }
      glm:    { type: openai-compatible, base-url: https://open.bigmodel.cn/api/paas/v4,
                api-key-ref: env:GLM_API_KEY, model: glm-4-plus, model-prefixes: [glm-, chatglm-] }
    role-models: { REASONING: gpt-4o }
    fallback-chains: { gpt-4o: [deepseek-chat, qwen-max] }
    circuit-breaker: { enabled: true, threshold: 3, cooldown-seconds: 30 }
```

### 向量库（H14）

```yaml
langur:
  vector:
    store: memory            # memory | pgvector | elasticsearch | milvus
    hybrid: { enabled: false, mode: native, fusion: RRF }
```

### 决策平面 + RSI（默认关）

```yaml
langur:
  decision: { enabled: true, backend: typesafe, record: true }
  rsi:
    enabled: false            # RSI 总开关（默认关）
    reflection:    { enabled: false }
    distillation:  { enabled: false }
    synthesis:     { enabled: false }
    governance:    { enabled: false }
    optimization:  { enabled: false }
    extension:     { enabled: false }
```

### 自定义工具

继承 `Tool` 并注册进 `ToolRegistry`，或经 `@SkillDef` 声明编排技能、经 MCP/REST OpenAPI 自动发现接入。

---

## 文档

```
doc/design/   # 架构设计知识文档：Harness · Jev · RSI 三大架构哲学 + 实践方法论
doc/share/    # 公众号分享博文（含自包含 HTML）
```

---

## 相关项目

- [Skylark](https://github.com/Jashinck/Skylark) — 实时 AI 语音对话系统
- [BlueWhale](https://github.com/Jashinck/BlueWhale) — 蓝鲸记忆框架（与 Langur 配套）

---

## 许可证

Apache License 2.0
