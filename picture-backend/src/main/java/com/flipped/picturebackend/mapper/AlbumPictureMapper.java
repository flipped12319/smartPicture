package com.flipped.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flipped.picturebackend.model.entity.AlbumPicture;
import org.apache.ibatis.annotations.Mapper;

/**
 * 相册图片关联表 Mapper 接口
 */
@Mapper
public interface AlbumPictureMapper extends BaseMapper<AlbumPicture> {
}
