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

    /**
     * 无参构造：Jackson 反序列化需要（Feign 要把本类作为返回值接收 user-service 的响应）。
     * 只有显式构造器时 Lombok 不会补默认构造器，缺了它反序列化会直接报
     * "cannot construct instance ... no Creators, like default constructor, exist"。
     */
    public BaseResponse() {
    }

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

