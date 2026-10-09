// 空间级别枚举
export const SPACE_LEVEL_ENUM = {
  COMMON: 0,
  PROFESSIONAL: 1,
  FLAGSHIP: 2,
} as const

// 空间级别文本映射
export const SPACE_LEVEL_MAP: Record<number, string> = {
  0: '普通版',
  1: '专业版',
  2: '旗舰版',
}

// 空间级别选项映射
export const SPACE_LEVEL_OPTIONS = Object.keys(SPACE_LEVEL_MAP).map((key) => {
  const value = Number(key) // Convert string key to number
  return {
    label: SPACE_LEVEL_MAP[value],
    value,
  }
})

// ────────────────────────── 空间类型（私有 / 团队）──────────────────────────

export const SPACE_TYPE_ENUM = {
  PRIVATE: 0,
  TEAM: 1,
} as const

export const SPACE_TYPE_MAP: Record<number, string> = {
  0: '私有空间',
  1: '团队空间',
}

export const SPACE_TYPE_OPTIONS = Object.keys(SPACE_TYPE_MAP).map((key) => {
  const value = Number(key)
  return {
    label: SPACE_TYPE_MAP[value],
    value,
  }
})

// ────────────────────────── 空间成员角色 ──────────────────────────

export const SPACE_USER_ROLE_ENUM = {
  /** 只能看 */
  VIEWER: 0,
  /** 能看能上传 */
  UPLOADER: 1,
  /** 能看能上传还能编辑 */
  EDITOR: 2,
  /** 空间管理员（空间创建者） */
  MANAGER: 3,
} as const

export const SPACE_USER_ROLE_MAP: Record<number, string> = {
  0: '只能看',
  1: '能看能上传',
  2: '能看能上传还能编辑',
  3: '管理员',
}

/** 可分配的角色选项（管理员由创建者天然拥有，不可分配） */
export const SPACE_USER_ROLE_OPTIONS = [0, 1, 2].map((value) => ({
  label: SPACE_USER_ROLE_MAP[value],
  value,
}))

// ────────────────────────── 空间成员状态 ──────────────────────────

export const SPACE_USER_STATUS_MAP: Record<number, string> = {
  0: '邀请中',
  1: '已加入',
  2: '已拒绝',
}
