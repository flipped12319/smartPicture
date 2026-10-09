package com.flipped.userservice.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginRequest implements Serializable {

    private String userAccount;
    private String userPassword;

    private static final long serialVersionUID = 3191241716373120793L;
}
