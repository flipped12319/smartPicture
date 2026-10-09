package com.flipped.spaceservice.common;

import com.flipped.spaceservice.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。字段与单体、user-service 保持一致（code/data/message），
 * 前端不需要区分是哪个服务返回的。
 */
@Data
public class BaseResponse<T> implements Serializable {

    private int code;
    private T data;
    private String message;

    /**
     * 无参构造：Jackson 反序列化需要。
     * <p>
     * ⚠️ 本服务是**调用方**（经 OpenFeign 调 user-service 的内部接口），
     * 所以这个构造器不是可选项：只有显式构造器时 Lombok 不会补默认构造器，
     * 缺了它 Feign 解码会直接报
     * "Cannot construct instance of BaseResponse (no Creators, like default constructor, exist)"。
     * 阶段 3b 在单体上踩过一次同样的坑，这里刻意保留注释以免再被删掉。
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
