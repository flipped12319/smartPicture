/**
 * 团队空间成员相关接口封装（手写）
 *
 * 说明：src/api 目录由 `npm run openapi` 自动生成，手写文件放在那里会被覆盖，
 *      因此这些接口暂时放在本目录。待后端接口稳定后可迁移到 src/api。
 *
 * 注意：后端把所有 Long 都序列化成了字符串（雪花 id 有 19 位），
 *      因此所有 id 一律按字符串原样透传，禁止使用 Number() 转换。
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

/**
 * 在自动生成的 SpaceVO 基础上补充团队空间相关字段
 * （自动生成的类型需要执行 npm run openapi 后才会带上这两个新字段）
 */
export interface SpaceVOExt extends API.SpaceVO {
  /** 空间类型：0-私有 1-团队 */
  spaceType?: number
  /** 当前登录用户在该空间中的角色：0-只读 1-可上传 2-可编辑 3-管理员 */
  currentUserRole?: number
}

/** 空间成员 */
export interface SpaceUserVO {
  /** 空间成员记录 id */
  id?: string | number
  spaceId?: string | number
  userId?: string | number
  /** 0-只读 1-可上传 2-可编辑 3-管理员 */
  spaceRole?: number
  /** 0-邀请中 1-已加入 2-已拒绝 */
  status?: number
  inviterId?: string | number
  createTime?: string
  updateTime?: string
  user?: API.UserVO
  inviter?: API.UserVO
  space?: SpaceVOExt
}

/** 邀请用户加入团队空间，返回空间成员记录 id */
export async function inviteSpaceUser(body: {
  spaceId: string | number
  userAccount: string
  spaceRole: number
}) {
  return request<BaseResponse<string>>('/api/spaceUser/invite', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 接受邀请 */
export async function acceptInvitation(id: string | number) {
  return request<BaseResponse<boolean>>('/api/spaceUser/accept', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { id },
  })
}

/** 拒绝邀请 */
export async function rejectInvitation(id: string | number) {
  return request<BaseResponse<boolean>>('/api/spaceUser/reject', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { id },
  })
}

/** 修改成员的空间权限 */
export async function updateSpaceUserRole(body: { id: string | number; spaceRole: number }) {
  return request<BaseResponse<boolean>>('/api/spaceUser/update/role', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 移除成员 / 退出空间 */
export async function removeSpaceUser(id: string | number) {
  return request<BaseResponse<boolean>>('/api/spaceUser/remove', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { id },
  })
}

/** 分页查询空间成员列表 */
export async function listSpaceUserByPage(body: {
  spaceId: string | number
  current?: number
  pageSize?: number
}) {
  return request<BaseResponse<Page<SpaceUserVO>>>('/api/spaceUser/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
  })
}

/** 分页查询我收到的邀请 */
export async function listMyInvitation(body?: { current?: number; pageSize?: number }) {
  return request<BaseResponse<Page<SpaceUserVO>>>('/api/spaceUser/my/invitation/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body ?? {},
  })
}

/** 分页查询与我有关的空间（我创建的 + 我加入的） */
export async function listMySpaceByPage(body?: { current?: number; pageSize?: number }) {
  return request<BaseResponse<Page<SpaceVOExt>>>('/api/spaceUser/my/space/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body ?? {},
  })
}
