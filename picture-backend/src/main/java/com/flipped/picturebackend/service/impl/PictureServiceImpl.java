package com.flipped.picturebackend.service.impl;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.manager.CosManager;
import com.flipped.picturebackend.manager.upload.FilePictureUpload;
import com.flipped.picturebackend.manager.upload.PictureUploadTemplate;
import com.flipped.picturebackend.manager.upload.UrlPictureUpload;
import com.flipped.picturebackend.mapper.PictureMapper;
import com.flipped.picturebackend.model.dto.file.UploadPictureResult;
import com.flipped.picturebackend.model.dto.picture.*;
import com.flipped.picturebackend.model.dto.picture.*;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.Space;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.enums.PictureReviewStatusEnum;
import com.flipped.picturebackend.model.enums.SpaceUserRoleEnum;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.model.vo.UserVO;
import com.flipped.picturebackend.service.AlbumService;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.manager.PictureListCacheManager;
import com.flipped.picturebackend.service.PictureIndexService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.SpaceUserService;
import com.flipped.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图片表 服务实现类
 */
@Service
@Slf4j
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture> implements IPictureService {

//
//    @Resource
//    private FileManager fileManager;

    @Resource
    private UserService userService;

    @Resource
    private FilePictureUpload filePictureUpload;

    @Resource
    private UrlPictureUpload urlPictureUpload;

    @Resource
    private SpaceService spaceService;

    @Resource
    private CosManager cosManager;

    /**
     * 使用 @Lazy 打破与 AlbumServiceImpl 的循环依赖：
     * 相册里保存的是图片引用，图片被删除时需要同步清理相册关联。
     */
    @Resource
    @Lazy
    private AlbumService albumService;

    /**
     * 团队空间权限校验入口
     */
    @Resource
    private SpaceUserService spaceUserService;

    /**
     * 图片向量索引（异步，失败不影响主流程）
     */
    @Resource
    private PictureIndexService pictureIndexService;

    /**
     * 图片列表缓存：任何写操作后都要让它全量失效
     */
    @Resource
    private PictureListCacheManager pictureListCacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 归还预占的额度（阶段 5d-b 的补偿路径）
     *
     * <p><b>为什么补偿失败只记日志、不抛异常</b>：
     * 这个方法是在「已经失败」的路径上调用的（COS 上传失败、入库失败）。此时业务失败才是
     * 调用方该看到的结果；如果补偿再抛一个异常，就会把原始失败原因盖掉 ——
     * 用户看到的是「空间服务不可用」，真正的问题（上传失败）反而被隐藏了。
     *
     * <p>补偿失败的后果是**空间额度被少算一点**（占着但没实际使用），属于可对账修正的偏差：
     * 这正是阶段 5c 那套对账机制存在的意义。日志里带上 reason，便于定位是哪条链路漏补的。
     */
    private void refundReservedQuota(Long spaceId, long sizeDelta, long countDelta, String reason) {
        if (spaceId == null || (sizeDelta == 0L && countDelta == 0L)) {
            return;
        }
        try {
            // 补偿/归还必须**同步**生效：用户刚被拒绝、马上会再试一次，
            // 若额度异步归还，他会看到「额度不足」却查不出原因
            spaceService.updateSpaceQuotaNow(spaceId, -sizeDelta, -countDelta, reason);
            log.warn("已归还预占额度：spaceId = {}，sizeDelta = {}，countDelta = {}，原因 = {}",
                    spaceId, sizeDelta, countDelta, reason);
        } catch (Exception e) {
            log.error("归还预占额度失败（会造成额度少算，需对账修正）：spaceId = {}，sizeDelta = {}，" +
                    "countDelta = {}，原因 = {}", spaceId, sizeDelta, countDelta, reason, e);
        }
    }

    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        if (inputSource == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片为空");
        }
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
//        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(pictureUploadRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");

        // ========== 1. 判断是新增还是「重新上传」（图像编辑走的就是重新上传） ==========
        Long pictureId = pictureUploadRequest.getId();
        Long spaceId = pictureUploadRequest.getSpaceId();
        Picture oldPicture = null;
        if (pictureId != null) {
            oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 重新上传（含图像编辑）的权限比「编辑图片信息」更严格：
            // 公共图库仅管理员可替换原图，空间图片要求「编辑者」权限
            checkPictureModifyAuth(loginUser, oldPicture);
            // 没传 spaceId，则复用原有图片的 spaceId
            if (spaceId == null) {
                spaceId = oldPicture.getSpaceId();
            } else if (ObjUtil.notEqual(spaceId, oldPicture.getSpaceId())) {
                // 传了 spaceId，必须和原有图片一致
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间 id 不一致");
            }
        }

        // ========== 2. 校验空间与额度（重新上传只是替换原图，不占用新的额度） ==========
        // 阶段 5d-b：这里把「校验额度」换成 space-service 侧的**原子预占**（条件 UPDATE）。
        // 原来的写法是「先查 totalCount/totalSize 判断够不够，事务里再扣」，中间夹着一次
        // COS 上传（几百毫秒）—— 并发上传可以都通过检查、最后一起超限（TOCTOU）。
        // 现在把判断与扣减合并成一条带 WHERE 的 UPDATE，由数据库保证原子性。
        long reserveSizeDelta = 0L;
        long reserveCountDelta = 0L;
        boolean shouldReserveQuota = spaceId != null && oldPicture == null;
        if (spaceId != null && oldPicture == null) {
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            // 空间成员至少需要「能看能上传」权限才能往空间里上传
            spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.UPLOADER);
            // 体量先按 0 预占，等 COS 上传拿到真实大小后再补差额（见下面 reserveAfterUpload）；
            // 这里先把「条数」占住 —— 条数是上传前就确定的，而且是最容易被并发突破的限额
            reserveCountDelta = 1L;
            spaceService.reserveSpaceQuota(spaceId, 0L, reserveCountDelta, "uploadPicture:count");
        }
// 按照用户 id 划分目录 => 按照空间划分目录
        String uploadPathPrefix;
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            uploadPathPrefix = String.format("space/%s", spaceId);
        }

        // 根据 inputSource 类型区分上传方式
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        if (inputSource instanceof String) {
            System.out.println("inputSource is a string");
            pictureUploadTemplate = urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult;
        try {
            uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        } catch (RuntimeException e) {
            // COS 上传失败：把刚占住的条数还回去，否则这个空间会永久少一个名额
            refundReservedQuota(spaceId, 0L, reserveCountDelta, "uploadPicture:cosFailed");
            throw e;
        }
        // ========== 3. 构造要入库的图片信息 ==========
        Picture picture = new Picture();
        picture.setThumbnailUrl(uploadPictureResult.getThumbnailUrl());
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        picture.setSpaceId(spaceId);

        // 名称优先级：请求显式传入 > 原有名称 > 上传文件的名字。
        // 重新上传（图像编辑）时要沿用原名称，否则会被编辑后导出的临时文件名覆盖
        String picName = pictureUploadRequest.getPicName();
        if (StrUtil.isBlank(picName) && oldPicture != null) {
            picName = oldPicture.getName();
        }
        if (StrUtil.isBlank(picName)) {
            picName = uploadPictureResult.getPicName();
        }
        picture.setName(picName);

        if (oldPicture == null) {
            // 新增：作者是当前登录用户
            picture.setUserId(loginUser.getId());
        } else {
            // 重新上传：保留原作者，避免空间编辑者替换图片后把作者改成自己
            picture.setUserId(oldPicture.getUserId());
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }
        fillUploadReviewParams(picture, spaceId, loginUser);
        // ========== 4. 补齐体量预占 + 入库 ==========
        // 条数已在第 2 步预占；体量要等 COS 上传拿到真实大小才知道，这里补差额。
        // 补偿路径与「条数」共用 refundReservedQuota：入库失败就把两者一起还回去。
        long sizeDelta = picture.getPicSize() == null ? 0L : picture.getPicSize();
        long countDelta = reserveCountDelta;
        if (oldPicture != null) {
            // 重新上传：条数不变，体量按「新图 - 旧图」的差值调整，
            // 否则同一张图会被反复计入，空间额度越用越多
            sizeDelta -= oldPicture.getPicSize() == null ? 0L : oldPicture.getPicSize();
            countDelta = 0L;
        } else if (spaceId != null) {
            // 体量差额预占：可能因「大小不足」失败，此时条数已经占住了，必须还回去
            try {
                spaceService.reserveSpaceQuota(spaceId, sizeDelta, 0L, "uploadPicture:size");
            } catch (RuntimeException e) {
                refundReservedQuota(spaceId, 0L, reserveCountDelta, "uploadPicture:sizeRejected");
                throw e;
            }
        }

        Long finalSpaceId = spaceId;
        Picture finalOldPicture = oldPicture;
        try {
            transactionTemplate.execute(status -> {
                boolean result = this.saveOrUpdate(picture);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
                // 阶段 5d-b：额度**已经在入库前预占完成**，这里不再改额度 ——
                // 「先扣额度再写数据」保证了并发下不会超卖；代价是入库失败时要补偿。
                return picture;
            });
        } catch (RuntimeException e) {
            // 入库失败：把预占的额度还回去（跨服务写，不在本地事务里，所以必须显式补偿）
            if (finalSpaceId != null) {
                long refundCount = finalOldPicture == null ? reserveCountDelta : 0L;
                refundReservedQuota(finalSpaceId, sizeDelta, refundCount, "uploadPicture:dbFailed");
            }
            throw e;
        }

        // 重新上传成功后清理旧文件，避免对象存储里留下没人引用的图片
        if (oldPicture != null) {
            this.clearPictureFile(oldPicture);
        }

        // 已过审的图片直接建索引并补齐缺失信息：
        // - 空间图片：空间创建者自动过审
        // - 管理员上传公共图库：管理员自动过审
        // 普通用户上传公共图库此时是「待审核」，不会进入这个分支，
        // 要等管理员审核通过后才补（见 doPictureReview）
        // 图片有新增或修改，列表缓存立即失效
        pictureListCacheManager.invalidateAll();

        Integer reviewStatus = picture.getReviewStatus();
        if (reviewStatus != null && reviewStatus == PictureReviewStatusEnum.PASS.getValue()) {
            pictureIndexService.indexPictureAndFillAsync(picture.getId());
        }

        return PictureVO.objToVo(picture);
    }


//    @Override
//    public PictureVO uploadPicture(MultipartFile multipartFile, PictureUploadRequest pictureUploadRequest, User loginUser) {
//        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
//        // 用于判断是新增还是更新图片
//        Long pictureId = null;
//        if (pictureUploadRequest != null) {
//            pictureId = pictureUploadRequest.getId();
//        }
//        // 如果是更新图片，需要校验图片是否存在
//
//
//        if (pictureId != null) {
//            Picture oldPicture = this.getById(pictureId);
//            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
//            // 仅本人或管理员可编辑
//            if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
//                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
//            }
//            boolean exists = this.lambdaQuery()
//                    .eq(Picture::getId, pictureId)
//                    .exists();
//            ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
//        }
//        // 上传图片，得到信息
//        // 按照用户 id 划分目录
//        String uploadPathPrefix = String.format("public/%s", loginUser.getId());
//
//        UploadPictureResult uploadPictureResult = fileManager.uploadPicture(multipartFile, uploadPathPrefix);
//        // 构造要入库的图片信息
//        Picture picture = new Picture();
//        picture.setUrl(uploadPictureResult.getUrl());
//        picture.setName(uploadPictureResult.getPicName());
//        picture.setPicSize(uploadPictureResult.getPicSize());
//        picture.setPicWidth(uploadPictureResult.getPicWidth());
//        picture.setPicHeight(uploadPictureResult.getPicHeight());
//        picture.setPicScale(uploadPictureResult.getPicScale());
//        picture.setPicFormat(uploadPictureResult.getPicFormat());
//        picture.setUserId(loginUser.getId());
//        // 补充审核参数
//        fillReviewParams(picture, loginUser);
//        // 如果 pictureId 不为空，表示更新，否则是新增
//        if (pictureId != null) {
//            // 如果是更新，需要补充 id 和编辑时间
//            picture.setId(pictureId);
//            picture.setEditTime(new Date());
//        }
//        boolean result = this.saveOrUpdate(picture);
//        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
//        return PictureVO.objToVo(picture);
//    }

    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        Boolean nullSpaceId=pictureQueryRequest.getNullSpaceId();

        // 语义检索召回结果：优先用 id 列表替代关键词 LIKE
        List<Long> semanticIds = pictureQueryRequest.getSemanticIds();
        if (CollUtil.isNotEmpty(semanticIds)) {
            queryWrapper.in("id", semanticIds);
        } else if (StrUtil.isNotBlank(searchText)) {
            // 从多字段中搜索（名称、简介）
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        // nullSpaceId 是装箱类型 Boolean，而 MyBatis-Plus 的 isNull 只有 (boolean, R) 这一个双参重载：
        // 直接传 null 会触发自动拆箱并抛 NPE（调用方不传该字段时必现），所以先做空值归一。
        queryWrapper.isNull(Boolean.TRUE.equals(nullSpaceId), "spaceId");


        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序：语义检索按「向量召回顺序」返回，其余情况沿用前端指定的排序字段
        if (CollUtil.isNotEmpty(semanticIds)) {
            queryWrapper.last("ORDER BY FIELD(id, " + joinPictureIds(semanticIds) + ")");
        } else {
            // 常量在前：客户端显式传 "sortOrder": null 时会覆盖掉字段初始值，
            // 写成 sortOrder.equals(...) 会 NPE（与 AlbumServiceImpl 的写法保持一致）
            queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), "ascend".equals(sortOrder), sortField);
        }
        return queryWrapper;
    }

    /**
     * 把召回的图片 id 拼成 ORDER BY FIELD(...) 需要的参数串
     * <p>
     * id 都是向量服务返回后经 Long 解析得到的，不存在拼接注入的风险。
     */
    private String joinPictureIds(List<Long> pictureIds) {
        return pictureIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }
        // 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
    }

    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 判断是否存在
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 已是该状态
        if (oldPicture.getReviewStatus().equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        // 更新审核状态
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(pictureReviewRequest, updatePicture);
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        boolean result = this.updateById(updatePicture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 审核通过后建立向量索引，并把模型生成的名称/简介/分类/标签补齐到图片上（只补空字段）；
        // 改为拒绝时把已有索引移除，避免被检索到
        if (reviewStatus == PictureReviewStatusEnum.PASS.getValue()) {
            pictureIndexService.indexPictureAndFillAsync(id);
        } else {
            pictureIndexService.removePictureIndexAsync(Collections.singletonList(id));
        }
        // 审核状态变了，列表缓存立即失效
        pictureListCacheManager.invalidateAll();
    }

    /**
     * 确定上传图片的审核状态：空间图片由空间创建者自动过审，公共图库按上传者角色决定
     */
    private void fillUploadReviewParams(Picture picture, Long spaceId, User loginUser) {
        if (spaceId != null) {
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("空间创建者自动过审");
            picture.setReviewTime(new Date());
        } else {
            fillReviewParams(picture, loginUser);
        }
    }

    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (userService.isAdmin(loginUser)) {
            // 管理员自动过审
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("管理员自动过审");
            picture.setReviewTime(new Date());
        } else {
            // 非管理员，创建或编辑都要改为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        String searchText = pictureUploadByBatchRequest.getSearchText();
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }
// ...
// 上传图片


        // 格式化数量
        Integer count = pictureUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count > 30, ErrorCode.PARAMS_ERROR, "最多 30 条");
        // 要抓取的地址
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);
        Document document;
        try {
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        Elements imgElementList = div.select("img.mimg");
        int uploadCount = 0;
        for (Element imgElement : imgElementList) {
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过: {}", fileUrl);
                continue;
            }
            // 处理图片上传地址，防止出现转义问题
            int questionMarkIndex = fileUrl.indexOf("?");
            if (questionMarkIndex > -1) {
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }
            // 上传图片
            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            if (StrUtil.isNotBlank(namePrefix)) {
                // 设置图片名称，序号连续递增
                pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            }
            try {
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUploadRequest, loginUser);
                log.info("图片上传成功, id = {}", pictureVO.getId());
                uploadCount++;
            } catch (Exception e) {
                log.error("图片上传失败", e);
                continue;
            }
            if (uploadCount >= count) {
                break;
            }
        }
        return uploadCount;
    }

    /**
     * 校验用户是否可以「修改图片内容」（替换原图、协同编辑等）
     * <p>
     * 比「编辑图片信息」更严格：
     * <ul>
     *     <li>公共图库：只有管理员可以修改。普通用户即便图片是自己上传的也不允许，
     *         这样既避免了「编辑后需要重新过审」的体验问题，也堵住了绕过审核的口子；</li>
     *     <li>空间图片：要求在该空间具备「编辑者」权限。</li>
     * </ul>
     */
    @Override
    public void checkPictureModifyAuth(User loginUser, Picture picture) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        if (userService.isAdmin(loginUser)) {
            return;
        }
        ThrowUtils.throwIf(picture.getSpaceId() == null, ErrorCode.NO_AUTH_ERROR,
                "公共图库的图片仅管理员可替换");
        // 空间图片复用统一校验：需要「编辑者」及以上权限
        checkPictureAuth(loginUser, picture);
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库，仅图片上传者本人或平台管理员可操作
            if (!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
            return;
        }
        // 空间内的图片：需要在该空间中具备「能看能上传还能编辑」权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.EDITOR);
    }

    @Override
    public void checkPictureViewAuth(User loginUser, Picture picture) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库对所有登录用户可见
            return;
        }
        // 空间内的图片：至少需要是该空间的成员（只读即可）
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.VIEWER);
    }

    @Async
    @Override
    public void clearPictureFile(Picture oldPicture) {
        // 判断该图片是否被多条记录使用
        String pictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery()
                .eq(Picture::getUrl, pictureUrl)
                .count();
        // 有不止一条记录用到了该图片，不清理
        if (count > 1) {
            return;
        }
        // FIXME 注意，这里的 url 包含了域名，实际上只要传 key 值（存储路径）就够了
        cosManager.deleteObject(oldPicture.getUrl());
        // 清理缩略图
        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)) {
            cosManager.deleteObject(thumbnailUrl);
        }
    }

    @Override
    public void deletePicture(long pictureId, User loginUser) {
        ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 判断是否存在
        Picture oldPicture = this.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        checkPictureAuth(loginUser, oldPicture);
//        // 操作数据库
//        boolean result = this.removeById(pictureId);
//        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

// 开启事务
        transactionTemplate.execute(status -> {
            // 操作数据库
            boolean result = this.removeById(pictureId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
            // 清理相册中对该图片的引用
            albumService.removePictureRelations(Collections.singletonList(pictureId));
            // 释放额度（阶段 4b 起是跨服务写，见 updateSpaceQuota 的注释）
            Long spaceId = oldPicture.getSpaceId();
            if (spaceId != null) {
                spaceService.updateSpaceQuota(spaceId, -oldPicture.getPicSize(), -1L, "deletePicture");
            }
            return true;
        });
// 异步清理文件
        this.clearPictureFile(oldPicture);
        // 移除向量索引，避免被检索到已删除的图片
        pictureIndexService.removePictureIndexAsync(Collections.singletonList(pictureId));
        // 图片已删除，列表缓存立即失效（否则列表里会继续展示它，点进去却是 404）
        pictureListCacheManager.invalidateAll();
    }

    /**
     * 单次批量删除的最大图片数量，防止一次性提交过多 id
     */
    private static final int MAX_BATCH_DELETE_COUNT = 100;

    @Override
    public int deletePictureBatch(List<Long> pictureIds, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIds), ErrorCode.PARAMS_ERROR, "请至少选择一张要删除的图片");
        // 过滤非法 id 并去重，避免同一个 id 被重复删除导致额度多次扣减
        List<Long> distinctIds = pictureIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        ThrowUtils.throwIf(CollUtil.isEmpty(distinctIds), ErrorCode.PARAMS_ERROR, "请至少选择一张要删除的图片");
        ThrowUtils.throwIf(distinctIds.size() > MAX_BATCH_DELETE_COUNT, ErrorCode.PARAMS_ERROR,
                "单次最多删除 " + MAX_BATCH_DELETE_COUNT + " 张图片");

        // 1. 查询图片，必须全部存在
        List<Picture> pictureList = this.listByIds(distinctIds);
        ThrowUtils.throwIf(pictureList.size() != distinctIds.size(), ErrorCode.NOT_FOUND_ERROR,
                "部分图片不存在或已被删除，请刷新后重试");

        // 2. 批量删除只允许操作私人空间内的图片，且必须属于同一个空间
        Set<Long> spaceIdSet = pictureList.stream().map(Picture::getSpaceId).collect(Collectors.toSet());
        ThrowUtils.throwIf(spaceIdSet.contains(null), ErrorCode.PARAMS_ERROR, "批量删除仅支持私人空间内的图片");
        ThrowUtils.throwIf(spaceIdSet.size() > 1, ErrorCode.PARAMS_ERROR, "只能批量删除同一个空间内的图片");
        Long spaceId = spaceIdSet.iterator().next();

        // 3. 校验空间权限：需要「能看能上传还能编辑」权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        spaceUserService.checkSpaceUserAuth(space, loginUser, SpaceUserRoleEnum.EDITOR);

        // 4. 汇总本次需要释放的容量与数量
        long totalSize = pictureList.stream()
                .mapToLong(picture -> picture.getPicSize() == null ? 0L : picture.getPicSize())
                .sum();
        long totalCount = pictureList.size();

        // 5. 事务：逻辑删除图片 + 一次性释放空间额度
        transactionTemplate.execute(status -> {
            boolean removeResult = this.removeByIds(distinctIds);
            ThrowUtils.throwIf(!removeResult, ErrorCode.OPERATION_ERROR, "批量删除失败");
            // 清理相册中对这些图片的引用
            albumService.removePictureRelations(distinctIds);
            // 一次性释放空间额度（阶段 4b 起是跨服务写，见 updateSpaceQuota 的注释）
            spaceService.updateSpaceQuota(spaceId, -totalSize, -totalCount, "deletePictureBatch");
            return true;
        });

        // 6. 清理对象存储中的文件
        pictureList.forEach(this::clearPictureFile);

        // 7. 移除这些图片的向量索引
        pictureIndexService.removePictureIndexAsync(distinctIds);

        // 8. 列表缓存立即失效，避免已删除的图片继续出现在列表里
        pictureListCacheManager.invalidateAll();

        log.info("批量删除图片成功，用户 id = {}，空间 id = {}，删除数量 = {}", loginUser.getId(), spaceId, totalCount);
        return (int) totalCount;
    }

    @Override
    public void editPicture(PictureEditRequest pictureEditRequest, User loginUser) {
        // 在此处将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        // 设置编辑时间
        picture.setEditTime(new Date());
        // 数据校验
        this.validPicture(picture);
        // 判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 校验权限
        checkPictureAuth(loginUser, oldPicture);
        // 补充审核参数
//        this.fillReviewParams(picture, loginUser);
        // 操作数据库
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 名称/简介/分类/标签变化后原来的向量已经失效，需要重新索引。
        // 编辑不是审核动作，这里只重建索引、不回填
        pictureIndexService.indexPictureAsync(id);
        // 列表里展示的仍是旧内容，缓存立即失效
        pictureListCacheManager.invalidateAll();
    }










}