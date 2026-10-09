package com.flipped.userservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flipped.userservice.model.entity.User;

/**
 * user 表的 Mapper —— 只有本服务持有它。
 */
public interface UserMapper extends BaseMapper<User> {
}
