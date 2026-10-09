package com.flipped.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flipped.picturebackend.model.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 本地消息表 Mapper（阶段 5c）
 */
@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {
}
