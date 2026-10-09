package com.flipped.spaceservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flipped.spaceservice.exception.BusinessException;
import com.flipped.spaceservice.exception.ErrorCode;
import com.flipped.spaceservice.exception.ThrowUtils;
import com.flipped.spaceservice.mapper.SpaceMapper;
import com.flipped.spaceservice.mapper.SpaceUserMapper;
import com.flipped.spaceservice.model.dto.space.QuotaReserveResult;
import com.flipped.spaceservice.model.dto.space.SpaceAddRequest;
import com.flipped.spaceservice.model.dto.space.SpaceQueryRequest;
import com.flipped.spaceservice.model.dto.space.SpaceUpdateRequest;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.entity.SpaceUser;
import com.flipped.spaceservice.model.enums.SpaceLevelEnum;
import com.flipped.spaceservice.model.enums.SpaceTypeEnum;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.model.enums.SpaceUserStatusEnum;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.service.SpaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 空间表 Service 实现（space 表的唯一属主）
 */
@Service
@Slf4j
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space> implements SpaceService {

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private AuthService authService;

    /**
     * 直接注入 Mapper 而不是 SpaceUserService：SpaceUserService 需要 SpaceService 来校验空间权限，
     * 反向依赖会形成循环引用。角色判定本身只是一次 space_user 查询，放在这里没有额外成本。
     */
    @Resource
    private SpaceUserMapper spaceUserMapper;

    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, Long loginUserId) {
        ThrowUtils.throwIf(spaceAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUserId == null || loginUserId <= 0, ErrorCode.NO_AUTH_ERROR);
        // 实体类和 DTO 转换
        Space space = new Space();
        BeanUtils.copyProperties(spaceAddRequest, space);
        // 默认值
        if (StrUtil.isBlank(spaceAddRequest.getSpaceName())) {
            space.setSpaceName("默认空间");
        }
        if (spaceAddRequest.getSpaceLevel() == null) {
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        // 空间类型，不传默认为私有空间
        Integer spaceType = spaceAddRequest.getSpaceType();
        if (spaceType == null) {
            spaceType = SpaceTypeEnum.PRIVATE.getValue();
        }
        ThrowUtils.throwIf(SpaceTypeEnum.getEnumByValue(spaceType) == null,
                ErrorCode.PARAMS_ERROR, "空间类型不合法");
        space.setSpaceType(spaceType);
        boolean isTeamSpace = SpaceTypeEnum.TEAM.getValue() == spaceType;
        // 填充数据
        this.fillSpaceBySpaceLevel(space);
        // 数据校验
        this.validSpace(space, true);
        space.setUserId(loginUserId);
        // 权限校验
        // 注意：这里必须比较「实体」的 spaceLevel，不能比较请求对象的。
        // 上面已经给实体填过默认值（且 validSpace 保证了它非空），而请求对象的 spaceLevel
        // 仍然可能是 null —— 直接用它会自动拆箱抛 NPE，也就是「不传 spaceLevel 建空间就 500」。
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !authService.isAdmin(loginUserId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        // 针对用户进行加锁（单实例内串行化同一用户的建空间请求，防止并发建出两个私有空间）
        String lock = String.valueOf(loginUserId).intern();
        synchronized (lock) {
            Long newSpaceId = transactionTemplate.execute(status -> {
                // 私有空间：每个用户仅能创建一个；团队空间：可以创建多个
                if (!isTeamSpace) {
                    boolean exists = this.lambdaQuery()
                            .eq(Space::getUserId, loginUserId)
                            .eq(Space::getSpaceType, SpaceTypeEnum.PRIVATE.getValue())
                            .exists();
                    ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "每个用户仅能有一个私有空间");
                }
                // 写入数据库
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
                // 团队空间：把创建者写入成员表，角色为「管理员」
                if (isTeamSpace) {
                    Date now = new Date();
                    SpaceUser manager = new SpaceUser();
                    manager.setSpaceId(space.getId());
                    manager.setUserId(loginUserId);
                    manager.setSpaceRole(SpaceUserRoleEnum.MANAGER.getValue());
                    manager.setStatus(SpaceUserStatusEnum.JOINED.getValue());
                    manager.setCreateTime(now);
                    manager.setUpdateTime(now);
                    int inserted = spaceUserMapper.insert(manager);
                    ThrowUtils.throwIf(inserted <= 0, ErrorCode.OPERATION_ERROR, "空间成员初始化失败");
                }
                return space.getId();
            });
            return Optional.ofNullable(newSpaceId).orElse(-1L);
        }
    }

    @Override
    public boolean updateSpace(SpaceUpdateRequest spaceUpdateRequest) {
        ThrowUtils.throwIf(spaceUpdateRequest == null
                || spaceUpdateRequest.getId() == null || spaceUpdateRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        // 将实体类和 DTO 进行转换
        Space space = new Space();
        BeanUtils.copyProperties(spaceUpdateRequest, space);
        // 自动填充数据
        this.fillSpaceBySpaceLevel(space);
        // 数据校验
        this.validSpace(space, false);
        // 判断是否存在
        long id = spaceUpdateRequest.getId();
        Space oldSpace = this.getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR);
        // 操作数据库
        boolean result = this.updateById(space);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean deleteSpace(Long spaceId, Long loginUserId) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        Space oldSpace = this.getById(spaceId);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或者管理员可删除
        this.checkSpaceAuth(loginUserId, oldSpace);
        Boolean result = transactionTemplate.execute(status -> {
            boolean removed = this.removeById(spaceId);
            ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR);
            // 连带清理成员记录：space_user 表没有逻辑删除，不清理会留下永远无人引用的脏记录
            // （原单体实现没有这一步；放到本服务里是因为它同时拥有两张表，可以在一个本地事务里做掉）
            int deletedMembers = spaceUserMapper.delete(
                    new LambdaQueryWrapper<SpaceUser>().eq(SpaceUser::getSpaceId, spaceId));
            log.info("删除空间 id = {}，连带清理成员记录 {} 条", spaceId, deletedMembers);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        // 要创建
        if (add) {
            if (StrUtil.isBlank(spaceName)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称不能为空");
            }
            if (spaceLevel == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不能为空");
            }
        }
        // 修改数据时，如果要改空间级别
        if (spaceLevel != null && spaceLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
        }
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称过长");
        }
    }

    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        // 根据空间级别，自动填充限额
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if (spaceLevelEnum != null) {
            long maxSize = spaceLevelEnum.getMaxSize();
            if (space.getMaxSize() == null) {
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null) {
                space.setMaxCount(maxCount);
            }
        }
    }

    @Override
    public void checkSpaceAuth(Long loginUserId, Space space) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 仅本人或管理员可编辑
        if (!space.getUserId().equals(loginUserId) && !authService.isAdmin(loginUserId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    @Override
    public Page<Space> listMySpaceByPage(long current, long size, Long loginUserId) {
        ThrowUtils.throwIf(loginUserId == null, ErrorCode.NO_AUTH_ERROR);
        // 我加入的团队空间 id
        List<Long> joinedSpaceIds = spaceUserMapper.selectList(new LambdaQueryWrapper<SpaceUser>()
                        .eq(SpaceUser::getUserId, loginUserId)
                        .eq(SpaceUser::getStatus, SpaceUserStatusEnum.JOINED.getValue()))
                .stream()
                .map(SpaceUser::getSpaceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        LambdaQueryWrapper<Space> wrapper = new LambdaQueryWrapper<>();
        if (CollUtil.isEmpty(joinedSpaceIds)) {
            wrapper.eq(Space::getUserId, loginUserId);
        } else {
            // 我创建的 或者 我加入的
            wrapper.and(w -> w.eq(Space::getUserId, loginUserId)
                    .or()
                    .in(Space::getId, joinedSpaceIds));
        }
        wrapper.orderByDesc(Space::getCreateTime);
        return this.page(new Page<>(current, size), wrapper);
    }

    @Override
    public Page<Space> listSpaceByPage(SpaceQueryRequest queryRequest) {
        SpaceQueryRequest request = queryRequest == null ? new SpaceQueryRequest() : queryRequest;
        return this.page(new Page<>(request.getCurrent(), request.getPageSize()), getQueryWrapper(request));
    }

    @Override
    public Long getSpaceIdByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        // 只查询 id 字段，提升效率
        LambdaQueryWrapper<Space> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Space::getUserId, userId)
                .eq(Space::getSpaceType, SpaceTypeEnum.PRIVATE.getValue())
                .select(Space::getId);
        Space space = this.getOne(wrapper);
        return space != null ? space.getId() : null;
    }

    @Override
    public Space getById(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            return null;
        }
        return super.getById(spaceId);
    }

    @Override
    public List<Space> listByIds(Collection<? extends Serializable> spaceIds) {
        if (CollUtil.isEmpty(spaceIds)) {
            return new ArrayList<>();
        }
        return super.listByIds(spaceIds);
    }

    @Override
    public Space getQuota(Long spaceId) {
        return this.getById(spaceId);
    }

    /**
     * 原子「校验 + 预占」额度（阶段 5d-b）
     *
     * <p><b>解决的是 TOCTOU</b>：改动前，单体在上传前查一次额度、事务内再扣一次，
     * 中间还夹着一次 COS 上传（几百毫秒）—— 两个并发上传可以**都通过检查**，最后一起超限。
     * 这里把校验放进 UPDATE 的 WHERE 条件，由数据库保证原子性，从根上消除窗口。
     *
     * <p><b>一个刻意的设计：只有「占用增加」才做上限校验</b>
     * <ul>
     *     <li>新增图片（countDelta &gt; 0）：校验 {@code totalCount + Δ ≤ maxCount}；</li>
     *     <li>覆盖重传（countDelta == 0）且体积变大：校验 {@code totalSize + Δ ≤ maxSize}；</li>
     *     <li>删除/减量（Δ &lt; 0）：**不校验**。限额配错导致空间已经超限时，
     *         删除必须能正常执行，否则用户会被永久卡死（只能找 DBA 改库）。</li>
     * </ul>
     * 这个判断在 Java 里算好再拼进 SQL，是为了让「是否需要校验」这件事显式可读
     * （纯 SQL 里表达会变成绕来绕去的 OR 条件）。
     */
    @Override
    public QuotaReserveResult reserveQuota(Long spaceId, long sizeDelta, long countDelta, String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        Space space = this.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

        long currentSize = space.getTotalSize() == null ? 0L : space.getTotalSize();
        long currentCount = space.getTotalCount() == null ? 0L : space.getTotalCount();
        long maxSize = space.getMaxSize() == null ? Long.MAX_VALUE : space.getMaxSize();
        long maxCount = space.getMaxCount() == null ? Long.MAX_VALUE : space.getMaxCount();

        long newSize = currentSize + sizeDelta;
        long newCount = currentCount + countDelta;

        // 用**库里的当前值**先做一次快速判定，给出明确的业务提示。
        // 真正的原子性由下面 WHERE 里的条件保证（这里的判定只是为了让错误信息具体）
        if (countDelta > 0 && newCount > maxCount) {
            log.info("空间条数不足：spaceId = {}，当前 {}，本次 +{}，上限 {}", spaceId, currentCount, countDelta, maxCount);
            return QuotaReserveResult.limited("空间条数不足");
        }
        if (sizeDelta > 0 && newSize > maxSize) {
            log.info("空间大小不足：spaceId = {}，当前 {}，本次 +{}，上限 {}", spaceId, currentSize, sizeDelta, maxSize);
            return QuotaReserveResult.limited("空间大小不足");
        }

        // 原子 UPDATE：把上限校验写进 WHERE，条件不满足则影响 0 行 —— 由数据库保证竞态下的正确性
        LambdaUpdateWrapper<Space> update = new LambdaUpdateWrapper<Space>()
                .eq(Space::getId, spaceId)
                .setSql("totalSize = COALESCE(totalSize, 0) + (" + sizeDelta + ")")
                .setSql("totalCount = COALESCE(totalCount, 0) + (" + countDelta + ")");
        if (countDelta > 0) {
            update.apply("COALESCE(totalCount, 0) + {0} <= COALESCE(maxCount, 0)", countDelta);
        }
        if (sizeDelta > 0) {
            update.apply("COALESCE(totalSize, 0) + {0} <= COALESCE(maxSize, 0)", sizeDelta);
        }
        boolean updated = this.update(update);
        if (!updated) {
            // 到这里的两种情况：①并发下被别人先占满（原子条件拦住了）
            // ②空间被并发删除。都按「额度不足/空间不可用」返回业务提示，不当系统故障。
            log.warn("额度预占未生效（可能被并发占满或空间已删除）：spaceId = {}，sizeDelta = {}，countDelta = {}",
                    spaceId, sizeDelta, countDelta);
            return QuotaReserveResult.limited("空间额度不足（并发占满）");
        }
        Space latest = this.getById(spaceId);
        log.info("额度预占成功：spaceId = {}，sizeDelta = {}，countDelta = {}，原因 = {}，" +
                        "预占后 totalSize = {}，totalCount = {}",
                spaceId, sizeDelta, countDelta, reason,
                latest == null ? null : latest.getTotalSize(),
                latest == null ? null : latest.getTotalCount());
        return QuotaReserveResult.ok(latest);
    }

    @Override
    public Space applyQuotaDelta(Long spaceId, long sizeDelta, long countDelta, String reason) {
        ThrowUtils.throwIf(spaceId == null || spaceId <= 0, ErrorCode.PARAMS_ERROR);
        Space space = this.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // GREATEST(...,0) 兜底：并发删除时不会把统计值减成负数
        boolean update = this.lambdaUpdate()
                .eq(Space::getId, spaceId)
                .setSql("totalSize = GREATEST(COALESCE(totalSize, 0) + (" + sizeDelta + "), 0)")
                .setSql("totalCount = GREATEST(COALESCE(totalCount, 0) + (" + countDelta + "), 0)")
                .update();
        ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "额度更新失败");
        Space latest = this.getById(spaceId);
        log.info("空间配额变更：spaceId = {}，sizeDelta = {}，countDelta = {}，原因 = {}，" +
                        "变更后 totalSize = {}，totalCount = {}",
                spaceId, sizeDelta, countDelta, reason,
                latest == null ? null : latest.getTotalSize(),
                latest == null ? null : latest.getTotalCount());
        return latest;
    }

    /**
     * 构造空间查询条件。
     * <p>
     * 刻意留在服务内部：QueryWrapper 是 MyBatis-Plus 的实现细节，
     * 不该出现在跨服务契约里（单体侧只需要把 SpaceQueryRequest 传过来）。
     */
    private QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        Integer spaceType = spaceQueryRequest.getSpaceType();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType), "spaceType", spaceType);
        // 排序
        // 常量在前：客户端显式传 "sortOrder": null 时字段初始值会被覆盖成 null，
        // 写成 sortOrder.equals(...) 会直接 NPE
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), "ascend".equals(sortOrder), sortField);
        return queryWrapper;
    }
}
