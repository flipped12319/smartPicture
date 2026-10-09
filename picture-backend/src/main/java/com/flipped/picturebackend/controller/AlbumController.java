package com.flipped.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.DeleteRequest;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.model.dto.album.AlbumAddRequest;
import com.flipped.picturebackend.model.dto.album.AlbumEditRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureQueryRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureRequest;
import com.flipped.picturebackend.model.dto.album.AlbumQueryRequest;
import com.flipped.picturebackend.model.entity.Album;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.AlbumVO;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.service.AlbumService;
import com.flipped.picturebackend.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 相册接口
 * <p>
 * 相册保存在用户的私人空间中，内部记录的是对图片的引用，
 * 图片可以来自公共图库，也可以来自本人的私人空间。
 */
@RestController
@RequestMapping("/album")
public class AlbumController {

    @Resource
    private AlbumService albumService;

    @Resource
    private UserService userService;

    /**
     * 创建相册
     *
     * @return 新相册 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addAlbum(@RequestBody AlbumAddRequest albumAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(albumAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long newAlbumId = albumService.addAlbum(albumAddRequest, loginUser);
        return ResultUtils.success(newAlbumId);
    }

    /**
     * 删除相册（同时清理相册内的图片关联）
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteAlbum(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        albumService.deleteAlbum(deleteRequest.getId(), loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 编辑相册（名称、说明）
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editAlbum(@RequestBody AlbumEditRequest albumEditRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(albumEditRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        albumService.editAlbum(albumEditRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取相册（封装类，仅相册创建者或管理员可查看）
     */
    @GetMapping("/get/vo")
    public BaseResponse<AlbumVO> getAlbumVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        Album album = albumService.getById(id);
        ThrowUtils.throwIf(album == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        User loginUser = userService.getLoginUser(request);
        albumService.checkAlbumAuth(loginUser, album);
        return ResultUtils.success(albumService.getAlbumVO(album, request));
    }

    /**
     * 分页获取当前用户的相册列表
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<AlbumVO>> listAlbumVOByPage(@RequestBody AlbumQueryRequest albumQueryRequest,
                                                         HttpServletRequest request) {
        ThrowUtils.throwIf(albumQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = albumQueryRequest.getCurrent();
        long size = albumQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 条");
        User loginUser = userService.getLoginUser(request);
        // 非管理员只能查询自己的相册
        if (!userService.isAdmin(loginUser)) {
            albumQueryRequest.setUserId(loginUser.getId());
        }
        Page<Album> albumPage = albumService.page(new Page<>(current, size),
                albumService.getQueryWrapper(albumQueryRequest));
        return ResultUtils.success(albumService.getAlbumVOPage(albumPage, request));
    }

    /**
     * 批量把图片加入相册
     *
     * @return 实际新增的图片数量（相册中已存在的不会重复加入）
     */
    @PostMapping("/picture/add")
    public BaseResponse<Integer> addPictureToAlbum(@RequestBody AlbumPictureRequest albumPictureRequest,
                                                   HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int count = albumService.addPictureToAlbum(albumPictureRequest, loginUser);
        return ResultUtils.success(count);
    }

    /**
     * 批量把图片移出相册
     *
     * @return 实际移出的图片数量
     */
    @PostMapping("/picture/remove")
    public BaseResponse<Integer> removePictureFromAlbum(@RequestBody AlbumPictureRequest albumPictureRequest,
                                                        HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int count = albumService.removePictureFromAlbum(albumPictureRequest, loginUser);
        return ResultUtils.success(count);
    }

    /**
     * 分页获取相册内的图片
     */
    @PostMapping("/picture/list/page/vo")
    public BaseResponse<Page<PictureVO>> listAlbumPictureByPage(
            @RequestBody AlbumPictureQueryRequest albumPictureQueryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Page<PictureVO> pictureVOPage = albumService.listAlbumPictureByPage(albumPictureQueryRequest,
                loginUser, request);
        return ResultUtils.success(pictureVOPage);
    }
}
