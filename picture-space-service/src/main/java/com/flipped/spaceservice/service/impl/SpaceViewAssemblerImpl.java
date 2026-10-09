package com.flipped.spaceservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flipped.spaceservice.model.entity.Space;
import com.flipped.spaceservice.model.enums.SpaceUserRoleEnum;
import com.flipped.spaceservice.model.vo.SpaceVO;
import com.flipped.spaceservice.model.vo.UserVO;
import com.flipped.spaceservice.service.AuthService;
import com.flipped.spaceservice.service.SpaceUserService;
import com.flipped.spaceservice.service.SpaceViewAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 空间封装类装配器实现
 */
@Component
public class SpaceViewAssemblerImpl implements SpaceViewAssembler {

    @Resource
    private AuthService authService;

    @Resource
    private SpaceUserService spaceUserService;

    @Override
    public SpaceVO toVO(Space space, Long loginUserId) {
        if (space == null) {
            return null;
        }
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        spaceVO.setUser(authService.toUserVO(space.getUserId()));
        if (loginUserId != null) {
            SpaceUserRoleEnum roleEnum = spaceUserService.getRoleInSpace(space, loginUserId);
            if (roleEnum != null) {
                spaceVO.setCurrentUserRole(roleEnum.getValue());
            }
        }
        return spaceVO;
    }

    @Override
    public Page<SpaceVO> toVOPage(Page<Space> spacePage, Long loginUserId) {
        Page<SpaceVO> voPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        List<Space> spaceList = spacePage.getRecords();
        if (CollUtil.isEmpty(spaceList)) {
            return voPage;
        }
        List<SpaceVO> voList = spaceList.stream().map(SpaceVO::objToVo).collect(Collectors.toList());
        fillUsers(spaceList, voList);
        if (loginUserId != null) {
            // 一次性把「我在这批空间里的角色」查出来，避免逐个空间查库
            List<Long> spaceIds = voList.stream().map(SpaceVO::getId).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            Map<Long, SpaceUserRoleEnum> roleMap = spaceUserService.getRoleMap(spaceIds, loginUserId);
            voList.forEach(spaceVO -> {
                SpaceUserRoleEnum roleEnum = roleMap.get(spaceVO.getId());
                if (roleEnum != null) {
                    spaceVO.setCurrentUserRole(roleEnum.getValue());
                }
            });
        }
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public List<SpaceVO> toVOList(Collection<Space> spaceList, Long loginUserId) {
        if (CollUtil.isEmpty(spaceList)) {
            return new ArrayList<>();
        }
        List<Space> list = new ArrayList<>(spaceList);
        List<SpaceVO> voList = list.stream().map(SpaceVO::objToVo).collect(Collectors.toList());
        fillUsers(list, voList);
        if (loginUserId != null) {
            List<Long> spaceIds = voList.stream().map(SpaceVO::getId).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            Map<Long, SpaceUserRoleEnum> roleMap = spaceUserService.getRoleMap(spaceIds, loginUserId);
            voList.forEach(spaceVO -> {
                SpaceUserRoleEnum roleEnum = roleMap.get(spaceVO.getId());
                if (roleEnum != null) {
                    spaceVO.setCurrentUserRole(roleEnum.getValue());
                }
            });
        }
        return voList;
    }

    /**
     * 批量填充创建者信息。user-service 不可用时 Map 为空，各条记录的 user 保持 null。
     */
    private void fillUsers(List<Space> spaceList, List<SpaceVO> voList) {
        Set<Long> userIdSet = spaceList.stream()
                .map(Space::getUserId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userMap = CollUtil.isEmpty(userIdSet)
                ? new HashMap<>()
                : authService.toUserVOMap(userIdSet);
        for (int i = 0; i < voList.size(); i++) {
            SpaceVO spaceVO = voList.get(i);
            Space space = spaceList.get(i);
            spaceVO.setUser(userMap.get(space.getUserId()));
        }
    }
}
