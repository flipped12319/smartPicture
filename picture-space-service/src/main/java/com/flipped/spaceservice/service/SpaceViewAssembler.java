package com.flipped.spaceservice.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.vo.SpaceVO;

import java.util.Collection;
import java.util.List;

/**
 * 空间封装类装配器。
 * <p>
 * 单独抽出来是因为「Space → SpaceVO 需要补用户信息」这个动作有三处调用
 * （单个查询、分页查询、我的空间列表），而且它跨越了一次服务调用（问 user-service）。
 * 与业务逻辑放在一起会让 SpaceService 同时承担「领域规则」和「跨服务渲染聚合」两件事。
 * <p>
 * 用户信息走**渲染路径**：user-service 不可用时 user 字段为 null，其余字段照常返回。
 */
public interface SpaceViewAssembler {

    /**
     * 单条装配。{@code loginUserId} 不为空时填充 currentUserRole（当前用户在该空间的角色）。
     */
    SpaceVO toVO(Space space, Long loginUserId);

    /**
     * 分页装配。
     */
    Page<SpaceVO> toVOPage(Page<Space> spacePage, Long loginUserId);

    /**
     * 列表装配。
     */
    List<SpaceVO> toVOList(Collection<Space> spaceList, Long loginUserId);
}
