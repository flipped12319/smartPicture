package com.flipped.picturebackend.common;

import com.flipped.picturebackend.model.vo.LoginUserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private LoginUserVO userInfo;
    private String token;
}