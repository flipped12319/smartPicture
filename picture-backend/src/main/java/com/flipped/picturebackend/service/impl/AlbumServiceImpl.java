package com.flipped.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flipped.picturebackend.exception.BusinessException;
import com.flipped.picturebackend.exception.ErrorCode;
import com.flipped.picturebackend.exception.ThrowUtils;
import com.flipped.picturebackend.mapper.AlbumMapper;
import com.flipped.picturebackend.mapper.AlbumPictureMapper;
import com.flipped.picturebackend.model.dto.album.AlbumAddRequest;
import com.flipped.picturebackend.model.dto.album.AlbumEditRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureQueryRequest;
import com.flipped.picturebackend.model.dto.album.AlbumPictureRequest;
import com.flipped.picturebackend.model.dto.album.AlbumQueryRequest;
import com.flipped.picturebackend.model.entity.Album;
import com.flipped.picturebackend.model.entity.AlbumPicture;
import com.flipped.picturebackend.model.entity.Picture;
import com.flipped.picturebackend.model.entity.User;
import com.flipped.picturebackend.model.vo.AlbumVO;
import com.flipped.picturebackend.model.vo.PictureVO;
import com.flipped.picturebackend.model.vo.UserVO;
import com.flipped.picturebackend.service.AlbumService;
import com.flipped.picturebackend.service.IPictureService;
import com.flipped.picturebackend.service.SpaceService;
import com.flipped.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 相册服务实现类
 */
@Service
@Slf4j
public class AlbumServiceImpl extends ServiceImpl<AlbumMapper, Album> implements AlbumService {

    /**
     * 单个相册最多容纳的图片数量
     */
    private static final int MAX_ALBUM_PICTURE_COUNT = 500;

    /**
     * 单次批量操作的最大图片数量
     */
    private static final int MAX_BATCH_SIZE = 100;

    @Resource
    private AlbumPictureMapper albumPictureMapper;

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    /**
     * 使用 @Lazy 打破与 PictureServiceImpl 的循环依赖：
     * 图片删除时需要回调相册服务清理关联，而相册服务又需要查询图片。
     */
    @Resource
    @Lazy
    private IPictureService pictureService;

    @Resource
    private TransactionTemplate transactionTemplate;

    // ──────────────────────────── 相册增删改 ────────────────────────────

    @Override
    public long addAlbum(AlbumAddRequest albumAddRequest, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        Album album = new Album();
        BeanUtils.copyProperties(albumAddRequest, album);
        this.validAlbum(album, true);
        album.setUserId(loginUser.getId());
        // 相册保存到当前用户的私人空间中
        Long spaceId = spaceService.getSpaceIdByUserId(loginUser.getId());
        ThrowUtils.throwIf(spaceId == null, ErrorCode.OPERATION_ERROR, "请先创建私人空间，再创建相册");
        album.setSpaceId(spaceId);
        // 校验初始图片
        List<Long> pictureIds = validPictureIds(albumAddRequest.getPictureIds(), loginUser);

        Long newAlbumId = transactionTemplate.execute(status -> {
            boolean saved = this.save(album);
            ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "相册创建失败");
            if (CollUtil.isNotEmpty(pictureIds)) {
                insertAlbumPictures(album.getId(), pictureIds);
            }
            return album.getId();
        });
        return Optional.ofNullable(newAlbumId).orElse(-1L);
    }

    @Override
    public void editAlbum(AlbumEditRequest albumEditRequest, User loginUser) {
        ThrowUtils.throwIf(albumEditRequest == null, ErrorCode.PARAMS_ERROR);
        Long albumId = albumEditRequest.getId();
        ThrowUtils.throwIf(albumId == null || albumId <= 0, ErrorCode.PARAMS_ERROR);
        Album oldAlbum = this.getById(albumId);
        ThrowUtils.throwIf(oldAlbum == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        this.checkAlbumAuth(loginUser, oldAlbum);

        Album album = new Album();
        BeanUtils.copyProperties(albumEditRequest, album);
        this.validAlbum(album, false);
        // 说明是相册的必填信息，不允许改成空白
        if (album.getIntroduction() != null) {
            ThrowUtils.throwIf(StrUtil.isBlank(album.getIntroduction()), ErrorCode.PARAMS_ERROR, "相册说明不能为空");
        }
        album.setEditTime(new Date());
        boolean result = this.updateById(album);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "相册更新失败");
    }

    @Override
    public void deleteAlbum(long albumId, User loginUser) {
        ThrowUtils.throwIf(albumId <= 0, ErrorCode.PARAMS_ERROR);
        Album oldAlbum = this.getById(albumId);
        ThrowUtils.throwIf(oldAlbum == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        this.checkAlbumAuth(loginUser, oldAlbum);
        transactionTemplate.execute(status -> {
            // 逻辑删除相册
            boolean removed = this.removeById(albumId);
            ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "相册删除失败");
            // 清理关联关系（关联表为物理删除）
            albumPictureMapper.delete(new LambdaQueryWrapper<AlbumPicture>()
                    .eq(AlbumPicture::getAlbumId, albumId));
            return true;
        });
        log.info("删除相册成功，相册 id = {}，操作人 id = {}", albumId, loginUser.getId());
    }

    @Override
    public void validAlbum(Album album, boolean add) {
        ThrowUtils.throwIf(album == null, ErrorCode.PARAMS_ERROR);
        String name = album.getName();
        String introduction = album.getIntroduction();
        if (add) {
            ThrowUtils.throwIf(StrUtil.isBlank(name), ErrorCode.PARAMS_ERROR, "相册名称不能为空");
            // 相册必须附带说明
            ThrowUtils.throwIf(StrUtil.isBlank(introduction), ErrorCode.PARAMS_ERROR, "相册说明不能为空");
        }
        if (StrUtil.isNotBlank(name) && name.length() > 64) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "相册名称过长");
        }
        if (StrUtil.isNotBlank(introduction) && introduction.length() > 512) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "相册说明过长");
        }
    }

    @Override
    public void checkAlbumAuth(User loginUser, Album album) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(album == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        // 相册保存在私人空间中，仅创建者本人或管理员可操作
        if (!album.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有相册权限");
        }
    }

    // ──────────────────────────── 相册内图片 ────────────────────────────

    @Override
    public int addPictureToAlbum(AlbumPictureRequest albumPictureRequest, User loginUser) {
        Album album = getAndCheckAlbum(albumPictureRequest, loginUser);
        List<Long> pictureIds = validPictureIds(albumPictureRequest.getPictureIds(), loginUser);
        if (CollUtil.isEmpty(pictureIds)) {
            return 0;
        }
        // 过滤掉已经在相册中的图片
        List<AlbumPicture> existingList = albumPictureMapper.selectList(
                new LambdaQueryWrapper<AlbumPicture>()
                        .eq(AlbumPicture::getAlbumId, album.getId())
                        .in(AlbumPicture::getPictureId, pictureIds));
        Set<Long> existingPictureIds = existingList.stream()
                .map(AlbumPicture::getPictureId)
                .collect(Collectors.toSet());
        List<Long> toInsertIds = pictureIds.stream()
                .filter(pictureId -> !existingPictureIds.contains(pictureId))
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(toInsertIds)) {
            return 0;
        }
        // 校验相册容量（existingList 只包含本次提交的图片，必须重新统计相册总量）
        long currentCount = countAlbumPictures(album.getId());
        ThrowUtils.throwIf(currentCount + toInsertIds.size() > MAX_ALBUM_PICTURE_COUNT,
                ErrorCode.OPERATION_ERROR, "单个相册最多容纳 " + MAX_ALBUM_PICTURE_COUNT + " 张图片");

        Integer inserted = transactionTemplate.execute(status -> insertAlbumPictures(album.getId(), toInsertIds));
        return Optional.ofNullable(inserted).orElse(0);
    }

    @Override
    public int removePictureFromAlbum(AlbumPictureRequest albumPictureRequest, User loginUser) {
        Album album = getAndCheckAlbum(albumPictureRequest, loginUser);
        List<Long> pictureIds = albumPictureRequest.getPictureIds();
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIds), ErrorCode.PARAMS_ERROR, "请至少选择一张图片");
        List<Long> distinctIds = pictureIds.stream()
                .filter(pictureId -> pictureId != null && pictureId > 0)
                .distinct()
                .collect(Collectors.toList());
        ThrowUtils.throwIf(CollUtil.isEmpty(distinctIds), ErrorCode.PARAMS_ERROR, "请至少选择一张图片");
        int removed = albumPictureMapper.delete(new LambdaQueryWrapper<AlbumPicture>()
                .eq(AlbumPicture::getAlbumId, album.getId())
                .in(AlbumPicture::getPictureId, distinctIds));
        log.info("从相册移出图片，相册 id = {}，移出数量 = {}", album.getId(), removed);
        return removed;
    }

    @Override
    public Page<PictureVO> listAlbumPictureByPage(AlbumPictureQueryRequest albumPictureQueryRequest,
                                                  User loginUser, HttpServletRequest request) {
        ThrowUtils.throwIf(albumPictureQueryRequest == null, ErrorCode.PARAMS_ERROR);
        Long albumId = albumPictureQueryRequest.getAlbumId();
        ThrowUtils.throwIf(albumId == null || albumId <= 0, ErrorCode.PARAMS_ERROR, "相册 id 不能为空");
        Album album = this.getById(albumId);
        ThrowUtils.throwIf(album == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        this.checkAlbumAuth(loginUser, album);

        long current = albumPictureQueryRequest.getCurrent();
        long size = albumPictureQueryRequest.getPageSize();
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 条");

        // 先按关联记录分页，保证与相册内的展示顺序一致
        Page<AlbumPicture> relationPage = albumPictureMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AlbumPicture>()
                        .eq(AlbumPicture::getAlbumId, albumId)
                        .orderByAsc(AlbumPicture::getId));
        Page<PictureVO> pictureVOPage = new Page<>(relationPage.getCurrent(), relationPage.getSize(),
                relationPage.getTotal());
        List<Long> pictureIds = relationPage.getRecords().stream()
                .map(AlbumPicture::getPictureId)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(pictureIds)) {
            return pictureVOPage;
        }
        // 查询图片并还原关联顺序
        Map<Long, Picture> pictureMap = pictureService.listByIds(pictureIds).stream()
                .collect(Collectors.toMap(Picture::getId, picture -> picture, (a, b) -> a));
        List<Picture> orderedPictures = pictureIds.stream()
                .map(pictureMap::get)
                .filter(ObjUtil::isNotNull)
                .collect(Collectors.toList());
        Page<Picture> picturePage = new Page<>(relationPage.getCurrent(), relationPage.getSize(),
                relationPage.getTotal());
        picturePage.setRecords(orderedPictures);
        return pictureService.getPictureVOPage(picturePage, request);
    }

    @Override
    public void removePictureRelations(List<Long> pictureIds) {
        if (CollUtil.isEmpty(pictureIds)) {
            return;
        }
        int deleted = albumPictureMapper.delete(new LambdaQueryWrapper<AlbumPicture>()
                .in(AlbumPicture::getPictureId, pictureIds));
        if (deleted > 0) {
            log.info("图片被删除，已清理相册关联 {} 条", deleted);
        }
    }

    // ──────────────────────────── 封装类与查询 ────────────────────────────

    @Override
    public AlbumVO getAlbumVO(Album album, HttpServletRequest request) {
        AlbumVO albumVO = AlbumVO.objToVo(album);
        if (albumVO == null) {
            return null;
        }
        // 关联查询用户信息
        Long userId = album.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            albumVO.setUser(userVO);
        }
        // 统计图片数量与封面
        fillAlbumPictureInfo(Collections.singletonList(albumVO));
        return albumVO;
    }

    @Override
    public Page<AlbumVO> getAlbumVOPage(Page<Album> albumPage, HttpServletRequest request) {
        List<Album> albumList = albumPage.getRecords();
        Page<AlbumVO> albumVOPage = new Page<>(albumPage.getCurrent(), albumPage.getSize(), albumPage.getTotal());
        if (CollUtil.isEmpty(albumList)) {
            return albumVOPage;
        }
        List<AlbumVO> albumVOList = albumList.stream()
                .map(AlbumVO::objToVo)
                .collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = albumList.stream().map(Album::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        albumVOList.forEach(albumVO -> {
            List<User> userList = userIdUserListMap.get(albumVO.getUserId());
            User user = CollUtil.isEmpty(userList) ? null : userList.get(0);
            albumVO.setUser(userService.getUserVO(user));
        });
        // 2. 统计每个相册的图片数量与封面
        fillAlbumPictureInfo(albumVOList);
        albumVOPage.setRecords(albumVOList);
        return albumVOPage;
    }

    @Override
    public QueryWrapper<Album> getQueryWrapper(AlbumQueryRequest albumQueryRequest) {
        QueryWrapper<Album> queryWrapper = new QueryWrapper<>();
        if (albumQueryRequest == null) {
            return queryWrapper;
        }
        Long id = albumQueryRequest.getId();
        Long userId = albumQueryRequest.getUserId();
        Long spaceId = albumQueryRequest.getSpaceId();
        String name = albumQueryRequest.getName();
        String sortField = albumQueryRequest.getSortField();
        String sortOrder = albumQueryRequest.getSortOrder();

        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        // 排序，默认按创建时间倒序
        boolean isAsc = "ascend".equals(sortOrder);
        if (StrUtil.isNotEmpty(sortField)) {
            queryWrapper.orderBy(true, isAsc, sortField);
        } else {
            queryWrapper.orderByDesc("createTime");
        }
        return queryWrapper;
    }

    // ──────────────────────────── 私有辅助方法 ────────────────────────────

    /**
     * 取出相册并校验操作权限
     */
    private Album getAndCheckAlbum(AlbumPictureRequest albumPictureRequest, User loginUser) {
        ThrowUtils.throwIf(albumPictureRequest == null, ErrorCode.PARAMS_ERROR);
        Long albumId = albumPictureRequest.getAlbumId();
        ThrowUtils.throwIf(albumId == null || albumId <= 0, ErrorCode.PARAMS_ERROR, "相册 id 不能为空");
        Album album = this.getById(albumId);
        ThrowUtils.throwIf(album == null, ErrorCode.NOT_FOUND_ERROR, "相册不存在");
        this.checkAlbumAuth(loginUser, album);
        return album;
    }

    /**
     * 校验待加入相册的图片：必须存在，且属于公共图库或本人的私人空间
     *
     * @return 去重后的合法图片 id 列表
     */
    private List<Long> validPictureIds(List<Long> rawPictureIds, User loginUser) {
        if (CollUtil.isEmpty(rawPictureIds)) {
            return Collections.emptyList();
        }
        List<Long> pictureIds = rawPictureIds.stream()
                .filter(pictureId -> pictureId != null && pictureId > 0)
                .distinct()
                .collect(Collectors.toList());
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIds), ErrorCode.PARAMS_ERROR, "图片 id 不合法");
        ThrowUtils.throwIf(pictureIds.size() > MAX_BATCH_SIZE, ErrorCode.PARAMS_ERROR,
                "单次最多操作 " + MAX_BATCH_SIZE + " 张图片");

        List<Picture> pictureList = pictureService.listByIds(pictureIds);
        ThrowUtils.throwIf(pictureList.size() != pictureIds.size(), ErrorCode.NOT_FOUND_ERROR,
                "部分图片不存在或已被删除，请刷新后重试");

        Long mySpaceId = spaceService.getSpaceIdByUserId(loginUser.getId());
        for (Picture picture : pictureList) {
            Long spaceId = picture.getSpaceId();
            // spaceId 为空表示公共图库，允许加入；否则必须是本人的私人空间
            if (spaceId != null && !spaceId.equals(mySpaceId)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能选择公共图库或本人私人空间中的图片");
            }
        }
        return pictureIds;
    }

    /**
     * 批量写入相册图片关联（不加事务，由调用方保证）
     *
     * @return 写入的条数
     */
    private int insertAlbumPictures(Long albumId, List<Long> pictureIds) {
        if (CollUtil.isEmpty(pictureIds)) {
            return 0;
        }
        Date now = new Date();
        int count = 0;
        for (Long pictureId : pictureIds) {
            AlbumPicture albumPicture = new AlbumPicture();
            albumPicture.setAlbumId(albumId);
            albumPicture.setPictureId(pictureId);
            albumPicture.setCreateTime(now);
            albumPicture.setUpdateTime(now);
            count += albumPictureMapper.insert(albumPicture);
        }
        return count;
    }

    /**
     * 统计相册内的图片数量
     */
    private long countAlbumPictures(Long albumId) {
        Long count = albumPictureMapper.selectCount(new LambdaQueryWrapper<AlbumPicture>()
                .eq(AlbumPicture::getAlbumId, albumId));
        return count == null ? 0L : count;
    }

    /**
     * 填充相册的图片数量与封面（取相册内第一张图片的缩略图）
     */
    private void fillAlbumPictureInfo(List<AlbumVO> albumVOList) {
        if (CollUtil.isEmpty(albumVOList)) {
            return;
        }
        List<Long> albumIds = albumVOList.stream()
                .map(AlbumVO::getId)
                .filter(ObjUtil::isNotNull)
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(albumIds)) {
            return;
        }
        // 一次查出这些相册的关联记录，按 id 升序，第一条即封面
        List<AlbumPicture> relationList = albumPictureMapper.selectList(
                new LambdaQueryWrapper<AlbumPicture>()
                        .in(AlbumPicture::getAlbumId, albumIds)
                        .orderByAsc(AlbumPicture::getId));
        Map<Long, List<AlbumPicture>> relationMap = relationList.stream()
                .collect(Collectors.groupingBy(AlbumPicture::getAlbumId));
        // 收集每个相册的第一张图片 id
        Map<Long, Long> coverPictureIdMap = new HashMap<>();
        relationMap.forEach((albumId, relations) -> {
            if (CollUtil.isNotEmpty(relations)) {
                coverPictureIdMap.put(albumId, relations.get(0).getPictureId());
            }
        });
        // 批量查询封面图片
        Map<Long, String> coverUrlMap = new HashMap<>();
        if (!coverPictureIdMap.isEmpty()) {
            Map<Long, String> pictureIdUrlMap = pictureService.listByIds(coverPictureIdMap.values()).stream()
                    .collect(Collectors.toMap(Picture::getId, this::getPictureCoverUrl, (a, b) -> a));
            coverPictureIdMap.forEach((albumId, pictureId) -> {
                String coverUrl = pictureIdUrlMap.get(pictureId);
                if (StrUtil.isNotBlank(coverUrl)) {
                    coverUrlMap.put(albumId, coverUrl);
                }
            });
        }
        for (AlbumVO albumVO : albumVOList) {
            List<AlbumPicture> relations = relationMap.getOrDefault(albumVO.getId(),
                    new ArrayList<>());
            albumVO.setPictureCount((long) relations.size());
            albumVO.setCoverUrl(coverUrlMap.get(albumVO.getId()));
        }
    }

    /**
     * 取图片的缩略图地址，没有缩略图时回退到原图
     */
    private String getPictureCoverUrl(Picture picture) {
        if (picture == null) {
            return null;
        }
        return StrUtil.isNotBlank(picture.getThumbnailUrl()) ? picture.getThumbnailUrl() : picture.getUrl();
    }
}
