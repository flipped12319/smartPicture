package com.flipped.spaceservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flipped.spaceservice.model.entity.SpaceUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 空间成员表 Mapper 接口
 */
@Mapper
public interface SpaceUserMapper extends BaseMapper<SpaceUser> {
}
