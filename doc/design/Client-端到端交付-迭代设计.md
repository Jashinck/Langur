# Langur Client 端到端交付 · 迭代设计

> **文档性质**: 迭代设计方案（Iteration Design）——本次迭代引入 `langur-client` 前端模块、把现有 DDD 六模块收敛至上层 `langur-server` 聚合器，交付一个 DeepSeek Harness 风格**会话工作台**，实现端到端完整交付、用户开箱即体验。
> **日期**: 2026-10-18 | **定位**: 企业级 Agent-Harness 框架的**首个交互面**，与既有三大架构哲学（Harness/Jev/RSI）正交，不改变六组件与分层边界。
> **一句话立场**: **框架讲能力，工作台讲体验；Client 是接入层外置，不是第七组件。**
> **三项决策（已对齐）**: ① 前端栈 React 18 + Vite + TS；② 六模块以**父聚合器嵌套**收敛至 `langur-server`（6 个 artifact 与 ArchUnit 三不变量原样保留）；③ v1 页面范围 = **会话工作台**（Agent 列表 + 会话流式 + 工具调用折叠展示）。

---

## 0. 背景与目标

### 0.1 为什么本次迭代

Langur 已有完整的服务端能力（六组件 E/T/C/S/L/V + 决策平面 J1–J10 + RSI R0–R5/R-G，614 单测全绿），但**交付形态止步于 REST/SSE 接口**：体验者需要手写 HTTP 请求才能验证框架能力。这不符合"企业级框架"的定位——框架的第一印象应该是"打开页面就能对话、能看见 Agent 在做什么"。

本次迭代补齐最后一段：**从 `mvn package` 到浏览器里跑起来一个 Agent 的完整闭环**。

### 0.2 三项目标

| # | 目标 | 度量 |
|---|------|------|
| G-C1 | **模块收敛**：六 DDD 模块物理下沉至 `langur-server` 聚合器，顶层只留 `server` + `client` 两个一级模块 | `mvn -pl langur-server clean test` 全绿，ArchUnit 三不变量不变 |
| G-C2 | **Client 落地**：`langur-client`（React 18 + Vite + TS）产出静态包，`mvn package` 后单 jar 内可直接打开会话工作台 | `java -jar langur.jar` 后浏览器 `http://localhost:8081` 可用 |
| G-C3 | **会话工作台 v1**：Agent 列表 + 会话流式 + 工具调用折叠展示 | 走通"建 Agent → 发消息 → 看工具调用 → 收最终答案"全链路 |

### 0.3 明确的非目标（防范围膨胀）

| 非目标 | 归入 |
|--------|------|
| 决策平面判定可视化（`decision_route_counts`/置信度时间线） | v2 |
| 运行轨迹回放时间线（每轮快照/观察） | v2（依赖后端轨迹查询接口，当前快照仅 3 标量） |
| 全功能控制台（workflow/bizCode 配置编辑、任务中心、指标面板） | v2+（多数配置接口后端未暴露） |
| token 级流式输出贯穿 ReAct 循环 | v2（见 §4.3 边界说明） |
| 登录/多租户 UI、多用户并发会话 | v2+（当前 `Agent.conversationHistory` 是 Agent 聚合上单条可变列表，非会话级） |

---

## 1. 工程架构变更

### 1.1 顶层结构（before → after）

**Before（当前）**：

```
langur/                              # 根聚合器 (packaging=pom)
├── pom.xml                          # <module> × 6
├── langur-common/
├── langur-domain/
├── langur-application/
├── langur-infrastructure/
├── langur-api/
├── langur-start/
└── doc/
```

**After（本次迭代后）**：

```
langur/                              # 根聚合器 (packaging=pom)
├── pom.xml                          # <module>langur-server</module>（client 不进 Maven reactor）
├── langur-server/                   # NEW 聚合器 (packaging=pom)
│   ├── pom.xml                      # 含 6 子模块；继承根 pom 的 dependencyManagement
│   ├── langur-common/
│   ├── langur-domain/
│   ├── langur-application/
│   ├── langur-infrastructure/
│   ├── langur-api/
│   └── langur-start/
├── langur-client/                   # NEW 前端模块（npm 工程，非 Maven artifact）
│   ├── package.json
│   ├── vite.config.ts
│   ├── index.html
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── api/                     # fetch 封装 + SSE 客户端（唯一与后端耦合的层）
│   │   ├── components/              # AgentList / ChatPane / ToolCallCollapse ...
│   │   ├── pages/
│   │   └── styles/
│   └── dist/                        # 构建产物（.gitignore，打包时复制进 server 静态资源）
└── doc/
```

### 1.2 收敛原则（不变量）

物理下沉，逻辑不动。六模块的**包路径、依赖方向、ArchUnit 三不变量**全部原样保留：

| 不变量 | 内容 | 收敛后 |
|--------|------|--------|
| P1 | domain 纯度（零 Spring / 零 infrastructure import） | ✅ 不变 |
| P2 | 严格单向依赖 `start → api → application → domain ← infrastructure` | ✅ 不变 |
| P4 | 六组件正交 | ✅ 不变 |
| 包名 | `org.skylark.langur.*` 六模块根包 | ✅ 不变（不因目录嵌套改名） |
| Artifact | 6 个 jar + 版本管理 | ✅ 不变 |

> **为什么是"嵌套"而非"合并"**：合并成单模块会把依赖隔离从 **Maven 编译期强制**降级为 **ArchUnit 运行时校验**，P1/P2 防线变弱；嵌套只是把 `<module>` 声明从根 pom 移到 `langur-server/pom.xml`，6 个 artifact 与编译期隔离零损失。代价仅是一次目录移动（`git mv` 保留历史）。

### 1.3 langur-server 聚合器

`langur-server/pom.xml` 关键点：

- `parent` 指向根 `../pom.xml`（根 pom 的 `parent` 仍是 `spring-boot-starter-parent`，链路不变）。
- `<packaging>pom</packaging>`，`<modules>` 声明 6 个子模块。
- 6 个 artifact 的 `<version>` 继续由根 pom `dependencyManagement` 统一管理（单一版本源，P9 配置驱动）。
- `langur-start` 的最终可执行 jar 仍为 `langur-server/langur-start/target/langur.jar`。

根 `pom.xml` 唯一改动：`<module>langur-common</module>…<module>langur-start</module>` 六行 → `<module>langur-server</module>` 一行。

### 1.4 langur-client 模块

| 维度 | 决策 |
|------|------|
| 技术栈 | React 18 + Vite 5 + TypeScript 5；样式 Tailwind CSS（可选，低侵入） |
| 状态管理 | 无全局 store——v1 单页工作台用 React 本地状态即可，不引入 Redux/Zustand（抽象最小化） |
| HTTP | 原生 `fetch` + `EventSource`（或 `fetch` + ReadableStream 消费 SSE），不引 axios（接口面小） |
| 目录契约 | `src/api` 是**唯一**与后端耦合的层：集中维护 endpoint 常量与事件类型，其余组件不 import 后端细节 |
| 构建产物 | `dist/` 静态资源（index.html + assets），SPA 单页 |

> **为什么 React 而非 Vue/Next**：① 该形态工作台（流式 Markdown、折叠面板、虚拟滚动）的 React 生态组件最全；② 产物纯静态、与"打进单 jar"的交付目标天然一致；Next.js 的 SSR/Node 运行时与之冲突。Vue 3 同等可行，仅生态组件储备差异。

### 1.5 依赖方向（收敛后全景）

```
                     ┌───────────────────────────────────────────────┐
                     │              langur-server（聚合器）           │
                     │  start → api → application → domain ← infra   │
                     │                 ↑  common（全模块依赖）        │
                     └───────────────────┬───────────────────────────┘
                                         │  HTTP（同源 /api/*、/api/v1/*）
                                         │  编译期零耦合
                     ┌───────────────────▼───────────────────────────┐
                     │              langur-client（Vite 静态产物）    │
                     │   src/api → fetch/SSE → 会话工作台 UI         │
                     └───────────────────────────────────────────────┘
```

**Client 与 Server 唯一的耦合是 HTTP 契约**，无编译期依赖、无共享代码、无反向导入——这正是"接入层外置"的含义：Client 可以被任意其他终端（CLI、第三方门户、移动端）替换，而不触碰 `langur-server` 一行代码（P3 依赖倒置在交付层的表达）。

---

## 2. 交付形态与构建链路

### 2.1 单 jar 静态托管（为什么）

"端到端完整交付、方便用户直接体验"的最短路径是**一个 jar 自包含**：Client 构建产物复制进 `langur-start` 的静态资源目录，由 Spring Boot 与 API 同源托管。

- **同源 → 无 CORS**：当前后端**无任何 CORS 配置**（全仓 `Cors/CrossOrigin` 命中 0）。同源静态托管让 v1 完全绕开 CORS 与预检，也绕开跨域 cookie/token 问题。
- **一个进程一个端口**：`http://localhost:8081` 同时提供页面（`/`、`/index.html`）与 API（`/api/*`），体验者零配置。
- **SPA fallback**：`/` 之外的路由（v1 无路由，但为 v2 预留）由 `forward:/index.html` 处理。

### 2.2 构建链路

```
开发态（前端热更 + 代理）                   生产态（单 jar 交付）
──────────────────────────────            ──────────────────────────────
cd langur-client                           npm run build
npm install                                → langur-client/dist/
npm run dev  (Vite :5173)                  mvn -pl langur-server package
  └ proxy /api → http://localhost:8081       └ maven-resources-plugin 把
                                              ../langur-client/dist 复制进
                                              langur-start/target/classes/static
                                             → java -jar langur-server/langur-start/target/langur.jar
                                             → 打开 http://localhost:8081 即工作台
```

### 2.3 node 依赖的弹性边界（关键设计）

**Server 构建不硬依赖 node**：`langur-start` 的 `maven-resources-plugin` 配置为**"dist 存在则打包，不存在则跳过"**（`<skip>` 由资源目录探测决定，或用 Maven profile `-Pclient` 显式启用前端复制）。

- 纯 Java 开发者/CI 不装 node，`mvn package` 照常产出**纯 API 版** jar（无静态资源，行为同现状）。
- 要交付体验版时，先 `npm run build` 再 `mvn package`，dist 自动入 jar。
- 这兑现了"渐进式演进（P8）+ 配置驱动（P9）"：前端构建是可插拔的一步，不是 server 的构建前置。

> 备选 `frontend-maven-plugin`（由 Maven 直接驱动 npm）刻意**不采用**：它把 node/npm 变成 Maven 构建的强制前置，破坏 Java 侧独立构建。弹性边界优先。

---

## 3. 会话工作台 v1 设计

### 3.1 页面结构（信息架构）

```
┌──────────────────────────────────────────────────────────────────────┐
│  Langur · 会话工作台                                    [新建 Agent] │
├───────────────┬──────────────────────────────────────────────────────┤
│  Agent 列表   │  会话区                                              │
│ ┌───────────┐ │  ┌────────────────────────────────────────────────┐ │
│ │ contract… │ │  │  你：帮我审查这份合同                          │ │
│ │ code_to_p…│ │  │  ┌─────────────────────────────────────────┐  │ │
│ │ 通用助手 ● │ │  │  │ 🔧 skill:contract-review              │  │ │
│ │ + 新建     │ │  │  │   （参数…）   展开 ▼                    │  │ │
│ └───────────┘ │  │  │   ✔ 输出摘要（截断）                     │  │ │
│               │  │  └─────────────────────────────────────────┘  │ │
│               │  │  ┌─────────────────────────────────────────┐  │ │
│               │  │  │ 🔧 knowledge_retrieve（…）   展开 ▼     │  │ │
│               │  │  └─────────────────────────────────────────┘  │ │
│               │  │  助手：审查结论如下…（Markdown 渲染）           │ │
│               │  └────────────────────────────────────────────────┘ │
│               │  [输入框：消息 + 选择模型/工具]           [发送]     │
└───────────────┴──────────────────────────────────────────────────────┘
```

三栏语义（v1 实际为两栏 + 内联折叠，避免过度设计）：

| 区域 | 内容 | 数据来源 |
|------|------|----------|
| **Agent 列表（左）** | 现有 Agent（名/描述/模型），选中即进入会话；`+ 新建` 弹简单表单（name/systemPrompt/model/temperature/maxIterations/toolNames） | `GET /api/agents`、`POST /api/agents` |
| **会话区（中）** | 用户消息、助手最终答案（Markdown 渲染）、内联**工具调用折叠卡** | 本地会话状态 + SSE 事件（§4） |
| **发送栏（底）** | 消息输入 + 发送（v1 不设模型/工具选择器，用 Agent 既有配置；选择器归 v2） | `POST /api/v1/agent/stream/chat` |

### 3.2 关键交互

| 交互 | 行为 |
|------|------|
| 流式渲染 | 消费 SSE `message` 事件逐 token 追加（纯补全快路径）；工具轨迹事件按序插入折叠卡（§4.3） |
| 工具调用折叠 | 默认折叠为一行摘要（`🔧 工具名 + 参数首行`），点击展开观察输出全文（**大输出截断**，展示前 N 字符 + "共 X 字节"） |
| 会话状态 | v1 **客户端本地维护**消息列表（内存 + localStorage），不依赖后端会话历史接口（§4.2 论证） |
| 错误态 | SSE `done` 前断开/`summary` 带 error → 会话区尾部红色错误条；发送按钮防抖，避免并发消息 |
| 空态 | 无 Agent 时引导"新建 Agent"；无会话时展示 Agent 描述与系统提示 |

### 3.3 组件清单

| 组件 | 职责 | 备注 |
|------|------|------|
| `AgentListPane` | 列表 + 选中态 + 新建表单 | 消费 `GET/POST /api/agents` |
| `ChatPane` | 消息流渲染 + 自动滚动 | 虚拟滚动归 v2（v1 消息量小） |
| `MessageBubble` | 用户/助手气泡，Markdown 渲染 | 用 `react-markdown`（无 dangerouslySetInnerHTML） |
| `ToolCallCollapse` | 工具调用折叠卡 | 消费 `tool`/`observation` 事件 |
| `ComposerBar` | 输入 + 发送 | 防抖 + 空态禁用 |
| `useSseChat`（hook） | SSE 生命周期：连接/事件分发/断线重连 | 封装在 `src/api` |

---

## 4. API 契约与事件流

### 4.1 复用的既有端点（零改动）

| 端点 | 用途 |
|------|------|
| `GET /api/agents` | Agent 列表 |
| `POST /api/agents` | 新建 Agent（`name/description/systemPrompt/model/temperature/maxIterations/toolNames`） |
| `GET /api/agents/{id}` | 单 Agent 详情 |
| `DELETE /api/agents/{id}` | 删除 |
| `POST /api/v1/agent/stream/chat` | SSE 流式对话（`message → summary → done`） |

### 4.2 会话历史：客户端本地维护（取舍论证）

后端**不新增会话历史接口**，v1 由客户端本地维护消息列表。理由：

1. 当前 `Agent.conversationHistory` 是 Agent 聚合上的**单条可变列表**（`langur-domain/.../model/agent/Agent.java:27`），非会话级、非多用户安全——它本就只承载"同一 Agent 连续对话"这一语义，与工作台的"选中 Agent 后开一个会话"恰好一致。
2. 新增会话历史接口意味着引入 session 概念与持久化，那是**会话级记忆治理**的完整工程（属 H15 上下文工程的地基项），不应被本次"交付体验面"的迭代绑架。
3. v1 工作台的正确性不依赖服务端会话历史：每次 SSE 请求无状态，客户端决定展示什么。

> **边界声明**：多用户/多会话并发同一 Agent 会互相污染 `conversationHistory`——这是既有模型限制，Client 不掩盖也不修复，留待 H15（session 事件日志化）一并解决。设计文档明示此边界，而非静默埋雷。

### 4.3 新增：ReAct 轨迹事件流（支撑"工具调用折叠展示"）

**现状事实（决策依据）**：既有流式路径 `streamChat` 调 `streamFinalAnswer`（`AgentApplicationService.java:204-216` → `AgentDomainService.streamFinalAnswer`），**只发 system + 最后一条 user 消息做纯补全，绕过了 ReAct 循环、工具调用与钩子**。因此"工具调用折叠展示"**无法从现有流式路径得到任何工具事件**——这是 v1 工作台必须补齐的唯一一处后端能力。

**方案：让流式穿过真实 E 循环，并沿既有 L 组件接缝外发轨迹事件。**

```
POST /api/v1/agent/stream/chat（增强）
  → AgentApplicationService.streamReAct（新增）
      → 循环 executeReActStep（复用既有 ReAct 单步，无新循环逻辑）
      → 每轮经 StreamEventHandler 外发类型化事件
```

**事件协议（SSE event: data）**：

| 事件 | 数据 | 触发点 | 来源 |
|------|------|--------|------|
| `round` | `{round, paradigm}` | 每轮开始 | E 循环 |
| `tool` | `{name, args}` | 工具调用发出 | `BEFORE_TOOL_CALL`（既有钩子点 `AgentDomainService.java:140-143`） |
| `observation` | `{name, success, summary, bytes}` | 工具结果回填 | `AFTER_TOOL_CALL`（既有钩子点 `AgentDomainService.java:122`） |
| `final` | `{answer}` | 最终答案 | `BEFORE_OUTPUT` / 循环退出 |
| `summary` | `{status, rounds, tokens}` | 收尾 | 复用现有语义 |
| `done` | `{agentId}` | 结束 | 复用现有语义 |

**落地接缝（不侵入循环主体，P2/P4 不破）**：新增 `ToolTraceStreamHook implements LifecycleHook`，挂 `BEFORE/AFTER_TOOL_CALL` + `BEFORE_OUTPUT`，把事件写入**当前任务的 `StreamEventHandler`**（经 `TaskProgressBus` 同款 per-task 注册机制，或 ThreadLocal 传递）。事件序列化在应用层，domain 只暴露既有 hook 数据（工具名/参数/结果），不感知 SSE。

**边界（v1 诚实声明）**：
- `final` 答案以**整段**下发，不做 token 级流式——因为 ReAct 最终答案是完整决策，token 化需 `decide` 支持流式 + 原生 `tool_calls`（属地基项 H15，v2）。
- `observation` 摘要化下发：全量结果仅截断后随事件送（大输出由 Client 折叠展示，服务端仍保留全量在会话历史里）。

> 这一改动同时修复了既有审查发现的"流式绕过 ReAct"缺陷（审计项 #9），使流式路径首次真实走完 E 循环——**Client 需求反向驱动了服务端正确性**。

---

## 5. 后端最小补齐清单（必要性论证）

| # | 变更 | 必要性 | 边界 |
|---|------|--------|------|
| R1 | **ReAct 轨迹事件流**（§4.3：`streamReAct` + `ToolTraceStreamHook` + 事件协议） | 工作台"工具调用折叠展示"的**唯一**数据来源；同时修复流式绕过循环 | 仅新增流式应用服务与一个 hook，循环主体零改动 |
| R2 | **SPA fallback**：`/` 与未命中 API 的 GET → `forward:/index.html` | SPA 单页路由与直接访问子路径 | 仅 `GET`、仅非 `/api/*`、非 `/actuator/*` |
| R3 | **静态资源托管**：`langur-start` 复制 `langur-client/dist` → `target/classes/static` | 单 jar 自包含交付（G-C2） | dist 存在才打包（§2.3），不引入 node 硬依赖 |
| R4 | **dev CORS**：仅 `dev` profile 允许 `http://localhost:5173` | 开发态 Vite 代理已可规避，但直连调试需放开；生产同源不启用 | 默认关闭，仅 profile 激活 |

**不新增**：会话历史接口（§4.2）、轨迹查询接口、配置编辑接口、登录体系——均归 v2，避免本次迭代绑架地基工程。

---

## 6. 与三大哲学 / P1–P12 的关系

| 哲学/原则 | 关系 |
|-----------|------|
| **Harness** | Client 是六组件之外的**接入层外置**，不占 E/T/C/S/L/V 任何正交位（P4 不破）；轨迹事件走 L 组件既有钩子接缝，零侵入循环 |
| **Jev（决策平面）** | v1 不渲染判定可视化；但 `ToolTraceStreamHook` 的接缝为 v2 "决策判定时间线"预留（`decision_route_counts` 可沿同通道外发） |
| **RSI** | 不触碰；工作台产生的真实轨迹未来正是 R0 回放/R2 蒸馏的**数据源**（Client 帮助 RSI 闭环，不构成依赖） |
| **P1/P2/P4** | 收敛为物理嵌套，编译期隔离与 ArchUnit 三不变量不变 |
| **P3 依赖倒置** | Client 与 Server 仅 HTTP 契约耦合，Client 可被任意终端替换 |
| **P8 渐进式** | v1 只做会话工作台；轨迹时间线、控制台、token 级流式分版本演进 |
| **P9 配置驱动** | 前端打包 = 可插拔一步（dist 存在即打包）；dev CORS 仅 profile 激活 |
| **P10 降级兜底** | dist 缺失 → 纯 API 版 jar 照常启动；SSE 断线 → Client 错误条不崩界面 |

---

## 7. 安全与治理衔接

- **同源 → v1 零 CORS**：生产部署页面与 API 同源，规避跨域 token/预检面。
- **治理链兼容**：`langur.governance.enabled=true` 时，`GovernanceFilter`（`langur-api/.../governance/GovernanceFilter.java`）对 `/api/*` 施加鉴权/限流/幂等/TraceId，Client 只需在请求头带 Bearer token 即可通过；v1 工作台在治理关闭时直连（内网/本地体验），开启时在设置里填 token（不做登录页）。
- **XSS 边界**：Markdown 渲染用 `react-markdown`（默认不渲染原始 HTML），工具输出/最终答案一律作为纯文本进入渲染管线，不 `dangerouslySetInnerHTML`。

---

## 8. 里程碑与任务分解

| # | 任务 | 内容 | 依赖 | 验收 |
|---|------|------|------|------|
| C1 | 模块收敛 | `git mv` 六模块入 `langur-server/`；根 pom 改单 module；`langur-server/pom.xml` 新建聚合器；修正 relativePath | — | `mvn clean test` 全绿，ArchUnit 不变 |
| C2 | Client 脚手架 | Vite + React + TS 初始化；`src/api` 契约层；Tailwind 接入 | C1 | `npm run build` 产出 dist |
| C3 | 静态托管链路 | `langur-start` 增加 dist→static 复制（存在才打包）；SPA fallback controller | C1 | 手工放 dist 后单 jar 打开 `/` 见页面 |
| C4 | 会话工作台 UI | Agent 列表 + 会话区 + 发送栏 + 折叠卡组件 + `useSseChat` | C2 | 页面可渲染、可交互 |
| C5 | 后端轨迹事件流 | `streamReAct` + `ToolTraceStreamHook` + 事件协议（R1） | C1 | 后端单测：事件序列正确 |
| C6 | 联调交付 | Client ↔ Server 联调；`npm run build && mvn package` 单 jar 全链路验证 | C3/C4/C5 | G-C2/G-C3 达成，附手动 checklist |

**测试策略**：C1 复用现有 614 单测 + ArchUnit；C5 新增 domain/application 层事件序列单测（沿用"纯 JDK / 匿名桩无 Mockito"约定）；C3/C6 增加一个 `@SpringBootTest` 装配冒烟（补足"零集成测试"的历史欠账，验证静态资源与 controller 装配）；Client 侧以 `vitest` 覆盖 `useSseChat` 状态机与组件快照。

---

## 9. 风险与取舍

| 风险 | 影响 | 缓解 |
|------|------|------|
| 目录迁移破坏构建（relativePath/依赖坐标） | 高（一次性） | C1 独立先行，`git mv` 保留历史，全绿后再动 Client |
| v1 无 token 级流式，最终答案"整段出现"体验弱于 DeepSeek Harness | 中（体验） | `final` 前先发 `round/tool` 事件制造"正在工作"观感；token 级流式入 v2 |
| 多用户并发同一 Agent 污染会话历史（既有模型限制） | 中（正确性） | §4.2 明示边界，客户端本地会话隔离展示；根修在 H15 |
| 前端生态依赖膨胀 | 低 | 仅 react-markdown/tailwind 两个运行时依赖，不引状态库 |
| dist 与 jar 版本错位（改了前端忘记重建） | 低 | 打包脚本固化顺序 `build → package`，README 记录 |

---

## 10. 验收标准（G-C1/G-C2/G-C3）

- [ ] `git mv` 后 `mvn clean test`（JDK 17）全绿，`ArchitectureGuardTest` 通过，无路径/坐标残留。
- [ ] `npm run build && mvn -pl langur-server package` 产出单 jar；`java -jar` 后 `curl :8081/` 返回 index.html，`/actuator/health` UP。
- [ ] 浏览器打开 `http://localhost:8081`：新建 Agent → 选中 → 发消息 → 看到工具调用折叠卡（展开可见截断输出）→ 收到最终答案。
- [ ] 断网/后端停止时页面不崩（错误条呈现），SSE 重连正常。
- [ ] 治理开关 `langur.governance.enabled=true` 时，带 Bearer token 的请求可通过、无 token 被拒（API 层，Client 兼容验证）。

---

## 附录 A. 术语对照

- **Client / langur-client**：React 18 + Vite + TS 前端模块，静态产物，经 HTTP 与 Server 耦合。
- **langur-server**：六 DDD 模块的聚合器（packaging=pom），不改包路径与依赖方向。
- **会话工作台**：v1 页面形态 = Agent 列表 + 会话流式 + 工具调用折叠展示。
- **轨迹事件流**：让 SSE 穿过真实 E 循环、经 L 钩子外发 `round/tool/observation/final` 的类型化事件协议（§4.3）。
- **接入层外置**：Client 是交付层扩展点，不占六组件正交位（P4），可被任意终端替换（P3）。
