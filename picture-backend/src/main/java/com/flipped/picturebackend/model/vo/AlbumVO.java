package com.flipped.picturebackend.model.vo;

import com.flipped.picturebackend.model.entity.Album;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 相册封装类
 */
@Data
public class AlbumVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 相册名称
     */
    private String name;

    /**
     * 相册说明
     */
    private String introduction;

    /**
     * 封面地址
     */
    private String coverUrl;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 所属私人空间 id
     */
    private Long spaceId;

    /**
     * 相册内图片数量
     */
    private Long pictureCount;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 创建用户信息
     */
    private UserVO user;

    private static final long serialVersionUID = 1L;

    /**
     * 封装类转对象
     */
    public static Album voToObj(AlbumVO albumVO) {
        if (albumVO == null) {
            return null;
        }
        Album album = new Album();
        BeanUtils.copyProperties(albumVO, album);
        return album;
    }

    /**
     * 对象转封装类
     */
    public static AlbumVO objToVo(Album album) {
        if (album == null) {
            return null;
        }
        AlbumVO albumVO = new AlbumVO();
        BeanUtils.copyProperties(album, albumVO);
        return albumVO;
    }
}
