# Agent-Harness 架构设计 v2.1 — 统一模型网关与可插拔向量库（原生混合检索）

> **文档性质**: 架构增量设计（Delta Design）——在 v2.0 As-Built 基线（六组件 + H1–H10 全部完成，320 测试）之上，硬化两条既有接缝：**模型接入网关**与**向量存储端口**。
> **版本**: v2.1 | **日期**: 2026-09-25 | **承接**: `Agent-Harness框架整体架构设计方案-v2.0.md`（六组件 As-Built 权威基线，继续有效）
> **配套路线图**: `Agent-Harness优化RoadMap-2.1.md`（H13 统一模型网关 + H14 可插拔向量库）
> **与 v3.0 的关系**: **正交、同级**。v3.0 是"决策平面（Jev）+ RSI"能力面（J\*/R\* 线）；v2.1 是"模型接入 + 向量存储"基础设施硬化（H\* 线）。二者都坐落在 v2.0 As-Built 基线上，互不依赖——v2.1 让 M2/M3（Embedding/Rerank）与 LLM 供给更稳更省，恰好为 v3.0 决策平面提供更可靠的运行期底座。
> **一句话立场**: **网关统一供给（国内外主流大模型一处适配），向量库可插拔（ES/Milvus 原生混合检索），接缝已就位，只做硬化不做重构。**

---

## 0. v2.1 变更摘要（相对 v2.0）

| 变更类型 | 说明 |
|----------|------|
| **A. 统一模型网关硬化** | 现状已有 `LlmGateway → LLMRouter → ModelRoutableLLMPort` 三层，且 OpenAI/Claude/Gemini + **Qwen/DeepSeek 已实现**。v2.1 补：**配置驱动的 Provider 类型工厂**（`type: openai-compatible\|anthropic\|gemini`，新增 OpenAI 兼容厂商零代码）、**GLM/智谱补齐**、**可配置 model-prefixes**、**修复错误传播使 fallback-chains 真正生效**、**`complete()` 回传 usage**、**API Key 经 `SecretResolver`（env/prop/kms）**、（P2）**Provider 健康熔断** |
| **B. 可插拔向量库 + 原生混合检索** | 现状已有 domain `VectorStore` 端口 + `InMemory`/`PgVector` 两实现，且 `EmbeddingRerankPort` 已提供**应用侧混合检索**（cosine + 词面覆盖）。v2.1 补：**扩展 `VectorStore` 端口**（delete / 批量 upsert / metadata 过滤 / **原生 `hybridSearch`**，均以 default 方法向后兼容）、**`ElasticsearchVectorStore`（dense kNN + BM25 + RRF）**、**`MilvusVectorStore`（dense + sparse + Ranker）**、**`VectorProperties` 配置类**、**修复 pgvector 数据源装配**、**维度一致性守卫** |
| **复用既有资产** | `SecretResolver`/`CompositeSecretResolver`（H7）、`SsrfGuard`、`ServerCircuitBreaker` 模式（H6）、`TokenUsage`（H1）、`MicrometerEvaluationService`（H5）、`CacheBackend`（H4）、`EmbeddingRerankPort`/`VectorMath`（H2） |
| **新增决策 DD14–DD21** | Provider 工厂形态、ES/Milvus 客户端选型、融合算法、命名空间映射、维度策略、**ES RRF 授权核实**、`complete()` usage VO 形态 |
| **原则契合** | 不新增原则；强化 **P3（依赖倒置）/P5（SPI 开闭）/P9（配置驱动）/P10（降级兜底）**；domain 仍零外部依赖（**P1**） |

**与 v2.0 的关系**：v2.0 仍是六组件 As-Built 权威基线。v2.1 **不改动六组件正交拓扑（P4）**——模型网关属 §6「LLM 供给」的硬化，向量库属 C 组件 §12「存储分层 L4」的硬化，均为既有端口的扩展，非新组件。

---

## 1. 现状（As-Is）与痛点

### 1.1 模型接入现状

```
调用方（AgentDomainService / LlmPlanner / DefaultSkillToolGateway / LlmEmbeddingPort）
   │  按 ModelRole（M1–M6）
   ▼
LlmGateway（infra）  ── resolveModel(role) ──▶ role-models.<ROLE> → DEFAULT → provider.model
   │                    withFallback(model)  ──▶ [primary] + fallback-chains[primary] 按序重试
   ▼
LLMRouter（@Primary LLMPort）  ── route(model) ──▶ 首个 supportsModel(model) 命中的 provider，否则 default-provider
   │  注入 List<ModelRoutableLLMPort>（固定 @ConditionalOnProperty Bean 集合）
   ▼
┌─ AbstractOpenAICompatibleLLMAdapter（通用 OpenAI 兼容基类：/chat/completions, Bearer, stream, tools, usage）
│    ├─ OpenAILLMAdapter   （matchIfMissing=true，前缀 gpt-/o1-/o3-，api.openai.com/v1）
│    ├─ QwenLLMAdapter     （前缀 qwen-，dashscope.aliyuncs.com/compatible-mode/v1）  ← 国内已有
│    └─ DeepSeekLLMAdapter （前缀 deepseek-，api.deepseek.com/v1）                    ← 国内已有
├─ ClaudeLLMAdapter（Anthropic Messages API：x-api-key + anthropic-version，/v1/messages，前缀 claude-）
└─ GeminiLLMAdapter（Google REST：?key= 查询参，:generateContent，前缀 gemini-）
```

**已具备**：角色→模型解析、降级链数据结构、OpenAI 兼容通用基类、国内外 5 家（OpenAI/Claude/Gemini/Qwen/DeepSeek）。

**痛点**：

| # | 痛点 | 现状 | 影响 |
|---|------|------|------|
| A1 | **无配置驱动的 Provider 工厂** | 每家 = 一个 `@ConditionalOnProperty` 硬编码类；`ProviderProperties` 仅 `enabled/baseUrl/apiKey/model` | 新增 OpenAI 兼容厂商（GLM/Moonshot/百川/讯飞/阶跃…）都要写新类，违反 P5/P9 |
| A2 | **缺 GLM/智谱** | 无 `glm-` 适配器 | 国内主流缺一家 |
| A3 | **provider 匹配前缀硬编码** | `supportsModel` 前缀写死在各类 | 同厂多模型系列/改名需改码 |
| A4 | **错误被吞，fallback 形同虚设** | 适配器把异常吞成 `"Error: ..."` 字符串返回，`withFallback` 收不到 throw | `fallback-chains` 几乎永不触发（P10 打折） |
| A5 | **`complete()` 丢弃 usage** | 返回 `String`，不回传 token | 补全路径无法纳入 H1 真实计量 |
| A6 | **LLM Key 未过 SecretResolver** | `apiKey` 为明文属性（env 占位），未走 `env:/prop:/kms:` | 与 H7 工具凭证不一致；无 KMS 托管 |

### 1.2 向量库现状

```
KnowledgeRetrievalTool / KnowledgeIngestTool（infra.tool，经 LocalToolRegistrar 注册为 LOCAL 工具）
   ▼
VectorMemoryService（domain，纯领域）
   │  remember(ns,id,content,meta): embeddingPort.embed → vectorStore.upsert
   │  recall(ns,query,topK,budget): embed → vectorStore.search → [rerankPort.rerank] → token 预算截断
   ▼
VectorStore（domain 端口，P3）              EmbeddingPort（domain 端口）        RerankPort（domain 端口）
   ├─ InMemoryVectorStore（默认 store=memory）  ├─ LexicalEmbeddingPort（256 维，默认）  └─ EmbeddingRerankPort
   └─ PgVectorStore（store=pgvector）           └─ LlmEmbeddingPort（1536 维，/embeddings）    score=α·cosine+(1−α)·词面覆盖
```

**已具备**：干净的 `VectorStore` 依赖倒置端口、内存/pgvector 两实现、语义 Embedding（M2）、**应用侧混合检索**（`EmbeddingRerankPort`：向量 cosine + 词面覆盖加权）、知识库工具接入统一工具链。

**痛点**：

| # | 痛点 | 现状 | 影响 |
|---|------|------|------|
| B1 | **端口过薄** | `VectorStore` 仅 `upsert(record)` + `search(ns,float[],topK)` | 无删除/批量/元数据过滤/**原生混合检索**；ES/Milvus 的服务端 BM25+ANN 能力无法表达 |
| B2 | **缺 ES/Milvus 适配** | 仅 memory + pgvector | 生产级向量库（全文+向量混合、水平扩展）缺位 |
| B3 | **混合检索仅在应用侧** | `EmbeddingRerankPort` 拉全量候选再本地打分 | 大规模下网络/内存开销高，无法用 ES BM25 / Milvus 稀疏向量的服务端检索 |
| B4 | **pgvector 数据源未装配** | `PgVectorStore` 依赖 `vectorJdbcTemplate` Bean，但 start 无该 Bean、yml 无 datasource | `store=pgvector` 当前会装配失败 |
| B5 | **无 VectorProperties** | `langur.vector.*` 仅靠 `@ConditionalOnProperty` 切 store，无连接配置类 | ES/Milvus 的 uris/index/collection/凭证/维度无处安放 |
| B6 | **维度不一致隐患** | lexical=256、llm=1536；`PgVectorStore` DDL 硬编码 `vector(256)` | Embedding 维度与索引 schema 不匹配会静默损坏召回 |

### 1.3 结论

**两条接缝都已存在，v2.1 是"硬化 + 补齐适配器"，不是"新建子系统"。** 模型侧把"每厂一类"升级为"配置驱动工厂"，向量侧把"薄端口 + 应用侧混合"升级为"厚端口 + 原生混合下推（应用侧兜底）"。

---

## 2. Stream A — 统一模型网关

### 2.1 目标与原则

- **一处适配、配置扩厂**：新增 OpenAI 兼容厂商 = 纯 YAML（P5/P9），零 Java 代码。
- **国内外主流全覆盖**：GPT / Claude / Gemini（国际）+ Qwen / GLM / DeepSeek（国内），并预留 Moonshot、百川、讯飞、阶跃、MiniMax、零一万物等（多数有 OpenAI 兼容端点 → 零代码）。
- **降级真正生效**：适配器失败必须 throw，`withFallback` 才能按 `fallback-chains` 逐级降级（P10）。
- **计量与密钥一致**：补全路径回传 usage（H1）；Key 经 `SecretResolver`（H7）。
- **domain 契约稳定**：`LLMPort` 保持零外部依赖（P1），仅以 default 方法增量扩展。

### 2.2 增量设计

#### ① 配置驱动的 Provider 类型工厂（核心）✅ 已落地（H13.1，2026-09-25）

`ProviderProperties` 扩展两字段：

```
ProviderProperties {
  boolean enabled;
  String  type;              // 新增：openai-compatible | anthropic | gemini（缺省 openai-compatible）
  String  baseUrl;
  String  apiKey;            // 保留（明文字面量，向后兼容，不推荐）
  String  apiKeyRef;         // 新增：env:/prop:/kms: → 经 SecretResolver 解析（优先于 apiKey）
  String  model;
  List<String> modelPrefixes;// 新增：可配置匹配前缀（替代硬编码 supportsModel）
}
```

新增 infra `ModelProviderFactory`：遍历 `langur.llm.providers.*` 中 `enabled=true` 的条目，按 `type` 实例化对应适配器模板（`openai-compatible` → 复用 `AbstractOpenAICompatibleLLMAdapter` 的**可配置子类**；`anthropic`/`gemini` → 复用 `ClaudeLLMAdapter`/`GeminiLLMAdapter` 逻辑，但改为**按配置构造**而非按厂硬编码），产出 `List<ModelRoutableLLMPort>` 供 `LLMRouter` 注入。

> **注册范式转变**：从"一家一个 `@ConditionalOnProperty` `@Component`"→"一个工厂读配置动态建 Bean"。`LLMRouter` 的构造注入（`List<ModelRoutableLLMPort>`）不变，只是列表来源由工厂产出。既有 5 家适配器逻辑**全部复用**，仅注册方式改为配置驱动 + 内置 `openai` 默认（matchIfMissing 语义保留）。

#### ② GLM/智谱补齐（零代码验证工厂）✅ 已落地（H13.2，2026-09-25）

```yaml
glm:
  type: openai-compatible
  enabled: false
  base-url: https://open.bigmodel.cn/api/paas/v4
  model: glm-4-plus
  model-prefixes: [glm-, chatglm-]
  api-key-ref: env:GLM_API_KEY
```

#### ③ 可配置 model-prefixes ✅ 已落地（H13.3，2026-09-25）

`ModelRoutableLLMPort.supportsModel(model)` 改为匹配 `modelPrefixes` 任一前缀；未配置前缀时回退按 `provider.model` 精确/前缀匹配。`LLMRouter.route` 逻辑不变。

#### ④ 错误传播修复（使 fallback 生效）✅ 已落地（H13.4，2026-09-25）

- 适配器在**传输/HTTP/解析失败**时抛 infra `ModelProviderException`（不再吞成 `"Error: ..."`）。
- `LlmGateway.withFallback` 捕获 `ModelProviderException` → 试链中下一个；链耗尽 → 抛 `IllegalStateException`（现状语义保留）。
- **边界**：模型正常返回的"内容里含 error 文本"**不算失败**（只有传输层/非 2xx/反序列化异常才 throw），避免误降级。

#### ⑤ `complete()` 回传 usage ✅ 已落地（H13.5，2026-09-25）

domain `LLMPort` 增量（default 方法，向后兼容）：

```
final class CompletionResult { String content; TokenUsage usage; }         // 新 VO（domain，纯 JDK）
default CompletionResult completeWithUsage(sys, model, userMessage) {       // 默认委派旧方法
    return new CompletionResult(complete(sys, model, userMessage), TokenUsage.empty());
}
String complete(...)  // 保留
```

OpenAI 兼容基类 override `completeWithUsage` 解析 `usage`（复用 H1 `parseUsage`）。补全路径由此纳入真实计量。

#### ⑥ API Key 经 SecretResolver ✅ 已落地（H13.6，2026-09-25）

`ProviderProperties.apiKeyRef`（`env:/prop:/kms:`）优先于 `apiKey`，经 H7 `CompositeSecretResolver` 解析；解析结果**只注入请求头，绝不落日志**（复用 `Credential` 不生成 toString 的防泄露约定）。缺失 `kms:` 后端时 fail-closed（与 H7 一致）。

#### ⑦（P2）Provider 健康熔断

复用 H6 `ServerCircuitBreaker` 模式，每 provider 独立：连续失败达阈值跳闸 → `LLMRouter`/`withFallback` 快速跳到链中下一个 → 冷却半开试探。指标暴露熔断态。

### 2.3 配置 Schema（`langur.llm.*`，P9）

```yaml
langur:
  llm:
    default-provider: openai
    providers:
      openai:   { type: openai-compatible, enabled: true,  base-url: https://api.openai.com/v1,                        model: gpt-4o,          model-prefixes: [gpt-, o1-, o3-], api-key-ref: env:OPENAI_API_KEY }
      claude:   { type: anthropic,         enabled: false, base-url: https://api.anthropic.com,                        model: claude-3-5-sonnet, model-prefixes: [claude-],       api-key-ref: env:CLAUDE_API_KEY }
      gemini:   { type: gemini,            enabled: false, base-url: https://generativelanguage.googleapis.com,        model: gemini-1.5-pro,  model-prefixes: [gemini-],       api-key-ref: env:GEMINI_API_KEY }
      # —— 国内主流（多为 OpenAI 兼容，零代码）——
      qwen:     { type: openai-compatible, enabled: false, base-url: https://dashscope.aliyuncs.com/compatible-mode/v1, model: qwen-max,        model-prefixes: [qwen-],         api-key-ref: env:QWEN_API_KEY }
      glm:      { type: openai-compatible, enabled: false, base-url: https://open.bigmodel.cn/api/paas/v4,              model: glm-4-plus,      model-prefixes: [glm-, chatglm-],api-key-ref: env:GLM_API_KEY }
      deepseek: { type: openai-compatible, enabled: false, base-url: https://api.deepseek.com/v1,                       model: deepseek-chat,   model-prefixes: [deepseek-],     api-key-ref: env:DEEPSEEK_API_KEY }
      # 预留（纯配置即可启用）：moonshot / baichuan / spark / stepfun / minimax / yi …
    role-models:      { ROUTING: gpt-4o, EMBEDDING: text-embedding-3-small, RERANK: gpt-4o, ACTION: gpt-4o, REASONING: gpt-4o, LONG_CONTEXT: gemini-1.5-pro }
    routing-rules:    { fast: deepseek-chat, powerful: gpt-4o, cheap: qwen-max }
    fallback-chains:  { gpt-4o: [deepseek-chat, qwen-max], gemini-1.5-pro: [gpt-4o] }
```

### 2.4 国内外主流模型能力矩阵

| 模型系列 | 厂商 | 线格式 / type | 端点 | 接入方式 |
|----------|------|---------------|------|----------|
| GPT-4o / o1 / o3 | OpenAI（国际） | openai-compatible | api.openai.com/v1 | 内置默认 |
| Claude 3.5/4 | Anthropic（国际） | anthropic（Messages API） | api.anthropic.com | 专用模板 |
| Gemini 1.5/2 | Google（国际） | gemini（Generative REST） | generativelanguage.googleapis.com | 专用模板 |
| 通义千问 Qwen | 阿里（国内） | openai-compatible | dashscope.aliyuncs.com/compatible-mode/v1 | **已实现** |
| 智谱 GLM-4 | 智谱（国内） | openai-compatible | open.bigmodel.cn/api/paas/v4 | **v2.1 补（纯配置）** |
| DeepSeek | 深度求索（国内） | openai-compatible | api.deepseek.com/v1 | **已实现** |
| Kimi/Moonshot、百川、讯飞星火、阶跃、MiniMax、零一万物 | 国内 | 多为 openai-compatible | 各家兼容端点 | **纯配置扩展（零代码）** |

> **工厂价值**：除 Anthropic/Google 两种非 OpenAI 线格式需专用模板外，**绝大多数国内外厂商都落在 `openai-compatible`**——一次工厂，长期零代码扩厂。

### 2.5 DDD 落点

| 模块 | 变更 | 依赖约束 |
|------|------|----------|
| **langur-domain** | `LLMPort` 增 `CompletionResult` VO + `completeWithUsage` default 方法 | 仅 common+lombok（P1）✅ |
| **langur-infrastructure** | `ProviderProperties` 扩 `type/apiKeyRef/modelPrefixes`；新增 `ModelProviderFactory`、`ModelProviderException`；适配器改可配置构造 + throw 语义 + prefix 配置化；`LlmGateway.withFallback` 捕获异常降级；Key 经 `CompositeSecretResolver`；（P2）provider 熔断 | 实现 domain 端口（P3） |
| **langur-start** | 工厂产出 `List<ModelRoutableLLMPort>` Bean；`SecretResolver` 注入 LLM 侧 | 装配 |
| **langur-common** | （可选，DD14）若定型 `ModelProviderSPI` 契约则在此 | 零 Spring |

---

## 3. Stream B — 可插拔向量库 + 原生混合检索（下推）

> **策略（已定）**：**原生下推优先，应用侧兜底**。ES/Milvus 在服务端做 BM25/稀疏 + ANN 融合；不支持原生的 store（memory/pgvector）或原生不可用时，回退既有 `EmbeddingRerankPort` 应用侧混合。

### 3.1 `VectorStore` 端口扩展（default 方法，向后兼容 P5）✅ 已落地（H14.1/H14.2，2026-09-25）

```
public interface VectorStore {

    // —— 既有（保留）——
    void upsert(VectorRecord record);
    List<VectorRecord> search(String namespace, float[] queryVector, int topK);   // 转调 search(SearchQuery)

    // —— v2.1 增量（均 default，既有实现零改动即编译通过）——
    default void upsertAll(String namespace, List<VectorRecord> records) { records.forEach(this::upsert); }
    default void delete(String namespace, String id) { throw new UnsupportedOperationException(); }
    default List<VectorRecord> search(SearchQuery query) { return search(query.namespace(), query.queryVector(), query.topK()); }

    default boolean supportsHybrid() { return false; }                             // 能力位：VectorMemoryService 据此分支
    default List<VectorRecord> hybridSearch(HybridQuery query) {                   // 原生混合检索（ES/Milvus override）
        throw new UnsupportedOperationException("native hybrid not supported");
    }
}
```

新增 domain 值对象（纯 JDK，P1）：

```
SearchQuery  { String namespace; float[] queryVector; int topK; SearchFilter filter; double minScore; }
HybridQuery  { String namespace; String queryText; float[] queryVector; int topK;
               SearchFilter filter; FusionMode fusion; int rrfK; double lexicalWeight; }
SearchFilter { List<Predicate> predicates; }   // metadata 过滤：EQ / IN / GT / LT / GTE / LTE / EXISTS
FusionMode   { RRF, WEIGHTED }                 // 融合算法（默认 RRF：基于排名、免分数归一化）
```

### 3.2 `VectorMemoryService.recall` 增强（domain）✅ 已落地（H14.3，2026-09-25）

```
recall(ns, query, topK, budget):
  vec = embeddingPort.embed(query)
  if (hybridEnabled && vectorStore.supportsHybrid()):
      matches = vectorStore.hybridSearch(HybridQuery.of(ns, query, vec, topK, fusion, rrfK, weight))   // 原生下推
  else:
      matches = vectorStore.search(ns, vec, topK)                                                        // 纯向量
      if (rerankPort != null) matches = rerankPort.rerank(query, matches, topK)                          // 应用侧混合兜底（现状路径）
  return truncateByTokenBudget(matches, budget)
```

> 现有 memory/pgvector 路径**完全不变**（`supportsHybrid()=false` → 走 search + app-side rerank）。ES/Milvus 打开 `supportsHybrid()=true` 才走原生。

### 3.3 `ElasticsearchVectorStore`（`store=elasticsearch`）✅ 已落地（H14.5，2026-09-25）

- **索引 mapping**：`content` → `text`（BM25，中文可选 `ik_max_word`/`smartcn` 分词器）；`embedding` → `dense_vector(dims=配置维度, similarity=cosine, index=true)`；`namespace` → `keyword`；`metadata.*` → `keyword`/数值。
- **纯向量**：`knn` 查询（`field=embedding, query_vector, k, num_candidates, filter`）。
- **原生混合**：`retriever` 框架的 **`rrf`** 融合 `standard`(BM25 `match`) + `knn`——服务端一次往返完成融合（下推）。
- ⚠️ **授权核实（DD20，强制）**：ES 的 **RRF retriever 历史上属付费授权层（Platinum/Enterprise）**。**落地前必须核实当前版本授权**。若未授权 → **降级方案**：分别发 BM25 查询 + kNN 查询，客户端做 **RRF 融合**（基于排名、免分数归一化，~20 行）或加权归一化。既保留"混合"语义，又不被授权卡死（P10）。
  - ✅ **已核实并落地**：RRF retriever 确属 Platinum+ 付费层 → 缺省 `native-rrf=false` 走"两查询 + 客户端 RRF 融合"（纯静态函数，RRF/WEIGHTED 双模式）；授权环境可开启原生 `retriever.rrf`，任何原生失败自动降级客户端融合。
- **客户端（DD15）**：官方 `co.elastic.clients:elasticsearch-java`（贴合既有 WebClient 风格、避免 Spring Data 锁定）vs `spring-data-elasticsearch`。倾向官方客户端。
  - ⚠️ **落地偏差**：实际以 **WebClient REST 直连**替代官方 SDK——pom 无该依赖，重依赖易冲突且难以离线确定性单测（验收硬约束）；REST 契约与 SDK 等价且更贴合工程既有 WebClient 风格（详见 RoadMap 2.1 §10）。
- **命名空间映射（DD18）**：单索引 + `namespace` keyword 字段过滤（默认，运维简单）vs 每命名空间一索引（隔离强）。✅ 采用单索引 + `namespace` 强制过滤，`_id = namespace::id` 幂等 UPSERT。
- **凭证**：`username` + `password-ref`（经 `SecretResolver`）；`uris` 经 `SsrfGuard` 校验（自部署内网走白名单）；建议 TLS。✅ 已落地：解析为空/无 resolver 均 fail-closed；明文仅注入 Basic 认证头，不落日志/异常/请求体。

### 3.4 `MilvusVectorStore`（`store=milvus`）✅ 已落地（H14.6，2026-09-25）

- **Collection schema**：`id`(PK) / `namespace`(varchar，**partition-key** 高效过滤) / `content`(varchar) / `embedding`(`FLOAT_VECTOR`，HNSW 或 IVF_FLAT，metric=COSINE/IP) / `sparse`(`SPARSE_FLOAT_VECTOR`)。✅ 首写 best-effort 自建（含索引参数），已存在则忽略交由预置 schema。
- **稀疏向量来源**：Milvus 2.5+ 内置 **`BM25` function**（insert 时由 `content` 文本自动生成稀疏向量）→ 原生全文 + 混合；或外接稀疏 Embedding（BGE-M3/SPLADE）。✅ 采用 BM25 function（schema 内声明），sparse 通道检索直传查询文本。
- **原生混合**：`hybrid_search` 传两个 `AnnSearchRequest`（dense + sparse）+ **`RRFRanker`** 或 `WeightedRanker`——服务端融合（下推）。
  - ⚠️ **落地偏差**：RESTful v2 未暴露服务端 `hybrid_search`（Ranker 下推属 SDK 能力）→ 实际为 dense + sparse 两通道 `entities/search` + **客户端融合**（`ClientSideFusion` 纯函数，与 ES DD20 缺省路径同构，RRF/WEIGHTED）；sparse 通道不可用 → 异常上抛由 domain 回退应用侧 `EmbeddingRerankPort`（P10）。
- **客户端（DD16）**：官方 `io.milvus:milvus-sdk-java`（v2 SDK）。
  - ⚠️ **落地偏差**：以 **WebClient REST（RESTful v2）直连**替代官方 SDK——pom 无该依赖，重依赖易冲突且难离线确定性单测（验收硬约束）；详见 RoadMap 2.1 §10。
- **命名空间映射（DD18）**：`namespace` 作 partition-key（默认）vs 每命名空间一 collection。✅ 采用 partition-key + 布尔表达式强制过滤。
- **凭证**：`uri` + `username`/`password-ref`（经 `SecretResolver`）；`SsrfGuard` 校验；建议 TLS。✅ 已落地：解析为空/无 resolver fail-closed；明文仅注入 `Bearer` 认证头。

### 3.5 `VectorProperties` 配置类（`langur.vector.*`，补 B5）✅ 已落地（H14.4，2026-09-25）

```yaml
langur:
  vector:
    store: memory                 # memory | pgvector | elasticsearch | milvus（默认 memory，行为不变）
    dimension: 1536               # 必须与 EmbeddingPort.dimensions() 一致（启动守卫，见 3.7）
    hybrid:
      enabled: true               # 打开后：supportsHybrid 的 store 走原生下推，否则应用侧兜底
      mode: native                # native | app（app = 强制走 EmbeddingRerankPort）
      fusion: rrf                 # rrf | weighted（DD17）
      rrf-k: 60                   # RRF 常数 k
      lexical-weight: 0.3         # weighted 模式下词面权重
    elasticsearch:
      uris: http://localhost:9200
      index: langur_vectors
      username: ""
      password-ref: env:ES_PASSWORD
    milvus:
      uri: http://localhost:19530
      collection: langur_vectors
      username: ""
      password-ref: env:MILVUS_PASSWORD
    pgvector:
      # 补 B4：定义 vectorJdbcTemplate 数据源（url/username/password-ref）
      url: jdbc:postgresql://localhost:5432/langur
      username: postgres
      password-ref: env:PG_PASSWORD
```

### 3.6 混合检索策略（下推 vs 兜底，已定"下推优先"）

| 场景 | 路径 | 说明 |
|------|------|------|
| ES/Milvus + `hybrid.mode=native` | **原生下推** hybridSearch（服务端 BM25/稀疏 + ANN 融合） | 一次往返、可扩展、用得上服务端全文能力 |
| memory / pgvector | **应用侧** search + `EmbeddingRerankPort`（cosine + 词面覆盖） | 现状路径，`supportsHybrid()=false` 自动走此 |
| ES RRF 未授权（DD20） | ES 两查询 + **客户端 RRF 融合** | 降级不丢混合语义（P10） |
| 原生 hybrid 抛异常 | 捕获 → 回退纯向量 search + app-side rerank | 兜底不中断（P10） |
| 二次精排（可选） | 原生 hybrid 取 top-N 后再过 `EmbeddingRerankPort` | 两级检索，精度可选增强 |

> **融合算法（DD17）**：默认 **RRF**（Reciprocal Rank Fusion，基于排名、免分数归一化、跨检索器稳健）；`weighted` 需先归一化 BM25 与 cosine 分数（尺度不同，需谨慎）。
>
> **落地状态（H14.5/H14.6，2026-09-25）**：ES 缺省（`native-rrf=false`，DD20 已核实 RRF retriever 属付费层）与 Milvus（RESTful v2 无服务端 Ranker 下推）均走 **store 级两通道 + 客户端融合**（共用 `ClientSideFusion` 纯函数）；ES 原生 `retriever.rrf` 仅在授权环境显式开启，失败自动降级。对 domain 而言两 store 均 `supportsHybrid()=true`，`hybrid.mode=native` 语义不变。

### 3.7 维度一致性守卫（补 B6）✅ 已落地（H14.8，2026-09-25）

启动时校验 `langur.vector.dimension` == `EmbeddingPort.dimensions()`（lexical=256 / llm=1536）；不一致 → **fail-fast** 明确报错（或按 embedding 维度自动建索引/collection）。ES `dense_vector.dims`、Milvus `FLOAT_VECTOR.dim`、pgvector `vector(n)` 均由该维度驱动，杜绝硬编码 256 与 llm 1536 冲突导致的静默召回损坏。

### 3.8 pgvector 数据源修复（补 B4）

`store=pgvector` 时，start 按 `langur.vector.pgvector.*` 条件装配独立 `DataSource` + `vectorJdbcTemplate` Bean（当前缺失 → 装配失败）；`PgVectorStore` 同步实现新端口方法（delete/batch/filter/hybrid 视能力，pgvector 可 `supportsHybrid()=false` 走应用侧，或用 SQL 全文 `ts_rank` + 向量做 DB 侧融合，列为后续可选）。

### 3.9 DDD 落点

| 模块 | 变更 | 依赖约束 |
|------|------|----------|
| **langur-domain** | `VectorStore` 扩 default 方法（delete/upsertAll/search(SearchQuery)/supportsHybrid/hybridSearch）；新增 `SearchQuery`/`HybridQuery`/`SearchFilter`/`FusionMode` VO；`VectorMemoryService.recall` 增原生分支 | 仅 common+lombok（P1）✅ |
| **langur-infrastructure** | `ElasticsearchVectorStore`、`MilvusVectorStore`；`VectorProperties`；ES/Milvus 客户端封装；凭证经 `SecretResolver`；`InMemory`/`PgVector` 补齐新 default 方法 | 实现 domain 端口（P3） |
| **langur-start** | 按 `store` 条件装配 store Bean；pgvector 数据源；启动维度守卫 | 装配 |

---

## 4. 与既有能力/v3.0 的关系（复用地图）

| 既有资产 | v2.1 复用点 |
|----------|-------------|
| `CompositeSecretResolver`/`KmsClient`（H7） | LLM `api-key-ref` + ES/Milvus `password-ref` 统一解析（env/prop/kms，fail-closed） |
| `SsrfGuard`（H7） | LLM `base-url`、ES `uris`、Milvus `uri` 远端校验 |
| `ServerCircuitBreaker` 模式（H6） | Provider 健康熔断（⑦）/ 向量库健康（G3） |
| `TokenUsage`（H1） | `complete()` usage 回传；Embedding 调用计量 |
| `MicrometerEvaluationService`（H5） | 新增网关/向量库指标（§5） |
| `CacheBackend`（H4） | （可选）Embedding 结果缓存 / 检索结果缓存 |
| `EmbeddingRerankPort`/`VectorMath`（H2） | 应用侧混合兜底 + 二次精排 |
| `KnowledgeRetrieval/IngestTool` | 直接受益于原生混合检索（召回质量↑），无需改工具 |
| **v3.0 决策平面（Jev）** | 正交：M2 EMBEDDING / M3 RERANK 供给更稳更省 → 决策平面运行期底座更可靠；无直接依赖 |

---

## 5. 可观测（接 H5 Micrometer）

| 指标 | 含义 |
|------|------|
| `llm_provider_latency` | 各 provider 往返延迟分布 |
| `llm_provider_errors` | 各 provider 失败计数（按 type/异常类） |
| `llm_fallback_count` | `fallback-chains` 实际触发次数（验证 A4 修复生效） |
| `llm_provider_circuit_state` | provider 熔断态（closed/open/half-open） |
| `vector_store_latency` | 向量库读写延迟 |
| `vector_search_mode` | 计数：`native-hybrid` vs `vector+app-rerank`（观测下推命中率） |
| `vector_store_docs` | 各命名空间文档量 |

---

## 6. 安全与合规

| 关注点 | 措施 |
|--------|------|
| **密钥** | LLM `api-key-ref`、ES/Milvus `password-ref`、pgvector `password-ref` 全经 `SecretResolver`（env/prop/kms），**绝不落日志/明文**；缺 KMS 后端 fail-closed |
| **SSRF** | 所有可配远端地址（base-url/uris/uri）经 `SsrfGuard`；自部署内网走白名单 |
| **数据驻留** | 敏感命名空间（合同/代码）→ **强制自部署 ES/Milvus**，禁发第三方云；LLM 侧同理（敏感场景走自部署/国内合规端点） |
| **传输** | ES/Milvus 建议 TLS；LLM 全 HTTPS |
| **越权** | 向量库 `SearchFilter` 必带 `namespace` 隔离，杜绝跨租户召回 |

---

## 7. 降级与兜底（P10）

| 失败点 | 降级 |
|--------|------|
| 某 provider 不可用 | `fallback-chains` 逐级 → default-provider → 明确报错（修复后真正生效） |
| provider 熔断跳闸 | 快速跳到链中下一个，不阻塞 |
| ES RRF 未授权 | 客户端 RRF/加权融合（DD20 降级） |
| 原生 hybrid 异常 | 回退纯向量 search + app-side rerank |
| ES/Milvus 不可用 | **fail-fast + 健康降级**（不静默转内存，避免数据丢失错觉）；启动期不可达可配置为"惰性重试" |
| Embedding 失败 | 本地哈希嵌入兜底（既有 P10） |

---

## 8. 设计原则契合

| 原则 | v2.1 体现 |
|------|-----------|
| **P1** domain 零依赖 | `VectorStore` 扩展 + 新 VO、`LLMPort.CompletionResult` 均纯 JDK |
| **P3** 依赖倒置 | domain 端口（`VectorStore`/`LLMPort`），infra 实现（ES/Milvus/工厂适配器） |
| **P5** SPI 开闭 | 配置驱动 Provider 工厂（零代码扩厂）；`VectorStore` default 方法（既有实现零改动） |
| **P9** 配置驱动 | `langur.llm.providers.*.type/model-prefixes/api-key-ref`、`langur.vector.{store,hybrid,elasticsearch,milvus,pgvector}` |
| **P10** 降级兜底 | fallback 修复、原生→应用侧混合兜底、RRF 授权降级、store fail-fast |

> **不新增原则**：v2.1 是既有原则在"模型供给 + 向量存储"两处的落地强化。（v3.0 的 P12 决策平面红线与此正交，不受影响。）

---

## 9. 成熟度分级（演进路径）

| 级别 | 能力 | 依赖 | 风险 |
|------|------|------|------|
| **G0** | 现状：固定 vendor Bean、memory/pgvector、应用侧混合 | — | 低（但扩厂改码、混合不下推） |
| **G1** | **网关统一**：配置工厂 + GLM + 前缀配置化 + 错误传播/fallback 修复 + usage + SecretResolver | H13.1–H13.6 | 低（**G1 达成**：H13.1–H13.6 ✅ 2026-09-25） |
| **G2** | **向量库可插拔**：端口扩展 + ES + Milvus + 原生混合下推 + VectorProperties + pgvector 修复 + 维度守卫 | H14.1–H14.8 | 中（ES RRF 授权 DD20 已核实关闭：缺省客户端融合）（进度：H14.1/H14.2/H14.3/H14.4/H14.5/H14.6/H14.8 ✅ 2026-09-25，余 H14.7） |
| **G3** | **弹性**：provider/store 熔断 + 健康探测 + 自动故障转移 + 检索缓存 | H13.7 / G2 | 中 |

---

## 10. 待决策项（DD14–DD21，详见 RoadMap 2.1 §8）

| # | 决策点 | 选项 | 倾向 |
|---|--------|------|------|
| DD14 | Provider 工厂形态 | 纯配置类型工厂 / 保留每厂类 + 配置补充 / 定型 `ModelProviderSPI` | **类型工厂**（openai-compatible 配置化，anthropic/gemini 专用模板），SPI 可选后置 |
| DD15 | ES 客户端 | 官方 `elasticsearch-java` / `spring-data-elasticsearch` | **官方客户端**（避免 Spring Data 锁定，贴合 WebClient 风格） |
| DD16 | Milvus 客户端 | 官方 `milvus-sdk-java` / REST | **官方 SDK** |
| DD17 | 融合算法 | RRF / 加权归一化 | **RRF**（免归一化、稳健） |
| DD18 | 命名空间映射 | 单索引/collection + namespace 字段（过滤/partition-key） / 每命名空间独立 | **单索引 + namespace**（ES keyword 过滤 / Milvus partition-key） |
| DD19 | 维度策略 | 全局单维 / 每命名空间维度 | **全局单维 + 启动守卫**（简化索引 schema） |
| DD20 | **ES RRF 授权** | 授权层内置 RRF retriever / 客户端融合降级 | **落地前核实当前版本授权**；未授权则客户端 RRF 融合（不阻断） |
| DD21 | `complete()` usage VO | 新增 `CompletionResult` default 方法 / 全量改走 `decide()` | **新增 `CompletionResult`**（向后兼容，改动小） |

---

## 11. 与 v2.0 / v3.0 的兼容与演进

- **不破坏 As-Built**：`store=memory` + 既有 provider 配置 → 行为与 v2.0 完全一致；所有端口扩展走 default 方法，既有 `InMemory`/`PgVector`/5 家适配器零改动即编译。
- **默认关闭**：GLM/ES/Milvus 均 `enabled=false`/需显式 `store=` 切换，未启用即降级现状路径（P10）。
- **前向兼容 v3.0**：M2/M3（Embedding/Rerank）供给更稳更省，为决策平面（Jev）运行期底座增益；两线正交，可独立推进。
- **文档维护约定**：每完成一个 H13.x/H14.x 子项，同步更新本文 §2/§3 落点标注与 §9 成熟度，并在 RoadMap 2.1 §11 完成记录补日期与说明；重大变更升版本号并保留历史。

---

> **附：术语对照**
> - **统一模型网关**：`LlmGateway`（角色→模型 + 降级）+ `LLMRouter`（前缀路由）+ 配置驱动 `ModelProviderFactory`（按 type 建适配器）。
> - **原生混合检索（下推）**：ES（BM25 + kNN，RRF）/ Milvus（稀疏 + dense，Ranker）在**服务端**融合全文与向量检索。
> - **应用侧混合（兜底）**：`EmbeddingRerankPort` 在进程内对纯向量召回结果做 `α·cosine + (1−α)·词面覆盖` 重排。
> - **RRF**：Reciprocal Rank Fusion，基于排名倒数融合多路检索结果，免分数归一化。
