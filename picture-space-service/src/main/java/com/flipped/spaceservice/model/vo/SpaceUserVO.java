package com.flipped.spaceservice.model.vo;

import com.flipped.spaceservice.model.entity.SpaceUser;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 空间成员封装类
 */
@Data
public class SpaceUserVO implements Serializable {

    /**
     * id（空间成员记录 id）
     */
    private Long id;

    /**
     * 空间 id
     */
    private Long spaceId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 空间角色：0-只读 1-可上传 2-可编辑 3-管理员
     */
    private Integer spaceRole;

    /**
     * 状态：0-邀请中 1-已加入 2-已拒绝
     */
    private Integer status;

    /**
     * 邀请人 id
     */
    private Long inviterId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 成员用户信息
     */
    private UserVO user;

    /**
     * 邀请人信息
     */
    private UserVO inviter;

    /**
     * 空间信息（我的邀请列表需要展示空间名称）
     */
    private SpaceVO space;

    private static final long serialVersionUID = 1L;

    /**
     * 对象转封装类
     */
    public static SpaceUserVO objToVo(SpaceUser spaceUser) {
        if (spaceUser == null) {
            return null;
        }
        SpaceUserVO spaceUserVO = new SpaceUserVO();
        BeanUtils.copyProperties(spaceUser, spaceUserVO);
        return spaceUserVO;
    }
}
