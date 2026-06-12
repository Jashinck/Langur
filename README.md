# 🐒 Langur — 叶猴 Agent 框架

> **Langur** 是 [Skylark](https://github.com/Jashinck/Skylark) 生态中的通用 Agent 能力框架，以叶猴（Langur）命名，轻盈而敏捷。  
> 基于 DDD 六边形架构，支持 ReAct 循环、工具调用、多步规划与多 Agent 协作。

---

## ✨ 特性

| 能力 | 说明 |
|------|------|
| 🔄 ReAct 循环 | Reasoning + Acting，最大迭代次数可配置 |
| 🛠️ 工具调用 | 内置 HTTP 工具，支持自定义工具注册 |
| 📋 多步规划 | PlanningDomainService 拆解复杂任务为步骤 |
| 🧾 Plan 持久化 | 内置 PlanRepository，可查询最近一次执行计划 |
| ⚙️ 异步执行任务 | 支持异步运行、任务状态查询与按 Agent 任务列表 |
| 🤝 多 Agent 协作 | AgentId 隔离，支持跨 Agent 任务分发 |
| 📡 REST API | 标准 HTTP 接口，便于集成任意前端或系统 |
| 🧩 结构化消息输入 | 支持 `messageParts`（text/image/audio/video/file/structured-data） |
| 🔀 多 LLM 支持 | 内置 OpenAI、Claude、Gemini、DeepSeek、Qwen 等多种 LLM 适配器 |
| 📚 工程审计 | 设计文档与代码变更同步提交，便于决策追溯与复盘 |

---

## 🏗️ 架构

### 项目结构

```
langur/
├── langur-common/               # 公共基础层
│   ├── exception/               # 异常体系
│   └── model/                   # 公共类型
├── langur-domain/               # 核心领域层
│   ├── model/
│   │   ├── agent/               # Agent 聚合根（Agent, AgentId, AgentConfig, AgentStatus）
│   │   ├── execution/           # 执行任务模型（AgentRunTask, RunTaskStatus）
│   │   ├── message/             # 消息类型模型（MessagePartType）
│   │   ├── plan/                # 规划模型（Plan, PlanStep, StepStatus）
│   │   └── tool/                # 工具定义（Tool, ToolDefinition, ToolResult）
│   ├── service/                 # 领域服务（AgentDomainService, PlanningDomainService）
│   ├── repository/              # 仓储接口（AgentRepository, PlanRepository, AgentRunTaskRepository）
│   ├── port/                    # 端口定义（LLMPort, ToolProvider）
│   └── event/                   # 领域事件（AgentCreatedEvent, AgentExecutedEvent）
├── langur-application/          # 应用编排层
│   ├── service/                 # 用例（AgentApplicationService, AgentRunTaskApplicationService, ToolRegistryService）
│   ├── command/                 # 命令对象（CreateAgentCommand, RunAgentCommand, MessagePartInput）
│   └── assembler/               # DTO 转换
├── langur-infrastructure/       # 基础设施层
│   ├── llm/                     # LLM 适配器（OpenAI, Claude, Gemini, DeepSeek, Qwen）
│   ├── tool/                    # 内置工具（BuiltinToolRegistry, HttpCallTool）
│   └── persistence/             # 内存持久化（InMemoryAgentRepository, InMemoryPlanRepository, InMemoryAgentRunTaskRepository）
├── langur-api/                  # 接口暴露层
│   ├── rest/                    # REST 控制器（AgentController）
│   └── dto/                     # 请求/响应 DTO
└── langur-start/                # 启动装配层
    ├── config/                  # Spring 配置
    └── resources/               # 配置文件
```

### 文档组织

```
doc/                  # 技术分享与总结类文档（长期知识积累）
├── 01-ddd-hexagonal-multi-module-refactoring.md
└── 02-langur-framework-overview.md

design/               # PR 对应的技术设计文档（决策与实现审计）
└── technical-design-pr-process.md
```

---

## 🚀 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 构建 & 运行

#### 完整项目构建与运行

```bash
# 构建完整项目（包含所有 6 个模块）
mvn clean package -DskipTests

# 启动服务
java -jar langur-start/target/langur.jar
```

#### 分模块开发

```bash
# 运行完整测试套件
mvn clean test

# 构建指定模块
mvn clean package -pl langur-domain -DskipTests

# 跳过测试快速打包
mvn clean package -DskipTests

# 只构建 langur-start 启动包
mvn clean package -pl langur-start -DskipTests
```

服务默认启动在 `http://localhost:8080`。

---

## 📡 API 接口

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

### 执行 Agent

```http
POST /api/agents/{agentId}/run
Content-Type: application/json

{
  "userMessage": "帮我查询明天北京的天气"
}
```

也支持结构化消息输入（用于多模态演进的兼容入口）：

```json
{
  "userId": "u-001",
  "tenantId": "t-001",
  "sessionId": "s-001",
  "messageParts": [
    {"type": "text", "content": "请分析这张图"},
    {"type": "image", "mediaUrl": "https://example.com/demo.png"}
  ]
}
```

### 异步执行 Agent

```http
POST /api/agents/{agentId}/run/async
Content-Type: application/json

{
  "userMessage": "帮我总结今天的工作项",
  "userId": "u-001",
  "tenantId": "t-001",
  "sessionId": "s-001"
}
```

### 其他接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/agents` | 列出所有 Agent |
| `GET` | `/api/agents/{id}` | 获取 Agent 详情 |
| `GET` | `/api/agents/{id}/plan` | 获取最近一次执行计划 |
| `GET` | `/api/agents/{id}/runs` | 获取该 Agent 的异步任务列表 |
| `GET` | `/api/agents/runs/{taskId}` | 获取异步任务状态 |
| `DELETE` | `/api/agents/{id}` | 删除 Agent |

---

## 🔌 扩展与集成

### 支持多种 LLM 后端

Langur 内置支持多种 LLM 提供商，通过实现 `LLMPort` 接口可扩展任意 LLM：

#### 内置 LLM 适配器

| 提供商 | 适配器类 | 特点 |
|------|---------|------|
| **OpenAI** | `OpenAILLMAdapter` | 支持 GPT-4o、GPT-4、GPT-3.5 等 |
| **Claude** | `ClaudeLLMAdapter` | 支持 Claude 3 系列，强大的推理能力 |
| **Gemini** | `GeminiLLMAdapter` | Google Gemini API，支持多模态 |
| **DeepSeek** | `DeepSeekLLMAdapter` | 兼容 OpenAI 协议 |
| **Qwen** | `QwenLLMAdapter` | 阿里云通义千问模型 |

#### 配置示例

```yaml
# application.yml
langur:
  llm:
    default: openai
    providers:
      openai:
        enabled: true
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o
      claude:
        enabled: true
        api-key: ${CLAUDE_API_KEY}
        model: claude-3-opus-20240229
      gemini:
        enabled: true
        api-key: ${GEMINI_API_KEY}
        model: gemini-pro
```

#### 自定义 LLM 适配器

实现 `LLMPort` 接口即可接入任意 LLM：

```java
public interface LLMPort {
    /**
     * 调用 LLM 模型进行对话
     * @param messages 对话历史（role: user/assistant/system, content: 消息内容）
     * @param tools 可用工具定义列表
     * @return LLM 的响应内容（JSON 格式的决策结果）
     */
    String chat(List<Map<String, String>> messages, List<ToolDefinition> tools);
}
```

### 扩展自定义工具

所有工具必须继承 `Tool` 抽象类并在 `ToolRegistry` 中注册：

```java
// 1. 定义自定义工具
public class WeatherTool extends Tool {
    public WeatherTool() {
        super(new ToolDefinition(
            "get_weather",
            "查询指定城市的天气信息",
            Map.of(
                "city", Map.of("type", "string", "description", "城市名称"),
                "days", Map.of("type", "integer", "description", "预报天数")
            )
        ));
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String city = (String) parameters.get("city");
        // 实现查询逻辑
        return ToolResult.success("北京明天晴天，气温 25°C");
    }
}

// 2. 在 ToolRegistry 中注册
@Component
public class CustomToolRegistry implements ToolProvider {
    @Override
    public void registerTools(ToolRegistry registry) {
        registry.register(new WeatherTool());
    }
}

// 3. 创建 Agent 时引用
{
  "name": "weather-agent",
  "toolNames": ["http_call", "get_weather"]
}
```

---

## 🔗 相关项目

- [Skylark](https://github.com/Jashinck/Skylark) — 实时 AI 语音对话系统
- [BlueWhale](https://github.com/Jashinck/BlueWhale) — 蓝鲸记忆框架（与 Langur 配套）

---

## 📄 许可证

Apache License 2.0