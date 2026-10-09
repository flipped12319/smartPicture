package com.flipped.userservice.common;

import com.flipped.userservice.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。字段与单体保持一致（code/data/message），前端不需要区分是哪个服务返回的。
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;
    private T data;
    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
