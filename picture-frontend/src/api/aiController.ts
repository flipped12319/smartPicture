// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** chat POST /api/ai/chat */
export async function chatUsingPost(body: API.AiChatRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseAiChatResponse_>('/api/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
