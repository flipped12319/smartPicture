package com.flipped.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flipped.picturebackend.model.entity.Album;
import org.apache.ibatis.annotations.Mapper;

/**
 * 相册表 Mapper 接口
 */
@Mapper
public interface AlbumMapper extends BaseMapper<Album> {
}
