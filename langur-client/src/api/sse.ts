import { API } from './endpoints';
import { getBearerToken } from './http';
import type { ChatRequest, StreamEvent, StreamEventName } from './types';

// SSE 客户端：chat 端点为 POST，EventSource 不支持 POST body，故用 fetch + ReadableStream
// 逐帧解析（§1.4：原生 fetch + ReadableStream 消费 SSE）。

function parseData(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

async function* sseReader(body: ReadableStream<Uint8Array>): AsyncGenerator<StreamEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName: StreamEventName = 'message';
  const dataLines: string[] = [];

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      let newlineIdx: number;
      while ((newlineIdx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newlineIdx).replace(/\r$/, '');
        buffer = buffer.slice(newlineIdx + 1);
        if (line === '') {
          // 空行终结一帧：data 可能跨多行（data 行拼接）
          if (dataLines.length > 0) {
            const raw = dataLines.join('\n').trim();
            yield { event: eventName, data: raw === '' ? '' : parseData(raw) };
            dataLines.length = 0;
          }
        } else if (line.startsWith('event:')) {
          eventName = line.slice('event:'.length).trim() as StreamEventName;
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function streamChat(
  request: ChatRequest,
  onEvent: (event: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
  };
  const token = getBearerToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const res = await fetch(API.streamChat, {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`SSE 连接失败（HTTP ${res.status}）`);
  }
  for await (const event of sseReader(res.body)) {
    onEvent(event);
  }
}