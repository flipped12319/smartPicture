package com.flipped.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import com.flipped.picturebackend.model.entity.Picture;
/**
 * 图片表 Mapper 接口
 */
@Mapper
public interface PictureMapper extends BaseMapper<Picture> {
}