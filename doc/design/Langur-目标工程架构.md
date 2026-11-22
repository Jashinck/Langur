# Langur 目标工程架构（三参照融合）

> 融合 **AgentScope-Java**、**BlueWhale**、**DeepSeek Harness** 三家工程组织方案，收敛为 Langur 的最终目标工程架构。
> 设计立场：**守住 Langur DDD 红线**——P1 domain 纯度、P2 单向依赖、P4 六组件正交，包名/端口/依赖方向不变。融合的是"组织粒度 + 交付拓扑"，**不是架构重写**。
> 上游依据：[Langur-jar化分析](Langur-jar化分析.md)（§4 三参照）、[Client-端到端交付-迭代设计](Client-端到端交付-迭代设计.md)、[Harness-地基优化-迭代设计](Harness-地基优化-迭代设计.md)。

---

## 0. 一句话架构

**Langur = 稳定内核（common/domain/application）+ 可插拔能力适配器 jar（seam 三角色）+ 外置接入层（host/client 两半 + 多接入 SDK）+ BOM/starter/examples 交付配套**；Server 默认、Embedded 降级；向量能力下沉 `skylark-index` 与 BlueWhale 共享；拆分由共享需求驱动、渐进式 Maven 化。

---

## 1. 三参照贡献矩阵（谁贡献了什么）

| 架构维度 | AgentScope-Java | BlueWhale | DeepSeek Harness | Langur 采纳 |
|---|---|---|---|---|
| 分层骨架 | core/harness/service 能力分层 | DDD + Client/Server | vendor/packages/apps 三层 | **保留 DDD 六边形**，借"内核/适配器/接入"三层视角 |
| 适配器组织 | extensions 平铺矩阵 | store/index/extract 独立模块 | 能力系列化（seam+实现+工具） | infra 拆 8 适配器 jar，内部 seam 三角色 |
| 解耦粒度 | 端口/适配器 | 端口下沉 domain | **Definition/Provider/Consumer 三角色** | 适配器内再分三角色，Consumer 只依赖 Definition |
| 版本管理 | 独立 BOM | dependencyManagement | pnpm workspace | 独立 `langur-bom` |
| 跨项目共享 | — | **skylark-index 共享库** | vendor 复用 | `langur-vector` → `skylark-index` |
| 客户端工程 | service/SDK 分离 | client 薄 SDK + Server/Embedded | **host/client 两半 + slot UI** | host(api/start) / client(React) + `langur-sdk` |
| 多接入 | spring-boot-starters | REST + MCP + SDK | **JSON-RPC SDK / ACP / Python** | `langur-sdk` + MCP/ACP 接入面 |
| 交付配套 | examples/distribution/starter | — | apps 组合包 + bundle profile | `langur-examples`/`distribution`/`starter` |
| 拆分节奏 | — | **渐进式 Maven 化** | 包按组渐进 | 共享需求驱动，不一次拆完 |
| 守卫 | — | ArchUnit 层守卫 | **依赖图 CI 新鲜度门禁** | ArchUnit 三不变量 + 依赖图 CI |
| 横切/生命周期 | middleware + hook 双拦截 | — | hooks 桥接 | middleware（横切）vs hook（生命周期）正交 |

---

## 2. 目标全景（分层 + 模块拓扑）

```
┌─────────────────────────── 接入/交付层（外置，可替换，P3）──────────────────────────┐
│  host 半侧                          client 半侧 / 多接入                          │
│  langur-api（REST+治理middleware）   langur-client（React，src/api 契约层）         │
│  langur-start（Server装配+静态托管）  langur-sdk（无Spring薄SDK，JSON-RPC/HTTP）     │
│                                      langur-mcp / langur-acp（多接入，规划）        │
│  交付配套：langur-bom · langur-starter · langur-examples · langur-distribution     │
└───────────────────────────────────────┬──────────────────────────────────────────┘
                                         │ 仅 HTTP/RPC/SSE 契约耦合（编译期零依赖）
┌───────────────────────────────────────┴──────────────────────────────────────────┐
│                        能力适配器层（一个可替换能力 = 一个 jar）                     │
│  langur-llm · langur-tools · langur-persistence · langur-security                 │
│  langur-observability · langur-decision · langur-rsi                              │
│  skylark-index（= langur-vector，与 BlueWhale 共享）                               │
│  规划：langur-sandbox（H15.5）· langur-workspace（H15.4）                          │
│  每个 jar 内部 seam 三角色：Definition（端口）/ Provider（实现）/ Consumer（工具/UI） │
└───────────────────────────────────────┬──────────────────────────────────────────┘
                                         │ 适配器只依赖内核，绝不反向（P2）
┌───────────────────────────────────────┴──────────────────────────────────────────┐
│                          内核层（稳定 API，零适配器依赖）                            │
│  langur-common（SPI契约 + Cache/Lock + 异常）                                      │
│  langur-domain（六组件 C/S/T/E/L/V + Jev值对象 + RSI引擎 + 全部端口，P1零外部依赖）   │
│  langur-application（用例编排）                                                    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**模块清单（按层）**：

| 层 | 模块 | 来源/状态 | 发布 |
|---|---|---|---|
| 内核 | `langur-common` / `langur-domain` / `langur-application` | 现状保留 | ✅ 框架 |
| 适配器 | `langur-llm` | infra/llm 拆出 | ✅ 框架 |
| 适配器 | `langur-tools` | infra/tool + harness/tool + skill | ✅ 框架 |
| 适配器 | `skylark-index`（langur-vector） | infra/harness/context + vector，**跨项目共享** | ✅ 框架 |
| 适配器 | `langur-persistence` | infra/cache + jpa + state | ✅ 框架 |
| 适配器 | `langur-security` | infra/harness/security | ✅ 框架 |
| 适配器 | `langur-observability` | infra/harness/evaluation | ✅ 框架 |
| 适配器 | `langur-decision` | infra/harness/decision | ✅ 框架 |
| 适配器 | `langur-rsi` | infra/harness/rsi（引擎留 domain） | ✅ 框架 |
| 适配器 | `langur-sandbox` / `langur-workspace` | H15.5 / H15.4 规划 | 🔜 |
| 接入 | `langur-api` / `langur-start` | 现状保留（host 半侧） | ⚠️ 应用 |
| 接入 | `langur-client` | C2 已建（React，client 半侧） | ⚠️ 应用 |
| 接入 | `langur-sdk` | 新增（无 Spring 薄 SDK） | ✅ 框架 |
| 接入 | `langur-mcp` / `langur-acp` | 规划（多接入） | 🔜 |
| 交付 | `langur-bom` / `langur-starter` / `langur-examples` / `langur-distribution` | 新增 | ✅ 框架 |

---

## 3. 内核层（稳定 API，几乎不动）

- `langur-common`：SPI 契约（`SecurityPolicySPI`/`DecisionEngineSPI`/`ToolProviderSPI` 等）+ `CacheBackend`/`DistributedLock` + 异常。
- `langur-domain`：Harness 六组件（C/S/T/E/L/V）+ Jev 决策平面值对象 + RSI 引擎 + **全部端口**（`LLMPort`/`DecisionPort`/`VectorStore`/`EmbeddingPort`/仓储）。**零外部依赖（P1）**，ArchUnit 守护。
- `langur-application`：用例编排（`AgentApplicationService` 等）。

> 借 AgentScope"core 是最小推理内核"视角：内核 = 领域机制，稳定 API，第三方可直接依赖。**内核不依赖任何适配器**（P2）。

---

## 4. 能力适配器层（seam 三角色）

**一个可替换能力 = 一个 jar**（jar 化分析 §3）。每个适配器 jar 内部按 DeepSeek 能力 seam 三角色组织：

```
langur-llm/
├── definition/   端口引用（LLMPort 在 domain，此处仅依赖）   ← Definition
├── provider/     LlmGateway / LLMRouter / 4 适配器 / 熔断    ← Provider（具体实现）
└── consumer/     面向模型的消费面（如需）                     ← Consumer，只依赖 Definition
```

- **铁律（借 DeepSeek）**：Consumer（面向模型工具/UI/钩子）依赖 Definition，**绝不依赖 Provider**。`LLMPort` 可替换，消费面只用端口。
- **装配（借 Langur 既有 + AgentScope auto-config）**：每个 jar 自带 `*Configuration` + `AutoConfiguration.imports`；缺 jar = 缺 Bean = P10 降级自然成立。
- **依赖方向**：适配器只依赖内核；适配器间允许向下依赖（`skylark-index → langur-llm`、`langur-rsi → llm + tools`），**任何适配器不得被内核依赖**。
- **裁剪**：LLM/向量/决策最常单独替换，建议不合并；security+observability 可视团队偏好合并为 `langur-ops`。

---

## 5. 接入层与客户端（host/client 两半 + 多接入）

借 DeepSeek host/client 对称切分 + BlueWhale Client/Server：

| 半侧 | Langur 模块 | 职责 |
|---|---|---|
| **host（宿主）** | `langur-api` | REST 控制器 + 治理 middleware（鉴权/限流/幂等/TraceId）= API 网关 |
| **host（宿主）** | `langur-start` | Spring 装配 + `application.yml` + **静态托管**（dist→static，C3）= webserver + frontend-static |
| **client（浏览器）** | `langur-client` | React 工作台；`src/api` 契约层 = connection（仅 RPC/SSE 耦合）；UI 可演进为 slot 插件化 |
| **client（进程外）** | `langur-sdk` | 无 Spring 薄 SDK（JSON-RPC/HTTP），供非 Spring 消费方（借 bluewhale-client + dsh-sdk） |
| **多接入** | `langur-mcp` / `langur-acp` | MCP server / Agent Client Protocol，服务跨语言/第三方 Agent（规划） |

- **耦合边界**：host 与 client 仅经 HTTP/RPC/SSE 契约，编译期零依赖（P3 接入层外置）。client 可被任意终端替换。
- **middleware vs hook 正交（借 AgentScope）**：横切关注点（鉴权/限流/重试/TraceId）走 middleware 链（`GovernanceFilter`）；生命周期点走 L 组件 `LifecycleHookEngine`。二者不混。
- **UI slot 化（借 DeepSeek，C4 演进方向）**：工具折叠卡/消息区/设置区做成 slot 扩展点（`ui-slots` 注册 + `ui-renderer` 绑定 React），避免单体 `App.tsx`。v1 先单体，v2 slot 化。

---

## 6. 部署形态（Server 默认 / Embedded 降级）

借 BlueWhale §13.1：同一套 domain/application，两种 `@AutoConfiguration` 装配入口。

| 形态 | 用法 | 地位 |
|---|---|---|
| **Server（默认）** | `langur-start` jar：REST + MCP + SDK + 静态托管，服务 langur-client / BlueWhale / 第三方 | 主路径（跨语言消费方需进程边界） |
| **Embedded（降级）** | 第三方依赖 `langur-bom + common + domain + 所需适配器`，同进程 DI | 单机测试/小规模 |

---

## 7. 机制/策略分离 + 跨项目共享

借 BlueWhale §3/§12：

- **机制 vs 策略**：Langur 框架 jar = 机制（推理/工具/记忆/治理）；`langur-api`/`langur-start` 与第三方应用 = 策略（预算/渲染/注入/编排）。jar 化后第三方可只引机制 jar 自建策略。
- **skylark-index 共享库**：`langur-vector` 直接设计为 Skylark 生态共享索引库（`org.skylark.index`），下沉 Langur 已验证的 `VectorStore`/`EmbeddingPort`/4 实现，**Langur 与 BlueWhale 共用、严禁双写**。这是 Langur jar 化独有的跨项目维度。
- **职责边界**：BlueWhale = 上游上下文基座（加工/治理/resolve 供给）；Langur = 消费端（装配/渲染/注入）。二者经 `ContextBundle` 契约耦合。

---

## 8. 守卫与治理

| 守卫 | 内容 | 来源 |
|---|---|---|
| **ArchUnit 三不变量** | ① domain 纯度（零适配器 import）② 全模块依赖无环 ③ common 无状态 Bean | Langur 既有 |
| **依赖图 CI 门禁** | `gen-module-graph` 生成模块依赖图，CI 校验新鲜度（与 ArchUnit 互补：守不变量 + 守可视化） | DeepSeek |
| **BOM 锁版本** | `langur-bom` 单一版本源，第三方引 bom + 所需适配器即可组装 | AgentScope |
| **条件装配降级** | 缺 jar = 缺 Bean = P10 降级 | Langur 既有 |

---

## 9. 分阶段落地（渐进式 Maven 化）

借 BlueWhale"避免过早 Maven 化"+ jar 化 §7 + Client C1–C6 + H15，**拆分由共享需求驱动**：

| 阶段 | 动作 | 验收 | 状态 |
|---|---|---|---|
| **P0 地基** | 包内分层 + ArchUnit + 依赖图；C1 模块收敛（六模块入 langur-server） | 全量测试绿、ArchUnit 绿 | ✅ C1 已完成 |
| **P1 移类** | `SecretResolver` 移 common（解 llm↔tool 环） | 全量测试绿 | ✅ 已完成（81558b9） |
| **P2 拆 llm** | `langur-llm` 先拆（内部扇出最多） | start 能跑、测试绿 | ✅ 已完成（0169ce3） |
| **P3 拆共享索引** | `langur-vector` → `skylark-index`（**跨项目共享需求驱动**，与 BlueWhale 对齐） | 两项目共用、无双写 | ⏸ 暂缓（2026-11-23 决策：按需再建；skylark-index 尚未存在） |
| **P4 按需拆其余** | tools/persistence/security/observability/decision/rsi 按需拆，**不一次拆完**；其余先保持包内分层 + ArchUnit | 每拆一个跑全量测试 | 🔜 部分：security/observability/decision ✅（余 tools/persistence/rsi/vector） |
| **P5 交付配套** | `langur-bom` + `langur-sdk` + `langur-starter` + auto-config + 依赖图 CI | ArchUnit 绿、package 绿 | 🔜 |
| **P6 验证** | `langur-examples` 最小示例（bom+common+domain+llm+tools 跑通 ReAct）；`langur-distribution` | 第三方最小依赖可运行 | 🔜 |
| **Client 并行** | C2 脚手架 → C3 静态托管 → C4 工作台 UI（slot 化演进）→ C5 轨迹流 → C6 联调 | G-C2/G-C3 | ✅ C2 已完成 |
| **H15 并行** | H15.4 workspace / H15.5 sandbox 成熟后独立成 `langur-workspace`/`langur-sandbox` | 可插拔工程层 | 部分已落地 |

> 关键纪律（BlueWhale）：结构性共享库（skylark-index）优先真拆；其余适配器可先包内分层 + ArchUnit 守卫，按需再 Maven 化。llm 按内部扇出先拆，vector 按外部共享先拆。

---

## 10. 与现有文档的关系

| 文档 | 关系 |
|---|---|
| [Langur-jar化分析](Langur-jar化分析.md) | 本文 §4 适配器拆分 + §8 BOM 的依据；§4 三参照借鉴在此收敛为最终拓扑 |
| [Client-端到端交付-迭代设计](Client-端到端交付-迭代设计.md) | 本文 §5 host/client + §9 Client 并行线的依据 |
| [Harness-地基优化-迭代设计](Harness-地基优化-迭代设计.md) | 本文 §4 sandbox/workspace + §9 H15 并行线的依据 |
| [Agent-Harness架构设计](Agent-Harness架构设计.md) | 内核层六组件/ Jev / RSI 的领域设计（本文不改） |

**本文定位**：三参照融合后的**目标工程架构快照**——jar 化分析回答"怎么拆"，Client/H15 迭代回答"怎么建"，本文回答"最终长什么样"。