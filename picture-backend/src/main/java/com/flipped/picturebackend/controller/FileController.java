package com.flipped.picturebackend.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import com.flipped.picturebackend.annotation.AuthCheck;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.DeleteRequest;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.constant.UserConstant;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.manager.CosManager;
import com.flipped.picturebackend.model.dto.picture.PictureBatchDeleteRequest;
import com.flipped.picturebackend.model.dto.picture.PictureEditRequest;
import com.flipped.picturebackend.model.dto.picture.PictureQueryRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUpdateRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUploadRequest;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.SpaceUserService;
import com.flipped.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

/**
 * 测试文件上传
 *
 *

 */
@Slf4j
@RestController
@RequestMapping("/")
public class FileController {

    @Resource   // 注入 CosManager
    private CosManager cosManager;

    @Resource
    private UserService userService;

    @Resource
    private IPictureService pictureService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/test/upload")
    public BaseResponse<String> testUploadFile(@RequestPart("file") MultipartFile multipartFile) {
        // 文件目录
        String filename = multipartFile.getOriginalFilename();
        String filepath = String.format("/test/%s", filename);
        File file = null;
        try {
            // 上传文件
            file = File.createTempFile(filepath, null);
            multipartFile.transferTo(file);
            cosManager.putObject(filepath, file);
            // 返回可访问地址
            return ResultUtils.success(filepath);
        } catch (Exception e) {
            log.error("file upload error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            if (file != null) {
                // 删除临时文件
                boolean delete = file.delete();
                if (!delete) {
                    log.error("file delete error, filepath = {}", filepath);
                }
            }
        }
    }

    /**
     * 测试文件下载
     *
     * @param filepath 文件路径
     * @param response 响应对象
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download/")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        COSObjectInputStream cosObjectInput = null;
        try {
            COSObject cosObject = cosManager.getObject(filepath);
            cosObjectInput = cosObject.getObjectContent();
            // 处理下载到的流
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            // 设置响应头
            response.setContentType("application/octet-stream;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + filepath);
            // 写入响应
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file download error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }

    /**
     * 上传图片（可重新上传）
     */
    @PostMapping("/upload")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPicture(
            @RequestPart("file") MultipartFile multipartFile,
            PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        System.out.println(pictureVO);
        return ResultUtils.success(pictureVO);
    }

    @PostMapping("/upload/byToken")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPictureByToken(
            @RequestPart("file") MultipartFile multipartFile,
            PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        User loginUser=userService.getLoginUserByToken(token);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        System.out.println(pictureVO);
        return ResultUtils.success(pictureVO);
    }
    /**
     * 删除图片
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePicture(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser;
        try {
            loginUser = userService.getLoginUser(request);
        } catch (Exception e) {
            // 第一种方式失败，尝试从 token 获取
            String authHeader = request.getHeader("Authorization");
            if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    loginUser = userService.getLoginUserByToken(token);
                } catch (Exception ex) {
                    // 两种方式都失败，抛出业务异常
                    throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"用户未登录或登录已过期");
                }
            } else {
                // 没有 token，也失败
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"用户未登录");
            }
        }
        long id = deleteRequest.getId();
        pictureService.deletePicture(id,loginUser);
//        // 判断是否存在
//        Picture oldPicture = pictureService.getById(id);
//        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
//        // 仅本人或管理员可删除
//        if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
//            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
//        }
//        // 操作数据库
//        boolean result = pictureService.removeById(id);
//        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 批量删除图片
     * <p>
     * 仅支持同一个私人空间内的图片，且只有空间创建者本人可以操作。
     *
     * @return 实际删除的图片数量
     */
    @PostMapping("/delete/batch")
    public BaseResponse<Integer> deletePictureBatch(@RequestBody PictureBatchDeleteRequest batchDeleteRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(batchDeleteRequest == null || CollUtil.isEmpty(batchDeleteRequest.getIds()),
                ErrorCode.PARAMS_ERROR, "请至少选择一张要删除的图片");
        User loginUser = resolveLoginUser(request);
        int deletedCount = pictureService.deletePictureBatch(batchDeleteRequest.getIds(), loginUser);
        return ResultUtils.success(deletedCount);
    }

    /**
     * 解析当前登录用户：优先读取 Session，失败后回退到 Authorization 头中的 JWT
     */
    private User resolveLoginUser(HttpServletRequest request) {
        try {
            return userService.getLoginUser(request);
        } catch (Exception e) {
            String authHeader = request.getHeader("Authorization");
            if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    return userService.getLoginUserByToken(token);
                } catch (Exception ex) {
                    throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "用户未登录或登录已过期");
                }
            }
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "用户未登录");
        }
    }

    /**
     * 更新图片（仅管理员可用）
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest,HttpServletRequest request) {
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));
        // 数据校验
        pictureService.validPicture(picture);
        // 判断是否存在
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);

        // 补充审核参数
        User loginUser = userService.getLoginUser(request);
        pictureService.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = pictureService.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取图片（仅管理员可用）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Picture> getPictureById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(picture);
    }

    /**
     * 根据 id 获取图片（封装类）
     */
    @GetMapping("/get/vo")
    public BaseResponse<PictureVO> getPictureVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 空间权限校验：空间内的图片至少需要是该空间成员
        Long spaceId = picture.getSpaceId();
        if (spaceId != null) {
            User loginUser = userService.getLoginUser(request);
            pictureService.checkPictureViewAuth(loginUser, picture);
        }
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVO(picture, request));
    }

    /**
     * 分页获取图片列表（仅管理员可用）
     */
//    @PostMapping("/list/page")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
//    public BaseResponse<Page<Picture>> listPictureByPage(@RequestBody PictureQueryRequest pictureQueryRequest) {
//        long current = pictureQueryRequest.getCurrent();
//        long size = pictureQueryRequest.getPageSize();
//        Long spaceId = pictureQueryRequest.getSpaceId();
//        // 查询数据库
//        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
//                pictureService.getQueryWrapper(pictureQueryRequest));
//        return ResultUtils.success(picturePage);
//    }

    /**
     * 分页获取图片列表（封装类）
     * <p>
     * 公共图库对所有登录用户可见；<b>空间图片必须校验访问权限</b>。
     * <p>
     * 这里曾经漏掉了空间校验：接口本身不要求登录，只强制 reviewStatus，
     * 而「上传到空间」的图片是自动过审的（见 PictureServiceImpl.fillUploadReviewParams），
     * 于是任何人只要传入 spaceId 就能读到别人私有空间的图片列表。
     * 与缓存的列表接口（PictureController#listPictureVOByPageWithCache）保持一致，
     * 空间图片至少要求是该空间的成员（只读即可）。
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

        // 空间图片：必须先校验权限，未登录时 getLoginUser 会直接抛出未登录异常
        Long spaceId = pictureQueryRequest.getSpaceId();
        if (spaceId != null && spaceId > 0) {
            User loginUser = userService.getLoginUser(request);
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.VIEWER);
        }

        // 普通用户默认只能查看已过审的数据
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage, request));
    }

    /**
     * 编辑图片（给用户使用）
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editPicture(@RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        if (pictureEditRequest == null || pictureEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        pictureService.editPicture(pictureEditRequest,loginUser);
        return ResultUtils.success(true);
    }

    @PostMapping("/edit/byToken")
    public BaseResponse<Boolean> editPictureByToken(@RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        if (pictureEditRequest == null || pictureEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        User loginUser=userService.getLoginUserByToken(token);
        pictureService.editPicture(pictureEditRequest,loginUser);
        return ResultUtils.success(true);
    }



}

