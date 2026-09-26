# Agent-Harness 架构设计 v1.0

> 本文件为 v1.0 阶段设计文档合并版——原 `design/` 目录 9 份文档合并为 v1.0 / v2.0 / v3.0 三份，随项目开源。
> 组成：总体架构设计（通用版，15 节 + ADR D1–D13）· DDD 优化落地路线图（T1–T15 完成记录 + 待决策 D1–D4）· PR 设计文档约定（附录）。


---

> **▍第一部分 · 总体架构设计（通用版）**

# Agent-Harness 框架整体架构设计方案（通用版）

> **文档性质**: 通用架构设计方案（General Architecture Blueprint）
> **版本**: v1.0 | **日期**: 2026-08
> **定位**: 框架级通用架构设计方案，不绑定任何具体业务场景
> **核心公式**: `Agent = LLM推理能力 + Harness确定性调度能力`

---

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

> **▍第二部分 · DDD 优化落地路线图（T1–T15 完成记录）**

# Langur 架构差距弥合 TODO

> 依据：`design/Agent-Harness架构设计-v1.0.md` v1.0
> 基线：2026-08-31 重构完成态（六组件骨架已落位，18 测试全绿，应用可启动）
> 现状评估：骨架完整度 ~85%，主链路贯通度 ~40%
> 使用方式：逐项派发（如"完成 T1"），完成后将 `[ ]` 改为 `[x]` 并在"完成记录"补充日期与说明。

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

> **▍附录 · PR 设计文档约定**

# 技术设计文档：将技术设计作为 PR 组成部分

## 1. 背景
当前仓库已有 `doc/` 目录用于技术分享类文档，但缺少针对 PR 变更的技术设计文档统一落点与提交约定。

## 2. 目标
- 技术设计文档与代码变更一同提交在同一 PR 中。
- 技术设计文档统一存放在仓库新增目录：`/design`。
- 让评审在审阅 PR 时可同时看到“设计决策 + 代码实现”。

## 3. 范围
本设计仅定义文档组织与提交流程约定，不改动业务代码逻辑。

## 4. 设计方案
- 新增目录：`/design`。
- 在该目录中维护 PR 对应的技术设计文档。
- 文档建议包含以下核心信息：
  - 需求背景与目标
  - 方案对比与设计决策
  - 影响范围与风险评估
  - 验证策略与回滚考虑

## 5. 预期收益
- 评审信息更完整，降低沟通成本。
- 关键设计决策可追溯，便于后续维护与复盘。
- 形成“设计先行、实现可审计”的工程实践。

## 6. 与现有文档目录关系
- `doc/`：技术分享、总结类内容。
- `design/`：面向 PR 变更的技术设计文档。
