package com.flipped.picturebackend.common;

import com.flipped.picturebackend.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

@Data

//如果一个类 没有实现 Serializable，很多框架在传输对象时会报错。
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

