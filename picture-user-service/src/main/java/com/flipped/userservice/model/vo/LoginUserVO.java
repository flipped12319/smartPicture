package com.flipped.userservice.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 已登录用户信息（脱敏，不含密码）
 */
@Data
public class LoginUserVO implements Serializable {

    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userProfile;
    private String userRole;
    private Date createTime;
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
