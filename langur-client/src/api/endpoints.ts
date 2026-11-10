/** 后端端点常量（集中维护，§4.1 既有端点零改动）。 */
export const API = {
  agents: '/api/agents',
  agent: (id: string) => `/api/agents/${id}`,
  streamChat: '/api/v1/agent/stream/chat',
} as const;