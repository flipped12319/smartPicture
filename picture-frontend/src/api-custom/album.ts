/**
 * 相册相关接口封装（手写）
 *
 * 说明：src/api 目录由 `npm run openapi` 自动生成，手写文件放在那里会被覆盖，
 *      因此相册接口暂时放在本目录。待后端接口稳定后，执行 npm run openapi
 *      即可把这里的调用替换为 src/api 中自动生成的客户端方法。
 */
import request from '@/request'

/** 后端统一响应结构 */
export interface BaseResponse<T> {
  code?: number
  data?: T
  message?: string
}

/** 后端分页结构 */
export interface Page<T> {
  records?: T[]
  total?: number
  current?: number
  size?: number
}

/** 相册 */
export interface AlbumVO {
  /** 后端 Long 统一以字符串下发（见后端 JsonConfig），避免雪花 id 精度丢失 */
  id?: string | number
  name?: string
  introduction?: string
  /** 封面地址（取相册内第一张图片的缩略图） */
  coverUrl?: string
  userId?: string | number
  spaceId?: string | number
  /** 相册内图片数量 */
  pictureCount?: number
  createTime?: string
  editTime?: string
  updateTime?: string
  user?: API.UserVO
}

/** 创建相册请求 */
export interface AlbumAddRequest {
  name: string
  introduction: string
  pictureIds?: (string | number)[]
}

/** 编辑相册请求 */
export interface AlbumEditRequest {
  id: string | number
  name?: string
  introduction?: string
}

/** 相册内图片批量操作请求 */
export interface AlbumPictureRequest {
  albumId: string | number
  pictureIds: (string | number)[]
}

/** 相册分页查询请求 */
export interface AlbumQueryRequest {
  current?: number
  pageSize?: number
  sortField?: string
  sortOrder?: string
}

/** 相册内图片分页查询请求 */
export interface AlbumPictureQueryRequest {
  albumId: string | number
  current?: number
  pageSize?: number
}

/** 创建相册，返回新相册 id */
export async function addAlbum(body: AlbumAddRequest) {
  return request<BaseResponse<number>>('/api/album/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 删除相册 */
export async function deleteAlbumById(id: string | number) {
  return request<BaseResponse<boolean>>('/api/album/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { id },
  })
}

/** 编辑相册（名称、说明） */
export async function editAlbum(body: AlbumEditRequest) {
  return request<BaseResponse<boolean>>('/api/album/edit', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 获取相册详情 */
export async function getAlbumVOById(id: string | number) {
  return request<BaseResponse<AlbumVO>>('/api/album/get/vo', {
    method: 'GET',
    params: { id },
  })
}

/** 分页获取当前用户的相册列表 */
export async function listAlbumVOByPage(body: AlbumQueryRequest) {
  return request<BaseResponse<Page<AlbumVO>>>('/api/album/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 批量把图片加入相册，返回实际新增数量 */
export async function addPictureToAlbum(body: AlbumPictureRequest) {
  return request<BaseResponse<number>>('/api/album/picture/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 批量把图片移出相册，返回实际移出数量 */
export async function removePictureFromAlbum(body: AlbumPictureRequest) {
  return request<BaseResponse<number>>('/api/album/picture/remove', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 分页获取相册内的图片 */
export async function listAlbumPictureByPage(body: AlbumPictureQueryRequest) {
  return request<API.BaseResponsePagePictureVO_>('/api/album/picture/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}
