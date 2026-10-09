package com.flipped.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.flipped.picturebackend.model.dto.album.AlbumAddRequest;
import com.flipped.picturebackend.model.dto.album.AlbumEditRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureQueryRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureRequest;
import com.flipped.picturebackend.model.dto.album.AlbumQueryRequest;
import com.flipped.picturebackend.model.entity.Album;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.AlbumVO;
import com.flipped.picturebackend.model.vo.PictureVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 相册服务接口
 */
public interface AlbumService extends IService<Album> {

    /**
     * 创建相册并保存到当前用户的私人空间
     *
     * @param albumAddRequest 创建请求（可携带初始图片）
     * @param loginUser       当前登录用户
     * @return 新相册 id
     */
    long addAlbum(AlbumAddRequest albumAddRequest, User loginUser);

    /**
     * 编辑相册名称与说明
     */
    void editAlbum(AlbumEditRequest albumEditRequest, User loginUser);

    /**
     * 删除相册（同时清理相册与图片的关联关系）
     */
    void deleteAlbum(long albumId, User loginUser);

    /**
     * 校验相册参数
     *
     * @param album 相册
     * @param add   是否为创建操作
     */
    void validAlbum(Album album, boolean add);

    /**
     * 校验用户是否有权操作该相册（仅创建者本人或管理员）
     */
    void checkAlbumAuth(User loginUser, Album album);

    /**
     * 获取相册封装类
     */
    AlbumVO getAlbumVO(Album album, HttpServletRequest request);

    /**
     * 批量获取相册封装类
     */
    Page<AlbumVO> getAlbumVOPage(Page<Album> albumPage, HttpServletRequest request);

    /**
     * 构造相册查询条件
     */
    QueryWrapper<Album> getQueryWrapper(AlbumQueryRequest albumQueryRequest);

    /**
     * 批量把图片加入相册
     *
     * @return 新增的图片数量（已存在的不会重复加入）
     */
    int addPictureToAlbum(AlbumPictureRequest albumPictureRequest, User loginUser);

    /**
     * 批量把图片移出相册
     *
     * @return 实际移出的图片数量
     */
    int removePictureFromAlbum(AlbumPictureRequest albumPictureRequest, User loginUser);

    /**
     * 分页查询相册内的图片
     */
    Page<PictureVO> listAlbumPictureByPage(AlbumPictureQueryRequest albumPictureQueryRequest,
                                           User loginUser, HttpServletRequest request);

    /**
     * 图片被删除后，清理这些图片在所有相册中的关联关系
     *
     * @param pictureIds 被删除的图片 id 列表
     */
    void removePictureRelations(List<Long> pictureIds);
}
