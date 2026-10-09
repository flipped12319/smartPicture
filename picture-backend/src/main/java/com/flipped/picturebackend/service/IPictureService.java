package com.flipped.picturebackend.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.flipped.picturebackend.model.dto.picture.*;
import com.flipped.picturebackend.model.dto.picture.*;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.PictureVO;
import org.springframework.scheduling.annotation.Async;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 图片表 服务接口
 */
public interface IPictureService extends IService<Picture> {
    /**
     * 上传图片
     *
     * @param multipartFile
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */

    PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser);

    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    void validPicture(Picture picture);

    /**
     * 图片审核
     *
     * @param pictureReviewRequest
     * @param loginUser
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);

    void fillReviewParams(Picture picture, User loginUser);

    /**
     * 批量抓取和创建图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return 成功创建的图片数
     */
    Integer uploadPictureByBatch(
            PictureUploadByBatchRequest pictureUploadByBatchRequest,
            User loginUser
    );

    /**
     * 校验用户是否可以「修改图片内容」（替换原图、协同编辑等）
     * <p>
     * 比编辑图片信息更严格：公共图库的图片仅管理员可修改，空间图片需要「编辑者」权限。
     */
    void checkPictureModifyAuth(User loginUser, Picture picture);

    void checkPictureAuth(User loginUser, Picture picture);

    /**
     * 校验用户是否有权查看该图片（空间内的图片至少需要是空间成员）
     */
    void checkPictureViewAuth(User loginUser, Picture picture);

    @Async
    void clearPictureFile(Picture oldPicture);

    void deletePicture(long pictureId, User loginUser);

    /**
     * 批量删除图片
     * <p>
     * 仅支持同一个私人空间内的图片，且只有空间创建者本人可以操作。
     * 删除后会在同一个事务中释放该空间被占用的容量与数量额度。
     *
     * @param pictureIds 图片 id 列表
     * @param loginUser  当前登录用户
     * @return 实际删除的图片数量
     */
    int deletePictureBatch(List<Long> pictureIds, User loginUser);

    void editPicture(PictureEditRequest pictureEditRequest, User loginUser);


}