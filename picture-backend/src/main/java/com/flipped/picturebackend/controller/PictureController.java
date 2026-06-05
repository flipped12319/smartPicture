package com.flipped.picturebackend.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.flipped.picturebackend.annotation.AuthCheck;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.constant.UserConstant;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.model.dto.picture.PictureQueryRequest;
import com.flipped.picturebackend.model.dto.picture.PictureReviewRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUploadByBatchRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUploadRequest;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import com.flipped.picturebackend.model.vo.PictureTagCategory;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/")
public class PictureController {

    @Resource
    private IPictureService pictureService;

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final Cache<String, String> LOCAL_CACHE =
            Caffeine.newBuilder().initialCapacity(1024)
                    .maximumSize(10000L)
                    // 缓存 5 分钟移除
                    .expireAfterWrite(5L, TimeUnit.MINUTES)
                    .build();


    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> listPictureTagCategory() {
        PictureTagCategory pictureTagCategory = new PictureTagCategory();
        List<String> tagList = Arrays.asList("热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "简历", "创意");
        List<String> categoryList = Arrays.asList("模板", "电商", "表情包", "素材", "海报");
        pictureTagCategory.setTagList(tagList);
        pictureTagCategory.setCategoryList(categoryList);
        return ResultUtils.success(pictureTagCategory);
    }
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doPictureReview(@RequestBody PictureReviewRequest pictureReviewRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.doPictureReview(pictureReviewRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 通过 URL 上传图片（可重新上传）
     */
    @PostMapping("/upload/url")
    public BaseResponse<PictureVO> uploadPictureByUrl(
            @RequestBody PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        String fileUrl = pictureUploadRequest.getFileUrl();
        PictureVO pictureVO = pictureService.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    @PostMapping("/upload/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> uploadPictureByBatch(
            @RequestBody PictureUploadByBatchRequest pictureUploadByBatchRequest,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(pictureUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int uploadCount = pictureService.uploadPictureByBatch(pictureUploadByBatchRequest, loginUser);
        return ResultUtils.success(uploadCount);
    }

    @PostMapping("/list/page/vo/cache")
    public BaseResponse<Page<PictureVO>> listPictureVOByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                                      HttpServletRequest request) {
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        Long spaceId = pictureQueryRequest.getSpaceId();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
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



        // ========== 私有空间：不走缓存，直接查询数据库，并校验权限 ==========
        if (spaceId != null && spaceId > 0) {
            // 1. 权限校验：用户必须是该空间的创建者

            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            if (!loginUser.getId().equals(space.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
            }
            // 2. 直接查询数据库（不需要设置 reviewStatus，私有空间数据无需审核）
            Page<Picture> picturePage = pictureService.page(
                    new Page<>(current, size),
                    pictureService.getQueryWrapper(pictureQueryRequest)
            );
            Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
            return ResultUtils.success(pictureVOPage);
        }

        // ========== 公共空间：走缓存 ==========
        // 普通用户默认只能查看已过审的数据
        String role=loginUser.getUserRole();
//        if(Objects.equals(role, "admin")){
//            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
//        }
        if(Objects.equals(role, "user")){
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        }
        pictureQueryRequest.setNullSpaceId(true);

        // 构建缓存 key
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
        String cacheKey = "picture:listPictureVOByPage:" + hashKey;


      // 1. 查询本地缓存（Caffeine）
        String cachedValue = LOCAL_CACHE.getIfPresent(cacheKey);
        if (cachedValue != null) {
            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
            return ResultUtils.success(cachedPage);
        }

// 2. 查询分布式缓存（Redis），失败时降级到数据库
        ValueOperations<String, String> valueOps = stringRedisTemplate.opsForValue();
        try {
            cachedValue = valueOps.get(cacheKey);
            if (cachedValue != null) {
                // 如果命中 Redis，存入本地缓存并返回
                LOCAL_CACHE.put(cacheKey, cachedValue);
                Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
                return ResultUtils.success(cachedPage);
            }
        } catch (Exception e) {
            // Redis 查询失败（如连接断开），降级到数据库查询
            System.err.println("Redis query failed, fallback to database: " + e.getMessage());
        }
        // 3. 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);

        // 4. 更新缓存
        String cacheValue = JSONUtil.toJsonStr(pictureVOPage);
        // 更新本地缓存
        LOCAL_CACHE.put(cacheKey, cacheValue);
        // 更新 Redis 缓存，设置过期时间为 5 分钟
        try {
            valueOps.set(cacheKey, cacheValue, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Redis 写入失败，不影响返回结果，本地缓存已生效
            System.err.println("Redis update failed: " + e.getMessage());
        }


        // 返回结果
        return ResultUtils.success(pictureVOPage);
    }

//    @PostMapping("/list/page/vo/cache")
//    public BaseResponse<Page<PictureVO>> listPictureVOByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest,
//                                                                      HttpServletRequest request) {
//        long current = pictureQueryRequest.getCurrent();
//        long size = pictureQueryRequest.getPageSize();
//        // 限制爬虫
//        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
//
//        Long spaceId = pictureQueryRequest.getSpaceId();
//
//        // ========== 私有空间：不走缓存，直接查询数据库，并校验权限 ==========
//        if (spaceId != null && spaceId > 0) {
//            // 1. 权限校验：用户必须是该空间的创建者
//            User loginUser = userService.getLoginUser(request);
//            Space space = spaceService.getById(spaceId);
//            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
//            if (!loginUser.getId().equals(space.getUserId())) {
//                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
//            }
//            // 2. 直接查询数据库（不需要设置 reviewStatus，私有空间数据无需审核）
//            Page<Picture> picturePage = pictureService.page(
//                    new Page<>(current, size),
//                    pictureService.getQueryWrapper(pictureQueryRequest)
//            );
//            Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
//            return ResultUtils.success(pictureVOPage);
//        }
//
//        // ========== 公共空间：走缓存 ==========
//        // 普通用户默认只能查看已过审的数据
//        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
//        pictureQueryRequest.setNullSpaceId(true);
//
//
//        // 3. 查询数据库
//        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
//                pictureService.getQueryWrapper(pictureQueryRequest));
//        Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
//
//
//        // 返回结果
//        return ResultUtils.success(pictureVOPage);
//    }

    @PostMapping("/list/page/cache")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
public BaseResponse<Page<Picture>> listPictureByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest,
                                                                  HttpServletRequest request) {
    long current = pictureQueryRequest.getCurrent();
    long size = pictureQueryRequest.getPageSize();
    // 限制爬虫
    ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);

    Long spaceId = pictureQueryRequest.getSpaceId();
    User loginUser = userService.getLoginUser(request);
    // ========== 私有空间：不走缓存，直接查询数据库，并校验权限 ==========
    if (spaceId != null && spaceId > 0) {
        // 1. 权限校验：用户必须是该空间的创建者

        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        if (!loginUser.getId().equals(space.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有空间权限");
        }
        // 2. 直接查询数据库（不需要设置 reviewStatus，私有空间数据无需审核）
        Page<Picture> picturePage = pictureService.page(
                new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest)
        );

        return ResultUtils.success(picturePage);
    }

    // ========== 公共空间：走缓存 ==========
// 普通用户默认只能查看已过审的数据
    String role=loginUser.getUserRole();
    if(Objects.equals(role, "user")){
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
    }
    pictureQueryRequest.setNullSpaceId(true);

    // 构建缓存 key
    String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
    String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
    String cacheKey = "picture:listPictureByPage:" + hashKey;


    // 1. 查询本地缓存（Caffeine）
    String cachedValue = LOCAL_CACHE.getIfPresent(cacheKey);
    if (cachedValue != null) {
        Page<Picture> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
        return ResultUtils.success(cachedPage);
    }

// 2. 查询分布式缓存（Redis）
    ValueOperations<String, String> valueOps = stringRedisTemplate.opsForValue();
    cachedValue = valueOps.get(cacheKey);
    if (cachedValue != null) {
        // 如果命中 Redis，存入本地缓存并返回
        LOCAL_CACHE.put(cacheKey, cachedValue);
        Page<Picture> cachedPage = JSONUtil.toBean(cachedValue, Page.class);
        return ResultUtils.success(cachedPage);
    }
    // 3. 查询数据库
    Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
            pictureService.getQueryWrapper(pictureQueryRequest));
//    Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);

    // 4. 更新缓存
    String cacheValue = JSONUtil.toJsonStr(picturePage);
    // 更新本地缓存
    LOCAL_CACHE.put(cacheKey, cacheValue);
    // 更新 Redis 缓存，设置过期时间为 5 分钟
    valueOps.set(cacheKey, cacheValue, 5, TimeUnit.MINUTES);


    // 返回结果
    return ResultUtils.success(picturePage);
}




}
