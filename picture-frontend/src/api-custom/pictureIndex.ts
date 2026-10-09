/**
 * 图片智能补充相关接口封装（手写）
 *
 * 说明：src/api 目录由 `npm run openapi` 自动生成，手写文件放在那里会被覆盖，
 *      因此该接口暂时放在本目录。待后端接口稳定后，执行 npm run openapi
 *      即可替换为 src/api 中自动生成的客户端方法。
 *
 * 注意：后端把所有 Long 都序列化成了字符串（雪花 id 有 19 位），
 *      因此 id 一律按字符串原样透传，禁止使用 Number() 转换。
 */
import request from '@/request'

/** 后端统一响应结构 */
export interface BaseResponse<T> {
  code?: number
  data?: T
  message?: string
}

/** 智能补充请求 */
export interface PictureAutoFillRequest {
  pictureId: string | number
}

/**
 * 智能补充图片信息：补齐为空的名称、分类、简介、标签
 *
 * 后端是同步执行（内部要调用多模态模型），通常需要十几秒，
 * 所以这里单独放宽超时时间（默认 60 秒不够稳）。
 *
 * @returns data 为本次被补充的字段名数组，例如 ['名称', '标签']；空数组表示没有可补充的内容
 */
export async function autoFillPictureInfo(body: PictureAutoFillRequest) {
  return request<BaseResponse<string[]>>('/api/index/auto-fill', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    timeout: 180000,
  })
}
