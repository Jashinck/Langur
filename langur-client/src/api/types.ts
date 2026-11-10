// 与后端 langur-api DTO 对齐的契约类型（唯一与 Server 耦合的层，§1.4）。

export interface Agent {
  id: string;
  name: string;
  description: string;
  status: string;
  model: string;
  iterationCount: number;
  toolCount: number;
  lastError: string | null;
  answer: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface CreateAgentInput {
  name: string;
  description?: string;
  systemPrompt?: string;
  model?: string;
  temperature?: number;
  maxIterations?: number;
  toolNames?: string[];
}

export interface MessagePartRequest {
  type: string;
  content: string;
  mediaUrl?: string;
}

export interface ChatRequest {
  agentId: string;
  userMessage: string;
  userId?: string;
  tenantId?: string;
  sessionId?: string;
  bizCode?: string;
  messageParts?: MessagePartRequest[];
}

/** SSE 事件名：现有 chat 三项 + R1 轨迹事件（round/tool/observation/final，C5 起外发）。 */
export type StreamEventName =
  | 'message'
  | 'summary'
  | 'done'
  | 'round'
  | 'tool'
  | 'observation'
  | 'final';

export interface StreamEvent {
  event: StreamEventName;
  /** JSON 解析后的负载；非 JSON（如逐 token 的 message）回退为原始字符串。 */
  data: unknown;
}

export interface StreamSummaryData {
  status?: string;
  rounds?: number;
  tokens?: number;
  error?: string;
}