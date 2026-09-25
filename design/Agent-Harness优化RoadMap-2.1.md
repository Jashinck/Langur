# Agent-Harness 框架优化 RoadMap 2.1 — 统一模型网关与可插拔向量库

> **依据**：`design/Agent-Harness架构设计-v2.1-统一模型网关与向量库适配.md`（模型网关 + 向量库增量设计）
> **基线**：RoadMap 2.0 全部 H1–H10 已完成并提交（2026-09-25），六组件 As-Built ~95%，320 测试全绿；v2.0 六组件拓扑继续有效，本轮**只硬化两条既有接缝**（模型接入网关 + 向量存储端口），不新建子系统、不改 P4 正交拓扑
> **性质**：执行级路线图（可逐项派发）。本轮使用 **`H13`（统一模型网关）** + **`H14`（可插拔向量库与原生混合检索）** 编号，承接 2.0 的 `H*` 序列；子项以 `H13.x`/`H14.x` 计
> **使用方式**：逐项派发（如"完成 H13.1"），完成后将 `[ ]` 改为 `[x]` 并在 §10"完成记录"补日期与说明；每完成一项同步更新 v2.1 架构文档 §2/§3 落点标注与 §9 成熟度
> **状态**：本轮全部任务 **待派发**（规划态）。**与 RoadMap 3.0（J\*/R\* 决策平面 + RSI）正交**——v2.1 是 H 类基础设施硬化，可独立排期落地，不依赖也不触发 P11/RSI
> **前置门**：**DD20（ES RRF 授权核实）必须在 H14.5 落地前解决**

---

## 1. 总览

### 1.1 里程碑划分（依赖驱动，非日历驱动）

```
N-G1 网关统一 ──────▶ N-G2 向量库适配 ──────▶ N-G3 弹性硬化（可选）
 (配置工厂/降级/密钥)    (端口扩展/ES/Milvus/原生混合)   (熔断/健康/故障转移)
 H13.1–H13.6           H14.1–H14.8                  H13.7 + store 健康
   ↓                     ↓                            ↓
 达 G1 网关统一         达 G2 向量库可插拔             达 G3 弹性
```

> **核心逻辑**：两条接缝（`LlmGateway→LLMRouter→ModelRoutableLLMPort` 与 `VectorStore`）**已就位**，本轮是"配置驱动化 + 补齐适配器 + 修复降级"。**先统一网关（N-G1）**——把"每厂一类"升级为"配置工厂"、补齐 GLM、修复错误传播使 `fallback-chains` 真正生效、Key 过 `SecretResolver`；**再插拔向量库（N-G2）**——扩展 `VectorStore` 端口、加 ES/Milvus 两实现、落地**原生混合检索下推**（应用侧 `EmbeddingRerankPort` 兜底）。**N-G3 弹性**为可选增强。
>
> **成熟度映射（对齐 v2.1 §9）**：`H13.1–H13.6 → G1`；`H14.1–H14.8 → G2`；`H13.7 + store 健康 → G3`。

### 1.2 任务总表

| ID | 任务 | 里程碑 | 优先级 | 依赖 | 估算 | 类型 |
|----|------|--------|--------|------|------|------|
| **H13.1** | 配置驱动 Provider 类型工厂（`ProviderProperties` 扩 `type/modelPrefixes/apiKeyRef` + `ModelProviderFactory`，既有 5 家迁移为配置构造） | N-G1 | P0 | — | M | 模型网关 |
| **H13.2** | GLM/智谱补齐（纯配置验证工厂，零代码扩厂） | N-G1 | P1 | H13.1 | S | 模型网关 |
| **H13.3** | 可配置 `model-prefixes`（`supportsModel` 前缀配置化，替代硬编码） | N-G1 | P1 | H13.1 | S | 模型网关 |
| **H13.4** | 错误传播修复（`ModelProviderException` + `withFallback` 捕获降级，使 `fallback-chains` 生效） | N-G1 | P0 | — | M | 模型网关 |
| **H13.5** | `complete()` 回传 usage（`CompletionResult` VO + `completeWithUsage` default，接 H1 计量） | N-G1 | P1 | H1 | S | 模型网关 |
| **H13.6** | API Key 经 `SecretResolver`（`api-key-ref` env/prop/kms，绝不落日志，接 H7） | N-G1 | P0 | H7 | S | 模型网关 |
| **H13.7** | Provider 健康熔断（复用 H6 `ServerCircuitBreaker`，每 provider 独立，快速跳到降级链下一个） | N-G3 | P2 | H13.4 H6 | M | 弹性（可选） |
| **H14.1** | `VectorStore` 端口扩展（`delete`/`upsertAll`/`search(SearchQuery)`/`supportsHybrid`/`hybridSearch`，均 default 方法向后兼容） | N-G2 | P0 | — | M | 向量库 |
| **H14.2** | domain 值对象（`SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode`，纯 JDK） | N-G2 | P0 | — | S | 向量库 |
| **H14.3** | `VectorMemoryService.recall` 原生分支（`supportsHybrid()` → 原生下推；否则 search + 应用侧 rerank 兜底） | N-G2 | P0 | H14.1 H14.2 | S | 向量库 |
| **H14.4** | `VectorProperties` 配置类（`langur.vector.{store,dimension,hybrid,elasticsearch,milvus,pgvector}`） | N-G2 | P0 | — | S | 向量库 |
| **H14.5** | `ElasticsearchVectorStore`（dense kNN + BM25 + RRF 原生混合；DD15 客户端 / **DD20 授权核实**） | N-G2 | P1 | H14.1 H14.2 H14.4 | L | 向量库 |
| **H14.6** | `MilvusVectorStore`（dense + sparse + `RRFRanker`/`WeightedRanker` 原生混合；DD16 SDK） | N-G2 | P1 | H14.1 H14.2 H14.4 | L | 向量库 |
| **H14.7** | pgvector 数据源修复（条件装配 `vectorJdbcTemplate`）+ 端口补齐 | N-G2 | P2 | H14.1 | S | 向量库 |
| **H14.8** | 维度一致性守卫（启动校验 `vector.dimension == EmbeddingPort.dimensions()`，不一致 fail-fast） | N-G2 | P1 | H14.4 | S | 向量库 |

> 估算：S≈0.5–1 人日，M≈2–4 人日，L≈5–10 人日（单人熟手，含测试）。H14.5/H14.6 的 ES/Milvus 融合逻辑须可**离线确定性单测**（stub/testcontainers），不依赖真实服务。

### 1.3 参考时间线（假设 1–2 名后端，仅供排期参考）

```
周次:  1     2     3     4     5     6     7     8
N-G1  [H13.1][H13.4][H13.6][H13.2/3][H13.5]              ← G1 网关统一（可即刻派发）
N-G2        [H14.1/2][H14.3/4][H14.5      ][H14.6      ][H14.7/8]  ← G2 向量库（DD20 先决）
N-G3                                                  [H13.7 + store 健康]  ← G3 弹性（可选）
```

> N-G1、N-G2 相互独立，可并行；N-G2 的 H14.5 以 **DD20（ES RRF 授权）** 为前置门。

---

## 2. 派发约定（承接 2.0，强化 P1/P3/P5/P9/P10）

- **构建验证**：Maven 必须跑在 **JDK 17**（`export JAVA_HOME=/Users/cn-artisanzhou/Library/Java/JavaVirtualMachines/temurin-17.0.19/Contents/Home`，Lombok 在 JDK 22+/26 中断）；`mvn clean test` 全绿；**装配变更需打包实际启动冒烟**（`/actuator/health` UP、新 Bean 无歧义、无异常日志）。
- **架构红线**：
  - **P1**：domain 新增（`VectorStore` 扩展、`SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode`、`LLMPort.CompletionResult`）**纯 JDK，零外部依赖**。
  - **P3**：ES/Milvus/工厂适配器等实现放 infra，domain 仅依赖端口。
  - **P5**：`VectorStore` 扩展**全走 default 方法**，既有 `InMemoryVectorStore`/`PgVectorStore` **零改动即编译**；Provider 工厂对既有 5 家适配器**复用逻辑、仅改注册方式**。
  - **P9**：新增 OpenAI 兼容厂商 = **纯 YAML**（`providers.<name>.type: openai-compatible`），零 Java；向量库切换 = `langur.vector.store`。
  - **P10**：`fallback-chains` 修复后**实测可触发**；原生混合不支持/异常 → **应用侧 `EmbeddingRerankPort` 兜底**；ES RRF 未授权 → **客户端融合降级**；store 不可用 → **fail-fast + 健康降级，绝不静默转内存**（防数据丢失错觉）。
- **密钥红线**：LLM `api-key-ref`、ES/Milvus/pgvector `password-ref` 一律经 `SecretResolver`（env/prop/kms），**绝不落日志/明文**；缺 KMS 后端 fail-closed（与 H7 一致）。
- **DoD（每项）**：代码 + 纯 JUnit5 测试（domain **禁用 Mockito**，用匿名/内部类 stub；`shouldXxx` 命名；Javadoc 标任务 ID）+ 文档同步（v2.1 §2/§3 落点 + §9 成熟度 + 本 §10 完成记录）；`mvn clean test` 全绿；涉装配则启动冒烟。
- **前置门**：**DD20（ES RRF 授权）在 H14.5 落地前核实**——确认目标 ES 版本 RRF retriever 授权层；未授权则实现"两查询 + 客户端 RRF 融合"降级路径。

---

## 3. N-G1：统一模型网关（H13）

> 目标：把"每厂一个硬编码 `@ConditionalOnProperty` 类"升级为"**配置驱动的 Provider 类型工厂**"，补齐 GLM，修复降级链，统一密钥与计量。**达成 G1。**

### H13.1 配置驱动 Provider 类型工厂（P0，—，M）
- `ProviderProperties` 扩 `type`（`openai-compatible|anthropic|gemini`，缺省 openai-compatible）、`modelPrefixes`、`apiKeyRef`。
- 新增 infra `ModelProviderFactory`：遍历 `enabled=true` 的 `providers.*`，按 `type` 实例化适配器模板，产出 `List<ModelRoutableLLMPort>` 供 `LLMRouter` 注入。
- 既有 5 家（OpenAI/Qwen/DeepSeek/Claude/Gemini）迁移为**配置构造**；保留内置 `openai` 默认（matchIfMissing 语义）。
- **验收**：新增一个 OpenAI 兼容厂商**仅改 YAML** 即被 `LLMRouter` 识别路由；既有 5 家行为回归不变。

### H13.2 GLM/智谱补齐（P1，H13.1，S）
- 纯配置启用 `glm`（`type: openai-compatible`, `base-url: https://open.bigmodel.cn/api/paas/v4`, `model-prefixes: [glm-, chatglm-]`, `api-key-ref: env:GLM_API_KEY`）。
- **验收**：`glm-4-plus` 经工厂路由命中，零 Java 代码——验证 H13.1 工厂的"零代码扩厂"。

### H13.3 可配置 model-prefixes（P1，H13.1，S）
- `ModelRoutableLLMPort.supportsModel` 改匹配配置的 `modelPrefixes` 任一前缀；未配置回退按 `provider.model` 前缀。
- **验收**：改 YAML 前缀即改路由，无需改码；多前缀（`glm-`/`chatglm-`）生效。

### H13.4 错误传播修复 + fallback 生效（P0，—，M）
- 适配器传输/HTTP/反序列化失败 → 抛 infra `ModelProviderException`（不再吞成 `"Error: ..."`）。
- `LlmGateway.withFallback` 捕获 → 试 `fallback-chains` 下一个；链耗尽抛 `IllegalStateException`。
- **边界**：模型正常返回内容含 "error" 文本**不算失败**（只传输层异常才 throw）。
- **验收**：主模型模拟失败 → 实测降级到备用模型（修复前 `llm_fallback_count` 恒 0）。

### H13.5 `complete()` 回传 usage（P1，H1，S）
- domain `LLMPort` 增 `CompletionResult{content,usage}` VO + `completeWithUsage` default（默认委派 `complete`，usage 空）。
- OpenAI 兼容基类 override 解析 `usage`（复用 H1 `parseUsage`）；补全路径纳入真实计量。
- **验收**：`completeWithUsage` 回传非空 usage（provider 提供时），指标区分 real/estimated（承接 H1）。

### H13.6 API Key 经 SecretResolver（P0，H7，S）
- `ProviderProperties.apiKeyRef`（`env:/prop:/kms:`）优先于明文 `apiKey`，经 H7 `CompositeSecretResolver` 解析；结果只注入请求头，**绝不落日志**；缺 kms 后端 fail-closed。
- **验收**：`api-key-ref: env:X` / `kms:Y` 正确解析；日志无密钥；kms 缺失时明确 fail-closed。

### H13.7 Provider 健康熔断（P2，H13.4 H6，M — N-G3 可选）
- 复用 H6 `ServerCircuitBreaker`，每 provider 独立：连续失败跳闸 → 快速跳到降级链下一个 → 冷却半开试探；暴露 `llm_provider_circuit_state`。
- **验收**：provider 持续失败触发熔断，后续请求快速降级不阻塞；冷却后试探恢复。

---

## 4. N-G2：可插拔向量库 + 原生混合检索（H14）

> 目标：把"薄端口 + 应用侧混合"升级为"**厚端口 + 原生混合下推**（应用侧兜底）"，补齐 ES/Milvus。**达成 G2。**

### H14.1 `VectorStore` 端口扩展（P0，—，M）
- default 方法：`upsertAll`、`delete`、`search(SearchQuery)`、`supportsHybrid()`（默认 false）、`hybridSearch(HybridQuery)`（默认抛 UnsupportedOperation）。
- 既有 `search(ns,float[],topK)` 保留并转调 `search(SearchQuery)`。
- **验收**：`InMemoryVectorStore`/`PgVectorStore` **零改动即编译**（P5）；新能力默认关闭不影响现状。

### H14.2 domain 值对象（P0，—，S）
- `SearchQuery`/`HybridQuery`/`SearchFilter`(EQ/IN/GT/LT/GTE/LTE/EXISTS)/`FusionMode`(RRF/WEIGHTED)，纯 JDK（P1）。
- **验收**：VO 不可变、可离线单测；`SearchFilter` 必带 namespace 隔离语义。

### H14.3 `VectorMemoryService.recall` 原生分支（P0，H14.1 H14.2，S）
- `hybrid.enabled && supportsHybrid()` → `hybridSearch`（原生下推）；否则 search + `EmbeddingRerankPort`（应用侧兜底，现状路径）；原生异常 → 回退纯向量 + app rerank。
- **验收**：memory/pgvector 路径**完全不变**；mock 一个 `supportsHybrid()=true` 的 store 验证走原生分支 + 异常兜底。

### H14.4 `VectorProperties` 配置类（P0，—，S）
- `@ConfigurationProperties("langur.vector")`：`store`/`dimension`/`hybrid.{enabled,mode,fusion,rrf-k,lexical-weight}`/`elasticsearch.*`/`milvus.*`/`pgvector.*`。
- **验收**：配置绑定正确；缺省 `store=memory` 行为不变。

### H14.5 `ElasticsearchVectorStore`（P1，H14.1 H14.2 H14.4，L）
- mapping：`content`(text/BM25，中文可选 ik/smartcn) + `embedding`(dense_vector, cosine, dims=配置) + `namespace`(keyword)。
- 纯向量 `knn`；原生混合 `retriever.rrf`(standard BM25 + knn)。**DD20**：RRF 未授权 → 两查询 + 客户端 RRF 融合降级。
- 客户端 **DD15**（倾向官方 `co.elastic.clients:elasticsearch-java`）；`uris` 经 `SsrfGuard`；`password-ref` 经 `SecretResolver`。
- **验收**：离线（stub/testcontainers）验证 upsert/knn/混合融合/namespace 过滤/delete；RRF 授权降级路径可切换。

### H14.6 `MilvusVectorStore`（P1，H14.1 H14.2 H14.4，L）
- schema：`id`/`namespace`(partition-key)/`content`/`embedding`(FLOAT_VECTOR,HNSW,COSINE)/`sparse`(SPARSE_FLOAT_VECTOR，Milvus 2.5+ BM25 function 或外接稀疏)。
- 原生混合 `hybrid_search`（dense + sparse 两 `AnnSearchRequest` + `RRFRanker`/`WeightedRanker`）。
- 客户端 **DD16**（官方 `io.milvus:milvus-sdk-java`）；`uri` 经 `SsrfGuard`；`password-ref` 经 `SecretResolver`。
- **验收**：离线（stub/testcontainers）验证 upsert/dense 检索/稀疏混合/namespace partition 过滤/delete。

### H14.7 pgvector 数据源修复 + 端口补齐（P2，H14.1，S）
- start 按 `langur.vector.pgvector.*` 条件装配 `DataSource` + `vectorJdbcTemplate`（补 B4，当前 `store=pgvector` 装配失败）；`PgVectorStore` 实现新 default 方法（可 `supportsHybrid()=false` 走应用侧）。
- **验收**：`store=pgvector` 可装配启动（有数据源时）；无数据源时明确报错不静默。

### H14.8 维度一致性守卫（P1，H14.4，S）
- 启动校验 `langur.vector.dimension == EmbeddingPort.dimensions()`（lexical 256 / llm 1536），不一致 fail-fast（或按 embedding 维度自动建索引）；ES/Milvus/pgvector 索引维度均由此驱动。
- **验收**：维度不匹配启动即明确报错；匹配时索引/collection schema 维度正确。

---

## 5. N-G3：弹性硬化（可选，G3）

- **H13.7** Provider 健康熔断（见 §3）。
- **向量库健康/故障转移**：ES/Milvus 健康探测 + 不可用 fail-fast + 可选检索结果缓存（复用 H4 `CacheBackend`）。
- **自动故障转移**：多 store/多 provider 主备切换（承接 `fallback-chains` 思路到存储侧）。
- > N-G3 为增强项，非必需；G1/G2 达成即满足"统一网关 + 可插拔向量库"核心诉求。

---

## 6. 待决策项（DD14–DD21）

| # | 决策点 | 选项 | 倾向 | 影响任务 |
|---|--------|------|------|----------|
| DD14 | Provider 工厂形态 | 纯配置类型工厂 / 每厂类 + 配置补充 / 定型 `ModelProviderSPI` | **类型工厂**（openai-compatible 配置化，anthropic/gemini 专用模板），SPI 可后置 | H13.1 |
| DD15 | ES 客户端 | 官方 `elasticsearch-java` / `spring-data-elasticsearch` | **官方客户端**（避免 Spring Data 锁定） | H14.5 |
| DD16 | Milvus 客户端 | 官方 `milvus-sdk-java` / REST | **官方 SDK** | H14.6 |
| DD17 | 融合算法 | RRF / 加权归一化 | **RRF**（免归一化、跨检索器稳健） | H14.5 H14.6 |
| DD18 | 命名空间映射 | 单索引/collection + namespace 字段 / 每命名空间独立 | **单索引 + namespace**（ES keyword / Milvus partition-key） | H14.5 H14.6 |
| DD19 | 维度策略 | 全局单维 / 每命名空间维度 | **全局单维 + 启动守卫** | H14.4 H14.8 |
| **DD20** | **ES RRF 授权** | 授权层内置 RRF retriever / 客户端融合降级 | **落地前核实当前版本授权**；未授权则客户端 RRF 融合（不阻断） | **H14.5（前置门）** |
| DD21 | `complete()` usage VO | 新增 `CompletionResult` default / 全量改走 `decide()` | **新增 `CompletionResult`**（向后兼容，改动小） | H13.5 |

---

## 7. KPI（验收基线）

| 维度 | 指标 | 目标 |
|------|------|------|
| **扩厂成本** | 新增 OpenAI 兼容厂商所需 Java 代码 | **0 行**（纯配置） |
| **模型覆盖** | 国内外主流模型适配家数 | ≥ 6（OpenAI/Claude/Gemini/Qwen/GLM/DeepSeek） |
| **降级有效性** | `llm_fallback_count` 实测触发 | > 0（修复前恒 0） |
| **混合下推** | `vector_search_mode=native-hybrid` 占比（ES/Milvus 启用时） | 主流路径走原生 |
| **召回质量** | 原生混合 vs 纯向量 top-k 命中提升（离线评测集） | 正向提升 |
| **维度安全** | 启动期维度不一致拦截率 | 100% fail-fast |
| **密钥安全** | 日志/明文出现密钥次数 | 0 |
| **构建** | `mvn clean test`（JDK 17）+ 启动冒烟 | 全绿 + `/actuator/health` UP |

---

## 8. 风险登记

| 风险 | 影响面 | 缓解 |
|------|--------|------|
| **ES RRF 授权不确定** | H14.5 | DD20 前置核实 + 客户端 RRF 融合降级（不阻断） |
| ES/Milvus 版本漂移 | H14.5 H14.6 | 锁定客户端版本 + 契约测试 + testcontainers |
| 中文分词插件（ik/smartcn）部署依赖 | H14.5 | analyzer 可选配置，缺省 standard + 应用侧词面覆盖兜底 |
| 维度不一致静默损坏召回 | 全向量库 | H14.8 启动守卫 fail-fast |
| store 不可用误转内存丢数据 | H14.x | fail-fast + 健康降级，**绝不静默兜底内存** |
| Provider 工厂迁移回归既有 5 家 | H13.1 | 充分单测 + 保留内置 openai 默认 + 行为回归 |
| 构建环境 JDK 漂移 | 全项目 | 承接 H11（RoadMap 3.0 N5）toolchain 固定 + CI 校验 |

---

## 9. 与 RoadMap 2.0 / 3.0 的关系

- **承接 v2.0 As-Built**：本轮硬化 v2.0 §6「LLM 供给」与 §12「存储分层 L4」两处既有接缝，**不改六组件正交拓扑（P4）**。
- **与 RoadMap 3.0 正交**：3.0 是 `J*`（决策平面/Jev）+ `R*`（RSI）线；v2.1 是 `H*`（基础设施硬化）线。二者独立排期，v2.1 **不触发 P11/RSI**。M2 EMBEDDING / M3 RERANK 供给更稳更省，为 v3.0 决策平面运行期底座增益。
- **H11/H12**（依赖治理 / 多 Agent）仍保留在 **RoadMap 3.0 N5**（待派发），不在本轮。

---

## 10. 完成记录

| 日期 | 任务 | 说明 |
|------|------|------|
| 2026-09-25 | RoadMap 2.1 制定 | 依据 v2.1 架构设计，拆解为 N-G1/N-G2/N-G3 里程碑、H13（统一模型网关，7 子项）+ H14（可插拔向量库与原生混合检索，8 子项）、DD14–DD21、KPI 与风险登记；全部**待派发**；DD20（ES RRF 授权）设为 H14.5 前置门 |
| 2026-09-25 | H13.1 完成 | 配置驱动 Provider 类型工厂落地：`ProviderProperties` 扩 `type/apiKeyRef/modelPrefixes`（`enabled` 改 `Boolean` 保留 openai matchIfMissing 语义）；新增 `ModelProviderFactory`（内置 6 家模板默认 + 按 type 实例化）、`ConfigurableOpenAICompatibleLLMAdapter`、`ModelPrefixes`；Claude/Gemini 改配置构造模板，删除 OpenAI/Qwen/DeepSeek 每厂硬编码类；start 新增 `ModelProviderConfiguration` 产出 `List<ModelRoutableLLMPort>` Bean。8 项工厂单测 + 既有回归全绿（227 测试）；打包启动冒烟 `/actuator/health` UP |
| 2026-09-25 | H13.4 完成 | 错误传播修复：新增 `ModelProviderException`，三类适配器（OpenAI 兼容基类/Claude/Gemini）传输/非 2xx/反序列化失败一律 throw（不再吞成 "Error: ..."）；边界——正常返回内容含 "error" 文本不算失败；异常消息不拼底层文本防 Gemini 查询参 key 泄露；`LlmGateway.withFallback` 捕获降级并新增 `langur.harness.llm_fallback_count` 计数（role/model/error 标签，接 H5）。6 项离线确定性单测（JDK HttpServer 桩 + 拒绝连接端点）实测主失败→降级备用、链耗尽抛 IllegalStateException；233 测试全绿；启动冒烟 UP |
| 2026-09-25 | H13.6 完成 | LLM API Key 经 H7 `SecretResolver` 解析：`ModelProviderFactory.create` 新增 3 参重载，`api-key-ref`（env:/prop:/kms:）解析成功时注入配置副本（原配置不改写），优先于明文 `api-key`；解析为空回退明文（向后兼容）；`kms:` 引用缺失 KMS 后端时 fail-closed 抛 `IllegalStateException`（绝不静默回退）。`LlmEmbeddingPort` 同链路接入（`@Autowired ObjectProvider<SecretResolver>` 构造 + 4 参测试兼容构造）；start `ModelProviderConfiguration` 传入 resolver；application.yml openai 增 `api-key-ref: env:OPENAI_API_KEY` 示例。6 项离线单测（HttpServer 桩捕获 Authorization 头）验证注入/优先级/回退/fail-closed/密钥不落异常消息；239 测试全绿；启动冒烟 UP（factory created 1 adapter） |
| 2026-09-25 | H13.2/H13.3 完成 | GLM 纯配置接入 + 可配置 model-prefixes：application.yml 新增 `glm` provider（`type: openai-compatible`、`base-url: open.bigmodel.cn/api/paas/v4`、`api-key-ref: env:GLM_API_KEY`、前缀 `[glm-, chatglm-]`，默认 enabled:false 与其他厂商一致），既有 5 家补显式 `model-prefixes`；零 Java 代码。4 项离线单测（HttpServer 桩）验证 `glm-4-plus`/`chatglm-6` 纯配置路由命中、非配置前缀不命中、YAML 前缀覆盖 provider.model 派生、未配置前缀回退按 model 首段派生、无命中回退 default-provider；243 测试全绿；启动冒烟 UP |
| 2026-09-25 | H13.5 完成 | `complete()` 回传 usage：domain `LLMPort` 增 `CompletionResult{content,usage}` VO（null usage 归一空计量）+ `completeWithUsage` default（委派旧 complete，向后兼容）+ `TokenUsage.empty()`；OpenAI 兼容基类 override 解析响应 usage（复用 H1 `parseUsage`，complete() 改为委派）；`LLMRouter`/`LlmGateway` 透传（降级链语义一致），补全路径纳入 H1 real/estimated 计量。3 项 domain 单测（内部类 stub，禁 Mockito）+ 4 项 infra 离线单测（HttpServer 桩）验证真实/空 usage、旧契约回归、主失败→备用仍透传 usage 且 fallback 计数触发；351 测试全绿（domain 104/infra 230/api+app 17）。**G1（H13.1–H13.6）达成** |
| 2026-09-25 | H14.1/H14.2 完成 | `VectorStore` 端口扩展 + domain VO（P5 开闭）：新增 default `upsertAll(ns,records)`/`delete(ns,id)`/`search(SearchQuery)`/`supportsHybrid()`（false）/`hybridSearch(HybridQuery)`（抛 UnsupportedOperation），既有 `search(ns,float[],topK)` 保留；新增纯 JDK VO `SearchQuery`/`HybridQuery`（防御性副本 + withXxx 派生，不可变）/`SearchFilter`（EQ/IN/GT/LT/GTE/LTE/EXISTS，AND 语义，跨数值类型比较，null metadata 安全）/`FusionMode`（RRF/WEIGHTED，默认 RRF、rrf-k=60、lexical-weight=0.3）；namespace 为一等隔离字段不走 filter（越权红线）。`InMemoryVectorStore` 覆写 `search(SearchQuery)`（filter+minScore，minScore≤0 无阈值保持既有路径不变）与 `delete`。9 项 domain VO 单测 + 7 项 infra 单测；`InMemoryVectorStore`/`PgVectorStore` 零改动即编译（P5 验证）；367 测试全绿（domain 113/infra 237/api+app 17） |
| 2026-09-25 | H14.3/H14.4 完成 | `VectorMemoryService.recall` 原生混合分支 + `VectorProperties` 配置类：domain 新增 `HybridSearchOptions`（纯 JDK，缺省 disabled），recall 仅当 `enabled && mode=native && store.supportsHybrid()` 走 `hybridSearch` 下推，原生异常 try/catch 回退 search+应用侧 rerank（P10 不中断），否则完全走既有路径（memory/pgvector 不变）；融合参数（RRF/WEIGHTED、rrf-k、lexical-weight）透传进 `HybridQuery`。infra 新增 `@ConfigurationProperties("langur.vector")` `VectorProperties`（store/dimension/hybrid.*/elasticsearch.*/milvus.*/pgvector.*，password-ref 引用不落明文）；start `HarnessConfiguration` 将 `hybrid.*` 映射为 `HybridSearchOptions` 注入 bean；application.yml 补 hybrid（默认 enabled:false）与 ES/Milvus/pgvector 注释示例。8 项 domain recall 分支单测（内部类 stub，含原生/异常兜底/mode=app/缺省/无能力/预算截断/参数透传）+ 3 项 infra 绑定单测（Binder 离线）；378 测试全绿（domain 121/infra 240/api+app 17）；打包启动冒烟 UP（默认 memory + hybrid off 无 bean 错误） |
| 2026-09-25 | H14.8 完成 | 维度一致性守卫（DD19 全局单维，补 B6）：start 新增 `@Component VectorDimensionGuard`（`@PostConstruct` fail-fast），校验 `langur.vector.dimension` 与 `EmbeddingPort.dimensions()`（lexical=256/llm=embedding.dimensions）一致；不一致抛 `IllegalStateException`（消息含两侧维度，杜绝静默召回损坏），配置留空（≤0）则由 embedding 驱动不校验（向后兼容）。ES/Milvus/pgvector 索引维度均以 `EmbeddingPort.dimensions()` 为准。因 start 主源不含 lombok，改用 `org.slf4j` 直记日志（与既有 `LoggingSpanExporter` 一致）。6 项离线单测（纯 stub）验证未配置派生/一致通过/不一致 fail-fast/PostConstruct 双路径；384 测试全绿（domain 121/infra 240/start 23）；启动冒烟 UP，守卫日志 `effective=256 (configured=0, embedding=256)` |
| 2026-09-25 | H14.5 完成（DD20 前置门已核实关闭） | **DD20 核实结论：ES `retriever.rrf` 属 Platinum+ 付费授权层**（Elastic 官方 Search Labs 与社区多源证实），故缺省 `native-rrf=false`，混合检索走"BM25 match + kNN 两查询 + 客户端 RRF 融合"（免授权，DD17）；授权环境可显式开启原生 `retriever.rrf`，任何原生失败（400/403/版本不支持）自动降级客户端融合（P10 不中断）。infra 新增 `ElasticsearchVectorStore`（`@ConditionalOnProperty store=elasticsearch`，默认 memory 行为不变）：单索引 + `namespace` keyword 强制过滤（DD18 越权红线）、`embedding` dense_vector cosine（首写 best-effort 建索引，dims 由 `vector.dimension`>0 或首条记录驱动）、`_id=namespace::id`（URL 编码）幂等 UPSERT、`_bulk` 批量、`delete` 幂等（404 容忍）、knn `_score=(1+cos)/2` 还原裸 cosine、`SearchFilter` 谓词翻译（term/terms/range/exists）、minScore 客户端过滤；RRF/WEIGHTED 融合为纯静态函数可离线单测。安全：`password-ref` 经 `SecretResolver` 仅注入 Basic 认证头（解析为空/无 resolver 均 fail-closed，绝不静默匿名；明文不落日志/异常/请求体），端点经 `SsrfGuard`（scheme 限 http/https；管理侧配置 host 即显式白名单——URI 来自受信配置非模型输出，不属 SSRF 攻击面）。**DD15 偏差**：以 WebClient REST 直连替代官方 `elasticsearch-java` SDK——pom 无该依赖，重依赖易冲突且难离线确定性单测（验收硬约束），REST 契约与 SDK 等价且贴合工程既有 WebClient 风格。14 项离线单测（JDK HttpServer ES 桩：upsert/建索引映射/认证头/密码不落体/knn+namespace 过滤/谓词翻译+minScore/客户端 RRF/WEIGHTED/native 授权单请求/native 400 自动降级/delete 幂等/_bulk/融合纯函数×2）；398 测试全绿（domain 121/infra 254/start 23）；打包启动冒烟 UP（默认 memory，新条件 bean 未装配，无 bean 错误） |
| 2026-09-25 | H14.6 完成 | infra 新增 `MilvusVectorStore`（`@ConditionalOnProperty store=milvus`，默认 memory 行为不变）：**DD16 偏差**——以 Milvus RESTful v2（WebClient）直连替代官方 `milvus-sdk-java`（理由同 DD15：pom 无依赖、重依赖难离线单测；且 RESTful v2 未暴露服务端 `hybrid_search`/RRFRanker 下推，该能力属 SDK）。schema best-effort 自建：`id`(VarChar PK)/`namespace`(VarChar **partition-key**，DD18)/`content`(VarChar enable_match)/`metadata`(JSON)/`embedding`(FloatVector HNSW COSINE，dims 由 `vector.dimension`>0 或首条记录驱动)/`sparse`(SparseFloatVector) + **BM25 function**（2.5+ 由 content 自动生成稀疏向量）。混合检索：dense + sparse 两通道 `entities/search`（sparse 通道直传查询文本走 BM25 全文）+ **客户端融合**（与 ES DD20 缺省路径同构，融合逻辑抽为共用 `ClientSideFusion` 纯函数，RRF/WEIGHTED）；sparse 通道不可用（无 BM25 function/旧版本）→ 异常上抛由 domain 回退 search+应用侧 rerank（P10 不中断）；store 不可用 fail-fast 绝不静默转内存。upsert/upsertAll 走原生批量、delete 按 `namespace && id` 布尔表达式、COSINE distance 即裸 cosine 同内存语义、minScore 客户端过滤、谓词翻译 `metadata["f"]` 表达式（引号/反斜杠转义防表达式注入）。安全：`password-ref` 经 `SecretResolver` 仅注入 `Bearer user:pass` 头（解析为空/无 resolver fail-closed），端点经 `SsrfGuard`。10 项离线单测（JDK HttpServer Milvus 桩：upsert+schema+认证/密码不落体/fail-closed/批量单请求/delete 表达式/dense+namespace 过滤+minScore/谓词翻译/引号转义/双通道 RRF 融合/sparse 失败上抛）+ 3 项 `ClientSideFusion` 纯函数单测（自 ES 测试迁移共用）；409 测试全绿（domain 121/infra 265/start 23）；打包启动冒烟 UP（默认 memory，新条件 bean 未装配，无 bean 错误） |
