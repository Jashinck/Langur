# Agent-Harness 架构设计 v1.0

> **文档性质**: 通用架构设计方案（General Architecture Blueprint）
> **版本**: v1.0 | **日期**: 2026-08
> **定位**: 框架级通用架构设计方案，不绑定任何具体业务场景
> **核心公式**: `Agent = LLM推理能力 + Harness确定性调度能力`

---

# 第一部分 · 总体架构设计（通用版）

## 目录

1. [框架定位与愿景](#1-框架定位与愿景)
2. [核心问题域：大模型生产落地六大缺陷](#2-核心问题域大模型生产落地六大缺陷)
3. [Harness 六组件标准架构](#3-harness-六组件标准架构)
4. [DDD 六模块分层架构](#4-ddd-六模块分层架构)
5. [三层混合 Runtime 架构](#5-三层混合-runtime-架构)
6. [LLM 模型矩阵](#6-llm-模型矩阵)
7. [统一工具体系与工具路由](#7-统一工具体系与工具路由)
8. [全链路执行时序](#8-全链路执行时序)
9. [安全合规架构](#9-安全合规架构)
10. [可观测与运维架构](#10-可观测与运维架构)
11. [SPI 扩展体系](#11-spi-扩展体系)
12. [存储分层设计](#12-存储分层设计)
13. [API 接入体系](#13-api-接入体系)
14. [技术选型](#14-技术选型)
15. [设计目标与度量标准](#15-设计目标与度量标准)

---

## 1. 框架定位与愿景

### 1.1 产品定位

Agent-Harness 是一套**企业级 Agent 通用脚手架框架**，作为大模型通往生产环境的**确定性调度底座**，赋能各业务线快速构建安全、可控、可观测的企业级 Agent 应用。

> Harness 是部署在**大模型推理层**与**外部业务环境**（工具/数据库/API/文件系统/用户会话）之间的**确定性调度中间件基础设施**。本质：大模型的操作系统与安全沙箱层。

### 1.2 两大强制隔离原则（不可违背）

| 原则 | 定义 | 工程约束 |
|------|------|----------|
| **模型与环境隔离** | LLM 无法直连任何外部资源 | 所有工具调用必须经 T 组件校验 + 沙箱执行 |
| **推理与治理隔离** | 模型仅负责推理决策 | 权限/合规/风控/审计全下沉 Harness，无需改 Prompt |

### 1.3 架构全景公式

```
Agent（六边形DDD架构） = LLM（模型矩阵） + Harness（六大控制单元）
```

- **Agent 部分**：API、Application、Domain、Infrastructure、Common、Start 六个 DDD 模块
- **LLM 部分**：按职责分工的模型矩阵（路由/向量化/重排/行动/推理/长上下文）
- **Harness 部分**：Context、Execution、ToolManage、StateMachine、Hooks、Metrics 六大控制单元

---

## 2. 核心问题域：大模型生产落地六大缺陷

大模型原生落地存在六大致命缺陷，Harness 以六组件逐一解决：

| # | 生产缺陷 | 风险表现 | Harness 组件 | 解决策略 |
|---|----------|----------|--------------|----------|
| 1 | 无流程强制管控 | 模型自由发挥、流程不可控 | **E** 执行循环器 | 三大 Runtime 范式 + 终止闸门 |
| 2 | 无工具安全风控 | 越权调用、注入攻击、资损 | **T** 工具注册中心 | 九元组注册 + 四层校验链 |
| 3 | 无结构化记忆治理 | 上下文污染、Token 失控、幻觉 | **C** 上下文管理器 | 四级分层记忆 + 双画像 |
| 4 | 无状态持久化容灾 | 崩溃丢失、无法续跑 | **S** 状态存储库 | 快照 + 断点续跑 + 回滚 |
| 5 | 无全链路安全拦截 | 敏感泄露、合规缺失 | **L** 生命周期钩子 | 12 拦截点 + 四层纵深防御 |
| 6 | 无可观测合规审计 | 黑盒运行、无法溯源 | **V** 评估观测接口 | 四维指标 + Trace + 审计 |

---

## 3. Harness 六组件标准架构

### 3.1 标准架构 H=(E,T,C,S,L,V)

```
┌───────────────────────────────────────────────────────────────────────┐
│                    L — Lifecycle Hooks (全流程贯穿)                     │
│          12个拦截点 · 四层纵深防御 · 非侵入 · 可动态启停                 │
├───────────────────────────────────────────────────────────────────────┤
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐     │
│  │    C     │────▶│    E     │────▶│    T     │────▶│    S     │     │
│  │ Context  │     │Execution │     │   Tool   │     │  State   │     │
│  │ Manager  │◀────│  Loop    │◀────│ Registry │     │  Store   │     │
│  └──────────┘     └──────────┘     └──────────┘     └──────────┘     │
│       └────────────────┴────────────────┴────────────────┘            │
│                                │                                      │
│                         ┌──────▼──────┐                               │
│                         │      V      │                               │
│                         │ Evaluation  │                               │
│                         └─────────────┘                               │
└───────────────────────────────────────────────────────────────────────┘
```

### 3.2 六组件职责定义

| 组件 | 全称 | 聚合根/核心服务 | 核心能力 |
|------|------|-----------------|----------|
| **E** | Execution Loop 执行循环器 | `ExecutionTask` / `ExecutionLoopService` | 三大范式运行、终止闸门、循环检测、容错降级 |
| **T** | Tool Registry 工具注册中心 | `ToolDefinitionEntity` | 九元组注册、四层校验链、统一路由调度 |
| **C** | Context Manager 上下文管理器 | `AgentContext` | 四级记忆、双画像、Token 治理、脱敏过滤 |
| **S** | State Store 状态存储库 | `TaskState` | 快照、断点续跑、事务回滚、分布式锁 |
| **L** | Lifecycle Hooks 生命周期钩子 | `LifecycleHookEngine` | 12 拦截点、四层防御、Hook 链中断/放行 |
| **V** | Evaluation Interface 评估观测 | `EvaluationService` | 四维指标、全链路 Trace、不可篡改审计 |

### 3.3 组件关键设计要点

**E 组件**：
- 运行范式：Workflow / ReAct / PlanAndExecute / Hybrid
- 终止闸门：maxRounds / maxTokens / maxTimeout / maxCallsPerRound 四维硬约束
- 容错机制：自动重试 → 熔断 → 降级 → 人工介入 → 终止
- 循环检测：连续 N 轮输出相似度超阈值则强制终止

**T 组件**：
- 九元组注册标准（缺一不可）：ID / 描述 / 入参 Schema / 出参 Schema / 权限 / 风险 / 白名单 / 限流 / 超时
- 校验链（责任链模式）：白名单 → Schema → 动态权限 → 沙箱

**C 组件**：
- 四级记忆：L1 瞬时（轮次销毁）→ L2 会话（摘要裁剪）→ L3 任务（快照绑定）→ L4 知识（向量检索）
- 双画像：UserProfile（权限/脱敏/偏好）+ TaskProfile（风险/工具链/Prompt 模板）
- Token 治理：预算分配制，防止上下文溢出

**S 组件**：
- 存储分层：内存（当前轮）→ Redis（热状态）→ MySQL（归档）→ 向量库（知识）
- 锁机制：Redis 分布式锁（互斥）+ 乐观锁 version（并发）
- 每轮执行后自动快照，支持回滚到任意步骤

**L 组件**：
- 12 拦截点：BEFORE/AFTER × 6 阶段（Context/Inference/ToolCall/StateSave/Terminate/Output）
- Hook 返回动作：CONTINUE / ABORT / SKIP / MODIFY

**V 组件**：
- 四维指标：模型层 + 调度层 + 工具层 + 安全层
- Trace：OpenTelemetry 标准，每请求唯一 TraceId 贯穿全链路
- 审计：不可篡改日志，checksum 校验，长周期留存

### 3.4 六组件协同闭环（标准执行时序）

```
环境状态采集
→ [C] 上下文加载(记忆+画像+脱敏过滤)
→ [E] 执行循环驱动模型推理决策
→ [T] 工具中心(权限校验+Schema校验+沙箱执行)
→ [S] 状态存储写入本轮执行快照与结果
→ [L] 生命周期钩子全节点安全拦截与合规植入
→ [V] 观测接口全量轨迹/指标/日志留存
→ [E] 判断任务终止条件 → 结束迭代 或 进入下一轮
```

---

## 4. DDD 六模块分层架构

### 4.1 模块定义与职责边界

框架采用六边形 DDD 架构，划分为六个独立 Maven Artifact，每个模块拥有独立根包，通过包路径实现编译期依赖隔离：

| 模块 | Artifact | 根包 | 核心职责 | 禁止事项 |
|------|----------|------|----------|----------|
| **common** | harness-agent-common | `*.common` | 枚举/异常/DTO/注解/SPI接口/工具类 | 禁止有状态逻辑、禁止 Spring 依赖 |
| **domain** | harness-agent-domain | `*.domain` | 六组件领域模型/服务/事件/仓储接口 | 禁止依赖任何外层模块及中间件 |
| **application** | harness-agent-application | `*.application` | 用例编排/事务协调/DTO转换/事件分发 | 禁止包含领域规则 |
| **infrastructure** | harness-agent-infrastructure | `*.infrastructure` | 仓储实现/LLM适配/中间件/沙箱 | 禁止包含业务规则 |
| **api** | harness-agent-api | `*.api` | REST/SSE协议适配/参数校验/限流 | 禁止包含编排逻辑 |
| **start** | harness-agent-start | `*.start` | 启动/自动装配/配置/健康检查 | 禁止包含业务逻辑 |

### 4.2 依赖关系（强制单向）

```
start → api → application → domain ← infrastructure
                              ↑
                        common (全模块依赖)
```

关键约束：
- domain 层仅允许依赖 common + lombok，零外部依赖
- infrastructure 通过实现 domain 定义的接口完成依赖倒置
- api 层仅允许调用 application 层服务，禁止直接调用 domain
- 对外协议入口统一收敛为 REST/SSE

### 4.3 六组件在 DDD 中的落位

| 组件 | Domain 层 | Infrastructure 层 |
|------|-----------|-------------------|
| E 执行循环 | `domain.harness.execution`（聚合根/值对象/循环服务/路由） | 默认实现 + LLM 适配器协同 |
| T 工具中心 | `domain.harness.tool`（定义实体/校验器/调度器/发现服务） | 本地执行器 + MCP + REST API 执行器 |
| C 上下文 | `domain.harness.context`（AgentContext/双画像/仓储接口） | 向量库实现 + 缓存 |
| S 状态存储 | `domain.harness.state`（TaskState/Snapshot） | MySQL/Redis/内存分层实现 |
| L 钩子 | `domain.harness.lifecycle`（Hook/引擎） | 安全策略实现 |
| V 观测 | `domain.harness.evaluation`（指标/Trace/审计模型） | OTel/Prometheus/MQ 上报 |

### 4.4 模块依赖治理（CI 强制检查）

| 规则 | 说明 |
|------|------|
| domain 禁止 import Spring | 领域层纯净性 |
| domain 禁止 import infrastructure | 依赖倒置 |
| api 禁止直接调用 domain 服务 | 必须经 application |
| common 禁止有状态 Bean | 纯工具/契约 |
| 循环依赖零容忍 | 编译期检测 |

---

## 5. 三层混合 Runtime 架构

### 5.1 分层职责

| 层级 | 范式 | 职责 | 控制权 | 适用场景 |
|------|------|------|--------|----------|
| 顶层 | Workflow | 锁定合规边界/审批/强约束 | Harness 全权 | 强合规流程、审批工单 |
| 中层 | PlanAndExecute | 全局任务拆解/进度管控/动态调优 | LLM 规划 + Harness 校验 | 复杂长链路任务 |
| 底层 | ReAct | 细粒度工具推理/局部动态交互 | LLM 自主 + Harness 兜底 | RAG/开放对话 |

### 5.2 范式对比矩阵

| 维度 | Workflow | ReAct | PlanAndExecute | 三层混合 |
|------|----------|-------|----------------|----------|
| 流程控制权 | Harness 全权 | LLM 自主 | LLM 规划 + Harness 校验 | 分层分权 |
| 动态适配 | 极低 | 极高 | 中高 | 全局最优 |
| 安全管控 | 极强 | 弱 | 中等 | 分层最强 |
| 资源溢出风险 | 无 | 高 | 中低 | 完全可控 |

### 5.3 路由策略与渐进演进

```
任务进入 → LayerRouter 判断:
  ├── 强合规/审批类 → 顶层 Workflow
  ├── 多步骤复杂任务 → 中层 PlanAndExecute
  ├── 单轮推理/开放问答 → 底层 ReAct
  └── 默认(未指定) → ReAct
```

演进路径：
- Phase 1：纯 Workflow/ReAct → 覆盖标准场景
- Phase 2：双模共存 → BizCode 路由
- Phase 3：三层混合架构 → 全场景最优适配

---

## 6. LLM 模型矩阵

框架按职责分工组织模型矩阵，而非单一模型承担所有职能：

| 模型角色 | 职责定位 | 典型用途 |
|----------|----------|----------|
| **M1 Local SLM** | 轻量路由 | 意图识别、任务分流、低成本预处理 |
| **M2 Embedding** | 记忆写入 | 文本向量化、知识库入库 |
| **M3 Reranking** | 检索去噪 | 向量检索结果重排序 |
| **M4 Action** | 多模态工具调用 | Function Call、工具参数生成 |
| **M5 Reasoning** | 深度思考 | 规划、自检质检、复杂推理 |
| **M6 Long-Context** | 海量吞吐 | 长文档处理、日志审计 |

**LLM 适配架构**：工厂 + 策略模式，支持多模型切换与降级（D6 决策）。所有模型调用经统一 `LlmGateway` 网关，支持同步/流式两种调用模式。

---

## 7. 统一工具体系与工具路由

### 7.1 五类工具来源

| 来源 | toolSource | 说明 |
|------|-----------|------|
| 本地工具 | `LOCAL` | 框架内置原子工具 |
| SPI 工具 | `SPI` | 业务方通过 SPI 注入的工具 |
| MCP 远程工具 | `MCP` | 通过 MCP 协议接入的外部工具服务器 |
| REST API 工具 | `REST_API` | 存量 HTTP API 零改造工具化 |
| Skill 高阶能力 | `SKILL` | 多工具/子流程/子 Agent 组合编排 |

### 7.2 T 组件统一路由全景

```
┌────────────────────────────────────────────────────────────────┐
│                       LLM 推理决策                              │
│  可见能力 = 本地Tools + MCP Tools + REST API Tools + Skills     │
└────────────────────────────┬───────────────────────────────────┘
                             │ tool_calls
                             ▼
┌────────────────────────────────────────────────────────────────┐
│                    T组件 统一路由                                │
│  toolSource判断:                                               │
│    LOCAL    → 本地ToolExecutor                                 │
│    SPI      → 业务SPI ToolExecutor                             │
│    MCP      → McpClientGateway.callTool()                      │
│    REST_API → RestApiToolGateway.execute()                     │
│    SKILL    → SkillOrchestrator.invoke()                       │
└────────────────────────────────────────────────────────────────┘
```

所有来源的工具统一注册到 T 组件，经同一套四层校验链管控，LLM 无感知差异。

### 7.3 MCP 协议集成

- **定位**：标准化工具集成协议层，扩展工具边界
- **传输支持**：STDIO / SSE / Streamable HTTP / WebSocket
- **生命周期**：启动连接 + 工具发现注册 → 运行期心跳 + 断线重连 + 热更新 → 优雅停机
- **工具 ID 规范**：`mcp:{serverName}:{toolName}`
- **容错**：单 Server 故障不影响其他 Server，降级标记不可用

### 7.4 REST API as Tool

- **设计理念**：任何符合 REST 规范的 HTTP API 均可注册为 LLM 可调用工具，零改造接入
- **三种注册方式**：OpenAPI Spec 自动发现（推荐）/ YAML 手动配置 / 注解声明
- **工具 ID 规范**：`api:{serviceName}:{operationId}`
- **凭证托管**：CredentialVault 统一管理，支持 BEARER / API_KEY / BASIC / OAUTH2
- **安全防护**：SSRF 防护（禁内网保留地址）+ 路径白名单 + Schema 强校验

### 7.5 Skill 编排

- **定义**：Skill = 可被 LLM 调用的高阶复合能力单元，内部编排多个 Tool 调用、子 Workflow、子 Agent
- **与 Tool 的区别**：Tool 是原子操作（单次调用、无状态）；Skill 是复合操作（多步编排、可含分支/循环/子 Agent）
- **步骤类型**：TOOL_CALL / SUB_WORKFLOW / SUB_AGENT / LLM_CALL / CONDITION
- **注册方式**：`@SkillDef` 注解声明或 `SkillProviderSPI` 编程式定义

---

## 8. 全链路执行时序

### 8.1 标准请求处理流

```
═══ 启动阶段(应用初始化时) ═══
[Start] → McpClientManager.init() → 连接MCP Server → tools/list发现 → 注册到[T]
        → SkillRegistry.init() → 扫描@SkillDef注解 → 注册所有Skill
        → RestApiToolAutoConfiguration → OpenAPI解析/手动配置 → 注册到[T]

═══ 请求处理阶段 ═══
Client → [API] 鉴权/限流/参数校验/TraceId生成
       → [APP] AgentAppService.dispatch()
       → [L] BEFORE_CONTEXT_ASSEMBLE Hook
       → [C] 上下文组装(并行: 画像+记忆+知识+工具列表+Skill列表)
       → [C] 脱敏过滤 + Token治理
       → [L] AFTER_CONTEXT_ASSEMBLE Hook
       → [S] 断点恢复检测(是否有未完成快照)
       → [E] 执行循环启动
           ┌─→ [L] BEFORE_INFERENCE Hook
           │   [E] LLM推理(通过LlmGateway, tools含全部来源工具)
           │   [L] AFTER_INFERENCE Hook
           │   [E] 解析LLM返回的tool_calls → [T]统一路由执行
           │   [L] BEFORE/AFTER_TOOL_CALL Hook
           │   [S] 本轮快照写入
           │   [E] 终止判断(闸门检测)
           └── 未终止 → 下一轮 / 已终止 → 退出循环
       → [V] 指标上报 + Trace结束
       → [L] BEFORE_OUTPUT Hook(输出合规)
       → [API] 响应返回
```

### 8.2 容错降级链路

| 异常场景 | 降级策略 |
|----------|----------|
| LLM 超时/不可用 | 兜底话术 / 切换备用模型 |
| 工具执行失败 | 重试 → 降级 → 兜底分支 |
| 状态丢失 | 从最近快照恢复 |
| 循环检测触发 | 强制终止 + 人工介入通知 |
| 安全拦截 | 阻断 + 告警 + 审计记录 |
| 上下文加载失败 | 最小化上下文继续执行 |
| 埋点/监控失败 | 静默丢弃（不影响主链路） |

---

## 9. 安全合规架构

### 9.1 五层纵深防御

```
Layer 1: 接入安全 — 身份鉴权/JWT/限流/黑名单/IP白名单
Layer 2: 输入安全 — 敏感词检测/Prompt注入拦截/长度校验
Layer 3: 执行安全 — 工具沙箱/权限校验/高危审批/越权阻断
Layer 4: 输出安全 — 内容审核/资损校验/涉密过滤/合规检测
Layer 5: 审计安全 — 全量留痕/不可篡改/合规归档/溯源查询
```

### 9.2 安全设计原则

| 原则 | 说明 |
|------|------|
| 零信任 | 所有工具调用默认不可信，必须经四层校验 |
| 最小权限 | 基于用户画像动态授予最小工具集 |
| 纵深防御 | 五层拦截，单点突破不致全局失守 |
| 审计全覆盖 | 所有操作不可篡改留痕 |
| 资损零容忍 | 金额相关操作多层校验 + 阻断机制 |

### 9.3 工具调用四层校验链（强制）

```
ToolCallRequest → [1]白名单准入(未注册不可见)
               → [2]Schema强校验(参数/注入/格式)
               → [3]动态权限(用户画像+风险等级)
               → [4]沙箱隔离执行(文件/网络/进程隔离)
               → ToolCallResult
```

---

## 10. 可观测与运维架构

### 10.1 四维指标体系

| 维度 | 核心指标 |
|------|----------|
| 模型层 | inputTokens / outputTokens / 推理耗时 / 幻觉率 / 重复率 |
| 调度层 | 总轮次 / 有效轮次 / 范式占比 / 终止原因 / 人工介入次数 |
| 工具层 | 调用成功率 / 超时率 / 重试率 / 风险工具调用记录 |
| 安全层 | 脱敏次数 / 拦截次数 / 越权尝试 / 审批记录 / 审计完整度 |

### 10.2 全链路 Trace

```
TraceId(全局唯一)
├── Span: API接入 (method/uri/status)
├── Span: 上下文构建 (loadTime/tokenCount)
├── Span: LLM推理 (model/tokens/latency)
├── Span: 工具调用 (toolId/status/elapsed)
├── Span: 状态写入 (snapshotId)
└── Span: 响应输出 (totalElapsed)
```

### 10.3 告警分级

| 级别 | 触发条件 | 响应 |
|------|----------|------|
| P0-致命 | 资损事件/数据泄露 | 立即阻断 + 人工介入 |
| P1-严重 | 成功率<95%/延迟>5s | 自动降级 + OnCall 通知 |
| P2-警告 | Token 超限/循环检测 | 自动终止 + 记录 |
| P3-提示 | 指标波动/容量预警 | 记录 + 日报汇总 |

---

## 11. SPI 扩展体系

### 11.1 七大 SPI 扩展点

| # | SPI 接口 | 职责 | 注册方式 |
|---|---------|------|----------|
| 1 | `BusinessExecutorSPI` | 业务节点执行逻辑 | @BizExecutor(bizCode) |
| 2 | `PromptTemplateSPI` | 业务专属 Prompt 构建 | @Component + getBizCode() |
| 3 | `ToolProviderSPI` | 业务工具注册 + 执行 | @ToolProvider(bizCode) |
| 4 | `SecurityPolicySPI` | 业务安全规则 | @Component + getBizCode() |
| 5 | `ContextEnricherSPI` | 业务上下文数据加载 | @Component + getOrder() |
| 6 | `DecisionEngineSPI` | 业务决策规则匹配 | @Component + getBizCode() |
| 7 | `OutputPostProcessorSPI` | 业务输出格式化 | @Component + getOrder() |

### 11.2 BizCode 路由机制

```
AgentRequest.bizCode → BizCodeRouter:
  ├── 匹配 BusinessExecutorSPI 实现
  ├── 匹配 ToolProviderSPI → 加载工具集
  ├── 匹配 PromptTemplateSPI → 构建 Prompt
  ├── 匹配 NodeConfig → 确定节点开关
  └── 匹配 RuntimeParadigm → 选择执行引擎
```

### 11.3 业务三步接入法

```
1. 定义 bizCode → 标识业务域
2. 注入工具 → 实现 ToolProviderSPI 或配置 MCP/REST API 工具
3. 调用 API → chat / task / stream 三入口
```

框架对扩展开放、对修改关闭（P5 原则），业务通过 SPI 注入，目标 ≤3 天完成新业务域对接。

---

## 12. 存储分层设计

### 12.1 四级存储分层

| 层级 | 介质 | 数据特征 | 用途 |
|------|------|----------|------|
| L1 | 内存 | 当前轮次热数据 | 执行循环中的瞬时状态 |
| L2 | Redis | 热状态（带 TTL） | 会话状态、分布式锁、限流计数 |
| L3 | MySQL | 持久归档 | 任务状态、快照、工具定义、审计日志 |
| L4 | 向量库（PostgreSQL + pgvector） | 静态可信知识 | 知识库语义检索、长期记忆存取 |

### 12.2 向量存储设计

- **引擎**：PostgreSQL + pgvector 扩展（`vector` 类型 + HNSW 索引 + cosine distance）
- **核心表**：
  - `t_vector_knowledge`：知识库向量表（namespace 多租户 + 1536 维向量 + JSONB 元数据）
  - `t_vector_memory`：长期记忆表（user 维度 + 记忆衰减管理 + UPSERT 模式）
- **独立数据源**：向量库与业务主库分离，独立 DataSource + JdbcTemplate
- **Embedding 流水线**：写入时向量化入库 → 检索时 cosine 相似度召回 → 可选重排去噪

### 12.3 核心业务表（MySQL）

| 表 | 职责 |
|----|------|
| `t_task_state` | 任务状态主表（状态机 + 乐观锁 + 分布式锁持有者） |
| `t_state_snapshot` | 执行快照（断点续跑 + 回滚） |
| `t_execution_round` | 执行轮次（推理数据 + 工具调用 + Token 消耗） |
| `t_tool_definition` | 工具定义（九元组 + 限流配置） |
| `t_tool_invocation` | 工具调用记录（全量留痕） |
| `t_audit_log` | 审计日志（不可篡改 + checksum） |
| `t_user_profile` | 用户画像（权限 + 脱敏开关 + 偏好） |

---

## 13. API 接入体系

### 13.1 三大入口

| 入口 | 端点 | 模式 | 适用场景 |
|------|------|------|----------|
| 同步对话 | `POST /api/v1/agent/chat` | 同步请求-响应 | 单轮问答、短任务 |
| 流式对话 | `POST /api/v1/agent/stream/chat` | SSE（message → summary → done） | 逐字流式输出 |
| 长任务流式 | `POST /api/v1/agent/stream/task` | SSE（accepted → progress → summary → done） | 多步规划长任务，默认 PLAN_AND_EXECUTE |

### 13.2 任务控制面

| 端点 | 职责 |
|------|------|
| `POST /api/v1/agent/task/create` | 创建任务 |
| `GET /api/v1/agent/task/{taskId}/status` | 查询任务状态 |
| `POST /api/v1/agent/task/{taskId}/cancel` | 取消任务 |
| `POST /api/v1/agent/task/{taskId}/retry` | 重试失败任务 |

### 13.3 API 治理链

```
请求 → 鉴权校验 → 租户校验 → 限流 → 幂等拦截 → TraceId生成 → 业务处理
```

- 治理头透传：可信 user/tenant/identity 注入请求上下文并覆盖业务入参
- 全局异常处理：统一 `Result<T>` 返回结构 + 语义化 HTTP 状态码
- 长任务进度总线：按 taskId 维护监听器注册表，先注册后执行，避免丢失早期进度事件

---

## 14. 技术选型

| 层面 | 选型 | 版本 |
|------|------|------|
| 语言 | Java | 17+ |
| 框架 | Spring Boot | 3.2.x |
| ORM | MyBatis-Plus | 3.5.x |
| 缓存 | Redis | 7.x+ |
| 业务数据库 | MySQL | 8.0+ |
| 向量库 | PostgreSQL + pgvector | PG 18 / pgvector 0.8+ |
| 消息队列 | RocketMQ | 5.x |
| 监控 | Prometheus + Grafana | latest |
| 链路追踪 | OpenTelemetry | latest |
| 构建 | Maven | 3.9+ |

### 关键架构决策记录（ADR 摘要）

| # | 决策 | 选择 | 原因 |
|---|------|------|------|
| D1 | 架构风格 | DDD 六模块 | 领域沉淀/业务隔离/可维护 |
| D2 | 六组件位置 | Domain 层 | 核心调度与业务无关 |
| D3 | Runtime | 三层混合 | 合规+规划+灵活全兼顾 |
| D4 | 扩展机制 | SPI | 零侵入/框架业务解耦 |
| D5 | 存储策略 | 四级分层 | 性能与持久化平衡 |
| D6 | LLM 适配 | 工厂+策略 | 多模型切换/降级 |
| D7 | 安全体系 | 五层纵深 | 单点不致全局失守 |
| D8 | 可观测 | OpenTelemetry | 行业标准/生态兼容 |
| D9 | 事件通信 | Spring Event + RocketMQ | 进程内+跨服务 |
| D10 | 流式响应 | SSE | 首字延迟低、协议简单 |
| D11 | MCP 集成 | Infrastructure 层 Client | 协议标准化/工具生态扩展 |
| D12 | Skill 编排 | Domain 定义 + Infra 实现 | 高阶复合能力/可组合 |
| D13 | REST API as Tool | OpenAPI 解析 + HTTP 执行器 | 存量 API 零改造接入 |

---

## 15. 设计目标与度量标准

| 目标 | 度量标准 |
|------|----------|
| 通用性 | 业务方通过 SPI 接入，≤3 天完成新业务域对接 |
| 生产级 | 安全/容错/可观测/合规能力出厂内置 |
| 高性能 | 首字延迟 <800ms，上下文构建 <100ms |
| 高可用 | 断点续跑，崩溃恢复 <5s，零任务丢失 |
| 可扩展 | 六组件独立启停，范式动态切换 |
| 可演进 | 单范式 → 混合架构平滑过渡，无破坏性变更 |

### 战略设计原则总览

| # | 原则 | 约束力 |
|---|------|--------|
| P1 | 领域核心不可侵犯（Domain 零外部依赖） | 强制 |
| P2 | 严格单向依赖 | 强制 |
| P3 | 依赖倒置 | 强制 |
| P4 | 六组件正交 | 强制 |
| P5 | SPI 开闭原则 | 强制 |
| P6 | 安全纵深防御 | 强制 |
| P7 | 可观测优先（无观测=未上线） | 强制 |
| P8 | 渐进式演进 | 推荐 |
| P9 | 配置化驱动 | 推荐 |
| P10 | 降级兜底（外部依赖失败主链路不中断） | 强制 |



---

# 第二部分 · 工程落地概览（框架能力详解）

## 一、工程背景与定位

### 1.1 项目起源

Langur 是一套**独立的企业级 Agent-Harness 通用框架**，专注于 **Agent 推理与执行**环节，将复杂的 Agent 编排能力从业务代码中剥离，形成可复用、可扩展的确定性调度底座。


### 1.2 命名寓意

叶猴（Langur）是一种轻盈、敏捷的灵长类动物，擅长在复杂环境中快速决策与行动——与 Agent 框架"感知-推理-执行"的核心循环高度契合。

### 1.3 核心能力定位

| 能力维度 | 说明 |
|---------|------|
| 🔄 **ReAct 循环** | Reasoning（推理）+ Acting（行动），最大迭代次数可配置，杜绝无限循环 |
| 🛠️ **工具调用** | 内置 HTTP 工具，支持自定义工具注册，Tools 与 Agent 解耦 |
| 📋 **多步规划** | PlanningDomainService 将复杂任务拆解为可追踪的步骤序列 |
| 🧾 **Plan 持久化** | 每次执行生成可查询的执行计划，支持调试与审计 |
| ⚙️ **异步执行** | 支持异步任务提交、状态轮询、按 Agent 维度查询任务列表 |
| 🤝 **多 Agent 协作** | AgentId 隔离机制，支持跨 Agent 任务分发与状态管理 |
| 📡 **REST API** | 标准 HTTP 接口，便于集成任意前端或上游系统 |
| 🧩 **结构化消息** | 支持 `messageParts`（text/image/audio/video/file/structured-data），为多模态演进预留入口 |

### 1.4 技术栈

- **语言**：Java 17
- **框架**：Spring Boot 3.2
- **构建**：Maven 多模块工程（6 个模块）
- **架构范式**：DDD（领域驱动设计）+ 六边形架构（Hexagonal Architecture）
- **HTTP 客户端**：Spring WebClient（响应式）
- **序列化**：Jackson ObjectMapper

### 1.5 设计文档与工程审计

从本次迭代开始，**技术设计文档与代码变更同步提交作为 PR 的组成部分**，存放在 `/design` 目录下。与既有的 `/doc`（技术分享与总结）分开：

| 目录 | 用途 | 内容类型 |
|------|------|---------|
| `/doc` | 技术分享与总结 | 框架介绍、设计方案回顾、最佳实践 |
| `/design` | PR 对应的设计文档 | 需求背景、方案对比、影响范围、验证策略 |

**设计文档规范**：每份 PR 设计文档应包含以下核心信息：

1. **背景与目标**：需求来源、解决的问题、预期收益
2. **方案对比**：备选方案分析、为什么选择最终方案
3. **影响范围**：涉及的模块、API 变化、性能影响
4. **验证策略**：如何测试、如何验证、风险评估

这种实践让关键决策可追溯，便于后续复盘与知识沉淀。

---

## 二、核心架构设计

### 2.1 整体架构分层

Langur 采用 **DDD 六边形架构**，将系统清晰拆分为 6 层，每层职责单一、依赖方向严格向内：

```
┌──────────────────────────────────────────────────────────────┐
│                        langur-api                            │
│          REST 接口层 · Controller · DTO · Assembler           │
└────────────────────────┬─────────────────────────────────────┘
                         │ 调用
┌────────────────────────▼─────────────────────────────────────┐
│                    langur-application                        │
│         应用编排层 · Use Case · Command · Assembler           │
└────────────────────────┬─────────────────────────────────────┘
                         │ 调用
┌────────────────────────▼─────────────────────────────────────┐
│                     langur-domain                            │
│   核心领域层 · 聚合根 · 领域服务 · 仓储接口 · 领域事件 · Port │
└────────────────────────┬─────────────────────────────────────┘
                         │ 实现（反向依赖）
┌────────────────────────▼─────────────────────────────────────┐
│                  langur-infrastructure                       │
│    基础设施层 · LLM 适配器 · 内置工具 · JPA/内存持久化        │
└──────────────────────────────────────────────────────────────┘

（横向贯穿各层）
┌──────────────────────────────────────────────────────────────┐
│                     langur-common                            │
│              公共基础 · 异常体系 · 基础类型                   │
└──────────────────────────────────────────────────────────────┘

（应用入口）
┌──────────────────────────────────────────────────────────────┐
│                      langur-start                            │
│              Spring Boot 启动器 · 配置装配                    │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 依赖关系图

```
langur-start
    ├── langur-api
    │     └── langur-application
    │           └── langur-domain
    │                 └── langur-common
    └── langur-infrastructure
          └── langur-domain
                └── langur-common
```

> **核心设计原则**：domain 层不依赖任何外部框架（零 Spring 依赖），基础设施层实现 domain 层定义的接口，依赖永远指向领域核心。

### 2.3 六边形架构中的端口与适配器

```
外部系统（HTTP 客户端、OpenAI API 等）
         ↕  
   [ 适配器 Adapter ]          ← infrastructure 层实现
         ↕  
   [ 端口 Port / Interface ]   ← domain 层定义
         ↕  
     领域核心（Domain）
         ↕  
   [ 端口 Port / Interface ]   ← domain 层定义（仓储、事件）
         ↕  
   [ 适配器 Adapter ]          ← infrastructure 层实现
         ↕  
  持久化存储（内存 / DB）
```

**内向端口（Driving Ports）**
- `AgentRepository` — Agent 聚合根持久化接口
- `PlanRepository` — Plan 持久化接口
- `AgentRunTaskRepository` — 异步任务持久化接口

**外向端口（Driven Ports）**
- `LLMPort` — LLM 调用抽象接口
- `ToolProvider` — 工具发现抽象接口

---

## 三、模块详细拆解

### 3.1 langur-common — 公共基础层

**职责**：提供跨模块共用的基础设施，包含异常体系和公共类型，所有模块均可依赖。

#### 异常体系

```
LangurException（基础运行时异常）
    ├── AgentNotFoundException     — Agent 不存在（404）
    ├── PlanNotFoundException      — 执行计划不存在（404）
    └── ToolNotFoundException      — 工具未注册（404）
```

**设计亮点**：自定义异常体系从根部统一封装，便于全局异常处理器（`@ControllerAdvice`）按类型精确拦截，返回标准化错误响应。

---

### 3.2 langur-domain — 核心领域层

**职责**：承载所有业务规则与领域逻辑，不依赖任何框架，是整个系统的心脏。

#### 3.2.1 领域模型（model）

##### Agent 聚合根

```java
// 包路径：domain.model.agent
Agent {
    AgentId id                           // 值对象，UUID 封装
    String name                          // Agent 名称
    String description                   // Agent 描述
    AgentConfig config                   // 配置值对象
    AgentStatus status                   // 状态枚举
    List<Tool> tools                     // 工具列表
    List<Map<String,String>> conversationHistory  // 对话历史
    int currentIteration                 // 当前迭代次数
    String lastError                     // 最后一次错误信息
    Instant createdAt / updatedAt        // 时间戳
}
```

**核心行为方法**：

| 方法 | 说明 |
|------|------|
| `Agent.create(config, tools)` | 工厂方法，创建新 Agent |
| `Agent.restore(...)` | 工厂方法，从存储恢复 Agent |
| `agent.markRunning()` | 状态流转 → RUNNING |
| `agent.markCompleted(answer)` | 状态流转 → COMPLETED，记录最终答案 |
| `agent.markFailed(reason)` | 状态流转 → FAILED，记录错误原因 |
| `agent.incrementIteration()` | 迭代计数 +1 |
| `agent.hasExceededMaxIterations()` | 判断是否超过最大迭代限制 |
| `agent.addConversationMessage(role, content)` | 追加对话历史 |
| `agent.getTools()` | 获取已注册工具列表 |

**AgentConfig 值对象**：

```java
AgentConfig {
    String systemPrompt      // 系统提示词
    String model             // 模型名称（如 gpt-4o）
    double temperature       // 采样温度
    int maxTokens            // 最大 token 数
    int maxIterations        // ReAct 最大迭代次数
}
```

**AgentStatus 枚举**：`IDLE → RUNNING → COMPLETED / FAILED`

##### Plan 与 PlanStep 模型

```java
// 包路径：domain.model.plan
Plan {
    String planId            // Plan ID
    AgentId agentId          // 归属 Agent
    String goal              // 规划目标
    List<PlanStep> steps     // 步骤列表
    Instant createdAt        // 创建时间
}

PlanStep {
    int stepIndex            // 步骤序号
    String description       // 步骤描述
    StepStatus status        // 步骤状态
    String observation       // 执行观测结果
    String failureReason     // 失败原因
}
```

**StepStatus 枚举**：`PENDING → IN_PROGRESS → COMPLETED / FAILED`

**PlanStep 不可变更新方法**：
- `step.withObservation(observation)` — 返回携带观测结果的新实例
- `step.withFailure(reason)` — 返回携带失败原因的新实例

##### AgentRunTask 异步任务模型

```java
// 包路径：domain.model.execution
AgentRunTask {
    String taskId            // 任务 ID（UUID）
    AgentId agentId          // 归属 Agent
    String userMessage       // 用户输入
    RunTaskStatus status     // 任务状态
    String result            // 最终结果
    String lastError         // 错误信息
    Instant createdAt / updatedAt
}
```

**RunTaskStatus 枚举**：`PENDING → RUNNING → COMPLETED / FAILED`

##### Tool 抽象模型

```java
// 包路径：domain.model.tool
abstract Tool {
    ToolDefinition definition    // 工具定义（抽象描述）
    abstract ToolResult execute(Map<String, Object> parameters)
}

ToolDefinition {
    String name              // 工具名称（唯一标识）
    String description       // 工具描述（LLM 感知）
    Map<String, Object> parametersSchema  // JSON Schema 参数描述
}

ToolResult {
    boolean success          // 是否成功
    String content           // 结果内容
    String errorMessage      // 错误信息（失败时）
    // 工厂方法
    static success(content)
    static failure(errorMessage)
}
```

##### MessagePartType 多模态消息类型

```java
// 包路径：domain.model.message
enum MessagePartType {
    TEXT, IMAGE, AUDIO, VIDEO, FILE, STRUCTURED_DATA
}
```

> 多模态消息类型枚举为未来图像、语音、视频输入的扩展预留了完整的类型空间。

#### 3.2.2 领域服务（service）

##### AgentDomainService — ReAct 核心调度

**职责**：封装单步 ReAct 执行逻辑（Reason → Act → Observe），是整个 Agent 推理循环的核心。

```java
AgentDomainService {
    // 执行单步 ReAct：调用 LLM → 解析决策 → 执行工具 → 更新计划
    String executeReActStep(Agent agent, Plan plan)
    
    // 发布领域事件
    void publishAgentCreatedEvent(Agent agent)
    void publishAgentExecutedEvent(Agent agent, String result)
}
```

**ReAct 单步执行内部流程**：

```
1. agent.incrementIteration()
2. LLMPort.decide(systemPrompt, conversationHistory, availableTools)
3. 解析 LLMDecision：
   ├── isFinalAnswer=true → agent.markCompleted(answer), return answer
   └── isFinalAnswer=false（工具调用）
         ├── 从 agent.tools 中查找目标工具
         ├── tool.execute(parameters) → ToolResult
         ├── 更新 PlanStep（withObservation 或 withFailure）
         ├── agent.addConversationMessage("tool", observation)
         └── return null（继续循环）
```

##### PlanningDomainService — 任务拆解规划

**职责**：基于 LLM 的完成能力（completion），将自然语言目标拆解为结构化步骤序列。

```java
PlanningDomainService {
    // 调用 LLM 将 goal 拆解为多步计划
    Plan createPlan(Agent agent, String goal)
}
```

#### 3.2.3 仓储接口（repository）

```java
AgentRepository {
    void save(Agent agent)
    Optional<Agent> findById(AgentId id)
    List<Agent> findAll()
    void delete(AgentId id)
}

PlanRepository {
    void save(Plan plan)
    Optional<Plan> findByAgentId(AgentId agentId)
}

AgentRunTaskRepository {
    void save(AgentRunTask task)
    Optional<AgentRunTask> findById(String taskId)
    List<AgentRunTask> findByAgentId(AgentId agentId)
}
```

#### 3.2.4 LLM 端口（port）

```java
// 包路径：domain.port（或 infrastructure.llm）
interface LLMPort {
    // ReAct 决策：返回工具调用指令或最终答案
    LLMDecision decide(String systemPrompt, 
                       List<Map<String,String>> conversationHistory,
                       List<Tool> availableTools)
    
    // 纯文本完成：用于规划拆解等场景
    String complete(String systemPrompt, String userMessage)
}
```

**LLMDecision 值对象**：

```java
LLMDecision {
    boolean isFinalAnswer    // true=最终答案，false=工具调用
    String answer            // 最终答案（isFinalAnswer=true 时有效）
    String toolName          // 工具名称（isFinalAnswer=false 时有效）
    Map<String,Object> toolParameters  // 工具参数
    
    // 工厂方法
    static finalAnswer(String answer)
    static toolCall(String toolName, Map<String,Object> params)
}
```

#### 3.2.5 领域事件（event）

```java
AgentCreatedEvent {
    AgentId agentId
    String agentName
    Instant occurredAt
}

AgentExecutedEvent {
    AgentId agentId
    String result
    int iterationsUsed
    Instant occurredAt
}
```

---

### 3.3 langur-application — 应用编排层

**职责**：编排领域服务，实现完整业务用例（Use Case），不含任何领域规则，仅做流程调度。

#### 3.3.1 应用服务

##### AgentApplicationService — 核心用例服务

```java
AgentApplicationService {
    // 用例1：创建 Agent
    AgentResult createAgent(CreateAgentCommand command)
    
    // 用例2：同步执行 Agent（ReAct 完整循环）
    AgentResult runAgent(RunAgentCommand command)
    
    // 用例3：查询 Agent 详情
    AgentResult getAgent(String agentId)
    
    // 用例4：查询所有 Agent
    List<AgentResult> listAgents()
    
    // 用例5：查询最近一次执行计划
    Optional<PlanResult> getLatestPlan(String agentId)
    
    // 用例6：删除 Agent
    void deleteAgent(String agentId)
}
```

**runAgent 完整 ReAct 循环实现**：

```java
// 伪代码
while (finalAnswer == null && !agent.hasExceededMaxIterations()) {
    finalAnswer = agentDomainService.executeReActStep(agent, plan);
}
if (finalAnswer == null) agent.markFailed("超过最大迭代次数");
planRepository.save(plan);
agentRepository.save(agent);
```

##### AgentRunTaskApplicationService — 异步任务管理

```java
AgentRunTaskApplicationService {
    // 提交异步执行任务，立即返回 taskId
    RunTaskResult startAsyncRun(RunAgentCommand command)
    
    // 查询任务状态
    RunTaskResult getTaskStatus(String taskId)
    
    // 查询 Agent 下所有任务
    List<RunTaskResult> listTasksByAgent(String agentId)
}
```

**异步执行内部机制**：
- 使用 `ExecutorService`（默认 4 线程池）提交任务
- 任务状态流转：`PENDING → RUNNING → COMPLETED/FAILED`
- 支持 `graceful shutdown`（超时等待线程池结束）

##### ToolRegistryService — 工具注册管理

```java
ToolRegistryService {
    // 根据工具名称列表获取 Tool 对象
    List<Tool> getToolsByNames(List<String> toolNames)
    
    // 获取所有已注册工具
    List<Tool> getAllTools()
}
```

#### 3.3.2 命令对象（Command）

```java
CreateAgentCommand {
    String name
    String description
    String systemPrompt
    String model
    double temperature
    int maxTokens
    int maxIterations
    List<String> toolNames   // 按名称指定工具
}

RunAgentCommand {
    String agentId
    String userMessage                   // 简单文本输入
    String userId / tenantId / sessionId // 多租户上下文
    List<MessagePartInput> messageParts  // 多模态结构化输入
}

MessagePartInput {
    MessagePartType type
    String content       // TEXT 类型内容
    String mediaUrl      // 媒体类型 URL
    Object data          // STRUCTURED_DATA 内容
}
```

#### 3.3.3 结果 DTO（Result）

```java
AgentResult { id, name, description, status, config, lastError, createdAt, updatedAt }
PlanResult  { planId, agentId, goal, steps: List<PlanStepResult> }
PlanStepResult { stepIndex, description, status, observation, failureReason }
RunTaskResult { taskId, agentId, status, result, lastError, createdAt, updatedAt }
```

#### 3.3.4 装配器（Assembler）

```java
AgentAssembler {
    AgentResult toResult(Agent agent)     // Domain → App DTO
}
```

---

### 3.4 langur-infrastructure — 基础设施层

**职责**：实现 domain 层定义的所有接口（Port/Repository），对接外部系统（OpenAI）和存储（内存）。

#### 3.4.1 LLM 适配器

##### OpenAILLMAdapter

```java
// 实现 LLMPort，对接 OpenAI Chat Completions API
OpenAILLMAdapter {
    // 配置项（通过 application.yml 注入）
    String baseUrl        // API Base URL
    String apiKey         // API Key
    String defaultModel   // 默认模型
    WebClient webClient   // 响应式 HTTP 客户端
    
    // decide()：将 Agent 的对话历史 + 工具列表转换为 OpenAI tool_calls 格式
    //           解析响应：function_call → toolCall，content → finalAnswer
    LLMDecision decide(...)
    
    // complete()：直接调用 Chat Completions（无工具），用于规划拆解
    String complete(...)
}
```

**OpenAI 工具调用格式映射**：

```
Domain Tool                    →   OpenAI Tool Format
─────────────────────────────────────────────────────
ToolDefinition.name            →   function.name
ToolDefinition.description     →   function.description
ToolDefinition.parametersSchema →  function.parameters (JSON Schema)
```

#### 3.4.2 内置工具

##### HttpCallTool — HTTP 请求工具

```java
HttpCallTool extends Tool {
    // 工具名称：http_call
    // 描述：执行 HTTP 请求，支持 GET/POST
    
    // 参数 Schema：
    //   url (string, required)     目标 URL
    //   method (string)            HTTP 方法，默认 GET
    //   body (object)              请求体（POST 时使用）
    //   headers (object)           自定义请求头
    
    ToolResult execute(Map<String, Object> parameters)
    // 使用 WebClient 发送请求，返回响应体字符串
    // 失败时返回 ToolResult.failure(errorMessage)
}
```

##### BuiltinToolRegistry — 内置工具注册表

```java
BuiltinToolRegistry implements ToolProvider {
    // 使用 ConcurrentHashMap 存储已注册工具
    // 自动注册 HttpCallTool
    
    List<Tool> getTools()                  // 获取所有内置工具
    Optional<Tool> findByName(String name) // 按名称查找工具
}
```

#### 3.4.3 持久化实现

Langur 提供两种持久化实现，通过 Repository 接口完全解耦，支持灵活切换。

##### 3.4.3.1 内存实现

三个仓储实现均使用 `ConcurrentHashMap` 保证线程安全，适合开发测试场景：

```java
// 按 AgentId 存储 Agent 对象
InMemoryAgentRepository implements AgentRepository {
    ConcurrentHashMap<String, Agent> store
}

// 按 AgentId 存储最新 Plan（一个 Agent 保存最近一次计划）
InMemoryPlanRepository implements PlanRepository {
    ConcurrentHashMap<String, Plan> store
}

// 按 taskId 存储 AgentRunTask，同时维护 agentId → taskIds 的索引
InMemoryAgentRunTaskRepository implements AgentRunTaskRepository {
    ConcurrentHashMap<String, AgentRunTask> taskStore
    ConcurrentHashMap<String, List<String>> agentTaskIndex
}
```

> **特点**：快速启动、零依赖，但服务重启数据丢失。

##### 3.4.3.2 JPA 实现

通过 Spring Data JPA 实现，支持 MySQL/PostgreSQL 等关系型数据库，生产级持久化能力：

```java
// JPA 实体类（自动ORM映射）
AgentDO (id, agentName, createdTime, updatedTime, conversationHistory, iterationCount, status, configuration)
PlanDO (id, agentId, createTime, finishTime, planSteps)
AgentRunTaskDO (id, agentId, status, error, result, createdTime, updatedTime)

// JPA 仓储接口（Spring Data 自动实现）
AgentJpaRepository extends JpaRepository<AgentDO, String>
PlanJpaRepository extends JpaRepository<PlanDO, String> with custom findByAgentId()
AgentRunTaskJpaRepository extends JpaRepository<AgentRunTaskDO, String> with custom findByAgentId()

// 适配器层（自动进行 DO ↔ Domain 转换）
JpaAgentRepository implements AgentRepository {
    // 自动映射 Agent ↔ AgentDO
    AgentEntity.from(agent)  // Domain → DO
    agent.restore(agentDO)   // DO → Domain
}
```

> **特点**：
> - 支持 MySQL、PostgreSQL、H2 等数据库
> - 自动化事务管理，ACID 保证
> - 数据库升级与迁移通过 Liquibase/Flyway
> - 支持查询优化与索引管理
> - 线程安全保障通过 JPA 框架

> **线程安全保障**：内存实现使用 `ConcurrentHashMap`，`AgentRunTask` 的异步更新通过仓储接口统一修改。JPA 实现通过数据库事务隔离级别保证并发安全。

---

### 3.5 langur-api — 接口暴露层

**职责**：将应用层能力以 RESTful HTTP 接口形式暴露，处理 HTTP 协议细节，与应用层通过 DTO 解耦。

#### 3.5.1 AgentController — 核心控制器

```java
@RestController
@RequestMapping("/api/agents")
AgentController {
    POST   /                        createAgent(CreateAgentRequest)
    GET    /                        listAgents()
    GET    /{agentId}               getAgent(agentId)
    DELETE /{agentId}               deleteAgent(agentId)
    POST   /{agentId}/run           runAgent(agentId, RunAgentRequest)
    POST   /{agentId}/run/async     runAgentAsync(agentId, RunAgentRequest)
    GET    /{agentId}/plan          getLatestPlan(agentId)
    GET    /{agentId}/runs          listAgentTasks(agentId)
    GET    /runs/{taskId}           getTaskStatus(taskId)
}
```

#### 3.5.2 请求/响应 DTO

```java
// 请求 DTO
CreateAgentRequest { name, description, systemPrompt, model, temperature, maxTokens, maxIterations, toolNames }
RunAgentRequest    { userMessage, userId, tenantId, sessionId, messageParts: List<MessagePartRequest> }
MessagePartRequest { type, content, mediaUrl, data }

// 响应 DTO
AgentResponse    { id, name, description, status, config, lastError, createdAt, updatedAt }
PlanResponse     { planId, agentId, goal, steps: List<PlanStepResponse> }
PlanStepResponse { stepIndex, description, status, observation, failureReason }
RunTaskResponse  { taskId, agentId, status, result, lastError, createdAt, updatedAt }
```

#### 3.5.3 AgentApiAssembler — API 装配器

```java
AgentApiAssembler {
    AgentResponse   toResponse(AgentResult result)
    PlanResponse    toPlanResponse(PlanResult result)
    RunTaskResponse toRunTaskResponse(RunTaskResult result)
    
    CreateAgentCommand toCommand(CreateAgentRequest request)
    RunAgentCommand    toCommand(String agentId, RunAgentRequest request)
}
```

**双层 Assembler 设计**：

```
API Layer:  Request  →  AgentApiAssembler  →  Command
App Layer:  Domain   →  AgentAssembler     →  Result
API Layer:  Result   →  AgentApiAssembler  →  Response
```

这种双层转换彻底隔离了 HTTP 协议变化（API 层）和业务模型变化（Domain 层），每层只感知相邻层的 DTO。

---

### 3.6 langur-start — 启动装配层

**职责**：Spring Boot 应用启动入口，统一装配所有模块的 Bean。

#### 关键配置项（application.yml）

```yaml
server:
  port: 8081                        # 服务端口

langur:
  llm:
    base-url: https://api.openai.com  # LLM API 地址（可替换）
    api-key: ${LANGUR_LLM_API_KEY}    # API Key（从环境变量注入）
    model: gpt-4o                     # 默认模型
  run-task:
    executor-size: 4                  # 异步任务线程池大小
```

#### 启动命令

```bash
# 构建
mvn clean package -DskipTests

# 运行（需设置 LLM API Key）
export LANGUR_LLM_API_KEY=your_key
java -jar langur-start/target/langur.jar
```

---

## 四、关键设计模式

### 4.1 六边形架构（端口与适配器）

Domain 层只定义 **Port 接口**（LLMPort、ToolProvider、Repository），Infrastructure 层实现这些接口作为 **Adapter**。Domain 层对外部系统（LLM API、数据库）一无所知，完全可测试、可替换。

### 4.2 聚合根模式（Aggregate Root）

`Agent` 是整个框架的核心聚合根，所有与 Agent 相关的状态变更（运行状态、对话历史、迭代计数）都通过 Agent 实体的方法进行，外部不直接修改 Agent 的内部状态。

### 4.3 工厂方法模式（Factory Method）

```java
Agent.create(config, tools)    // 创建新 Agent
Agent.restore(...)             // 从存储恢复
LLMDecision.finalAnswer(...)   // 创建最终答案决策
LLMDecision.toolCall(...)      // 创建工具调用决策
ToolResult.success(...)        // 创建成功结果
ToolResult.failure(...)        // 创建失败结果
```

工厂方法让对象创建语义更清晰，并封装了内部构建细节。

### 4.4 策略模式（Strategy）

- **LLM 策略**：只需实现 `LLMPort` 即可无缝替换 OpenAI → DeepSeek → Qwen 等任意 LLM
- **工具策略**：只需实现 `ToolProvider` 即可无缝扩展工具集

### 4.5 命令模式（Command Pattern）

所有应用层入口通过 `Command` 对象封装请求（`CreateAgentCommand`、`RunAgentCommand`），实现输入验证、审计日志和未来命令总线扩展的能力。

### 4.6 不可变值对象（Immutable Value Object）

`PlanStep` 的状态更新通过 `withObservation()`、`withFailure()` 返回新实例，天然线程安全，避免共享可变状态。

### 4.7 仓储模式（Repository Pattern）

仓储接口定义在 Domain 层，实现在 Infrastructure 层。当前为内存实现，未来可无缝切换为 JPA/MongoDB 实现，Domain 层代码零修改。

### 4.8 工程审计与决策追溯

设计文档与代码变更同步提交在同一 PR 中，确保：

- **决策可追溯**：每次重要变更都伴随设计文档，记录 Why（为什么）而非仅记录 What（做了什么）
- **复盘高效**：后续维护者通过 PR 历史即可理解演进脉络，降低上手成本
- **知识沉淀**：设计文档进入 `/design` 目录，积累成团队知识库

---

## 五、核心执行流程

### 5.1 同步执行完整流程（ReAct 循环）

```
POST /api/agents/{agentId}/run
        │
        ▼
AgentController.runAgent()
        │
        ▼
AgentApiAssembler.toCommand()  →  RunAgentCommand
        │
        ▼
AgentApplicationService.runAgent()
   ├── AgentRepository.findById()  →  Agent
   ├── agent.markRunning()
   ├── PlanningDomainService.createPlan()  →  Plan
   │      └── LLMPort.complete(goal)  →  解析步骤 → Plan
   │
   └── ReAct 循环（while !finalAnswer && !maxIterations）
          │
          ▼
      AgentDomainService.executeReActStep(agent, plan)
          ├── agent.incrementIteration()
          ├── LLMPort.decide(systemPrompt, history, tools)
          │      └── OpenAI Chat Completions API
          │
          ├── [最终答案] → agent.markCompleted(answer) → return answer
          │
          └── [工具调用]
                 ├── 查找 Tool → tool.execute(params) → ToolResult
                 ├── plan.step.withObservation(result)
                 ├── agent.addConversationMessage("tool", result)
                 └── return null（继续循环）
        │
        ▼
   PlanRepository.save(plan)
   AgentRepository.save(agent)
        │
        ▼
AgentAssembler.toResult() → AgentResult
AgentApiAssembler.toResponse() → AgentResponse (200 OK)
```

### 5.2 异步执行流程

```
POST /api/agents/{agentId}/run/async
        │
        ▼
AgentRunTaskApplicationService.startAsyncRun()
   ├── AgentRunTask.create()  →  status=PENDING
   ├── AgentRunTaskRepository.save(task)
   └── ExecutorService.submit(task)
        │  立即返回 202 Accepted + taskId
        │
        ▼ [线程池中异步执行]
   task.markRunning()
   AgentApplicationService.runAgent()  [同同步流程]
   task.markCompleted(result) 或 task.markFailed(error)
   AgentRunTaskRepository.save(task)
        │
        ▼ [客户端轮询]
GET /api/agents/runs/{taskId}
   AgentRunTaskRepository.findById(taskId)  →  RunTaskResponse
```

---

## 六、扩展能力全景

### 6.1 接入自定义 LLM 与多模型支持

Langur 内置支持多种 LLM 提供商，通过实现 `LLMPort` 接口可轻松扩展任意 LLM。

#### 6.1.1 内置 LLM 适配器

| 提供商 | 适配器类 | 特点 | 模型示例 |
|------|---------|------|---------|
| **OpenAI** | `OpenAILLMAdapter` | 业界标准，功能完整 | GPT-4o, GPT-4, GPT-3.5 |
| **Claude** | `ClaudeLLMAdapter` | 推理能力强，上下文长 | Claude 3 Opus/Sonnet/Haiku |
| **Gemini** | `GeminiLLMAdapter` | 多模态支持，性能优异 | Gemini Pro, Gemini Pro Vision |
| **DeepSeek** | `DeepSeekLLMAdapter` | 兼容 OpenAI 协议，国内优化 | DeepSeek LLM |
| **Qwen** | `QwenLLMAdapter` | 阿里云通义千问，中文优化 | Qwen-Max, Qwen-Plus |

#### 6.1.2 配置与切换

```yaml
# application.yml
langur:
  llm:
    default: openai           # 默认 LLM 提供商
    providers:
      openai:
        enabled: true
        api-key: ${OPENAI_API_KEY}
        base-url: https://api.openai.com/v1
        model: gpt-4o
      claude:
        enabled: true
        api-key: ${CLAUDE_API_KEY}
        model: claude-3-opus-20240229
      gemini:
        enabled: true
        api-key: ${GEMINI_API_KEY}
        model: gemini-pro
      deepseek:
        enabled: false
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com/v1
      qwen:
        enabled: false
        api-key: ${QWEN_API_KEY}
```

#### 6.1.3 LLM 路由与动态选择

通过 `LLMRouter` 和 `ModelRoutableLLMPort` 支持按任务自动选择合适的 LLM：

```java
// LLMRouter：统一的 LLM 路由管理器
LLMRouter {
    LLMPort getLLMForTask(String agentId, String taskType)  // 按任务类型路由
    LLMPort getDefaultLLM()                                  // 获取默认 LLM
    Map<String, LLMPort> getAllAvailableLLMs()               // 列举所有可用 LLM
}

// ModelRoutableLLMPort：支持模型路由的接口
ModelRoutableLLMPort extends LLMPort {
    String getModelName()      // 获取模型名称
    String getProviderName()   // 获取提供商名称
}
```

**应用场景示例**：

```java
// 在 Agent 初始化时配置模型路由策略
Agent agent = Agent.create(config, tools);
agent.setModelRoutingStrategy(new ModelRoutingStrategy() {
    @Override
    public LLMPort selectModel(String taskType) {
        return switch(taskType) {
            case "reasoning" -> llmRouter.getLLM("claude");        // 推理用 Claude
            case "coding" -> llmRouter.getLLM("openai");           // 编程用 GPT-4
            case "chinese_understanding" -> llmRouter.getLLM("qwen"); // 中文用通义千问
            case "multimodal" -> llmRouter.getLLM("gemini");       // 多模态用 Gemini
            default -> llmRouter.getDefaultLLM();
        };
    }
});
```

#### 6.1.4 自定义 LLM 适配器

实现 `ModelRoutableLLMPort` 接口，标注 `@Component` 即可无缝集成：

```java
@Component
public class CustomLLMAdapter implements ModelRoutableLLMPort {
    @Override
    public LLMDecision decide(String systemPrompt,
                              List<Map<String, String>> history,
                              List<Tool> tools) {
        // 调用你的 LLM API
        // 返回 LLMDecision（最终答案或工具调用）
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        // 调用你的 LLM API 的完成接口
    }

    @Override
    public String getModelName() {
        return "custom-model-v1";
    }

    @Override
    public String getProviderName() {
        return "custom-provider";
    }
}
```

#### 6.1.5 LLM 路由实现机制

```java
@Component
public class LLMRouter {
    @Autowired
    private Map<String, ModelRoutableLLMPort> llmAdapters;  // 自动注入所有 LLM 适配器
    
    @Autowired
    private LlmProperties config;  // 从配置文件读取默认和启用的 LLM

    public LLMPort getLLMForTask(String agentId, String taskType) {
        // 优先按任务类型查询，找不到则使用默认
        ModelRoutableLLMPort llm = llmAdapters.get(taskType);
        return llm != null ? llm : getDefaultLLM();
    }

    public LLMPort getDefaultLLM() {
        String defaultProvider = config.getDefault();  // 从配置读取
        return llmAdapters.get(defaultProvider);
    }

    public Map<String, LLMPort> getAllAvailableLLMs() {
        return llmAdapters.entrySet().stream()
                .filter(e -> isEnabled(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean isEnabled(String provider) {
        return config.getProviders().get(provider).isEnabled();
    }
}
```

### 6.2 注册自定义工具

继承 `Tool` 抽象类，在 `BuiltinToolRegistry` 中注册：

```java
public class WeatherTool extends Tool {
    public WeatherTool() {
        super(ToolDefinition.of(
            "weather_query",
            "查询指定城市的天气信息",
            Map.of(
                "city", Map.of("type", "string", "description", "城市名称")
            )
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String city = (String) parameters.get("city");
        // 调用天气 API
        return ToolResult.success("北京今天晴，25°C");
    }
}
```

### 6.3 性能优化——批量工具再水合

#### 问题背景

在 Agent 恢复时，需要根据保存的工具名称列表重新加载对应的 Tool 对象。之前的实现在循环中逐个查询工具注册表，导致 O(n*m) 的时间复杂度（n 个工具名，m 次查询）。

#### 优化方案

引入**批量再水合**机制，在单次调用中一次性加载所有工具：

```java
// 优化前：O(n*m) 复杂度
List<Tool> tools = new ArrayList<>();
for (String toolName : toolNames) {
    tools.add(toolRegistry.findByName(toolName));  // 每次都遍历一次
}

// 优化后：O(n+m) 复杂度
List<Tool> tools = toolRegistry.getToolsByNames(toolNames);
// 内部实现：先构建工具名 → Tool 的 HashMap（O(m)），然后批量查询（O(n)）

public List<Tool> getToolsByNames(List<String> toolNames) {
    // 先遍历仓储一次，构建 name → Tool 的 Map
    Map<String, Tool> toolMap = new HashMap<>();
    for (Tool tool : getAllTools()) {
        toolMap.put(tool.getName(), tool);
    }
    
    // 然后按照 toolNames 的顺序批量取值
    return toolNames.stream()
        .map(toolMap::get)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}
```

#### 性能收益

- **Agent 创建**：工具列表越多，收益越明显（10 个工具约快 2-3 倍）
- **Agent 恢复**：从持久化存储恢复时立即生效
- **内存占用**：无额外内存开销，仅优化查询顺序

### 6.4 数据库持久化集成

#### 6.4.1 从内存迁移到 JPA 数据库实现

实现仓储接口，替换内存实现为数据库实现，**无需修改 Domain 层代码**：

```java
// 方式一：通过 @Primary 注解自动切换
@Repository
@Primary  // 覆盖内存实现，Spring 优先注入此实现
public class JpaAgentRepository implements AgentRepository {
    @Autowired
    private AgentJpaRepository agentDataRepository;  // Spring Data JPA 自动实现的 CRUD repository

    @Override
    public void save(Agent agent) {
        // 自动将 Domain 对象转换为数据库实体
        AgentDO agentDO = AgentDO.from(agent);
        agentDataRepository.save(agentDO);
    }

    @Override
    public Optional<Agent> findById(String id) {
        return agentDataRepository.findById(id)
                .map(AgentDO::toDomain);  // DO → Domain
    }
}
```

#### 6.4.2 数据库配置

在 `application.yml` 中配置数据库连接：

```yaml
spring:
  datasource:
    # MySQL 示例
    url: jdbc:mysql://localhost:3306/langur?useSSL=false&serverTimezone=UTC
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
    # 或 PostgreSQL
    # url: jdbc:postgresql://localhost:5432/langur
    # driver-class-name: org.postgresql.Driver
  
  jpa:
    hibernate:
      ddl-auto: validate  # 生产环境使用 validate，开发使用 create-drop
    database-platform: org.hibernate.dialect.MySQL8Dialect  # 或 PostgreSQL10Dialect
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
```

#### 6.4.3 JPA 实体设计

三个核心 JPA 实体自动处理 Domain 模型持久化：

```java
@Entity
@Table(name = "t_agent")
public class AgentDO {
    @Id
    private String id;
    private String agentName;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    
    @Convert(converter = ConversationHistoryConverter.class)
    private List<ConversationMessage> conversationHistory;  // 通过 converter 自动 JSON 序列化
    private Integer iterationCount;
    private String status;
    @Convert(converter = AgentConfigConverter.class)
    private AgentConfig configuration;  // Agent 配置信息
}

@Entity
@Table(name = "t_plan")
public class PlanDO {
    @Id
    private String id;
    @Column(nullable = false)
    private String agentId;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    
    @Convert(converter = PlanStepsConverter.class)
    private List<PlanStep> planSteps;  // 通过 converter 自动序列化步骤列表
}

@Entity
@Table(name = "t_agent_run_task", indexes = {@Index(columnList = "agentId")})
public class AgentRunTaskDO {
    @Id
    private String id;
    @Column(nullable = false)
    private String agentId;  // 通过 @Table 注解中的 indexes 定义索引
    private String status;
    private String error;
    @Column(columnDefinition = "TEXT")
    private String result;  // 支持大文本
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
```

#### 6.4.4 自动映射机制

通过 `AttributeConverter` 实现复杂对象的自动序列化/反序列化，保证 Domain 层与 DO 层的无缝转换：

```java
// 为 ConversationMessage 列表创建转换器
@Converter(autoApply = false)
public class ConversationHistoryConverter implements AttributeConverter<List<ConversationMessage>, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<ConversationMessage> attribute) {
        if (attribute == null) return null;
        try {
            // List<ConversationMessage> → JSON String
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert conversation history to JSON", e);
        }
    }

    @Override
    public List<ConversationMessage> convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            // JSON String → List<ConversationMessage>
            return objectMapper.readValue(dbData, 
                new TypeReference<List<ConversationMessage>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert JSON to conversation history", e);
        }
    }
}

// 为 PlanStep 列表创建转换器（类似模式）
@Converter(autoApply = false)
public class PlanStepsConverter implements AttributeConverter<List<PlanStep>, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 实现方式同 ConversationHistoryConverter，使用 TypeReference<List<PlanStep>>
}

// 为 AgentConfig 创建转换器
@Converter(autoApply = false)
public class AgentConfigConverter implements AttributeConverter<AgentConfig, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 实现方式同上，针对单个对象类型而非列表
}
```

> **说明**：
> - 每个 `AttributeConverter` 实现必须有**无参构造方法**（JPA 规范要求）
> - `@Converter(autoApply = false)` 表示不自动应用，需在实体中显式指定 `@Convert(converter = ...)`
> - 使用 `ObjectMapper`（Jackson）实现 JSON 序列化/反序列化
> - `TypeReference` 用于处理泛型类型信息丢失的问题
```

#### 6.4.5 动态切换存储实现

通过配置文件动态选择持久化实现，无需改代码：

```yaml
langur:
  persistence:
    type: jpa  # 可选值: memory, jpa
```

```java
@Configuration
public class PersistenceConfig {
    @Bean
    @ConditionalOnProperty(name = "langur.persistence.type", havingValue = "jpa")
    public AgentRepository jpaAgentRepository(AgentJpaRepository jpaRepo) {
        return new JpaAgentRepository(jpaRepo);
    }

    @Bean
    @ConditionalOnProperty(name = "langur.persistence.type", havingValue = "memory", matchIfMissing = true)
    public AgentRepository inMemoryAgentRepository() {
        return new InMemoryAgentRepository();
    }
}
```

> **生产建议**：使用 Liquibase 或 Flyway 进行版本化的数据库迁移管理，确保数据库 Schema 变更可追踪且可回滚。

### 6.5 多模态消息扩展

`RunAgentRequest` 已支持 `messageParts` 结构化入参，当 LLM 和工具层能力就绪时，可直接在应用层处理图像、音频等多模态内容，**无需修改接口协议**。

---

## 七、API 接口速览

| 方法 | 路径 | 说明 | 响应 |
|------|------|------|------|
| `POST` | `/api/agents` | 创建 Agent | `AgentResponse` |
| `GET` | `/api/agents` | 列出所有 Agent | `List<AgentResponse>` |
| `GET` | `/api/agents/{id}` | 获取 Agent 详情 | `AgentResponse` |
| `DELETE` | `/api/agents/{id}` | 删除 Agent | `204 No Content` |
| `POST` | `/api/agents/{id}/run` | 同步执行（阻塞直到完成） | `AgentResponse` |
| `POST` | `/api/agents/{id}/run/async` | 异步提交执行（立即返回） | `RunTaskResponse (202)` |
| `GET` | `/api/agents/{id}/plan` | 查询最近一次执行计划 | `PlanResponse` |
| `GET` | `/api/agents/{id}/runs` | 查询 Agent 的异步任务列表 | `List<RunTaskResponse>` |
| `GET` | `/api/agents/runs/{taskId}` | 查询异步任务状态 | `RunTaskResponse` |

---

## 八、后续演进规划

Langur 当前版本（v1.0）奠定了完整的架构基础，后续演进将沿以下方向推进：

### 8.1 🗄️ 持久化升级（已完成 ✅）

- **已实现**：完整的 JPA 数据库实现，支持 MySQL/PostgreSQL
- **架构**：
  - 三个 JPA 实体（AgentDO、PlanDO、AgentRunTaskDO）自动进行 ORM 映射
  - 三个对应的 JPA Repository 实现，保证 Domain 层零修改
  - 自动的 Domain ↔ DO 双向转换，透明处理序列化
  - 灵活的持久化配置，支持动态切换内存/数据库实现
- **特点**：
  - 生产级 ACID 保证，数据库事务隔离
  - 支持复杂查询优化与索引管理
  - 支持 Liquibase/Flyway 数据库版本管理
  - 内存实现仍可用于开发测试，零依赖
- **迁移**：通过 `@Primary` 注解或配置文件无缝切换，既有业务代码无需修改
- **收益**：服务重启数据不丢失，支持多实例部署与数据共享

### 8.2 🤖 多 LLM 支持（已完成 ✅）

- **已实现**：内置 OpenAI、Claude、Gemini、DeepSeek、Qwen 五大主流模型适配器
- **特点**：统一 `LLMPort` 接口，支持配置切换与模型路由
- **演进**：后续支持更多模型（Mistral、LLaMA 等），完全无缝集成

### 8.3 ⚡ 工程审计与决策追踪（已完成 ✅）

- **已实现**：技术设计文档与代码变更同步提交，存放在 `/design` 目录
- **收益**：
  - 设计决策可追溯，支持快速复盘
  - 新人易理解演进脉络
  - 积累团队知识库
- **后续**：支持基于 PR 的设计文档自动生成、检索、对标对比

### 8.4 ⏱️ 性能优化（已完成 ✅）

- **已实现**：批量 Agent 工具再水合优化（O(n*m) → O(n+m)）
- **收益**：Agent 创建与恢复性能提升 2-3 倍
- **后续**：继续优化 LLM 推理缓存、工具执行并行化

### 8.5 🧠 长期记忆集成（中期）

- **目标**：构建框架 L4 知识层，赋予 Agent 长期记忆能力
- **能力**：跨会话记忆检索（RAG）、用户画像积累、知识沉淀
- **接入方式**：新增 `MemoryPort` 接口，在 ReAct 循环前注入相关记忆上下文

### 8.6 🌊 流式输出支持（中期）

- **目标**：支持 SSE（Server-Sent Events）或 WebSocket 推送 ReAct 中间步骤
- **能力**：前端实时展示 Agent 的思考过程、工具调用详情和中间结果
- **接口**：新增 `POST /api/agents/{id}/run/stream` 流式端点

### 8.7 🔗 多 Agent 协作增强（中期）

- **目标**：支持 Agent 之间的任务委托与结果汇聚
- **能力**：主 Agent 可通过工具调用将子任务委托给专业 Agent，并聚合结果
- **方案**：增加 `AgentCallTool`，将其他 Agent 作为可调用工具

### 8.8 📊 可观测性（中期）

- **目标**：为每次 ReAct 执行提供完整的可观测链路
- **能力**：
  - 执行 Trace（每步耗时、LLM token 消耗）
  - 结构化日志（包含 agentId、taskId、迭代轮次）
  - Metrics 接入（Prometheus + Grafana）
  - 分布式链路追踪（OpenTelemetry）

### 8.9 🌐 多模态能力（远期）

- **目标**：充分利用已预留的 `MessagePartType` 枚举，支持图像/音频/视频输入
- **能力**：视觉理解工具、语音转文字工具、多模态 LLM 接入（如 GPT-4V）
- **接口**：当前 `messageParts` 接口协议已兼容，LLM 层就绪后即可打通

### 8.10 📦 SDK 化（远期）

- **目标**：将 Langur 发布为可独立引入的 Maven 包
- **能力**：其他 Spring Boot 项目通过依赖引入即可获得 Agent 能力
- **方案**：提取 `langur-sdk` 模块，提供 Spring Auto-Configuration 支持

---

## 总结

Langur 框架以 **DDD 六边形架构** 为基础，通过严格的分层设计和端口-适配器模式，构建了一个高度可扩展的 Agent 执行引擎。核心亮点在于：

1. **业务纯粹**：Domain 层零框架依赖，业务规则清晰，可独立测试
2. **扩展友好**：LLM、工具、存储均通过接口抽象，一行注解即可替换
3. **生产基础**：异步任务、状态追踪、线程安全的并发模型
4. **演进预留**：多模态消息格式、流式输出接口均已预留扩展入口

作为独立的企业级 Agent-Harness 框架，Langur 将持续演进，逐步具备**记忆、流式、多 Agent 协作与完整可观测性**，成为面向生产环境的企业级 Agent 框架。

---

# 第三部分 · 演进历程：四阶段 Agent 能力升级

## 这次 MR 的核心，不只是 DDD

上一版解读把重点放在了 DDD 多模块改造上，但最新 MR 的真正主线是：**Agent 能力从单次调用升级为“四阶段闭环”**，DDD 只是支撑这条主线的工程化手段。

这四个阶段分别是：

1. **输入阶段（Input Normalization）**：统一结构化消息输入
2. **规划阶段（Planning）**：把执行过程沉淀为可追踪 Plan
3. **执行阶段（Reason + Act）**：稳定 ReAct 循环并记录每一步
4. **运营阶段（Async + Observability）**：异步任务化运行与状态可观测

---

## 阶段一：输入能力升级（支持 `messageParts`）

在 `RunAgentRequest` 中，输入从单一 `userMessage` 扩展为：

- `userMessage`（纯文本）
- `messageParts`（结构化分片）

结构化类型由 `MessagePartType` 统一约束：

- `TEXT`
- `IMAGE`
- `AUDIO`
- `VIDEO`
- `FILE`
- `STRUCTURED_DATA`

应用层 `AgentApplicationService#resolveUserMessage` 负责兜底合并逻辑：

- 优先使用 `userMessage`
- 当 `userMessage` 为空时，按 `messageParts` 组装可用文本
- 无有效内容直接抛错，避免空输入进入 Agent 循环

**价值**：Agent 的输入边界从“文本参数”升级为“标准化多模态消息入口”。

---

## 阶段二：规划能力升级（Plan 持久化）

在执行前，应用层会通过 `PlanningDomainService#createPlan` 创建 Plan，并在流程结束时持久化到 `PlanRepository`。

执行过程中的每次决策都会落成 `PlanStep`：

- `thought`
- `action`
- `actionInput`
- `observation`
- `status`

并通过 API 支持查询最近一次计划：

- `GET /api/agents/{id}/plan`

**价值**：Agent 从“黑盒调用”升级为“可复盘的思维链 + 行动链”。

---

## 阶段三：执行能力升级（ReAct 闭环更完整）

核心逻辑在 `AgentDomainService#executeReActStep`：

1. 调用 `LLMPort.decide(...)` 获取决策
2. 若为最终答案，直接完成 Agent
3. 若为工具调用，记录思考与动作
4. 执行工具并回填 `observation`
5. 将 Thought/Action/ToolResult 写回会话历史

这一轮设计把关键状态全部显式化：

- Agent 状态：`IDLE/RUNNING/COMPLETED/FAILED`
- 计划步骤状态：`RUNNING/COMPLETED/FAILED/...`
- 最大迭代保护：`maxIterations`

**价值**：Agent 的 Reason-Act 循环具备了可中断、可诊断、可审计的工程属性。

---

## 阶段四：运营能力升级（异步任务化 + 可观测）

新增 `AgentRunTask` 与 `AgentRunTaskApplicationService`，把执行过程任务化：

- `POST /api/agents/{id}/run/async`：异步启动
- `GET /api/agents/runs/{taskId}`：查询任务状态
- `GET /api/agents/{id}/runs`：按 Agent 查看任务列表

任务状态生命周期：

- `PENDING -> RUNNING -> COMPLETED/FAILED/CANCELLED`

并携带 `userId/tenantId/sessionId`，便于多租户和会话级追踪。

**价值**：Agent 从“同步接口能力”升级为“可并发调度的运行单元”，为生产环境接入奠定基础。

---

## DDD 改造在这里扮演什么角色？

本次 DDD 多模块拆分（common/domain/application/api/infrastructure/start）不是目标本身，而是为四阶段能力提供稳定边界：

- Domain 专注 Agent 规则与状态机
- Application 负责编排用例与输入归一
- API 专注协议层输出
- Infrastructure 实现 LLM / Tool / Repository 适配

尤其是 `LLMPort` 回归 `domain.port`，使 Agent 核心流程不再绑定具体 LLM 供应商，保证了后续扩展能力。

---

## 对业务方最直接的收益

从业务视角看，这次 MR 带来的不是“架构更优雅”，而是更实际的四点：

1. **接入成本更低**：输入协议支持结构化内容
2. **排障效率更高**：每次执行都有 Plan 可复盘
3. **稳定性更强**：状态机 + 迭代上限 + 异常路径清晰
4. **生产可用性更好**：异步任务化 + 状态查询 + 列表检索

---

## 总结

如果要用一句话概括这次 MR：

> **Langur 从“有 Agent 功能”升级到了“有 Agent 生命周期管理能力”。**

DDD 多模块拆分是地基，但真正拉开差距的是这套四阶段闭环：

**输入标准化 → 规划可追踪 → 执行可诊断 → 运行可运营**。

这也是 Agent 框架走向生产化最关键的一步。

---

# 第四部分 · 落地路线图与完成记录（T1–T15）

> 基线：2026-08-31 重构完成态（六组件骨架已落位，18 测试全绿，应用可启动）
> 现状评估：骨架完整度 ~85%，主链路贯通度 ~40%
> 说明：以下任务对应第一部分「总体架构设计」的落地弥合，完成后在「完成记录」中标注日期与说明。

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
- [x] T10a 已完成（2026-09-21）；T10b/T10c 已完成（2026-09-25）
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
| 2026-09-25 | T10b | infra `harness/tool/mcp` 包：`McpToolProperties`（`langur.mcp-tools`，默认关闭）+ `McpToolSpec`/`McpToolCatalog`（工具 ID `mcp:{server}:{tool}`）+ JSON-RPC 传输层（`McpTransport` 接口 + `HttpMcpTransport` WebClient 实现，复用 `SsrfGuard`/`CredentialVault`，兼容 application/json 与 SSE `data:` 分帧）+ `McpClient`（initialize 握手 / tools-list 发现 / tools-call 抽取文本 / ping 心跳）+ `McpClientManager`（连接/发现/心跳保活/断线懒重连，implements `McpToolGateway`，@PostConstruct 装载 @PreDestroy 停机）；`DefaultToolDispatcher` 新增 `McpToolGateway` 可选注入与 `case MCP -> executeMcp`（复用 `runGeneric` 沙箱限时）；application.yml 增 `langur.mcp-tools` 配置块；`McpClientTest` 5 + `McpClientManagerTest` 4 + `McpToolRoutingTest` 3 |
| 2026-09-25 | T10c | infra `harness/tool/skill` 包：`@SkillDef` 注解 + `Skill` 契约 + `StepType`(TOOL_CALL/CONDITION) + `SkillStep`/`SkillSpec`/`SkillCatalog`（工具 ID `skill:{name}`）+ `SkillExpressionResolver`（安全最小集占位符 `${input.x}`/`${step.field}` 解析 + 六种比较运算符条件求值，无脚本引擎杜绝注入）+ `SkillExecutor`（顺序驱动，TOOL_CALL 回派 `ToolDispatcher` 形成嵌套四层校验，CONDITION 跳转/END，步数硬上限防环）+ `DefaultSkillToolGateway`（`@Lazy` 注入 ToolDispatcher 打破构造期循环）+ `SkillRegistrar`（@PostConstruct 扫描 `@SkillDef` Bean 注册 source=SKILL）；`DefaultToolDispatcher` 新增 `SkillToolGateway` 可选注入与 `case SKILL -> executeSkill`；`SkillExecutorTest` 6 + `SkillRegistrarTest` 2 + `SkillToolRoutingTest` 3 |
| 2026-09-25 | T10b/T10c 验收 | 117 测试全绿（domain 39 + infra 67 + start 11，新增 23），`mvn clean test` BUILD SUCCESS；打包后启动 2.1s，MCP/SKILL/REST 三源默认关闭空转无副作用，`@Lazy` 打破调度器↔技能网关循环依赖，上下文装配无异常，T10 全部子项闭环 |


---

# 附录 · PR 设计文档约定

## 技术设计文档：将技术设计作为 PR 组成部分

### 1. 背景
当前仓库已有 `doc/` 目录用于技术分享类文档，但缺少针对 PR 变更的技术设计文档统一落点与提交约定。

### 2. 目标
- 技术设计文档与代码变更一同提交在同一 PR 中。
- 技术设计文档统一存放在仓库新增目录：`/design`。
- 让评审在审阅 PR 时可同时看到“设计决策 + 代码实现”。

### 3. 范围
本设计仅定义文档组织与提交流程约定，不改动业务代码逻辑。

### 4. 设计方案
- 新增目录：`/design`。
- 在该目录中维护 PR 对应的技术设计文档。
- 文档建议包含以下核心信息：
  - 需求背景与目标
  - 方案对比与设计决策
  - 影响范围与风险评估
  - 验证策略与回滚考虑

### 5. 预期收益
- 评审信息更完整，降低沟通成本。
- 关键设计决策可追溯，便于后续维护与复盘。
- 形成“设计先行、实现可审计”的工程实践。

### 6. 与现有文档目录关系
- `doc/`：技术分享、总结类内容。
- `design/`：面向 PR 变更的技术设计文档。
