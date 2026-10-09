package com.flipped.picturebackend.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.flipped.picturebackend.annotation.AuthCheck;
import com.flipped.picturebackend.common.BaseResponse;
import com.flipped.picturebackend.common.ResultUtils;
import com.flipped.picturebackend.constant.UserConstant;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.model.dto.picture.PictureAutoFillRequest;
import com.flipped.picturebackend.model.dto.picture.PictureQueryRequest;
import com.flipped.picturebackend.model.dto.picture.PictureReviewRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUploadByBatchRequest;
import com.flipped.picturebackend.model.dto.picture.PictureUploadRequest;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;
import com.flipped.picturebackend.model.vo.PictureTagCategory;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.service.PictureIndexService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.SpaceUserService;
import com.flipped.picturebackend.service.UserService;
import com.flipped.picturebackend.config.CosClientConfig;
import com.flipped.picturebackend.manager.CosManager;
import com.flipped.picturebackend.manager.PictureListCacheManager;
import com.flipped.picturebackend.model.vo.CacheStatsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/")
public class PictureController {

    @Resource
    private IPictureService pictureService;

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    /**
     * 图片列表多级缓存：版本号失效 + 本地 L1 + Redis L2
     */
    @Resource
    private PictureListCacheManager pictureListCacheManager;

    /**
     * 图片向量索引：语义检索与索引重建
     */
    @Resource
    private PictureIndexService pictureIndexService;

    /**
     * 对象存储操作（图片代理读取用）
     */
    @Resource
    private CosManager cosManager;

    @Resource
    private CosClientConfig cosClientConfig;


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

        // ========== 语义搜索 ==========
        // searchText 非空时先用向量召回一批图片 id，再交给 MySQL 做权限过滤与分页。
        // 召回失败或为空时保持原样，由 getQueryWrapper 里的 LIKE 条件兜底，
        // 保证搜索不会因为下游索引服务不可用而整体失效。
        applySemanticSearch(pictureQueryRequest);

        // ========== 私有空间：不走缓存，直接查询数据库，并校验权限 ==========
        if (spaceId != null && spaceId > 0) {
            // 1. 权限校验：至少需要是该空间的成员（只读及以上即可查看）
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.VIEWER);
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

        // 构建缓存 key（带版本号：任何写操作让版本号 +1，这里所有缓存立即失效）
        String cacheKey = pictureListCacheManager.buildKey(
                PictureListCacheManager.NAMESPACE_PUBLIC, pictureQueryRequest);

        // 本地缓存 → Redis → 数据库依次回源；同一 key 的并发请求只会有一个线程真正查库
        String cacheValue = pictureListCacheManager.getOrLoad(cacheKey, () -> {
            Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                    pictureService.getQueryWrapper(pictureQueryRequest));
            Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
            return JSONUtil.toJsonStr(pictureVOPage);
        });

        // 返回结果
        Page<PictureVO> pictureVOPage = JSONUtil.toBean(cacheValue, Page.class);
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

    // 语义搜索：与管理端一致，searchText 非空时先做向量召回
    applySemanticSearch(pictureQueryRequest);

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

    // 构建缓存 key（带版本号：任何写操作让版本号 +1，这里所有缓存立即失效）
    String cacheKey = pictureListCacheManager.buildKey(
            PictureListCacheManager.NAMESPACE_ADMIN, pictureQueryRequest);

    // 本地缓存 → Redis → 数据库依次回源；同一 key 的并发请求只会有一个线程真正查库
    // （早期这里没有做异常兜底，Redis 一挂接口就直接失败，管理页一条数据都拿不到）
    String cacheValue = pictureListCacheManager.getOrLoad(cacheKey, () -> {
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return JSONUtil.toJsonStr(picturePage);
    });

    // 返回结果
    Page<Picture> picturePage = JSONUtil.toBean(cacheValue, Page.class);
    return ResultUtils.success(picturePage);
}

    /**
     * 把 searchText 转成向量召回的图片 id 列表
     * <p>
     * 召回结果只用于收窄查询范围；权限、分页与最终数据仍以 MySQL 为准。
     * 召回失败或为空时不设置 semanticIds，调用方继续走关键词 LIKE 兜底。
     */
    private void applySemanticSearch(PictureQueryRequest pictureQueryRequest) {
        String searchText = pictureQueryRequest.getSearchText();
        if (StrUtil.isBlank(searchText)) {
            return;
        }
        List<Long> pictureIds = pictureIndexService.searchPictureIds(searchText,
                pictureQueryRequest.getSpaceId());
        if (CollUtil.isEmpty(pictureIds)) {
            log.info("语义检索未召回结果，降级为关键词搜索，query = {}", searchText);
            return;
        }
        pictureQueryRequest.setSemanticIds(pictureIds);
        log.info("语义检索召回 {} 张图片，query = {}", pictureIds.size(), searchText);
    }

    /**
     * 重建图片向量索引（用于补齐存量图片）
     * <p>
     * 只处理已过审的图片，任务提交到后台执行，接口立即返回待处理数量。
     * 每张图都要调用一次多模态模型，耗时较长，可通过日志观察进度。
     *
     * @param onlyMissing 是否只处理未索引或索引失败的图片
     */
    @PostMapping("/index/rebuild")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> rebuildPictureIndex(@RequestParam(defaultValue = "false") boolean onlyMissing) {
        return ResultUtils.success(pictureIndexService.rebuildIndex(onlyMissing));
    }

    /**
     * 智能补充图片信息：补齐为空的名称、分类、简介、标签
     * <p>
     * 详情页手动触发，同步执行（要调用多模态模型，通常十几秒），调用方直接拿到被补充的字段名。
     * 权限与「编辑图片」保持一致：公共图库需本人或管理员，空间图片需空间「编辑者」权限。
     */
    @PostMapping("/index/auto-fill")
    public BaseResponse<List<String>> autoFillPictureInfo(@RequestBody PictureAutoFillRequest autoFillRequest,
                                                          HttpServletRequest request) {
        ThrowUtils.throwIf(autoFillRequest == null || autoFillRequest.getPictureId() == null,
                ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        User loginUser = userService.getLoginUser(request);
        Picture picture = pictureService.getById(autoFillRequest.getPictureId());
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 复用图片编辑的权限校验：公共图库=本人或管理员，空间图片=空间编辑者
        pictureService.checkPictureAuth(loginUser, picture);

        List<String> filledFields = pictureIndexService.autoFillPictureInfo(autoFillRequest.getPictureId());
        // 补充会改变列表展示的内容，让列表缓存立即失效
        pictureListCacheManager.invalidateAll();
        return ResultUtils.success(filledFields);
    }

    /**
     * 代理读取图片原图（同源返回）
     * <p>
     * 为什么不让前端直接用 COS 地址：COS 桶没有配置跨域规则，浏览器把跨域图片画进 canvas 后
     * 会把它标记为「已污染」，调用 toBlob() 导出时会直接抛异常，前端就没法做裁切、旋转等图像编辑。
     * 通过本接口读取后图片与前端同源（全局 CORS 配置已放行带 Cookie 的请求），canvas 可以正常导出。
     *
     * @param id 图片 id（只接受 id 而不是任意 url，避免被用来请求其他地址）
     */
    @GetMapping("/picture/proxy")
    public void proxyPicture(long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 公共图库的图片允许匿名读取；空间图片仍然要校验访问权限
        if (picture.getSpaceId() != null) {
            pictureService.checkPictureViewAuth(userService.getLoginUser(request), picture);
        }

        // 库里存的是完整 url，对象存储要的是 key
        String key = toCosObjectKey(picture.getUrl());
        COSObjectInputStream cosObjectInput = null;
        try {
            COSObject cosObject = cosManager.getObject(key);
            cosObjectInput = cosObject.getObjectContent();
            response.setContentType(resolveImageContentType(picture.getPicFormat()));
            // 图片内容基本不变，允许浏览器短暂缓存，减少重复代理转发
            response.setHeader("Cache-Control", "private, max-age=60");
            StreamUtils.copy(cosObjectInput, response.getOutputStream());
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("代理读取图片失败，id = {}，key = {}", id, key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片读取失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }

    /**
     * 图片 url 里带着域名，这里截掉域名取出对象存储中的 key
     */
    private String toCosObjectKey(String url) {
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.PARAMS_ERROR, "图片地址为空");
        String key = url;
        String host = cosClientConfig.getHost();
        if (StrUtil.isNotBlank(host) && key.startsWith(host)) {
            key = key.substring(host.length());
        }
        return StrUtil.removePrefix(key, "/");
    }

    /**
     * 根据图片格式给出响应头里的 Content-Type
     */
    private String resolveImageContentType(String picFormat) {
        if (StrUtil.isBlank(picFormat)) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String format = picFormat.trim().toLowerCase();
        if ("jpg".equals(format) || "jpeg".equals(format)) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if ("png".equals(format)) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if ("gif".equals(format)) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        return "image/" + format;
    }

    /**
     * 查看图片列表缓存的运行指标（命中率、版本号等），用于排查缓存问题
     */
    @GetMapping("/cache/stats")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<CacheStatsVO> getCacheStats() {
        return ResultUtils.success(pictureListCacheManager.getStats());
    }

    /**
     * 手动清空图片列表缓存
     * <p>
     * 通过「版本号 +1」实现，不需要枚举删除 key，因此任何时刻调用都是安全的。
     */
    @PostMapping("/cache/clear")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> clearCache() {
        pictureListCacheManager.invalidateAll();
        return ResultUtils.success(true);
    }




}
